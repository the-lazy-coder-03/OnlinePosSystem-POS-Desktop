package za.co.onlinepos.server.recipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import za.co.onlinepos.contracts.CatalogueManagement.Change;
import za.co.onlinepos.contracts.Recipes;

/** Pure diff planner shared by JDBC stores. Never infers food types from names. */
public final class RecipePlanner {
  private static final ObjectMapper JSON = new ObjectMapper();

  private RecipePlanner() {}

  static List<JsonNode> rows(JsonNode state, String table) {
    var result = new ArrayList<JsonNode>();
    state.path("tables").path(table).forEach(result::add);
    return result;
  }

  private static JsonNode product(JsonNode state, Recipes.Product p) {
    return rows(state, p.kind()).stream()
        .filter(r -> r.path(p.kind().equals("pizza") ? "pizza_id" : "id").asLong() == p.id())
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Product no longer exists"));
  }

  private static String prefix(String table) {
    return table.startsWith("burger")
        ? "burger:"
        : table.equals("salad_ingredients") ? "salad:" : "ingredient:";
  }

  private static String child(String table) {
    return table.startsWith("burger")
        ? "component_id"
        : table.equals("salad_ingredients") ? "id" : "ingredient_id";
  }

  private static String parent(String table) {
    return switch (table) {
      case "pizza_default_ingredient" -> "pizza_id";
      case "menu_item_ingredient" -> "menu_item_id";
      case "burger_recipe_component" -> "recipe_id";
      case "salad_ingredients" -> "salad_id";
      default -> "burger_id";
    };
  }

  public static String family(JsonNode state, Recipes.Product p) {
    if (p.kind().equals("pizza")) return "ingredient";
    return rows(state, "burger_recipe_assignment").stream()
                .anyMatch(r -> r.path("burger_id").asLong() == p.id())
            || rows(state, "burger_item_default_component").stream()
                .anyMatch(r -> r.path("burger_id").asLong() == p.id())
        ? "burger"
        : "ingredient";
  }

  private static Map<String, List<JsonNode>> links(JsonNode state, Recipes.Product p) {
    product(state, p);
    var result = new LinkedHashMap<String, List<JsonNode>>();
    if (p.kind().equals("pizza"))
      result.put(
          "pizza_default_ingredient",
          rows(state, "pizza_default_ingredient").stream()
              .filter(r -> r.path("pizza_id").asLong() == p.id())
              .toList());
    else {
      for (String t :
          List.of("menu_item_ingredient", "burger_item_default_component", "salad_ingredients"))
        result.put(
            t, rows(state, t).stream().filter(r -> r.path(parent(t)).asLong() == p.id()).toList());
      for (var a : rows(state, "burger_recipe_assignment"))
        if (a.path("burger_id").asLong() == p.id())
          result.put(
              "burger_recipe_component",
              rows(state, "burger_recipe_component").stream()
                  .filter(r -> r.path("recipe_id").equals(a.path("recipe_id")))
                  .toList());
    }
    return result;
  }

