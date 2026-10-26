package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class DeveloperRecoveryUiTest {
  @BeforeAll
  static void start() throws Exception {
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

  private static void await(Callable<Boolean> check) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (fx(check)) return;
      Thread.sleep(20);
    }
    assertTrue(fx(check));
  }

  static class Gateway extends ModuleGateway {
    final boolean owner;

    Gateway(boolean owner) {
      super(Path.of("unused-recovery-test"));
      this.owner = owner;
    }

    @Override
    public boolean ownerRecoveryAvailable() {
      return owner;
    }

    @Override
    public void check() {}

    @Override
    public JsonNode admin(String path, String method, Object body, String token) {
      if (path.equals("overview"))
        return JsonFiles.JSON.valueToTree(
            Map.of("owner", owner, "permissions", java.util.List.of("module.administration")));
      if (path.equals("system-access/developer-recovery"))
        return JsonFiles.JSON.valueToTree(Map.of("configured", true, "enabled", true));
      throw new IllegalArgumentException("Unexpected test endpoint");
    }
  }

  @Test
  void ownerSeesDisclosedRecoveryStatusButOrdinaryStaffDoNot() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      for (boolean isOwner : new boolean[] {true, false}) {
        var panel =
            fx(
                () ->
                    new AdministrationPanel(
                        new Gateway(isOwner),
                        executor,
                        "session",
                        "Test Store",
                        "staff",
                        ignored -> {}));
        var stage =
            fx(
                () -> {
                  var s = new Stage();
                  s.setScene(new Scene(panel, 1000, 700));
                  s.show();
                  return s;
                });
        try {
          await(
              () ->
                  panel.lookup("#admin-tab-system-access") != null
                      || panel.lookup("#admin-tab-security-audit-tracking") != null);
          fx(
              () -> {
                if (isOwner) {
                  assertNotNull(panel.lookup("#admin-tab-system-access"));
                  ((ToggleButton) panel.lookup("#admin-tab-system-access")).fire();
                } else assertNull(panel.lookup("#admin-tab-system-access"));
                return null;
              });
          if (isOwner) {
            await(() -> panel.lookup("#developer-recovery-status") != null);
            fx(
                () -> {
                  assertEquals(
                      "Enabled", ((Label) panel.lookup("#developer-recovery-status")).getText());
                  assertNotNull(panel.lookup("#developer-recovery-disable"));
                  return null;
                });
          }
        } finally {
          fx(
              () -> {
                panel.close();
                stage.close();
                return null;
              });
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void recoveryEntryIsMasterOnlyAndSalesStillOpensWithoutPin() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      for (boolean master : new boolean[] {true, false}) {
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
                            master ? InstallationRole.MASTER : InstallationRole.SLAVE,
                            "Test",
                            "INSTALLED"),
                        new Gateway(master),
                        executor));
        var stage =
            fx(
                () -> {
                  var s = new Stage();
                  s.setScene(new Scene(home, 1000, 700));
                  s.show();
                  return s;
                });
        try {
          fx(
              () -> {
                ((Button) home.lookup("#module-ADMINISTRATION")).fire();
                return null;
              });
          fx(
              () -> {
                var dialog =
                    Window.getWindows().stream()
                        .filter(w -> w != stage && w.isShowing())
                        .findFirst()
                        .orElseThrow();
                if (master) assertNotNull(dialog.getScene().lookup("#forgot-owner-pin"));
                else assertNull(dialog.getScene().lookup("#forgot-owner-pin"));
                ((Stage) dialog).close();
                return null;
              });
          fx(
              () -> {
                ((Button) home.lookup("#module-SALES_ORDERS")).fire();
                assertNotNull(home.lookup("#home-lock"));
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
    } finally {
      executor.shutdownNow();
    }
  }
}
