#ifndef StageDir
  #error StageDir is required
#endif
[Setup]
AppId={{B61A5417-6832-4693-AE78-410A7BCA04C2}
AppName=OnlinePOS ({#Channel})
AppVersion=0.3.0
DefaultDirName={autopf}\OnlinePOS
DefaultGroupName=OnlinePOS
OutputDir={#OutputDir}
OutputBaseFilename=OnlinePOS-0.3.0-{#Channel}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
PrivilegesRequired=admin
Compression=lzma2
SolidCompression=yes
UninstallDisplayIcon={app}\desktop\OnlinePOS.exe
SetupLogging=no
[Types]
Name: "slave"; Description: "Slave terminal"
Name: "master"; Description: "Master / managed Backup components"
Name: "custom"; Description: "Custom"; Flags: iscustom
[Components]
Name: "desktop"; Description: "JavaFX terminal"; Types: slave master custom; Flags: fixed
Name: "master"; Description: "Store backend and managed Backup components"; Types: master
[Files]
Source: "{#StageDir}\OnlinePOS\*"; DestDir: "{app}\desktop"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: desktop
Source: "{#StageDir}\server\*"; DestDir: "{app}\server"; Flags: ignoreversion recursesubdirs; Components: master
Source: "{#StageDir}\setup\*"; DestDir: "{app}\setup"; Flags: ignoreversion recursesubdirs
Source: "{#StageDir}\dependencies.json"; DestDir: "{app}"
Source: "{#StageDir}\release-manifest.json"; DestDir: "{app}"
[Icons]
Name: "{group}\OnlinePOS"; Filename: "{app}\desktop\OnlinePOS.exe"
Name: "{autodesktop}\OnlinePOS"; Filename: "{app}\desktop\OnlinePOS.exe"
[Run]
Filename: "{app}\desktop\OnlinePOS.exe"; Description: "Open JavaFX terminal setup"; Flags: nowait postinstall skipifsilent runasoriginaluser
[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
  if not WizardSilent then
    Result := MsgBox('This is an unsigned development installer. Sales, cloud sync and automatic promotion are unavailable. Install for testing?', mbConfirmation, MB_YESNO) = IDYES;
end;


function InitializeUninstall(): Boolean;
var ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ExpandConstant('{app}\setup\Uninstall.ps1') + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := Result and (ResultCode = 0);
  if not Result then MsgBox('OnlinePOS services could not stop safely. Uninstall has been cancelled.', mbError, MB_OK);
end;
