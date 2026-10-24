param([Parameter(Mandatory=$true)][ValidateSet('inno','winsw','postgres','vcredist')][string]$Name,
  [string]$Cache = "$env:LOCALAPPDATA\OnlinePOS\downloads")
$ErrorActionPreference = 'Stop'
$manifest = Get-Content (Join-Path $PSScriptRoot '..\dependencies.json') -Raw | ConvertFrom-Json
$dependency = $manifest.$Name
if (-not $dependency -or $dependency.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Missing pinned dependency' }
New-Item $Cache -ItemType Directory -Force | Out-Null
$file = Join-Path $Cache ($Name + '-' + $dependency.version + [IO.Path]::GetExtension(([uri]$dependency.url).AbsolutePath))
if (-not (Test-Path $file)) {
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -UseBasicParsing -Uri $dependency.url -OutFile ($file+'.pending')
  Move-Item ($file+'.pending') $file
}
if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dependency.sha256) {
  Remove-Item $file -Force
  throw 'Dependency checksum mismatch; execution blocked'
}
Write-Output $file

