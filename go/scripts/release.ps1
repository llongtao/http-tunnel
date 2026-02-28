$ErrorActionPreference = "Stop"

$rootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$distDir = Join-Path $rootDir "dist"
$tun2socksVersion = if ($env:TUN2SOCKS_VERSION -and $env:TUN2SOCKS_VERSION.Trim() -ne "") { $env:TUN2SOCKS_VERSION } else { "v1.16.11" }
$includeUI = if ($env:INCLUDE_UI -and $env:INCLUDE_UI.Trim() -ne "") { $env:INCLUDE_UI } else { "1" }
$hostOS = (go env GOHOSTOS).Trim()
$hostArch = (go env GOHOSTARCH).Trim()

if (-not $env:TARGETS -or $env:TARGETS.Trim() -eq "") {
  $targets = @("windows/amd64")
} else {
  $targets = $env:TARGETS.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Write-Host "[release] root=$rootDir"
Write-Host "[release] targets=$($targets -join ' ')"
Write-Host "[release] include_ui=$includeUI"

foreach ($target in $targets) {
  $parts = $target.Split("/")
  if ($parts.Count -ne 2) {
    throw "invalid target: $target"
  }
  $os = $parts[0]
  $arch = $parts[1]
  if ($os -ne $hostOS -or $arch -ne $hostArch) {
    throw "target $os/$arch requires building on $os/$arch host because tun2socks uses cgo; current host is $hostOS/$hostArch"
  }

  $ext = ""
  if ($os -eq "windows") { $ext = ".exe" }

  $pkgDir = Join-Path $distDir "htunnel-agent-$os-$arch"
  if (Test-Path $pkgDir) { Remove-Item -Recurse -Force $pkgDir }
  New-Item -ItemType Directory -Force -Path (Join-Path $pkgDir "bin") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $pkgDir "configs") | Out-Null

  Push-Location $rootDir
  try {
    Write-Host "[release] build agent $os/$arch"
    $env:GOOS = $os
    $env:GOARCH = $arch
    $env:CGO_ENABLED = "0"
    go build -o (Join-Path $pkgDir "bin/htunnel-agent$ext") ./cmd/htunnel-agent

    if ($includeUI -eq "1") {
      Write-Host "[release] build ui $os/$arch"
      $env:CGO_ENABLED = "1"
      go build -tags "desktop,production" -o (Join-Path $pkgDir "bin/htunnel-ui$ext") ./cmd/htunnel-ui
    }

    Write-Host "[release] build tun2socks $os/$arch $tun2socksVersion"
    $env:CGO_ENABLED = "1"
    $env:GOBIN = (Join-Path $pkgDir "bin")
    if (-not $env:GOPROXY -or $env:GOPROXY.Trim() -eq "") {
      $env:GOPROXY = "https://goproxy.cn,direct"
    }
    Remove-Item -ErrorAction SilentlyContinue (Join-Path $pkgDir "bin/tun2socks.exe")
    go install -tags socks "github.com/eycorsican/go-tun2socks/cmd/tun2socks@$tun2socksVersion"
  }
  finally {
    Pop-Location
  }

  Copy-Item (Join-Path $rootDir "configs/agent.yaml") (Join-Path $pkgDir "configs/agent.yaml") -Force

  @'
#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  exec sudo "$0" "$@"
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
./bin/htunnel-agent -config ./configs/agent.yaml
'@ | Set-Content -Path (Join-Path $pkgDir "start-agent.sh") -NoNewline

  @'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
if [ -x "./start-ui.sh" ]; then
  ./start-ui.sh
else
  ./start-agent.sh
fi
'@ | Set-Content -Path (Join-Path $pkgDir "start.sh") -NoNewline

  @'
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -File `"$PSCommandPath`""
  exit
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
./bin/htunnel-agent.exe -config ./configs/agent.yaml
'@ | Set-Content -Path (Join-Path $pkgDir "start-agent.ps1") -NoNewline

  @'
@echo off
setlocal
cd /d "%~dp0"

if /i "%~1"=="__elevated" (
  shift
  goto run
)

whoami /groups | find "S-1-16-12288" >NUL 2>NUL
if not "%errorlevel%"=="0" (
  whoami /groups | find "S-1-16-16384" >NUL 2>NUL
)
if not "%errorlevel%"=="0" (
  mshta "vbscript:CreateObject(""Shell.Application"").ShellExecute(""cmd.exe"",""/c cd /d """"%~dp0"""" ^&^& """"%~f0"""" __elevated"","""",""runas"",1)(close)"
  exit /b 0
)

:run
.\bin\htunnel-agent.exe -config .\configs\agent.yaml
exit /b %errorlevel%
'@ | Set-Content -Path (Join-Path $pkgDir "start-agent.cmd") -NoNewline

  @'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
if (Test-Path .\start-ui.ps1) {
  .\start-ui.ps1
} else {
  .\start-agent.ps1
}
'@ | Set-Content -Path (Join-Path $pkgDir "start.ps1") -NoNewline

  @'
@echo off
setlocal
cd /d "%~dp0"

if exist ".\start-ui.cmd" (
  call ".\start-ui.cmd"
) else (
  call ".\start-agent.cmd"
)
exit /b %errorlevel%
'@ | Set-Content -Path (Join-Path $pkgDir "start.cmd") -NoNewline

  if ($includeUI -eq "1") {
    @'
#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  exec sudo "$0" "$@"
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
./bin/htunnel-ui -config ./configs/agent.yaml
'@ | Set-Content -Path (Join-Path $pkgDir "start-ui.sh") -NoNewline

    @'
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -File `"$PSCommandPath`""
  exit
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
./bin/htunnel-ui.exe -config ./configs/agent.yaml
'@ | Set-Content -Path (Join-Path $pkgDir "start-ui.ps1") -NoNewline

    @'
@echo off
setlocal
cd /d "%~dp0"

if /i "%~1"=="__elevated" (
  shift
  goto run
)

whoami /groups | find "S-1-16-12288" >NUL 2>NUL
if not "%errorlevel%"=="0" (
  whoami /groups | find "S-1-16-16384" >NUL 2>NUL
)
if not "%errorlevel%"=="0" (
  mshta "vbscript:CreateObject(""Shell.Application"").ShellExecute(""cmd.exe"",""/c cd /d """"%~dp0"""" ^&^& """"%~f0"""" __elevated"","""",""runas"",1)(close)"
  exit /b 0
)

:run
.\bin\htunnel-ui.exe -config .\configs\agent.yaml
exit /b %errorlevel%
'@ | Set-Content -Path (Join-Path $pkgDir "start-ui.cmd") -NoNewline
  }

  @'
HTunnel package usage:
- start-agent.cmd / start-agent.ps1 : start tunnel agent on Windows (requires admin)
- start-ui.cmd / start-ui.ps1       : start desktop UI on Windows (requires admin)
- start-agent.sh / start-ui.sh      : start on macOS/Linux (requires root/sudo)
- start.cmd                         : default starter on Windows
- configs/agent.yaml               : client config
'@ | Set-Content -Path (Join-Path $pkgDir "README.txt") -NoNewline

  if ($os -eq "windows") {
    $zipPath = Join-Path $distDir "htunnel-agent-$os-$arch.zip"
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path $pkgDir -DestinationPath $zipPath -Force
  }

  Write-Host "[release] package ready: $pkgDir"
}

Write-Host "[release] done"
