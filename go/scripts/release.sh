#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
HOST_OS="$(go env GOHOSTOS)"
HOST_ARCH="$(go env GOHOSTARCH)"

TARGETS="${TARGETS:-${HOST_OS}/${HOST_ARCH}}"
TUN2SOCKS_VERSION="${TUN2SOCKS_VERSION:-v1.16.11}"
INCLUDE_UI="${INCLUDE_UI:-1}"

mkdir -p "${DIST_DIR}"

echo "[release] root=${ROOT_DIR}"
echo "[release] targets=${TARGETS}"
echo "[release] include_ui=${INCLUDE_UI}"

for target in ${TARGETS}; do
  os="${target%/*}"
  arch="${target#*/}"

  if [[ "${os}" != "${HOST_OS}" || "${arch}" != "${HOST_ARCH}" ]]; then
    echo "[release] target ${os}/${arch} requires building on ${os}/${arch} host because tun2socks uses cgo"
    echo "[release] current host is ${HOST_OS}/${HOST_ARCH}"
    exit 1
  fi

  ext=""
  if [[ "${os}" == "windows" ]]; then
    ext=".exe"
  fi

  pkg="${DIST_DIR}/htunnel-agent-${os}-${arch}"
  rm -rf "${pkg}"
  mkdir -p "${pkg}/bin" "${pkg}/configs"

  echo "[release] build agent ${os}/${arch}"
  (
    cd "${ROOT_DIR}"
    GOOS="${os}" GOARCH="${arch}" CGO_ENABLED=0 go build -o "${pkg}/bin/htunnel-agent${ext}" ./cmd/htunnel-agent
  )

  if [[ "${INCLUDE_UI}" == "1" ]]; then
    echo "[release] build ui ${os}/${arch}"
    (
      cd "${ROOT_DIR}"
      if [[ "${os}" == "darwin" ]]; then
        GOOS="${os}" GOARCH="${arch}" CGO_ENABLED=1 CGO_LDFLAGS="-framework UniformTypeIdentifiers" \
          go build -tags "desktop,production" -o "${pkg}/bin/htunnel-ui${ext}" ./cmd/htunnel-ui
      else
        GOOS="${os}" GOARCH="${arch}" CGO_ENABLED=1 \
          go build -tags "desktop,production" -o "${pkg}/bin/htunnel-ui${ext}" ./cmd/htunnel-ui
      fi
    )
  fi

  echo "[release] build tun2socks ${os}/${arch} ${TUN2SOCKS_VERSION}"
  (
    cd "${ROOT_DIR}"
    GOOS="${os}" GOARCH="${arch}" CGO_ENABLED=1 \
      GOPROXY="${GOPROXY:-https://goproxy.cn,direct}" GOBIN="${pkg}/bin" \
      go install -tags socks github.com/eycorsican/go-tun2socks/cmd/tun2socks@"${TUN2SOCKS_VERSION}"
  )

  cp "${ROOT_DIR}/configs/agent.yaml" "${pkg}/configs/agent.yaml"
  if [[ "${os}" == "windows" && -f "${ROOT_DIR}/drivers/windows/tap-windows-installer.exe" ]]; then
    cp "${ROOT_DIR}/drivers/windows/tap-windows-installer.exe" "${pkg}/bin/tap-windows-installer.exe"
    echo "[release] bundled Windows TAP driver installer"
  fi
  if [[ "${os}" == "windows" && -d "${ROOT_DIR}/drivers/windows/tap.win10" ]]; then
    mkdir -p "${pkg}/drivers/windows"
    cp -R "${ROOT_DIR}/drivers/windows/tap.win10" "${pkg}/drivers/windows/"
    echo "[release] bundled Windows TAP package drivers/windows/tap.win10"
  fi

  cat > "${pkg}/start-agent.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  exec sudo "$0" "$@"
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"
./bin/htunnel-agent -config ./configs/agent.yaml
SH
  chmod +x "${pkg}/start-agent.sh"

  cat > "${pkg}/start.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"
if [ -x "./start-ui.sh" ]; then
  ./start-ui.sh
else
  ./start-agent.sh
fi
SH
  chmod +x "${pkg}/start.sh"

  if [[ "${INCLUDE_UI}" == "1" ]]; then
    cat > "${pkg}/start-ui.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  exec sudo "$0" "$@"
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"
./bin/htunnel-ui -config ./configs/agent.yaml
SH
    chmod +x "${pkg}/start-ui.sh"
  fi

  cat > "${pkg}/start-agent.ps1" <<'PS1'
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -File `"$PSCommandPath`""
  exit
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
./bin/htunnel-agent.exe -config ./configs/agent.yaml
PS1

  cat > "${pkg}/start-agent.cmd" <<'CMD'
