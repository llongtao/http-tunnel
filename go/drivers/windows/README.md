Place the Windows TAP driver installer here before packaging:

- `tap-windows-installer.exe`
- `tap.win10/` (from OpenVPN tap-windows6 `dist.win10.zip`, includes `devcon.exe` + `OemVista.inf`)

When this file exists, `scripts/release.sh` and `scripts/release.ps1` will copy it to:

- `dist/htunnel-agent-windows-*/bin/tap-windows-installer.exe`

Then `start-agent.cmd` / `start-ui.cmd` will auto-run it (silent mode `/S`) if no TAP/Wintun adapter is detected.
If `tap.win10/` exists, launchers will also try `devcon.exe install OemVista.inf tap0901` automatically.
