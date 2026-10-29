package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DEV_CONNECTION", matches = ".+")
class ProductWorkspaceLiveTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void newPizzaIngredientsSizeAndPriceCommitTogetherThroughTheActualEditor() throws Exception {
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
              "UX tests",
              "storeName",
              "UX test store",
              "code",
              "UX"));
    var seed = new RecipeEditorLiveTest();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String category = "Workflow Pizzas " + suffix;
    seed.create(
        gateway,
        "pizza_category",
        Map.of("pizza_category_id", 0, "name", category, "sort_order", 0, "active", true));
    seed.create(
        gateway,
        "price_category",
        Map.of(
            "price_category_id",
            0,
            "name",
            "Workflow ingredients " + suffix,
            "sort_order",
            0,
            "active",
            true));
    long priceCategory =
        seed.named(
            seed.state(gateway),
            "price_category",
            "Workflow ingredients " + suffix,
            "price_category_id");
    if (java.util.stream.StreamSupport.stream(
            seed.state(gateway).path("tables").path("pizza_size").spliterator(), false)
        .noneMatch(r -> r.path("cm").asInt() == 31))
      seed.create(
          gateway,
          "pizza_size",
          Map.of("pizza_size_id", 0, "cm", 31, "sort_order", 0, "active", true));
    for (String name : List.of("Ham ", "Pineapple ", "Mozzarella "))
      seed.create(
          gateway,
          "ingredient",
          Map.of(
              "ingredient_id",
              0,
              "name",
              name + suffix,
              "price_category_id",
              priceCategory,
              "active",
              true,
              "seasonal",
              false));
    String productName = "Workflow Hawaiian " + suffix;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var workspace = GridModulesLiveTest.fx(() -> new ProductWorkspace(gateway, executor, null));
      var stage =
          GridModulesLiveTest.fx(
              () -> {
                var s = new Stage();
                var scene = new Scene(workspace, 1440, 900);
                scene
                    .getStylesheets()
                    .add(CataloguePanel.class.getResource("setup.css").toExternalForm());
                s.setScene(scene);
                s.show();
                return s;
              });
      try {
        GridModulesLiveTest.await(
            () -> ((TreeView<?>) workspace.lookup("#product-category-tree")).getRoot() != null);
        GridModulesLiveTest.fx(
            () -> {
              var tree = (TreeView) workspace.lookup("#product-category-tree");
              var node =
                  ((javafx.scene.control.TreeItem<?>) tree.getRoot())
                      .getChildren().stream()
                          .filter(n -> n.getValue().toString().equals(category))
                          .findFirst()
                          .orElseThrow();
              tree.getSelectionModel().select(node);
              Platform.runLater(
                  () -> {
                    var dialog =
                        Window.getWindows().stream()
                            .filter(w -> w != stage && w.isShowing())
                            .findFirst()
                            .orElseThrow();
                    dialog.getScene().getRoot().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b && b.getText().equals("OK"))
                        .map(n -> (Button) n)
                        .findFirst()
                        .orElseThrow()
                        .fire();
                  });
              ((Button) workspace.lookup("#product-add")).fire();
              return null;
            });
        GridModulesLiveTest.fx(
            () -> {
              ((TextField) workspace.lookup("#product-name")).setText(productName);
              select(workspace, "Sizes / Variants");
              workspace.lookupAll(".check-box").stream()
                  .filter(n -> n instanceof CheckBox c && c.getText().equals("31 cm"))
                  .map(n -> (CheckBox) n)
                  .findFirst()
                  .orElseThrow()
                  .fire();
              select(workspace, "Ingredients / Recipe");
              return null;
            });
        GridModulesLiveTest.fx(
            () -> {
              Platform.runLater(
                  () -> {
                    var dialog =
                        Window.getWindows().stream()
                            .filter(w -> w != stage && w.isShowing())
                            .findFirst()
                            .orElseThrow();
                    var root = dialog.getScene().getRoot();
                    ((TextField) root.lookup("#ingredient-picker-search")).setText(suffix);
                    root.lookupAll(".check-box").stream()
                        .filter(n -> n instanceof CheckBox c && c.getText().contains(suffix))
                        .map(n -> (CheckBox) n)
                        .forEach(CheckBox::fire);
                    root.lookupAll(".button").stream()
                        .filter(
                            n -> n instanceof Button b && b.getText().equals("Apply to Product"))
                        .map(n -> (Button) n)
                        .findFirst()
                        .orElseThrow()
                        .fire();
                  });
              ((Button) workspace.lookup("#product-edit-ingredients")).fire();
              select(workspace, "Pricing");
              ((TextField) workspace.lookup("#product-pricing").lookup(".text-field"))
                  .setText("109.50");
              ((Button) workspace.lookup("#product-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                ((Label) workspace.lookup("#product-unsaved"))
                    .getText()
                    .equals("All changes saved"));
        var state = seed.state(gateway);
        long product = seed.named(state, "pizza", productName, "pizza_id");
        var recipe = gateway.catalogue("recipes/pizza/" + product, "GET", null, null);
        assertEquals(
            3,
            java.util.stream.StreamSupport.stream(recipe.path("ingredients").spliterator(), false)
                .filter(r -> r.path("selected").asBoolean())
                .count());
        var catalog =
            JsonFiles.JSON.treeToValue(
                gateway.catalogue("items", "GET", null, null), ItemCustomization.Catalog.class);
        assertTrue(
            catalog.products().values().stream()
                .anyMatch(
                    p ->
                        p.name().contains(productName)
                            && p.price().compareTo(new BigDecimal("109.50")) == 0
                            && p.extras().stream().filter(e -> e.included() > 0).count() == 3));
      } finally {
        GridModulesLiveTest.fx(
            () -> {
              workspace.close();
              stage.close();
              return null;
            });
      }
    }
  }

  private void select(ProductWorkspace workspace, String text) {
    var tabs = (TabPane) workspace.lookup("#product-sections");
    tabs.getSelectionModel()
        .select(
            tabs.getTabs().stream()
                .filter(t -> t.getText().equals(text))
                .findFirst()
                .orElseThrow());
    workspace.applyCss();
    workspace.layout();
  }
}
