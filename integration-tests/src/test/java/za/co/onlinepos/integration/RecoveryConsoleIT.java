package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RecoveryConsoleIT {
  @Test
  void redirectedRecoveryCannotSilentlyFallBackToUnmaskedInput() throws Exception {
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var jar =
        Path.of("..", "store-server", "target", "store-server-0.1.0-SNAPSHOT-exec.jar")
            .toAbsolutePath()
            .normalize()
            .toString();
    for (String operation : new String[] {"recovery-enrol", "recovery-rotate", "recover-owner"}) {
      var process =
          new ProcessBuilder(java, "-jar", jar, "--database-tool=" + operation)
              .redirectErrorStream(true)
              .start();
      try {
        process.getOutputStream().close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, process.exitValue());
        assertFalse(output.contains("Installation administrator:"));
        assertFalse(output.contains("PIN:"));
        assertTrue(output.contains("SecurityException"));
      } finally {
        process.destroyForcibly();
      }
    }
  }
}
