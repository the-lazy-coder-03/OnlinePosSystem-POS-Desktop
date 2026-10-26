package za.co.onlinepos.desktop.setup;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import za.co.onlinepos.installation.DiagnosticsService;
import za.co.onlinepos.installation.InstallationService;
import za.co.onlinepos.installation.MasterDiscovery;
import za.co.onlinepos.installation.SetupRequest;

@Dependent
public class SetupPresenter {
  private final InstallationService installations;
  private final MasterDiscovery discovery;
  private final DiagnosticsService diagnostics;
  private final ExecutorService executor;
  private final za.co.onlinepos.installation.InstallationRepository repository;

  @Inject
  public SetupPresenter(
      InstallationService installations,
      MasterDiscovery discovery,
      DiagnosticsService diagnostics,
      ExecutorService executor,
      za.co.onlinepos.installation.InstallationRepository repository) {
    this.installations = installations;
    this.discovery = discovery;
    this.diagnostics = diagnostics;
    this.executor = executor;
    this.repository = repository;
  }

  public CompletableFuture<String> install(SetupRequest request, char[] password) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            installations.install(request, password);
            return diagnostics.inspect().toString();
          } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(exception);
          }
        },
        executor);
  }

  public CompletableFuture<java.util.List<za.co.onlinepos.installation.LanPolicy>> networks() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return installations.networks();
          } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
          }
        },
        executor);
  }

  public CompletableFuture<String> discover() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return String.join("\n", discovery.candidates());
          } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(exception);
          }
        },
        executor);
  }

  public CompletableFuture<java.util.Optional<za.co.onlinepos.installation.Installation>>
      currentInstallation() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return repository.load();
          } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
          }
        },
        executor);
  }
}
