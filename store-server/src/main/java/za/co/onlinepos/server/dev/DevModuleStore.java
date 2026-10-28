package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.server.local.CommandJournal;

/** Database command boundary shared by all real development modules. */
@Service
@Profile("dev")
public class DevModuleStore {
  final JdbcTemplate jdbc;
  final ObjectMapper json;
  final TransactionTemplate tx;

  public DevModuleStore(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.json = json;
    this.tx = tx;
  }

  public int branchId() {
    var branch =
        jdbc.queryForObject(
            "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1", Integer.class);
    if (branch == null) throw fail(409, "Configure this Master's store in Settings first");
    return branch;
  }

  public String branchName() {
    return jdbc.queryForObject(
        "SELECT name FROM public.branch WHERE branch_id=?", String.class, branchId());
  }

  public void lock() {
    jdbc.queryForObject(
        "SELECT next_id FROM dev_meta.category_ids WHERE singleton=1 FOR UPDATE", Long.class);
  }

  public JsonNode tree(Object value) {
    return json.valueToTree(value);
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw fail(400, "Invalid command");
    }
  }

  public JsonNode parse(String value) {
    try {
      return json.reader()
          .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readTree(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Stored record is unreadable");
    }
  }

  public <T> T decode(JsonNode value, Class<T> type) {
    try {
      return json.treeToValue(value, type);
    } catch (Exception invalid) {
      throw fail(400, "Invalid request fields");
    }
  }

  public static DevCategoryService.Failure fail(int status, String message) {
    return new DevCategoryService.Failure(
        status, status == 409 ? "REVISION_CONFLICT" : "VALIDATION", "", message);
  }

  public JsonNode command(UUID id, String kind, Object value, Supplier<JsonNode> operation) {
    if (id == null) throw fail(400, "Command UUID required");
    String digest = CommandJournal.digest(encode(java.util.List.of(kind, value)));
    return tx.execute(
        status -> {
          lock();
          var receipt =
              jdbc.query(
                  "SELECT digest,result_json FROM dev_meta.commands WHERE command_id=?",
                  (rs, n) -> Map.of("digest", rs.getString(1), "result", rs.getString(2)),
                  id);
          if (!receipt.isEmpty()) {
            if (!receipt.getFirst().get("digest").equals(digest))
              throw new DevCategoryService.Failure(
                  409, "COMMAND_REUSED", "", "Command UUID was reused");
            return parse(receipt.getFirst().get("result"));
          }
          JsonNode result = parse(encode(operation.get()));
          jdbc.update("INSERT INTO dev_meta.commands VALUES(?,?,?)", id, digest, encode(result));
          return result;
        });
  }

  public void event(
      UUID command, String action, UUID entity, long revision, Map<String, Object> safe) {
    UUID event = UUID.randomUUID();
    String payload = encode(safe);
    jdbc.update(
        "INSERT INTO dev_meta.module_events(event_id,command_id,action,entity_id,revision,payload) VALUES(?,?,?,?,?,?)",
        event,
        command,
        action,
        entity,
        revision,
        payload);
    jdbc.update(
        "INSERT INTO dev_meta.outbox(event_id,body) VALUES(?,?)",
        event,
        encode(
            Map.of(
                "eventId",
                event,
                "action",
                action,
                "entityId",
                entity,
                "revision",
                revision,
                "payload",
                safe)));
  }

  public void queueRecord(UUID command, Object record) {
    for (var row :
        jdbc.query(
            "SELECT o.event_id,o.body FROM dev_meta.outbox o JOIN dev_meta.module_events e ON e.event_id=o.event_id WHERE e.command_id=?",
            (rs, n) -> Map.of("id", rs.getObject(1), "body", rs.getString(2)),
            command)) {
      var body = (com.fasterxml.jackson.databind.node.ObjectNode) parse(row.get("body").toString());
      body.set("record", tree(record));
      jdbc.update(
          "UPDATE dev_meta.outbox SET body=? WHERE event_id=?", encode(body), row.get("id"));
    }
  }

  public long revision() {
    return jdbc.queryForObject(
        "SELECT revision FROM dev_meta.catalogue_state WHERE singleton=1", Long.class);
  }

  public long bump() {
    jdbc.update("UPDATE dev_meta.catalogue_state SET revision=revision+1 WHERE singleton=1");
    return revision();
  }

  public static UUID identity(String kind, String key) {
    return UUID.nameUUIDFromBytes(
        ("onlinepos:h2:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
  }
}
