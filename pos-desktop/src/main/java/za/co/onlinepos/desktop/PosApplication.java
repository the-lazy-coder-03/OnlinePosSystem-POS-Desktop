package za.co.onlinepos.desktop;

import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class PosApplication extends Application {
  private DesktopContainer container;
  private DesktopShell shell;

  @Override
  public void init() {
    container = new DesktopContainer();
  }

  @Override
  public void stop() {
    if (shell != null) {
      shell.close();
      shell = null;
    }
    if (container != null) {
      container.close();
      container = null;
    }
    System.out.println("DESKTOP_CDI_CLOSED");
  }

  @Override
  public void start(Stage stage) {
    boolean started = false;
    try {
      show(stage);
      started = true;
    } finally {
      if (!started) stop();
    }
  }

  private void show(Stage stage) {
    boolean homeSmoke = getParameters().getRaw().contains("--home-smoke-test");
    shell = container.view(getParameters().getRaw().contains("--smoke-test"), homeSmoke);
    Scene scene = new Scene(shell, 1100, 800);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("setup.css")).toExternalForm());
    stage.setTitle("OnlinePOS");
    stage.setMinWidth(900);
    stage.setMinHeight(700);
    stage.setScene(scene);
    stage.show();
    if (getParameters().getRaw().contains("--smoke-test") || homeSmoke) {
      Platform.runLater(
          () -> {
            try {
              shell.verifySmokeScenario();
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
