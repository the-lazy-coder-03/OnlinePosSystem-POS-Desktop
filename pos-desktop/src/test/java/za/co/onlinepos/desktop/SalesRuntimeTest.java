package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class SalesRuntimeTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  private static <T> T fx(Callable<T> task) throws Exception {
    var future = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            future.complete(task.call());
          } catch (Exception | AssertionError e) {
            future.completeExceptionally(e);
          }
        });
    return future.get(15, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (fx(condition)) return;
      Thread.sleep(20);
    }
    fail("Sales transition timed out");
  }

  @Test
  void tenTapsCreateTenDistinctLines() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var gateway = new PreviewGateway();
    var catalog =
        JsonFiles.JSON.treeToValue(
            gateway.catalogue("items", "GET", null, "preview"), ItemCustomization.Catalog.class);
    var simple =
        catalog.products().values().stream()
            .filter(
                p ->
                    p.available()
                        && ItemCustomization.effective(catalog, p).combo() == null
                        && ItemCustomization.effective(catalog, p).groups().isEmpty())
            .findFirst()
            .orElseThrow();
    var doc = ScreenDocuments.starter(UUID.randomUUID(), catalog);
    var productWidget =
        doc.pages().stream()
            .flatMap(p -> p.widgets().stream())
            .filter(
                w ->
                    w.type() == SalesScreens.Type.PRODUCT_BUTTON
                        && simple.priceId().toString().equals(w.binding().get("priceId")))
            .findFirst()
            .orElseThrow();
    var page =
        doc.pages().stream()
            .filter(p -> p.widgets().contains(productWidget))
            .findFirst()
            .orElseThrow();
    var orderedPages = new java.util.ArrayList<>(doc.pages());
    orderedPages.remove(page);
    orderedPages.addFirst(page);
    var screen =
        new SalesScreens.Document(
            1,
            doc.screenId(),
            doc.storeId(),
            doc.canvas(),
            orderedPages,
            doc.workflows(),
            doc.templateOrigins());
    final SalesRuntime[] runtime = new SalesRuntime[1];
    final Stage[] stage = new Stage[1];
    try {
      fx(
          () -> {
            runtime[0] = new SalesRuntime(gateway, executor, "preview", screen, catalog);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(runtime[0], 1200, 780));
            stage[0].show();
            var button = (Button) runtime[0].lookup("#sales-widget-" + productWidget.id());
            for (int i = 0; i < 10; i++) button.fire();
            return null;
          });
      await(() -> ((TableView<?>) runtime[0].lookup(".table-view")).getItems().size() == 10);
      fx(
          () -> {
            var table = (TableView<?>) runtime[0].lookup(".table-view");
            assertEquals(
                10,
                table.getItems().stream()
                    .map(l -> ((SalesRuntime.Line) l).id())
                    .distinct()
                    .count());
            assertTrue(
                table.getItems().stream()
                    .allMatch(l -> ((SalesRuntime.Line) l).quote().quantity() == 1));
            return null;
          });
      fx(
          () -> {
            var table = (TableView<?>) runtime[0].lookup(".table-view");
            table.getSelectionModel().select(0);
            var keypad = runtime[0].lookup(".quantity-keypad");
            var buttons = keypad.lookupAll(".button").stream().map(n -> (Button) n).toList();
            buttons.stream().filter(b -> b.getText().equals("4")).findFirst().orElseThrow().fire();
            buttons.stream()
                .filter(b -> b.getText().equals("Apply quantity"))
                .findFirst()
                .orElseThrow()
                .fire();
            return null;
          });
      await(
          () ->
              ((SalesRuntime.Line)
                          ((TableView<?>) runtime[0].lookup(".table-view")).getItems().getFirst())
                      .quote()
                      .quantity()
                  == 4);
      fx(
          () -> {
            var table = (TableView<?>) runtime[0].lookup(".table-view");
            assertEquals(
                9,
                table.getItems().stream()
                    .filter(l -> ((SalesRuntime.Line) l).quote().quantity() == 1)
                    .count());
            stage[0].getScene().setRoot(new javafx.scene.layout.Pane());
            for (int[] size : new int[][] {{1024, 768}, {1200, 800}, {1920, 1080}}) {
              var scene = new Scene(runtime[0], size[0], size[1]);
              runtime[0].resize(size[0], size[1]);
              runtime[0].applyCss();
              runtime[0].layout();
              var image = runtime[0].snapshot(null, null);
              int width = (int) image.getWidth(), height = (int) image.getHeight();
              assertEquals(size[0], width);
              assertEquals(size[1], height);
              var bounds = runtime[0].localToScene(runtime[0].getLayoutBounds());
              for (var node :
                  doc.pages().stream()
                      .flatMap(p -> p.widgets().stream())
                      .map(w -> runtime[0].lookup("#sales-widget-" + w.id()))
                      .filter(java.util.Objects::nonNull)
                      .toList()) {
                var box = node.localToScene(node.getLayoutBounds());
                assertTrue(
                    box.getMinX() >= bounds.getMinX() - 1 && box.getMaxX() <= bounds.getMaxX() + 1);
                assertTrue(
                    box.getMinY() >= bounds.getMinY() - 1 && box.getMaxY() <= bounds.getMaxY() + 1);
              }
              var out =
                  new java.awt.image.BufferedImage(
                      width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
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
              out.setRGB(0, 0, width, height, pixels, 0, width);
              javax.imageio.ImageIO.write(
                  out,
                  "png",
                  new java.io.File(
                      System.getProperty("java.io.tmpdir"), "sales-photo-" + size[0] + ".png"));
              assertTrue(runtime[0].lookupAll(".selected-category").size() >= 1);
              scene.setRoot(new javafx.scene.layout.Pane());
            }
            stage[0].setScene(new Scene(runtime[0], 1200, 800));
            return null;
          });
    } finally {
      fx(
          () -> {
            if (runtime[0] != null) runtime[0].close();
            if (stage[0] != null) stage[0].close();
            return null;
          });
      executor.shutdownNow();
    }
  }

  @Test
  void photoConversionPreviewCanCancelApplyAndUndoWithoutSaving() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var editor = new ScreenBuilderEditor[1];
    var stage = new Stage[1];
    var field = ScreenBuilderEditor.class.getDeclaredField("document");
    field.setAccessible(true);
    var dirty = ScreenBuilderEditor.class.getDeclaredField("dirty");
    dirty.setAccessible(true);
    try {
      fx(
          () -> {
            editor[0] = new ScreenBuilderEditor(new PreviewGateway(), executor, "preview");
            stage[0] = new Stage();
            stage[0].setScene(new Scene(editor[0], 1280, 850));
            stage[0].show();
            return null;
          });
      await(() -> field.get(editor[0]) != null && !editor[0].isDisabled());
      var old =
          fx(
              () -> {
                var doc = (SalesScreens.Document) field.get(editor[0]);
                var previous =
                    new SalesScreens.Document(
                        1,
                        doc.screenId(),
                        doc.storeId(),
                        new SalesScreens.Canvas(1200, 900, 8),
                        doc.pages(),
                        doc.workflows(),
                        doc.templateOrigins());
                field.set(editor[0], previous);
                return previous;
              });
      for (boolean apply : new boolean[] {false, true}) {
        fx(
            () -> {
              editor[0].lookupAll(".button").stream()
                  .filter(
                      n ->
                          n instanceof Button b
                              && b.getText().equals("Apply photo layout to all Sales pages"))
                  .map(n -> (Button) n)
                  .findFirst()
                  .orElseThrow()
                  .fire();
              return null;
            });
        await(
            () ->
                javafx.stage.Window.getWindows().stream()
                    .anyMatch(
                        w ->
                            w instanceof Stage st
                                && String.valueOf(st.getTitle())
                                    .startsWith("Review photo layout")));
        fx(
            () -> {
              var window =
                  javafx.stage.Window.getWindows().stream()
                      .filter(
                          w ->
                              w instanceof Stage st
                                  && String.valueOf(st.getTitle())
                                      .startsWith("Review photo layout"))
                      .findFirst()
                      .orElseThrow();
              window.getScene().getRoot().lookupAll(".button").stream()
                  .filter(
                      n ->
                          n instanceof Button b
                              && b.getText().equals(apply ? "Apply layout" : "Cancel"))
                  .map(n -> (Button) n)
                  .findFirst()
                  .orElseThrow()
                  .fire();
              return null;
            });
        await(
            () ->
                !javafx.stage.Window.getWindows().stream()
                    .anyMatch(
                        w ->
                            w instanceof Stage st
                                && String.valueOf(st.getTitle())
                                    .startsWith("Review photo layout")));
        fx(
            () -> {
              if (apply) {
                assertEquals(800, ((SalesScreens.Document) field.get(editor[0])).canvas().height());
                assertTrue(dirty.getBoolean(editor[0]));
              } else {
                assertEquals(old, field.get(editor[0]));
                assertTrue(!dirty.getBoolean(editor[0]));
              }
              return null;
            });
      }
      fx(
          () -> {
            editor[0].lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && b.getText().equals("Undo"))
                .map(n -> (Button) n)
                .findFirst()
                .orElseThrow()
                .fire();
            assertEquals(old, field.get(editor[0]));
            return null;
          });
    } finally {
      fx(
          () -> {
            if (editor[0] != null) editor[0].close();
            if (stage[0] != null) stage[0].close();
            return null;
          });
      executor.shutdownNow();
    }
  }

  @Test
  void guidedComboCancelsAndExplicitEditorRetainsQuantityAndNotes() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var gateway = new PreviewGateway();
    var catalog =
        JsonFiles.JSON.treeToValue(
            gateway.catalogue("items", "GET", null, "preview"), ItemCustomization.Catalog.class);
    var simple =
        catalog.products().values().stream()
            .filter(
                p ->
                    p.available()
                        && ItemCustomization.effective(catalog, p).combo() == null
                        && ItemCustomization.effective(catalog, p).groups().isEmpty())
            .findFirst()
            .orElseThrow();
    var combo =
        catalog.products().values().stream()
            .filter(p -> p.combo() != null)
            .findFirst()
            .orElseThrow();
    var simpleButton =
        ScreenDocuments.widget(
            SalesScreens.Type.PRODUCT_BUTTON,
            "Simple",
            new SalesScreens.Rect(20, 20, 170, 80, 1),
            java.util.Map.of("priceId", simple.priceId().toString()));
    var comboButton =
        ScreenDocuments.widget(
            SalesScreens.Type.PRODUCT_BUTTON,
            "Combo",
            new SalesScreens.Rect(20, 120, 170, 80, 1),
            java.util.Map.of("priceId", combo.priceId().toString()));
    var order =
        ScreenDocuments.widget(
            SalesScreens.Type.ORDER_LIST,
            "Order",
            new SalesScreens.Rect(220, 20, 900, 600, 1),
            java.util.Map.of());
    var editButton =
        ScreenDocuments.widget(
            SalesScreens.Type.FUNCTION_BUTTON,
            "Edit item",
            new SalesScreens.Rect(20, 220, 170, 70, 1),
            java.util.Map.of("action", "EDIT_LINE"));
    var doc =
        new SalesScreens.Document(
            1,
            UUID.randomUUID(),
            PreviewGateway.SCREEN_STORE,
            new SalesScreens.Canvas(1200, 720, 8),
            java.util.List.of(
                new SalesScreens.Page(
                    UUID.randomUUID(),
                    "Sales",
                    SalesScreens.Purpose.SALES,
                    java.util.List.of(simpleButton, comboButton, order, editButton))),
            java.util.List.of(),
            java.util.List.of());
    final SalesRuntime[] runtime = new SalesRuntime[1];
    final Stage[] stage = new Stage[1];
    try {
      fx(
          () -> {
            runtime[0] = new SalesRuntime(gateway, executor, "preview", doc, catalog);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(runtime[0], 1200, 780));
            stage[0].show();
            ((Button) runtime[0].lookup("#sales-widget-" + comboButton.id())).fire();
            return null;
          });
      fx(
          () -> {
            var d = dialog();
            assertTrue(d.lookup("#combo-next") != null);
            ((Button) d.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
            assertEquals(0, ((TableView<?>) runtime[0].lookup(".table-view")).getItems().size());
            var b = (Button) runtime[0].lookup("#sales-widget-" + simpleButton.id());
            b.fire();
            b.fire();
            return null;
          });
      await(() -> ((TableView<?>) runtime[0].lookup(".table-view")).getItems().size() == 2);
      UUID selected =
          fx(
              () ->
                  ((SalesRuntime.Line)
                          ((TableView<?>) runtime[0].lookup(".table-view")).getItems().getFirst())
                      .id());
      fx(
          () -> {
            var table = (TableView<?>) runtime[0].lookup(".table-view");
            table.applyCss();
            table.layout();
            var row =
                table.lookupAll(".table-row-cell").stream()
                    .filter(javafx.scene.control.TableRow.class::isInstance)
                    .map(n -> (javafx.scene.control.TableRow<?>) n)
                    .filter(
                        n -> n.getItem() instanceof SalesRuntime.Line l && l.id().equals(selected))
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
            return null;
          });
      fx(
          () -> {
            assertTrue(runtime[0].lookup("#topping-page") == null);
            ((Button) runtime[0].lookup("#sales-widget-" + editButton.id())).fire();
            var d = dialog();
            ((javafx.scene.control.Spinner<Integer>) d.lookup("#line-quantity"))
                .getValueFactory()
                .setValue(3);
            ((javafx.scene.control.TextArea) d.lookup("#line-notes")).setText("Please cut");
            var ok =
                d.getButtonTypes().stream()
                    .filter(
                        t -> t.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            ((Button) d.lookupButton(ok)).fire();
            return null;
          });
      await(
          () ->
              ((TableView<?>) runtime[0].lookup(".table-view"))
                  .getItems().stream()
                      .anyMatch(
                          n ->
                              n instanceof SalesRuntime.Line l
                                  && l.id().equals(selected)
                                  && l.quote().quantity() == 3));
      fx(
          () -> {
            var lines = ((TableView<?>) runtime[0].lookup(".table-view")).getItems();
            assertEquals(2, lines.size());
            assertEquals(
                1,
                lines.stream()
                    .filter(n -> n instanceof SalesRuntime.Line l && l.quote().quantity() == 1)
                    .count());
            assertEquals(
                "Please cut",
                lines.stream()
                    .map(n -> (SalesRuntime.Line) n)
                    .filter(l -> l.id().equals(selected))
                    .findFirst()
                    .orElseThrow()
                    .notes());
            return null;
          });
    } finally {
      fx(
          () -> {
            if (runtime[0] != null) runtime[0].close();
            if (stage[0] != null) stage[0].close();
            return null;
          });
      executor.shutdownNow();
    }
  }

  private static javafx.scene.control.DialogPane dialog() {
    return javafx.stage.Window.getWindows().stream()
        .filter(javafx.stage.Window::isShowing)
        .map(w -> w.getScene().getRoot())
        .filter(javafx.scene.control.DialogPane.class::isInstance)
        .map(javafx.scene.control.DialogPane.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void cancellingWhileAuthoritativeQuoteIsInFlightAddsNothing() throws Exception {
    var delegate = new PreviewGateway();
    var catalog =
        JsonFiles.JSON.treeToValue(
            delegate.catalogue("items", "GET", null, "preview"), ItemCustomization.Catalog.class);
    var product =
        catalog.products().values().stream()
            .filter(
                p ->
                    p.available()
                        && !ItemCustomization.effective(catalog, p).groups().isEmpty()
                        && p.combo() == null)
            .findFirst()
            .orElseThrow();
    var requested = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var gateway =
        new ModuleGateway(java.nio.file.Path.of("unused-cancel-test")) {
          @Override
          public com.fasterxml.jackson.databind.JsonNode catalogue(
              String path, String method, Object body, String token) throws Exception {
            if (path.equals("items/quote")) {
              requested.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Quote test release timed out");
            }
            return delegate.catalogue(path, method, body, token);
          }
        };
    var button =
        ScreenDocuments.widget(
            SalesScreens.Type.PRODUCT_BUTTON,
            "Configure",
            new SalesScreens.Rect(20, 20, 180, 70, 1),
            java.util.Map.of("priceId", product.priceId().toString()));
    var order =
        ScreenDocuments.widget(
            SalesScreens.Type.ORDER_LIST,
            "Order",
            new SalesScreens.Rect(220, 20, 900, 600, 1),
            java.util.Map.of());
    var doc =
        new SalesScreens.Document(
            1,
            UUID.randomUUID(),
            PreviewGateway.SCREEN_STORE,
            new SalesScreens.Canvas(1200, 720, 8),
            java.util.List.of(
                new SalesScreens.Page(
                    UUID.randomUUID(),
                    "Sales",
                    SalesScreens.Purpose.SALES,
                    java.util.List.of(button, order))),
            java.util.List.of(),
            java.util.List.of());
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    final SalesRuntime[] runtime = new SalesRuntime[1];
    final Stage[] stage = new Stage[1];
    try {
      fx(
          () -> {
            runtime[0] = new SalesRuntime(gateway, executor, "preview", doc, catalog);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(runtime[0], 1200, 780));
            stage[0].show();
            ((Button) runtime[0].lookup("#sales-widget-" + button.id())).fire();
            var pane = dialog();
            var ok =
                pane.getButtonTypes().stream()
                    .filter(
                        t -> t.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            ((Button) pane.lookupButton(ok)).fire();
            return null;
          });
      assertTrue(requested.await(10, TimeUnit.SECONDS));
      fx(
          () -> {
            ((Button) dialog().lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
            return null;
          });
      release.countDown();
      await(
          () ->
              runtime[0].lookupAll(".label").stream()
                  .filter(javafx.scene.control.Label.class::isInstance)
                  .map(n -> ((javafx.scene.control.Label) n).getText())
                  .anyMatch(t -> t.startsWith("Selection cancelled")));
      assertEquals(
          0, fx(() -> ((TableView<?>) runtime[0].lookup(".table-view")).getItems().size()));
    } finally {
      release.countDown();
      fx(
          () -> {
            if (runtime[0] != null) runtime[0].close();
            if (stage[0] != null) stage[0].close();
            return null;
          });
      executor.shutdownNow();
    }
  }
}
