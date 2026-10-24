package za.co.onlinepos.server.local;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile({"installed", "local-store"})
public class StaffService {
  public record Actor(UUID staffId, String role, String terminal, long credentialVersion) {
    public void manager() {
      if (!role.equals("MANAGER")) throw new LocalFailure(403, "Manager permission required");
    }
  }

  public record Login(String username, String password) {
    @Override
    public String toString() {
      return "Login[REDACTED]";
    }
  }

  public record StaffEdit(
      UUID staffId,
      String username,
      String role,
      boolean enabled,
      long expectedRevision,
      String password) {
    @Override
    public String toString() {
      return "StaffEdit[REDACTED]";
    }
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;
  private final String dummyHash = Passwords.hash("invalid-login-placeholder".toCharArray());

  public StaffService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      CommandJournal journal,
      LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.journal = journal;
    this.identity = identity;
  }

  public Map<String, Object> login(Login request, String terminal) {
    if (request.password() == null
        || request.password().length() > 128
        || request.username() == null
        || request.username().length() > 64) throw new LocalFailure(401, "Login refused");
    char[] password = request.password().toCharArray();
    try {
      Map<String, Object> result =
          transactions.execute(
              status -> {
                var rows =
                    jdbc.queryForList(
                        "SELECT * FROM pos.staff WHERE branch_id=? AND username=? FOR UPDATE",
                        identity.branchId(),
                        request.username().toLowerCase(java.util.Locale.ROOT));
                if (rows.isEmpty()) {
                  Passwords.matches(password, dummyHash);
                  return null;
                }
                var row = rows.getFirst();
                UUID id = (UUID) row.get("staff_id");
                java.sql.Timestamp locked = (java.sql.Timestamp) row.get("locked_until");
                if (!(Boolean) row.get("enabled")
                    || (locked != null && locked.toInstant().isAfter(java.time.Instant.now())))
                  return null;
                if (!Passwords.matches(password, (String) row.get("password_hash"))) {
                  jdbc.update(
                      "UPDATE pos.staff SET failed_attempts=failed_attempts+1,locked_until=CASE WHEN failed_attempts>=4 THEN clock_timestamp()+interval '5 minutes' ELSE NULL END WHERE staff_id=?",
                      id);
                  return null;
                }
                jdbc.update(
                    "UPDATE pos.staff SET failed_attempts=0,locked_until=NULL WHERE staff_id=?",
                    id);
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                jdbc.update(
                    "INSERT INTO pos.staff_session(token_hash,branch_id,staff_id,terminal_identity,credential_version,expires_at) VALUES (?,?,?,?,?,clock_timestamp()+interval '8 hours')",
                    CommandJournal.digest(token),
                    identity.branchId(),
                    id,
                    terminal,
                    row.get("credential_version"));
                jdbc.update("DELETE FROM pos.staff_session WHERE expires_at<clock_timestamp()");
                return Map.of(
                    "token",
                    token,
                    "staffId",
                    id,
                    "role",
                    row.get("role"),
                    "expiresInSeconds",
                    28800);
              });
      if (result == null) throw new LocalFailure(401, "Login refused");
      return result;
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  public Actor authenticate(String token, String terminal) {
    if (token == null || token.length() != 43)
      throw new LocalFailure(401, "Staff session required");
    var actors =
        jdbc.query(
            "SELECT s.staff_id,s.role,s.credential_version FROM pos.staff_session t JOIN pos.staff s ON s.staff_id=t.staff_id AND s.branch_id=t.branch_id WHERE t.token_hash=? AND t.branch_id=? AND t.terminal_identity=? AND t.expires_at>clock_timestamp() AND t.credential_version=s.credential_version AND s.enabled",
            (rs, n) ->
                new Actor(rs.getObject(1, UUID.class), rs.getString(2), terminal, rs.getLong(3)),
            CommandJournal.digest(token),
            identity.branchId(),
            terminal);
    if (actors.isEmpty()) throw new LocalFailure(401, "Staff session required");
    return actors.getFirst();
  }

  public void logout(String token) {
    jdbc.update("DELETE FROM pos.staff_session WHERE token_hash=?", CommandJournal.digest(token));
  }

  public List<Map<String, Object>> list(Actor actor) {
    actor.manager();
    return jdbc.queryForList(
        "SELECT staff_id,username,role,enabled,revision FROM pos.staff WHERE branch_id=? ORDER BY username",
        identity.branchId());
  }

  public Map<String, Object> edit(Actor actor, StaffEdit edit) {
    actor.manager();
    CommandJournal.requireUuid(edit.staffId());
    if (edit.username() == null
        || !edit.username().matches("[a-z0-9._-]{3,64}")
        || !List.of("MANAGER", "CASHIER").contains(edit.role())
        || edit.expectedRevision() < 0) throw new LocalFailure(400, "Invalid staff fields");
    String hash = null;
    if (edit.password() != null) {
      char[] pass = edit.password().toCharArray();
      try {
        hash = Passwords.hash(pass);
      } finally {
        Arrays.fill(pass, '\0');
      }
    }
    final String newHash = hash;
    return transactions.execute(
        status -> {
          journal.lockStream();
          journal.requireActor(actor);
          var previous =
              jdbc.queryForList(
                  "SELECT role,enabled,revision FROM pos.staff WHERE staff_id=? AND branch_id=?",
                  edit.staffId(),
                  identity.branchId());
          long revision;
          if (previous.isEmpty()) {
            if (newHash == null || edit.expectedRevision() != 0)
              throw new LocalFailure(400, "New staff require a password and revision zero");
            jdbc.update(
                "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role,enabled) VALUES (?,?,?,?,?,?)",
                edit.staffId(),
                identity.branchId(),
                edit.username(),
                newHash,
                edit.role(),
                edit.enabled());
            revision = 0;
          } else {
            var old = previous.getFirst();
            if (((Number) old.get("revision")).longValue() != edit.expectedRevision())
              throw new LocalFailure(409, "Staff revision changed");
            if (old.get("role").equals("MANAGER")
                && (Boolean) old.get("enabled")
                && (!edit.enabled() || !edit.role().equals("MANAGER"))
                && jdbc.queryForObject(
                        "SELECT count(*) FROM pos.staff WHERE enabled AND role='MANAGER'",
                        Integer.class)
                    == 1) throw new LocalFailure(409, "The last enabled manager cannot be removed");
            revision = edit.expectedRevision() + 1;
            jdbc.update(
                "UPDATE pos.staff SET username=?,role=?,enabled=?,password_hash=COALESCE(?,password_hash),credential_version=credential_version+1,failed_attempts=0,locked_until=NULL,revision=? WHERE staff_id=? AND branch_id=?",
                edit.username(),
                edit.role(),
                edit.enabled(),
                newHash,
                revision,
                edit.staffId(),
                identity.branchId());
            jdbc.update("DELETE FROM pos.staff_session WHERE staff_id=?", edit.staffId());
          }
          Map<String, Object> result =
              Map.of(
                  "staffId",
                  edit.staffId(),
                  "revision",
                  revision,
                  "role",
                  edit.role(),
                  "enabled",
                  edit.enabled());
          journal.event(actor, "STAFF_CHANGED", edit.staffId(), revision, null, result);
          return result;
        });
  }
}
