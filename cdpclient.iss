; cdpclient.iss — Inno Setup script for CDP Client
; Build: iscc.exe cdpclient.iss

#define MyAppName "CDP Client"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "AIWeber2"
#define MyAppExeName "cdpclient.exe"

[Setup]
AppId={{CDPCLIENT-2024-AIWEBER2}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=CDPClient-Setup-{#MyAppVersion}
SetupIconFile=appicon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "startup"; Description: "开机启动"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "cdpclient.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "appicon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "aitun-client.exe"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\appicon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\appicon.ico"; Tasks: desktopicon
Name: "{autostartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: startup

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "启动 CDP Client"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /F /IM cdpclient.exe"; Flags: runhidden; RunOnceId: "KillProcess"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
