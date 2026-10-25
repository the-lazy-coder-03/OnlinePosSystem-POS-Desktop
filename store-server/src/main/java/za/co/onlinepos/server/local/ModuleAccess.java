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
  public record Entry(String pin, PosModule module) {
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
            "SELECT DISTINCT p.permission FROM pos.staff_role r JOIN pos.role_permission p ON p.branch_id=r.branch_id AND p.role_name=r.role_name WHERE r.branch_id=? AND r.staff_id=?",
            String.class,
            identity.branchId(),
            staff));
  }

  public Map<String, Object> enter(Entry request, String terminal) {
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
                            "SELECT s.staff_id,s.username,s.enabled,s.credential_version,p.hash FROM pos.staff_pin p JOIN pos.staff s ON s.staff_id=p.staff_id AND s.branch_id=p.branch_id WHERE p.branch_id=? AND p.lookup=?",
                            identity.branchId(),
                            lookup(key, identity.branchId(), pin))
                        : List.<Map<String, Object>>of();
                var row = rows.isEmpty() ? null : rows.getFirst();
                boolean valid =
                    Passwords.matches(pin, row == null ? dummy : (String) row.get("hash"));
                if (!valid || row == null || !Boolean.TRUE.equals(row.get("enabled"))) {
                  fail("branch", 30);
                  fail("terminal:" + terminal, 5);
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
                    "INSERT INTO pos.module_session VALUES (?,?,?,?,?,?,clock_timestamp()+interval '5 minutes')",
                    CommandJournal.digest(token),
                    identity.branchId(),
                    staff,
                    terminal,
                    request.module().name(),
                    row.get("credential_version"));
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
            "SELECT t.staff_id,s.username,t.module,t.credential_version FROM pos.module_session t JOIN pos.staff s ON s.staff_id=t.staff_id AND s.branch_id=t.branch_id WHERE t.token_hash=? AND t.branch_id=? AND t.terminal_identity=? AND t.expires_at>clock_timestamp() AND s.enabled AND t.credential_version=s.credential_version",
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

  public void logout(String token) {
    jdbc.update("DELETE FROM pos.module_session WHERE token_hash=?", CommandJournal.digest(token));
  }
}
