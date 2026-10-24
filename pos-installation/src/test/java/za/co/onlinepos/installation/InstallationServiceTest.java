package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.InstallationRole;

class InstallationServiceTest {
  @Test
  void injectedAdapterFailureDoesNotLeaveCallerPasswordInMemory() {
    char[] password = UUID.randomUUID().toString().substring(0, 5).toCharArray();
    InstallationService service =
        new DefaultInstallationService(
            (request, secret) -> {
              throw new IOException("Simulated test adapter failure");
            });
    assertThrows(
        IOException.class,
        () ->
            service.install(new SetupRequest(1, InstallationRole.MASTER, "Counter", ""), password));
    for (char character : password) assertEquals('\0', character);
  }

  @Test
  void networkConfigurationRejectsInvalidAndCollidingPorts() {
    assertThrows(IllegalArgumentException.class, () -> new NetworkSettings(443, 55432));
    assertThrows(IllegalArgumentException.class, () -> new NetworkSettings(8443, 8443));
    assertEquals(18443, new NetworkSettings(18443, 55433).httpsPort());
    assertEquals(
        "192.168.1.0/24", new NetworkSettings(8443, 55432, "192.168.1.0/24").storeSubnet());
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkSettings(8443, 55432, "0.0.0.0/0"));
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkSettings(8443, 55432, "10.0.0.0/0"));
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkSettings(8443, 55432, "10.999.0.0/24"));
  }
}
