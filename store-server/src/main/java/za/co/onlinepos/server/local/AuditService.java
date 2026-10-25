package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.Auditing.EventType;

/** One append-only recording/query boundary. The database is the final policy authority. */
@Service
@Profile({"installed", "local-store"})
public class AuditService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final LocalDatabase.Identity identity;

  public AuditService(JdbcTemplate jdbc, ObjectMapper json, LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.json = json;
    this.identity = identity;
  }

  public boolean isTrackingEnabled(EventType type) {
    if (!type.optional()) return true;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT enabled FROM pos.audit_tracking_settings WHERE branch_id=? AND event_type=?",
            Boolean.class,
            identity.branchId(),
            type.name()));
  }

  public void record(
      UUID actor, String terminal, String action, UUID entity, UUID command, Object safeDetails) {
    EventType.valueOf(action);
    UUID id =
        command == null
            ? UUID.randomUUID()
            : UUID.nameUUIDFromBytes(
                (command + ":" + action + ":" + entity).getBytes(StandardCharsets.UTF_8));
    var details = json.valueToTree(safeDetails);
    redact(details);
    jdbc.queryForObject(
        "SELECT pos.record_audit_event(?,?,?,?,?,?,?,?::jsonb,NULL,?,NULL,'LOCAL_MASTER')",
        Object.class,
        id,
        identity.branchId(),
        actor,
        terminal,
        action,
        entity,
        command,
        details.toString(),
        action.startsWith("DRAFT_") ? entity : null);
  }

  private static void redact(JsonNode node) {
    if (node.isObject()) {
      var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
      var remove = new ArrayList<String>();
      object
          .properties()
          .forEach(
              e -> {
                String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("password")
                    || key.contains("token")
                    || key.contains("secret")
                    || key.contains("hash")
                    || key.contains("lookup")
                    || key.contains("cvv")
                    || key.contains("cardnumber")
                    || key.equals("pin")
                    || key.contains("email")
                    || key.contains("phone")
                    || key.contains("address")) remove.add(e.getKey());
                else redact(e.getValue());
              });
      remove.forEach(object::remove);
    } else if (node.isArray()) node.forEach(AuditService::redact);
  }

  public record Search(
      Instant from,
      Instant to,
      String terminal,
      String staff,
      UUID transaction,
      String category,
      String action,
      String cursor) {}

  public Map<String, Object> history(Search search) {
    if (search.from() != null && search.to() != null && search.from().isAfter(search.to()))
      throw new LocalFailure(400, "Invalid date range");
    var sql =
        new StringBuilder(
            " FROM pos.audit_record a LEFT JOIN pos.audit_event_type t ON t.action=a.action WHERE a.branch_id=?");
    var args = new ArrayList<Object>();
    args.add(identity.branchId());
    if (search.from() != null) {
      sql.append(" AND a.occurred_at>=?");
      args.add(java.sql.Timestamp.from(search.from()));
    }
    if (search.to() != null) {
      sql.append(" AND a.occurred_at<=?");
      args.add(java.sql.Timestamp.from(search.to()));
    }
    filter(sql, args, "a.terminal_identity", search.terminal());
    filter(sql, args, "a.username_snapshot", search.staff());
    filter(sql, args, "t.category", search.category());
    filter(sql, args, "a.action", search.action());
    if (search.transaction() != null) {
      sql.append(" AND a.transaction_id=?");
      args.add(search.transaction());
    }
    if (search.cursor() != null && !search.cursor().isBlank()) {
      try {
        if (search.cursor().length() > 200) throw new IllegalArgumentException();
        String[] parts =
            new String(Base64.getUrlDecoder().decode(search.cursor()), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        sql.append(" AND (a.occurred_at,a.audit_id)<(?,?)");
        args.add(java.sql.Timestamp.from(Instant.parse(parts[0])));
        args.add(UUID.fromString(parts[1]));
      } catch (IllegalArgumentException | java.time.DateTimeException e) {
        throw new LocalFailure(400, "Invalid audit cursor");
      }
    }
    var items =
        jdbc.queryForList(
            "SELECT a.audit_id AS \"eventId\",a.occurred_at AS \"occurredAt\",a.recorded_at AS \"recordedAt\",a.username_snapshot AS staff,a.actor_id AS \"staffId\",a.action,t.category,a.transaction_id AS \"transactionId\",a.terminal_identity AS terminal,a.branch_sequence AS \"sequence\""
                + sql
                + " ORDER BY a.occurred_at DESC,a.audit_id DESC LIMIT 51",
            args.toArray());
    boolean more = items.size() > 50;
    var page = new ArrayList<>(items.subList(0, Math.min(50, items.size())));
    String next = "";
    if (more) {
      var last = page.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (((java.sql.Timestamp) last.get("occurredAt")).toInstant()
                          + "|"
                          + last.get("eventId"))
                      .getBytes(StandardCharsets.UTF_8));
    }
    return Map.of(
        "items", page, "hasMore", more, "nextCursor", next, "branchId", identity.branchId());
  }

  private static void filter(StringBuilder sql, List<Object> args, String column, String value) {
    if (value != null && !value.isBlank()) {
      if (value.length() > 512) throw new LocalFailure(400, "Filter too long");
      sql.append(" AND ").append(column).append("=?");
      args.add(value.strip());
    }
  }

  public Map<String, Object> detail(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT a.*,t.category FROM pos.audit_record a LEFT JOIN pos.audit_event_type t ON t.action=a.action WHERE a.branch_id=? AND a.audit_id=?",
            identity.branchId(),
            id);
    if (rows.isEmpty()) throw new LocalFailure(404, "Audit event not found");
    var result = new HashMap<>(rows.getFirst());
    // Only events produced by the new allowlisted writer expose details. Legacy JSON stays private.
    if (result.get("recorded_at") == null)
      result.put("details", Map.of("notice", "Legacy event details unavailable"));
    else
      try {
        result.put("details", json.readTree(result.get("details").toString()));
      } catch (java.io.IOException e) {
        throw new LocalFailure(503, "Unreadable audit record");
      }
    return result;
  }
}
