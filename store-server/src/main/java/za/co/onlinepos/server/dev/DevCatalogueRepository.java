package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import za.co.onlinepos.server.catalogue.SnapshotFormat;

/** Allowlisted SQL-source records are the single catalogue source of truth. */
@Repository
@Profile("dev")
public class DevCatalogueRepository {
  final DevModuleStore store;
  final JsonNode schema = new SnapshotFormat(true).schema();

  public DevCatalogueRepository(DevModuleStore store) {
    this.store = store;
  }

  public DevModuleStore store() {
    return store;
  }

  public JsonNode schema() {
    return schema.deepCopy();
  }

  List<String> keys(String table) {
    var keys = new ArrayList<String>();
    metadata(table).path("keys").forEach(n -> keys.add(n.asText()));
    return keys;
  }

  JsonNode metadata(String table) {
    if (!schema.has(table)) throw DevModuleStore.fail(400, "Unsupported catalogue table");
    return schema.path(table);
  }

  List<String> columns(String table) {
    var columns = new ArrayList<String>();
    metadata(table).path("columns").fieldNames().forEachRemaining(columns::add);
    return columns;
  }

  public List<JsonNode> rows(String table) {
    var columns = columns(table);
    return store.jdbc.query(
        "SELECT "
            + String.join(",", columns)
            + " FROM public."
            + table
            + " ORDER BY "
            + String.join(",", keys(table)),
        (rs, n) -> {
          var row = store.json.createObjectNode();
          for (String column : columns) {
            Object value = rs.getObject(column);
            if (value instanceof java.sql.Date date) row.put(column, date.toLocalDate().toString());
            else if (value instanceof java.time.temporal.TemporalAccessor)
              row.put(column, value.toString());
            else row.set(column, store.tree(value));
          }
          return (JsonNode) row;
        });
  }

  public Map<String, List<JsonNode>> tables(boolean branchOnly) {
    var result = new LinkedHashMap<String, List<JsonNode>>();
    schema.fieldNames().forEachRemaining(table -> result.put(table, rows(table)));
    return branchOnly ? branchTables(result, store.branchId()) : result;
  }

  private Map<String, List<JsonNode>> branchTables(Map<String, List<JsonNode>> tables, int branch) {
    var result = new LinkedHashMap<String, List<JsonNode>>();
    tables.forEach(
        (table, rows) ->
            result.put(
                table,
                rows.stream()
                    .filter(row -> !row.has("branch_id") || row.path("branch_id").asInt() == branch)
                    .toList()));
    var specials =
        result.get("special").stream()
            .map(row -> row.path("special_id").asText())
            .collect(java.util.stream.Collectors.toSet());
    for (String table : List.of("special_day", "special_component", "special_addon"))
      result.put(
          table,
          result.get(table).stream()
              .filter(row -> specials.contains(row.path("special_id").asText()))
              .toList());
    var components =
        result.get("special_component").stream()
            .map(row -> row.path("special_component_id").asText())
            .collect(java.util.stream.Collectors.toSet());
    for (String table : List.of("special_component_menu_item", "special_component_pizza"))
      result.put(
          table,
          result.get(table).stream()
              .filter(row -> components.contains(row.path("special_component_id").asText()))
              .toList());
    return result;
  }

  public void validateAll() {
    var all = tables(false);
    var format = new SnapshotFormat(true);
    for (int branch :
        store.jdbc.queryForList(
            "SELECT branch_id FROM public.branch ORDER BY branch_id", Integer.class)) {
      var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
      branchTables(all, branch)
          .forEach(
              (table, rows) ->
                  tables.put(
                      table,
                      rows.stream()
                          .map(
                              row ->
                                  store.json.convertValue(
                                      row,
                                      new com.fasterxml.jackson.core.type.TypeReference<
                                          Map<String, Object>>() {}))
                          .toList()));
      var initial =
          new za.co.onlinepos.contracts.CatalogueSnapshot(
              1,
              DevModuleStore.identity("validation", Integer.toString(branch)),
              branch,
              SnapshotFormat.SOURCE,
              java.time.Instant.now().toString(),
              SnapshotFormat.POLICY,
              tables,
              Map.of(),
              Map.of());
      format.validate(
          new za.co.onlinepos.contracts.CatalogueSnapshot(
              1,
              initial.snapshotId(),
              branch,
              initial.sourceRevision(),
              initial.capturedAt(),
              initial.pricingPolicy(),
              tables,
              format.counts(initial),
              format.hashes(initial)));
    }
    if (store.jdbc.queryForObject(
            "SELECT count(*) FROM public.modifier_group WHERE required=TRUE AND min_select<1",
            Integer.class)
        > 0) throw DevModuleStore.fail(400, "Required groups need a minimum selection");
    if (store.jdbc.queryForObject(
            "SELECT count(*) FROM public.branch_pizza_price p WHERE NOT EXISTS(SELECT 1 FROM public.pizza_allowed_size a WHERE a.pizza_id=p.pizza_id AND a.pizza_size_id=p.pizza_size_id)",
            Integer.class)
        > 0) throw DevModuleStore.fail(400, "Pizza price requires an allowed size");
  }

