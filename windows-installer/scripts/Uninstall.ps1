$ErrorActionPreference='Stop'
foreach($name in @('OnlinePOSStore','OnlinePOSPostgres')){
 if(Get-Service $name -ErrorAction SilentlyContinue){
  Stop-Service $name -Force -ErrorAction Stop
  & sc.exe delete $name | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Service removal failed'}
 }
}
Remove-NetFirewallRule -Name OnlinePOS-HTTPS -ErrorAction SilentlyContinue
# Never delete ProgramData: database, keys, administrator verifier and pending queues survive.


Remove-NetFirewallRule -Name OnlinePOS-Discovery -ErrorAction SilentlyContinue

Remove-NetFirewallRule -Name OnlinePOS-MasterDiscovery -ErrorAction SilentlyContinue

# Only the marked, application-owned runtime is removable; ProgramData is never a target.
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$postgresRoot=Join-Path $program 'postgres'
if(Test-Path (Join-Path $postgresRoot '.runtime.sha256')){
  if((Get-Item $postgresRoot).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing to traverse a runtime directory link'}
  Remove-Item $postgresRoot -Recurse -Force
}
