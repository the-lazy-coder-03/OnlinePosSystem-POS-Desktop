package za.co.onlinepos.desktop.setup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import za.co.onlinepos.installation.DefaultInstallationService;
import za.co.onlinepos.installation.DiagnosticsService;
import za.co.onlinepos.installation.FileInstallationRepository;
import za.co.onlinepos.installation.InstallationRepository;
import za.co.onlinepos.installation.InstallationService;
import za.co.onlinepos.installation.LocalDiagnostics;
import za.co.onlinepos.installation.MasterDiscovery;
import za.co.onlinepos.installation.MdnsDiscovery;
import za.co.onlinepos.installation.PlatformProvisioner;
import za.co.onlinepos.installation.WindowsProvisioner;

@ApplicationScoped
public class DesktopComponents {
  @Produces
  @ApplicationScoped
  public PlatformProvisioner provisioner() {
    return new WindowsProvisioner();
  }

  @Produces
  @ApplicationScoped
  public InstallationService installations(PlatformProvisioner provisioner) {
    return new DefaultInstallationService(provisioner);
  }

  @Produces
  @ApplicationScoped
  public InstallationRepository repository() {
    String data = System.getenv("ProgramData");
    return new FileInstallationRepository(
        data == null
            ? Path.of(System.getProperty("user.home"), ".onlinepos")
            : Path.of(data, "OnlinePOS"));
  }

  @Produces
  @ApplicationScoped
  public MasterDiscovery discovery() {
    return new MdnsDiscovery();
  }

  public void closeDiscovery(@Disposes MasterDiscovery discovery) {
    discovery.close();
  }

  @Produces
  @ApplicationScoped
  public DiagnosticsService diagnostics(InstallationRepository repository) {
    return new LocalDiagnostics(repository);
  }

  @Produces
  @ApplicationScoped
  public ExecutorService executor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  public void closeExecutor(@Disposes ExecutorService executor) {
    executor.shutdown();
  }
}
