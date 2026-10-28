package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
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
import za.co.onlinepos.contracts.DevData;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class LocalDataManagerTest {
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
          } catch (Exception | AssertionError failure) {
            result.completeExceptionally(failure);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> test) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (fx(test)) return;
      Thread.sleep(20);
    }
    fail("Local Data Manager transition timed out");
  }

  private static class Gateway extends DevGateway {
    volatile int failure;
    volatile DevData.Save last;
    volatile DevData.Category row;

    Gateway() {
      super("test-credential-with-at-least-32-characters", "test-instance", 18091);
    }

    @Override
    JsonNode status() {
      assertFalse(Platform.isFxApplicationThread());
      return JsonFiles.JSON.valueToTree(java.util.Map.of("database", "H2"));
    }

    @Override
    JsonNode call(String path, String method, Object value) throws Exception {
      assertFalse(Platform.isFxApplicationThread());
      if (!method.equals("GET")) {
        last = (DevData.Save) value;
        if (failure == 503) throw new java.io.IOException("unavailable");
        if (failure == 409)
          throw new Failure(
              409,
              "",
              "REVISION_CONFLICT",
              "This category changed. Refresh and review before saving.");
        row =
            new DevData.Category(
                1,
                last.name().strip(),
                last.sortOrder(),
                last.active(),
                last.expectedRevision() == null ? 0 : last.expectedRevision() + 1);
        return JsonFiles.JSON.valueToTree(row);
      }
      return JsonFiles.JSON.valueToTree(
          new DevData.Page(
              row == null ? java.util.List.of() : java.util.List.of(row),
              row == null ? 0 : 1,
              0,
              50));
    }
  }

  @Test
  void savesOffThreadPreservesEditsAndRetryIdentityThenRequiresReloadOnConflict() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stage = new Stage[1];
      var panel =
          fx(
              () -> {
                var view = new LocalDataManager(gateway, executor, () -> {});
                stage[0] = new Stage();
                var scene = new Scene(view, 1200, 800);
                scene
                    .getStylesheets()
                    .add(MenuPreview.class.getResource("setup.css").toExternalForm());
                stage[0].setScene(scene);
                stage[0].show();
                return view;
              });
      try {
        await(() -> !((Button) panel.lookup("#category-add")).isDisabled());
        fx(
            () -> {
              ((Button) panel.lookup("#category-add")).fire();
              ((TextField) panel.lookup("#category-name")).setText("Burgers");
              ((TextField) panel.lookup("#category-sort-order")).setText("not-number");
              ((Button) panel.lookup("#category-save")).fire();
              return null;
            });
        assertEquals(null, gateway.last);
        gateway.failure = 503;
        fx(
            () -> {
              ((TextField) panel.lookup("#category-sort-order")).setText("2");
              ((Button) panel.lookup("#category-save")).fire();
              return null;
            });
        await(() -> !((Button) panel.lookup("#category-save")).isDisabled());
        var retry = gateway.last.commandId();
        assertEquals("Burgers", fx(() -> ((TextField) panel.lookup("#category-name")).getText()));
        gateway.failure = 0;
        fx(
            () -> {
              ((Button) panel.lookup("#category-save")).fire();
              return null;
            });
        await(
            () ->
                !((Button) panel.lookup("#category-add")).isDisabled()
                    && ((TableView<?>) panel.lookup("#data-categories")).getItems().size() == 1);
        assertEquals(retry, gateway.last.commandId());
        gateway.failure = 409;
        fx(
            () -> {
              ((TextField) panel.lookup("#category-name")).setText("New name");
              ((Button) panel.lookup("#category-save")).fire();
              return null;
            });
        await(
            () ->
                panel.lookupAll(".label").stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> label.getText().contains("Refresh and review")));
        assertTrue(fx(() -> ((Button) panel.lookup("#category-save")).isDisabled()));
        assertEquals("New name", fx(() -> ((TextField) panel.lookup("#category-name")).getText()));
        fx(
            () -> {
              Platform.runLater(() -> ((Button) panel.lookup("#category-cancel")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var pane =
                  (DialogPane)
                      Window.getWindows().stream()
                          .filter(
                              w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane)
                          .findFirst()
                          .orElseThrow()
                          .getScene()
                          .getRoot();
              ((Button) pane.lookupButton(ButtonType.NO)).fire();
              return null;
            });
        assertEquals("New name", fx(() -> ((TextField) panel.lookup("#category-name")).getText()));
        fx(
            () -> {
              Platform.runLater(() -> ((Button) panel.lookup("#category-cancel")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var pane =
                  (DialogPane)
                      Window.getWindows().stream()
                          .filter(
                              w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane)
                          .findFirst()
                          .orElseThrow()
                          .getScene()
                          .getRoot();
              ((Button) pane.lookupButton(ButtonType.YES)).fire();
              return null;
            });
        assertTrue(fx(() -> ((TextField) panel.lookup("#category-name")).isDisabled()));

      } finally {
        fx(
            () -> {
              panel.close();
              stage[0].close();
              return null;
            });
      }
    }
  }
}
