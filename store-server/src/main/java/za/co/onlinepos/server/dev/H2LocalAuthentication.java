package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.server.auth.PinAuthenticationProvider;
import za.co.onlinepos.server.auth.PinIdentitySelection;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;
import za.co.onlinepos.server.local.Passwords;

/** Persistent LOCAL-account core. Not an HTTP bypass for the current development actor grid. */
@Service
@Profile("dev")
public class H2LocalAuthentication {
  private final DevModuleStore store;
  private final String key;
  private final Clock clock;
  private final String dummy = Passwords.hashPin("000000".toCharArray());

  @org.springframework.beans.factory.annotation.Autowired
  public H2LocalAuthentication(DevModuleStore store, DevDatabase.Identity identity) {
    this(store, loadKey(store, identity), Clock.systemUTC());
  }

  H2LocalAuthentication(DevModuleStore store, String key, Clock clock) {
    this.store = store;
    this.key = key;
    this.clock = clock;
  }

  private static String loadKey(DevModuleStore store, DevDatabase.Identity identity) {
    try {
      var file = identity.root().resolve("local-pin.key");
      if (Files.isSymbolicLink(file)) throw new IllegalStateException("Invalid local PIN key file");
      if (!Files.exists(file)) {
        if (store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.local_staff", Integer.class)
            > 0)
          throw new IllegalStateException("Restore the original local PIN key with the database");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        try {
          Files.writeString(
              file, Base64.getEncoder().encodeToString(bytes), StandardOpenOption.CREATE_NEW);
        } finally {
          Arrays.fill(bytes, (byte) 0);
        }
      }
      DevDatabase.protect(file, false);
      String key = Files.readString(file);
      if (Base64.getDecoder().decode(key).length != 32)
        throw new IllegalStateException("Invalid local PIN key");
      return key;
    } catch (Exception failure) {
      throw new IllegalStateException("Protected local PIN key unavailable");
    }
  }

  public record Session(
      String token,
      UUID staffId,
      String username,
      PosModule module,
      Set<String> permissions,
      boolean ownerVerified) {
    @Override
    public String toString() {
      return "LocalSession[REDACTED]";
    }
  }

  private static void pin(char[] value) {
    if (value == null || value.length != 6) throw new LocalFailure(400, "Six digit PIN required");
    for (char digit : value)
      if (digit < '0' || digit > '9') throw new LocalFailure(400, "Six digit PIN required");
  }

  private static void username(String name) {
    if (name == null || !name.matches("[a-z0-9._-]{3,64}"))
      throw new LocalFailure(400, "Invalid local username");
  }

  private OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private String lookup(char[] value) {
    return ModuleAccess.lookup(key, store.branchId(), value);
  }

  private Set<String> permissions(String role) {
    if (role.equals("CASHIER")) return Set.of(PosModule.SALES_ORDERS.permission());
    var all = new java.util.HashSet<String>(Customers.PERMISSIONS);
    for (var module : PosModule.values()) all.add(module.permission());
    all.addAll(Set.of("staff.manage", "rbac.manage", "catalogue.manage", "audit.view"));
    return Set.copyOf(all);
  }

