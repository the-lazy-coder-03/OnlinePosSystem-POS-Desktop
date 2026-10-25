package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class CataloguePanelTest {
  @BeforeAll
  static void startup() throws Exception {
    FxTestSupport.start();
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Exception | AssertionError e) {
            result.completeExceptionally(e);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> predicate) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (fx(predicate)) return;
      Thread.sleep(20);
    }
    fail("Catalogue GUI transition timed out");
  }

  private static final class Gateway extends ModuleGateway {
    final ObjectNode state;
    final java.util.List<CatalogueManagement.Edit> saves =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean failSave;

    Gateway(boolean editable) throws Exception {
      super(Path.of("unused-catalogue-test"));
      var schema =
          JsonFiles.JSON.readTree(
              Files.readString(
                  Path.of("../store-server/src/main/resources/catalogue-schema-v1.json")));
      var tables =
          JsonFiles.JSON.readTree(
              Files.readString(
                  Path.of(
                      "../integration-tests/src/test/resources/catalogue-source-fixture.json")));
      state = JsonFiles.JSON.createObjectNode();
      state.put("revision", 0).put("branchId", 1).put("editable", editable);
      state.set("schema", schema);
      state.set("tables", tables);
      state.putArray("warnings");
    }

    @Override
    public void check() {}

    @Override
    public JsonNode enter(za.co.onlinepos.contracts.PosModule module, char[] pin) {
      java.util.Arrays.fill(pin, '\0');
      return JsonFiles.JSON.valueToTree(
          java.util.Map.of("token", "catalogue-test-session", "username", "Test staff"));
    }

    @Override
    public JsonNode request(String path, String method, byte[] body, String token) {
      return JsonFiles.JSON.createObjectNode();
    }

    @Override
    public synchronized JsonNode catalogue(String path, String method, Object payload, String token)
        throws Exception {
      if (method.equals("GET")) return state.deepCopy();
      var edit = (CatalogueManagement.Edit) payload;
      saves.add(edit);
      if (failSave) throw new java.io.IOException("Synthetic unavailable store");
      state.put("revision", state.path("revision").asLong() + 1);
      return JsonFiles.JSON.valueToTree(
          java.util.Map.of("revision", state.path("revision").asLong()));
    }
  }

  private record View(CataloguePanel panel, Stage stage, ExecutorService executor)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      fx(
          () -> {
            panel.close();
            stage.close();
            return null;
          });
      executor.close();
    }
  }

  private View view(Gateway gateway, AtomicReference<String> locked) throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    return fx(
        () -> {
          var panel =
              new CataloguePanel(
                  gateway,
                  executor,
                  "catalogue-test-session",
                  "Kenridge · Terminal 1",
                  "Test staff",
                  locked::set,
                  () -> {});
          var stage = new Stage();
          var scene = new Scene(panel, 1250, 850);
          scene
              .getStylesheets()
              .add(
                  java.util.Objects.requireNonNull(CataloguePanel.class.getResource("setup.css"))
                      .toExternalForm());
          stage.setScene(scene);
          stage.show();
          return new View(panel, stage, executor);
        });
  }

  @SuppressWarnings("unchecked")
  private void select(View view) throws Exception {
    await(() -> view.panel.lookup("#catalogue-list") != null);
    fx(
        () -> {
          ((TableView<JsonNode>) view.panel.lookup("#catalogue-list"))
              .getSelectionModel()
              .selectFirst();
          view.panel.applyCss();
          view.panel.layout();
          return null;
        });
  }

  private void choose(View view, String text) throws Exception {
    fx(
        () -> {
          var combo = (ComboBox<?>) view.panel.lookup("#catalogue-kind");
          for (int i = 0; i < combo.getItems().size(); i++)
            if (combo.getItems().get(i).toString().equals(text)) {
              combo.getSelectionModel().select(i);
              return null;
            }
          throw new IllegalStateException("Missing catalogue kind: " + text);
        });
  }

  private void answer(ButtonType type) {
    Platform.runLater(
        () -> {
          var pane =
              Window.getWindows().stream()
                  .filter(Window::isShowing)
                  .map(w -> w.getScene().getRoot())
                  .filter(DialogPane.class::isInstance)
                  .map(DialogPane.class::cast)
                  .findFirst()
                  .orElseThrow();
          ((Button) pane.lookupButton(type)).fire();
        });
  }

  private void snapshot(View view, String name) throws Exception {
    var pulse = new CompletableFuture<Void>();
    fx(
        () -> {
          Runnable[] listener = new Runnable[1];
          listener[0] =
              () -> {
                view.stage.getScene().removePostLayoutPulseListener(listener[0]);
                pulse.complete(null);
              };
          view.stage.getScene().addPostLayoutPulseListener(listener[0]);
          Platform.requestNextPulse();
          return null;
        });
    pulse.get(15, TimeUnit.SECONDS);
    fx(
        () -> {
          view.panel.applyCss();
          view.panel.layout();
          var parameters = new javafx.scene.SnapshotParameters();
          parameters.setViewport(
              new javafx.geometry.Rectangle2D(0, 0, view.panel.getWidth(), view.panel.getHeight()));
          var source = view.panel.snapshot(parameters, null);
          var bitmap =
              new java.awt.image.BufferedImage(
                  (int) source.getWidth(),
                  (int) source.getHeight(),
                  java.awt.image.BufferedImage.TYPE_INT_ARGB);
          for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
              bitmap.setRGB(x, y, source.getPixelReader().getArgb(x, y));
          Files.createDirectories(Path.of("target"));
          javax.imageio.ImageIO.write(bitmap, "png", Path.of("target", name + ".png").toFile());
          return null;
        });
  }

  @Test
  void nativeFormsSidebarDirtyWarningsAndSharedRecipeConfirmation() throws Exception {
    var gateway = new Gateway(true);
    try (var view = view(gateway, new AtomicReference<>())) {
      select(view);
      snapshot(view, "catalogue-items");
      fx(
          () -> {
            assertTrue(((ToggleButton) view.panel.lookup("#catalogue-items")).isSelected());
            ((TextField) view.panel.lookup("#catalogue-field-name")).setText("Changed item");
            answer(ButtonType.CANCEL);
            ((ToggleButton) view.panel.lookup("#catalogue-ingredients")).fire();
            assertNotNull(view.panel.lookup("#catalogue-field-name"));
            assertTrue(((ToggleButton) view.panel.lookup("#catalogue-items")).isSelected());
            answer(ButtonType.OK);
            ((ToggleButton) view.panel.lookup("#catalogue-ingredients")).fire();
            return null;
          });
      select(view);
      fx(
          () -> {
            assertNotNull(view.panel.lookup("#catalogue-field-seasonal"));
            ((ToggleButton) view.panel.lookup("#catalogue-items")).fire();
            return null;
          });
      choose(view, "Shared recipe components");
      select(view);
      fx(
          () -> {
            assertTrue(
                ((Label) view.panel.lookup("#catalogue-affected"))
                    .getText()
                    .contains("menu_item name"));
            answer(ButtonType.CANCEL);
            ((Button) view.panel.lookup("#catalogue-save")).fire();
            assertTrue(gateway.saves.isEmpty());
            answer(ButtonType.OK);
            ((Button) view.panel.lookup("#catalogue-save")).fire();
            return null;
          });
      await(
          () ->
              !gateway.saves.isEmpty()
                  && !view.panel.lookup("#catalogue-kind").isDisabled()
                  && view.panel.lookup("#catalogue-feedback") instanceof Label label
                  && label.getText().startsWith("Saved"));
      assertTrue(gateway.saves.getFirst().confirmSharedRecipe());
      fx(
          () -> {
            ((ToggleButton) view.panel.lookup("#catalogue-categories")).fire();
            return null;
          });
      choose(view, "Modifier groups");
      select(view);
      snapshot(view, "catalogue-options");
      fx(
          () -> {
            assertNotNull(view.panel.lookup("#catalogue-field-min_select"));
            return null;
          });
    }
  }

  @Test
  void failedSaveRetriesSameCommandAndLockClearsForms() throws Exception {
    var gateway = new Gateway(true);
    gateway.failSave = true;
    var locked = new AtomicReference<String>();
    try (var view = view(gateway, locked)) {
      select(view);
      fx(
          () -> {
            ((TextField) view.panel.lookup("#catalogue-field-name")).setText("Unconfirmed edit");
            ((javafx.scene.control.TextArea) view.panel.lookup("#catalogue-field-description"))
                .setText("First line\nSecond line");
            ((Button) view.panel.lookup("#catalogue-save")).fire();
            return null;
          });
      await(
          () ->
              ((Label) view.panel.lookup("#catalogue-feedback")).getText().contains("unconfirmed"));
      fx(
          () -> {
            ((Button) view.panel.lookup("#catalogue-save")).fire();
            return null;
          });
      await(
          () ->
              gateway.saves.size() == 2
                  && ((Label) view.panel.lookup("#catalogue-feedback"))
                      .getText()
                      .contains("unconfirmed"));
      assertEquals(gateway.saves.get(0), gateway.saves.get(1));
      assertEquals(
          "First line\nSecond line",
          gateway.saves.getFirst().changes().getFirst().values().get("description"));
      var field = fx(() -> (TextField) view.panel.lookup("#catalogue-field-name"));
      fx(
          () -> {
            ((Button) view.panel.lookup("#home-lock")).fire();
            view.panel.close();
            return null;
          });
      assertEquals("", locked.get());
      assertEquals("", fx(field::getText));
    }
  }

  @Test
  void readOnlyBrowsingAndCompactLayout() throws Exception {
    var gateway = new Gateway(false);
    try (var view = view(gateway, new AtomicReference<>())) {
      select(view);
      fx(
          () -> {
            assertNull(view.panel.lookup("#catalogue-add"));
            assertNull(view.panel.lookup("#catalogue-save"));
            assertTrue(view.panel.lookup("#catalogue-field-name") instanceof Label);
            view.stage.setWidth(920);
            view.panel.applyCss();
            view.panel.layout();
            assertTrue(
                view.panel
                        .lookup("#catalogue-list")
                        .localToScene(view.panel.lookup("#catalogue-list").getBoundsInLocal())
                        .getMaxX()
                    < view.panel.getWidth());
            return null;
          });
    }
  }

  @Test
  void homeOpensCatalogueAndIdleLockClearsItsForms() throws Exception {
    var gateway = new Gateway(true);
    var time = new java.util.concurrent.atomic.AtomicLong(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var installation =
          new za.co.onlinepos.installation.Installation(
              1,
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              1,
              za.co.onlinepos.contracts.InstallationRole.MASTER,
              "Test terminal",
              "INSTALLED");
      var home = fx(() -> new ModuleHome(installation, gateway, executor, time::get));
      var stage =
          fx(
              () -> {
                var window = new Stage();
                window.setScene(new Scene(home, 1250, 850));
                window.show();
                return window;
              });
      try {
        fx(
            () -> {
              ((Button) home.lookup("#module-CATALOGUE")).fire();
              var pane =
                  Window.getWindows().stream()
                      .filter(Window::isShowing)
                      .map(w -> w.getScene().getRoot())
                      .filter(DialogPane.class::isInstance)
                      .map(DialogPane.class::cast)
                      .findFirst()
                      .orElseThrow();
              ((javafx.scene.control.PasswordField) pane.lookup("#staff-pin")).setText("012345");
              var open =
                  pane.getButtonTypes().stream()
                      .filter(
                          b ->
                              b.getButtonData()
                                  == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                      .findFirst()
                      .orElseThrow();
              ((Button) pane.lookupButton(open)).fire();
              return null;
            });
        await(() -> home.lookup("#catalogue-list") != null);
        var field =
            fx(
                () -> {
                  ((TableView<?>) home.lookup("#catalogue-list")).getSelectionModel().selectFirst();
                  home.applyCss();
                  home.layout();
                  var input = (TextField) home.lookup("#catalogue-field-name");
                  input.setText("Unsaved item");
                  return input;
                });
        fx(
            () -> {
              time.addAndGet(java.time.Duration.ofMinutes(5).toNanos());
              home.tick();
              assertNull(home.lookup("#catalogue-panel"));
              assertNotNull(home.lookup("#module-CATALOGUE"));
              assertEquals("", field.getText());
              return null;
            });
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
