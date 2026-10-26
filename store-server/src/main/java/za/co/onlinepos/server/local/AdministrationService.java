package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.Administration;
import za.co.onlinepos.contracts.PosModule;

@Service
@Profile({"installed", "local-store"})
public class AdministrationService {
  private final JdbcTemplate jdbc;
  private final CommandJournal journal;
  private final ModuleAccess modules;
  private final LocalDatabase.Identity identity;
  private final String key;

  public AdministrationService(
      JdbcTemplate jdbc,
      CommandJournal journal,
      ModuleAccess modules,
      LocalDatabase.Identity identity,
      Environment env) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.modules = modules;
    this.identity = identity;
    key = env.getProperty("pos.pin-key", "");
  }

  public static Set<String> implementedPermissions() {
    var result = new TreeSet<String>();
    for (var module : PosModule.values()) result.add(module.permission());
    result.addAll(Set.of("staff.manage", "rbac.manage", "audit.view", "catalogue.manage"));
    return result;
  }

  public boolean owner(UUID staff) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM pos.store_owner WHERE branch_id=? AND staff_id=?",
            Integer.class,
            identity.branchId(),
            staff)
        == 1;
  }

  private ModuleAccess.Session require(
      String token, String terminal, String permission, boolean ownerOnly) {
    var session = modules.authenticate(token, terminal, false);
    if (session.module() != PosModule.ADMINISTRATION
        || !session.permissions().contains(permission)
        || (ownerOnly && !owner(session.staffId())))
      throw new LocalFailure(403, "Administration permission required");
    return session;
  }

  public JsonNode recoveryStatus(String token, String terminal) {
    require(token, terminal, "module.administration", true);
    return journal.parse(
        jdbc.queryForObject(
            "SELECT pos.developer_recovery_status(?,?)::text",
            String.class,
            CommandJournal.digest(token),
            terminal));
  }

  public void disableRecovery(String token, String terminal) {
    require(token, terminal, "module.administration", true);
    jdbc.queryForObject(
        "SELECT pos.disable_developer_recovery(?,?)",
        Object.class,
        CommandJournal.digest(token),
        terminal);
  }

  public Map<String, Object> overview(String token, String terminal) {
    var session = require(token, terminal, "module.administration", false);
    var result = new HashMap<String, Object>();
    result.put("username", session.username());
    result.put("permissions", session.permissions());
    result.put("owner", owner(session.staffId()));
    result.put(
        "ownerConfigured",
        jdbc.queryForObject(
                "SELECT count(*) FROM pos.store_owner WHERE branch_id=?",
                Integer.class,
                identity.branchId())
            == 1);
    result.put("branchId", identity.branchId());
    result.put("implementedPermissions", implementedPermissions());
    if (session.permissions().contains("staff.manage")) {
      result.put(
          "staffCount",
          jdbc.queryForObject(
              "SELECT count(*) FROM pos.staff WHERE branch_id=?",
              Integer.class,
              identity.branchId()));
      result.put(
          "pendingPins",
          jdbc.queryForObject(
              "SELECT count(*) FROM pos.staff s WHERE s.branch_id=? AND s.enabled AND NOT EXISTS(SELECT 1 FROM pos.staff_pin p WHERE p.branch_id=s.branch_id AND p.staff_id=s.staff_id)",
              Integer.class,
              identity.branchId()));
    }
    if (session.permissions().contains("rbac.manage"))
      result.put(
          "roleCount",
          jdbc.queryForObject(
              "SELECT count(*) FROM pos.rbac_role WHERE branch_id=?",
              Integer.class,
              identity.branchId()));
    return result;
  }

  private String search(String search) {
    if (search == null) return "";
    if (search.length() > 64) throw new LocalFailure(400, "Search is too long");
    return search;
  }

  private void page(int page) {
    if (page < 0 || page > 10000) throw new LocalFailure(400, "Invalid page");
  }

  public Map<String, Object> staff(String token, String terminal, String search, int page) {
    var session = require(token, terminal, "staff.manage", false);
    page(page);
    var rows =
        jdbc.queryForList(
            "SELECT s.staff_id AS \"staffId\",s.username,s.enabled,s.revision,s.password_login_enabled AS \"passwordLoginEnabled\",EXISTS(SELECT 1 FROM pos.staff_pin p WHERE p.branch_id=s.branch_id AND p.staff_id=s.staff_id) AS \"pinConfigured\",EXISTS(SELECT 1 FROM pos.store_owner o WHERE o.branch_id=s.branch_id AND o.staff_id=s.staff_id) AS owner,(SELECT coalesce(jsonb_agg(r.role_name ORDER BY r.role_name),'[]')::text FROM pos.staff_role r WHERE r.branch_id=s.branch_id AND r.staff_id=s.staff_id) AS roles FROM pos.staff s WHERE s.branch_id=? AND position(lower(?) in s.username)>0 ORDER BY s.username,s.staff_id LIMIT 51 OFFSET ?",
            identity.branchId(),
            search(search),
            page * 50);
    boolean more = rows.size() > 50;
    if (more) rows.removeLast();
    for (var row : rows) {
      row.put("roles", journal.parse((String) row.get("roles")));
      UUID id = (UUID) row.get("staffId");
      row.put(
          "editable",
          owner(session.staffId())
              || (!Boolean.TRUE.equals(row.get("owner"))
                  && session.permissions().containsAll(modules.permissions(id))));
    }
    return Map.of("items", rows, "page", page, "hasMore", more);
  }

  public List<Map<String, Object>> roles(String token, String terminal, boolean options) {
    var session = require(token, terminal, options ? "staff.manage" : "rbac.manage", false);
    var rows =
        jdbc.queryForList(
            "SELECT name,revision,(SELECT coalesce(jsonb_agg(p.permission ORDER BY p.permission),'[]')::text FROM pos.role_permission p WHERE p.branch_id=r.branch_id AND p.role_name=r.name) AS permissions FROM pos.rbac_role r WHERE r.branch_id=? ORDER BY name",
            identity.branchId());
    for (var row : rows) {
      var permissions = journal.parse((String) row.get("permissions"));
      var values = new TreeSet<String>();
      permissions.forEach(p -> values.add(p.asText()));
      row.put("editable", owner(session.staffId()) || session.permissions().containsAll(values));
      if (options) row.remove("permissions");
      else row.put("permissions", permissions);
    }
    if (options) rows.removeIf(row -> !Boolean.TRUE.equals(row.get("editable")));
    return rows;
  }

  public JsonNode editStaff(String token, String terminal, Administration.StaffEdit edit) {
    require(token, terminal, "staff.manage", false);
    CommandJournal.requireUuid(edit.staffId());
    if (edit.username() == null
        || !edit.username().matches("[a-z0-9._-]{3,64}")
        || edit.roles() == null
        || edit.roles().isEmpty()
        || edit.roles().size() > 64
        || edit.expectedRevision() < -1) throw new LocalFailure(400, "Invalid staff fields");
    var payload =
        new Administration.StaffEdit(
            edit.commandId(),
            edit.staffId(),
            edit.username(),
            edit.enabled(),
            new TreeSet<>(edit.roles()),
            edit.expectedRevision());
    var data = new HashMap<String, Object>();
    data.put("staffId", edit.staffId());
    data.put("username", edit.username());
    data.put("enabled", edit.enabled());
    data.put("roles", new TreeSet<>(edit.roles()));
    data.put("expectedRevision", edit.expectedRevision());
    // New PIN-only accounts cannot log in with a password; retain a valid unusable random hash.
    data.put(
        "unusablePasswordHash",
        jdbc.queryForObject(
                    "SELECT count(*) FROM pos.staff WHERE branch_id=? AND staff_id=?",
                    Integer.class,
                    identity.branchId(),
                    edit.staffId())
                == 0
            ? Passwords.hash(UUID.randomUUID().toString().toCharArray())
            : "");
    return mutate(token, terminal, edit.commandId(), "staff", edit.staffId(), payload, data);
  }

  public JsonNode editRole(String token, String terminal, Administration.RoleEdit edit) {
    if (edit.name() == null
        || !edit.name().matches("[A-Z][A-Z0-9_]{0,63}")
        || edit.permissions() == null
        || !implementedPermissions().containsAll(edit.permissions())
        || edit.expectedRevision() < -1)
      throw new LocalFailure(400, "Invalid role or unsupported permission");
    var normalized =
        new Administration.RoleEdit(
            edit.commandId(),
            edit.name(),
            new TreeSet<>(edit.permissions()),
            edit.expectedRevision());
    return mutate(
        token,
        terminal,
        edit.commandId(),
        "role",
        UUID.nameUUIDFromBytes(
            (identity.branchId() + ":" + edit.name()).getBytes(StandardCharsets.UTF_8)),
        normalized,
        Map.of(
            "name",
            edit.name(),
            "permissions",
            new TreeSet<>(edit.permissions()),
            "expectedRevision",
            edit.expectedRevision()));
  }

  public JsonNode editPin(
      String token, String terminal, UUID staffId, Administration.PinEdit edit) {
    require(token, terminal, "staff.manage", true);
    CommandJournal.requireUuid(staffId);
    CommandJournal.requireUuid(edit.commandId());
    if (edit.expectedRevision() < 0 || edit.pin() == null || !edit.pin().matches("[0-9]{6}"))
      throw new LocalFailure(400, "Six-digit PIN required");
    if (key.isBlank()) throw new LocalFailure(503, "PIN access has not been provisioned");
    char[] pin = edit.pin().toCharArray();
    try {
      var safe =
          Map.of(
              "staffId",
              staffId,
              "expectedRevision",
              edit.expectedRevision(),
              "proof",
              Passwords.commandProof(pin, edit.commandId()));
      return mutate(
          token,
          terminal,
          edit.commandId(),
          "pin",
          staffId,
          safe,
          Map.of(
              "staffId",
              staffId,
              "expectedRevision",
              edit.expectedRevision(),
              "lookup",
              ModuleAccess.lookup(key, identity.branchId(), pin),
              "hash",
              Passwords.hashPin(pin)));
    } finally {
      Arrays.fill(pin, '\0');
    }
  }

  private JsonNode mutate(
      String token,
      String terminal,
      UUID command,
      String kind,
      UUID aggregate,
      Object payload,
      Map<String, ?> data) {
    var session =
        require(
            token,
            terminal,
            kind.equals("role") ? "rbac.manage" : "staff.manage",
            kind.equals("pin"));
    var actor =
        new StaffService.Actor(
            session.staffId(),
            jdbc.queryForObject(
                "SELECT role FROM pos.staff WHERE staff_id=? AND branch_id=?",
                String.class,
                session.staffId(),
                identity.branchId()),
            terminal,
            session.credentialVersion());
    try {
      return journal.execute(
          command,
          actor,
          "ADMIN_" + kind.toUpperCase(java.util.Locale.ROOT),
          payload,
          () -> {
            require(
                token,
                terminal,
                kind.equals("role") ? "rbac.manage" : "staff.manage",
                kind.equals("pin"));
            long revision =
                jdbc.queryForObject(
                    "SELECT pos.apply_admin_change(?,?,?,?::jsonb)",
                    Long.class,
                    CommandJournal.digest(token),
                    terminal,
                    kind,
                    journal.encode(data));
            var safe = new HashMap<String, Object>();
            safe.put("revision", revision);
            safe.put("target", aggregate);
            if (kind.equals("staff")) {
              safe.put("username", data.get("username"));
              safe.put("enabled", data.get("enabled"));
              safe.put("roles", data.get("roles"));
            }
            if (kind.equals("role")) {
              safe.put("name", data.get("name"));
              safe.put("permissions", data.get("permissions"));
            }
            if (kind.equals("pin")) safe.put("pinConfigured", true);
            journal.event(
                actor,
                "ADMIN_" + kind.toUpperCase(java.util.Locale.ROOT) + "_CHANGED",
                aggregate,
                revision,
                command,
                safe);
            safe.put(
                "reauthenticate",
                jdbc.queryForObject(
                        "SELECT count(*) FROM pos.module_session WHERE token_hash=?",
                        Integer.class,
                        CommandJournal.digest(token))
                    == 0);
            return journal.tree(safe);
          });
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionSystemException exception) {
      Throwable cause = exception;
      while (cause != null) {
        if (cause instanceof java.sql.SQLException sql) {
          String state = sql.getSQLState();
          if ("42501".equals(state))
            throw new LocalFailure(403, "Administration permission required");
          if ("28000".equals(state)) throw new LocalFailure(401, "Administration session expired");
          if (state != null && state.startsWith("23"))
            throw new LocalFailure(
                409, "Record changed or value unavailable. Reload before retrying.");
        }
        cause = cause.getCause();
      }
      throw new LocalFailure(503, "Store database unavailable");
    }
  }

  public Map<String, Object> audit(
      String token,
      String terminal,
      int page,
      String actor,
      String action,
      Instant from,
      Instant to) {
    require(token, terminal, "audit.view", false);
    page(page);
    if (action != null && (action.length() > 64 || !action.matches("[A-Z0-9_]*")))
      throw new LocalFailure(400, "Invalid action filter");
    if (from != null && to != null && from.isAfter(to))
      throw new LocalFailure(400, "Invalid date range");
    var args = new ArrayList<Object>();
    args.add(identity.branchId());
    StringBuilder where = new StringBuilder(" WHERE a.branch_id=?");
    if (actor != null && !actor.isBlank()) {
      where.append(
          " AND position(lower(?) in lower(coalesce(s.username,'Protected local console')))>0");
      args.add(search(actor));
    }
    if (action != null && !action.isBlank()) {
      where.append(" AND a.action=?");
      args.add(action);
    }
    if (from != null) {
      where.append(" AND a.occurred_at>=?");
      args.add(java.sql.Timestamp.from(from));
    }
    if (to != null) {
      where.append(" AND a.occurred_at<=?");
      args.add(java.sql.Timestamp.from(to));
    }
    args.add(page * 50);
    var rows =
        jdbc.queryForList(
            "SELECT a.audit_id AS \"auditId\",a.occurred_at AS \"occurredAt\",a.actor_id AS \"actorId\",coalesce(s.username,'Protected local console') AS actor,a.action,a.aggregate_id AS target,CASE WHEN a.action IN ('ADMIN_ROLE_CHANGED','RBAC_SET_ROLE') AND coalesce(a.details->>'name',a.details->>'subject','') ~ '^[A-Z][A-Z0-9_]{0,63}$' THEN coalesce(a.details->>'name',a.details->>'subject') ELSE coalesce(target_staff.username,'Store configuration') END AS \"targetName\",a.command_id AS \"commandId\" FROM pos.audit_record a LEFT JOIN pos.staff s ON s.staff_id=a.actor_id AND s.branch_id=a.branch_id LEFT JOIN pos.staff target_staff ON target_staff.staff_id=a.aggregate_id AND target_staff.branch_id=a.branch_id"
                + where
                + " ORDER BY a.occurred_at DESC,a.audit_id DESC LIMIT 51 OFFSET ?",
            args.toArray());
    boolean more = rows.size() > 50;
    if (more) rows.removeLast();
    // Raw audit details are intentionally not exposed: historical rows may have different schemas.
    rows.forEach(
        row ->
            row.put(
                "occurredAt", ((java.sql.Timestamp) row.get("occurredAt")).toInstant().toString()));
    return Map.of("items", rows, "page", page, "hasMore", more);
  }
}
