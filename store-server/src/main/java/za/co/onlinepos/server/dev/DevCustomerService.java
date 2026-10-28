package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.CustomerProfile;
import za.co.onlinepos.contracts.Customers;

@Service
@Profile("dev")
public class DevCustomerService {
  final DevModuleStore store;

  public DevCustomerService(DevModuleStore store) {
    this.store = store;
  }

  @PostConstruct
  void adopt() {
    store.tx.executeWithoutResult(
        status -> {
          store.lock();
          for (Long id :
              store.jdbc.queryForList(
                  "SELECT id FROM public.customers WHERE id NOT IN (SELECT source_id FROM dev_meta.customer_identity)",
                  Long.class))
            store.jdbc.update(
                "INSERT INTO dev_meta.customer_identity(customer_id,source_id) VALUES(?,?)",
                DevModuleStore.identity("customer", id.toString()),
                id);
        });
  }

  public UUID canonical(UUID id) {
    var aliases =
        store.jdbc.queryForList(
            "SELECT customer_id FROM dev_meta.customer_aliases WHERE alias_id=?", UUID.class, id);
    if (!aliases.isEmpty()) return canonical(aliases.getFirst());
    var rows =
        store.jdbc.queryForList(
            "SELECT canonical_id FROM dev_meta.customer_identity WHERE customer_id=?",
            UUID.class,
            id);
    if (rows.isEmpty()) throw DevModuleStore.fail(404, "Customer not found");
    UUID canonical = rows.getFirst();
    return canonical == null ? id : canonical(canonical);
  }

  public ObjectNode read(UUID requested) {
    UUID id = canonical(requested);
    var meta =
        store.jdbc.queryForMap("SELECT * FROM dev_meta.customer_identity WHERE customer_id=?", id);
    var rows =
        store.jdbc.queryForMap(
            "SELECT "
                + String.join(",", CustomerProfile.FIELDS.keySet())
                + " FROM public.customers WHERE id=?",
            meta.get("source_id"));
    var profile = new LinkedHashMap<String, String>();
    CustomerProfile.FIELDS
        .keySet()
        .forEach(key -> profile.put(key, rows.get(key) == null ? "" : rows.get(key).toString()));
    var result = store.json.createObjectNode();
    result
        .put("id", id.toString())
        .put("name", (profile.get("first_name") + " " + profile.get("last_name")).strip())
        .put("revision", ((Number) meta.get("revision")).longValue())
        .put("archived", (Boolean) meta.get("archived"))
        .put("banned", (Boolean) meta.get("banned"))
        .put("banReason", meta.get("ban_reason") == null ? "" : meta.get("ban_reason").toString())
        .put("syncState", meta.get("sync_state").toString())
        .put(
            "preferredBranch",
            meta.get("preferred_branch") == null
                ? 0
                : ((Number) meta.get("preferred_branch")).intValue());
    result.set("profile", store.tree(profile));
    result.put("updatedAt", meta.get("updated_at").toString());
    result.set("fieldTimes", store.parse(meta.get("field_times").toString()));
    result.put(
        "banChangedAt",
        meta.get("ban_changed_at") == null ? null : meta.get("ban_changed_at").toString());
    return result;
  }

  public JsonNode search(String query, boolean archived, String cursor) {
    if (query.length() > 255) throw DevModuleStore.fail(400, "Search too long");
    int offset = offset(cursor);
    String term = query.toLowerCase(java.util.Locale.ROOT);
    var rows =
        store
            .jdbc
            .queryForList(
                "SELECT customer_id FROM dev_meta.customer_identity WHERE canonical_id IS NULL AND archived=? ORDER BY source_id",
                UUID.class,
                archived)
            .stream()
            .map(this::read)
            .filter(
                r ->
                    r.path("preferredBranch").asInt() == 0
                        || r.path("preferredBranch").asInt() == store.branchId())
            .filter(
                r ->
                    r.path("name").asText().toLowerCase(java.util.Locale.ROOT).contains(term)
                        || r.path("profile")
                            .toString()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains(term)
                        || phoneMatch(r, query))
            .toList();
    var page = rows.stream().skip(offset).limit(50).toList();
    return store.tree(
        Map.of(
            "items",
            page,
            "hasMore",
            rows.size() > offset + 50,
            "nextCursor",
            rows.size() > offset + 50 ? Integer.toString(offset + 50) : ""));
  }

