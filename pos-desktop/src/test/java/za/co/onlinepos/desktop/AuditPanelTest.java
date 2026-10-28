package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.Auditing;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class AuditPanelTest {
  @BeforeAll
  static void startup() throws Exception {
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

  private static void await(Callable<Boolean> task) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (fx(task)) return;
      Thread.sleep(20);
    }
    fail("Audit UI timed out");
  }

  static final class Gateway extends ModuleGateway {
    final Map<String, Boolean> settings = new LinkedHashMap<>();
    final List<String> paths = new ArrayList<>();
    volatile int status;
    volatile int saves;
    long revision;
    Auditing.SettingsEdit last;

    Gateway() {
      super(Path.of("unused-audit-test"));
      for (var t : Auditing.EventType.values()) if (t.optional()) settings.put(t.name(), true);
    }

    @Override
    public synchronized JsonNode admin(String path, String method, Object payload, String token)
        throws Exception {
      paths.add(path);
      if (status != 0) throw new TrustedStoreClient.ModuleFailure(status);
      if (path.equals("audit/types"))
        return JsonFiles.JSON.valueToTree(
            java.util.Arrays.stream(Auditing.EventType.values())
                .map(t -> Map.of("action", t.name(), "optional", t.optional()))
                .toList());
      if (path.equals("audit/settings")) {
        if (method.equals("PUT")) {
          last = (Auditing.SettingsEdit) payload;
          if (!last.ownerPin().equals("012345")) throw new TrustedStoreClient.ModuleFailure(401);
          settings.putAll(last.settings());
          revision++;
          saves++;
        }
        return JsonFiles.JSON.valueToTree(
            Map.of(
                "branchId",
                1,
                "revision",
                revision,
                "settings",
                settings,
                "approvedByName",
                "Owner",
                "updatedAt",
                "2026-10-25T18:00:00Z"));
      }
      if (path.startsWith("audit/events/"))
        return JsonFiles.JSON.valueToTree(
            Map.of("action", "DRAFT_CANCELLED", "details", Map.of("reason", "Example")));
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "branchId",
              1,
              "items",
              List.of(
                  Map.of(
                      "eventId",
                      "18ddca74-9fe5-4b3c-aee8-f397230b760d",
                      "occurredAt",
                      "2026-10-25T18:00:00Z",
                      "action",
                      "DRAFT_CANCELLED",
                      "staff",
                      "Cashier",
                      "terminal",
                      "POS02")),
              "hasMore",
              !path.contains("cursor=next"),
              "nextCursor",
              "next"));
    }
  }

  private static Button button(AuditPanel panel, String text) {
    return (Button)
        panel.lookupAll(".button").stream()
            .filter(n -> ((Button) n).getText().equals(text))
            .findFirst()
            .orElseThrow();
  }

  private static DialogPane dialog() {
    return (DialogPane)
        Window.getWindows().stream()
            .filter(Window::isShowing)
            .map(w -> w.getScene().getRoot())
            .filter(DialogPane.class::isInstance)
            .findFirst()
            .orElseThrow();
  }

  private static void approve(AuditPanel panel, String pin, boolean cancel) throws Exception {
    Platform.runLater(() -> button(panel, "Save").fire());
    await(
        () ->
            Window.getWindows().stream()
                .filter(Window::isShowing)
                .anyMatch(w -> w.getScene().getRoot() instanceof DialogPane));
    var cleared =
        fx(
            () -> {
              var popup = dialog();
              var input = (PasswordField) popup.lookup("#audit-owner-pin");
              input.setText(pin);
              var type =
                  popup.getButtonTypes().stream()
                      .filter(
                          t ->
                              t.getButtonData()
                                  == (cancel
                                      ? ButtonBar.ButtonData.CANCEL_CLOSE
                                      : ButtonBar.ButtonData.OK_DONE))
                      .findFirst()
                      .orElseThrow();
              ((Button) popup.lookupButton(type)).fire();
              return input;
            });
    assertEquals("", fx(cleared::getText));
  }

  private static void snapshot(AuditPanel panel, String name) throws Exception {
    fx(
        () -> {
          panel.applyCss();
          panel.layout();
          var image = panel.snapshot(new javafx.scene.SnapshotParameters(), null);
          var out =
              new java.awt.image.BufferedImage(
                  (int) image.getWidth(),
                  (int) image.getHeight(),
                  java.awt.image.BufferedImage.TYPE_INT_ARGB);
          for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
              out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
          javax.imageio.ImageIO.write(out, "png", Path.of("target", name + ".png").toFile());
          return null;
        });
  }

  @Test
  void settingsStagingOwnerPinCancelSaveAndMandatoryLocks() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var gateway = new Gateway();
      var panel = fx(() -> new AuditPanel(gateway, executor, "session", true, d -> {}, () -> {}));
      var stage =
          fx(
              () -> {
                var s = new Stage();
                s.setScene(new Scene(panel, 1000, 750));
                s.getScene()
                    .getStylesheets()
                    .add(AuditPanel.class.getResource("setup.css").toExternalForm());
                s.show();
                return s;
              });
      try {
        await(() -> panel.lookup("#track-DRAFT_CANCELLED") != null);
        fx(
            () -> {
              assertTrue(((CheckBox) panel.lookup("#track-AUDIT_SETTING_CHANGED")).isDisabled());
              button(panel, "Disable Optional Tracking").fire();
              assertFalse(((Button) panel.lookup("#audit-settings-save")).isDisabled());
              return null;
            });
        snapshot(panel, "audit-tracking-settings");
        assertEquals(0, gateway.saves);
        approve(panel, "", true);
        assertEquals(0, gateway.saves);
        approve(panel, "999999", false);
        await(() -> ((Label) panel.lookup("#audit-feedback")).getText().contains("refused"));
        assertEquals(0, gateway.saves);
        approve(panel, "012345", false);
        await(
            () ->
                gateway.saves == 1
                    && ((Label) panel.lookup("#audit-feedback")).getText().contains("saved"));
        assertTrue(gateway.last.settings().values().stream().noneMatch(Boolean::booleanValue));
        fx(
            () -> {
              assertTrue(((Button) panel.lookup("#audit-settings-save")).isDisabled());
              button(panel, "Enable All").fire();
              return null;
            });
        gateway.status = 409;
        approve(panel, "012345", false);
        await(() -> ((Label) panel.lookup("#audit-feedback")).getText().contains("Reload"));
        fx(
            () -> {
              assertTrue(((Button) panel.lookup("#audit-settings-save")).isDisabled());
              return null;
            });
        gateway.status = 0;
        fx(
            () -> {
              button(panel, "Reload").fire();
              return null;
            });
        await(
            () -> {
              panel.applyCss();
              panel.layout();
              var toggle = (CheckBox) panel.lookup("#track-DRAFT_CANCELLED");
              return toggle != null && !toggle.isSelected();
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
  void historyPaginationFiltersReadOnlyDetailsAndOfflineState() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var gateway = new Gateway();
      var panel = fx(() -> new AuditPanel(gateway, executor, "session", false, d -> {}, () -> {}));
      var stage =
          fx(
              () -> {
                var s = new Stage();
                s.setScene(new Scene(panel, 1000, 700));
                s.getScene()
                    .getStylesheets()
                    .add(AuditPanel.class.getResource("setup.css").toExternalForm());
                s.show();
                return s;
              });
      try {
        await(() -> panel.lookup("#admin-audit-table") != null);
        snapshot(panel, "audit-history");
        fx(
            () -> {
              ((Button) panel.lookup("#audit-next")).fire();
              return null;
            });
        await(() -> gateway.paths.stream().anyMatch(p -> p.contains("cursor=next")));
        await(() -> ((Button) panel.lookup("#audit-next")).isDisabled());
        fx(
            () -> {
              var table = (TableView<?>) panel.lookup("#admin-audit-table");
              table.getSelectionModel().select(0);
              ((Button) panel.lookup("#audit-details")).fire();
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .anyMatch(w -> w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var popup = dialog();
              assertFalse(
                  popup.lookupAll(".text-area").stream()
                      .map(javafx.scene.control.TextArea.class::cast)
                      .anyMatch(javafx.scene.control.TextArea::isEditable));
              ((Button) popup.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
              return null;
            });
        gateway.status = 503;
        fx(
            () -> {
              ((Button) panel.lookup("#audit-apply")).fire();
              return null;
            });
        await(() -> ((Label) panel.lookup("#audit-feedback")).getText().contains("unavailable"));
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
