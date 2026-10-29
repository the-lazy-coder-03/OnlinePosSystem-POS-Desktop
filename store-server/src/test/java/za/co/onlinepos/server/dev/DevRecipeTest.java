package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.server.recipes.RecipePlanner;

class DevRecipeTest {
  @TempDir Path directory;

  org.springframework.context.ConfigurableApplicationContext start() {
    var fixture = new DevGridTest();
    fixture.directory = directory;
    return fixture.start();
  }

  List<String> selected(JsonNode view) {
    var result = new ArrayList<String>();
    view.path("ingredients")
        .forEach(
            r -> {
              if (r.path("selected").asBoolean()) result.add(r.path("key").asText());
            });
    return result;
  }

  Recipes.Save request(
      DevCatalogueService catalogue,
      Recipes.Product product,
      List<String> keys,
      Map<String, Map<String, Object>> props,
      boolean shared) {
    var state = catalogue.state();
    return new Recipes.Save(
        UUID.randomUUID(),
        state.path("revision").asLong(),
        product,
        RecipePlanner.family(state, product),
        keys,
        props,
        shared);
  }

  JsonNode save(DevCatalogueService catalogue, Recipes.Save request) {
    return catalogue.edit(
        new CatalogueManagement.Edit(
            request.commandId(),
            request.expectedRevision(),
            List.of(),
            request.confirmSharedRecipe(),
            request));
  }