  public static JsonNode view(JsonNode state, Recipes.Product p, String requestedFamily) {
    product(state, p);
    String f = requestedFamily == null ? family(state, p) : requestedFamily;
    if (!Set.of("ingredient", "burger").contains(f)
        || (p.kind().equals("pizza") && !f.equals("ingredient")))
      throw new IllegalArgumentException("Incompatible ingredient family");
    var origins = new LinkedHashMap<String, List<Map<String, Object>>>();
    links(state, p)
        .forEach(
            (table, list) ->
                list.forEach(
                    r -> {
                      String key = prefix(table) + r.path(child(table)).asLong();
                      origins
                          .computeIfAbsent(key, k -> new ArrayList<>())
                          .add(
                              Map.of(
                                  "table", table, "properties", JSON.convertValue(r, Map.class)));
                    }));
    for (var row : rows(state, "burger_item_component_override"))
      if (p.kind().equals("menu_item") && row.path("burger_id").asLong() == p.id())
        origins
            .computeIfAbsent("burger:" + row.path("component_id").asLong(), k -> new ArrayList<>())
            .add(
                Map.of(
                    "table",
                    "burger_item_component_override",
                    "properties",
                    JSON.convertValue(row, Map.class)));
    var choices = new LinkedHashMap<String, Map<String, Object>>();
    for (String type : List.of("ingredient", "burger"))
      for (var r : rows(state, type.equals("ingredient") ? "ingredient" : "burger_component")) {
        String key =
            type
                + ":"
                + r.path(type.equals("ingredient") ? "ingredient_id" : "component_id").asLong();
        if (type.equals(f) || origins.containsKey(key))
          choices.put(
              key,
              Map.of(
                  "key",
                  key,
                  "name",
                  r.path("name").asText(),
                  "type",
                  type.equals("burger") ? r.path("component_type").asText() : "ingredient",
                  "available",
                  r.path("active").asBoolean(),
                  "compatible",
                  type.equals(f),
                  "selected",
                  origins.containsKey(key)
                      && (!key.startsWith("burger:")
                          || BurgerRecipes.configured(BurgerRecipes.tables(state), p.id()).stream()
                              .anyMatch(
                                  c ->
                                      c.path("component_id").asLong()
                                          == Long.parseLong(key.substring(7)))),
                  "origins",
                  origins.getOrDefault(key, List.of())));
      }
    for (var r : rows(state, "salad_ingredients"))
      if (p.kind().equals("menu_item") && r.path("salad_id").asLong() == p.id()) {
        String key = "salad:" + r.path("id").asLong();
        choices.put(
            key,
            Map.of(
                "key",
                key,
                "name",
                r.path("ingredient_name").asText(),
                "available",
                true,
                "compatible",
                false,
                "selected",
                true,
                "origins",
                origins.get(key)));
      }
    var affected = new ArrayList<String>();
    for (var a : rows(state, "burger_recipe_assignment"))
      if (a.path("burger_id").asLong() == p.id())
        for (var b : rows(state, "burger_recipe_assignment"))
          if (b.path("recipe_id").equals(a.path("recipe_id")))
            for (var item : rows(state, "menu_item"))
              if (item.path("id").equals(b.path("burger_id")))
                affected.add(item.path("name").asText());
    return JSON.valueToTree(
        Map.of(
            "productName",
            product(state, p).path("name").asText(),
            "revision",
            state.path("revision").asLong(),
            "editable",
            state.path("editable").asBoolean(),
            "product",
            p,
            "family",
            f,
            "ingredients",
            choices.values(),
            "affectedItems",
            affected));
  }

