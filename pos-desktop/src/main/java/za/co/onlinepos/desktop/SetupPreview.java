package za.co.onlinepos.desktop;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import za.co.onlinepos.desktop.setup.SetupPresenter;
import za.co.onlinepos.installation.DefaultInstallationService;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.LanPolicy;
import za.co.onlinepos.installation.SetupRequest;
import za.co.onlinepos.installation.WindowsProvisioner;

/** Isolated visual review. No repository, secrets, database or elevated installer is written. */
public final class SetupPreview {
  private SetupPreview() {}

  public static void main(String[] args) {
    Platform.startup(
        () -> {
          var executor = Executors.newVirtualThreadPerTaskExecutor();
          var presenter =
              new SetupPresenter(
                  new DefaultInstallationService(new WindowsProvisioner()),
                  null,
                  null,
                  executor,
                  null) {
                @Override
                public CompletableFuture<Optional<Installation>> currentInstallation() {
                  return CompletableFuture.completedFuture(Optional.empty());
                }

                @Override
                public CompletableFuture<List<LanPolicy>> networks() {
                  if (!System.getProperty("os.name").startsWith("Windows"))
                    return CompletableFuture.completedFuture(
                        List.of(new LanPolicy("preview", "Preview network", "192.168.1.10", 24)));
                  return super.networks();
                }

                @Override
                public CompletableFuture<String> install(SetupRequest request, char[] password) {
                  java.util.Arrays.fill(password, '\0');
                  return CompletableFuture.failedFuture(new IllegalStateException("Preview only"));
                }
              };
          var root = new BorderPane(new SetupWizard(presenter));
          root.setTop(new Label("Setup preview · installation is disabled"));
          var scene = new Scene(root, 1050, 800);
          scene.getStylesheets().add(SetupPreview.class.getResource("setup.css").toExternalForm());
          var stage = new Stage();
          stage.setTitle("OnlinePOS — Setup Preview");
          stage.setMinWidth(800);
          stage.setMinHeight(650);
          stage.setScene(scene);
          stage.setOnHidden(
              e -> {
                executor.shutdownNow();
                Platform.exit();
              });
          stage.show();
        });
  }
}
