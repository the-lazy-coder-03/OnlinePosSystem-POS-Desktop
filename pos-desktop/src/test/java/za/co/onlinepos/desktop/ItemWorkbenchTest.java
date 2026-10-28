package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class ItemWorkbenchTest {
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

  private static void await(Callable<Boolean> condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (fx(condition)) return;
      Thread.sleep(20);
    }
    fail("Item workbench transition timed out");
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

  private static final class Gateway extends ModuleGateway {
    final PreviewItems preview = new PreviewItems();
    final List<Object> saves = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean fail;

    Gateway() {
      super(Path.of("unused-item-test"));
    }

    @Override
    public JsonNode catalogue(String path, String method, Object payload, String token)
        throws Exception {
      assertFalse(Platform.isFxApplicationThread());
      if (method.equals("PUT")) {
        saves.add(payload);
        if (fail) throw new java.io.IOException("Synthetic disconnected Master");
      }
      if (path.equals("items/drafts"))
        return JsonFiles.JSON.valueToTree(java.util.Map.of("reference", "DRAFT-TEST"));
      return preview.request(path, method, payload);
    }
  }

  private Stage stage(ItemWorkbench panel) throws Exception {
    return fx(
        () -> {
          var stage = new Stage();
          var scene = new Scene(panel, 1200, 900);
          scene.getStylesheets().add(ItemWorkbench.class.getResource("setup.css").toExternalForm());
          stage.setScene(scene);
          stage.show();
          return stage;
        });
  }

  @Test
  void stagedRecipeEditsSurviveConnectionFailureAndRetryIdenticalCommand() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var panel = fx(() -> new ItemWorkbench(gateway, executor, "test"));
      var stage = stage(panel);
      try {
        await(() -> !((ComboBox<?>) panel.lookup("#item-product")).getItems().isEmpty());
        fx(
            () -> {
              var choices = (ComboBox<?>) panel.lookup("#item-product");
              for (int i = 0; i < choices.getItems().size(); i++)
                if (choices.getItems().get(i).toString().equals("Sample burger"))
                  choices.getSelectionModel().select(i);
              assertEquals(5, ((TabPane) panel.lookup("#item-tabs")).getTabs().size());
              Platform.runLater(
                  () -> {
                    panel.applyCss();
                    panel.layout();
                    panel.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getText().equals("Edit"))
                        .findFirst()
                        .orElseThrow()
                        .fire();
                  });
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              ((TextField) dialog().lookup("#item-extra-price")).setText("7.25");
              ((Button) dialog().lookupButton(ButtonType.OK)).fire();
              gateway.fail = true;
              ((Button) panel.lookup("#item-save")).fire();
              return null;
            });
        await(() -> ((Label) panel.lookup("#item-feedback")).getText().contains("not confirmed"));
        fx(
            () -> {
              assertTrue(panel.dirty());
              gateway.fail = false;
              ((Button) panel.lookup("#item-save")).fire();
              return null;
            });
        await(() -> !panel.dirty() && !((Button) panel.lookup("#item-reload")).isDisabled());
        assertEquals(2, gateway.saves.size());
        assertEquals(gateway.saves.get(0), gateway.saves.get(1));
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
  void builderRequiresConfirmedExactQuoteAndPreviewCannotSaveRealDrafts() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var panel = fx(() -> new ItemWorkbench(gateway, executor, "test"));
      var stage = stage(panel);
      try {
        await(() -> !((ComboBox<?>) panel.lookup("#item-product")).getItems().isEmpty());
        fx(
            () -> {
              var choices = (ComboBox<?>) panel.lookup("#item-product");
              for (int i = 0; i < choices.getItems().size(); i++)
                if (choices.getItems().get(i).toString().equals("Sample burger meal"))
                  choices.getSelectionModel().select(i);
              ((TabPane) panel.lookup("#item-tabs")).getSelectionModel().select(4);
              panel.applyCss();
              panel.layout();
              ((Button) panel.lookup("#item-preview")).fire();
              assertTrue(((TextArea) panel.lookup("#item-breakdown")).getText().contains("85.00"));
              ((Button) panel.lookup("#item-add-draft")).fire();
              assertTrue(
                  ((Label) panel.lookup("#item-feedback"))
                      .getText()
                      .contains("fresh Master quote"));
              ((Button) panel.lookup("#item-quote")).fire();
              return null;
            });
        await(() -> ((Label) panel.lookup("#item-feedback")).getText().contains("Quote confirmed"));
        fx(
            () -> {
              ((Button) panel.lookup("#item-add-draft")).fire();
              ((Spinner<?>) panel.lookup("#item-quantity")).increment();
              ((Button) panel.lookup("#item-add-draft")).fire();
              assertTrue(
                  ((Label) panel.lookup("#item-feedback"))
                      .getText()
                      .contains("fresh Master quote"));
              ((Button) panel.lookup("#item-save-draft")).fire();
              return null;
            });
        await(() -> ((Label) panel.lookup("#item-feedback")).getText().contains("DRAFT-TEST"));
        fx(
            () -> {
              panel.applyCss();
              panel.layout();
              panel
                  .lookupAll(".tab")
                  .forEach(
                      header ->
                          assertTrue(
                              header.getLayoutBounds().getWidth() >= 130,
                              "Tab headings must remain readable after product changes"));
              var image = panel.snapshot(null, null);
              var out =
                  new java.awt.image.BufferedImage(
                      (int) image.getWidth(),
                      (int) image.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_ARGB);
              for (int y = 0; y < out.getHeight(); y++)
                for (int x = 0; x < out.getWidth(); x++)
                  out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
              javax.imageio.ImageIO.write(
                  out, "png", Path.of("target", "item-workbench-preview.png").toFile());
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
      var preview = fx(() -> new ItemWorkbench(new PreviewGateway(), executor, "preview"));
      var previewStage = stage(preview);
      try {
        await(() -> !((ComboBox<?>) preview.lookup("#item-product")).getItems().isEmpty());
        fx(
            () -> {
              ((TabPane) preview.lookup("#item-tabs")).getSelectionModel().select(4);
              preview.applyCss();
              preview.layout();
              assertTrue(preview.lookup("#item-save-draft").isDisabled());
              return null;
            });
      } finally {
        fx(
            () -> {
              preview.close();
              previewStage.close();
              return null;
            });
      }
    }
  }

  @Test
  void salesUsesSharedBuilderWithoutConfigurationTabs() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var gateway = new Gateway();
      var panel = fx(() -> new ItemWorkbench(gateway, executor, "sales-test", true));
      var stage = stage(panel);
      try {
        await(
            () -> panel.lookup("#item-tabs") instanceof TabPane tabs && tabs.getTabs().size() == 1);
        fx(
            () -> {
              panel.applyCss();
              panel.layout();
              assertEquals(
                  "Order builder",
                  ((TabPane) panel.lookup("#item-tabs")).getTabs().getFirst().getText());
              assertTrue(panel.lookup("#sales-category") != null);
              assertEquals(null, panel.lookup("#item-save"));
              ((Button) panel.lookup("#item-quote")).fire();
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
