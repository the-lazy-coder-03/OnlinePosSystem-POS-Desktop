param([ValidateSet('Development','Production')][string]$Channel='Development')
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT'){throw 'Windows packaging must run on Windows x64'}
if($Channel -eq 'Production' -and -not $env:ONLINEPOS_SIGNING_THUMBPRINT){throw 'Production signing identity is required'}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $root
try {
 $out=Join-Path $root 'windows-installer\target'
 $stage=Join-Path $out 'stage'
 if(Test-Path $stage){Remove-Item $stage -Recurse -Force}
 New-Item $stage -ItemType Directory -Force|Out-Null
 $desktopInput=Join-Path $out 'desktop-input'
 if(Test-Path $desktopInput){Remove-Item $desktopInput -Recurse -Force}
 New-Item $desktopInput -ItemType Directory -Force|Out-Null
 & .\mvnw.cmd -B -ntp -pl pos-desktop dependency:copy-dependencies "-DoutputDirectory=$desktopInput" '-DincludeScope=runtime'
 if($LASTEXITCODE -ne 0){throw 'Desktop dependency staging failed'}
 Copy-Item 'pos-desktop\target\pos-desktop-0.1.0-SNAPSHOT.jar' $desktopInput
 $runtime=Join-Path $out 'runtime'
 if(Test-Path $runtime){Remove-Item $runtime -Recurse -Force}
 & (Join-Path $env:JAVA_HOME 'bin\jlink.exe') --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --output $runtime
 if($LASTEXITCODE -ne 0){throw 'Runtime generation failed'}
 & (Join-Path $env:JAVA_HOME 'bin\jpackage.exe') --type app-image --name OnlinePOS --input $desktopInput --main-jar pos-desktop-0.1.0-SNAPSHOT.jar --main-class za.co.onlinepos.desktop.Launcher --runtime-image $runtime --dest $stage --java-options '--enable-native-access=ALL-UNNAMED' --win-console --app-version 0.2.0
 if($LASTEXITCODE -ne 0){throw 'JavaFX image generation failed'}
 New-Item (Join-Path $stage 'server') -ItemType Directory|Out-Null
 Copy-Item 'store-server\target\store-server-0.1.0-SNAPSHOT-exec.jar' (Join-Path $stage 'server\store-server.jar')
 $winsw=& (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name winsw
 Copy-Item $winsw (Join-Path $stage 'server\OnlinePOSStore.exe')
 Copy-Item 'windows-installer\scripts' (Join-Path $stage 'setup') -Recurse
 Copy-Item 'windows-installer\dependencies.json' $stage
 $inno=& (Join-Path $PSScriptRoot 'Dependencies.ps1') -Name inno
 $compiler=Join-Path $out 'inno'
 $install=Start-Process $inno -Wait -PassThru -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=$compiler")
 if($install.ExitCode -ne 0){throw 'Inno Setup installation failed'}
 $manifest=@{channel=$Channel;version='0.2.0';jdk=(Get-Content (Join-Path $env:JAVA_HOME 'release') -Raw);files=@()}
 foreach($file in Get-ChildItem $stage -File -Recurse){
  $manifest.files+=@{path=$file.FullName.Substring($stage.Length+1);sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
 }
 $manifest|ConvertTo-Json -Depth 6|Set-Content (Join-Path $stage 'release-manifest.json') -Encoding UTF8
 & (Join-Path $compiler 'ISCC.exe') "/DStageDir=$stage" "/DOutputDir=$out" "/DChannel=$Channel" 'windows-installer\OnlinePOS.iss'
 if($LASTEXITCODE -ne 0){throw 'Installer compilation failed'}
 if($Channel -eq 'Production'){
  $sdk=Join-Path ([Environment]::GetFolderPath('ProgramFilesX86')) 'Windows Kits\10\bin\*\x64\signtool.exe'
  $sign=Get-ChildItem $sdk|Sort-Object FullName -Descending|Select-Object -First 1
  if(-not $sign){throw 'Windows SDK signing tool required'}
  & $sign.FullName sign /sha1 $env:ONLINEPOS_SIGNING_THUMBPRINT /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 (Join-Path $out 'OnlinePOS-0.2.0-Production.exe')
  if($LASTEXITCODE -ne 0){throw 'Installer signing failed'}
 }
 Get-ChildItem $out -Filter '*.exe'|ForEach-Object{(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$_.Name}|Set-Content (Join-Path $out 'SHA256SUMS.txt') -Encoding ASCII
 Write-Output "PACKAGE_READY $out"
} finally {Pop-Location}

