package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class ModuleHomeTest {
  @BeforeAll
  static void startFx() throws Exception {
    FxTestSupport.start();
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Exception e) {
            result.completeExceptionally(e);
          }
        });
    return result.get(15, TimeUnit.SECONDS);
  }

  static final class Gateway extends ModuleGateway {
    final CountDownLatch revoked = new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicInteger refusal =
        new java.util.concurrent.atomic.AtomicInteger(401);

    Gateway() {
      super(Path.of("unused-test-root"));
    }

    @Override
    public void check() {}

    @Override
    public JsonNode enter(PosModule module, char[] pin) throws Exception {
      try {
        if (!new String(pin).equals("012345"))
          throw new TrustedStoreClient.ModuleFailure(refusal.get());
        return JsonFiles.JSON.readTree("{\"token\":\"test-token\",\"username\":\"Test staff\"}");
      } finally {
        java.util.Arrays.fill(pin, '\0');
      }
    }

    @Override
    public JsonNode request(String endpoint, String method, byte[] body, String token) {
      if (endpoint.equals("logout")) revoked.countDown();
      return JsonFiles.JSON.createObjectNode();
    }
  }

  private DialogPane dialog() {
    return Window.getWindows().stream()
        .filter(Window::isShowing)
        .map(w -> w.getScene().getRoot())
        .filter(DialogPane.class::isInstance)
        .map(DialogPane.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private void submit(ModuleHome home, String pin) throws Exception {
    fx(
        () -> {
          ((Button) home.lookup("#module-SALES_ORDERS")).fire();
          var dialog = dialog();
          var field = (PasswordField) dialog.lookup("#staff-pin");
          field.setText(pin);
          var open =
              dialog.getButtonTypes().stream()
                  .filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                  .findFirst()
                  .orElseThrow();
          ((Button) dialog.lookupButton(open)).fire();
          assertEquals("", field.getText());
          return null;
        });
  }

  private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (fx(condition)) return;
      // Bounded polling only in GUI tests; no production waits on the FX thread.
      Thread.sleep(20);
    }
    fail("Desktop state transition timed out");
  }

  @Test
  void gridPinCancellationFailureSuccessAndIdleLock() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var gateway = new Gateway();
      var time = new AtomicLong(1);
      var installation =
          new Installation(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              InstallationRole.MASTER,
              "Test terminal",
              "INSTALLED");
      ModuleHome home = fx(() -> new ModuleHome(installation, gateway, executor, time::get));
      Stage stage =
          fx(
              () -> {
                var window = new Stage();
                var scene = new Scene(home, 1100, 800);
                scene
                    .getStylesheets()
                    .add(
                        java.util.Objects.requireNonNull(ModuleHome.class.getResource("setup.css"))
                            .toExternalForm());
                window.setScene(scene);
                window.show();
                home.verifySmokeScenario();
                home.applyCss();
                home.layout();
                var snapshot = home.snapshot(null, null);
                var image =
                    new java.awt.image.BufferedImage(
                        (int) snapshot.getWidth(),
                        (int) snapshot.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < image.getHeight(); y++)
                  for (int x = 0; x < image.getWidth(); x++)
                    image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                java.nio.file.Files.createDirectories(Path.of("target"));
                javax.imageio.ImageIO.write(
                    image, "png", Path.of("target/module-home.png").toFile());
                return window;
              });
      try {
        fx(
            () -> {
              ((Button) home.lookup("#module-SALES_ORDERS")).fire();
              var pane = dialog();
              var field = (PasswordField) pane.lookup("#staff-pin");
              field.setText("012345");
              var cancel =
                  pane.getButtonTypes().stream()
                      .filter(b -> b.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                      .findFirst()
                      .orElseThrow();
              ((Button) pane.lookupButton(cancel)).fire();
              assertEquals("", field.getText());
              return null;
            });
        submit(home, "999999");
        await(
            () -> home.lookup("#module-grid") != null && !home.lookup("#module-grid").isDisabled());
        assertNull(fx(() -> home.lookup("#home-lock")));
        gateway.refusal.set(403);
        submit(home, "999999");
        await(
            () -> home.lookup("#module-grid") != null && !home.lookup("#module-grid").isDisabled());
        gateway.refusal.set(500);
        submit(home, "999999");
        await(
            () -> home.lookup("#module-grid") != null && !home.lookup("#module-grid").isDisabled());
        assertTrue(
            fx(
                () ->
                    home.lookupAll(".label").stream()
                        .filter(javafx.scene.control.Label.class::isInstance)
                        .map(javafx.scene.control.Label.class::cast)
                        .anyMatch(label -> label.getText().contains("Unable to connect"))));
        submit(home, "012345");
        await(() -> home.lookup("#home-lock") != null);
        time.set(TimeUnit.MINUTES.toNanos(5) + 2);
        fx(
            () -> {
              home.tick();
              assertNotNull(home.lookup("#module-grid"));
              return null;
            });
        assertTrue(gateway.revoked.await(5, TimeUnit.SECONDS));
        submit(home, "012345");
        await(() -> home.lookup("#home-lock") != null);
        fx(
            () -> {
              ((Button) home.lookup("#home-lock")).fire();
              assertNotNull(home.lookup("#module-grid"));
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

  @Test
  void startupRoutesConfiguredIncompleteAndNewTerminalsAndSetupCompletion() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var configured =
          new Installation(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              2,
              InstallationRole.SLAVE,
              "Smoke terminal",
              "INSTALLED");
      var saved =
          new java.util.concurrent.atomic.AtomicReference<java.util.Optional<Installation>>(
              java.util.Optional.empty());
      var presenter =
          new za.co.onlinepos.desktop.setup.SetupPresenter(null, null, null, executor, null) {
            @Override
            public CompletableFuture<java.util.Optional<Installation>> currentInstallation() {
              return CompletableFuture.completedFuture(saved.get());
            }

            @Override
            public CompletableFuture<String> install(
                za.co.onlinepos.installation.SetupRequest request, char[] password) {
              java.util.Arrays.fill(password, '\0');
              saved.set(java.util.Optional.of(configured));
              return CompletableFuture.completedFuture("Test setup complete");
            }
          };
      var shell = fx(() -> new DesktopShell(presenter, executor, false, false));
      Stage startupWindow =
          fx(
              () -> {
                var window = new Stage();
                window.setScene(new Scene(shell, 1100, 800));
                window.show();
                return window;
              });
      try {
        await(() -> shell.getCenter() instanceof SetupWizard);
        fx(
            () -> {
              var wizard = (SetupWizard) shell.getCenter();
              wizard.verifySmokeScenario();
              shell.applyCss();
              shell.layout();
              var field =
                  wizard.lookupAll(".password-field").stream()
                      .map(PasswordField.class::cast)
                      .findFirst()
                      .orElseThrow();
              field.setText(UUID.randomUUID().toString());
              wizard.lookupAll(".button").stream()
                  .filter(Button.class::isInstance)
                  .map(Button.class::cast)
                  .filter(b -> b.getText().equals("Install / resume setup"))
                  .findFirst()
                  .orElseThrow()
                  .fire();
              return null;
            });
        await(() -> shell.getCenter() instanceof ModuleHome);
      } finally {
        fx(
            () -> {
              shell.close();
              startupWindow.close();
              return null;
            });
      }
      for (String state : java.util.List.of("INSTALLED", "PROVISIONING")) {
        saved.set(java.util.Optional.of(configured.withState(state)));
        var restarted = fx(() -> new DesktopShell(presenter, executor, false, false));
        try {
          await(
              () ->
                  state.equals("INSTALLED")
                      ? restarted.getCenter() instanceof ModuleHome
                      : restarted.getCenter() instanceof SetupWizard);
        } finally {
          fx(
              () -> {
                restarted.close();
                return null;
              });
        }
      }
    }
  }
}