  /** Trusted deployment setup only; no public endpoint exposes this operation. */
  public JsonNode bootstrapOwner(UUID command, String username, char[] suppliedPin) {
    username(username);
    pin(suppliedPin);
    CommandJournal.requireUuid(command);
    char[] value = suppliedPin.clone();
    try {
      String proof = Passwords.commandProof(value, command);
      return store.command(
          command,
          "LOCAL_OWNER_SETUP",
          Map.of("username", username, "proof", proof),
          () -> {
            int branch = store.branchId();
            if (store.jdbc.queryForObject(
                    "SELECT count(*) FROM dev_meta.local_staff WHERE branch_id=?",
                    Integer.class,
                    branch)
                > 0)
              throw new LocalFailure(
                  409, "Local accounts already exist; use the authenticated Owner");
            UUID id = UUID.randomUUID();
            insert(id, username, "MANAGER", value);
            store.jdbc.update("INSERT INTO dev_meta.local_owner VALUES(?,?)", branch, id);
            store.event(
                command, "RBAC_SET_OWNER", id, 0, Map.of("staffId", id, "accountType", "LOCAL"));
            return store.tree(Map.of("staffId", id, "username", username, "accountType", "LOCAL"));
          });
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  private void insert(UUID id, String username, String role, char[] value) {
    int branch = store.branchId();
    if (store.jdbc.queryForObject(
            "SELECT count(*) FROM dev_meta.local_auth_method WHERE branch_id=? AND lookup=?",
            Integer.class,
            branch,
            lookup(value))
        > 0) throw new LocalFailure(409, "This PIN is already assigned in this store");
    store.jdbc.update(
        "INSERT INTO dev_meta.local_staff(staff_id,branch_id,username,role_name) VALUES(?,?,?,?)",
        id,
        branch,
        username,
        role);
    store.jdbc.update(
        "INSERT INTO dev_meta.local_auth_method(credential_id,branch_id,staff_id,method,verifier,lookup,credential_version) VALUES(?,?,?,'PIN',?,?,0)",
        UUID.randomUUID(),
        branch,
        id,
        Passwords.hashPin(value),
        lookup(value));
  }

  public JsonNode createStaff(
      String ownerToken,
      String terminal,
      UUID command,
      String username,
      String role,
      char[] suppliedPin) {
    username(username);
    pin(suppliedPin);
    CommandJournal.requireUuid(command);
    if (!Set.of("MANAGER", "CASHIER").contains(role))
      throw new LocalFailure(400, "Invalid local role");
    char[] value = suppliedPin.clone();
    try {
      return store.tx.execute(
          status -> {
            store.lock();
            requireOwner(ownerToken, terminal);
            return store.command(
                command,
                "LOCAL_STAFF_CREATE",
                Map.of(
                    "username",
                    username,
                    "role",
                    role,
                    "proof",
                    Passwords.commandProof(value, command)),
                () -> {
                  UUID id = UUID.randomUUID();
                  insert(id, username, role, value);
                  store.event(
                      command,
                      "STAFF_CHANGED",
                      id,
                      0,
                      Map.of("staffId", id, "accountType", "LOCAL"));
                  return store.tree(
                      Map.of("staffId", id, "username", username, "accountType", "LOCAL"));
                });
          });
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  public JsonNode editStaff(
      String ownerToken,
      String terminal,
      UUID command,
      UUID staffId,
      long expectedRevision,
      String username,
      String role,
      boolean enabled,
      char[] newPin) {
    username(username);
    CommandJournal.requireUuid(command);
    CommandJournal.requireUuid(staffId);
    if (expectedRevision < 0 || !Set.of("MANAGER", "CASHIER").contains(role))
      throw new LocalFailure(400, "Invalid staff edit");
    if (newPin != null) pin(newPin);
    char[] value = newPin == null ? null : newPin.clone();
    try {
      return store.tx.execute(
          status -> {
            store.lock();
            requireOwner(ownerToken, terminal);
            return store.command(
                command,
                "LOCAL_STAFF_EDIT",
                Map.of(
                    "staffId",
                    staffId,
                    "revision",
                    expectedRevision,
                    "username",
                    username,
                    "role",
                    role,
                    "enabled",
                    enabled,
                    "proof",
                    value == null ? "UNCHANGED" : Passwords.commandProof(value, command)),
                () -> {
                  int branch = store.branchId();
                  if (store.jdbc.queryForObject(
                              "SELECT count(*) FROM dev_meta.local_owner WHERE branch_id=? AND staff_id=?",
                              Integer.class,
                              branch,
                              staffId)
                          > 0
                      && (!enabled || !role.equals("MANAGER")))
                    throw new LocalFailure(
                        409, "The designated Owner must remain enabled with management access");
                  if (value != null
                      && store.jdbc.queryForObject(
                              "SELECT count(*) FROM dev_meta.local_auth_method WHERE branch_id=? AND lookup=? AND staff_id<>?",
                              Integer.class,
                              branch,
                              lookup(value),
                              staffId)
                          > 0)
                    throw new LocalFailure(409, "This PIN is already assigned in this store");
                  int updated =
                      store.jdbc.update(
                          "UPDATE dev_meta.local_staff SET username=?,role_name=?,enabled=?,revision=revision+1,credential_version=credential_version+1 WHERE branch_id=? AND staff_id=? AND revision=?",
                          username,
                          role,
                          enabled,
                          branch,
                          staffId,
                          expectedRevision);
                  if (updated != 1) throw new LocalFailure(409, "Staff changed; reload and review");
                  store.jdbc.update(
                      "UPDATE dev_meta.local_auth_method SET enabled=?,credential_version=(SELECT credential_version FROM dev_meta.local_staff WHERE staff_id=?) WHERE staff_id=?",
                      enabled,
                      staffId,
                      staffId);
                  if (value != null)
                    store.jdbc.update(
                        "UPDATE dev_meta.local_auth_method SET verifier=?,lookup=? WHERE staff_id=? AND method='PIN'",
                        Passwords.hashPin(value),
                        lookup(value),
                        staffId);
                  store.jdbc.update(
                      "DELETE FROM dev_meta.local_module_session WHERE staff_id=?", staffId);
                  store.event(
                      command,
                      "ADMIN_STAFF_CHANGED",
                      staffId,
                      expectedRevision + 1,
                      Map.of("staffId", staffId, "enabled", enabled, "accountType", "LOCAL"));
                  if (value != null)
                    store.event(
                        command,
                        "ADMIN_PIN_CHANGED",
                        staffId,
                        expectedRevision + 1,
                        Map.of("staffId", staffId, "accountType", "LOCAL"));
                  return store.tree(
                      Map.of(
                          "staffId",
                          staffId,
                          "revision",
                          expectedRevision + 1,
                          "enabled",
                          enabled,
                          "username",
                          username,
                          "role",
                          role));
                });
          });
    } finally {
      if (value != null) Arrays.fill(value, '\0');
    }
  }

  public Session enter(
      char[] suppliedPin,
      UUID selectedStaff,
      PosModule module,
      String terminal,
      boolean ownerOnly) {
    if (module == null || terminal == null || terminal.isBlank() || terminal.length() > 512)
      throw new LocalFailure(400, "Module and terminal required");
    char[] value = suppliedPin == null ? new char[0] : suppliedPin.clone();
    try {
      var result =
          store.tx.execute(
              status -> {
                store.lock();
                int branch = store.branchId();
                Instant now = clock.instant();
                String terminalScope = "terminal:" + terminal;
                scope(branch, "branch", now);
                scope(branch, terminalScope, now);
                if (locked(branch, "branch", now) || locked(branch, terminalScope, now)) {
                  store.jdbc.update(
                      "UPDATE dev_meta.local_pin_throttle SET cooldown_attempts=cooldown_attempts+1 WHERE branch_id=? AND scope IN (?,?)",
                      branch,
                      "branch",
                      terminalScope);
                  return Map.of("failure", 429);
                }
                var rows =
                    store.jdbc.queryForList(
                        "SELECT s.staff_id,s.username,s.enabled,s.role_name,s.credential_version,a.verifier AS hash FROM dev_meta.local_staff s JOIN dev_meta.local_auth_method a USING(branch_id,staff_id) WHERE s.branch_id=? AND a.lookup=? AND a.method='PIN' AND a.enabled AND a.credential_version=s.credential_version",
                        branch,
                        lookup(value));
                var row = PinIdentitySelection.select(rows, selectedStaff);
                boolean valid =
                    new PinAuthenticationProvider()
                        .verify(
                            row == null ? null : (UUID) row.get("staff_id"),
                            value,
                            row == null ? dummy : (String) row.get("hash"));
                boolean owner =
                    row != null
                        && store.jdbc.queryForObject(
                                "SELECT count(*) FROM dev_meta.local_owner WHERE branch_id=? AND staff_id=?",
                                Integer.class,
                                branch,
                                row.get("staff_id"))
                            == 1;
                if (!valid || row == null || (ownerOnly && !owner)) {
                  fail(branch, "branch", 30, now);
                  fail(branch, terminalScope, 5, now);
                  return Map.of("failure", 401);
                }
                var rights = permissions((String) row.get("role_name"));
                if (!rights.contains(module.permission())) return Map.of("failure", 403);
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                Arrays.fill(bytes, (byte) 0);
                store.jdbc.update(
                    "DELETE FROM dev_meta.local_module_session WHERE expires_at<=?", at(now));
                store.jdbc.update(
                    "INSERT INTO dev_meta.local_module_session VALUES(?,?,?,?,?,?,?,?)",
                    CommandJournal.digest(token),
                    branch,
                    row.get("staff_id"),
                    terminal,
                    module.name(),
                    row.get("credential_version"),
                    at(now.plusSeconds(ownerOnly ? 60 : 300)),
                    ownerOnly);
                store.jdbc.update(
                    "UPDATE dev_meta.local_pin_throttle SET failures=0,locked_until=NULL,window_start=? WHERE branch_id=? AND scope=?",
                    at(now),
                    branch,
                    terminalScope);
                store.event(
                    UUID.randomUUID(),
                    ownerOnly ? "OWNER_PIN_SUCCEEDED" : "STAFF_LOGIN",
                    (UUID) row.get("staff_id"),
                    0,
                    Map.of(
                        "staffId",
                        row.get("staff_id"),
                        "module",
                        module.name(),
                        "accountType",
                        "LOCAL"));
                return Map.of(
                    "session",
                    new Session(
                        token,
                        (UUID) row.get("staff_id"),
                        (String) row.get("username"),
                        module,
                        rights,
                        ownerOnly));
              });
      if (result.containsKey("failure")) {
        // Counters have committed. A rejected owner's audit transaction cannot roll them back.
        if (ownerOnly && (Integer) result.get("failure") == 401)
          store.tx.executeWithoutResult(
              status -> {
                store.lock();
                UUID storeId =
                    store.jdbc.queryForObject(
                        "SELECT store_uuid FROM public.branch WHERE branch_id=?",
                        UUID.class,
                        store.branchId());
                store.event(
                    UUID.randomUUID(),
                    "OWNER_PIN_FAILED",
                    storeId,
                    0,
                    Map.of("reason", "INVALID_OWNER_CREDENTIAL"));
              });
        throw new LocalFailure((Integer) result.get("failure"), "Local access refused");
      }
      return (Session) result.get("session");
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  public Session authenticate(String token, String terminal, PosModule module) {
    return store.tx.execute(
        status -> {
          store.lock();
          if (token == null || token.length() != 43)
            throw new LocalFailure(401, "Local module session required");
          var rows =
              store.jdbc.queryForList(
                  "SELECT s.staff_id,s.username,s.role_name,t.owner_verified FROM dev_meta.local_module_session t JOIN dev_meta.local_staff s USING(branch_id,staff_id) WHERE t.branch_id=? AND t.token_hash=? AND t.terminal_identity=? AND t.module=? AND t.expires_at>? AND t.credential_version=s.credential_version AND s.enabled",
                  store.branchId(),
                  CommandJournal.digest(token),
                  terminal,
                  module.name(),
                  at(clock.instant()));
          if (rows.size() != 1) throw new LocalFailure(401, "Local module session required");
          var row = rows.getFirst();
          return new Session(
              token,
              (UUID) row.get("staff_id"),
              (String) row.get("username"),
              module,
              permissions((String) row.get("role_name")),
              (Boolean) row.get("owner_verified"));
        });
  }

  private Session requireOwner(String token, String terminal) {
    var session = authenticate(token, terminal, PosModule.ADMINISTRATION);
    if (!session.ownerVerified()
        || store.jdbc.queryForObject(
                "SELECT count(*) FROM dev_meta.local_owner WHERE branch_id=? AND staff_id=?",
                Integer.class,
                store.branchId(),
                session.staffId())
            != 1) throw new LocalFailure(403, "Fresh designated Owner verification required");
    return session;
  }

  public void logout(String token, String terminal, PosModule module) {
    store.tx.executeWithoutResult(
        status -> {
          store.lock();
          var session = authenticate(token, terminal, module);
          store.jdbc.update(
              "DELETE FROM dev_meta.local_module_session WHERE token_hash=?",
              CommandJournal.digest(token));
          store.event(
              UUID.randomUUID(),
              "STAFF_LOGOUT",
              session.staffId(),
              0,
              Map.of("staffId", session.staffId(), "module", module.name()));
        });
  }

  private void scope(int branch, String scope, Instant now) {
    if (store.jdbc.queryForObject(
            "SELECT count(*) FROM dev_meta.local_pin_throttle WHERE branch_id=? AND scope=?",
            Integer.class,
            branch,
            scope)
        == 0)
      store.jdbc.update(
          "INSERT INTO dev_meta.local_pin_throttle(branch_id,scope,window_start) VALUES(?,?,?)",
          branch,
          scope,
          at(now));
  }

  private boolean locked(int branch, String scope, Instant now) {
    var locked =
        store.jdbc.queryForObject(
            "SELECT locked_until FROM dev_meta.local_pin_throttle WHERE branch_id=? AND scope=?",
            OffsetDateTime.class,
            branch,
            scope);
    return locked != null && locked.toInstant().isAfter(now);
  }

  private void fail(int branch, String scope, int limit, Instant now) {
    var row =
        store.jdbc.queryForMap(
            "SELECT failures,window_start FROM dev_meta.local_pin_throttle WHERE branch_id=? AND scope=?",
            branch,
            scope);
    int failures =
        ((OffsetDateTime) row.get("window_start")).toInstant().isBefore(now.minusSeconds(300))
            ? 1
            : ((Number) row.get("failures")).intValue() + 1;
    store.jdbc.update(
        "UPDATE dev_meta.local_pin_throttle SET failures=?,window_start=?,locked_until=? WHERE branch_id=? AND scope=?",
        failures,
        failures == 1 ? at(now) : row.get("window_start"),
        failures >= limit ? at(now.plusSeconds(300)) : null,
        branch,
        scope);
  }
}