  private boolean phoneMatch(JsonNode row, String phone) {
    try {
      return Customers.phone(phone, false).equals(row.path("profile").path("phone1").asText());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  static int offset(String value) {
    if (value == null || value.isBlank()) return 0;
    try {
      int n = Integer.parseInt(value);
      if (n < 0 || n > 1000000) throw new IllegalArgumentException();
      return n;
    } catch (IllegalArgumentException invalid) {
      throw DevModuleStore.fail(400, "Invalid pagination cursor");
    }
  }

  public JsonNode lookup(String phone) {
    String normalized = Customers.phone(phone, true);
    var matches =
        store
            .jdbc
            .queryForList(
                "SELECT customer_id FROM dev_meta.customer_identity WHERE canonical_id IS NULL",
                UUID.class)
            .stream()
            .map(this::read)
            .filter(r -> phoneMatch(r, normalized))
            .toList();
    return matches.size() == 1
        ? store.tree(Map.of("status", "FOUND", "customer", matches.getFirst()))
        : store.tree(
            Map.of(
                "status",
                matches.isEmpty() ? "REMOTE_UNAVAILABLE" : "CONFLICT",
                "message",
                matches.isEmpty()
                    ? "Remote lookup is unavailable; not a confirmed not-found result."
                    : "Multiple identities match; review Account Conflicts."));
  }

  public JsonNode save(Customers.Edit edit) {
    try {
      return store.command(edit.commandId(), "CUSTOMER_SAVE", edit, () -> write(edit));
    } catch (ContactCollision collision) {
      store.tx.executeWithoutResult(
          status -> {
            store.lock();
            UUID conflict = DevModuleStore.identity("conflict", edit.commandId().toString());
            if (store.jdbc.queryForObject(
                    "SELECT count(*) FROM dev_meta.customer_conflicts WHERE id=?",
                    Integer.class,
                    conflict)
                == 0)
              store.jdbc.update(
                  "INSERT INTO dev_meta.customer_conflicts(id,payload,reason) VALUES(?,?,?)",
                  conflict,
                  store.encode(
                      Map.of(
                          "proposedId",
                          edit.customerId(),
                          "fields",
                          edit.fields(),
                          "matches",
                          collision.matches)),
                  "CONTACT_COLLISION");
          });
      throw DevModuleStore.fail(409, "Contact collision; review Account Conflicts");
    }
  }

  private static final class ContactCollision extends RuntimeException {
    final List<UUID> matches;

    ContactCollision(List<UUID> matches) {
      this.matches = matches;
    }
  }

  private List<UUID> collisions(UUID exclude, Map<String, String> fields) {
    var result = new ArrayList<UUID>();
    for (UUID id :
        store.jdbc.queryForList(
            "SELECT customer_id FROM dev_meta.customer_identity WHERE canonical_id IS NULL",
            UUID.class)) {
      if (id.equals(exclude)) continue;
      var p = read(id).path("profile");
      boolean phone = false;
      try {
        phone = Customers.phone(p.path("phone1").asText(), false).equals(fields.get("phone1"));
      } catch (IllegalArgumentException invalid) {
        /* Legacy invalid contacts require explicit correction. */
      }
      if (phone
          || (!fields.get("email").isBlank()
              && fields.get("email").equalsIgnoreCase(p.path("email").asText()))) result.add(id);
    }
    return result;
  }

  private JsonNode write(Customers.Edit edit) {
    if (edit.customerId() == null || edit.fields() == null || edit.expectedRevision() < -1)
      throw DevModuleStore.fail(400, "Invalid customer command");
    if (store.jdbc.queryForObject(
            "SELECT count(*) FROM dev_meta.customer_aliases WHERE alias_id=?",
            Integer.class,
            edit.customerId())
        > 0) throw DevModuleStore.fail(409, "Customer identity was linked; reload");
    boolean existing =
        store.jdbc.queryForObject(
                "SELECT count(*) FROM dev_meta.customer_identity WHERE customer_id=?",
                Integer.class,
                edit.customerId())
            > 0;
    if (existing != (edit.expectedRevision() >= 0))
      throw DevModuleStore.fail(409, "Customer changed; reload");
    var fields = new LinkedHashMap<String, String>();
    if (existing) {
      var old = read(edit.customerId());
      if (!old.path("id").asText().equals(edit.customerId().toString())
          || old.path("revision").asLong() != edit.expectedRevision())
        throw DevModuleStore.fail(409, "Customer changed; reload");
      old.path("profile").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));
    }
    if (!CustomerProfile.FIELDS.keySet().containsAll(edit.fields().keySet())
        || edit.fields().values().stream().anyMatch(java.util.Objects::isNull))
      throw DevModuleStore.fail(400, "Unsupported customer fields");
    String preferred = existing ? fields.get("preferred_store") : store.branchName();
    if (edit.fields().containsKey("preferred_store")
        && !edit.fields().get("preferred_store").equals(preferred))
      throw DevModuleStore.fail(403, "Preferred store cannot be changed by POS staff");
    fields.putAll(edit.fields());
    fields.put("preferred_store", preferred);
    var validated = Customers.validate(fields);
    var collisions = collisions(edit.customerId(), validated);
    if (!collisions.isEmpty()) throw new ContactCollision(collisions);
    long source;
    if (existing)
      source =
          store.jdbc.queryForObject(
              "SELECT source_id FROM dev_meta.customer_identity WHERE customer_id=?",
              Long.class,
              edit.customerId());
    else {
      source =
          store.jdbc.queryForObject(
              "SELECT COALESCE(MAX(id),0)+1 FROM public.customers", Long.class);
      store.jdbc.update("INSERT INTO public.customers(id) VALUES(?)", source);
      store.jdbc.update(
          "INSERT INTO dev_meta.customer_identity(customer_id,source_id,revision,preferred_branch) VALUES(?,?,-1,?)",
          edit.customerId(),
          source,
          store.branchId());
    }
    var args = new ArrayList<Object>();
    var columns = new ArrayList<>(CustomerProfile.FIELDS.keySet());
    for (String field : columns) {
      String value = validated.get(field);
      args.add(
          value == null || value.isBlank()
              ? null
              : List.of("latitude", "longitude").contains(field) ? new BigDecimal(value) : value);
    }
    args.add(source);
    store.jdbc.update(
        "UPDATE public.customers SET "
            + String.join(",", columns.stream().map(c -> c + "=?").toList())
            + " WHERE id=?",
        args.toArray());
    store.jdbc.update(
        "UPDATE dev_meta.customer_identity SET revision=revision+1,updated_at=CURRENT_TIMESTAMP,sync_state='PENDING_DISABLED' WHERE customer_id=?",
        edit.customerId());
    var times =
        store
            .parse(
                store.jdbc.queryForObject(
                    "SELECT field_times FROM dev_meta.customer_identity WHERE customer_id=?",
                    String.class,
                    edit.customerId()))
            .deepCopy();
    String now = java.time.Instant.now().toString();
    for (String field : edit.fields().keySet())
      ((ObjectNode) times)
          .put(
              java.util.Set.of("first_name", "last_name", "phone1", "phone2", "email")
                      .contains(field)
                  ? field
                  : "address",
              now);
    store.jdbc.update(
        "UPDATE dev_meta.customer_identity SET field_times=? WHERE customer_id=?",
        store.encode(times),
        edit.customerId());
    var result = read(edit.customerId());
    store.event(
        edit.commandId(),
        existing ? "CUSTOMER_UPDATED" : "CUSTOMER_CREATED",
        edit.customerId(),
        result.path("revision").asLong(),
        Map.of("branchId", store.branchId()));
    store.queueRecord(edit.commandId(), result);
    return result;
  }

