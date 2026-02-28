Place the Windows TAP driver installer here before packaging:

- `tap-windows-installer.exe`

When this file exists, `scripts/release.sh` and `scripts/release.ps1` will copy it to:

- `dist/htunnel-agent-windows-*/bin/tap-windows-installer.exe`

Then `start-agent.cmd` / `start-ui.cmd` will auto-run it (silent mode `/S`) if no TAP/Wintun adapter is detected.
