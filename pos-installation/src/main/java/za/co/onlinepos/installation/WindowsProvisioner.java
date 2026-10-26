package za.co.onlinepos.installation;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class WindowsProvisioner implements PlatformProvisioner {
  @Override
  public java.util.List<LanPolicy> networks() throws Exception {
    return WindowsNetworks.available();
  }

  public void provision(SetupRequest request, char[] password) throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows"))
      throw new UnsupportedOperationException("Installation requires Windows 10/11 x64");
    Path program = Path.of(System.getenv("ProgramFiles"), "OnlinePOS");
    Path helper = program.resolve("setup/Provision.ps1");
    if (!Files.isRegularFile(helper))
      throw new IllegalStateException("Install the Windows test package before provisioning");
    Path requests = Path.of(System.getenv("LOCALAPPDATA"), "OnlinePOS", "requests");
    Files.createDirectories(requests);
    Path file = requests.resolve(UUID.randomUUID() + ".dpapi");
    byte[] plain =
        JsonFiles.JSON.writeValueAsBytes(
            Map.of("request", request, "administratorPassword", new String(password)));
    try {
      JsonFiles.writeBytes(
          file,
          Crypt32Util.cryptProtectData(
              plain, null, WinCrypt.CRYPTPROTECT_LOCAL_MACHINE, "OnlinePOS setup", null));
      String script =
          "& '"
              + helper.toString().replace("'", "''")
              + "' -RequestPath '"
              + file.toString().replace("'", "''")
              + "'";
      String encoded =
          java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
      Process process =
          new ProcessBuilder(
                  "powershell.exe",
                  "-NoProfile",
                  "-Command",
                  "$p=Start-Process powershell.exe -Verb RunAs -PassThru -ArgumentList '-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "
                      + encoded
                      + "'; $handle=$p.Handle; $p.WaitForExit(); exit $p.ExitCode")
              .inheritIO()
              .start();
      if (process.waitFor() != 0)
        throw new IllegalStateException(
            "Windows setup did not complete. Review installation diagnostics.");
    } finally {
      java.util.Arrays.fill(plain, (byte) 0);
      Files.deleteIfExists(file);
    }
  }
}