  public JsonNode status(UUID id, Customers.Status edit, boolean ban) {
    return store.command(
        edit.commandId(),
        ban ? "CUSTOMER_BAN" : "CUSTOMER_ARCHIVE",
        List.of(id, edit),
        () -> {
          var before = read(id);
          if (before.path("revision").asLong() != edit.expectedRevision())
            throw DevModuleStore.fail(409, "Customer changed; reload");
          if (ban
              && edit.value()
              && (edit.reason() == null
                  || edit.reason().isBlank()
                  || edit.reason().length() > 2000))
            throw DevModuleStore.fail(400, "A ban reason of 1–2000 characters is required");
          if (!canonical(id).equals(id))
            throw DevModuleStore.fail(409, "Identity was linked; reload");
          String action =
              ban
                  ? (edit.value() ? "CUSTOMER_BANNED" : "CUSTOMER_UNBANNED")
                  : (edit.value() ? "CUSTOMER_ARCHIVED" : "CUSTOMER_RESTORED");
          if (ban)
            store.jdbc.update(
                "UPDATE dev_meta.customer_identity SET banned=?,ban_reason=?,ban_changed_at=CURRENT_TIMESTAMP,revision=revision+1,sync_state='PENDING_DISABLED',updated_at=CURRENT_TIMESTAMP WHERE customer_id=?",
                edit.value(),
                edit.value() ? edit.reason().strip() : null,
                id);
          else
            store.jdbc.update(
                "UPDATE dev_meta.customer_identity SET archived=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE customer_id=?",
                edit.value(),
                id);
          store.jdbc.update(
              "INSERT INTO dev_meta.customer_status(id,customer_id,action,reason) VALUES(?,?,?,?)",
              UUID.randomUUID(),
              id,
              action,
              ban && edit.value() ? edit.reason().strip() : null);
          var result = read(id);
          store.event(
              edit.commandId(),
              action,
              id,
              result.path("revision").asLong(),
              Map.of("branchId", store.branchId()));
          store.queueRecord(edit.commandId(), result);
          return result;
        });
  }

