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
  & icacls.exe $Path /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Cannot secure installation directory' }
}
Protect-Directory $data
$provisionLock=[IO.File]::Open((Join-Path $data 'provision.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
# SetupTool authenticates the local installation administrator before modifying role/state.
Invoke-Tool -Arguments @('prepare',$resolved)
$identity = Get-Content (Join-Path $data 'installation.json') -Raw | ConvertFrom-Json
$role = $identity.role
$journal = Join-Path $data 'provisioning.log'
Add-Content $journal ('Prepared role '+$role+' at '+[DateTime]::UtcNow.ToString('o'))
if ($role -eq 'MASTER' -or $role -eq 'BACKUP_MASTER') {
  if (-not (Test-Path (Join-Path $program 'server\store-server.jar'))) { throw 'Re-run the installer with Master components before this role change' }
  $vc = & (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name vcredist
  $vcProcess=Start-Process $vc -Wait -PassThru -ArgumentList @('/install','/quiet','/norestart')
  if($vcProcess.ExitCode -notin @(0,1638,3010)){throw 'Verified Microsoft runtime installation failed'}
  $archive = & (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name postgres
  if (-not (Test-Path (Join-Path $program 'postgres\pgsql\bin\postgres.exe'))) {
    New-Item (Join-Path $program 'postgres') -ItemType Directory -Force | Out-Null
    Expand-Archive $archive (Join-Path $program 'postgres') -Force
  }
  $bin = Join-Path $program 'postgres\pgsql\bin'
  $pgdata = Join-Path $data 'postgres-data'
  if ($role -eq 'MASTER') {
    if (Get-NetTCPConnection -State Listen -LocalPort 55432 -ErrorAction SilentlyContinue) {
      if (-not (Get-Service 'OnlinePOSPostgres' -ErrorAction SilentlyContinue)) { throw 'PostgreSQL port 55432 is already occupied' }
    }
    Add-Type -AssemblyName System.Security
    $bundle = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $data 'secrets\database-bundle.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::LocalMachine)
    $credentials = [Text.Encoding]::UTF8.GetString($bundle) | ConvertFrom-Json
    if (-not (Test-Path (Join-Path $pgdata 'PG_VERSION'))) {
      if ((Test-Path $pgdata) -and (Get-ChildItem $pgdata -Force | Select-Object -First 1)) { throw 'Refusing to initialize nonempty database directory' }
      Protect-Directory $pgdata
      $pw = Join-Path $data 'initdb.password'
      try {
        [IO.File]::WriteAllText($pw,$credentials.bootstrap,[Text.Encoding]::ASCII)
        & (Join-Path $bin 'initdb.exe') '-D' $pgdata '--username=pos_bootstrap' '--auth=scram-sha-256' '--encoding=UTF8' '--locale=C' '--data-checksums' "--pwfile=$pw" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed' }
      } finally { Remove-Item $pw -Force -ErrorAction SilentlyContinue }
      Add-Content (Join-Path $pgdata 'postgresql.conf') "port=55432`nlisten_addresses='127.0.0.1'`npassword_encryption='scram-sha-256'"
      [IO.File]::WriteAllText((Join-Path $pgdata 'pg_hba.conf'),"host all all 127.0.0.1/32 scram-sha-256`r`n",[Text.Encoding]::ASCII)
    }
    if (-not (Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue)) {
      & (Join-Path $bin 'pg_ctl.exe') register -N OnlinePOSPostgres -D $pgdata -U 'NT AUTHORITY\LocalService' -S auto
      if ($LASTEXITCODE -ne 0) { throw 'Database service registration failed' }
      & sc.exe sidtype OnlinePOSPostgres unrestricted | Out-Null
    }
    & icacls.exe $pgdata /grant 'NT SERVICE\OnlinePOSPostgres:(OI)(CI)F' | Out-Null
    Start-Service OnlinePOSPostgres
    $env:PGPASSWORD = $credentials.bootstrap
    try {
      $sql = @"
SELECT format('CREATE ROLE pos_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', '$($credentials.owner)') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pos_owner')\gexec
SELECT format('CREATE ROLE pos_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', '$($credentials.runtime)') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pos_runtime')\gexec
SELECT 'CREATE DATABASE pos_store OWNER pos_owner' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='pos_store')\gexec
REVOKE ALL ON DATABASE pos_store FROM PUBLIC;
GRANT CONNECT ON DATABASE pos_store TO pos_runtime;
"@
      $sql | & (Join-Path $bin 'psql.exe') -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 -U pos_bootstrap -d postgres -q
      if ($LASTEXITCODE -ne 0) { throw 'Application database role provisioning failed' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue; [Array]::Clear($bundle,0,$bundle.Length);$credentials=$null;$sql=$null }
  }
  $service = Join-Path $program 'server\OnlinePOSStore.exe'
  $xml = @"
<service><id>OnlinePOSStore</id><name>OnlinePOS Store Backend</name>
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
  & sc.exe config OnlinePOSStore obj= 'NT AUTHORITY\LocalService' password= '' | Out-Null
  & sc.exe sidtype OnlinePOSStore unrestricted | Out-Null
  & icacls.exe $data /grant 'NT SERVICE\OnlinePOSStore:(OI)(CI)M' | Out-Null
  # PostgreSQL directory remains isolated from backend service access.
  if (Test-Path $pgdata) { & icacls.exe $pgdata /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' 'NT SERVICE\OnlinePOSPostgres:(OI)(CI)F' | Out-Null }
  if ($role -eq 'BACKUP_MASTER') { Set-Service OnlinePOSStore -StartupType Disabled }
}
Remove-NetFirewallRule -Name OnlinePOS-Discovery -ErrorAction SilentlyContinue
New-NetFirewallRule -Name OnlinePOS-Discovery -DisplayName 'OnlinePOS LAN discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -RemoteAddress LocalSubnet -Profile Private -Program (Join-Path $program 'desktop\OnlinePOS.exe') | Out-Null
Invoke-Tool -Arguments @('complete')
& icacls.exe (Join-Path $data 'installation.json') /grant '*S-1-5-32-545:R' | Out-Null
if ($role -eq 'MASTER') {
  if (Get-NetTCPConnection -State Listen -LocalPort 8443 -ErrorAction SilentlyContinue) {
    if ((Get-Service OnlinePOSStore).Status -ne 'Running') { throw 'HTTPS port 8443 is occupied' }
  }
  Remove-NetFirewallRule -Name OnlinePOS-HTTPS -ErrorAction SilentlyContinue
  New-NetFirewallRule -Name OnlinePOS-HTTPS -DisplayName 'OnlinePOS trusted store HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443 -RemoteAddress LocalSubnet -Profile Private -Program $java | Out-Null
  Set-Service OnlinePOSStore -StartupType Automatic
  Start-Service OnlinePOSStore
  Invoke-Tool -Arguments @('pair-local')
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