  List<Object> key(JsonNode row, String table) {
    return keys(table).stream()
        .map(
            k ->
                value(
                    k.equals("archived")
                        ? row.path(k).isMissingNode()
                            ? com.fasterxml.jackson.databind.node.BooleanNode.FALSE
                            : row.get(k)
                        : row.get(k)))
        .toList();
  }

  String where(String table) {
    return String.join(" AND ", keys(table).stream().map(k -> k + "=?").toList());
  }

  Object value(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isBoolean()) return node.booleanValue();
    if (node.isNumber()) return node.decimalValue();
    return node.asText();
  }

  public void insert(String table, JsonNode row) {
    var columns = columns(table);
    store.jdbc.update(
        "INSERT INTO public."
            + table
            + "("
            + String.join(",", columns)
            + ") VALUES("
            + String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
            + ")",
        columns.stream()
            .map(
                k ->
                    value(
                        k.equals("archived")
                            ? row.path(k).isMissingNode()
                                ? com.fasterxml.jackson.databind.node.BooleanNode.FALSE
                                : row.get(k)
                            : row.get(k)))
            .toArray());
  }

  public void update(String table, JsonNode row, List<Long> original) {
    var columns = columns(table);
    var args = new ArrayList<Object>();
    columns.forEach(
        k ->
            args.add(
                value(
                    k.equals("archived")
                        ? row.path(k).isMissingNode()
                            ? com.fasterxml.jackson.databind.node.BooleanNode.FALSE
                            : row.get(k)
                        : row.get(k))));
    args.addAll(original);
    if (store.jdbc.update(
            "UPDATE public."
                + table
                + " SET "
                + String.join(",", columns.stream().map(k -> k + "=?").toList())
                + " WHERE "
                + where(table),
            args.toArray())
        != 1) throw DevModuleStore.fail(409, "Record changed or was removed");
  }

  public void validate(String table, Map<String, Object> values) {
    var meta = metadata(table).path("columns");
    if (!new java.util.HashSet<>(columns(table)).containsAll(values.keySet()))
      throw DevModuleStore.fail(400, "Unsupported catalogue field");
    for (String column : columns(table)) {
      var definition = meta.path(column);
      Object value = values.get(column);
      if (value == null) {
        if (definition.path("required").asBoolean())
          throw DevModuleStore.fail(400, "Required catalogue field: " + column);
        continue;
      }
      String type = definition.path("type").asText();
      if (type.equals("BOOLEAN") && !(value instanceof Boolean))
        throw DevModuleStore.fail(400, "Boolean required: " + column);
      if (type.equals("INT") || type.equals("BIGINT")) {
        try {
          new BigDecimal(value.toString()).longValueExact();
        } catch (Exception invalid) {
          throw DevModuleStore.fail(400, "Integer required: " + column);
        }
      }
      if (List.of("DOUBLE", "NUMERIC", "DECIMAL").contains(type)
          || List.of("price", "additional_price", "bundle_price").contains(column)) {
        try {
          if (new BigDecimal(value.toString())
                  .setScale(2, java.math.RoundingMode.UNNECESSARY)
                  .signum()
              < 0) throw new IllegalArgumentException();
        } catch (Exception invalid) {
          throw DevModuleStore.fail(400, "Nonnegative two-decimal amount required: " + column);
        }
      }
      if (value instanceof String text
          && (text.length() > 4000 || (definition.path("required").asBoolean() && text.isBlank())))
        throw DevModuleStore.fail(400, "Invalid catalogue text: " + column);
    }
  }
}
