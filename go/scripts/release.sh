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

  if [[ "${INCLUDE_UI}" == "1" ]]; then
    cat > "${pkg}/start-ui.ps1" <<'PS1'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir
./bin/htunnel-ui.exe -config ./configs/agent.yaml
PS1
  fi

  cat > "${pkg}/README.txt" <<'TXT'
HTunnel package usage:
- start-agent.sh / start-agent.ps1 : start tunnel agent (requires admin)
- start-ui.sh / start-ui.ps1       : start desktop UI (requires admin)
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
