package za.co.onlinepos.installation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class WindowsNetworks {
  private WindowsNetworks() {}

  public static List<LanPolicy> available() throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows")) return List.of();
    String script =
        "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $rows=@(Get-NetAdapter -Physical | Where-Object {$_.Status -eq 'Up'} | ForEach-Object { $a=$_; $p=Get-NetConnectionProfile -InterfaceIndex $a.ifIndex -ErrorAction SilentlyContinue; if($p.NetworkCategory -eq 'Private'){ Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 | Where-Object {$_.AddressState -eq 'Preferred'} | ForEach-Object { @{adapterId=$a.InterfaceGuid.ToString();adapterName=$a.Name;address=$_.IPAddress;prefixLength=$_.PrefixLength} } } }); ConvertTo-Json -InputObject $rows -Compress";
    var process =
        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                java.util.Base64.getEncoder()
                    .encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Network detection timed out");
    }
    if (process.exitValue() != 0) throw new IllegalStateException("Network detection failed");
    var rows = JsonFiles.JSON.readTree(process.getInputStream());
    var result = new java.util.ArrayList<LanPolicy>();
    if (rows != null)
      for (var row : rows) {
        try {
          result.add(JsonFiles.JSON.treeToValue(row, LanPolicy.class));
        } catch (IllegalArgumentException
            | com.fasterxml.jackson.core.JsonProcessingException ignored) {
          /* public/non-store ranges excluded */
        }
      }
    return List.copyOf(result);
  }
}
