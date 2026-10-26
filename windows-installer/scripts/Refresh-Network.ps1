$ErrorActionPreference='Stop'
# Runs as SYSTEM from the protected Program Files directory. Never changes branch, CA or adapter.
$data=Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$lock=$null
try {
 foreach($path in @($data,(Join-Path $data 'lan-policy.json'),(Join-Path $data 'installation.json'),(Join-Path $data 'secrets'))){
  if((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Protected path link'}
 }
 $identity=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($identity.role -ne 'MASTER' -or $identity.state -ne 'INSTALLED'){return}
 $lock=[IO.File]::Open((Join-Path $data 'provision.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
 $lan=Get-Content (Join-Path $data 'lan-policy.json') -Raw|ConvertFrom-Json
 $adapter=Get-NetAdapter -Physical | Where-Object {$_.InterfaceGuid.ToString() -eq $lan.adapterId -and $_.Status -eq 'Up'} | Select-Object -First 1
 $profile=$null
 if($adapter){$profile=Get-NetConnectionProfile -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue}
 if(-not $adapter -or $profile.NetworkCategory -ne 'Private'){
  Stop-Service OnlinePOSStore -ErrorAction SilentlyContinue
  Remove-NetFirewallRule -Name OnlinePOS-HTTPS -ErrorAction SilentlyContinue
  Remove-NetFirewallRule -Name OnlinePOS-MasterDiscovery -ErrorAction SilentlyContinue
  return
 }
 $addresses=@(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 | Where-Object {$_.AddressState -eq 'Preferred'})
 $same=$addresses | Where-Object {$_.IPAddress -eq $lan.address -and $_.PrefixLength -eq $lan.prefixLength}
 if($same -and (Get-Service OnlinePOSStore).Status -eq 'Running'){return}
 Stop-Service OnlinePOSStore -ErrorAction SilentlyContinue
 $env:POS_DATA_ROOT=$data
 $java=Join-Path $program 'desktop\runtime\bin\java.exe'
 & $java -cp (Join-Path $program 'desktop\app\*') za.co.onlinepos.installation.SetupTool refresh-network
 if($LASTEXITCODE -ne 0){throw 'Approved network unavailable; protected repair required'}
 $lan=Get-Content (Join-Path $data 'lan-policy.json') -Raw|ConvertFrom-Json
 $network=Get-Content (Join-Path $data 'network.json') -Raw|ConvertFrom-Json
 foreach($rule in @('OnlinePOS-HTTPS','OnlinePOS-MasterDiscovery')){Remove-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue}
 New-NetFirewallRule -Name OnlinePOS-HTTPS -DisplayName 'OnlinePOS trusted store HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $lan.address -LocalPort $network.httpsPort -RemoteAddress $network.storeSubnet -InterfaceAlias $adapter.Name -Profile Private -Program $java|Out-Null
 New-NetFirewallRule -Name OnlinePOS-MasterDiscovery -DisplayName 'OnlinePOS Master discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -RemoteAddress $network.storeSubnet -InterfaceAlias $adapter.Name -Profile Private -Program $java|Out-Null
 & icacls.exe (Join-Path $data 'lan-policy.json') /grant '*S-1-5-32-545:R' 'NT SERVICE\OnlinePOSStore:R'|Out-Null
 & icacls.exe (Join-Path $data 'secrets\server-p12.dpapi') /grant 'NT SERVICE\OnlinePOSStore:R'|Out-Null
 Start-Service OnlinePOSStore
} catch {
 # Redacted: no credentials, request payloads or customer data in periodic diagnostics.
 if(Test-Path (Join-Path $data 'logs')){Add-Content (Join-Path $data 'logs\network-refresh.log') ([DateTime]::UtcNow.ToString('o')+' Network refresh incomplete; check protected repair diagnostics.')}
} finally {if($lock){$lock.Dispose()}}
