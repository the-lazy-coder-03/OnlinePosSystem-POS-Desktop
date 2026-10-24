param([Parameter(Mandatory=$true)][ValidateSet('Invite','Revoke','Backup')][string]$Action,
 [string]$Endpoint,[string]$OutputPath,[string]$TerminalId)
$ErrorActionPreference='Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {throw 'Run installation management in an elevated PowerShell window'}
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$java=Join-Path $program 'desktop\runtime\bin\java.exe'
$classPath=Join-Path $program 'desktop\app\*'
if ($Action -eq 'Invite') {
 if (-not $Endpoint -or -not $OutputPath) {throw 'Provide Endpoint and OutputPath'}
 # Invitations contain a temporary registration credential; only save under an administrator-owned directory.
 $parent=Join-Path $env:ProgramData 'OnlinePOS\invitations'
 if(-not ([IO.Path]::GetFullPath($OutputPath)).StartsWith($parent+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Save invitations only under ProgramData\OnlinePOS\invitations'}
 New-Item $parent -ItemType Directory -Force | Out-Null
 & icacls.exe $parent /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
 & $java -cp $classPath za.co.onlinepos.installation.SetupTool invite $Endpoint $OutputPath
} elseif ($Action -eq 'Revoke') {
 & $java -cp $classPath za.co.onlinepos.installation.SetupTool revoke $TerminalId
} else {
 $data=Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
 $identity=Get-Content (Join-Path $data 'installation.json') -Raw | ConvertFrom-Json
 $secure=Read-Host 'Installation administrator password' -AsSecureString
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try {
  $envelope=@{request=@{branchId=$identity.branchId;role='BACKUP_MASTER';terminalName=$identity.terminalName;invitation=''};administratorPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
  $plain=[Text.Encoding]::UTF8.GetBytes(($envelope|ConvertTo-Json -Depth 5))
  Add-Type -AssemblyName System.Security
  $requests=Join-Path $env:LOCALAPPDATA 'OnlinePOS\requests';New-Item $requests -ItemType Directory -Force|Out-Null
  $path=Join-Path $requests (([guid]::NewGuid()).ToString()+'.dpapi')
  [IO.File]::WriteAllBytes($path,[Security.Cryptography.ProtectedData]::Protect($plain,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))
  & (Join-Path $PSScriptRoot 'Provision.ps1') -RequestPath $path
 } finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  if($plain){[Array]::Clear($plain,0,$plain.Length)}
  if($path){Remove-Item $path -Force -ErrorAction SilentlyContinue}
 }
}
if($LASTEXITCODE -ne 0){throw 'Installation management failed'}

