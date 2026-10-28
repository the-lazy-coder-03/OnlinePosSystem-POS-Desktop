package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.contracts.PosModule;

@Service
@Profile({"installed", "local-store"})
public class CustomerService {
  private final JdbcTemplate jdbc;
  private final ModuleAccess modules;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;
  private final TransactionTemplate independent;

  public CustomerService(
      JdbcTemplate jdbc,
      ModuleAccess modules,
      CommandJournal journal,
      LocalDatabase.Identity identity,
      TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.modules = modules;
    this.journal = journal;
    this.identity = identity;
    independent = new TransactionTemplate(transactions.getTransactionManager());
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private String branchName() {
    return jdbc.queryForObject(
        "SELECT COALESCE(s.store_name,(SELECT c.data->>'name' FROM pos.catalogue_record c WHERE c.branch_id=s.branch_id AND c.source_type='branch' LIMIT 1),'Store '||s.branch_id) FROM pos.store_identity s",
        String.class);
  }

  public ModuleAccess.Session require(String token, String terminal, String permission) {
    var session = modules.authenticate(token, terminal, false);
    if (session.module() != PosModule.CUSTOMERS || !session.permissions().contains(permission))
      throw new LocalFailure(403, "Customer permission required");
    return session;
  }

  private StaffService.Actor actor(ModuleAccess.Session session) {
    return new StaffService.Actor(
        session.staffId(),
        jdbc.queryForObject(
            "SELECT role FROM pos.staff WHERE branch_id=? AND staff_id=?",
            String.class,
            identity.branchId(),
            session.staffId()),
        session.terminal(),
        session.credentialVersion());
  }

  public Map<String, Object> context(String token, String terminal) {
    var s = require(token, terminal, "customers.view");
    return Map.of(
        "branchId",
        identity.branchId(),
        "branchName",
        branchName(),
        "permissions",
        s.permissions(),
        "syncEnabled",
        false);
  }

  public Map<String, Object> search(
      String token, String terminal, String query, boolean archived, String cursor) {
    require(token, terminal, "customers.view");
    if (query == null || query.length() > 255) throw new LocalFailure(400, "Invalid search");
    String sql =
        "SELECT customer_id FROM pos.local_customer WHERE branch_id=? AND archived=? AND canonical_customer_id IS NULL AND preferred_branch=? AND (position(lower(?) in lower(display_name))>0 OR position(? in coalesce(phone,''))>0 OR position(? in coalesce(profile->>'phone2',''))>0 OR position(lower(?) in coalesce(normalized_email,''))>0";
    String normalized = query;
    try {
      normalized = Customers.phone(query, false);
    } catch (IllegalArgumentException ignored) {
      /* Name/email and incomplete phone searches remain valid. */
    }
    sql += " OR normalized_phone=?)";
    var args =
        new ArrayList<Object>(
            List.of(
                identity.branchId(),
                archived,
                identity.branchId(),
                query.strip(),
                query.strip(),
                query.strip(),
                query.strip(),
                normalized));
    if (cursor != null && !cursor.isBlank()) {
      try {
        if (cursor.length() > 3000) throw new IllegalArgumentException();
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int separator = decoded.lastIndexOf('|');
        String[] value = {decoded.substring(0, separator), decoded.substring(separator + 1)};
        sql += " AND (lower(display_name),customer_id)>(?,?)";
        args.add(value[0]);
        args.add(UUID.fromString(value[1]));
      } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
        throw new LocalFailure(400, "Invalid cursor");
      }
    }
    var ids =
        jdbc.queryForList(
            sql + " ORDER BY lower(display_name),customer_id LIMIT 51", UUID.class, args.toArray());
    boolean more = ids.size() > 50;
    var items = ids.subList(0, Math.min(50, ids.size())).stream().map(this::read).toList();
    String next = "";
    if (more) {
      var last = items.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.get("name").toString().toLowerCase(java.util.Locale.ROOT)
                          + "|"
                          + last.get("id"))
                      .getBytes(StandardCharsets.UTF_8));
    }
    return Map.of("items", items, "hasMore", more, "nextCursor", next);
  }

  private UUID canonical(UUID id) {
    var aliases =
        jdbc.queryForList(
            "SELECT customer_id FROM pos.customer_alias WHERE branch_id=? AND alias_id=?",
            UUID.class,
            identity.branchId(),
            id);
    return aliases.isEmpty() ? id : aliases.getFirst();
  }

  private Map<String, Object> read(UUID requested) {
    UUID id = canonical(requested);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM pos.local_customer WHERE branch_id=? AND customer_id=?",
            identity.branchId(),
            id);
    if (rows.isEmpty()) throw new LocalFailure(404, "Customer not found");
    var row = rows.getFirst();
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("name", row.get("display_name"));
    result.put("revision", row.get("revision"));
    result.put("profile", journal.parse(row.get("profile").toString()));
    result.put("archived", row.get("archived"));
    result.put("banned", row.get("banned"));
    result.put("banReason", row.get("ban_reason"));
    result.put("banChangedAt", row.get("ban_changed_at"));
    result.put("syncState", row.get("sync_state"));
    result.put("preferredBranch", row.get("preferred_branch"));
    result.put("cloudId", row.get("cloud_id"));
    result.put("globalId", row.get("global_id"));
    result.put("fieldTimes", journal.parse(row.get("field_times").toString()));
    result.put("updatedAt", row.get("updated_at"));
    return result;
  }

  public Map<String, Object> detail(String token, String terminal, UUID id) {
    require(token, terminal, "customers.view");
    return read(id);
  }

  public Map<String, Object> lookup(String token, String terminal, String phone) {
    require(token, terminal, "customers.view");
    String normalized = Customers.phone(phone, true);
    var ids =
        jdbc.queryForList(
            "SELECT customer_id FROM pos.local_customer WHERE branch_id=? AND (normalized_phone=? OR profile->>'phone1'=?) AND canonical_customer_id IS NULL",
            UUID.class,
            identity.branchId(),
            normalized,
            normalized);
    if (ids.size() > 1)
      return Map.of(
          "status",
          "CONFLICT",
          "message",
          "Multiple local identities share this phone. Review Account Conflicts.");
    return ids.isEmpty()
        ? Map.of(
            "status",
            "REMOTE_UNAVAILABLE",
            "message",
            "Website lookup is not available; this is not a confirmed customer-not-found result.")
        : Map.of("status", "FOUND", "customer", read(ids.getFirst()));
  }

  public JsonNode save(String token, String terminal, Customers.Edit edit) {
    var session = require(token, terminal, "customers.manage");
    CommandJournal.requireUuid(edit.commandId());
    CommandJournal.requireUuid(edit.customerId());
    if (edit.expectedRevision() < -1) throw new LocalFailure(400, "Invalid revision");
    var data = new LinkedHashMap<String, String>();
    if (edit.expectedRevision() >= 0) {
      var existing = journal.tree(read(edit.customerId())).path("profile");
      existing.properties().forEach(e -> data.put(e.getKey(), e.getValue().asText()));
    }
    if (edit.fields() == null
        || !za.co.onlinepos.contracts.CustomerProfile.FIELDS
            .keySet()
            .containsAll(edit.fields().keySet())
        || edit.fields().values().stream().anyMatch(java.util.Objects::isNull))
      throw new LocalFailure(400, "Unsupported profile fields");
    data.putAll(edit.fields());
    if (edit.expectedRevision() < 0) data.putIfAbsent("preferred_store", branchName());
    var validated = Customers.validate(data);
    try {
      return journal.execute(
          edit.commandId(),
          actor(session),
          "CUSTOMER_SAVE",
          edit,
          () -> {
            // Recheck session/action inside the same branch transaction, not merely before waiting
            // for the lock.
            require(token, terminal, "customers.manage");
            long revision =
                write(
                    token,
                    terminal,
                    edit.customerId(),
                    edit.expectedRevision(),
                    "SAVE",
                    validated,
                    edit.commandId());
            event(
                session,
                edit.expectedRevision() < 0 ? "CUSTOMER_CREATED" : "CUSTOMER_UPDATED",
                edit.customerId(),
                revision,
                edit.commandId(),
                false);
            return journal.tree(read(edit.customerId()));
          });
    } catch (DataIntegrityViolationException collision) {
      if (!(collision.getMostSpecificCause() instanceof java.sql.SQLException sql)
          || !"23505".equals(sql.getSQLState())) throw collision;
      // Contact collisions become durable, manager-visible work; no failed profile is half-created.
      recordCollision(token, terminal, edit, validated);
      throw new LocalFailure(
          409, "Contact already exists. Review Account Conflicts or use phone lookup.");
    }
  }

  private long write(
      String token,
      String terminal,
      UUID id,
      long revision,
      String operation,
      Object payload,
      UUID command) {
    return jdbc.queryForObject(
        "SELECT pos.write_customer(?,?,?,?,?,?::jsonb,?)",
        Long.class,
        CommandJournal.digest(token),
        terminal,
        id,
        revision,
        operation,
        journal.encode(payload),
        command);
  }

  private void event(
      ModuleAccess.Session session,
      String type,
      UUID id,
      long revision,
      UUID command,
      boolean localOnly) {
    var record = read(id);
    record.put(
        "origin",
        Map.of(
            "branchId",
            identity.branchId(),
            "staffId",
            session.staffId(),
            "terminal",
            session.terminal()));
    var safe =
        Map.of("revision", revision, "customerId", id, "fields", Set.of("profile", "status"));
    journal.event(
        actor(session),
        type,
        id,
        revision,
        command,
        localOnly
            ? Map.of("customerId", id, "archived", record.get("archived"), "scope", "BRANCH")
            : record,
        safe);
    if (!localOnly) {
      UUID eventId =
          jdbc.queryForObject(
              "SELECT event_id FROM pos.outbox_event WHERE branch_id=? AND aggregate_id=? AND aggregate_revision=? AND event_type=? ORDER BY stream_position DESC LIMIT 1",
              UUID.class,
              identity.branchId(),
              id,
              revision,
              type);
      jdbc.queryForObject(
          "SELECT pos.queue_customer_sync(?,?,?)", Object.class, eventId, identity.branchId(), id);
    }
  }

  public JsonNode status(
      String token, String terminal, UUID id, Customers.Status edit, boolean ban) {
    var session = require(token, terminal, ban ? "customers.ban" : "customers.archive");
    CommandJournal.requireUuid(edit.commandId());
    if (edit.expectedRevision() < 0
        || edit.reason() != null && edit.reason().length() > 2000
        || ban && edit.value() && (edit.reason() == null || edit.reason().isBlank()))
      throw new LocalFailure(400, "A ban requires a reason of at most 2000 characters");
    return journal.execute(
        edit.commandId(),
        actor(session),
        ban ? "CUSTOMER_BAN" : "CUSTOMER_ARCHIVE",
        Map.of("id", id, "edit", edit),
        () -> {
          require(token, terminal, ban ? "customers.ban" : "customers.archive");
          long revision =
              write(
                  token,
                  terminal,
                  id,
                  edit.expectedRevision(),
                  ban ? "BAN" : "ARCHIVE",
                  Map.of(
                      "value",
                      edit.value(),
                      "reason",
                      edit.reason() == null ? "" : edit.reason().strip()),
                  edit.commandId());
          String type =
              ban
                  ? (edit.value() ? "CUSTOMER_BANNED" : "CUSTOMER_UNBANNED")
                  : (edit.value() ? "CUSTOMER_ARCHIVED" : "CUSTOMER_RESTORED");
          event(session, type, id, revision, edit.commandId(), !ban);
          return journal.tree(read(id));
        });
  }

  public Map<String, Object> history(String token, String terminal, UUID requested, int page) {
    require(token, terminal, "customers.view");
    if (page < 0 || page > 10000) throw new LocalFailure(400, "Invalid page");
    UUID id = canonical(requested);
    read(id);
    var items =
        jdbc.queryForList(
            "SELECT sync_id AS \"orderId\",state,total,created_at AS \"createdAt\",provisional_reference AS reference,customer_snapshot AS snapshot FROM pos.order_draft WHERE branch_id=? AND (customer_id=? OR customer_id IN (SELECT alias_id FROM pos.customer_alias WHERE branch_id=? AND customer_id=?)) ORDER BY created_at DESC,sync_id DESC LIMIT 51 OFFSET ?",
            identity.branchId(),
            id,
            identity.branchId(),
            id,
            page * 50);
    var changes =
        jdbc.queryForList(
            "SELECT action,actor_id AS \"staffId\",occurred_at AS \"occurredAt\",reason FROM pos.customer_status_history WHERE branch_id=? AND (customer_id=? OR customer_id IN (SELECT alias_id FROM pos.customer_alias WHERE branch_id=? AND customer_id=?)) ORDER BY occurred_at DESC,event_id DESC LIMIT 51 OFFSET ?",
            identity.branchId(),
            id,
            identity.branchId(),
            id,
            page * 50);
    return Map.of(
        "items",
        items.subList(0, Math.min(50, items.size())),
        "hasMore",
        items.size() > 50 || changes.size() > 50,
        "statusHistory",
        changes.subList(0, Math.min(50, changes.size())));
  }

  public Map<String, Object> syncStatus(String token, String terminal) {
    require(token, terminal, "customers.view");
    return Map.of(
        "enabled",
        false,
        "state",
        "DISABLED_CLOUD_API_PENDING",
        "pending",
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.customer_sync_pending WHERE branch_id=? AND state='PENDING'",
            Integer.class,
            identity.branchId()),
        "message",
        "Saved locally. Website and other branches have not confirmed these changes.");
  }

  private void recordCollision(
      String token, String terminal, Customers.Edit edit, Map<String, String> data) {
    independent.executeWithoutResult(
        status -> {
          var matches =
              jdbc.queryForList(
                  "SELECT customer_id FROM pos.local_customer WHERE branch_id=? AND canonical_customer_id IS NULL AND (profile->>'phone1'=? OR normalized_email=?)",
                  UUID.class,
                  identity.branchId(),
                  data.get("phone1"),
                  data.get("email"));
          UUID conflict =
              UUID.nameUUIDFromBytes(
                  (edit.commandId() + ":customer-conflict").getBytes(StandardCharsets.UTF_8));
          var payload =
              Map.of(
                  "proposedId",
                  edit.customerId(),
                  "proposedRevision",
                  edit.expectedRevision(),
                  "fields",
                  data,
                  "matches",
                  matches);
          jdbc.queryForObject(
              "SELECT pos.customer_conflict_write(?,?,?,?,?,?::jsonb,?,-1,NULL)",
              Object.class,
              CommandJournal.digest(token),
              terminal,
              conflict,
              edit.commandId(),
              CommandJournal.digest(journal.encode(payload)),
              journal.encode(payload),
              "CONTACT_COLLISION");
        });
  }

  public Map<String, Object> conflicts(String token, String terminal, int page) {
    require(token, terminal, "customers.resolve");
    if (page < 0 || page > 10000) throw new LocalFailure(400, "Invalid page");
    var items =
        jdbc.queryForList(
            "SELECT conflict_id AS id,reason,revision,payload,created_at AS \"createdAt\" FROM pos.customer_conflict WHERE branch_id=? AND state='OPEN' ORDER BY created_at,conflict_id LIMIT 51 OFFSET ?",
            identity.branchId(),
            page * 50);
    items.forEach(row -> row.put("payload", journal.parse(row.get("payload").toString())));
    return Map.of(
        "items", items.subList(0, Math.min(50, items.size())), "hasMore", items.size() > 50);
  }

  public JsonNode resolve(
      String token, String terminal, UUID conflictId, Customers.Resolution edit) {
    var session = require(token, terminal, "customers.resolve");
    CommandJournal.requireUuid(edit.commandId());
    return journal.execute(
        edit.commandId(),
        actor(session),
        "CUSTOMER_RESOLUTION",
        Map.of("id", conflictId, "edit", edit),
        () -> {
          var rows =
              jdbc.queryForList(
                  "SELECT payload::text,revision FROM pos.customer_conflict WHERE branch_id=? AND conflict_id=? AND state='OPEN'",
                  identity.branchId(),
                  conflictId);
          if (rows.isEmpty()
              || ((Number) rows.getFirst().get("revision")).longValue() != edit.expectedRevision())
            throw new LocalFailure(409, "Conflict changed");
          var payload = journal.parse((String) rows.getFirst().get("payload"));
          UUID proposed = UUID.fromString(payload.path("proposedId").asText());
          if ("LINK".equals(edit.action())) {
            if (edit.targetCustomerId() == null)
              throw new LocalFailure(400, "Choose a target customer");
            read(edit.targetCustomerId());
            jdbc.queryForObject(
                "SELECT pos.resolve_customer_identity(?,?,?,?,?)",
                Object.class,
                CommandJournal.digest(token),
                terminal,
                proposed,
                edit.targetCustomerId(),
                edit.commandId());
          } else if ("SEPARATE".equals(edit.action())) {
            if (edit.targetCustomerId() != null)
              throw new LocalFailure(400, "Separate resolution cannot select another identity");
            var existing =
                jdbc.queryForList(
                    "SELECT revision FROM pos.local_customer WHERE branch_id=? AND customer_id=?",
                    Long.class,
                    identity.branchId(),
                    proposed);
            var data = new LinkedHashMap<String, String>();
            var baseProfile =
                existing.isEmpty()
                    ? payload.path("fields")
                    : journal.tree(read(proposed)).path("profile");
            baseProfile.properties().forEach(e -> data.put(e.getKey(), e.getValue().asText()));
            if (edit.correctedFields() == null)
              throw new LocalFailure(400, "Corrected contact fields required");
            if (edit.correctedFields().containsKey("preferred_store")
                && !edit.correctedFields()
                    .get("preferred_store")
                    .equals(data.get("preferred_store")))
              throw new LocalFailure(403, "Preferred store is customer-controlled");
            data.putAll(edit.correctedFields());
            var validated = Customers.validate(data);
            long expectedCustomerRevision =
                edit.customerRevision() == null
                    ? payload.path("proposedRevision").asLong(-1)
                    : edit.customerRevision();
            if ((existing.isEmpty() ? -1 : existing.getFirst()) != expectedCustomerRevision)
              throw new LocalFailure(
                  409, "Customer changed since this conflict; reload and review the profile");
            long rev =
                write(
                    token,
                    terminal,
                    proposed,
                    expectedCustomerRevision,
                    "RESOLVE_SAVE",
                    validated,
                    edit.commandId());
            event(
                session,
                existing.isEmpty() ? "CUSTOMER_CREATED" : "CUSTOMER_UPDATED",
                proposed,
                rev,
                edit.commandId(),
                false);
          } else throw new LocalFailure(400, "Choose LINK or SEPARATE");
          var result =
              Map.of(
                  "action",
                  edit.action(),
                  "target",
                  edit.targetCustomerId() == null ? proposed : edit.targetCustomerId());
          jdbc.queryForObject(
              "SELECT pos.customer_conflict_write(?,?,?,NULL,NULL,NULL,NULL,?,?::jsonb)",
              Object.class,
              CommandJournal.digest(token),
              terminal,
              conflictId,
              edit.expectedRevision(),
              journal.encode(result));
          journal.event(
              actor(session),
              "CUSTOMER_CONFLICT_RESOLVED",
              conflictId,
              edit.expectedRevision() + 1,
              edit.commandId(),
              Map.of(
                  "action",
                  edit.action(),
                  "target",
                  result.get("target"),
                  "source",
                  proposed,
                  "customer",
                  read((UUID) result.get("target")),
                  "origin",
                  Map.of(
                      "branchId",
                      identity.branchId(),
                      "staffId",
                      session.staffId(),
                      "terminal",
                      session.terminal())),
              Map.of("revision", edit.expectedRevision() + 1));
          UUID pendingId =
              jdbc.queryForObject(
                  "SELECT event_id FROM pos.outbox_event WHERE branch_id=? AND aggregate_id=? AND event_type='CUSTOMER_CONFLICT_RESOLVED' ORDER BY stream_position DESC LIMIT 1",
                  UUID.class,
                  identity.branchId(),
                  conflictId);
          UUID linked = edit.targetCustomerId() == null ? proposed : edit.targetCustomerId();
          jdbc.queryForObject(
              "SELECT pos.queue_customer_sync(?,?,?)",
              Object.class,
              pendingId,
              identity.branchId(),
              linked);
          return journal.tree(result);
        });
  }
}
