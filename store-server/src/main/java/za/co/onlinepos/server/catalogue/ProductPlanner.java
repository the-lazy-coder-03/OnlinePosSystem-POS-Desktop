package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ProductEdits;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.server.recipes.RecipePlanner;

/**
 * Database-neutral aggregate validation and draft reference resolution. Called under the store
 * lock.
 */
public final class ProductPlanner {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SnapshotFormat FORMAT = new SnapshotFormat(true);

  private ProductPlanner() {}

  public record Prepared(
      Recipes.Product product,
      List<CatalogueManagement.Change> changes,
      Recipes.Save recipe,
      List<ProductEdits.Variant> variants,
      Map<String, Long> draftIds) {}

  public static JsonNode identities(JsonNode state, BiFunction<String, List<Long>, UUID> identity) {
    var map = ((ObjectNode) state).putObject("priceIdentities");
    for (String table : List.of("branch_menu_item_price", "branch_pizza_price"))
      for (var row : state.path("tables").path(table)) {
        var key = new ArrayList<Long>();
        FORMAT
            .schema()
            .path(table)
            .path("keys")
            .forEach(k -> key.add(row.path(k.asText()).asLong()));
        map.put(table + key, identity.apply(table, key).toString());
      }
    return state;
  }

  public static String revision(JsonNode state, String kind, long id) {
    if (id < 0) return "new";
    var row = find(state, kind, id);
    return FORMAT.hash(
        List.of(row, RecipePlanner.view(state, new Recipes.Product(kind, id), null)));
  }

  private static JsonNode find(JsonNode state, String kind, long id) {
    if (!List.of("menu_item", "pizza").contains(kind))
      throw new IllegalArgumentException("Invalid product kind");
    for (var row : state.path("tables").path(kind))
      if (row.path(kind.equals("pizza") ? "pizza_id" : "id").asLong() == id) return row;
    throw new IllegalArgumentException("Product no longer exists");
  }

  public static JsonNode view(
      JsonNode state, ItemCustomization.Catalog catalog, String kind, long id) {
    var product = new Recipes.Product(kind, id);
    var response = JSON.createObjectNode();
    response.set("state", state);
    response.set("catalog", JSON.valueToTree(catalog));
    response.set("details", find(state, kind, id));
    response.set("recipe", RecipePlanner.view(state, product, null));
    response.put("productRevision", revision(state, kind, id));
    return response;
  }

