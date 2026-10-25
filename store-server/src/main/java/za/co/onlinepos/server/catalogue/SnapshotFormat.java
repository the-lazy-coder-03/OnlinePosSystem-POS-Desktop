package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.CatalogueSnapshot;
import za.co.onlinepos.server.local.CommandJournal;

/** Versioned allowlist audited against cloud ad708b93; unknown schema fails closed. */
public final class SnapshotFormat {
  public static final String POLICY = "CLOUD_V1_NO_SEPARATE_TAX_DELIVERY_DISCOUNT";
  public static final String SOURCE = "ad708b93e1026605f15e39dc02ebce790948ba0a";
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private final JsonNode schema;

  public SnapshotFormat() {
    try (var input = getClass().getResourceAsStream("/catalogue-schema-v1.json")) {
      schema = json.readTree(input);
    } catch (Exception failure) {
      throw new IllegalStateException("Catalogue schema unavailable", failure);
    }
  }

  public JsonNode schema() {
    return schema.deepCopy();
  }

  public JsonNode tree(Object value) {
    return canonical(json.valueToTree(value));
  }

  public JsonNode canonical(JsonNode node) {
    if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
    if (node.isObject()) {
      var sorted = new TreeMap<String, JsonNode>();
      node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
      var result = json.createObjectNode();
      sorted.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(n -> result.add(canonical(n)));
      return result;
    }
    return node;
  }

  public String encode(Object value) {
    return tree(value).toString();
  }

  public String hash(Object value) {
    return CommandJournal.digest(encode(value));
  }

  public List<JsonNode> rows(CatalogueSnapshot snapshot, String table) {
    var result = new ArrayList<JsonNode>();
    snapshot.tables().get(table).forEach(r -> result.add(tree(r)));
    result.sort(Comparator.comparing(r -> key(table, r)));
    return result;
  }

  public String key(String table, JsonNode row) {
    var result = json.createArrayNode();
    schema.get(table).get("keys").forEach(k -> result.add(row.get(k.asText())));
    return canonical(result).toString();
  }

  public Map<String, String> hashes(CatalogueSnapshot snapshot) {
    var result = new TreeMap<String, String>();
    schema.fieldNames().forEachRemaining(t -> result.put(t, hash(rows(snapshot, t))));
    return result;
  }

  public Map<String, Integer> counts(CatalogueSnapshot snapshot) {
    var result = new TreeMap<String, Integer>();
    schema.fieldNames().forEachRemaining(t -> result.put(t, snapshot.tables().get(t).size()));
    return result;
  }

  public String fingerprint(CatalogueSnapshot snapshot) {
    var value = (com.fasterxml.jackson.databind.node.ObjectNode) tree(snapshot);
    var tables = json.createObjectNode();
    schema.fieldNames().forEachRemaining(t -> tables.set(t, tree(rows(snapshot, t))));
    value.set("tables", tables);
    return hash(value);
  }

