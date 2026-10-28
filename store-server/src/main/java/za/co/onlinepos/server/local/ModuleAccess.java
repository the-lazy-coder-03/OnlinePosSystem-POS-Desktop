package za.co.onlinepos.server.local;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.PosModule;

@Service
@Profile({"installed", "local-store"})
public class ModuleAccess {
  public record Entry(String pin, PosModule module, UUID staffId) {
    public Entry(String pin, PosModule module) {
      this(pin, module, null);
    }

    @Override
    public String toString() {
      return "Entry[REDACTED]";
    }
  }

  public record Session(
      UUID staffId,
      String username,
      PosModule module,
      Set<String> permissions,
      String terminal,
      long credentialVersion) {}

  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final TransactionTemplate transactions;
  private final LocalDatabase.Identity identity;
  private final String key;
  private final String dummy = Passwords.hash("module-dummy-secret".toCharArray());

  public ModuleAccess(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      LocalDatabase.Identity identity,
      Environment env) {
    this.jdbc = jdbc;
    this.audit =
        new AuditService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), identity);
    this.transactions = transactions;
    this.identity = identity;
    key = env.getProperty("pos.pin-key", "");
  }

  public static String lookup(String key, int branch, char[] pin) {
    try {
      if (Base64.getDecoder().decode(key).length != 32)
        throw new IllegalArgumentException("PIN key unavailable");
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal((branch + ":" + new String(pin)).getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public Set<String> permissions(UUID staff) {
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT pos.admin_permissions(?,?)", String.class, identity.branchId(), staff));
  }

  public Map<String, Object> enter(Entry request, String terminal) {
    return enter(request, terminal, false);
  }

  public Map<String, Object> enterOwner(Entry request, String terminal) {
    return enter(request, terminal, true);
  }

  private Map<String, Object> enter(Entry request, String terminal, boolean ownerOnly) {
    if (key.isBlank()) throw new LocalFailure(503, "PIN access has not been provisioned");
    if (request.module() == null) throw new LocalFailure(400, "Module required");
    char[] pin = request.pin() == null ? new char[0] : request.pin().toCharArray();
    try {
      var result =
          transactions.execute(
              status -> {
                jdbc.queryForObject(
                    "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
                    Long.class,
                    identity.branchId());
                // A branch row serializes guessing counters across terminals and service instances.
                lockScope("branch");
                lockScope("terminal:" + terminal);
                if (locked("branch") || locked("terminal:" + terminal))
                  return Map.<String, Object>of("failure", 429);
                var rows =
                    pin.length == 6 && new String(pin).matches("[0-9]{6}")
                        ? jdbc.queryForList(
                            "SELECT s.staff_id,s.username,s.enabled,s.credential_version,p.verifier AS hash FROM pos.staff_auth_method p JOIN pos.staff s ON s.staff_id=p.staff_id AND s.branch_id=p.branch_id WHERE p.branch_id=? AND p.lookup=? AND p.method='PIN' AND p.account_type='LOCAL' AND s.account_type='LOCAL' AND p.enabled AND p.credential_version=s.credential_version",
                            identity.branchId(),
                            lookup(key, identity.branchId(), pin))
                        : List.<Map<String, Object>>of();
                var row =
                    za.co.onlinepos.server.auth.PinIdentitySelection.select(
                        rows, request.staffId());
                boolean valid =
                    new za.co.onlinepos.server.auth.PinAuthenticationProvider()
                        .verify(
                            row == null ? null : (UUID) row.get("staff_id"),
                            pin,
                            row == null ? dummy : (String) row.get("hash"));
                boolean designatedOwner =
                    row != null
                        && jdbc.queryForObject(
                                "SELECT count(*) FROM pos.store_owner WHERE branch_id=? AND staff_id=?",
                                Integer.class,
                                identity.branchId(),
                                row.get("staff_id"))
                            == 1;
                if (!valid
                    || row == null
                    || !Boolean.TRUE.equals(row.get("enabled"))
                    || (ownerOnly && !designatedOwner)) {
                  fail("branch", 30);
                  fail("terminal:" + terminal, 5);
                  if (ownerOnly)
                    audit.record(
                        null,
                        terminal,
                        "OWNER_PIN_FAILED",
                        identity.clusterId(),
                        null,
                        Map.of("reason", "INVALID_OWNER_CREDENTIAL"));
                  return Map.<String, Object>of("failure", 401);
                }
                UUID staff = (UUID) row.get("staff_id");
                var permissions = permissions(staff);
                if (!permissions.contains(request.module().permission()))
                  return Map.<String, Object>of("failure", 403);
                jdbc.update(
                    "UPDATE pos.pin_throttle SET failures=0,locked_until=NULL,window_start=clock_timestamp() WHERE branch_id=? AND scope=?",
                    identity.branchId(),
                    "terminal:" + terminal);
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                jdbc.update("DELETE FROM pos.module_session WHERE expires_at<clock_timestamp()");
                jdbc.update(
                    "INSERT INTO pos.module_session(token_hash,branch_id,staff_id,terminal_identity,module,credential_version,expires_at) VALUES (?,?,?,?,?,?,clock_timestamp()+interval '5 minutes')",
                    CommandJournal.digest(token),
                    identity.branchId(),
                    staff,
                    terminal,
                    request.module().name(),
                    row.get("credential_version"));
                if (ownerOnly)
                  jdbc.update(
                      "UPDATE pos.module_session SET owner_verified_at=clock_timestamp() WHERE token_hash=?",
                      CommandJournal.digest(token));
                if (ownerOnly)
                  audit.record(
                      staff,
                      terminal,
                      "OWNER_PIN_SUCCEEDED",
                      staff,
                      null,
                      Map.of("reason", "OWNER_IDENTITY_VERIFIED"));
                if (!ownerOnly)
                  audit.record(
                      staff,
                      terminal,
                      "STAFF_LOGIN",
                      staff,
                      null,
                      Map.of("module", request.module().name()));
                return Map.<String, Object>of(
                    "token",
                    token,
                    "staffId",
                    staff,
                    "username",
                    row.get("username"),
                    "module",
                    request.module(),
                    "permissions",
                    permissions,
                    "expiresInSeconds",
                    300);
              });
      if (result.containsKey("failure"))
        throw new LocalFailure((Integer) result.get("failure"), "Module access refused");
      return result;
    } finally {
      Arrays.fill(pin, '\0');
    }
  }

  public void deniedAuditSettings(String terminal) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
              Long.class,
              identity.branchId());
          String scope = "audit-denied:" + terminal;
          lockScope(scope);
          if (locked(scope)) return;
          fail(scope, 5);
          audit.record(
              null,
              terminal,
              "AUDIT_ACCESS_REFUSED",
              identity.clusterId(),
              null,
              Map.of("reason", "INVALID_SESSION"));
        });
  }

  private void lockScope(String scope) {
    jdbc.update(
        "INSERT INTO pos.pin_throttle(branch_id,scope) VALUES (?,?) ON CONFLICT DO NOTHING",
        identity.branchId(),
        scope);
    jdbc.queryForObject(
        "SELECT failures FROM pos.pin_throttle WHERE branch_id=? AND scope=? FOR UPDATE",
        Integer.class,
        identity.branchId(),
        scope);
  }

  private boolean locked(String scope) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT locked_until>clock_timestamp() FROM pos.pin_throttle WHERE branch_id=? AND scope=?",
            Boolean.class,
            identity.branchId(),
            scope));
  }

  private void fail(String scope, int limit) {
    jdbc.update(
        "UPDATE pos.pin_throttle SET failures=CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN 1 ELSE failures+1 END, locked_until=CASE WHEN (CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN 1 ELSE failures+1 END)>=? THEN clock_timestamp()+interval '5 minutes' ELSE NULL END, window_start=CASE WHEN window_start<clock_timestamp()-interval '5 minutes' THEN clock_timestamp() ELSE window_start END WHERE branch_id=? AND scope=?",
        limit,
        identity.branchId(),
        scope);
  }

  public Session authenticate(String token, String terminal, boolean touch) {
    if (token == null || token.length() != 43)
      throw new LocalFailure(401, "Module session required");
    var rows =
        jdbc.queryForList(
            "SELECT t.staff_id,s.username,t.module,t.credential_version FROM pos.module_session t JOIN pos.staff s ON s.staff_id=t.staff_id AND s.branch_id=t.branch_id WHERE t.token_hash=? AND t.branch_id=? AND t.terminal_identity=? AND t.expires_at>clock_timestamp() AND s.enabled AND s.account_type='LOCAL' AND t.credential_version=s.credential_version",
            CommandJournal.digest(token),
            identity.branchId(),
            terminal);
    if (rows.isEmpty()) throw new LocalFailure(401, "Module session expired");
    var row = rows.getFirst();
    var module = PosModule.valueOf((String) row.get("module"));
    var permissions = permissions((UUID) row.get("staff_id"));
    if (!permissions.contains(module.permission()))
      throw new LocalFailure(403, "Module access refused");
    if (touch)
      jdbc.update(
          "UPDATE pos.module_session SET expires_at=clock_timestamp()+interval '5 minutes' WHERE token_hash=?",
          CommandJournal.digest(token));
    return new Session(
        (UUID) row.get("staff_id"),
        (String) row.get("username"),
        module,
        permissions,
        terminal,
        ((Number) row.get("credential_version")).longValue());
  }

  public void discardApprovalSession(String token) {
    jdbc.update("DELETE FROM pos.module_session WHERE token_hash=?", CommandJournal.digest(token));
  }

  public void logout(String token) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
              Long.class,
              identity.branchId());
          var rows =
              jdbc.queryForList(
                  "SELECT staff_id,terminal_identity FROM pos.module_session WHERE token_hash=? AND branch_id=?",
                  CommandJournal.digest(token),
                  identity.branchId());
          if (!rows.isEmpty()) {
            var row = rows.getFirst();
            audit.record(
                (UUID) row.get("staff_id"),
                (String) row.get("terminal_identity"),
                "STAFF_LOGOUT",
                (UUID) row.get("staff_id"),
                null,
                Map.of());
            discardApprovalSession(token);
          }
        });
  }
}
