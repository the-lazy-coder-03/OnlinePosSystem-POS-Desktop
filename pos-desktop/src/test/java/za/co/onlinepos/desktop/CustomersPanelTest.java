package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class CustomersPanelTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  private static <T> T fx(Callable<T> task) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(task.call());
          } catch (Exception | AssertionError e) {
            result.completeExceptionally(e);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (fx(condition)) return;
      Thread.sleep(20);
    }
    fail("Customers transition timed out");
  }

  private static DialogPane dialog() {
    return Window.getWindows().stream()
        .filter(Window::isShowing)
        .map(w -> w.getScene().getRoot())
        .filter(DialogPane.class::isInstance)
        .map(DialogPane.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static class Gateway extends ModuleGateway {
    final PreviewCustomers preview = new PreviewCustomers();
    final java.util.List<Object> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean unavailable;

    Gateway() {
      super(Path.of("unused-customers-test"));
    }

    @Override
    public JsonNode customers(String path, String method, Object body, String token)
        throws Exception {
      assertFalse(Platform.isFxApplicationThread(), "Network calls must leave JavaFX thread");
      if (method.equals("PUT")) {
        requests.add(body);
        if (unavailable) throw new java.io.IOException("Synthetic disconnected Master");
      }
      return preview.request(path, method, body);
    }
  }

  @Test
  void profileValidationHiddenMetadataRetryAndSearchWorkWithoutFalseSuccess() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var panel = fx(() -> new CustomersPanel(gateway, executor, "test", () -> {}, () -> {}));
      var stage =
          fx(
              () -> {
                var s = new Stage();
                var scene = new Scene(panel, 1200, 850);
                scene
                    .getStylesheets()
                    .add(CustomersPanel.class.getResource("setup.css").toExternalForm());
                s.setScene(scene);
                s.show();
                return s;
              });
      try {
        await(
            () ->
                panel.lookup("#customers") != null
                    && ((TableView<?>) panel.lookup("#customers")).getItems().size() == 3);
        fx(
            () -> {
              panel.applyCss();
              panel.layout();
              var image = panel.snapshot(null, null);
              var bitmap =
                  new java.awt.image.BufferedImage(
                      (int) image.getWidth(),
                      (int) image.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_ARGB);
              for (int y = 0; y < bitmap.getHeight(); y++)
                for (int x = 0; x < bitmap.getWidth(); x++)
                  bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
              javax.imageio.ImageIO.write(
                  bitmap, "png", Path.of("target", "customers-persistent-preview.png").toFile());
              return null;
            });
        fx(
            () -> {
              assertTrue(
                  ((javafx.scene.control.TabPane) panel.getCenter())
                      .getTabs().stream()
                          .anyMatch(t -> "customer-conflicts-tab".equals(t.getId())));
              Platform.runLater(() -> ((Button) panel.lookup("#customer-add")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var d = dialog();
              assertNull(d.lookup("#customer-field-country"));
              assertNull(d.lookup("#customer-field-province"));
              assertNull(d.lookup("#customer-field-latitude"));
              assertNull(d.lookup("#customer-field-longitude"));
              assertFalse(((TextField) d.lookup("#customer-field-preferred_store")).isEditable());
              ((Button) d.lookupButton(ButtonType.OK)).fire();
              assertFalse(((Label) d.lookup("#customer-form-error")).getText().isBlank());
              ((TextField) d.lookup("#customer-field-first_name")).setText("New profile");
              ((TextField) d.lookup("#customer-field-phone1")).setText("0821234567");
              gateway.unavailable = true;
              ((Button) d.lookupButton(ButtonType.OK)).fire();
              return null;
            });
        await(
            () ->
                ((Label) dialog().lookup("#customer-form-error"))
                    .getText()
                    .contains("Save not confirmed"));
        fx(
            () -> {
              assertEquals(
                  "New profile",
                  ((TextField) dialog().lookup("#customer-field-first_name")).getText());
              gateway.unavailable = false;
              ((Button) dialog().lookupButton(ButtonType.OK)).fire();
              return null;
            });
        await(() -> ((TableView<?>) panel.lookup("#customers")).getItems().size() == 4);
        assertEquals(2, gateway.requests.size());
        assertEquals(gateway.requests.get(0), gateway.requests.get(1));
        fx(
            () -> {
              ((TextField) panel.lookup("#search")).setText("No such fictional customer");
              return null;
            });
        await(() -> ((TableView<?>) panel.lookup("#customers")).getItems().isEmpty());
        fx(
            () -> {
              stage.setWidth(850);
              stage.setHeight(650);
              panel.applyCss();
              panel.layout();
              return null;
            });
      } finally {
        fx(
            () -> {
              panel.close();
              stage.close();
              return null;
            });
      }
    }
  }

  @Test
  void lifecycleControlsRequireReasonAndShowReadOnlyHistory() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var panel = fx(() -> new CustomersPanel(gateway, executor, "test", () -> {}, () -> {}));
      var stage =
          fx(
              () -> {
                var window = new Stage();
                window.setScene(new Scene(panel, 1200, 850));
                window.show();
                return window;
              });
      try {
        await(
            () ->
                panel.lookup("#customers") != null
                    && ((TableView<?>) panel.lookup("#customers")).getItems().size() == 3);
        fx(
            () -> {
              ((TableView<?>) panel.lookup("#customers")).getSelectionModel().selectFirst();
              Platform.runLater(() -> ((Button) panel.lookup("#customer-ban")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var d = dialog();
              assertTrue(d.lookupButton(ButtonType.OK).isDisabled());
              ((javafx.scene.control.TextArea) d.lookup("#customer-ban-reason"))
                  .setText("Preview reason");
              ((Button) d.lookupButton(ButtonType.OK)).fire();
              return null;
            });
        await(() -> ((Button) panel.lookup("#customer-ban")).getText().equals("Unban"));
        fx(
            () -> {
              assertTrue(((Label) panel.lookup("#customer-details")).getText().contains("BANNED"));
              ((Button) panel.lookup("#customer-history")).fire();
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var d = dialog();
              assertTrue(d.lookup("#customer-linked-drafts") instanceof TableView<?>);
              assertTrue(d.lookup("#customer-status-history") instanceof TableView<?>);
              ((Button) d.lookupButton(ButtonType.CLOSE)).fire();
              Platform.runLater(() -> ((Button) panel.lookup("#customer-archive")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              ((Button) dialog().lookupButton(ButtonType.YES)).fire();
              return null;
            });
        await(() -> ((Button) panel.lookup("#customer-archive")).getText().equals("Restore"));
        fx(
            () -> {
              ((javafx.scene.control.CheckBox) panel.lookup("#customer-archived")).fire();
              return null;
            });
        await(() -> ((TableView<?>) panel.lookup("#customers")).getItems().size() == 1);
        fx(
            () -> {
              ((TableView<?>) panel.lookup("#customers")).getSelectionModel().selectFirst();
              assertEquals("Unban", ((Button) panel.lookup("#customer-ban")).getText());
              return null;
            });
      } finally {
        fx(
            () -> {
              panel.close();
              stage.close();
              return null;
            });
      }
    }
  }
}
