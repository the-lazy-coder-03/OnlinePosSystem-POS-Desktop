package za.co.onlinepos.installation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class WindowsNetworks {
  private WindowsNetworks() {}

  public static List<LanPolicy> available() throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows")) return List.of();
    String script =
        """
        [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
        $all=@(Get-NetAdapter | Where-Object {$_.Status -eq 'Up'})
        $physical=@($all | Where-Object {$_.HardwareInterface -and -not $_.Virtual})
        $eligible=@($physical)
        # Read-only fallback for an unelevated host: the standard external
        # management NIC shares its hardware NIC's MAC and external-switch binding.
        foreach($nic in $physical){
          $binding=Get-NetAdapterBinding -Name $nic.Name -ComponentID vms_pp -ErrorAction SilentlyContinue
          if($binding.Enabled -and ![string]::IsNullOrWhiteSpace($nic.MacAddress)){
            $eligible+=@($all | Where-Object {
              $_.Virtual -and $_.InterfaceDescription -like 'Hyper-V Virtual Ethernet Adapter*' -and $_.MacAddress -eq $nic.MacAddress
            })
          }
        }
        # A management-OS adapter on an EXTERNAL Hyper-V switch is the host's
        # physical LAN endpoint. Never admit internal switches, host-only NICs or VPNs.
        if(Get-Command Get-VMSwitch -ErrorAction SilentlyContinue){
          $switches=@(Get-VMSwitch -ErrorAction SilentlyContinue | Where-Object {$_.SwitchType -eq 'External'})
          $hosts=@(Get-VMNetworkAdapter -ManagementOS -ErrorAction SilentlyContinue)
          foreach($switch in $switches){
            $descriptions=@($switch.NetAdapterInterfaceDescription)+@($switch.NetAdapterInterfaceDescriptions)
            if(!@($physical | Where-Object {$descriptions -contains $_.InterfaceDescription}).Count){continue}
            foreach($hostNic in @($hosts | Where-Object {$_.SwitchId -eq $switch.Id})){
              if([string]::IsNullOrWhiteSpace($hostNic.MacAddress)){continue}
              $eligible+=@($all | Where-Object {($_.MacAddress -replace '-','') -eq $hostNic.MacAddress})
            }
          }
        }
        $rows=@($eligible | Sort-Object InterfaceGuid -Unique | ForEach-Object {
          $adapter=$_
          $profile=Get-NetConnectionProfile -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue
          if($profile.NetworkCategory -eq 'Private'){
            Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object {$_.AddressState -eq 'Preferred'} | ForEach-Object {
                @{adapterId=$adapter.InterfaceGuid.ToString();adapterName=$adapter.Name;address=$_.IPAddress;prefixLength=$_.PrefixLength}
              }
          }
        })
        ConvertTo-Json -InputObject $rows -Compress
        """;
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
    return result.stream().distinct().toList();
  }
}