@echo off
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$id=[Security.Principal.WindowsIdentity]::GetCurrent(); $p=New-Object Security.Principal.WindowsPrincipal($id); if($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 1}"
if "%errorlevel%"=="0" goto run

if /i "%~1"=="__elevated" (
  echo Failed to acquire Administrator privileges.
  echo Please right-click this script and select "Run as administrator".
  pause
  exit /b 1
)

echo Requesting Administrator privileges...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -ArgumentList '__elevated' -Verb RunAs -WorkingDirectory '%~dp0'"
if not "%errorlevel%"=="0" (
  echo Elevation was cancelled or failed.
  pause
  exit /b 1
)
exit /b 0

:run
if not exist ".\bin\htunnel-agent.exe" (
  echo Missing file: .\bin\htunnel-agent.exe
  pause
  exit /b 1
)
if not exist ".\configs\agent.yaml" (
  echo Missing file: .\configs\agent.yaml
  pause
  exit /b 1
)

call :has_tap_adapter
if "%errorlevel%"=="0" goto launch

echo No TAP/Wintun adapter found. Trying bundled driver installer...
if exist ".\bin\tap-windows-installer.exe" (
  start "" /wait ".\bin\tap-windows-installer.exe" /S
  call :has_tap_adapter
  if "%errorlevel%"=="0" goto launch
  echo Installer finished, but no TAP/Wintun adapter detected yet.
)

set "tap_arch=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "tap_arch=arm64"
if /i "%PROCESSOR_ARCHITECTURE%"=="X86" set "tap_arch=i386"
if /i "%PROCESSOR_ARCHITEW6432%"=="AMD64" set "tap_arch=amd64"
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" set "tap_arch=arm64"

if exist ".\drivers\windows\tap.win10\%tap_arch%\devcon.exe" if exist ".\drivers\windows\tap.win10\%tap_arch%\OemVista.inf" (
  echo Trying bundled TAP package via devcon ^(arch=%tap_arch%^)...
  pushd ".\drivers\windows\tap.win10\%tap_arch%"
  .\devcon.exe install .\OemVista.inf tap0901 >NUL 2>NUL
  if not "%errorlevel%"=="0" .\devcon.exe install .\OemVista.inf root\tap0901 >NUL 2>NUL
  popd
  call :has_tap_adapter
  if "%errorlevel%"=="0" goto launch
  echo Devcon install finished, but no TAP/Wintun adapter detected yet.
)

echo Missing bundled TAP installer assets:
echo - .\bin\tap-windows-installer.exe ^(optional^)
echo - .\drivers\windows\tap.win10\%tap_arch%\devcon.exe + OemVista.inf ^(optional^)
echo Please install TAP/Wintun driver, then run again.
pause
exit /b 1

:has_tap_adapter
powershell -NoProfile -ExecutionPolicy Bypass -Command "$a=Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue; $ok=$false; foreach($n in $a){$name=[string]$n.Name; $desc=[string]$n.InterfaceDescription; if((($name -match 'wintun') -or ($desc -match 'wintun') -or ($desc -match 'tap-windows') -or ($desc -match 'tap-win32') -or ($desc -match 'tap adapter') -or ($desc -match 'openvpn tap')) -and ($name -notmatch 'isatap') -and ($desc -notmatch 'isatap')){$ok=$true; break}}; if($ok){exit 0}else{exit 1}"
exit /b %errorlevel%

:launch
.\bin\htunnel-agent.exe -config .\configs\agent.yaml
set "exit_code=%errorlevel%"
if not "%exit_code%"=="0" (
  echo Process exited with code %exit_code%.
  pause
)
exit /b %exit_code%
CMD

  cat > "${pkg}/start.ps1" <<'PS1'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
if (Test-Path .\start-ui.ps1) {
  .\start-ui.ps1
} else {
  .\start-agent.ps1
}
PS1

  cat > "${pkg}/start.cmd" <<'CMD'
@echo off
setlocal
cd /d "%~dp0"

if exist ".\start-ui.cmd" (
  call ".\start-ui.cmd"
) else (
  call ".\start-agent.cmd"
)
set "exit_code=%errorlevel%"
if not "%exit_code%"=="0" (
  echo Starter failed with code %exit_code%.
  pause
)
exit /b %exit_code%
CMD

  if [[ "${INCLUDE_UI}" == "1" ]]; then
    cat > "${pkg}/start-ui.ps1" <<'PS1'
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -File `"$PSCommandPath`""
  exit
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
./bin/htunnel-ui.exe -config ./configs/agent.yaml
PS1

    cat > "${pkg}/start-ui.cmd" <<'CMD'
@echo off
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$id=[Security.Principal.WindowsIdentity]::GetCurrent(); $p=New-Object Security.Principal.WindowsPrincipal($id); if($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 1}"
if "%errorlevel%"=="0" goto run