  public static List<Change> changes(JsonNode state, Recipes.Save save) {
    if (save != null
        && save.product() != null
        && family(state, save.product()).equals("burger")
        && "ingredient".equals(save.family())) {
      var result = new ArrayList<Change>();
      burgerChanges(state, save).stream()
          .filter(c -> c.table().startsWith("burger"))
          .forEach(result::add);
      var copy = state.deepCopy();
      for (String table :
          List.of(
              "burger_recipe_component",
              "burger_item_default_component",
              "burger_item_component_override"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("tables")).putArray(table);
      var properties = new LinkedHashMap<String, Map<String, Object>>();
      save.properties()
          .forEach(
              (key, value) -> {
                if (!key.startsWith("burger:")) properties.put(key, value);
              });
      result.addAll(
          legacyChanges(
              copy,
              new Recipes.Save(
                  save.commandId(),
                  save.expectedRevision(),
                  save.product(),
                  save.family(),
                  save.selected().stream().filter(k -> !k.startsWith("burger:")).toList(),
                  properties,
                  false)));
      validateProteins(state, result);
      return result;
    }
    if (save != null && "burger".equals(save.family())) return burgerChanges(state, save);
    return legacyChanges(state, save);
  }

  public static JsonNode sharedView(JsonNode state, Recipes.Product product) {
    return view(sharedState(state), product, "burger");
  }

  private static JsonNode sharedState(JsonNode state) {
    var copy = state.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("tables"))
        .putArray("burger_item_default_component");
    ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("tables"))
        .putArray("burger_item_component_override");
    return copy;
  }

  public static List<Change> sharedChanges(JsonNode state, Recipes.Save save) {
    if (!save.confirmSharedRecipe() || !"burger".equals(save.family()))
      throw new IllegalArgumentException("Confirm shared recipe changes");
    var changes = legacyChanges(sharedState(state), save);
    if (changes.stream().anyMatch(c -> !c.table().equals("burger_recipe_component")))
      throw new IllegalArgumentException("Assign a shared recipe first");
    validateProteins(state, changes);
    return changes;
  }

  private static List<Change> burgerChanges(JsonNode state, Recipes.Save save) {
    if (save.product() == null
        || !save.product().kind().equals("menu_item")
        || save.selected() == null
        || save.selected().size() > 2000
        || save.properties() == null) throw new IllegalArgumentException("Invalid burger recipe");
    var selected = new HashSet<>(save.selected());
    if (selected.size() != save.selected().size()
        || !selected.containsAll(save.properties().keySet()))
      throw new IllegalArgumentException("Invalid ingredient selection");
    var known = new HashMap<String, JsonNode>();
    view(state, save.product(), save.family())
        .path("ingredients")
        .forEach(c -> known.put(c.path("key").asText(), c));
    for (String key : selected) {
      var c = known.get(key);
      if (c == null
          || (!c.path("selected").asBoolean()
              && (!c.path("available").asBoolean() || !c.path("compatible").asBoolean())))
        throw new IllegalArgumentException("Ingredient unavailable or incompatible");
    }
    long burger = save.product().id();
    var shared = new HashMap<Long, JsonNode>();
    for (var a : rows(state, "burger_recipe_assignment"))
      if (a.path("burger_id").asLong() == burger)
        for (var c : rows(state, "burger_recipe_component"))
          if (c.path("recipe_id").asLong() == a.path("recipe_id").asLong())
            shared.put(c.path("component_id").asLong(), c);
    var additions = new HashMap<Long, JsonNode>();
    var overrides = new HashMap<Long, JsonNode>();
    for (var c : rows(state, "burger_item_default_component"))
      if (c.path("burger_id").asLong() == burger) additions.put(c.path("component_id").asLong(), c);
    for (var c : rows(state, "burger_item_component_override"))
      if (c.path("burger_id").asLong() == burger) overrides.put(c.path("component_id").asLong(), c);
    var ids = new java.util.TreeSet<Long>();
    ids.addAll(shared.keySet());
    ids.addAll(additions.keySet());
    ids.addAll(overrides.keySet());
    for (String key : selected) {
      if (!key.startsWith("burger:")) {
        continue;
      }
      ids.add(Long.parseLong(key.substring(7)));
    }
    var changes = new ArrayList<Change>();
    int order = 0;
    for (long id : ids) {
      String key = "burger:" + id;
      var identity = List.of(burger, id);
      var old = overrides.get(id);
      var addition = additions.get(id);
      if (save.restoreInherited() != null && save.restoreInherited().contains(key)) {
        if (old != null)
          changes.add(
              new Change(
                  "burger_item_component_override",
                  identity,
                  JSON.convertValue(old, Map.class),
                  true));
        if (addition != null && shared.containsKey(id))
          changes.add(
              new Change(
                  "burger_item_default_component",
                  identity,
                  JSON.convertValue(addition, Map.class),
                  true));
        continue;
      }
      var props = save.properties().getOrDefault(key, Map.of());
      for (var prop : props.entrySet()) {
        if (prop.getKey().equals("is_removable")) {
          if (!(prop.getValue() instanceof Boolean))
            throw new IllegalArgumentException("Invalid removable status");
        } else if (prop.getKey().equals("sort_order")) {
          try {
            new java.math.BigDecimal(prop.getValue().toString()).intValueExact();
          } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid ingredient order");
          }
        } else throw new IllegalArgumentException("Unsupported burger property");
      }
      if (shared.containsKey(id) || old != null) {
        var data = new LinkedHashMap<String, Object>();
        data.put("burger_id", burger);
        data.put("component_id", id);
        data.put("excluded", !selected.contains(key));
        data.put(
            "is_removable",
            old == null ? null : JSON.convertValue(old.get("is_removable"), Object.class));
        data.put(
            "sort_order",
            old == null ? null : JSON.convertValue(old.get("sort_order"), Object.class));
        data.putAll(props);
        if (Boolean.TRUE.equals(data.get("excluded"))
            || data.get("is_removable") != null
            || data.get("sort_order") != null) {
          if (!JSON.valueToTree(data).equals(old))
            changes.add(
                new Change(
                    "burger_item_component_override", old == null ? null : identity, data, false));
        } else if (old != null)
          changes.add(new Change("burger_item_component_override", identity, data, true));
      }
      if (!selected.contains(key)) {
        if (addition != null)
          changes.add(
              new Change(
                  "burger_item_default_component",
                  identity,
                  JSON.convertValue(addition, Map.class),
                  true));
      } else if (!shared.containsKey(id)) {
        Map<String, Object> data =
            addition == null ? new LinkedHashMap<>() : JSON.convertValue(addition, Map.class);
        data.put("burger_id", burger);
        data.put("component_id", id);
        data.putIfAbsent("is_removable", true);
        data.putIfAbsent("sort_order", ++order);
        data.putAll(props);
        if (!JSON.valueToTree(data).equals(addition))
          changes.add(
              new Change(
                  "burger_item_default_component",
                  addition == null ? null : identity,
                  data,
                  false));
      }
    }
    // Preserve or explicitly remove legacy ingredient-family links without modifying shared rows.
    for (var entry : links(state, save.product()).entrySet())
      if (!entry.getKey().startsWith("burger"))
        for (var c : entry.getValue()) {
          String key = prefix(entry.getKey()) + c.path(child(entry.getKey())).asLong();
          if (!selected.contains(key))
            changes.add(
                new Change(
                    entry.getKey(),
                    entry.getKey().equals("salad_ingredients")
                        ? List.of(c.path("id").asLong())
                        : List.of(burger, c.path(child(entry.getKey())).asLong()),
                    JSON.convertValue(c, Map.class),
                    true));
        }
    validateProteins(state, changes);
    return changes;
  }

  private static List<Change> legacyChanges(JsonNode state, Recipes.Save save) {
    if (save == null
        || save.product() == null
        || save.family() == null
        || !Set.of("ingredient", "burger").contains(save.family())
        || save.selected() == null
        || save.selected().size() > 2000
        || save.properties() == null)
      throw new IllegalArgumentException("Invalid recipe selection");
    var selected = new HashSet<>(save.selected());
    if (selected.size() != save.selected().size())
      throw new IllegalArgumentException("Duplicate ingredient selection");
    var view = view(state, save.product(), save.family());
    var known = new HashMap<String, JsonNode>();
    view.path("ingredients").forEach(r -> known.put(r.path("key").asText(), r));
    for (String key : selected) {
      var r = known.get(key);
      if (r == null
          || (!r.path("selected").asBoolean()
              && (!r.path("available").asBoolean() || !r.path("compatible").asBoolean())))
        throw new IllegalArgumentException("Ingredient unavailable or incompatible");
    }
    if (!selected.containsAll(save.properties().keySet()))
      throw new IllegalArgumentException("Properties require a selected ingredient");
    var current = links(state, save.product());
    var oldKeys = new HashSet<String>();
    var result = new ArrayList<Change>();
    int max = 0;
    for (var entry : current.entrySet())
      for (var row : entry.getValue()) {
        String t = entry.getKey(), key = prefix(t) + row.path(child(t)).asLong();
        oldKeys.add(key);
        max = Math.max(max, row.path("sort_order").asInt());
        var identity = new ArrayList<Long>();
        identity.add(row.path(parent(t)).asLong());
        if (!t.equals("salad_ingredients")) identity.add(row.path(child(t)).asLong());
        else {
          identity.clear();
          identity.add(row.path("id").asLong());
        }
        Map<String, Object> values = JSON.convertValue(row, Map.class);
        if (!selected.contains(key)) {
          result.add(new Change(t, identity, values, true));
          continue;
        }
        var properties = save.properties().getOrDefault(key, Map.of());
        for (var property : properties.entrySet()) {
          String col = property.getKey();
          if (!Set.of("default_qty", "is_removable", "sort_order").contains(col) || !row.has(col))
            throw new IllegalArgumentException("Unsupported recipe property");
          Object v = property.getValue();
          if (v == null) throw new IllegalArgumentException("Recipe properties cannot be null");
          if (col.equals("is_removable")) {
            if (!(v instanceof Boolean))
              throw new IllegalArgumentException("Invalid removable status");
          } else {
            try {
              int n = new java.math.BigDecimal(v.toString()).intValueExact();
              if (col.equals("default_qty") && (n < 1 || n > 99))
                throw new IllegalArgumentException("Invalid included quantity");
            } catch (ArithmeticException ex) {
              throw new IllegalArgumentException("Invalid recipe integer");
            }
          }
          values.put(col, v);
        }
        if (!JSON.valueToTree(values).equals(row))
          result.add(new Change(t, identity, values, false));
      }
    for (String key : save.selected())
      if (!oldKeys.contains(key)) {
        String t =
            save.product().kind().equals("pizza")
                ? "pizza_default_ingredient"
                : save.family().equals("burger")
                    ? "burger_item_default_component"
                    : "menu_item_ingredient";
        long owner = save.product().id();
        if (t.equals("burger_item_default_component"))
          for (var a : rows(state, "burger_recipe_assignment"))
            if (a.path("burger_id").asLong() == owner) {
              t = "burger_recipe_component";
              owner = a.path("recipe_id").asLong();
              break;
            }
        var values = new LinkedHashMap<String, Object>();
        values.put(parent(t), owner);
        values.put(child(t), Long.parseLong(key.substring(key.indexOf(':') + 1)));
        values.put("is_removable", true);
        values.put("sort_order", ++max);
        if (!t.startsWith("burger")) values.put("default_qty", 1);
        for (var prop : save.properties().getOrDefault(key, Map.of()).entrySet()) {
          if (!values.containsKey(prop.getKey())
              || Set.of(parent(t), child(t)).contains(prop.getKey()))
            throw new IllegalArgumentException("Unsupported recipe property");
          values.put(prop.getKey(), prop.getValue());
        }
        result.add(new Change(t, null, values, false));
      }
    validateProteins(state, result);
    return result;
  }

  public static void validateProteins(JsonNode state, List<Change> changes) {
    var after = state.deepCopy();
    var tables = (com.fasterxml.jackson.databind.node.ObjectNode) after.path("tables");
    for (var c : changes) {
      if (!c.table().startsWith("burger")) continue;
      var rows = new ArrayList<JsonNode>();
      tables.path(c.table()).forEach(rows::add);
      var value = JSON.valueToTree(c.values());
      rows.removeIf(
          row ->
              row.path("component_id").equals(value.path("component_id"))
                  && row.path(parent(c.table())).equals(value.path(parent(c.table()))));
      if (!c.remove()) rows.add(value);
      tables.set(c.table(), JSON.valueToTree(rows));
    }
    var beforeTables = BurgerRecipes.tables(state);
    var afterTables = BurgerRecipes.tables(after);
    for (var a : rows(state, "burger_recipe_assignment")) {
      if (!a.path("protein_required").asBoolean()) continue;
      long burger = a.path("burger_id").asLong();
      java.util.function.Predicate<JsonNode> protein =
          c ->
              rows(state, "burger_component").stream()
                  .anyMatch(
                      r ->
                          r.path("component_id").equals(c.path("component_id"))
                              && r.path("component_type").asText().equals("protein"));
      if (BurgerRecipes.effective(beforeTables, burger).stream().anyMatch(protein)
          && BurgerRecipes.effective(afterTables, burger).stream().noneMatch(protein))
        throw new IllegalArgumentException("This burger requires a protein component");
    }
  }

  public static JsonNode cleanRules(
      JsonNode source,
      JsonNode before,
      JsonNode state,
      List<Change> changes,
      java.util.function.BiFunction<String, List<Long>, java.util.UUID> priceIdentity) {
    var result = source.deepCopy();
    for (var change : changes) {
      String table = change.table();
      if (table.equals("burger_item_component_override")) {
        var value = JSON.valueToTree(change.values());
        boolean oldExcluded =
            rows(before, table).stream()
                .anyMatch(
                    r ->
                        r.path("burger_id").equals(value.path("burger_id"))
                            && r.path("component_id").equals(value.path("component_id"))
                            && r.path("excluded").asBoolean());
        boolean excluded = !change.remove() && value.path("excluded").asBoolean();
        if (oldExcluded == excluded) continue;
      } else if (!change.remove() && change.originalKey() != null) continue;
      if (!Set.of(
              "pizza_default_ingredient",
              "menu_item_ingredient",
              "burger_recipe_component",
              "burger_item_default_component",
              "burger_item_component_override",
              "salad_ingredients")
          .contains(table)) continue;
      var row = JSON.valueToTree(change.values());
      String extra = prefix(table) + row.path(child(table)).asLong();
      var owners = new HashSet<Long>();
      long owner = row.path(parent(table)).asLong();
      if (table.equals("burger_recipe_component"))
        for (var a : rows(state, "burger_recipe_assignment")) {
          if (a.path("recipe_id").asLong() == owner) owners.add(a.path("burger_id").asLong());
        }
      else owners.add(owner);
      String prices =
          table.equals("pizza_default_ingredient")
              ? "branch_pizza_price"
              : "branch_menu_item_price";
      for (var price : rows(state, prices))
        if (owners.contains(
            price
                .path(prices.equals("branch_pizza_price") ? "pizza_id" : "menu_item_id")
                .asLong())) {
          if (table.equals("burger_recipe_component")
              && rows(state, "burger_item_default_component").stream()
                  .anyMatch(
                      r ->
                          r.path("burger_id").asLong() == price.path("menu_item_id").asLong()
                              && r.path("component_id").asLong()
                                  == row.path("component_id").asLong())) continue;
          var key = new ArrayList<Long>();
          key.add(price.path("branch_id").asLong());
          key.add(
              price
                  .path(prices.equals("branch_pizza_price") ? "pizza_id" : "menu_item_id")
                  .asLong());
          if (prices.equals("branch_pizza_price")) key.add(price.path("pizza_size_id").asLong());
          var rule =
              result
                  .path("items")
                  .path(priceIdentity.apply(prices, key).toString())
                  .path("extras")
                  .path(extra);
          if (rule instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            object.putNull("included");
            object.putNull("removable");
          }
        }
    }
    return result;
  }
}