  public void validate(CatalogueSnapshot s) {
    if (s == null
        || s.protocolVersion() != 1
        || !SOURCE.equals(s.sourceRevision())
        || !POLICY.equals(s.pricingPolicy())) bad("Unsupported snapshot contract");
    Branch.fromId(s.branchId());
    CommandJournal.requireUuid(s.snapshotId());
    java.time.Instant.parse(s.capturedAt());
    var allowed = new HashSet<String>();
    schema.fieldNames().forEachRemaining(allowed::add);
    if (s.tables() == null || !allowed.equals(s.tables().keySet()))
      bad("Incomplete catalogue tables");
    int count = 0;
    for (String table : allowed) {
      if (s.tables().get(table) == null) bad("Missing rows");
      var keys = new HashSet<String>();
      var spec = schema.get(table);
      for (var row : rows(s, table)) {
        if (++count > 100000) bad("Snapshot too large");
        var fields = new HashSet<String>();
        row.fieldNames().forEachRemaining(fields::add);
        var columns = new HashSet<String>();
        spec.get("columns").fieldNames().forEachRemaining(columns::add);
        if (!fields.equals(columns)) bad("Unexpected columns in " + table);
        spec.get("columns")
            .properties()
            .forEach(
                e -> {
                  JsonNode v = row.get(e.getKey());
                  if (v.isNull()) {
                    if (e.getValue().get("required").asBoolean()) bad("Missing required column");
                  } else {
                    switch (e.getValue().get("type").asText()) {
                      case "INT", "BIGINT", "BIGSERIAL" -> {
                        try {
                          v.decimalValue().longValueExact();
                        } catch (ArithmeticException ex) {
                          bad("Invalid integer");
                        }
                        if (!v.isNumber()) bad("Invalid integer");
                      }
                      case "NUMERIC", "DOUBLE" -> {
                        if (!v.isNumber()) bad("Invalid decimal");
                        money(v);
                      }
                      case "BOOLEAN" -> {
                        if (!v.isBoolean()) bad("Invalid boolean");
                      }
                      case "DATE" -> java.time.LocalDate.parse(v.asText());
                      default -> {
                        if (!v.isTextual() || v.asText().length() > 10000) bad("Invalid text");
                      }
                    }
                  }
                });
        for (var k : spec.get("keys")) {
          if (row.get(k.asText()).isNull()) bad("Null source identity");
        }
        for (String column :
            List.of("quantity", "max_quantity", "default_qty", "protein_quantity_required")) {
          if (row.has(column) && row.get(column).asLong() < 1) bad("Invalid quantity");
        }
        if (row.has("min_select")
            && (row.get("min_select").asLong() < 0
                || row.get("max_select").asLong() < row.get("min_select").asLong()))
          bad("Invalid modifier limits");
        if (row.has("day_of_week")
            && (row.get("day_of_week").asInt() < 1 || row.get("day_of_week").asInt() > 7))
          bad("Invalid special day");
        if (row.has("starts_on")
            && !row.get("starts_on").isNull()
            && !row.get("ends_on").isNull()
            && java.time.LocalDate.parse(row.get("starts_on").asText())
                .isAfter(java.time.LocalDate.parse(row.get("ends_on").asText())))
          bad("Invalid special dates");
        if (row.has("product_type")
            && !List.of("MENU_ITEM", "PIZZA").contains(row.get("product_type").asText()))
          bad("Unsupported product type");
        if (row.has("selection_mode")
            && !List.of("INCLUDED", "CUSTOMER_CHOICE").contains(row.get("selection_mode").asText()))
          bad("Unsupported selection mode");
        if (!keys.add(key(table, row))) bad("Duplicate source identity");
        if (row.has("branch_id") && row.get("branch_id").asInt() != s.branchId())
          bad("Foreign branch row");
      }
    }
    if (rows(s, "branch").size() != 1 || !rows(s, "branch").getFirst().path("active").asBoolean())
      bad("Inactive or missing branch");
    String branchName = s.branchId() == 1 ? "Kenridge" : "Uitzicht";
    if (!branchName.equals(rows(s, "branch").getFirst().path("name").asText()))
      bad("Branch identity mismatch");
    // Every nullable and non-nullable foreign key is checked before any write.
    for (String table : allowed)
      for (JsonNode row : rows(s, table)) {
        schema
            .get(table)
            .get("references")
            .properties()
            .forEach(
                e -> {
                  var value = row.get(e.getKey());
                  if (!value.isNull()) {
                    String target = e.getValue().get(0).asText();
                    String column = e.getValue().get(1).asText();
                    if (rows(s, target).stream().noneMatch(r -> r.get(column).equals(value)))
                      bad("Broken reference in " + table);
                  }
                });
      }
    if (!counts(s).equals(s.counts()) || !hashes(s).equals(s.hashes()))
      bad("Snapshot manifest mismatch");
  }

  public static BigDecimal money(JsonNode value) {
    BigDecimal amount = value.decimalValue();
    if (amount.precision() > 19 || amount.scale() < -17 || amount.scale() > 128)
      bad("Invalid price precision");
    amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    if (amount.signum() < 0 || amount.precision() > 19) bad("Invalid price");
    return amount;
  }

  private static void bad(String message) {
    throw new IllegalArgumentException(message);
  }
}
