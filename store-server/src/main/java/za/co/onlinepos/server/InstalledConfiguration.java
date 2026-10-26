package za.co.onlinepos.server;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import za.co.onlinepos.installation.FileInstallationRepository;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.MdnsDiscovery;
import za.co.onlinepos.installation.TerminalRegistry;
import za.co.onlinepos.installation.WindowsSecretStore;

@Configuration(proxyBeanMethods = false)
@Profile("installed")
public class InstalledConfiguration {
  @Bean
  Installation installedIdentity() throws Exception {
    Installation identity = new FileInstallationRepository(dataRoot()).load().orElseThrow();
    if (identity.role() != za.co.onlinepos.contracts.InstallationRole.MASTER
        || !identity.state().equals("INSTALLED"))
      throw new IllegalStateException("Only a provisioned Master may start the installed server");
    return identity;
  }

  static Path dataRoot() {
    return Path.of(
        System.getProperty(
            "pos.dataRoot",
            System.getenv().getOrDefault("POS_DATA_ROOT", "C:\\ProgramData\\OnlinePOS")));
  }

  @Bean
  za.co.onlinepos.installation.LanPolicy lanPolicy() throws Exception {
    return za.co.onlinepos.installation.LanPolicy.load(dataRoot());
  }

  @Bean
  TerminalRegistry terminalRegistry(Installation identity) {
    return new TerminalRegistry(
        dataRoot(), Clock.systemUTC(), identity, new WindowsSecretStore(dataRoot()));
  }

  @Bean(destroyMethod = "close")
  MdnsDiscovery masterAdvertisement(Installation identity) throws Exception {
    MdnsDiscovery discovery = new MdnsDiscovery(lanPolicy());
    try {
      discovery.advertise(
          identity, za.co.onlinepos.installation.NetworkSettings.load(dataRoot()).httpsPort());
    } catch (java.io.IOException exception) {
      discovery
          .close(); // Manual trusted hostname fallback remains available when multicast is blocked.
    }
    return discovery;
  }
}
