package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DEV_CONNECTION", matches = ".+")
class RecipeEditorLiveTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  JsonNode state(DevGateway gateway) throws Exception {
    return gateway.catalogue("state", "GET", null, null);
  }

  void create(DevGateway gateway, String table, Map<String, Object> values) throws Exception {
    gateway.catalogue(
        "records",
        "PUT",
        new CatalogueManagement.Edit(
            UUID.randomUUID(),
            state(gateway).path("revision").asLong(),
            List.of(new CatalogueManagement.Change(table, null, values, false)),
            false),
        null);
  }

  long named(JsonNode state, String table, String name, String column) {
    for (var row : state.path("tables").path(table))
      if (row.path("name").asText().equals(name)) return row.path(column).asLong();
    throw new AssertionError(name);
  }

  @Test
  void fiveCheckedIngredientsPersistThroughHttpAndDriveCashierRecipe() throws Exception {
    var config =
        JsonFiles.JSON.readTree(
            Files.readString(Path.of(System.getenv("POS_TEST_DEV_CONNECTION"))));
    var gateway =
        new DevGateway(
            config.path("token").asText(),
            config.path("instanceId").asText(),
            config.path("port").asInt());
    if (!gateway.call("/grid/store", "GET", null).path("configured").asBoolean())
      gateway.call(
          "/grid/store",
          "POST",
          Map.of(
              "commandId",
              UUID.randomUUID(),
              "businessName",
              "Recipe test",
              "storeName",
              "Recipe store",
              "code",
              "RECIPE"));
    create(
        gateway,
        "menu_category",
        Map.of("id", 0, "name", "Bulk recipe category", "sort_order", 9, "active", true));
    long category = named(state(gateway), "menu_category", "Bulk recipe category", "id");
    create(
        gateway,
        "price_category",
        Map.of(
            "price_category_id",
            0,
            "name",
            "Bulk recipe portions",
            "sort_order",
            0,
            "active",
            true));
    long priceCategory =
        named(state(gateway), "price_category", "Bulk recipe portions", "price_category_id");
    create(
        gateway,
        "menu_item",
        Map.of(
            "id",
            0,
            "category_id",
            category,
            "name",
            "Bulk recipe product",
            "description",
            "Owner-created food",
            "active",
            true,
            "sort_order",
            0,
            "is_300ml",
            false,
            "is_2l",
            false));
    long item = named(state(gateway), "menu_item", "Bulk recipe product", "id");
    int branch = state(gateway).path("branchId").asInt();
    create(
        gateway,
        "branch_menu_item_price",
        Map.of(
            "branch_id", branch, "menu_item_id", item, "price", new java.math.BigDecimal("30.00")));
    var ids = new ArrayList<Long>();
    for (int n = 1; n <= 5; n++) {
      String name = "Bulk ingredient " + n;
      create(
          gateway,
          "ingredient",
          Map.of(
              "ingredient_id",
              0,
              "name",
              name,
              "price_category_id",
              priceCategory,
              "active",
              true,
              "seasonal",
              false));
      ids.add(named(state(gateway), "ingredient", name, "ingredient_id"));
    }
    var initialRecipe = gateway.call("/grid/catalogue/recipes/menu_item/" + item, "GET", null);
    assertEquals(5, initialRecipe.path("ingredients").size());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var editor =
          GridModulesLiveTest.fx(
              () ->
                  new RecipeEditor(
                      gateway, executor, null, new Recipes.Product("menu_item", item), () -> {}));
      var stage =
          GridModulesLiveTest.fx(
              () -> {
                var s = new Stage();
                s.setScene(new Scene(editor, 1200, 700));
                s.show();
                return s;
              });
      try {
        GridModulesLiveTest.await(
            () -> editor.lookup("#recipe-ingredient-ingredient-" + ids.getFirst()) != null);
        GridModulesLiveTest.fx(
            () -> {
              ((TextField) editor.lookup("#recipe-search")).setText("Bulk ingredient");
              ((Button) editor.lookup("#recipe-select-visible")).fire();
              ((Button) editor.lookup("#recipe-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                !editor.isDisabled()
                    && ((Label) editor.lookup("#recipe-feedback")).getText().contains("saved"));
        for (long id : ids)
          GridModulesLiveTest.fx(
              () -> {
                assertTrue(
                    ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-" + id)).isSelected());
                return null;
              });
      } finally {
        GridModulesLiveTest.fx(
            () -> {
              editor.close();
              stage.close();
              return null;
            });
      }
    }
    var recipe = gateway.catalogue("recipes/menu_item/" + item, "GET", null, null);
    assertEquals(
        5,
        java.util.stream.StreamSupport.stream(recipe.path("ingredients").spliterator(), false)
            .filter(r -> r.path("selected").asBoolean())
            .count());
    var catalog =
        JsonFiles.JSON.treeToValue(
            gateway.catalogue("items", "GET", null, null), ItemCustomization.Catalog.class);
    var product =
        catalog.products().values().stream()
            .filter(p -> p.name().equals("Bulk recipe product"))
            .findFirst()
            .orElseThrow();
    assertEquals(5, product.extras().stream().filter(e -> e.included() > 0).count());
    assertEquals(
        item,
        gateway
            .catalogue("recipes/price/" + product.priceId(), "GET", null, null)
            .path("product")
            .path("id")
            .asLong());
  }
}
