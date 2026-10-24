package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsProtectionTest {
  @TempDir Path root;

  @Test
  void dpapiSurvivesStoreReopenWithoutWritingPlaintext() throws Exception {
    byte[] value =
        UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    var store = new WindowsSecretStore(root);
    store.put("terminal-p12", value);
    assertArrayEquals(value, new WindowsSecretStore(root).get("terminal-p12"));
    assertFalse(
        Arrays.equals(value, Files.readAllBytes(root.resolve("secrets/terminal-p12.dpapi"))));
    assertThrows(IllegalArgumentException.class, () -> store.get("../escape"));
  }
}
