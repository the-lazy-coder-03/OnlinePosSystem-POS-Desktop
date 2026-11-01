package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class ToppingPageTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static class Gateway extends ModuleGateway {
    final ItemCustomization.Catalog catalog = ToppingDraftTest.catalog();
    volatile boolean fail;
    volatile int blockExtras = -1;
    final CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);

    Gateway() {
      super(Path.of("unused-topping-test"));
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode catalogue(
        String path, String method, Object body, String token) throws Exception {
      if (path.equals("items/quote")) {
        var request = (ItemCustomization.QuoteRequest) body;
        if (request.selection().additions().getOrDefault("cheese", 0) == blockExtras) {
          requested.countDown();
          if (!release.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("Test quote release timed out");
        }
        if (fail) {
          fail = false;
          throw new IllegalStateException("Test store unavailable");
        }
        return JsonFiles.JSON.valueToTree(ItemCustomization.quote(catalog, request));
      }
      throw new IllegalArgumentException(path);
    }
  }

  static class Fixture implements AutoCloseable {
    final Gateway gateway = new Gateway();
    final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    final SalesRuntime runtime;
    final Stage stage;
    final Map<UUID, UUID> buttons = new java.util.HashMap<>();
    final TableView<SalesRuntime.Line> order;
    UUID editId;

    @SuppressWarnings("unchecked")
    Fixture() throws Exception {
      var widgets = new ArrayList<SalesScreens.Widget>();
      int i = 0;
      for (UUID id :
          List.of(
              ToppingDraftTest.PIZZA,
              ToppingDraftTest.BURGER,
              ToppingDraftTest.DRINK,
              ToppingDraftTest.COMBO)) {
        var button =
            ScreenDocuments.widget(
                SalesScreens.Type.PRODUCT_BUTTON,
                gateway.catalog.products().get(id).name(),
                new SalesScreens.Rect(10 + i++ * 160, 10, 150, 70, 1),
                Map.of("priceId", id.toString()));
        widgets.add(button);
        buttons.put(id, button.id());
      }
      var edit =
          ScreenDocuments.widget(
              SalesScreens.Type.FUNCTION_BUTTON,
              "Edit item",
              new SalesScreens.Rect(700, 10, 150, 70, 1),
              Map.of("action", "EDIT_LINE"));
      widgets.add(edit);
      editId = edit.id();
      widgets.add(
          ScreenDocuments.widget(
              SalesScreens.Type.ORDER_LIST,
              "Order",
              new SalesScreens.Rect(10, 100, 1180, 620, 1),
              Map.of()));
      var doc =
          new SalesScreens.Document(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              new SalesScreens.Canvas(1200, 800, 8),
              List.of(
                  new SalesScreens.Page(
                      UUID.randomUUID(),
                      "Sales",
                      SalesScreens.Purpose.SALES,
                      List.copyOf(widgets))),
              List.of(),
              List.of());
      runtime =
          GridModulesLiveTest.fx(
              () -> new SalesRuntime(gateway, executor, "preview", doc, gateway.catalog));
      stage =
          GridModulesLiveTest.fx(
              () -> {
                var window = new Stage();
                window.setScene(new Scene(runtime, 1280, 850));
                window.show();
                return window;
              });
      order =
          GridModulesLiveTest.fx(
              () -> (TableView<SalesRuntime.Line>) runtime.lookup(".table-view"));
    }

    void add(UUID id) {
      ((Button) runtime.lookup("#sales-widget-" + buttons.get(id))).fire();
    }

    void tap(String key) {
      ((Button) runtime.lookup("#topping-ingredient-" + key)).fire();
    }

    void press(String id) {
      ((Button) runtime.lookup("#" + id)).fire();
    }

    void doubleTap(int at) {
      order.applyCss();
      order.layout();
      var id = order.getItems().get(at).id();
      var row =
          order.lookupAll(".table-row-cell").stream()
              .filter(TableRow.class::isInstance)
              .map(n -> (TableRow<?>) n)
              .filter(r -> r.getItem() instanceof SalesRuntime.Line l && l.id().equals(id))
              .findFirst()
              .orElseThrow();
      row.fireEvent(
          new javafx.scene.input.MouseEvent(
              javafx.scene.input.MouseEvent.MOUSE_CLICKED,
              10,
              10,
              10,
              10,
              javafx.scene.input.MouseButton.PRIMARY,
              2,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              new javafx.scene.input.PickResult(row, 10, 10)));
    }

    boolean ready() {
      return runtime.lookup("#topping-ok") instanceof Button b && !b.isDisabled();
    }

    @Override
    public void close() throws Exception {
      gateway.release.countDown();
      GridModulesLiveTest.fx(
          () -> {
            runtime.close();
            stage.close();
            return null;
          });
      executor.shutdownNow();
    }
  }

  @Test
  void tapsModesCancelAndApplyChangeOnlyTheSelectedLine() throws Exception {
    try (var f = new Fixture()) {
      GridModulesLiveTest.fx(
          () -> {
            f.add(ToppingDraftTest.PIZZA);
            f.add(ToppingDraftTest.PIZZA);
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().size() == 2);
      GridModulesLiveTest.fx(
          () -> {
            f.order.getSelectionModel().selectFirst();
            ((Button) f.runtime.lookup("#sales-widget-" + f.editId)).fire();
            var dialog = GridModulesLiveTest.dialog();
            ((javafx.scene.control.Spinner<Integer>) dialog.lookup("#line-quantity"))
                .getValueFactory()
                .setValue(2);
            ((javafx.scene.control.TextArea) dialog.lookup("#line-notes"))
                .setText("Cut into slices");
            var confirm =
                dialog.getButtonTypes().stream()
                    .filter(
                        b -> b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            ((Button) dialog.lookupButton(confirm)).fire();
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().getFirst().request().quantity() == 2);
      var original = GridModulesLiveTest.fx(() -> List.copyOf(f.order.getItems()));
      GridModulesLiveTest.fx(
          () -> {
            f.doubleTap(0);
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      GridModulesLiveTest.fx(
          () -> {
            assertNotNull(f.runtime.lookup("#topping-page"));
            f.tap("cheese");
            ((ToggleButton) f.runtime.lookup("#topping-remove")).fire();
            assertTrue(f.runtime.lookup("#topping-ingredient-unavailable").isDisabled());
            f.tap("unavailable");
            assertTrue(((ToggleButton) f.runtime.lookup("#topping-remove")).isSelected());
            f.tap("cheese");
            assertFalse(((ToggleButton) f.runtime.lookup("#topping-remove")).isSelected());
            f.tap("ham");
            assertEquals(original, List.copyOf(f.order.getItems()));
            f.press("topping-cancel");
            assertEquals(original, List.copyOf(f.order.getItems()));
            f.doubleTap(0);
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      GridModulesLiveTest.fx(
          () -> {
            ((ToggleButton) f.runtime.lookup("#topping-remove")).fire();
            f.tap("cheese");
            f.tap("cheese"); // restore included portion free
            f.tap("cheese"); // paid extra
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      GridModulesLiveTest.fx(
          () -> {
            snapshots(f);
            f.press("topping-ok");
            return null;
          });
      GridModulesLiveTest.await(() -> f.runtime.lookup("#topping-page") == null);
      GridModulesLiveTest.fx(
          () -> {
            assertEquals(2, f.order.getItems().size());
            assertEquals(original.getFirst().id(), f.order.getItems().getFirst().id());
            assertEquals(original.getFirst().notes(), f.order.getItems().getFirst().notes());
            assertEquals(
                original.getFirst().request().quantity(),
                f.order.getItems().getFirst().request().quantity());
            assertEquals(
                1, f.order.getItems().getFirst().request().selection().additions().get("cheese"));
            assertEquals(original.get(1), f.order.getItems().get(1));
            return null;
          });
    }
  }

  @Test
  void absentToppingsDoNothingAndRequiredPagesBlockFurtherProductTaps() throws Exception {
    try (var f = new Fixture()) {
      GridModulesLiveTest.fx(
          () -> {
            f.add(ToppingDraftTest.DRINK);
            f.add(ToppingDraftTest.DRINK);
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().size() == 2);
      GridModulesLiveTest.fx(
          () -> {
            f.doubleTap(0);
            assertNull(f.runtime.lookup("#topping-page"));
            f.add(ToppingDraftTest.BURGER);
            f.add(ToppingDraftTest.PIZZA);
            assertEquals(2, f.order.getItems().size());
            var d = GridModulesLiveTest.dialog();
            ((Button) d.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
            assertEquals(2, f.order.getItems().size());
            return null;
          });
    }
  }

  @Test
  void quoteFailuresRetainDraftAndCancelDuringConfirmationCannotApplyLateResponse()
      throws Exception {
    try (var f = new Fixture()) {
      GridModulesLiveTest.fx(
          () -> {
            f.add(ToppingDraftTest.PIZZA);
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().size() == 1);
      var original = GridModulesLiveTest.fx(() -> f.order.getItems().getFirst());
      GridModulesLiveTest.fx(
          () -> {
            f.doubleTap(0);
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      f.gateway.fail = true;
      GridModulesLiveTest.fx(
          () -> {
            f.tap("cheese");
            return null;
          });
      GridModulesLiveTest.await(
          () ->
              ((Label) f.runtime.lookup("#topping-message"))
                  .getText()
                  .contains("Changes retained"));
      GridModulesLiveTest.fx(
          () -> {
            assertFalse(f.ready());
            assertEquals(original, f.order.getItems().getFirst());
            f.press("topping-retry");
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      f.gateway.fail = true;
      GridModulesLiveTest.fx(
          () -> {
            f.press("topping-ok");
            return null;
          });
      GridModulesLiveTest.await(
          () ->
              ((Label) f.runtime.lookup("#topping-message"))
                  .getText()
                  .contains("Changes retained"));
      GridModulesLiveTest.fx(
          () -> {
            assertEquals(original, f.order.getItems().getFirst());
            assertNotNull(f.runtime.lookup("#topping-page"));
            f.press("topping-retry");
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      f.gateway.blockExtras = 1;
      GridModulesLiveTest.fx(
          () -> {
            f.press("topping-ok");
            return null;
          });
      assertTrue(f.gateway.requested.await(5, TimeUnit.SECONDS));
      GridModulesLiveTest.fx(
          () -> {
            f.press("topping-cancel");
            return null;
          });
      f.gateway.release.countDown();
      GridModulesLiveTest.await(() -> f.runtime.lookup("#topping-page") == null);
      GridModulesLiveTest.fx(
          () -> {
            assertEquals(original, f.order.getItems().getFirst());
            return null;
          });
    }
  }

  @Test
  void olderQuoteCannotReplaceNewerToppingPrice() throws Exception {
    try (var f = new Fixture()) {
      GridModulesLiveTest.fx(
          () -> {
            f.add(ToppingDraftTest.PIZZA);
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().size() == 1);
      GridModulesLiveTest.fx(
          () -> {
            f.doubleTap(0);
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      f.gateway.blockExtras = 1;
      GridModulesLiveTest.fx(
          () -> {
            f.tap("cheese");
            return null;
          });
      assertTrue(f.gateway.requested.await(5, TimeUnit.SECONDS));
      GridModulesLiveTest.fx(
          () -> {
            f.tap("cheese");
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      f.gateway.release.countDown();
      GridModulesLiveTest.fx(
          () -> {
            assertTrue(((Label) f.runtime.lookup("#topping-total")).getText().contains("60.00"));
            f.press("topping-ok");
            return null;
          });
      GridModulesLiveTest.await(() -> f.runtime.lookup("#topping-page") == null);
      GridModulesLiveTest.fx(
          () -> {
            assertEquals(
                2, f.order.getItems().getFirst().request().selection().additions().get("cheese"));
            return null;
          });
    }
  }

  @Test
  void comboComponentPickerStagesNestedSelections() throws Exception {
    try (var f = new Fixture()) {
      GridModulesLiveTest.fx(
          () -> {
            f.add(ToppingDraftTest.COMBO);
            var d = GridModulesLiveTest.dialog();
            while (d.lookup("#combo-next") instanceof Button b && b.isVisible()) b.fire();
            var confirm =
                d.getButtonTypes().stream()
                    .filter(
                        b -> b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            ((Button) d.lookupButton(confirm)).fire();
            return null;
          });
      GridModulesLiveTest.await(() -> f.order.getItems().size() == 1);
      var original = GridModulesLiveTest.fx(() -> f.order.getItems().getFirst());
      GridModulesLiveTest.fx(
          () -> {
            f.doubleTap(0);
            f.runtime.lookup("#topping-components").lookupAll(".button").stream()
                .map(n -> (Button) n)
                .filter(b -> b.getText().startsWith("Pizza"))
                .findFirst()
                .orElseThrow()
                .fire();
            f.tap("ham");
            return null;
          });
      GridModulesLiveTest.await(f::ready);
      GridModulesLiveTest.fx(
          () -> {
            assertEquals(original, f.order.getItems().getFirst());
            f.press("topping-ok");
            return null;
          });
      GridModulesLiveTest.await(() -> f.runtime.lookup("#topping-page") == null);
      GridModulesLiveTest.fx(
          () -> {
            var changed = f.order.getItems().getFirst();
            assertEquals(ToppingDraftTest.COMBO, changed.request().priceId());
            assertEquals(new java.math.BigDecimal("98.00"), changed.quote().total().setScale(2));
            assertEquals(
                original.quote().selection().components().get(1),
                changed.quote().selection().components().get(1));
            return null;
          });
    }
  }

  private static void snapshots(Fixture f) throws Exception {
    f.stage.getScene().setRoot(new javafx.scene.layout.Pane());
    for (int[] size : new int[][] {{1024, 768}, {1200, 800}, {1920, 1080}}) {
      var scene = new Scene(f.runtime, size[0], size[1]);
      f.runtime.resize(size[0], size[1]);
      f.runtime.applyCss();
      f.runtime.layout();
      var bounds = f.runtime.localToScene(f.runtime.getLayoutBounds());
      for (String id :
          List.of("topping-ok", "topping-cancel", "topping-remove", "topping-ingredient-cheese")) {
        var node = f.runtime.lookup("#" + id);
        var box = node.localToScene(node.getLayoutBounds());
        assertTrue(box.getMinX() >= bounds.getMinX() && box.getMaxX() <= bounds.getMaxX());
        assertTrue(box.getMinY() >= bounds.getMinY() && box.getMaxY() <= bounds.getMaxY());
        assertTrue(node.getLayoutBounds().getHeight() >= 44);
        assertTrue(node.isFocusTraversable());
      }
      assertTrue(((Button) f.runtime.lookup("#topping-ok")).isDefaultButton());
      assertTrue(((Button) f.runtime.lookup("#topping-cancel")).isCancelButton());
      assertEquals(2, ((TableView<?>) f.runtime.lookup("#topping-ingredients")).getItems().size());
      var image = f.runtime.snapshot(null, null);
      int w = (int) image.getWidth(), h = (int) image.getHeight();
      assertEquals(size[0], w);
      assertEquals(size[1], h);
      var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      int[] pixels = new int[w * h];
      image
          .getPixelReader()
          .getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
      out.setRGB(0, 0, w, h, pixels, 0, w);
      java.nio.file.Files.createDirectories(Path.of("target/topping-ui"));
      javax.imageio.ImageIO.write(
          out, "png", Path.of("target/topping-ui/toppings-" + w + ".png").toFile());
      scene.setRoot(new javafx.scene.layout.Pane());
    }
    f.stage.setScene(new Scene(f.runtime, 1280, 850));
  }
}
