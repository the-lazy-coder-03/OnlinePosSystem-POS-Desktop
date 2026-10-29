package za.co.onlinepos.server.recipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared resolution for product editing, protein validation and authoritative pricing. */
public final class BurgerRecipes {
  private BurgerRecipes() {}

  public static List<JsonNode> effective(Map<String, List<JsonNode>> tables, long burger) {
    return resolve(tables, burger, false);
  }

  public static List<JsonNode> configured(Map<String, List<JsonNode>> tables, long burger) {
    return resolve(tables, burger, true);
  }

  private static List<JsonNode> resolve(
      Map<String, List<JsonNode>> tables, long burger, boolean includeInactive) {
    var result = new LinkedHashMap<Long, JsonNode>();
    for (var a : tables.getOrDefault("burger_recipe_assignment", List.of()))
      if (a.path("burger_id").asLong() == burger
          && tables.getOrDefault("burger_recipe", List.of()).stream()
              .anyMatch(
                  r ->
                      r.path("recipe_id").equals(a.path("recipe_id"))
                          && (includeInactive || r.path("active").asBoolean())))
        for (var c : tables.getOrDefault("burger_recipe_component", List.of()))
          if (c.path("recipe_id").asLong() == a.path("recipe_id").asLong()) {
            var row = (ObjectNode) c.deepCopy();
            row.put("origin", "Inherited");
            result.put(c.path("component_id").asLong(), row);
          }
    for (var c : tables.getOrDefault("burger_item_default_component", List.of()))
      if (c.path("burger_id").asLong() == burger) {
        var row = (ObjectNode) c.deepCopy();
        row.put("origin", "Product-specific");
        result.put(c.path("component_id").asLong(), row);
      }
    for (var c : tables.getOrDefault("burger_item_component_override", List.of()))
      if (c.path("burger_id").asLong() == burger) {
        long id = c.path("component_id").asLong();
        if (c.path("excluded").asBoolean()) result.remove(id);
        else if (result.get(id) instanceof ObjectNode row) {
          for (String field : List.of("is_removable", "sort_order"))
            if (c.hasNonNull(field)) row.set(field, c.get(field));
          row.put("origin", "Overridden");
        }
      }
    return new ArrayList<>(result.values());
  }

  public static Map<String, List<JsonNode>> tables(JsonNode state) {
    var result = new LinkedHashMap<String, List<JsonNode>>();
    state
        .path("tables")
        .properties()
        .forEach(
            e -> {
              var rows = new ArrayList<JsonNode>();
              e.getValue().forEach(rows::add);
              result.put(e.getKey(), rows);
            });
    return result;
  }
}
