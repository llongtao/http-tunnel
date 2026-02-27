# Repository Guidelines

## Project Structure & Module Organization
- `go/` is the active codebase.
- `go/cmd/htunnel-server`: server entrypoint.
- `go/cmd/htunnel-agent`: agent entrypoint.
- `go/internal/protocol`: WebSocket wire message definitions (`tunnel.proto` + protobuf envelope struct).
- `go/internal/server`: JWT auth, VIP mapping, TCP relay logic.
- `go/internal/agent`: WebSocket session mux, local SOCKS5, `tun2socks` process orchestration.
- `go/configs/*.yaml`: runnable configuration examples.
- `legacy/`: frozen Java implementation kept only for historical reference and rollback.

## Build, Test, and Development Commands
- `cd go && go mod tidy`: sync module dependencies.
- `cd go && go build ./...`: compile all Go packages.
- `cd go && go test ./...`: run unit tests.
- `cd go && go run ./cmd/htunnel-server -config configs/server.yaml`: run server.
- `cd go && go run ./cmd/htunnel-agent -config configs/agent.yaml`: run agent.
- `cd go && go run -tags "desktop,production" ./cmd/htunnel-ui -config configs/agent.yaml`: run native desktop UI.
- `cd go && ./scripts/release.sh`: build distributable agent packages with bundled `tun2socks`.

## Coding Style & Naming Conventions
- Use standard Go style (`gofmt` formatting, short package names, exported identifiers in `PascalCase`).
- Keep packages cohesive: protocol types in `internal/protocol`, runtime orchestration in `internal/agent` / `internal/server`.
- Prefer explicit message type constants (`auth`, `open`, `data`, `close`, `ping`, `pong`) over string literals in business logic.

## Testing Guidelines
- Place tests next to implementation files as `*_test.go`.
- Prioritize protocol round-trip tests, JWT validation tests, and stream lifecycle tests (`open -> data -> close`).
- Add integration tests for VIP mapping behavior and websocket reconnect handling when changing transport logic.

## Commit & Pull Request Guidelines
- Use concise conventional prefixes where possible, e.g. `feat:`, `fix:`, `refactor:`, `ci:`.
- PRs should include:
  - change summary,
  - config impact (`server.yaml`/`agent.yaml`),
  - verification commands used (`go build`, `go test`).
- If behavior changes on auth, mapping, or TUN startup, update `README.md` in the same PR.

## Security & Configuration Tips
- Never commit real JWT secrets or production tokens.
- Prefer `wss` in production and keep `ws` only for local/dev.
- TUN mode requires elevated privileges; document platform-specific route commands in config rather than hardcoding.
