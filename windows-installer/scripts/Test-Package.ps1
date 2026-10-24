$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out=Join-Path $root 'windows-installer\target'
$installer=Join-Path $out 'OnlinePOS-0.2.0-Development.exe'
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$data=Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
function Install-Role([string]$Role) {
 Write-Output ('TEST_INSTALL_'+$Role)
 $p=Start-Process $installer -Wait -PassThru -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/TYPE=$Role")
 if($p.ExitCode -ne 0){throw "Installer failed for $Role"}
}
Install-Role 'slave'
if(Test-Path (Join-Path $program 'server\store-server.jar')){throw 'Slave installed backend components'}
if(Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue){throw 'Slave installed PostgreSQL service'}
$smoke=Start-Process (Join-Path $program 'desktop\OnlinePOS.exe') -ArgumentList '--smoke-test' -PassThru -RedirectStandardOutput (Join-Path $out 'packaged-fx.log') -RedirectStandardError (Join-Path $out 'packaged-fx-error.log')
# Keep the process handle open: Windows PowerShell otherwise loses ExitCode for detached starts.
$smokeHandle=$smoke.Handle
if(-not $smoke.WaitForExit(60000)){Stop-Process -Id $smoke.Id -Force;throw 'Packaged JavaFX did not exit within 60 seconds'}
$smoke.Refresh()
if($smoke.ExitCode -ne 0 -or (Get-Content (Join-Path $out 'packaged-fx.log') -Raw) -notmatch 'DESKTOP_SMOKE_OK'){throw 'Packaged JavaFX launch failed'}
if((Get-Content (Join-Path $out 'packaged-fx.log') -Raw) -notmatch 'DESKTOP_CDI_CLOSED'){throw 'Packaged CDI shutdown failed'}
Write-Output 'PACKAGED_JAVAFX_CDI_OK'
New-Item $data -ItemType Directory -Force|Out-Null
Set-Content (Join-Path $data 'preserve.marker') 'preserve'
$uninstall=Start-Process (Join-Path $program 'unins000.exe') -Wait -PassThru -ArgumentList '/VERYSILENT'
if($uninstall.ExitCode -ne 0 -or -not(Test-Path (Join-Path $data 'preserve.marker'))){throw 'Uninstall did not preserve data'}
Install-Role 'master'
Add-Type -AssemblyName System.Security
# Exercise an explicitly unseeded Backup before Master setup, retaining its state after uninstall.
$backupRequests=Join-Path $env:LOCALAPPDATA 'OnlinePOS\requests'
New-Item $backupRequests -ItemType Directory -Force|Out-Null
$backupRequest=Join-Path $backupRequests (([guid]::NewGuid()).ToString()+'.dpapi')
$backupPassword=([guid]::NewGuid()).ToString()
$backupBody=@{request=@{branchId=1;role='BACKUP_MASTER';terminalName='CI Backup';invitation=''};administratorPassword=$backupPassword}|ConvertTo-Json -Depth 5
$backupPlain=[Text.Encoding]::UTF8.GetBytes($backupBody)
# A pre-existing explicit permissive grant must be replaced before secrets are created.
& icacls.exe $data /grant '*S-1-1-0:(OI)(CI)M' | Out-Null
try {
 [IO.File]::WriteAllBytes($backupRequest,[Security.Cryptography.ProtectedData]::Protect($backupPlain,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $backupRequest
 if($LASTEXITCODE -ne 0){throw 'Backup component setup failed'}
 $backupIdentity=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($backupIdentity.state -ne 'STANDBY_UNSEEDED' -or (Get-Service OnlinePOSStore).StartType -ne 'Disabled'){throw 'Backup was not kept inactive'}
 if(Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue){throw 'Backup initialized a PostgreSQL service'}
 if(Test-Path (Join-Path $data 'postgres-data\PG_VERSION')){throw 'Backup initialized a database'}
 if((Get-Acl $data).Access|Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq 'S-1-1-0'}){throw 'Setup retained a permissive Everyone grant'}
 Write-Output 'WINDOWS_BACKUP_INACTIVE_UNSEEDED_OK'
} finally {
 Remove-Item $backupRequest -Force -ErrorAction SilentlyContinue
 [Array]::Clear($backupPlain,0,$backupPlain.Length);$backupPassword=$null;$backupBody=$null
}
$backupUninstall=Start-Process (Join-Path $program 'unins000.exe') -Wait -PassThru -ArgumentList '/VERYSILENT'
if($backupUninstall.ExitCode -ne 0 -or -not(Test-Path (Join-Path $data 'installation.json'))){throw 'Backup uninstall lost state'}
# Disposable-runner only: move retained Backup data intact so the next test is a fresh independent Master.
Move-Item $data (Join-Path $out 'retained-backup-state')
Install-Role 'master'
Add-Type -AssemblyName System.Security
$requests=Join-Path $env:LOCALAPPDATA 'OnlinePOS\requests';New-Item $requests -ItemType Directory -Force|Out-Null
$request=Join-Path $requests (([guid]::NewGuid()).ToString()+'.dpapi')
$password=([guid]::NewGuid()).ToString().Substring(0,5)
$body=@{request=@{branchId=1;role='MASTER';terminalName='CI Master';invitation=''};administratorPassword=$password}|ConvertTo-Json -Depth 5
$plain=[Text.Encoding]::UTF8.GetBytes($body)
try{
 [IO.File]::WriteAllBytes($request,[Security.Cryptography.ProtectedData]::Protect($plain,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))
 Write-Output 'TEST_MASTER_PROVISION_OR_REPAIR'
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $request
 if($LASTEXITCODE -ne 0){throw 'Master provisioning failed'}
 $identity=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($identity.state -ne 'INSTALLED' -or $identity.role -ne 'MASTER'){throw 'Wrong installation state'}
 foreach($service in @('OnlinePOSStore','OnlinePOSPostgres')){
  if((Get-Service $service).Status -ne 'Running'){throw "Service not running: $service"}
  if((Get-CimInstance Win32_Service -Filter "Name='$service'").StartName -ne ('NT SERVICE\'+$service)){throw 'Service does not use its dedicated virtual account'}
  Restart-Service $service
 }
 & (Join-Path $program 'desktop\runtime\bin\java.exe') -cp (Join-Path $program 'desktop\app\*') za.co.onlinepos.installation.SetupTool pair-local
 if($LASTEXITCODE -ne 0){throw 'Paired Master self-test failed'}
 # Repeat provisioning must preserve identity and database.
 Write-Output 'TEST_MASTER_PROVISION_OR_REPAIR'
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $request
 $again=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($again.installationId -ne $identity.installationId){throw 'Retry changed identity'}
 if($LASTEXITCODE -ne 0){throw 'Master repair failed'}
 $https=Get-NetFirewallRule -Name OnlinePOS-HTTPS
 if($https.Profile -ne 'Private'){throw 'HTTPS firewall opened outside Private profile'}
 $env:PGPASSWORD=([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $data 'secrets\database-bundle.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))|ConvertFrom-Json).bootstrap
 try {
  $pg=Join-Path $program 'postgres\pgsql\bin\psql.exe'
  $grants=& $pg -X -h 127.0.0.1 -p 55432 -U pos_bootstrap -d pos_store -At -c "SELECT count(*) FROM pg_roles WHERE rolname IN ('pos_owner','pos_runtime') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole"
  if($LASTEXITCODE -ne 0 -or $grants -ne '2'){throw 'Incorrect PostgreSQL role grants'}
  $tables=& $pg -X -h 127.0.0.1 -p 55432 -U pos_bootstrap -d pos_store -At -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
  if($LASTEXITCODE -ne 0 -or $tables -ne '0'){throw 'Stage 2 created business tables'}
 }finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
 $cachePath=Join-Path $data 'downloads'
 if(-not(Test-Path $cachePath)){throw 'Elevated setup did not use the protected machine cache'}
 foreach($rule in (Get-Acl $cachePath).Access){
  if($rule.AccessControlType -eq 'Allow' -and ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @('S-1-5-18','S-1-5-32-544')){throw 'Payload cache permits untrusted writes'}
 }
 Write-Output 'WINDOWS_MASTER_INSTALL_REPAIR_GRANTS_FIREWALL_OK'
 $masterUninstall=Start-Process (Join-Path $program 'unins000.exe') -Wait -PassThru -ArgumentList '/VERYSILENT'
 if($masterUninstall.ExitCode -ne 0){throw 'Configured Master uninstall failed'}
 if(-not(Test-Path (Join-Path $data 'postgres-data\PG_VERSION')) -or -not(Test-Path (Join-Path $data 'secrets\database-bundle.dpapi'))){throw 'Master uninstall removed database or keys'}
 foreach($service in @('OnlinePOSStore','OnlinePOSPostgres')){
  if(Get-CimInstance Win32_Service -Filter "Name='$service'"){throw 'Uninstall retained an app-owned service'}
 }
 if(Test-Path (Join-Path $program 'postgres')){throw 'Uninstall retained marked runtime binaries'}
 Install-Role 'master'
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $request
 if($LASTEXITCODE -ne 0){throw 'Configured Master reinstall/repair failed'}
 $restored=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($restored.installationId -ne $identity.installationId -or $restored.clusterId -ne $identity.clusterId -or $restored.terminalId -ne $identity.terminalId){throw 'Reinstall changed retained identities'}
 Write-Output 'WINDOWS_MASTER_UNINSTALL_REINSTALL_PRESERVED_DATA_OK'
}finally{
 Remove-Item $request -Force -ErrorAction SilentlyContinue
 [Array]::Clear($plain,0,$plain.Length);$password=$null;$body=$null
}

