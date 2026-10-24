package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;

class InstallationSecurityTest {
  @TempDir Path root;

  @Test
  void identitiesSurviveRetryAndCannotBeReplaced() throws Exception {
    var repository = new FileInstallationRepository(root);
    var identity =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            InstallationRole.MASTER,
            "Counter",
            "CONFIGURING");
    repository.save(identity);
    repository.save(identity.withState("INSTALLED"));
    assertEquals(identity.installationId(), repository.load().orElseThrow().installationId());
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.save(
                new Installation(
                    1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    InstallationRole.MASTER,
                    "Other",
                    "INSTALLED")));
  }

  @Test
  void administratorEnforcesApprovedMinimumAndPersistsAttemptLock() throws Exception {
    var admin = new Administrator(root, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class, () -> admin.initialize(new char[4]));
    char[] correct = UUID.randomUUID().toString().substring(0, 5).toCharArray();
    admin.initialize(correct);
    admin.verify(correct);
    for (int i = 0; i < 5; i++)
      assertThrows(SecurityException.class, () -> admin.verify("incorrect".toCharArray()));
    assertThrows(
        SecurityException.class,
        () -> new Administrator(root, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).verify(correct));
  }

  @Test
  void nonWindowsProvisioningCannotPretendToSucceed() {
    if (!System.getProperty("os.name").startsWith("Windows"))
      assertThrows(
          UnsupportedOperationException.class,
          () ->
              new WindowsProvisioner()
                  .provision(
                      new SetupRequest(1, InstallationRole.MASTER, "Counter", ""), new char[12]));
  }

  static class MemorySecrets implements SecretStore {
    private final Map<String, byte[]> values = new HashMap<>();

    public void put(String name, byte[] value) {
      values.put(name, value.clone());
    }

    public byte[] get(String name) {
      return values.get(name).clone();
    }
  }
}
