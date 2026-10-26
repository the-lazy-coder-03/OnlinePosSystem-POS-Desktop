package za.co.onlinepos.server.local;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local elevated management only. Never a staff identity or a network reset API. */
public final class DeveloperRecovery {
  private static final String SCOPE = "developer-recovery";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final LocalDatabase.Identity identity;
  private final AuditService audit;

  public DeveloperRecovery(DataSource owner, LocalDatabase.Identity identity) {
    jdbc = new JdbcTemplate(owner);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(owner));
    this.identity = identity;
    audit = new AuditService(jdbc, za.co.onlinepos.installation.JsonFiles.JSON, identity);
  }

  private void lock() {
    if (!"pos_owner".equals(jdbc.queryForObject("SELECT current_user", String.class)))
      throw new SecurityException("Protected owner console required");
    if (!identity
        .clusterId()
        .equals(
            jdbc.queryForObject(
                "SELECT cluster_id FROM pos.store_identity WHERE branch_id=? AND installation_id=?",
                UUID.class,
                identity.branchId(),
                identity.installationId()))) throw new SecurityException("Wrong installation");
    jdbc.queryForObject(
        "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
        Long.class,
        identity.branchId());
    jdbc.update(
        "INSERT INTO pos.pin_throttle(branch_id,scope) VALUES (?,?) ON CONFLICT DO NOTHING",
        identity.branchId(),
        SCOPE);
  }

  private UUID owner() {
    var rows =
        jdbc.queryForList(
            "SELECT o.staff_id FROM pos.store_owner o JOIN pos.staff s ON s.branch_id=o.branch_id AND s.staff_id=o.staff_id WHERE o.branch_id=? AND s.enabled",
            UUID.class,
            identity.branchId());
    if (rows.size() != 1)
      throw new IllegalStateException("An enabled designated owner is required");
    return rows.getFirst();
  }

  private char[] bound(char[] pin) {
    return (identity.clusterId() + ":" + identity.installationId() + ":" + new String(pin))
        .toCharArray();
  }

  private void event(String action, UUID target, String reason) {
    audit.record(
        null,
        "protected-developer-console",
        action,
        target,
        null,
        Map.of("origin", "protected-developer-console", "reason", reason));
  }

  private void failure(String reason) {
    jdbc.update(
        "UPDATE pos.pin_throttle SET failures=CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN 1 ELSE failures+1 END, locked_until=CASE WHEN (CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN 1 ELSE failures+1 END)>=5 THEN clock_timestamp()+interval '5 minutes' ELSE locked_until END,window_start=CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN clock_timestamp() ELSE window_start END WHERE branch_id=? AND scope=?",
        identity.branchId(),
        SCOPE);
    event("DEVELOPER_RECOVERY_FAILED", identity.installationId(), reason);
  }

  /** Administrator failures are committed too; no supplied credential enters the audit. */
  public void deniedAdministrator() {
    transactions.executeWithoutResult(
        status -> {
          lock();
          failure("ADMIN_AUTHENTICATION_FAILED");
        });
  }

  public void deniedConfirmation() {
    transactions.executeWithoutResult(
        status -> {
          lock();
          failure("PIN_CONFIRMATION_MISMATCH");
        });
  }

  public void enrol(char[] pin, boolean rotate) {
    if (!new String(pin).matches("[0-9]{5}"))
      throw new IllegalArgumentException("Five digit developer PIN required");
    char[] secret = bound(pin);
    try {
      String hash = Passwords.hashRecovery(secret);
      transactions.executeWithoutResult(
          status -> {
            lock();
            owner();
            boolean exists =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pos.developer_recovery WHERE branch_id=?",
                        Integer.class,
                        identity.branchId())
                    == 1;
            if (exists && !rotate)
              throw new IllegalStateException("Recovery already enrolled; use rotation");
            jdbc.update(
                "INSERT INTO pos.developer_recovery(branch_id,installation_id,cluster_id,verifier,enabled) VALUES (?,?,?,?,true) ON CONFLICT(branch_id) DO UPDATE SET installation_id=excluded.installation_id,cluster_id=excluded.cluster_id,verifier=excluded.verifier,enabled=true,changed_at=clock_timestamp()",
                identity.branchId(),
                identity.installationId(),
                identity.clusterId(),
                hash);
            // Credential rotation cannot clear an active guessing cooldown.
            event(
                exists ? "DEVELOPER_RECOVERY_ROTATED" : "DEVELOPER_RECOVERY_ENROLLED",
                identity.installationId(),
                "LOCAL_ADMIN_ENROLMENT");
          });
    } finally {
      Arrays.fill(secret, '\0');
    }
  }

  public void disable() {
    transactions.executeWithoutResult(
        status -> {
          lock();
          jdbc.update(
              "UPDATE pos.developer_recovery SET enabled=false,changed_at=clock_timestamp() WHERE branch_id=?",
              identity.branchId());
          event("DEVELOPER_RECOVERY_DISABLED", identity.installationId(), "LOCAL_ADMIN_DISABLED");
        });
  }

  public void recover(char[] pin, char[] newPin, String pinKey) {
    char[] secret = bound(pin);
    try {
      Integer result =
          transactions.execute(
              status -> {
                lock();
                if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                        "SELECT locked_until>clock_timestamp() FROM pos.pin_throttle WHERE branch_id=? AND scope=?",
                        Boolean.class,
                        identity.branchId(),
                        SCOPE))) {
                  event("DEVELOPER_RECOVERY_FAILED", identity.installationId(), "COOLDOWN");
                  return 429;
                }
                var rows =
                    jdbc.queryForList(
                        "SELECT verifier FROM pos.developer_recovery WHERE branch_id=? AND installation_id=? AND cluster_id=? AND enabled",
                        identity.branchId(),
                        identity.installationId(),
                        identity.clusterId());
                String hash =
                    rows.isEmpty()
                        ? Passwords.hashRecovery("unavailable-recovery-credential".toCharArray())
                        : (String) rows.getFirst().get("verifier");
                boolean valid = Passwords.matches(secret, hash);
                if (!new String(pin).matches("[0-9]{5}") || !valid || rows.isEmpty()) {
                  failure("INVALID_OR_DISABLED_CREDENTIAL");
                  return 401;
                }
                var owners =
                    jdbc.queryForList(
                        "SELECT o.staff_id FROM pos.store_owner o JOIN pos.staff s ON s.staff_id=o.staff_id AND s.branch_id=o.branch_id WHERE o.branch_id=? AND s.enabled",
                        UUID.class,
                        identity.branchId());
                if (owners.size() != 1 || !new String(newPin).matches("[0-9]{6}")) {
                  failure("OWNER_OR_NEW_PIN_INVALID");
                  return 400;
                }
                UUID target = owners.getFirst();
                String lookup = ModuleAccess.lookup(pinKey, identity.branchId(), newPin);
                if (jdbc.queryForObject(
                        "SELECT count(*) FROM pos.staff_pin WHERE branch_id=? AND lookup=? AND staff_id<>?",
                        Integer.class,
                        identity.branchId(),
                        lookup,
                        target)
                    != 0) {
                  failure("NEW_PIN_UNAVAILABLE");
                  return 409;
                }
                jdbc.update(
                    "INSERT INTO pos.staff_pin VALUES (?,?,?,?) ON CONFLICT(branch_id,staff_id) DO UPDATE SET lookup=excluded.lookup,hash=excluded.hash",
                    identity.branchId(),
                    target,
                    lookup,
                    Passwords.hashPin(newPin));
                jdbc.update(
                    "UPDATE pos.staff SET credential_version=credential_version+1,revision=revision+1 WHERE branch_id=? AND staff_id=?",
                    identity.branchId(),
                    target);
                jdbc.update(
                    "DELETE FROM pos.staff_session WHERE branch_id=? AND staff_id=?",
                    identity.branchId(),
                    target);
                jdbc.update(
                    "DELETE FROM pos.module_session WHERE branch_id=? AND staff_id=?",
                    identity.branchId(),
                    target);
                jdbc.update(
                    "UPDATE pos.pin_throttle SET failures=0,locked_until=NULL,window_start=clock_timestamp() WHERE branch_id=? AND scope=?",
                    identity.branchId(),
                    SCOPE);
                event("OWNER_PIN_RECOVERED", target, "DEVELOPER_AUTHORIZED_RESET");
                return 200;
              });
      if (result != 200)
        throw new LocalFailure(
            result, result == 429 ? "Recovery cooldown active" : "Recovery refused");
    } finally {
      Arrays.fill(secret, '\0');
    }
  }
}