  public JsonNode history(UUID requested, int page) {
    int offset = offset(Integer.toString(page)) * 50;
    UUID id = canonical(requested);
    var related =
        store
            .jdbc
            .queryForList("SELECT customer_id FROM dev_meta.customer_identity", UUID.class)
            .stream()
            .filter(candidate -> canonical(candidate).equals(id))
            .toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(related.size(), "?"));
    var historyArgs = new ArrayList<Object>(related);
    historyArgs.add(offset);
    var items = new ArrayList<JsonNode>();
    for (var row :
        store.jdbc.queryForList(
            "SELECT order_id,reference,revision,status,snapshot,created_at FROM dev_meta.drafts WHERE customer_id IN("
                + placeholders
                + ") ORDER BY created_at DESC,order_id DESC LIMIT 51 OFFSET ?",
            historyArgs.toArray())) {
      var node = store.json.createObjectNode();
      node.put("orderId", row.get("order_id").toString())
          .put("reference", row.get("reference").toString())
          .put("state", row.get("status").toString())
          .put("createdAt", row.get("created_at").toString());
      var snapshot = store.parse(row.get("snapshot").toString());
      node.set("total", snapshot.path("total"));
      items.add(node);
    }
    var statuses =
        store.jdbc.query(
            "SELECT action,reason,recorded_at FROM dev_meta.customer_status WHERE customer_id IN("
                + placeholders
                + ") ORDER BY recorded_at DESC,id DESC LIMIT 51 OFFSET ?",
            (r, n) ->
                Map.of(
                    "action",
                    r.getString(1),
                    "reason",
                    r.getString(2) == null ? "" : r.getString(2),
                    "occurredAt",
                    r.getObject(3).toString(),
                    "staffId",
                    "Development actor"),
            historyArgs.toArray());
    return store.tree(
        Map.of(
            "items",
            items.stream().limit(50).toList(),
            "statusHistory",
            statuses.stream().limit(50).toList(),
            "hasMore",
            items.size() > 50 || statuses.size() > 50));
  }

  public JsonNode sync() {
    return store.tree(
        Map.of(
            "enabled",
            false,
            "pending",
            store.jdbc.queryForObject(
                "SELECT count(*) FROM dev_meta.outbox o JOIN dev_meta.module_events e ON e.event_id=o.event_id WHERE o.state='PENDING_DISABLED' AND e.action LIKE 'CUSTOMER_%'",
                Long.class),
            "message",
            "Cloud transport disabled — local changes have not been delivered."));
  }

  public JsonNode conflicts(int page) {
    int offset = offset(Integer.toString(page)) * 50;
    var records =
        store.jdbc.query(
            "SELECT id,revision,payload,reason FROM dev_meta.customer_conflicts WHERE state='OPEN' ORDER BY id LIMIT 51 OFFSET ?",
            (r, n) ->
                Map.of(
                    "id",
                    r.getObject(1),
                    "revision",
                    r.getLong(2),
                    "payload",
                    store.parse(r.getString(3)),
                    "reason",
                    r.getString(4)),
            offset);
    return store.tree(
        Map.of("items", records.stream().limit(50).toList(), "hasMore", records.size() > 50));
  }

  public JsonNode resolve(UUID id, Customers.Resolution edit) {
    return store.command(
        edit.commandId(),
        "CUSTOMER_RESOLUTION",
        List.of(id, edit),
        () -> {
          var records =
              store.jdbc.queryForList(
                  "SELECT revision,payload,state FROM dev_meta.customer_conflicts WHERE id=?", id);
          if (records.isEmpty()
              || !records.getFirst().get("state").equals("OPEN")
              || ((Number) records.getFirst().get("revision")).longValue()
                  != edit.expectedRevision())
            throw DevModuleStore.fail(409, "Conflict changed; reload");
          var payload = store.parse(records.getFirst().get("payload").toString());
          UUID proposed = UUID.fromString(payload.path("proposedId").asText());
          if (edit.action().equals("LINK")) {
            if (edit.targetCustomerId() == null)
              throw DevModuleStore.fail(400, "Target customer required");
            UUID target = canonical(edit.targetCustomerId());
            if (target.equals(proposed))
              throw DevModuleStore.fail(400, "Different target identity required");
            var existing =
                store.jdbc.queryForList(
                    "SELECT customer_id FROM dev_meta.customer_identity WHERE customer_id=?",
                    UUID.class,
                    proposed);
            if (!existing.isEmpty()) {
              if (read(proposed).path("banned").asBoolean()
                  && !read(target).path("banned").asBoolean())
                throw DevModuleStore.fail(
                    409, "Resolve the target ban before linking; linking cannot clear a ban");
              store.jdbc.update(
                  "UPDATE dev_meta.customer_identity SET canonical_id=?,revision=revision+1 WHERE customer_id=?",
                  target,
                  proposed);
            } else {
              store.jdbc.update(
                  "INSERT INTO dev_meta.customer_aliases(alias_id,customer_id) VALUES(?,?)",
                  proposed,
                  target);
            }
          } else if (edit.action().equals("SEPARATE")) {
            try {
              write(
                  new Customers.Edit(
                      edit.commandId(),
                      proposed,
                      edit.customerRevision() == null ? -1 : edit.customerRevision(),
                      edit.correctedFields()));
            } catch (ContactCollision conflict) {
              throw DevModuleStore.fail(
                  409, "Contacts still collide; correct them before resolving");
            }
          } else throw DevModuleStore.fail(400, "Unsupported resolution");
          store.jdbc.update(
              "UPDATE dev_meta.customer_conflicts SET state='RESOLVED',revision=revision+1,resolution=? WHERE id=?",
              store.encode(edit),
              id);
          store.event(
              edit.commandId(),
              "CUSTOMER_CONFLICT_RESOLVED",
              proposed,
              edit.expectedRevision() + 1,
              Map.of("action", edit.action()));
          store.queueRecord(edit.commandId(), Map.of("conflictId", id, "resolution", edit));
          return store.tree(Map.of("resolved", true));
        });
  }
}