if /i "%~1"=="__elevated" (
  echo Failed to acquire Administrator privileges.
  echo Please right-click this script and select "Run as administrator".
  pause
  exit /b 1
)

echo Requesting Administrator privileges...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -ArgumentList '__elevated' -Verb RunAs -WorkingDirectory '%~dp0'"
if not "%errorlevel%"=="0" (
  echo Elevation was cancelled or failed.
  pause
  exit /b 1
)
exit /b 0

:run
if not exist ".\bin\htunnel-ui.exe" (
  echo Missing file: .\bin\htunnel-ui.exe
  pause
  exit /b 1
)
if not exist ".\configs\agent.yaml" (
  echo Missing file: .\configs\agent.yaml
  pause
  exit /b 1
)

call :has_tap_adapter
if "%errorlevel%"=="0" goto launch

echo No TAP/Wintun adapter found. Trying bundled driver installer...
if exist ".\bin\tap-windows-installer.exe" (
  start "" /wait ".\bin\tap-windows-installer.exe" /S
  call :has_tap_adapter
  if "%errorlevel%"=="0" goto launch
  echo Installer finished, but no TAP/Wintun adapter detected yet.
)

set "tap_arch=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "tap_arch=arm64"
if /i "%PROCESSOR_ARCHITECTURE%"=="X86" set "tap_arch=i386"
if /i "%PROCESSOR_ARCHITEW6432%"=="AMD64" set "tap_arch=amd64"
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" set "tap_arch=arm64"

if exist ".\drivers\windows\tap.win10\%tap_arch%\devcon.exe" if exist ".\drivers\windows\tap.win10\%tap_arch%\OemVista.inf" (
  echo Trying bundled TAP package via devcon ^(arch=%tap_arch%^)...
  pushd ".\drivers\windows\tap.win10\%tap_arch%"
  .\devcon.exe install .\OemVista.inf tap0901 >NUL 2>NUL
  if not "%errorlevel%"=="0" .\devcon.exe install .\OemVista.inf root\tap0901 >NUL 2>NUL
  popd
  call :has_tap_adapter
  if "%errorlevel%"=="0" goto launch
  echo Devcon install finished, but no TAP/Wintun adapter detected yet.
)

echo Missing bundled TAP installer assets:
echo - .\bin\tap-windows-installer.exe ^(optional^)
echo - .\drivers\windows\tap.win10\%tap_arch%\devcon.exe + OemVista.inf ^(optional^)
echo Please install TAP/Wintun driver, then run again.
pause
exit /b 1

:has_tap_adapter
powershell -NoProfile -ExecutionPolicy Bypass -Command "$a=Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue; $ok=$false; foreach($n in $a){$name=[string]$n.Name; $desc=[string]$n.InterfaceDescription; if((($name -match 'wintun') -or ($desc -match 'wintun') -or ($desc -match 'tap-windows') -or ($desc -match 'tap-win32') -or ($desc -match 'tap adapter') -or ($desc -match 'openvpn tap')) -and ($name -notmatch 'isatap') -and ($desc -notmatch 'isatap')){$ok=$true; break}}; if($ok){exit 0}else{exit 1}"
exit /b %errorlevel%

:launch
.\bin\htunnel-ui.exe -config .\configs\agent.yaml
set "exit_code=%errorlevel%"
if not "%exit_code%"=="0" (
  echo Process exited with code %exit_code%.
  pause
)
exit /b %exit_code%
CMD
  fi

  cat > "${pkg}/README.txt" <<'TXT'
HTunnel package usage:
- start-agent.cmd / start-agent.ps1 : start tunnel agent on Windows (requires admin)
- start-ui.cmd / start-ui.ps1       : start desktop UI on Windows (requires admin)
- start-agent.sh / start-ui.sh       : start on macOS/Linux (requires root/sudo)
- start.cmd                          : default starter on Windows
- bin/tap-windows-installer.exe      : optional Windows TAP driver installer (auto-run by start-*.cmd when adapter missing)
- drivers/windows/tap.win10          : optional TAP package (devcon + OemVista.inf), auto-used by start-*.cmd when adapter missing
- configs/agent.yaml               : client config
TXT

  if [[ "${os}" == "windows" ]]; then
    (
      cd "${DIST_DIR}"
      rm -f "htunnel-agent-${os}-${arch}.zip"
      zip -qr "htunnel-agent-${os}-${arch}.zip" "htunnel-agent-${os}-${arch}"
    )
  else
    (
      cd "${DIST_DIR}"
      tar -czf "htunnel-agent-${os}-${arch}.tar.gz" "htunnel-agent-${os}-${arch}"
    )
  fi

  echo "[release] package ready: ${pkg}"
done

echo "[release] done"
