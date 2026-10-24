param([ValidateSet('BootstrapStaff','Backup')][string]$Operation,[string]$Username,[string]$Destination)
$ErrorActionPreference='Stop'
if(-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Windows elevation required'}
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$data=Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
$env:POS_DATA_ROOT=$data
$java=Join-Path $program 'desktop\runtime\bin\java.exe'
$jar=Join-Path $program 'server\store-server.jar'
if($Operation -eq 'BootstrapStaff') {
 if($Username -notmatch '^[a-z0-9._-]{3,64}$'){throw 'Supply a staff username'}
 & $java -jar $jar '--database-tool=bootstrap-staff' "--username=$Username"
 if($LASTEXITCODE -ne 0){throw 'Staff bootstrap refused'}
} else {
 & $java -jar $jar '--database-tool=authorize'
 if($LASTEXITCODE -ne 0){throw 'Installation administrator authentication refused'}
 # No destructive restore operation is exposed. Restore into an isolated database and verify first.
 $backups=Join-Path $data 'backups'
 New-Item $backups -ItemType Directory -Force|Out-Null
 if(-not $Destination){$Destination=Join-Path $backups ('local-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.dump')}
 if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Destination)) -ne $backups -or (Test-Path $Destination)){throw 'Use a new backup file in protected ProgramData\OnlinePOS\backups'}
 if((Get-Item $backups).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Backup directory links refused'}
 Add-Type -AssemblyName System.Security
 $bytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $data 'secrets\database-bundle.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::LocalMachine)
 try {
  $credentials=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
  $env:PGPASSWORD=$credentials.owner
  $port=55432
  if(Test-Path (Join-Path $data 'network.json')){$port=(Get-Content (Join-Path $data 'network.json') -Raw|ConvertFrom-Json).postgresPort}
  & (Join-Path $program 'postgres\pgsql\bin\pg_dump.exe') -h 127.0.0.1 -p $port -U pos_owner -d pos_store -Fc -f $Destination
  if($LASTEXITCODE -ne 0){throw 'Backup failed; incomplete file retained for diagnosis'}
  # Encrypt before marking success; plaintext exists only under the restricted machine directory.
  $dump=[IO.File]::ReadAllBytes($Destination)
  try {[IO.File]::WriteAllBytes(($Destination+'.dpapi'),[Security.Cryptography.ProtectedData]::Protect($dump,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))}finally{[Array]::Clear($dump,0,$dump.Length)}
  Remove-Item $Destination -Force
  Get-FileHash ($Destination+'.dpapi') -Algorithm SHA256|ForEach-Object {Write-Output ('LOCAL_BACKUP_OK SHA256 '+$_.Hash)}
 }finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue;[Array]::Clear($bytes,0,$bytes.Length);$credentials=$null}
}