  public static Prepared prepare(
      JsonNode state, ProductEdits.Save save, ToLongFunction<String> allocate) {
    if (save == null
        || save.commandId() == null
        || save.id() == 0
        || save.changes() == null
        || save.changes().size() > 500
        || save.variants() == null
        || save.variants().size() > 100
        || !List.of("pizza", "menu_item").contains(save.kind()))
      throw new IllegalArgumentException("Invalid product draft");
    if (!revision(state, save.kind(), save.id()).equals(save.expectedProductRevision()))
      throw new IllegalArgumentException("Product changed; reload and review");
    var ids = new HashMap<String, Long>();
    for (var c : save.changes()) {
      if (c == null || c.table() == null)
        throw new IllegalArgumentException("Invalid product section");
      var spec = FORMAT.schema().path(c.table());
      if (spec.isMissingNode() || c.values() == null)
        throw new IllegalArgumentException("Invalid product section");
      if (c.originalKey() == null && spec.path("keys").size() == 1) {
        String key = spec.path("keys").get(0).asText();
        if (!spec.path("references").has(key)) {
          Object value = c.values().get(key);
          if (!(value instanceof Number n) || n.longValue() >= 0)
            throw new IllegalArgumentException("New records require a draft reference");
          if (ids.put(c.table() + ":" + n.longValue(), allocate.applyAsLong(c.table())) != null)
            throw new IllegalArgumentException("Repeated draft reference");
        }
      }
    }
    long productId = resolve(ids, save.kind(), save.id());
    if (productId < 1) throw new IllegalArgumentException("Missing new product details");
    var changes = new ArrayList<CatalogueManagement.Change>();
    var staged = state.deepCopy();
    for (var c : save.changes()) {
      var spec = FORMAT.schema().path(c.table());
      var values = new LinkedHashMap<>(c.values());
      for (var key : spec.path("keys")) {
        String col = key.asText();
        if (!spec.path("references").has(col) && values.get(col) instanceof Number n)
          values.put(col, resolve(ids, c.table(), n.longValue()));
      }
      spec.path("references")
          .properties()
          .forEach(
              ref -> {
                if (values.get(ref.getKey()) instanceof Number n)
                  values.put(
                      ref.getKey(), resolve(ids, ref.getValue().get(0).asText(), n.longValue()));
              });
      var change = new CatalogueManagement.Change(c.table(), c.originalKey(), values, c.remove());
      validateOwnership(save.kind(), productId, change);
      apply(staged, change);
      changes.add(change);
    }
    for (var change : changes)
      if (!change.remove()) {
        var spec = FORMAT.schema().path(change.table());
        JsonNode before = null;
        if (change.originalKey() != null)
          for (var row : state.path("tables").path(change.table()))
            if (FORMAT.key(change.table(), row).equals(FORMAT.encode(change.originalKey())))
              before = row;
        var old = before;
        for (var ref : spec.path("references").properties()) {
          Object value = change.values().get(ref.getKey());
          if (value == null
              || old != null && FORMAT.tree(value).equals(FORMAT.canonical(old.path(ref.getKey()))))
            continue;
          String table = ref.getValue().get(0).asText(), column = ref.getValue().get(1).asText();
          for (var target : staged.path("tables").path(table))
            if (FORMAT.canonical(target.path(column)).equals(FORMAT.tree(value))) {
              if (target.has("active") && !target.path("active").asBoolean())
                throw new IllegalArgumentException("Inactive records cannot be newly assigned");
            }
        }
      }
    // A source row must exist even if the draft only changes related records.
    find(staged, save.kind(), productId);
    Recipes.Save recipe = null;
    if (save.recipe() != null) {
      var input = save.recipe();
      if (input.selected() == null
          || input.properties() == null
          || input.restoreInherited() == null)
        throw new IllegalArgumentException("Invalid recipe selection");
      var selected = input.selected().stream().map(k -> ingredient(ids, k)).toList();
      var props = new LinkedHashMap<String, Map<String, Object>>();
      input.properties().forEach((k, v) -> props.put(ingredient(ids, k), v));
      recipe =
          new Recipes.Save(
              save.commandId(),
              save.expectedRevision(),
              new Recipes.Product(save.kind(), productId),
              input.family(),
              selected,
              props,
              false,
              input.restoreInherited());
      var recipeChanges = RecipePlanner.changes(staged, recipe);
      changes.addAll(recipeChanges);
    }
    var variants = new ArrayList<ProductEdits.Variant>();
    for (var v : save.variants()) {
      if (v == null
          || v.key() == null
          || v.key().size() != (save.kind().equals("pizza") ? 3 : 2)
          || v.key().get(0) != state.path("branchId").asLong()
          || v.key().get(1) != save.id())
        throw new IllegalArgumentException("Foreign product settings");
      var key = new ArrayList<>(v.key());
      key.set(1, productId);
      variants.add(new ProductEdits.Variant(key, v.item(), v.combo()));
    }
    return new Prepared(
        new Recipes.Product(save.kind(), productId), changes, recipe, variants, ids);
  }

  private static long resolve(Map<String, Long> ids, String table, long id) {
    return ids.getOrDefault(table + ":" + id, id);
  }

  private static String ingredient(Map<String, Long> ids, String key) {
    String[] bits = key.split(":");
    if (bits.length != 2) throw new IllegalArgumentException("Invalid ingredient reference");
    String table =
        bits[0].equals("burger")
            ? "burger_component"
            : bits[0].equals("salad") ? "salad_ingredients" : "ingredient";
    return bits[0] + ":" + resolve(ids, table, Long.parseLong(bits[1]));
  }

