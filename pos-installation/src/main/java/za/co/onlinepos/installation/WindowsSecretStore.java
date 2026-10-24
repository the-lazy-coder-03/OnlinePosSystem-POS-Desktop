package za.co.onlinepos.installation;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WindowsSecretStore implements SecretStore {
  private final Path root;

  public WindowsSecretStore(Path root) {
    if (!System.getProperty("os.name").startsWith("Windows"))
      throw new UnsupportedOperationException("Windows DPAPI is required");
    this.root = root.resolve("secrets");
  }

  private Path path(String name) {
    if (!name.matches("[a-z][a-z0-9-]{0,63}"))
      throw new IllegalArgumentException("Invalid secret name");
    return root.resolve(name + ".dpapi");
  }

  public void put(String name, byte[] value) throws IOException {
    JsonFiles.writeBytes(
        path(name),
        Crypt32Util.cryptProtectData(
            value, null, WinCrypt.CRYPTPROTECT_LOCAL_MACHINE, "OnlinePOS", null));
  }

  public byte[] get(String name) throws IOException {
    return Crypt32Util.cryptUnprotectData(Files.readAllBytes(path(name)));
  }
}
