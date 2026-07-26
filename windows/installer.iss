#define MyAppName "Red Arrow"
#define MyAppVersion "1.2.2"
#define MyAppPublisher "JoursBleu"
#define MyAppExeName "RedArrow.exe"

[Setup]
AppId={{8F91CE80-A833-4D84-88BE-92BAAD94398C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Red Arrow
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=dist
OutputBaseFilename=RedArrow-Windows-Setup-{#MyAppVersion}-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
UsePreviousAppDir=no
SetupIconFile=assets\red-arrow.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
VersionInfoCompany={#MyAppPublisher}

[Files]
Source: "dist\RedArrow.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\start-hidden.vbs"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\start-proxy.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\stop-proxy.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\system-proxy.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\control-center.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\show-status.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "scripts\open-log-folder.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\red-arrow.ico"; DestDir: "{app}"; DestName: "red-arrow.ico"; Flags: ignoreversion
Source: "assets\red-arrow.png"; DestDir: "{app}"; DestName: "red-arrow.png"; Flags: ignoreversion
Source: "config.example.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "rules\china.txt"; DestDir: "{app}\rules"; Flags: ignoreversion
Source: "rules\china6.txt"; DestDir: "{app}\rules"; Flags: ignoreversion
Source: "rules\README.md"; DestDir: "{app}\rules"; Flags: ignoreversion
Source: "rules\LICENSE"; DestDir: "{app}\rules"; Flags: ignoreversion

[Icons]
Name: "{group}\Red Arrow"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\control-center.ps1"""; WorkingDir: "{app}"; IconFilename: "{app}\red-arrow.ico"
Name: "{group}\Start proxy in background"; Filename: "{sys}\wscript.exe"; Parameters: """{app}\start-hidden.vbs"""; WorkingDir: "{app}"; IconFilename: "{app}\red-arrow.ico"
Name: "{group}\Stop proxy"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\stop-proxy.ps1"""; WorkingDir: "{app}"
Name: "{group}\Proxy status"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\show-status.ps1"""; WorkingDir: "{app}"
Name: "{group}\Open log folder"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\open-log-folder.ps1"""; WorkingDir: "{app}"
Name: "{group}\Uninstall"; Filename: "{uninstallexe}"

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\control-center.ps1"""; Description: "Open {#MyAppName}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\stop-proxy.ps1"" -RemoveStartup -RestoreSystemProxy"; Flags: runhidden; RunOnceId: "StopProxy"
