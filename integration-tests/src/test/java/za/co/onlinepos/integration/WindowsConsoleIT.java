package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.installation.Administrator;
import za.co.onlinepos.installation.FileInstallationRepository;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.WindowsSecretStore;

@EnabledOnOs(OS.WINDOWS)
class WindowsConsoleIT {
  @Test
  void redirectedCredentialInputStillRequiresTheInstallationAdministrator(@TempDir Path root)
      throws Exception {
    String password = "ř" + UUID.randomUUID().toString().substring(0, 4);
    new Administrator(root, Clock.systemUTC()).initialize(password.toCharArray());
    new FileInstallationRepository(root)
        .save(
            new Installation(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                InstallationRole.MASTER,
                "Console test",
                "INSTALLED"));
    new WindowsSecretStore(root)
        .put(
            "database-bundle",
            JsonFiles.JSON.writeValueAsBytes(
                Map.of(
                    "owner",
                    UUID.randomUUID().toString(),
                    "runtime",
                    UUID.randomUUID().toString())));
    assertEquals(1, authorize(root, UUID.randomUUID().toString()));
    assertEquals(0, authorize(root, password));
  }

  private int authorize(Path root, String password) throws Exception {
    Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
    Path jar =
        Path.of("..", "store-server", "target", "store-server-0.1.0-SNAPSHOT-exec.jar")
            .toAbsolutePath()
            .normalize();
    var builder =
        new ProcessBuilder(
                javaExecutable.toString(),
                "-jar",
                jar.toString(),
                "--database-tool=authorize",
                "--password-stdin")
            .redirectErrorStream(true);
    builder.environment().put("POS_DATA_ROOT", root.toString());
    Process process = builder.start();
    try {
      process.getOutputStream().write((password + "\n").getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      assertTrue(
          process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
          "Credential tool must terminate");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(output.contains(password), "Credential must not appear in diagnostic output");
      return process.exitValue();
    } finally {
      process.destroyForcibly();
    }
  }
}
