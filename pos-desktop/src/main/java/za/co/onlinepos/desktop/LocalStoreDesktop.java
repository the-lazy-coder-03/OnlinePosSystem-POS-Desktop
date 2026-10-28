package za.co.onlinepos.desktop;

import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Installation;

/** Explicit development entry point connected to the persistent loopback Master. */
public final class LocalStoreDesktop {
  private LocalStoreDesktop() {}

  public static void main(String[] args) {
    Platform.startup(
        () -> {
          var executor = Executors.newVirtualThreadPerTaskExecutor();
          var home =
              new ModuleHome(
                  new Installation(
                      1,
                      UUID.fromString(System.getenv("POS_CLUSTER_ID")),
                      UUID.fromString(System.getenv("POS_INSTALLATION_ID")),
                      UUID.fromString(System.getenv("POS_DEV_TERMINAL")),
                      1,
                      InstallationRole.MASTER,
                      "Kenridge — persistent local database",
                      "INSTALLED"),
                  new LocalStoreGateway(),
                  executor);
          var scene = new Scene(home, 1100, 800);
          scene
              .getStylesheets()
              .add(LocalStoreDesktop.class.getResource("setup.css").toExternalForm());
          var stage = new Stage();
          stage.setTitle("OnlinePOS — Kenridge");
          stage.setMinWidth(900);
          stage.setMinHeight(700);
          stage.setScene(scene);
          stage.setOnHidden(
              event -> {
                home.close();
                executor.shutdownNow();
                Platform.exit();
              });
          stage.show();
          System.out.println("LOCAL_STORE_DESKTOP_OPEN");
        });
  }
}