  @Test
  void pizzaBulkAddsRemovesPreservesPropertiesRetriesAndSurvivesRestart() {
    Recipes.Product product;
    List<String> expected;
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      product =
          new Recipes.Product(
              "pizza",
              catalogue.state().path("tables").path("pizza").get(0).path("pizza_id").asLong());
      var view = RecipePlanner.view(catalogue.state(), product, null);
      var keys = selected(view);
      var before =
          store.jdbc.queryForList(
              "SELECT * FROM public.pizza_default_ingredient WHERE pizza_id=?", product.id());
      var additions = new ArrayList<String>();
      for (var r : view.path("ingredients"))
        if (r.path("available").asBoolean()
            && !r.path("selected").asBoolean()
            && additions.size() < 5) additions.add(r.path("key").asText());
      assertEquals(5, additions.size());
      keys.addAll(additions);
      var command = request(catalogue, product, keys, Map.of(), false);
      var result = save(catalogue, command);
      assertEquals(result, save(catalogue, command));
      for (var row : before)
        assertTrue(
            store
                .jdbc
                .queryForList(
                    "SELECT * FROM public.pizza_default_ingredient WHERE pizza_id=?", product.id())
                .contains(row));
      assertEquals(
          new LinkedHashSet<>(keys),
          new LinkedHashSet<>(selected(RecipePlanner.view(catalogue.state(), product, null))));
      keys.removeAll(additions.subList(0, 2));
      save(
          catalogue,
          request(
              catalogue,
              product,
              keys,
              Map.of(additions.get(2), Map.of("default_qty", 3, "is_removable", false)),
              false));
      expected = List.copyOf(keys);
      var duplicate = new ArrayList<>(keys);
      duplicate.add(keys.getFirst());
      assertThrows(
          IllegalArgumentException.class,
          () -> save(catalogue, request(catalogue, product, duplicate, Map.of(), false)));
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              save(
                  catalogue,
                  new Recipes.Save(
                      UUID.randomUUID(),
                      command.expectedRevision(),
                      product,
                      "ingredient",
                      keys,
                      Map.of(),
                      false)));
      assertFalse(
          store
              .jdbc
              .queryForList(
                  "SELECT * FROM public.ingredient WHERE ingredient_id=?",
                  Long.parseLong(additions.getFirst().split(":")[1]))
              .isEmpty());
    }
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      assertEquals(
          new LinkedHashSet<>(expected),
          new LinkedHashSet<>(selected(RecipePlanner.view(catalogue.state(), product, null))));
    }
  }

  @Test
  void genericRecipesUseExistingIngredientsAndRollbackWithJournalFailure() {
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var template =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              catalogue.state().path("tables").path("menu_item").get(0).deepCopy();
      template.put("name", "Custom recipe product");
      Map<String, Object> fields = store.json.convertValue(template, Map.class);
      catalogue.edit(
          new CatalogueManagement.Edit(
              UUID.randomUUID(),
              store.revision(),
              List.of(new CatalogueManagement.Change("menu_item", null, fields, false)),
              false));
      long id =
          store.jdbc.queryForObject(
              "SELECT id FROM public.menu_item WHERE name='Custom recipe product'", Long.class);
      var product = new Recipes.Product("menu_item", id);
      var view = RecipePlanner.view(catalogue.state(), product, null);
      String ingredient =
          java.util.stream.StreamSupport.stream(view.path("ingredients").spliterator(), false)
              .filter(r -> r.path("available").asBoolean())
              .findFirst()
              .orElseThrow()
              .path("key")
              .asText();
      save(
          catalogue,
          request(
              catalogue,
              product,
              List.of(ingredient),
              Map.of(ingredient, Map.of("default_qty", 2)),
              false));
      assertEquals(
          2,
          store.jdbc.queryForObject(
              "SELECT default_qty FROM public.menu_item_ingredient WHERE menu_item_id=?",
              Integer.class,
              id));
      long next = store.revision() + 1;
      store.jdbc.execute(
          "ALTER TABLE dev_meta.module_events ADD CONSTRAINT recipe_failure CHECK(revision<>"
              + next
              + ")");
      var command = request(catalogue, product, List.of(), Map.of(), false);
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> save(catalogue, command));
      assertEquals(
          1,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM public.menu_item_ingredient WHERE menu_item_id=?",
              Integer.class,
              id));
      assertEquals(
          0,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.commands WHERE command_id=?",
              Integer.class,
              command.commandId()));
      store.jdbc.execute("ALTER TABLE dev_meta.module_events DROP CONSTRAINT recipe_failure");
      save(catalogue, request(catalogue, product, List.of(), Map.of(), false));
      assertEquals(
          0,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM public.menu_item_ingredient WHERE menu_item_id=?",
              Integer.class,
              id));
    }
  }

  @Test
  void burgerBulkSaveIsLocalAndPreservesProteinRules() {
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var assignments = catalogue.state().path("tables").path("burger_recipe_assignment");
      JsonNode assignment = null;
      for (var row : assignments) {
        long count =
            java.util.stream.StreamSupport.stream(assignments.spliterator(), false)
                .filter(a -> a.path("recipe_id").equals(row.path("recipe_id")))
                .count();
        if (count > 1) {
          assignment = row;
          break;
        }
      }
      assertTrue(assignment != null);
      var product = new Recipes.Product("menu_item", assignment.path("burger_id").asLong());
      var view = RecipePlanner.view(catalogue.state(), product, null);
      var selected = selected(view);
      String added =
          java.util.stream.StreamSupport.stream(view.path("ingredients").spliterator(), false)
              .filter(
                  r ->
                      r.path("key").asText().startsWith("burger:")
                          && r.path("available").asBoolean()
                          && !r.path("selected").asBoolean()
                          && store
                              .jdbc
                              .queryForObject(
                                  "SELECT component_type FROM public.burger_component WHERE component_id=?",
                                  String.class,
                                  Long.parseLong(r.path("key").asText().split(":")[1]))
                              .equals("protein"))
              .findFirst()
              .orElseThrow()
              .path("key")
              .asText();
      selected.add(added);
      save(catalogue, request(catalogue, product, selected, Map.of(), false));
      long recipe = assignment.path("recipe_id").asLong();
      for (var other : assignments)
        if (other.path("recipe_id").asLong() == recipe)
          assertEquals(
              other.path("burger_id").asLong() == product.id(),
              selected(
                      RecipePlanner.view(
                          catalogue.state(),
                          new Recipes.Product("menu_item", other.path("burger_id").asLong()),
                          null))
                  .contains(added));
      store.jdbc.update(
          "UPDATE public.burger_recipe_assignment SET protein_required=TRUE WHERE burger_id=?",
          product.id());
      var proteins =
          store.jdbc.queryForList(
              "SELECT component_id FROM public.burger_component WHERE component_type='protein'",
              Long.class);
      var noProtein = new ArrayList<>(selected);
      noProtein.removeIf(key -> proteins.stream().anyMatch(id -> key.equals("burger:" + id)));
      assertThrows(
          IllegalArgumentException.class,
          () -> save(catalogue, request(catalogue, product, noProtein, Map.of(), true)));
    }
  }
}
