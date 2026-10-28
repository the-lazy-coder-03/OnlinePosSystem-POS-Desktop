package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.Auditing;

@Service
@Profile("dev")
public class DevAdministrationService {
  final DevModuleStore store;

  public DevAdministrationService(DevModuleStore store) {
    this.store = store;
  }

  public JsonNode overview() {
    var values = new LinkedHashMap<String, Object>();
    values.put("owner", false);
    values.put("ownerConfigured", false);
    values.put("username", "Development actor");
    values.put("permissions", List.of("audit.view"));
    values.put("implementedPermissions", List.of("audit.view"));
    values.put("authenticationDeferred", true);
    values.put(
        "customerCount",
        store.jdbc.queryForObject(
            "SELECT count(*) FROM dev_meta.customer_identity WHERE canonical_id IS NULL",
            Long.class));
    values.put(
        "draftCount",
        store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.drafts", Long.class));
    values.put(
        "pendingOutbox",
        store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.outbox", Long.class));
    values.put("database", "H2");
    return store.tree(values);
  }

  public JsonNode types() {
    return store.tree(
        java.util.Arrays.stream(Auditing.EventType.values())
            .map(
                t -> Map.of("action", t.name(), "category", t.category(), "optional", t.optional()))
            .toList());
  }

  public JsonNode settings() {
    var values = new java.util.TreeMap<String, Boolean>();
    for (var t : Auditing.EventType.values()) if (t.optional()) values.put(t.name(), true);
    return store.tree(
        Map.of(
            "branchId",
            store.branchId(),
            "revision",
            0,
            "settings",
            values,
            "editable",
            false,
            "message",
            "Owner authentication is deferred; tracking remains enabled."));
  }

  private JsonNode event(Map<String, Object> row) {
    var action = Auditing.EventType.valueOf(row.get("action").toString());
    var value = store.json.createObjectNode();
    value
        .put("eventId", row.get("event_id").toString())
        .put("occurredAt", row.get("recorded_at").toString())
        .put("recordedAt", row.get("recorded_at").toString())
        .put("staff", "Development actor")
        .put("terminal", "development:local-master")
        .put("action", action.name())
        .put("category", action.category())
        .put("optional", action.optional())
        .put("entityId", row.get("entity_id") == null ? null : row.get("entity_id").toString())
        .put(
            "transactionId",
            action.name().startsWith("DRAFT_") && row.get("entity_id") != null
                ? row.get("entity_id").toString()
                : null)
        .put("branchId", store.branchId())
        .put("sequence", ((Number) row.get("seq")).longValue())
        .put("revision", ((Number) row.get("revision")).longValue())
        .put("source", "H2_DEVELOPMENT");
    value.set("payload", store.parse(row.get("payload").toString()));
    return value;
  }

  public JsonNode history(Map<String, String> filters) {
    long cursor = Long.MAX_VALUE;
    try {
      if (!filters.getOrDefault("cursor", "").isBlank())
        cursor = Long.parseLong(filters.get("cursor"));
    } catch (NumberFormatException invalid) {
      throw DevModuleStore.fail(400, "Invalid audit cursor");
    }
    StringBuilder sql = new StringBuilder("SELECT * FROM dev_meta.module_events WHERE seq<?");
    var args = new java.util.ArrayList<Object>();
    args.add(cursor);
    for (String field : List.of("staff", "terminal"))
      if (!filters.getOrDefault(field, "").isBlank()
          && !filters
              .get(field)
              .equals(field.equals("staff") ? "Development actor" : "development:local-master"))
        return store.tree(
            Map.of(
                "branchId",
                store.branchId(),
                "items",
                List.of(),
                "hasMore",
                false,
                "nextCursor",
                ""));
    if (!filters.getOrDefault("action", "").isBlank()) {
      sql.append(" AND action=?");
      args.add(filters.get("action"));
    }
    if (!filters.getOrDefault("category", "").isBlank()) {
      var actions =
          java.util.Arrays.stream(Auditing.EventType.values())
              .filter(t -> t.category().equals(filters.get("category")))
              .map(Enum::name)
              .toList();
      if (actions.isEmpty())
        return store.tree(
            Map.of(
                "branchId",
                store.branchId(),
                "items",
                List.of(),
                "hasMore",
                false,
                "nextCursor",
                ""));
      sql.append(" AND action IN(")
          .append(String.join(",", java.util.Collections.nCopies(actions.size(), "?")))
          .append(")");
      args.addAll(actions);
    }
    if (!filters.getOrDefault("transaction", "").isBlank()) {
      sql.append(" AND entity_id=? AND action IN('DRAFT_CREATED','DRAFT_CANCELLED')");
      args.add(UUID.fromString(filters.get("transaction")));
    }
    for (String field : List.of("from", "to"))
      if (!filters.getOrDefault(field, "").isBlank()) {
        sql.append(field.equals("from") ? " AND recorded_at>=?" : " AND recorded_at<=?");
        args.add(java.time.OffsetDateTime.parse(filters.get(field)));
      }
    sql.append(" ORDER BY seq DESC LIMIT 51");
    var records =
        store.jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::event).toList();
    var page = records.stream().limit(50).toList();
    return store.tree(
        Map.of(
            "branchId",
            store.branchId(),
            "items",
            page,
            "hasMore",
            records.size() > 50,
            "nextCursor",
            page.isEmpty() ? "" : page.getLast().path("sequence").asText()));
  }

  public JsonNode detail(UUID id) {
    var rows = store.jdbc.queryForList("SELECT * FROM dev_meta.module_events WHERE event_id=?", id);
    if (rows.isEmpty()) throw DevModuleStore.fail(404, "Event not found");
    return event(rows.getFirst());
  }
}
