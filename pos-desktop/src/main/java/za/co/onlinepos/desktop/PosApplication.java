package za.co.onlinepos.desktop;

import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class PosApplication extends Application {
  private DesktopContainer container;

  @Override
  public void init() {
    container = new DesktopContainer();
  }

  @Override
  public void stop() {
    if (container != null) {
      container.close();
      container = null;
    }
    System.out.println("DESKTOP_CDI_CLOSED");
  }

  @Override
  public void start(Stage stage) {
    SetupWizard wizard = container.view();
    Scene scene = new Scene(wizard, 720, 740);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("setup.css")).toExternalForm());
    stage.setTitle("OnlinePOS · Terminal setup");
    stage.setMinWidth(620);
    stage.setMinHeight(700);
    stage.setScene(scene);
    stage.show();
    if (getParameters().getRaw().contains("--smoke-test")) {
      Platform.runLater(
          () -> {
            try {
              wizard.verifySmokeScenario();
              System.out.println("DESKTOP_SMOKE_OK");
              Platform.exit();
            } catch (RuntimeException exception) {
              exception.printStackTrace(System.err);
              stop();
              System.exit(1);
            }
          });
    }
  }
}
