package za.co.onlinepos.desktop;

import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Installation;

/** Separate preview entry point. Normal installed-terminal startup is unchanged. */
public final class MenuPreview {
  private MenuPreview() {}

  public static void main(String[] args) {
    Platform.startup(
        () -> {
          var executor = Executors.newVirtualThreadPerTaskExecutor();
          var home =
              new ModuleHome(
                  new Installation(
                      1,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      1,
                      InstallationRole.MASTER,
                      "PREVIEW — click modules without a PIN",
                      "INSTALLED"),
                  new PreviewGateway(),
                  executor);
          var scene = new Scene(home, 1100, 800);
          scene.getStylesheets().add(MenuPreview.class.getResource("setup.css").toExternalForm());
          var stage = new Stage();
          stage.setTitle("OnlinePOS — Preview only");
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
          System.out.println("MODULE_PREVIEW_OPEN");
        });
  }
}
