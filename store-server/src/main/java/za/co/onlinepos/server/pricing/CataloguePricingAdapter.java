package za.co.onlinepos.server.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Choice;
import za.co.onlinepos.contracts.ItemCustomization.Combo;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.Group;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.Rules;
import za.co.onlinepos.contracts.ItemCustomization.Slot;

/** Database-neutral projection shared by H2 and PostgreSQL pricing services. */
public final class CataloguePricingAdapter {
  private CataloguePricingAdapter() {}

  private static JsonNode parse(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Invalid catalogue record");
    }
  }

  private static List<JsonNode> rows(Map<String, List<JsonNode>> tables, String table) {
    return tables.getOrDefault(table, List.of());
  }

  private static String key(JsonNode r, String field) {
    return r.path(field).asText();
  }

  private static JsonNode find(
      Map<String, List<JsonNode>> t, String table, String field, String value) {
    return rows(t, table).stream()
        .filter(r -> key(r, field).equals(value))
        .findFirst()
        .orElse(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
  }

  private static BigDecimal amount(JsonNode row, String field) {
    return row.hasNonNull(field) ? ItemCustomization.money(row.path(field).decimalValue()) : null;
  }

  private static boolean active(JsonNode row) {
    return !row.isEmpty()
        && row.path("active").asBoolean(true)
        && !row.path("archived").asBoolean(false);
  }

  public static Catalog build(
      Map<String, List<JsonNode>> tables,
      List<Map<String, Object>> rawPrices,
      Rules rules,
      long revision,
      boolean editable) {
    var extras = new LinkedHashMap<String, Extra>();
    for (var r : rows(tables, "burger_component")) {
      String id = key(r, "component_id");
      var price = find(tables, "branch_burger_component_price", "component_id", id);
      extras.put(
          "burger:" + id,
          new Extra(
              "burger:" + id,
              r.path("name").asText(),
              amount(price, "price"),
              active(r),
              0,
              true,
              "",
              "Catalogue"));
    }
    for (var r : rows(tables, "ingredient")) {
      String id = key(r, "ingredient_id");
      extras.put(
          "ingredient:" + id,
          new Extra(
              "ingredient:" + id,
              r.path("name").asText(),
              null,
              active(r),
              0,
              true,
              "",
              "Catalogue"));
    }
    for (var r : rows(tables, "salad_ingredients")) {
      String id = key(r, "id");
      extras.put(
          "salad:" + id,
          new Extra(
              "salad:" + id,
              r.path("ingredient_name").asText(),
              amount(r, "price"),
              true,
              0,
              true,
              "",
              "Catalogue"));
    }
    var categories = new LinkedHashMap<String, String>();
    for (var r :
        rows(tables, "menu_category").stream()
            .sorted(
                java.util.Comparator.comparingInt((JsonNode r) -> r.path("sort_order").asInt())
                    .thenComparingInt(r -> r.path("id").asInt()))
            .toList()) categories.put("menu:" + key(r, "id"), r.path("name").asText());
    for (var r : rows(tables, "pizza_category"))
      categories.put("pizza:" + key(r, "pizza_category_id"), r.path("name").asText());
    categories.put("special", "Combos / specials");
    var parents = new LinkedHashMap<String, String>();
    var categoryRows = new HashMap<String, JsonNode>();
    for (String table : List.of("menu_category", "pizza_category")) {
      String prefix = table.equals("menu_category") ? "menu:" : "pizza:";
      String key = table.equals("menu_category") ? "id" : "pizza_category_id";
      for (var row : rows(tables, table)) {
        categoryRows.put(prefix + key(row, key), row);
        if (row.hasNonNull("parent_id"))
          parents.put(prefix + key(row, key), prefix + key(row, "parent_id"));
      }
    }
    var products = new LinkedHashMap<UUID, Product>();
    var menuPrices = new HashMap<String, UUID>();
    var pizzaPrices = new HashMap<String, List<UUID>>();
    for (var r : rawPrices) {
      String type = r.get("source_type").toString();
      if (List.of(
              "branch_extra_price",
              "branch_pizza_base_option_price",
              "branch_burger_component_price",
              "modifier_option",
              "salad_ingredients",
              "special_addon")
          .contains(type)) continue;
      UUID id = (UUID) r.get("price_id");
      var d = parse(r.get("definition").toString());
      String category = "", sourceId = "";
      var defaults = new LinkedHashMap<String, Extra>();
      var groups = new ArrayList<Group>();
      if (type.equals("branch_menu_item_price")) {
        sourceId = key(d, "menu_item_id");
        menuPrices.put(sourceId, id);
        var item = find(tables, "menu_item", "id", sourceId);
        category = "menu:" + key(item, "category_id");
        for (var link : rows(tables, "menu_item_ingredient"))
          if (key(link, "menu_item_id").equals(sourceId)) {
            String ingredientKey = "ingredient:" + key(link, "ingredient_id");
            add(
                defaults,
                extras,
                ingredientKey,
                link.path("default_qty").asInt(1),
                link.path("is_removable").asBoolean(true));
          }
        var assignment = find(tables, "burger_recipe_assignment", "burger_id", sourceId);
        String recipe = key(assignment, "recipe_id");
        for (var component :
            za.co.onlinepos.server.recipes.BurgerRecipes.effective(
                tables, Long.parseLong(sourceId)))
          add(
              defaults,
              extras,
              "burger:" + key(component, "component_id"),
              1,
              component.path("is_removable").asBoolean());
        var proteins =
            defaults.keySet().stream()
                .filter(
                    k ->
                        k.startsWith("burger:")
                            && find(
                                    tables,
                                    "burger_component",
                                    "component_id",
                                    k.substring("burger:".length()))
                                .path("component_type")
                                .asText()
                                .equalsIgnoreCase("protein"))
                .toList();
        if (proteins.size() == 1 && assignment.path("protein_quantity_required").asInt() > 0) {
          var protein = defaults.get(proteins.getFirst());
          defaults.put(
              protein.key(),
              new Extra(
                  protein.key(),
                  protein.name(),
                  protein.price(),
                  protein.available(),
                  assignment.path("protein_quantity_required").asInt(),
                  protein.removable(),
                  protein.swapGroup(),
                  protein.origin()));
        }
        for (var component : rows(tables, "salad_ingredients"))
          if (key(component, "salad_id").equals(sourceId))
            add(defaults, extras, "salad:" + key(component, "id"), 1, true);
        for (var link : rows(tables, "menu_item_modifier_group"))
          if (key(link, "menu_item_id").equals(sourceId)) {
            var group = find(tables, "modifier_group", "id", key(link, "group_id"));
            var options = new ArrayList<Choice>();
            for (var option : rows(tables, "modifier_option"))
              if (key(option, "group_id").equals(key(group, "id")))
                options.add(
                    new Choice(
                        "modifier:" + key(option, "id"),
                        option.path("name").asText(),
                        amount(option, "additional_price"),
                        null,
                        null,
                        false,
                        true));
            groups.add(
                new Group(
                    "modifier:" + key(group, "id"),
                    group.path("name").asText(),
                    Math.max(
                        group.path("required").asBoolean() ? 1 : 0,
                        group.path("min_select").asInt()),
                    group.path("max_select").asInt(),
                    null,
                    options));
          }
      } else if (type.equals("branch_pizza_price")) {
        sourceId = key(d, "pizza_id");
        pizzaPrices.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(id);
        var pizza = find(tables, "pizza", "pizza_id", sourceId);
        category = "pizza:" + key(pizza, "pizza_category_id");
        String size = key(d, "pizza_size_id");
        for (var ingredient : rows(tables, "ingredient")) {
          var prices =
              rows(tables, "branch_extra_price").stream()
                  .filter(
                      e ->
                          key(e, "price_category_id").equals(key(ingredient, "price_category_id"))
                              && key(e, "pizza_size_id").equals(size))
                  .findFirst();
          var e = extras.get("ingredient:" + key(ingredient, "ingredient_id"));
          defaults.put(
              e.key(),
              new Extra(
                  e.key(),
                  e.name(),
                  prices.isEmpty() ? null : amount(prices.get(), "price"),
                  e.available()
                      && active(
                          find(
                              tables,
                              "price_category",
                              "price_category_id",
                              key(ingredient, "price_category_id"))),
                  0,
                  true,
                  "",
                  "Candidate"));
        }
        for (var ingredient : rows(tables, "pizza_default_ingredient"))
          if (key(ingredient, "pizza_id").equals(sourceId)) {
            String k = "ingredient:" + key(ingredient, "ingredient_id");
            var e = defaults.get(k);
            if (e != null)
              defaults.put(
                  k,
                  new Extra(
                      e.key(),
                      e.name(),
                      e.price(),
                      e.available(),
                      ingredient.path("default_qty").asInt(),
                      ingredient.path("is_removable").asBoolean(),
                      "",
                      "Recipe"));
          }
      } else if (type.equals("special")) {
        category = "special";
        sourceId = key(d, "special_id");
      }
      boolean available =
          (Boolean) r.get("available")
              && (!d.hasNonNull("branch_id")
                  || active(find(tables, "branch", "branch_id", key(d, "branch_id"))));
      if (type.equals("branch_menu_item_price")) {
        var item = find(tables, "menu_item", "id", sourceId);
        available =
            available
                && active(item)
                && active(find(tables, "menu_category", "id", key(item, "category_id")));
      }
      if (type.equals("branch_pizza_price")) {
        var pizza = find(tables, "pizza", "pizza_id", sourceId);
        available =
            available
                && active(pizza)
                && active(
                    find(
                        tables,
                        "pizza_category",
                        "pizza_category_id",
                        key(pizza, "pizza_category_id")));
      }
      if (type.equals("special")) {
        var special = find(tables, "special", "special_id", sourceId);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"));
        var days =
            rows(tables, "special_day").stream()
                .filter(day -> key(day, "special_id").equals(key(special, "special_id")))
                .toList();
        available =
            rules.combos().containsKey(id)
                && active(special)
                && !special.path("archived").asBoolean()
                && (!special.hasNonNull("starts_on")
                    || !today.isBefore(java.time.LocalDate.parse(key(special, "starts_on"))))
                && (!special.hasNonNull("ends_on")
                    || !today.isAfter(java.time.LocalDate.parse(key(special, "ends_on"))))
                && (days.isEmpty()
                    || days.stream()
                        .anyMatch(
                            day ->
                                day.path("day_of_week").asInt()
                                    == today.getDayOfWeek().getValue()));
      }
      products.put(
          id,
          new Product(
              id,
              r.get("description").toString(),
              category,
              ((Number) r.get("revision")).longValue(),
              ItemCustomization.money((BigDecimal) r.get("amount")),
              available,
              List.copyOf(defaults.values()),
              groups,
              null));
    }
    // Resolve existing product-linked modifier choices after all products have been mapped.
    for (var entry : new ArrayList<>(products.entrySet())) {
      var p = entry.getValue();
      var groups = new ArrayList<Group>();
      for (var group : p.groups()) {
        var choices = new ArrayList<Choice>();
        for (var choice : group.choices()) {
          var option =
              find(tables, "modifier_option", "id", choice.key().substring("modifier:".length()));
          UUID ref =
              option.hasNonNull("menu_item_id")
                  ? menuPrices.get(key(option, "menu_item_id"))
                  : null;
          choices.add(
              new Choice(
                  choice.key(),
                  choice.name(),
                  choice.surcharge(),
                  ref,
                  null,
                  false,
                  !option.hasNonNull("menu_item_id")
                      || ref != null && products.get(ref).available()));
        }
        groups.add(new Group(group.key(), group.name(), group.min(), group.max(), null, choices));
      }
      Combo combo = null;
      if (p.category().equals("special")) {
        var raw =
            rawPrices.stream()
                .filter(row -> row.get("price_id").equals(p.priceId()))
                .findFirst()
                .orElseThrow();
        String special = key(parse(raw.get("definition").toString()), "special_id");
        var slots = new ArrayList<Slot>();
        for (var slot : rows(tables, "special_component"))
          if (key(slot, "special_id").equals(special)) {
            String slotId = key(slot, "special_component_id");
            var allowed = new LinkedHashMap<UUID, BigDecimal>();
            for (var link : rows(tables, "special_component_menu_item"))
              if (key(link, "special_component_id").equals(slotId)
                  && menuPrices.containsKey(key(link, "menu_item_id")))
                allowed.put(menuPrices.get(key(link, "menu_item_id")), BigDecimal.ZERO.setScale(2));
            for (var link : rows(tables, "special_component_pizza"))
              if (key(link, "special_component_id").equals(slotId))
                for (var price : pizzaPrices.getOrDefault(key(link, "pizza_id"), List.of()))
                  allowed.put(price, BigDecimal.ZERO.setScale(2));
            if (slot.hasNonNull("menu_category_id"))
              for (var product : products.values())
                if (product.category().equals("menu:" + key(slot, "menu_category_id")))
                  allowed.put(product.priceId(), BigDecimal.ZERO.setScale(2));
            if (slot.hasNonNull("pizza_category_id"))
              for (var product : products.values())
                if (product.category().equals("pizza:" + key(slot, "pizza_category_id")))
                  allowed.put(product.priceId(), BigDecimal.ZERO.setScale(2));
            slots.add(
                new Slot(
                    slotId,
                    slot.path("label").asText(),
                    slot.path("quantity").asInt(),
                    allowed.size() == 1 ? allowed.keySet().iterator().next() : null,
                    allowed));
          }
        combo = new Combo(p.price(), slots);
      }
      products.put(
          p.priceId(),
          new Product(
              p.priceId(),
              p.name(),
              p.category(),
              p.priceRevision(),
              p.price(),
              p.available(),
              p.extras(),
              groups,
              combo));
    }
    products.replaceAll(
        (id, p) -> {
          boolean available = p.available();
          String node = p.category();
          var visited = new java.util.HashSet<String>();
          while (node != null) {
            if (!visited.add(node))
              throw new IllegalArgumentException("Category hierarchy contains a cycle");
            if (categoryRows.containsKey(node)) available &= active(categoryRows.get(node));
            node = parents.get(node);
          }
          return new Product(
              p.priceId(),
              p.name(),
              p.category(),
              p.priceRevision(),
              p.price(),
              available,
              p.extras(),
              p.groups(),
              p.combo());
        });
    return new Catalog(revision, products, extras, categories, rules, editable, parents);
  }

  private static void add(
      Map<String, Extra> target, Map<String, Extra> all, String key, int qty, boolean removable) {
    var e = all.get(key);
    if (e != null)
      target.put(
          key,
          new Extra(e.key(), e.name(), e.price(), e.available(), qty, removable, "", "Recipe"));
  }
}
