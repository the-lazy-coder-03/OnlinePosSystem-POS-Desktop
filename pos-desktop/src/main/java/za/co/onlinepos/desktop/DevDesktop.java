package za.co.onlinepos.desktop;

import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Installation;

/** H2 development entry point. No cloud, fixed store, or sample catalogue is required. */
public final class DevDesktop {
  private DevDesktop() {}

  public static void main(String[] args) {
    if (!"dev".equals(System.getenv("POS_DESKTOP_PROFILE")))
      throw new IllegalStateException("Explicit dev launch required");
    var gateway =
        new DevGateway(
            System.getenv("POS_DEV_API_TOKEN"),
            System.getenv("POS_DEV_INSTANCE"),
            Integer.parseInt(System.getenv("POS_DEV_PORT")));
    Platform.startup(
        () -> {
          var executor = Executors.newVirtualThreadPerTaskExecutor();
          var root =
              new javafx.scene.layout.BorderPane(new Label("Loading local store configuration…"));
          var scene = new Scene(root, 1100, 800);
          scene.getStylesheets().add(DevDesktop.class.getResource("setup.css").toExternalForm());
          var stage = new Stage();
          stage.setTitle("OnlinePOS — H2 Development");
          stage.setMinWidth(900);
          stage.setMinHeight(700);
          stage.setScene(scene);
          var current = new java.util.concurrent.atomic.AtomicReference<AutoCloseable>();
          Runnable[] load = new Runnable[1];
          load[0] =
              () ->
                  executor.execute(
                      () -> {
                        try {
                          var state = gateway.call("/grid/store", "GET", null);
                          Platform.runLater(
                              () -> {
                                if (!stage.isShowing()) return;
                                if (state.path("configured").asBoolean()) {
                                  var home =
                                      new ModuleHome(
                                          new Installation(
                                              1,
                                              UUID.randomUUID(),
                                              UUID.randomUUID(),
                                              UUID.randomUUID(),
                                              state.path("store").path("branch_id").asInt(),
                                              InstallationRole.MASTER,
                                              "Development — H2",
                                              "INSTALLED"),
                                          gateway,
                                          executor);
                                  current.set(home);
                                  root.setCenter(home);
                                } else {
                                  var setup = new StoreManagementPanel(gateway, executor, load[0]);
                                  current.set(setup);
                                  root.setCenter(setup);
                                }
                                System.out.println("DEV_DESKTOP_OPEN");
                              });
                        } catch (Exception failure) {
                          Platform.runLater(
                              () ->
                                  root.setCenter(
                                      new Label(
                                          "Local backend unavailable. Close and relaunch to retry; no data was cleared.")));
                        }
                      });
          stage.setOnCloseRequest(
              event -> {
                var controller = current.get();
                if (controller instanceof ModuleHome home && !home.canCloseDevelopment()
                    || controller instanceof StoreManagementPanel setup && !setup.confirmClose())
                  event.consume();
              });
          stage.setOnHidden(
              event -> {
                try {
                  if (current.get() != null) current.get().close();
                } catch (Exception ignored) {
                }
                executor.shutdownNow();
                Platform.exit();
              });
          stage.show();
          load[0].run();
        });
  }
}
