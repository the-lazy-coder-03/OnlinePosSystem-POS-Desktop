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
