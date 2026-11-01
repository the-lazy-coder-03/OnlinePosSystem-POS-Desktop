package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class CombinedCategoriesTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static class Gateway extends CataloguePanelTest.Gateway {
    final List<CatalogueManagement.Edit> edits = new ArrayList<>();

    Gateway() throws Exception {
      var menu = (ArrayNode) state.path("tables").path("menu_category");
      for (int i = 2; i <= 51; i++)
        menu.addObject()
            .put("id", i)
            .put("name", "Category " + i)
            .put("sort_order", i)
            .put("active", true);
      ((ArrayNode) state.path("tables").path("pizza_category"))
          .addObject()
          .put("pizza_category_id", 2)
          .put("name", "Seasonal pizzas")
          .put("sort_order", 3)
          .put("active", false);
    }

    @Override
    public synchronized JsonNode catalogue(String path, String method, Object payload, String token)
        throws Exception {
      if (!path.equals("records") || !method.equals("PUT"))
        return super.catalogue(path, method, payload, token);
      var edit = (CatalogueManagement.Edit) payload;
      edits.add(edit);
      for (var change : edit.changes()) {
        var rows = (ArrayNode) state.path("tables").path(change.table());
        String key = change.table().equals("pizza_category") ? "pizza_category_id" : "id";
        var value = (ObjectNode) JsonFiles.JSON.valueToTree(change.values());
        if (change.originalKey() == null) {
          long next = 1;
          for (var row : rows) next = Math.max(next, row.path(key).asLong() + 1);
          value.put(key, next);
          rows.add(value);
        } else {
          for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).path(key).asLong() == change.originalKey().getFirst())
              rows.set(i, value);
        }
      }
      state.put("revision", state.path("revision").asLong() + 1);
      return state.deepCopy();
    }
  }

  @SuppressWarnings("unchecked")
  private static TableView<JsonNode> table(CataloguePanel panel) {
    return (TableView<JsonNode>) panel.lookup("#catalogue-list");
  }

  private static void button(CataloguePanel panel, String text) {
    panel.lookupAll(".button").stream()
        .filter(n -> n instanceof Button b && b.getText().equals(text))
        .map(n -> (Button) n)
        .findFirst()
        .orElseThrow()
        .fire();
  }

  @Test
  void combinedPagingFilteringAndEditsKeepSourceIdentityAcrossReopening() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var panel = new CataloguePanel[1];
      var stage = new Stage[1];
      GridModulesLiveTest.fx(
          () -> {
            panel[0] =
                new CataloguePanel(gateway, executor, "test", "Store", "Owner", s -> {}, () -> {});
            panel[0].openCategories();
            stage[0] = new Stage();
            stage[0].setScene(new Scene(panel[0], 1280, 850));
            stage[0].show();
            return null;
          });
      try {
        GridModulesLiveTest.await(
            () -> table(panel[0]) != null && table(panel[0]).getItems().size() == 50);
        GridModulesLiveTest.fx(
            () -> {
              assertNull(panel[0].lookup("#catalogue-kind"));
              assertEquals("Drinks", table(panel[0]).getItems().getFirst().path("name").asText());
              button(panel[0], "Next");
              assertEquals(
                  List.of("Category 51", "Pizzas", "Seasonal pizzas"),
                  table(panel[0]).getItems().stream().map(r -> r.path("name").asText()).toList());
              ((TextField) panel[0].lookup("#catalogue-search")).setText("pizz");
              assertEquals(2, table(panel[0]).getItems().size());
              ((ComboBox<String>) panel[0].lookup("#catalogue-status")).setValue("Inactive");
              assertEquals(
                  "Seasonal pizzas", table(panel[0]).getItems().getFirst().path("name").asText());
              ((ComboBox<String>) panel[0].lookup("#catalogue-status")).setValue("All statuses");
              table(panel[0]).getSelectionModel().selectFirst();
              ((TextField) panel[0].lookup("#catalogue-field-name")).setText("Updated pizzas");
              ((Button) panel[0].lookup("#catalogue-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.edits.size() == 1
                    && !panel[0].lookup("#catalogue-add").isDisabled()
                    && table(panel[0]) != null);
        GridModulesLiveTest.fx(
            () -> {
              assertEquals("pizza_category", gateway.edits.getFirst().changes().getFirst().table());
              assertEquals(
                  List.of(1L), gateway.edits.getFirst().changes().getFirst().originalKey());
              table(panel[0]).getSelectionModel().selectFirst();
              ((TextField) panel[0].lookup("#catalogue-field-name")).setText("Updated drinks");
              ((Button) panel[0].lookup("#catalogue-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.edits.size() == 2
                    && !panel[0].lookup("#catalogue-add").isDisabled()
                    && table(panel[0]) != null);
        GridModulesLiveTest.fx(
            () -> {
              assertEquals("menu_category", gateway.edits.get(1).changes().getFirst().table());
              assertEquals(List.of(1L), gateway.edits.get(1).changes().getFirst().originalKey());
              ((Button) panel[0].lookup("#catalogue-add-pizza-category")).fire();
              ((TextField) panel[0].lookup("#catalogue-field-name")).setText("New pizzas");
              ((Button) panel[0].lookup("#catalogue-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.edits.size() == 3
                    && !panel[0].lookup("#catalogue-add").isDisabled()
                    && table(panel[0]) != null);
        GridModulesLiveTest.fx(
            () -> {
              assertNull(gateway.edits.get(2).changes().getFirst().originalKey());
              assertEquals("pizza_category", gateway.edits.get(2).changes().getFirst().table());
              ((Button) panel[0].lookup("#catalogue-add")).fire();
              ((TextField) panel[0].lookup("#catalogue-field-name")).setText("New category");
              ((Button) panel[0].lookup("#catalogue-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () ->
                gateway.edits.size() == 4
                    && !panel[0].lookup("#catalogue-add").isDisabled()
                    && table(panel[0]) != null);
        GridModulesLiveTest.fx(
            () -> {
              assertNull(gateway.edits.get(3).changes().getFirst().originalKey());
              assertEquals("menu_category", gateway.edits.get(3).changes().getFirst().table());
              panel[0].close();
              panel[0] =
                  new CataloguePanel(
                      gateway, executor, "test", "Store", "Owner", s -> {}, () -> {});
              panel[0].openCategories();
              stage[0].getScene().setRoot(panel[0]);
              return null;
            });
        GridModulesLiveTest.await(
            () -> table(panel[0]) != null && table(panel[0]).getItems().size() == 50);
        GridModulesLiveTest.fx(
            () -> {
              ((TextField) panel[0].lookup("#catalogue-search")).setText("Updated");
              assertEquals(
                  List.of("Updated drinks", "Updated pizzas"),
                  table(panel[0]).getItems().stream().map(r -> r.path("name").asText()).toList());
              assertTrue(panel[0].lookup("#catalogue-kind") == null);
              panel[0].applyCss();
              panel[0].layout();
              var image = panel[0].snapshot(null, null);
              int width = (int) image.getWidth(), height = (int) image.getHeight();
              int[] pixels = new int[width * height];
              image
                  .getPixelReader()
                  .getPixels(
                      0,
                      0,
                      width,
                      height,
                      javafx.scene.image.PixelFormat.getIntArgbInstance(),
                      pixels,
                      0,
                      width);
              var bitmap =
                  new java.awt.image.BufferedImage(
                      width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
              bitmap.setRGB(0, 0, width, height, pixels, 0, width);
              java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/category-ui"));
              javax.imageio.ImageIO.write(
                  bitmap,
                  "png",
                  java.nio.file.Path.of("target/category-ui/combined-categories.png").toFile());
              return null;
            });
      } finally {
        GridModulesLiveTest.fx(
            () -> {
              panel[0].close();
              stage[0].close();
              return null;
            });
      }
    }
  }
}
