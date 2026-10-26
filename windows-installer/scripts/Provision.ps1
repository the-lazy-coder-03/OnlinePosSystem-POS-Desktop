param([Parameter(Mandatory=$true)][string]$RequestPath)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Windows administrator elevation required' }
$program = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$data = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
$requestRoot = Join-Path $env:LOCALAPPDATA 'OnlinePOS\requests'
$resolved = [IO.Path]::GetFullPath($RequestPath)
$requestOwner=(Get-Acl $resolved).Owner
if (-not $resolved.StartsWith(([IO.Path]::GetFullPath($requestRoot) + '\'),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($resolved) -ne '.dpapi') { throw 'Request outside protected setup directory' }
if ((Get-Item $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Request links are prohibited' }
# Reject existing data-root links before changing ACLs or invoking an elevated process.
$walk=[IO.DirectoryInfo]$data
while($walk){if($walk.Exists -and ($walk.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Installation directory links are prohibited'};$walk=$walk.Parent}
$java = Join-Path $program 'desktop\runtime\bin\java.exe' 
$classPath = Join-Path $program 'desktop\app\*'
$env:POS_DATA_ROOT=$data
function Invoke-Tool([string[]]$Arguments) {
  & $java -cp $classPath za.co.onlinepos.installation.SetupTool @Arguments
  if ($LASTEXITCODE -ne 0) { throw 'Protected setup operation failed' }
}
function Protect-Directory([string]$Path) {
  New-Item $Path -ItemType Directory -Force | Out-Null
  if((Get-Item $Path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Protected directory links are prohibited'}
  $security=New-Object Security.AccessControl.DirectorySecurity
  $security.SetAccessRuleProtection($true,$false)
  $security.SetOwner((New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))
  foreach($sid in @('S-1-5-18','S-1-5-32-544')){
    $rule=New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)),[Security.AccessControl.FileSystemRights]::FullControl,([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
    $security.AddAccessRule($rule)
  }
  Set-Acl -LiteralPath $Path -AclObject $security
}
# Refuse pre-planted security material instead of adopting untrusted files during elevation.
$trustedOwners=@('S-1-5-18','S-1-5-32-544',[Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
foreach($serviceName in @('OnlinePOSStore','OnlinePOSPostgres')){
  # Service SIDs are deterministic; retained ACLs must remain valid after uninstall/reinstall.
  $sidOutput=(& sc.exe showsid $serviceName) -join ' '
  $sidMatch=[regex]::Match($sidOutput,'S-1-5-80-(?:[0-9]+-){4}[0-9]+')
  if($LASTEXITCODE -ne 0 -or -not $sidMatch.Success){throw 'Cannot establish the app-owned service identities'}
  $trustedOwners+=$sidMatch.Value
}
function Assert-ProtectedMaterial([string]$Path) {
  if(-not(Test-Path -LiteralPath $Path)){return}
  if((Get-Item -LiteralPath $Path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Protected configuration links are prohibited'}
  $acl=Get-Acl -LiteralPath $Path
  if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trustedOwners){throw 'Untrusted configuration owner; administrator repair required'}
  $writeMask=[Security.AccessControl.FileSystemRights]::Write -bor [Security.AccessControl.FileSystemRights]::Delete -bor [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor [Security.AccessControl.FileSystemRights]::ChangePermissions -bor [Security.AccessControl.FileSystemRights]::TakeOwnership
  foreach($rule in $acl.Access){
    if($rule.AccessControlType -eq 'Allow' -and ($rule.FileSystemRights -band $writeMask) -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $trustedOwners){throw 'Configuration permits untrusted writes; administrator repair required'}
  }
}
foreach($name in @('administrator.json','installation.json','network.json','lan-policy.json','secrets','pairing','runtime','postgres-data','downloads')){
  Assert-ProtectedMaterial (Join-Path $data $name)
}
foreach($protectedFiles in @('secrets','downloads')){
  if(Test-Path (Join-Path $data $protectedFiles)){
    foreach($entry in Get-ChildItem (Join-Path $data $protectedFiles) -Force){Assert-ProtectedMaterial $entry.FullName}
  }
}
Protect-Directory $data
$provisionLock=[IO.File]::Open((Join-Path $data 'provision.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
# SetupTool authenticates the local installation administrator before modifying role/state.
Invoke-Tool -Arguments @('prepare',$resolved)
$identity = Get-Content (Join-Path $data 'installation.json') -Raw | ConvertFrom-Json
$role = $identity.role
$httpsPort=8443;$postgresPort=55432
$lan=Get-Content (Join-Path $data 'lan-policy.json') -Raw|ConvertFrom-Json
$adapterName=[string]$lan.adapterName
$storeAddress=[string]$lan.address
$storeSubnet=$null
$networkPath=Join-Path $data 'network.json'
if(Test-Path $networkPath){
 $network=Get-Content $networkPath -Raw|ConvertFrom-Json
 $httpsPort=[int]$network.httpsPort;$postgresPort=[int]$network.postgresPort
 if($network.storeSubnet){$storeSubnet=$network.storeSubnet}
 if($httpsPort -lt 1024 -or $httpsPort -gt 65535 -or $postgresPort -lt 1024 -or $postgresPort -gt 65535 -or $httpsPort -eq $postgresPort){throw 'Invalid network ports'}
}
if(-not $storeSubnet -or $storeSubnet -eq 'LocalSubnet'){throw 'An exact store subnet is required'}
$journal = Join-Path $data 'provisioning.log'
Add-Content $journal ('Prepared role '+$role+' at '+[DateTime]::UtcNow.ToString('o'))
if ($role -eq 'MASTER' -or $role -eq 'BACKUP_MASTER') {
  if (-not (Test-Path (Join-Path $program 'server\store-server.jar'))) { throw 'Re-run the installer with Master components before this role change' }
  $machineCache=Join-Path $data 'downloads'
  Protect-Directory $machineCache
  $vc = & (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name vcredist -Cache $machineCache
  $vcProcess=Start-Process $vc -PassThru -ArgumentList @('/install','/quiet','/norestart')
  $vcHandle=$vcProcess.Handle
  if(-not $vcProcess.WaitForExit(180000)){throw 'Microsoft runtime setup has not completed; allow it to finish before retrying'}
  $vcProcess.Refresh()
  if($vcProcess.ExitCode -notin @(0,1638,3010)){throw 'Verified Microsoft runtime installation failed'}
  Write-Output 'MICROSOFT_RUNTIME_READY'
  $archive = & (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name postgres -Cache $machineCache
  Write-Output 'POSTGRES_PAYLOAD_VERIFIED'
  $postgresRoot=Join-Path $program 'postgres'
  $payloadMarker=Join-Path $postgresRoot '.runtime.sha256'
  $payloadHash=((Get-Content (Join-Path $program 'dependencies.json') -Raw|ConvertFrom-Json).postgres.sha256)
  $runtimeReady=(Test-Path $payloadMarker) -and ((Get-Content $payloadMarker -Raw).Trim() -eq $payloadHash)
  foreach($requiredBinary in @('postgres.exe','initdb.exe','pg_ctl.exe','psql.exe')){
    if(-not(Test-Path (Join-Path $postgresRoot ('pgsql\bin\'+$requiredBinary)))){$runtimeReady=$false}
  }
  if (-not $runtimeReady) {
    if((Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue).Status -eq 'Running'){throw 'Stop the app-owned PostgreSQL service before repairing runtime binaries'}
    New-Item $postgresRoot -ItemType Directory -Force | Out-Null
    # Extract only runtime components and their notices; omit the unrelated pgAdmin distribution.
    & (Join-Path $env:SystemRoot 'System32\tar.exe') -xf $archive -C $postgresRoot 'pgsql/bin' 'pgsql/lib' 'pgsql/share' 'pgsql/server_license.txt' 'pgsql/commandlinetools_3rd_party_licenses.txt'
    if($LASTEXITCODE -ne 0){throw 'PostgreSQL runtime extraction failed; retry preserves the database'}
    foreach($requiredBinary in @('postgres.exe','initdb.exe','pg_ctl.exe','psql.exe')){
      if(-not(Test-Path (Join-Path $postgresRoot ('pgsql\bin\'+$requiredBinary)))){throw 'PostgreSQL runtime is incomplete'}
    }
    # A partial extraction is never considered ready on retry.
    [IO.File]::WriteAllText($payloadMarker,$payloadHash,[Text.Encoding]::ASCII)
  }
  Write-Output 'POSTGRES_RUNTIME_READY'
  $bin = Join-Path $program 'postgres\pgsql\bin'
  $pgdata = Join-Path $data 'postgres-data'
  if ($role -eq 'MASTER') {
    if (Get-NetTCPConnection -State Listen -LocalPort $postgresPort -ErrorAction SilentlyContinue) {
      if (-not (Get-Service 'OnlinePOSPostgres' -ErrorAction SilentlyContinue)) { throw 'PostgreSQL port 55432 is already occupied' }
    }
    Add-Type -AssemblyName System.Security
    $bundle = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $data 'secrets\database-bundle.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::LocalMachine)
    $credentials = [Text.Encoding]::UTF8.GetString($bundle) | ConvertFrom-Json
    if (-not (Test-Path (Join-Path $pgdata 'PG_VERSION'))) {
      if ((Test-Path $pgdata) -and (Get-ChildItem $pgdata -Force | Select-Object -First 1)) { throw 'Refusing to initialize nonempty database directory' }
      Protect-Directory $pgdata
      # initdb deliberately drops the Administrators SID on Windows. Grant only this user's SID
      # temporary access, then remove it and transfer ownership before starting the service.
      $setupSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
      & icacls.exe $data /grant "*$($setupSid):(RX)" | Out-Null
      if($LASTEXITCODE -ne 0){throw 'Cannot grant setup traversal'}
      & icacls.exe $pgdata /grant "*$($setupSid):(OI)(CI)M" | Out-Null
      if($LASTEXITCODE -ne 0){throw 'Cannot grant restricted initdb access'}
      $pw = Join-Path $data 'initdb.password'
      try {
        [IO.File]::WriteAllText($pw,$credentials.bootstrap,[Text.Encoding]::ASCII)
        & icacls.exe $pw /grant "*$($setupSid):R" | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot grant restricted initdb password-file access'}
        & (Join-Path $bin 'initdb.exe') '-D' $pgdata '--username=pos_bootstrap' '--auth=scram-sha-256' '--encoding=UTF8' '--locale=C' '--data-checksums' "--pwfile=$pw" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed' }
      } finally {
        Remove-Item $pw -Force -ErrorAction SilentlyContinue
        & icacls.exe $pgdata /setowner '*S-1-5-32-544' /T /C | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot secure initialized database ownership'}
        & icacls.exe $pgdata /remove:g "*$setupSid" /T /C | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot revoke temporary database setup access'}
      }
      Add-Content (Join-Path $pgdata 'postgresql.conf') "port=$postgresPort`nlisten_addresses='127.0.0.1'`npassword_encryption='scram-sha-256'"
      [IO.File]::WriteAllText((Join-Path $pgdata 'pg_hba.conf'),"host all all 127.0.0.1/32 scram-sha-256`r`n",[Text.Encoding]::ASCII)
    }
    if (-not (Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue)) {
      & (Join-Path $bin 'pg_ctl.exe') register -N OnlinePOSPostgres -D $pgdata -U 'NT AUTHORITY\LocalService' -S auto
      if ($LASTEXITCODE -ne 0) { throw 'Database service registration failed' }
      & sc.exe sidtype OnlinePOSPostgres unrestricted | Out-Null
    }
    $pgAccount=Invoke-CimMethod -InputObject (Get-CimInstance Win32_Service -Filter "Name='OnlinePOSPostgres'") -MethodName Change -Arguments @{StartName='NT SERVICE\OnlinePOSPostgres'}
    if($pgAccount.ReturnValue -ne 0){throw 'Cannot assign the dedicated PostgreSQL virtual account'}
    & icacls.exe $pgdata /grant 'NT SERVICE\OnlinePOSPostgres:(OI)(CI)F' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot grant PostgreSQL service access'}
    Start-Service OnlinePOSPostgres
    $ready=$false
    for($attempt=0;$attempt -lt 30;$attempt++){
      & (Join-Path $bin 'pg_isready.exe') -h 127.0.0.1 -p $postgresPort -q
      if($LASTEXITCODE -eq 0){$ready=$true;break}
      Start-Sleep -Seconds 1
    }
    if(-not $ready){throw 'PostgreSQL did not become ready'}
    $env:PGPASSWORD = $credentials.bootstrap
    try {
      $sql = @"
SELECT format('CREATE ROLE pos_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', '$($credentials.owner)') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pos_owner')\gexec
SELECT format('CREATE ROLE pos_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', '$($credentials.runtime)') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pos_runtime')\gexec
SELECT 'CREATE DATABASE pos_store OWNER pos_owner' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='pos_store')\gexec
REVOKE ALL ON DATABASE pos_store FROM PUBLIC;
GRANT CONNECT ON DATABASE pos_store TO pos_runtime;
"@
      $sql | & (Join-Path $bin 'psql.exe') -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $postgresPort -U pos_bootstrap -d postgres -q
      if ($LASTEXITCODE -ne 0) { throw 'Application database role provisioning failed' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue; [Array]::Clear($bundle,0,$bundle.Length);$credentials=$null;$sql=$null }
  }
  if ($role -eq 'MASTER') {
    # Owner credentials stay in the elevated helper; the service receives runtime credentials only.
    if((Get-Service OnlinePOSStore -ErrorAction SilentlyContinue).Status -eq 'Running'){Stop-Service OnlinePOSStore}
    & $java -jar (Join-Path $program 'server\store-server.jar') '--database-tool=migrate'
    if($LASTEXITCODE -ne 0){throw 'Local database migration failed; backend remains stopped'}
  }
  $service = Join-Path $program 'server\OnlinePOSStore.exe'
  $databaseDependency=''
  if($role -eq 'MASTER'){$databaseDependency='<depend>OnlinePOSPostgres</depend>'}
  $xml = @"
<service><id>OnlinePOSStore</id><name>OnlinePOS Store Backend</name>
$databaseDependency
<description>OnlinePOS local HTTPS installation service. Stage 3 local database and draft APIs; sales remain unavailable.</description>
<executable>$java</executable><arguments>-jar &quot;$program\server\store-server.jar&quot; --spring.profiles.active=installed</arguments>
<env name="POS_DATA_ROOT" value="$data"/><logpath>$data\logs</logpath>
<log mode="roll"/><onfailure action="restart" delay="10 sec"/><stoptimeout>30 sec</stoptimeout>
</service>
"@
  [IO.File]::WriteAllText((Join-Path $program 'server\OnlinePOSStore.xml'),$xml,[Text.Encoding]::UTF8)
  if (-not (Get-Service OnlinePOSStore -ErrorAction SilentlyContinue)) {
    & $service install
    if ($LASTEXITCODE -ne 0) { throw 'Backend service registration failed' }
  }
  if($role -eq 'MASTER'){ & sc.exe config OnlinePOSStore depend= OnlinePOSPostgres | Out-Null; if($LASTEXITCODE -ne 0){throw 'Cannot configure backend database dependency'} }
  $accountChange=Invoke-CimMethod -InputObject (Get-CimInstance Win32_Service -Filter "Name='OnlinePOSStore'") -MethodName Change -Arguments @{StartName='NT SERVICE\OnlinePOSStore'}
  if($accountChange.ReturnValue -ne 0){throw 'Cannot assign the least-privilege backend service account'}
  & sc.exe sidtype OnlinePOSStore unrestricted | Out-Null
  & icacls.exe $data /remove 'NT SERVICE\OnlinePOSStore' | Out-Null
  & icacls.exe $data /grant 'NT SERVICE\OnlinePOSStore:(RX)' | Out-Null
  foreach($directoryName in @('pairing','runtime','logs')){
    $directoryPath=Join-Path $data $directoryName
    Protect-Directory $directoryPath
    & icacls.exe $directoryPath /grant 'NT SERVICE\OnlinePOSStore:(OI)(CI)M' | Out-Null
  }
  $secretDirectory=Join-Path $data 'secrets'
  if(Test-Path $secretDirectory){ & icacls.exe $secretDirectory /grant 'NT SERVICE\OnlinePOSStore:(RX)' | Out-Null }
  foreach($secretName in @('ca-key','ca-certificate','pki-password','server-p12','trust-p12','database-runtime','pin-key')){
    $serviceSecret=Join-Path $secretDirectory ($secretName+'.dpapi')
    if(Test-Path $serviceSecret){ & icacls.exe $serviceSecret /grant 'NT SERVICE\OnlinePOSStore:R' | Out-Null }
  }
  # PostgreSQL directory remains isolated from backend service access.
  if (Test-Path $pgdata) { & icacls.exe $pgdata /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' 'NT SERVICE\OnlinePOSPostgres:(OI)(CI)F' | Out-Null }
  if ($role -eq 'BACKUP_MASTER') { Set-Service OnlinePOSStore -StartupType Disabled }
}
foreach($publicConfig in @('lan-policy.json','network.json')) {
 & icacls.exe (Join-Path $data $publicConfig) /grant '*S-1-5-32-545:R' | Out-Null
 if($role -eq 'MASTER' -or $role -eq 'BACKUP_MASTER'){& icacls.exe (Join-Path $data $publicConfig) /grant 'NT SERVICE\OnlinePOSStore:R'|Out-Null}
}
Remove-NetFirewallRule -Name OnlinePOS-Discovery -ErrorAction SilentlyContinue
New-NetFirewallRule -Name OnlinePOS-Discovery -DisplayName 'OnlinePOS LAN discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -RemoteAddress $storeSubnet -InterfaceAlias $adapterName -Profile Private -Program (Join-Path $program 'desktop\OnlinePOS.exe') | Out-Null
Invoke-Tool -Arguments @('complete')
& icacls.exe (Join-Path $data 'installation.json') /grant '*S-1-5-32-545:R' | Out-Null
if ($role -eq 'MASTER') {
  if (Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue) {
    if ((Get-Service OnlinePOSStore).Status -ne 'Running') { throw 'HTTPS port 8443 is occupied' }
  }
  Remove-NetFirewallRule -Name OnlinePOS-MasterDiscovery -ErrorAction SilentlyContinue
  New-NetFirewallRule -Name OnlinePOS-MasterDiscovery -DisplayName 'OnlinePOS Master discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -RemoteAddress $storeSubnet -InterfaceAlias $adapterName -Profile Private -Program $java | Out-Null
  Remove-NetFirewallRule -Name OnlinePOS-HTTPS -ErrorAction SilentlyContinue
  New-NetFirewallRule -Name OnlinePOS-HTTPS -DisplayName 'OnlinePOS trusted store HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $storeAddress -LocalPort $httpsPort -RemoteAddress $storeSubnet -InterfaceAlias $adapterName -Profile Private -Program $java | Out-Null
  Set-Service OnlinePOSStore -StartupType Automatic
  Start-Service OnlinePOSStore
  Invoke-Tool -Arguments @('pair-local')
  $refreshScript=Join-Path $program 'setup\Refresh-Network.ps1'
  $action=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$refreshScript+'"')
  $trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
  $startup=New-ScheduledTaskTrigger -AtStartup
  $settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 2) -StartWhenAvailable
  Register-ScheduledTask -TaskName 'OnlinePOS-NetworkRefresh' -Action $action -Trigger @($trigger,$startup) -Settings $settings -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
}
& icacls.exe $data /grant "$($requestOwner):(RX)" | Out-Null
if(Test-Path (Join-Path $data 'secrets')) {
 & icacls.exe (Join-Path $data 'secrets') /grant "$($requestOwner):(RX)" | Out-Null
 foreach($secretName in @('terminal-p12','terminal-password','pairing-trust')) {
  $secretPath=Join-Path $data ('secrets\'+$secretName+'.dpapi')
  if(Test-Path $secretPath){& icacls.exe $secretPath /grant "$($requestOwner):R" | Out-Null}
 }
}
Add-Content $journal ('Completed role '+$role+'; sales unavailable at '+[DateTime]::UtcNow.ToString('o'))
Write-Output 'SETUP_COMPLETE_SALES_UNAVAILABLE'


} finally { $provisionLock.Dispose() }
