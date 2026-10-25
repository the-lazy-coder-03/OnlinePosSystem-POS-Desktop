package za.co.onlinepos.desktop;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import za.co.onlinepos.desktop.setup.SetupPresenter;

@Dependent
public class DesktopRoot {
  private final SetupPresenter presenter;
  private final ExecutorService executor;

  @Inject
  public DesktopRoot(SetupPresenter presenter, ExecutorService executor) {
    this.presenter = presenter;
    this.executor = executor;
  }

  DesktopShell view(boolean setupSmoke, boolean homeSmoke) {
    return new DesktopShell(presenter, executor, setupSmoke, homeSmoke);
  }

  static Path dataRoot() {
    String data = System.getenv("ProgramData");
    return data == null
        ? Path.of(System.getProperty("user.home"), ".onlinepos")
        : Path.of(data, "OnlinePOS");
  }
}

final class DesktopShell extends BorderPane implements AutoCloseable {
  private final SetupPresenter presenter;
  private final ExecutorService executor;
  private SetupWizard wizard;
  private ModuleHome home;
  private boolean closed;

  DesktopShell(
      SetupPresenter presenter, ExecutorService executor, boolean setupSmoke, boolean homeSmoke) {
    this.presenter = presenter;
    this.executor = executor;
    if (homeSmoke) {
      showHome(
          new za.co.onlinepos.installation.Installation(
              1,
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              java.util.UUID.randomUUID(),
              1,
              za.co.onlinepos.contracts.InstallationRole.MASTER,
              "Smoke terminal",
              "INSTALLED"));
    } else if (setupSmoke) showSetup();
    else {
      setCenter(new javafx.scene.control.Label("Opening OnlinePOS…"));
      route();
    }
  }

  private void route() {
    presenter
        .currentInstallation()
        .whenComplete(
            (installation, error) ->
                Platform.runLater(
                    () -> {
                      if (closed) return;
                      if (error != null) {
                        setCenter(
                            new javafx.scene.control.Label(
                                "Unable to read terminal configuration. Contact an administrator."));
                        return;
                      }
                      if (installation.isPresent()
                          && installation.get().state().equals("INSTALLED"))
                        showHome(installation.get());
                      else showSetup();
                    }));
  }

  private void showHome(za.co.onlinepos.installation.Installation installation) {
    home = new ModuleHome(installation, new ModuleGateway(DesktopRoot.dataRoot()), executor);
    setCenter(home);
  }

  private void showSetup() {
    wizard = new SetupWizard(presenter);
    wizard.onInstalled(this::route);
    setCenter(wizard);
  }

  void verifySmokeScenario() {
    if (home != null) home.verifySmokeScenario();
    else if (wizard != null) wizard.verifySmokeScenario();
    else throw new IllegalStateException("Startup incomplete");
  }

  @Override
  public void close() {
    closed = true;
    if (home != null) home.close();
  }
}
