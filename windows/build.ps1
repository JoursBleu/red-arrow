$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

& cargo fmt -- --check
if ($LASTEXITCODE -ne 0) { throw 'cargo fmt check failed' }
& cargo test
if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' }
& cargo build --release
if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }

$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Remove-Item -Force (Join-Path $dist '*Setup-*-x64.exe') -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $dist '*Portable-*-x64.zip') -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $dist 'RedArrow.exe') -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $dist 'LatexToolsSshProxy.exe') -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $dist 'SHA256SUMS.txt') -ErrorAction SilentlyContinue
Copy-Item -Force '.\target\release\red-arrow-windows.exe' (Join-Path $dist 'RedArrow.exe')

$isccCandidates = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    throw 'Inno Setup 6 was not found. Install JRSoftware.InnoSetup with winget.'
}

& $iscc '.\installer.iss'
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed' }

$portableRoot = Join-Path $dist 'portable'
Remove-Item -Recurse -Force $portableRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $portableRoot | Out-Null
Copy-Item -Force (Join-Path $dist 'RedArrow.exe') $portableRoot
Copy-Item -Force '.\config.example.json' $portableRoot
Copy-Item -Force '.\scripts\*.ps1' $portableRoot
Copy-Item -Force '.\scripts\*.vbs' $portableRoot
Copy-Item -Force '.\assets\red-arrow.ico' $portableRoot
Copy-Item -Force '.\assets\red-arrow.png' $portableRoot
Copy-Item -Recurse -Force '.\rules' $portableRoot
$portableZip = Join-Path $dist 'RedArrow-Windows-Portable-1.2.2-x64.zip'
Remove-Item -Force $portableZip -ErrorAction SilentlyContinue
Compress-Archive -Path "$portableRoot\*" -DestinationPath $portableZip -CompressionLevel Optimal
Remove-Item -Recurse -Force $portableRoot

Get-ChildItem $dist -File | Where-Object { $_.Extension -in '.exe', '.zip' } |
    Get-FileHash -Algorithm SHA256 |
    ForEach-Object { '{0}  {1}' -f $_.Hash.ToLowerInvariant(), (Split-Path $_.Path -Leaf) } |
    Set-Content -Encoding ASCII (Join-Path $dist 'SHA256SUMS.txt')

Get-Content (Join-Path $dist 'SHA256SUMS.txt')
