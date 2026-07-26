$ErrorActionPreference = 'Stop'
$logDir = Join-Path $env:LOCALAPPDATA 'RedArrow'
New-Item -ItemType Directory -Force $logDir | Out-Null
Start-Process -FilePath 'explorer.exe' -ArgumentList ('"' + $logDir + '"')