package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ProductEdits;
import za.co.onlinepos.installation.JsonFiles;

/** Behavioral regression tests for the product workspace replacing the table-first catalogue. */
@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class CataloguePanelTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static final UUID PRICE =
      UUID.nameUUIDFromBytes("pizza-price".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  static class Gateway extends ModuleGateway {
    final ObjectNode state = JsonFiles.JSON.createObjectNode();
    final List<ProductEdits.Save> saves = new ArrayList<>();
    boolean fail = true;

    Gateway() throws Exception {
      super(Path.of("."));
      state.put("revision", 1).put("branchId", 1).put("editable", true);
      try (var input = getClass().getResourceAsStream("preview/catalogue-schema.json")) {
        state.set("schema", JsonFiles.JSON.readTree(input));
      }
      state.set(
          "tables",
          JsonFiles.JSON.readTree(
              """
        {"menu_category":[{"id":1,"name":"Drinks","sort_order":1,"active":true}],
         "pizza_category":[{"pizza_category_id":1,"name":"Pizzas","sort_order":2,"active":true}],
         "menu_item":[{"id":1,"category_id":1,"name":"Coke","description":"Drink","active":true,"sort_order":0,"is_300ml":true,"is_2l":false}],
         "pizza":[{"pizza_id":1,"pizza_category_id":1,"name":"Hawaiian Pizza","description":"Ham and pineapple","active":true,"sort_order":0}],
         "pizza_size":[{"pizza_size_id":1,"cm":30,"sort_order":0,"active":true}],
         "pizza_allowed_size":[{"pizza_id":1,"pizza_size_id":1}],
         "branch_pizza_price":[{"branch_id":1,"pizza_id":1,"pizza_size_id":1,"price":85.00}],
         "branch_menu_item_price":[{"branch_id":1,"menu_item_id":1,"price":15.00}],
         "ingredient":[{"ingredient_id":1,"name":"Ham","price_category_id":1,"active":true,"seasonal":false},
                       {"ingredient_id":2,"name":"Pineapple","price_category_id":1,"active":true,"seasonal":false}],
         "pizza_default_ingredient":[{"pizza_id":1,"ingredient_id":1,"default_qty":2,"is_removable":true,"sort_order":0}],
         "modifier_group":[{"id":1,"name":"Cup size","required":false,"min_select":0,"max_select":1}],"menu_item_modifier_group":[],"modifier_option":[{"id":1,"group_id":1,"name":"Regular","menu_item_id":null,"additional_price":0.00}],"burger_recipe_assignment":[],"burger_recipe":[],"price_category":[]}
        """));
      state.putObject("priceIdentities").put("branch_pizza_price[1, 1, 1]", PRICE.toString());
    }

    JsonNode recipe(String kind) {
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "family",
              "ingredient",
              "ingredients",
              kind.equals("pizza")
                  ? List.of(
                      Map.of(
                          "key",
                          "ingredient:1",
                          "name",
                          "Ham",
                          "available",
                          true,
                          "compatible",
                          true,
                          "selected",
                          true,
                          "origins",
                          List.of(
                              Map.of(
                                  "table",
                                  "pizza_default_ingredient",
                                  "properties",
                                  Map.of(
                                      "default_qty", 2, "is_removable", true, "sort_order", 0)))),
                      Map.of(
                          "key",
                          "ingredient:2",
                          "name",
                          "Pineapple",
                          "available",
                          true,
                          "compatible",
                          true,
                          "selected",
                          false,
                          "origins",
                          List.of()))
                  : List.of()));
    }

    ItemCustomization.Catalog catalog() {
      var product =
          new ItemCustomization.Product(
              PRICE,
              state.path("tables").path("pizza").get(0).path("name").asText(),
              "pizza:1",
              state.path("revision").asLong(),
              new BigDecimal("85.00"),
              true,
              List.of(),
              List.of(),
              null);
      return new ItemCustomization.Catalog(
          state.path("revision").asLong(),
          Map.of(PRICE, product),
          Map.of(),
          Map.of("pizza:1", "Pizzas", "menu:1", "Drinks"),
          ItemCustomization.Rules.empty(),
          true);
    }

    @Override
    public synchronized JsonNode catalogue(String path, String method, Object payload, String token)
        throws Exception {
      if (method.equals("PUT")) {
        var command = (ProductEdits.Save) payload;
        saves.add(command);
        if (fail) {
          fail = false;
          throw new za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure(503);
        }
        for (var c : command.changes())
          if (c.table().equals("pizza"))
            ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("tables").path("pizza"))
                .set(0, JsonFiles.JSON.valueToTree(c.values()));
        state.put("revision", state.path("revision").asLong() + 1);
        return JsonFiles.JSON.valueToTree(
            Map.of("product", Map.of("kind", command.kind(), "id", command.id())));
      }
      if (path.equals("state")) return state.deepCopy();
      if (path.equals("items")) return JsonFiles.JSON.valueToTree(catalog());
      if (path.startsWith("products/")) {
        String kind = path.split("/")[1];
        return JsonFiles.JSON.valueToTree(
            Map.of(
                "state",
                state,
                "catalog",
                catalog(),
                "details",
                state.path("tables").path(kind).get(0),
                "recipe",
                recipe(kind),
                "productRevision",
                "r" + state.path("revision").asLong()));
      }
      return state.deepCopy();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void productSectionsShareOneDraftAndRetryTheSameCommand() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var view =
          GridModulesLiveTest.fx(
              () ->
                  new CataloguePanel(
                      gateway, executor, "session", "Test store", "Owner", s -> {}, () -> {}));
      var stage =
          GridModulesLiveTest.fx(
              () -> {
                var s = new Stage();
                var scene = new Scene(view, 1440, 900);
                scene
                    .getStylesheets()
                    .add(CataloguePanel.class.getResource("setup.css").toExternalForm());
                s.setScene(scene);
                s.show();
                return s;
              });
      try {
        GridModulesLiveTest.fx(
            () -> {
              ((Button) view.lookup("#catalogue-help")).fire();
              var helpWindow =
                  Window.getWindows().stream()
                      .filter(w -> w.getScene().lookup("#catalogue-help-search") != null)
                      .findFirst()
                      .orElseThrow();
              var helpRoot = helpWindow.getScene().getRoot();
              ((TextField) helpRoot.lookup("#catalogue-help-search")).setText("Restore Inherited");
              assertTrue(
                  helpRoot.lookupAll(".label").stream()
                      .filter(Label.class::isInstance)
                      .map(Label.class::cast)
                      .anyMatch(
                          l ->
                              l.getText()
                                  .contains("Normal product edits affect only this product")));
              helpWindow.hide();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                view.lookup("#product-list") instanceof ListView<?> list
                    && list.getItems().size() == 2);
        GridModulesLiveTest.fx(
            () -> {
              var list = (ListView<Object>) view.lookup("#product-list");
              list.getSelectionModel()
                  .select(
                      list.getItems().stream()
                          .filter(p -> p.toString().contains("Hawaiian"))
                          .findFirst()
                          .orElseThrow());
              return null;
            });
        GridModulesLiveTest.await(() -> view.lookup("#product-name") != null);
        GridModulesLiveTest.fx(
            () -> {
              ((TextField) view.lookup("#product-name")).setText("Hawaiian Deluxe");
              selectTab(view, "Ingredients / Recipe");
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
                    root.lookupAll(".check-box").stream()
                        .filter(n -> n instanceof CheckBox c && c.getText().equals("Pineapple"))
                        .map(n -> (CheckBox) n)
                        .findFirst()
                        .orElseThrow()
                        .fire();
                    root.lookupAll(".button").stream()
                        .filter(
                            n -> n instanceof Button b && b.getText().equals("Apply to Product"))
                        .map(n -> (Button) n)
                        .findFirst()
                        .orElseThrow()
                        .fire();
                  });
              ((Button) view.lookup("#product-edit-ingredients")).fire();
              return null;
            });
        assertEquals(0, gateway.saves.size());
        GridModulesLiveTest.fx(
            () -> {
              selectTab(view, "Pricing");
              var box = view.lookup("#product-pricing");
              ((TextField) box.lookup(".text-field")).setText("99.50");
              ((Button) view.lookup("#product-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.saves.size() == 1 && !((Button) view.lookup("#product-save")).isDisabled());
        assertEquals(
            List.of("ingredient:1", "ingredient:2"), gateway.saves.getFirst().recipe().selected());
        assertTrue(
            gateway.saves.getFirst().changes().stream().anyMatch(c -> c.table().equals("pizza")));
        assertTrue(
            gateway.saves.getFirst().changes().stream()
                .anyMatch(c -> c.table().equals("branch_pizza_price")));
        GridModulesLiveTest.fx(
            () -> {
              ((Button) view.lookup("#product-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.saves.size() == 2
                    && view.lookup("#product-name") != null
                    && ((Label) view.lookup("#product-unsaved"))
                        .getText()
                        .equals("All changes saved"));
        assertEquals(gateway.saves.get(0), gateway.saves.get(1));
        GridModulesLiveTest.fx(
            () -> {
              view.applyCss();
              view.layout();
              var image = view.snapshot(null, null);
              var bitmap =
                  new java.awt.image.BufferedImage(
                      (int) image.getWidth(),
                      (int) image.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_ARGB);
              for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                  bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
              java.nio.file.Files.createDirectories(Path.of("target/product-ui"));
              javax.imageio.ImageIO.write(
                  bitmap, "png", Path.of("target/product-ui/product-editor.png").toFile());
              return null;
            });
        GridModulesLiveTest.fx(
            () -> {
              var list = (ListView<Object>) view.lookup("#product-list");
              list.getSelectionModel()
                  .select(
                      list.getItems().stream()
                          .filter(p -> p.toString().equals("Coke"))
                          .findFirst()
                          .orElseThrow());
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                view.lookup("#product-name") instanceof TextField name
                    && name.getText().equals("Coke")
                    && !name.isDisabled());
        GridModulesLiveTest.fx(
            () -> {
              var sections = (TabPane) view.lookup("#product-sections");
              assertTrue(
                  sections.getTabs().stream()
                      .noneMatch(t -> t.getText().equals("Ingredients / Recipe")));
              selectTab(view, "Modifiers / Extras");
              assertTrue(
                  ((TabPane) view.lookup("#item-tabs"))
                      .getTabs().stream().noneMatch(t -> t.getText().equals("Order builder")));
              view.lookupAll(".check-box").stream()
                  .filter(n -> n instanceof CheckBox c && c.getText().equals("Cup size"))
                  .map(n -> (CheckBox) n)
                  .findFirst()
                  .orElseThrow()
                  .fire();
              var optionTabs = (TabPane) view.lookup("#item-tabs");
              optionTabs
                  .getSelectionModel()
                  .select(
                      optionTabs.getTabs().stream()
                          .filter(t -> t.getText().equals("Options"))
                          .findFirst()
                          .orElseThrow());
              view.applyCss();
              view.layout();
              assertTrue(
                  view.lookupAll(".label").stream()
                      .anyMatch(
                          n -> n instanceof Label label && label.getText().contains("Cup size")));
              view.lookupAll(".check-box").stream()
                  .filter(n -> n instanceof CheckBox c && c.getText().equals("Cup size"))
                  .map(n -> (CheckBox) n)
                  .findFirst()
                  .orElseThrow()
                  .fire();
              view.applyCss();
              view.layout();
              ((javafx.scene.control.ComboBox<String>) view.lookup("#item-type")).setValue("Combo");
              assertTrue(
                  sections.getTabs().stream()
                      .anyMatch(t -> t.getText().equals("Combo Configuration")));
              return null;
            });
      } finally {
        GridModulesLiveTest.fx(
            () -> {
              view.close();
              stage.close();
              return null;
            });
      }
    }
  }

  private void selectTab(CataloguePanel panel, String title) {
    var tabs = (TabPane) panel.lookup("#product-sections");
    tabs.getSelectionModel()
        .select(
            tabs.getTabs().stream()
                .filter(t -> t.getText().equals(title))
                .findFirst()
                .orElseThrow());
    panel.applyCss();
    panel.layout();
  }
}
