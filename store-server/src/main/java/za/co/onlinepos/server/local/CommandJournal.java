package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile({"installed", "local-store"})
public class CommandJournal {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final LocalDatabase.Identity identity;

  public CommandJournal(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ObjectMapper json,
      LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json =
        json.copy()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.identity = identity;
  }

  public JsonNode execute(
      UUID commandId,
      StaffService.Actor actor,
      String type,
      Object payload,
      Supplier<JsonNode> mutation) {
    requireUuid(commandId);
    String digest = digest(type + ":" + encode(canonical(tree(payload))));
    return transactions.execute(
        status -> {
          lockStream();
          requireActor(actor);
          var receipts =
              jdbc.query(
                  "SELECT actor_id,terminal_identity,payload_hash,result::text FROM pos.command_receipt WHERE command_id=?",
                  (rs, n) ->
                      new String[] {
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)
                      },
                  commandId);
          if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (!receipt[0].equals(actor.staffId().toString())
                || !receipt[1].equals(actor.terminal())
                || !receipt[2].equals(digest))
              throw new LocalFailure(
                  409, "Command identity already has different content or origin");
            return parse(receipt[3]);
          }
          JsonNode result = mutation.get();
          jdbc.update(
              "INSERT INTO pos.command_receipt(command_id,branch_id,actor_id,terminal_identity,payload_hash,result) VALUES (?,?,?,?,?,?::jsonb)",
              commandId,
              identity.branchId(),
              actor.staffId(),
              actor.terminal(),
              digest,
              encode(result));
          return result;
        });
  }

  public void requireActor(StaffService.Actor actor) {
    if (jdbc.queryForObject(
            "SELECT count(*) FROM pos.staff WHERE staff_id=? AND branch_id=? AND enabled AND role=? AND credential_version=?",
            Integer.class,
            actor.staffId(),
            identity.branchId(),
            actor.role(),
            actor.credentialVersion())
        != 1) throw new LocalFailure(401, "Staff credentials changed");
  }

  public void lockStream() {
    jdbc.queryForObject(
        "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
        Long.class,
        identity.branchId());
  }

  public void event(
      StaffService.Actor actor,
      String type,
      UUID aggregate,
      long revision,
      UUID command,
      Object safePayload) {
    long position =
        jdbc.queryForObject(
            "UPDATE pos.stream_head SET position=position+1 WHERE branch_id=? RETURNING position",
            Long.class,
            identity.branchId());
    String payload = encode(safePayload);
    jdbc.update(
        "INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,command_id,details) VALUES (?,?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        identity.branchId(),
        actor.staffId(),
        type,
        aggregate,
        command,
        payload);
    jdbc.update(
        "INSERT INTO pos.outbox_event(event_id,branch_id,stream_position,aggregate_id,aggregate_revision,event_type,payload) VALUES (?,?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        identity.branchId(),
        position,
        aggregate,
        revision,
        type,
        payload);
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isNumber())
      return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
          node.decimalValue().stripTrailingZeros());
    if (node.isObject()) {
      var sorted = json.createObjectNode();
      var fields = new java.util.TreeMap<String, JsonNode>();
      node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
      fields.forEach((key, value) -> sorted.set(key, canonical(value)));
      return sorted;
    }
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(value -> result.add(canonical(value)));
      return result;
    }
    return node;
  }

  public JsonNode tree(Object value) {
    return canonical(json.valueToTree(value));
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid payload", exception);
    }
  }

  public JsonNode parse(String value) {
    try {
      return canonical(json.readTree(value));
    } catch (Exception exception) {
      throw new IllegalStateException("Unreadable persisted payload", exception);
    }
  }

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static void requireUuid(UUID id) {
    if (id == null || id.equals(new UUID(0, 0)))
      throw new LocalFailure(400, "Non-nil UUID required");
  }
}