  private static void validateOwnership(String kind, long id, CatalogueManagement.Change c) {
    String owner =
        switch (c.table()) {
          case "menu_item" -> "id";
          case "pizza", "pizza_allowed_size", "branch_pizza_price", "pizza_default_ingredient" ->
              "pizza_id";
          case "branch_menu_item_price", "menu_item_modifier_group", "menu_item_ingredient" ->
              "menu_item_id";
          case "burger_recipe_assignment",
              "burger_item_default_component",
              "burger_item_component_override" ->
              "burger_id";
          default -> null;
        };
    if (owner != null) {
      boolean pizza = c.table().equals("pizza") || c.table().contains("pizza_");
      if (pizza != kind.equals("pizza")
          || !(c.values().get(owner) instanceof Number n)
          || n.longValue() != id)
        throw new IllegalArgumentException("Foreign product relationship");
    } else if (!(c.originalKey() == null
        && !c.remove()
        && List.of(
                "ingredient",
                "burger_component",
                "price_category",
                "modifier_group",
                "modifier_option")
            .contains(c.table())))
      throw new IllegalArgumentException("Edit reusable records through their global section");
  }

  private static void apply(JsonNode state, CatalogueManagement.Change c) {
    var tables = (ObjectNode) state.path("tables");
    var rows = new ArrayList<JsonNode>();
    tables.path(c.table()).forEach(rows::add);
    if (c.originalKey() != null)
      rows.removeIf(row -> FORMAT.key(c.table(), row).equals(FORMAT.encode(c.originalKey())));
    if (!c.remove()) rows.add(JSON.valueToTree(c.values()));
    tables.set(c.table(), JSON.valueToTree(rows));
  }

  private static ItemCustomization.ItemRule remapItem(
      ItemCustomization.ItemRule item, Map<String, Long> ids) {
    if (item.extras() == null || item.exclusions() == null || item.groups() == null)
      throw new IllegalArgumentException("Invalid modifier settings");
    var extras = new LinkedHashMap<String, ItemCustomization.ExtraRule>();
    item.extras().forEach((key, value) -> extras.put(ingredient(ids, key), value));
    var groups = new ArrayList<ItemCustomization.Group>();
    for (var group : item.groups()) {
      var choices = new ArrayList<ItemCustomization.Choice>();
      for (var choice : group.choices())
        choices.add(
            new ItemCustomization.Choice(
                choice.key(),
                choice.name(),
                choice.surcharge(),
                choice.productPriceId(),
                choice.componentKey() == null || choice.componentKey().isBlank()
                    ? choice.componentKey()
                    : ingredient(ids, choice.componentKey()),
                choice.defaultSelected(),
                choice.available()));
      groups.add(
          new ItemCustomization.Group(
              group.key(),
              group.name(),
              group.min(),
              group.max(),
              group.replacesKey() == null || group.replacesKey().isBlank()
                  ? group.replacesKey()
                  : ingredient(ids, group.replacesKey()),
              choices));
    }
    return new ItemCustomization.ItemRule(
        item.exclusions().stream().map(key -> ingredient(ids, key)).toList(),
        extras,
        groups,
        item.overrideGroups());
  }

  public static ItemCustomization.Rules rules(
      ItemCustomization.Rules old,
      Prepared prepared,
      BiFunction<String, List<Long>, UUID> priceId) {
    var items = new LinkedHashMap<>(old.items());
    var combos = new LinkedHashMap<>(old.combos());
    String table =
        prepared.product().kind().equals("pizza") ? "branch_pizza_price" : "branch_menu_item_price";
    for (var v : prepared.variants()) {
      UUID id = priceId.apply(table, v.key());
      if (v.item() == null) items.remove(id);
      else items.put(id, remapItem(v.item(), prepared.draftIds()));
      if (v.combo() == null) combos.remove(id);
      else combos.put(id, v.combo());
    }
    return new ItemCustomization.Rules(old.categoryExtras(), old.swapGroups(), items, combos);
  }
}
