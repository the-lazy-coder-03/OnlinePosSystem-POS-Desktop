$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out=Join-Path $root 'windows-installer\target'
$installer=Join-Path $out 'OnlinePOS-0.2.0-Development.exe'
$program=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OnlinePOS'
$data=Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'OnlinePOS'
function Install-Role([string]$Role) {
 $p=Start-Process $installer -Wait -PassThru -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/TYPE=$Role")
 if($p.ExitCode -ne 0){throw "Installer failed for $Role"}
}
Install-Role 'slave'
if(Test-Path (Join-Path $program 'server\store-server.jar')){throw 'Slave installed backend components'}
if(Get-Service OnlinePOSPostgres -ErrorAction SilentlyContinue){throw 'Slave installed PostgreSQL service'}
$smoke=Start-Process (Join-Path $program 'desktop\OnlinePOS.exe') -ArgumentList '--smoke-test' -Wait -PassThru -RedirectStandardOutput (Join-Path $out 'packaged-fx.log') -RedirectStandardError (Join-Path $out 'packaged-fx-error.log')
if($smoke.ExitCode -ne 0 -or (Get-Content (Join-Path $out 'packaged-fx.log') -Raw) -notmatch 'DESKTOP_SMOKE_OK'){throw 'Packaged JavaFX launch failed'}
New-Item $data -ItemType Directory -Force|Out-Null
Set-Content (Join-Path $data 'preserve.marker') 'preserve'
$uninstall=Start-Process (Join-Path $program 'unins000.exe') -Wait -PassThru -ArgumentList '/VERYSILENT'
if($uninstall.ExitCode -ne 0 -or -not(Test-Path (Join-Path $data 'preserve.marker'))){throw 'Uninstall did not preserve data'}
Install-Role 'master'
Add-Type -AssemblyName System.Security
$requests=Join-Path $env:LOCALAPPDATA 'OnlinePOS\requests';New-Item $requests -ItemType Directory -Force|Out-Null
$request=Join-Path $requests (([guid]::NewGuid()).ToString()+'.dpapi')
$password=([guid]::NewGuid()).ToString()
$body=@{request=@{branchId=1;role='MASTER';terminalName='CI Master';invitation=''};administratorPassword=$password}|ConvertTo-Json -Depth 5
$plain=[Text.Encoding]::UTF8.GetBytes($body)
try{
 [IO.File]::WriteAllBytes($request,[Security.Cryptography.ProtectedData]::Protect($plain,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $request
 if($LASTEXITCODE -ne 0){throw 'Master provisioning failed'}
 $identity=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($identity.state -ne 'INSTALLED' -or $identity.role -ne 'MASTER'){throw 'Wrong installation state'}
 foreach($service in @('OnlinePOSStore','OnlinePOSPostgres')){
  if((Get-Service $service).Status -ne 'Running'){throw "Service not running: $service"}
  Restart-Service $service
 }
 & (Join-Path $program 'desktop\runtime\bin\java.exe') -cp (Join-Path $program 'desktop\app\*') za.co.onlinepos.installation.SetupTool pair-local
 if($LASTEXITCODE -ne 0){throw 'Paired Master self-test failed'}
 # Repeat provisioning must preserve identity and database.
 & (Join-Path $program 'setup\Provision.ps1') -RequestPath $request
 $again=Get-Content (Join-Path $data 'installation.json') -Raw|ConvertFrom-Json
 if($again.installationId -ne $identity.installationId){throw 'Retry changed identity'}
 Write-Output 'WINDOWS_MASTER_INSTALL_REPAIR_OK'
}finally{
 Remove-Item $request -Force -ErrorAction SilentlyContinue
 [Array]::Clear($plain,0,$plain.Length);$password=$null;$body=$null
}

