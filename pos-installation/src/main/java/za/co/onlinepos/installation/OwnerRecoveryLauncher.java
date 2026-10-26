package za.co.onlinepos.installation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import za.co.onlinepos.contracts.InstallationRole;

/** Opens only the fixed, elevated local helper; no credentials or reset request cross the LAN. */
public final class OwnerRecoveryLauncher {
  private final Path root;

  public OwnerRecoveryLauncher(Path root) {
    this.root = root;
  }

  public boolean available() {
    if (!System.getProperty("os.name").startsWith("Windows")) return false;
    try {
      return new FileInstallationRepository(root)
          .load()
          .filter(i -> i.role() == InstallationRole.MASTER && i.state().equals("INSTALLED"))
          .isPresent();
    } catch (Exception unavailable) {
      return false;
    }
  }

  public void open() throws Exception {
    if (!available()) throw new IllegalStateException("Recovery is available only on the Master");
    Path helper = Path.of(System.getenv("ProgramFiles"), "OnlinePOS", "setup", "Recover-Owner.ps1");
    if (!Files.isRegularFile(helper) || Files.isSymbolicLink(helper))
      throw new IllegalStateException("Installed recovery helper unavailable");
    String inner = "& '" + helper.toString().replace("'", "''") + "'";
    String encoded =
        java.util.Base64.getEncoder().encodeToString(inner.getBytes(StandardCharsets.UTF_16LE));
    var process =
        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "$p=Start-Process powershell.exe -Verb RunAs -PassThru -ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand "
                    + encoded
                    + "'; $p.WaitForExit(); exit $p.ExitCode")
            .start();
    if (process.waitFor() != 0) throw new IllegalStateException("Recovery cancelled or refused");
  }
}
