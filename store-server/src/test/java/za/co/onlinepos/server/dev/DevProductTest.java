package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ProductEdits;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.server.catalogue.ProductPlanner;
import za.co.onlinepos.server.recipes.RecipePlanner;

class DevProductTest {
  @TempDir Path directory;

  org.springframework.context.ConfigurableApplicationContext start() {
    var test = new DevGridTest();
    test.directory = directory;
    return test.start();
  }

  Map<String, Object> values(JsonNode node) {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .convertValue(
            node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  List<String> selected(JsonNode view) {
    var result = new ArrayList<String>();
    view.path("ingredients")
        .forEach(
            c -> {
              if (c.path("selected").asBoolean()) result.add(c.path("key").asText());
            });
    return result;
  }

  ProductEdits.Save save(
      JsonNode state,
      String kind,
      long id,
      List<CatalogueManagement.Change> changes,
      ProductEdits.Recipe recipe,
      List<ProductEdits.Variant> variants) {
    return new ProductEdits.Save(
        UUID.randomUUID(),
        state.path("revision").asLong(),
        ProductPlanner.revision(state, kind, id),
        kind,
        id,
        changes,
        recipe,
        variants);
  }

  @Test
  void atomicProductCreationResolvesIngredientReferencesAndSurvivesRestart() {
    long productId;
    try (var context = start()) {
      var service = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var state = service.state();
      long category = state.path("tables").path("menu_category").get(0).path("id").asLong();
      long priceCategory =
          state.path("tables").path("price_category").get(0).path("price_category_id").asLong();
      var changes =
          List.of(
              new CatalogueManagement.Change(
                  "menu_item",
                  null,
                  Map.of(
                      "id",
                      -1,
                      "category_id",
                      category,
                      "name",
                      "Atomic product",
                      "description",
                      "Draft",
                      "active",
                      true,
                      "sort_order",
                      0,
                      "is_300ml",
                      false,
                      "is_2l",
                      false),
                  false),
              new CatalogueManagement.Change(
                  "ingredient",
                  null,
                  Map.of(
                      "ingredient_id",
                      -2,
                      "name",
                      "Atomic ingredient",
                      "active",
                      true,
                      "seasonal",
                      false,
                      "price_category_id",
                      priceCategory),
                  false),
              new CatalogueManagement.Change(
                  "branch_menu_item_price",
                  null,
                  Map.of(
                      "branch_id",
                      store.branchId(),
                      "menu_item_id",
                      -1,
                      "price",
                      new BigDecimal("72.50")),
                  false));
      var command =
          save(
              state,
              "menu_item",
              -1,
              changes,
              new ProductEdits.Recipe(
                  "ingredient",
                  List.of("ingredient:-2"),
                  Map.of("ingredient:-2", Map.of("default_qty", 2)),
                  List.of()),
              List.of());
      var result = service.saveProduct(command);
      assertEquals(result, service.saveProduct(command));
      productId = result.path("product").path("id").asLong();
      assertTrue(productId > 0);
      var aggregate = service.product("menu_item", productId);
      assertEquals("Atomic product", aggregate.path("details").path("name").asText());
      assertEquals(1, selected(aggregate.path("recipe")).size());
      assertEquals(
          2,
          store.jdbc.queryForObject(
              "SELECT default_qty FROM public.menu_item_ingredient WHERE menu_item_id=?",
              Integer.class,
              productId));
      assertTrue(
          service.catalog().products().values().stream()
              .anyMatch(
                  p ->
                      p.name().equals("Atomic product")
                          && p.price().equals(new BigDecimal("72.50"))));
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              service.saveProduct(
                  new ProductEdits.Save(
                      command.commandId(),
                      command.expectedRevision(),
                      command.expectedProductRevision(),
                      command.kind(),
                      command.id(),
                      List.of(),
                      null,
                      List.of())));
    }
    try (var context = start()) {
      assertEquals(
          "Atomic product",
          context
              .getBean(DevCatalogueService.class)
              .product("menu_item", productId)
              .path("details")
              .path("name")
              .asText());
    }
  }

  @Test
  void invalidSettingsAndJournalFailureRollBackEverySection() {
    try (var context = start()) {
      var service = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var state = service.state();
      var item = state.path("tables").path("menu_item").get(0);
      long id = item.path("id").asLong();
      var values = new LinkedHashMap<>(values(item));
      values.put("name", "Must rollback");
      var change = new CatalogueManagement.Change("menu_item", List.of(id), values, false);
      var invalidCombo = new ItemCustomization.Combo(new BigDecimal("5.00"), List.of());
      var invalid =
          save(
              state,
              "menu_item",
              id,
              List.of(change),
              null,
              List.of(
                  new ProductEdits.Variant(
                      List.of((long) store.branchId(), id), null, invalidCombo)));
      assertThrows(IllegalArgumentException.class, () -> service.saveProduct(invalid));
      assertEquals(state, service.state());
      store.jdbc.execute(
          "ALTER TABLE dev_meta.module_events ADD CONSTRAINT reject_product_test CHECK(action<>'CATALOGUE_CHANGED')");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              service.saveProduct(save(state, "menu_item", id, List.of(change), null, List.of())));
      assertEquals(state, service.state());
      store.jdbc.execute("ALTER TABLE dev_meta.module_events DROP CONSTRAINT reject_product_test");
      var command = save(state, "menu_item", id, List.of(change), null, List.of());
      service.saveProduct(command);
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              service.saveProduct(save(state, "menu_item", id, List.of(change), null, List.of())));
    }
  }

  @Test
  void removingRecipeIngredientAndEditingVariantSettingsCannotRestoreOldInclusion() {
    try (var context = start()) {
      var service = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var state = service.state();
      long pizza = state.path("tables").path("pizza").get(0).path("pizza_id").asLong();
      var product = new Recipes.Product("pizza", pizza);
      var recipe = RecipePlanner.view(state, product, null);
      var keys = selected(recipe);
      String removed = keys.getFirst();
      JsonNode price = null;
      for (var row : state.path("tables").path("branch_pizza_price"))
        if (row.path("pizza_id").asLong() == pizza) {
          price = row;
          break;
        }
      assertNotNull(price);
      var priceKey =
          List.of(price.path("branch_id").asLong(), pizza, price.path("pizza_size_id").asLong());
      var priceId = DevModuleStore.identity("branch_pizza_price", priceKey.toString());
      var item =
          new ItemCustomization.ItemRule(
              List.of(),
              Map.of(
                  removed, new ItemCustomization.ExtraRule(new BigDecimal("12.00"), 2, true, "")),
              List.of(),
              false);
      var old = service.catalog().rules();
      var itemRules = new LinkedHashMap<>(old.items());
      itemRules.put(priceId, item);
      service.rules(
          new ItemCustomization.Edit(
              UUID.randomUUID(),
              state.path("revision").asLong(),
              new ItemCustomization.Rules(
                  old.categoryExtras(), old.swapGroups(), itemRules, old.combos())));
      state = service.state();
      keys.remove(removed);
      service.saveProduct(
          save(
              state,
              "pizza",
              pizza,
              List.of(),
              new ProductEdits.Recipe("ingredient", keys, Map.of(), List.of()),
              List.of(new ProductEdits.Variant(priceKey, item, null))));
      var catalog = service.catalog();
      var effective = ItemCustomization.effective(catalog, catalog.products().get(priceId));
      assertTrue(
          effective.extras().stream().noneMatch(e -> e.key().equals(removed) && e.included() > 0));
      assertEquals(
          0,
          new BigDecimal("12.00")
              .compareTo(catalog.rules().items().get(priceId).extras().get(removed).price()));
    }
  }

  @Test
  void burgerOverridesAreLocalSharedChangesInheritAndRestoreResumesInheritance() {
    try (var context = start()) {
      var service = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var state = service.state();
      JsonNode assignment = state.path("tables").path("burger_recipe_assignment").get(0);
      long burger = assignment.path("burger_id").asLong(),
          recipe = assignment.path("recipe_id").asLong();
      var view = RecipePlanner.view(state, new Recipes.Product("menu_item", burger), "burger");
      String removed = null;
      for (var c : view.path("ingredients"))
        if (c.path("selected").asBoolean()) {
          long component = Long.parseLong(c.path("key").asText().substring(7));
          if (store
              .jdbc
              .queryForObject(
                  "SELECT component_type FROM public.burger_component WHERE component_id=?",
                  String.class,
                  component)
              .equals("default_topping")) {
            removed = c.path("key").asText();
            break;
          }
        }
      assertNotNull(removed);
      final String key = removed;
      long component = Long.parseLong(key.substring(7));
      var keys = selected(view);
      keys.remove(key);
      service.saveProduct(
          save(
              state,
              "menu_item",
              burger,
              List.of(),
              new ProductEdits.Recipe("burger", keys, Map.of(), List.of()),
              List.of()));
      assertEquals(
          1,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM public.burger_recipe_component WHERE recipe_id=? AND component_id=?",
              Integer.class,
              recipe,
              component));
      assertFalse(selected(service.product("menu_item", burger).path("recipe")).contains(key));
      var current = service.state();
      var shared = RecipePlanner.sharedView(current, new Recipes.Product("menu_item", burger));
      var sharedSave =
          new Recipes.Save(
              UUID.randomUUID(),
              current.path("revision").asLong(),
              new Recipes.Product("menu_item", burger),
              "burger",
              selected(shared),
              Map.of(key, Map.of("is_removable", false)),
              true);
      service.edit(
          new CatalogueManagement.Edit(
              sharedSave.commandId(),
              sharedSave.expectedRevision(),
              List.of(),
              true,
              sharedSave,
              null,
              true));
      assertFalse(selected(service.product("menu_item", burger).path("recipe")).contains(key));
      assertTrue(
          service.catalog().products().values().stream()
              .filter(p -> p.name().equals(view.path("productName").asText()))
              .flatMap(p -> p.extras().stream())
              .noneMatch(e -> e.key().equals(key) && e.included() > 0));
      current = service.state();
      keys = selected(service.product("menu_item", burger).path("recipe"));
      keys.add(key);
      service.saveProduct(
          save(
              current,
              "menu_item",
              burger,
              List.of(),
              new ProductEdits.Recipe("burger", keys, Map.of(), List.of(key)),
              List.of()));
      assertEquals(
          0,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM public.burger_item_component_override WHERE burger_id=? AND component_id=?",
              Integer.class,
              burger,
              component));
      assertTrue(selected(service.product("menu_item", burger).path("recipe")).contains(key));
      assertTrue(
          service.catalog().products().values().stream()
              .filter(p -> p.name().equals(view.path("productName").asText()))
              .flatMap(p -> p.extras().stream())
              .anyMatch(e -> e.key().equals(key) && !e.removable()));
    }
  }
}
