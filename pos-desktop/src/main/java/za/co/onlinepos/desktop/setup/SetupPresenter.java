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

  public CompletableFuture<java.util.List<za.co.onlinepos.installation.MasterSelection>>
      findMasters(za.co.onlinepos.installation.LanPolicy network) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (var scan = new za.co.onlinepos.installation.MdnsDiscovery(network)) {
            var result = new java.util.ArrayList<za.co.onlinepos.installation.MasterSelection>();
            for (String origin : scan.candidates()) {
              if (!za.co.onlinepos.installation.LanPolicy.privateAddress(
                  java.net.URI.create(origin).getHost())) continue;
              try {
                result.add(za.co.onlinepos.installation.LanPairingClient.inspect(origin, network));
              } catch (Exception unavailable) {
                /* Stale discovery hints are not trusted identities. */
              }
            }
            return result.stream().distinct().toList();
          } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
          }
        },
        executor);
  }

  public CompletableFuture<za.co.onlinepos.installation.MasterSelection> inspectMaster(
      String address, za.co.onlinepos.installation.LanPolicy network) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String origin =
                address.startsWith("https://")
                    ? address
                    : za.co.onlinepos.installation.LanPairingClient.origin(address);
            return za.co.onlinepos.installation.LanPairingClient.inspect(origin, network);
          } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
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
