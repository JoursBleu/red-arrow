Option Explicit

Dim shell, fileSystem, appDir, controlCenterScript, command
Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

appDir = fileSystem.GetParentFolderName(WScript.ScriptFullName)
controlCenterScript = fileSystem.BuildPath(appDir, "control-center.ps1")
command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File " & Chr(34) & controlCenterScript & Chr(34)

shell.Run command, 0, False