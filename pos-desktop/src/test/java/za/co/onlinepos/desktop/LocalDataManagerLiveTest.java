package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.DevData;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DEV_CONNECTION", matches = ".+")
class LocalDataManagerLiveTest {
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
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (fx(test)) return;
      Thread.sleep(20);
    }
    fail("Live H2 JavaFX operation timed out");
  }

  private static Button button(LocalDataManager panel, String id) {
    return (Button) panel.lookup("#category-" + id);
  }

  private static TableView<?> table(LocalDataManager panel) {
    return (TableView<?>) panel.lookup("#data-categories");
  }

  @Test
  void realBackendWorkflowAndDevelopmentNavigation() throws Exception {
    var connection =
        JsonFiles.JSON.readTree(
            Files.readString(Path.of(System.getenv("POS_TEST_DEV_CONNECTION"))));
    var gateway =
        new DevGateway(
            connection.path("token").asText(),
            connection.path("instanceId").asText(),
            connection.path("port").asInt());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var home =
          fx(
              () ->
                  new ModuleHome(
                      new Installation(
                          1,
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          1,
                          InstallationRole.MASTER,
                          "H2 integration test",
                          "INSTALLED"),
                      gateway,
                      executor));
      var stage =
          fx(
              () -> {
                var window = new Stage();
                var scene = new Scene(home, 1280, 850);
                scene
                    .getStylesheets()
                    .add(MenuPreview.class.getResource("setup.css").toExternalForm());
                window.setScene(scene);
                window.show();
                return window;
              });
      try {
        fx(
            () -> {
              var bar = (MenuBar) home.lookup(".menu-bar");
              bar.getMenus().getFirst().getItems().stream()
                  .filter(item -> "settings-store-management".equals(item.getId()))
                  .findFirst()
                  .orElseThrow()
                  .fire();
              return null;
            });
        await(() -> home.lookup("#business-name") != null);
        fx(
            () -> {
              ((TextField) home.lookup("#business-name")).setText("UI business");
              ((TextField) home.lookup("#store-name")).setText("Owner store");
              ((TextField) home.lookup("#store-code")).setText("OWNER");
              ((Button) home.lookup("#store-save")).fire();
              return null;
            });
        await(
            () -> ((Label) home.lookup("#store-feedback")).getText().contains("Local store saved"));
        fx(
            () -> {
              ((Button) home.lookup("#home-lock")).fire();
              return null;
            });
        fx(
            () -> {
              var bar = (MenuBar) home.lookup(".menu-bar");
              ((javafx.scene.control.Menu)
                      bar.getMenus().getFirst().getItems().stream()
                          .filter(item -> item instanceof javafx.scene.control.Menu)
                          .findFirst()
                          .orElseThrow())
                  .getItems()
                  .getFirst()
                  .fire();
              return null;
            });
        var panel = fx(() -> (LocalDataManager) home.getCenter());
        await(() -> !button(panel, "add").isDisabled());
        fx(
            () -> {
              var search = (TextField) panel.lookup("#data-search");
              search.setText("H2 UI");
              search.fireEvent(new javafx.event.ActionEvent());
              return null;
            });
        await(() -> !button(panel, "add").isDisabled());
        fx(
            () -> {
              button(panel, "add").fire();
              ((TextField) panel.lookup("#category-name")).setText("H2 UI created");
              button(panel, "save").fire();
              return null;
            });
        await(() -> !button(panel, "add").isDisabled() && table(panel).getItems().size() == 1);
        fx(
            () -> {
              table(panel).getSelectionModel().selectFirst();
              button(panel, "edit").fire();
              ((TextField) panel.lookup("#category-name")).setText("H2 UI persisted");
              ((TextField) panel.lookup("#category-sort-order")).setText("7");
              button(panel, "save").fire();
              return null;
            });
        await(
            () ->
                !button(panel, "add").isDisabled()
                    && ((DevData.Category) table(panel).getItems().getFirst())
                        .name()
                        .equals("H2 UI persisted"));
        fx(
            () -> {
              table(panel).getSelectionModel().selectFirst();
              Platform.runLater(() -> button(panel, "delete").fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var dialog =
                  Window.getWindows().stream()
                      .filter(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane)
                      .findFirst()
                      .orElseThrow();
              var pane = (DialogPane) dialog.getScene().getRoot();
              ((Button) pane.lookupButton(ButtonType.YES)).fire();
              return null;
            });
        await(() -> !button(panel, "add").isDisabled() && table(panel).getItems().isEmpty());
        fx(
            () -> {
              ComboBox<?> states = (ComboBox<?>) panel.lookup("#data-state");
              states.getSelectionModel().select(1);
              return null;
            });
        await(() -> !button(panel, "add").isDisabled() && table(panel).getItems().size() == 1);
        assertFalse(fx(() -> ((DevData.Category) table(panel).getItems().getFirst()).active()));
        fx(
            () -> {
              table(panel).getSelectionModel().selectFirst();
              button(panel, "edit").fire();
              ((CheckBox) panel.lookup("#category-active")).setSelected(true);
              button(panel, "save").fire();
              return null;
            });
        await(() -> !button(panel, "add").isDisabled() && table(panel).getItems().isEmpty());
        // Close the screen and open a fresh view; it must query the actual backend.
        fx(
            () -> {
              panel.close();
              stage.close();
              home.close();
              return null;
            });
        var reopened =
            fx(
                () -> {
                  var view = new LocalDataManager(gateway, executor, () -> {});
                  var scene = new Scene(view, 1280, 850);
                  scene
                      .getStylesheets()
                      .add(MenuPreview.class.getResource("setup.css").toExternalForm());
                  stage.setScene(scene);
                  stage.show();
                  return view;
                });
        try {
          await(
              () -> !button(reopened, "add").isDisabled() && !table(reopened).getItems().isEmpty());
          fx(
              () -> {
                var search = (TextField) reopened.lookup("#data-search");
                search.setText("H2 UI");
                search.fireEvent(new javafx.event.ActionEvent());
                return null;
              });
          await(
              () ->
                  !button(reopened, "add").isDisabled() && table(reopened).getItems().size() == 1);
          assertEquals(
              "H2 UI persisted",
              fx(() -> ((DevData.Category) table(reopened).getItems().getFirst()).name()));
          assertEquals(
              7, fx(() -> ((DevData.Category) table(reopened).getItems().getFirst()).sortOrder()));
          fx(
              () -> {
                table(reopened).getSelectionModel().selectFirst();
                button(reopened, "edit").fire();
                return null;
              });
          fx(
              () -> {
                var image = reopened.snapshot(null, null);
                var buffered =
                    new java.awt.image.BufferedImage(
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < buffered.getHeight(); y++)
                  for (int x = 0; x < buffered.getWidth(); x++)
                    buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(
                    buffered, "png", Path.of("target/h2-real-data-manager.png").toFile());
                return null;
              });
        } finally {
          fx(
              () -> {
                reopened.close();
                return null;
              });
        }
      } finally {
        fx(
            () -> {
              home.close();
              stage.close();
              return null;
            });
      }
    }
  }
}
