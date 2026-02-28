# http-tunnel

`http-tunnel` 是一个基于 WebSocket 的 TCP 隧道系统，用于把“本地访问虚拟 IP:端口”转发到远端内网真实服务。  
典型场景是：用户只访问固定地址（如 `10.2.2.123:30080`），由 agent 通过隧道连到服务端，再由服务端转发到内网目标地址。

主要解决的痛点：
- 研发团队常见限制：只能开通 HTTP/HTTPS 端口，不能额外开放自定义 TCP 端口
- 内网 TCP 服务（如管理端口、私有 API、调试端口）无法被外部直接访问
- 终端用户不希望配置复杂网络环境，只希望访问固定地址即可连通目标服务
- 多个内网目标需要统一出口与统一认证，降低运维复杂度

本项目的做法：
- 复用已开通的 HTTP/HTTPS 端口承载 WebSocket 隧道
- 在隧道内传输内部 TCP 流量，实现“HTTP 口复用的 TCP 代理”

核心组件：
- `go/cmd/htunnel-server`：服务端，维护 agent 连接并按 `vip_map` 转发到真实 IP
- `go/cmd/htunnel-agent`：客户端 agent，建立隧道并承接本地流量
- `go/cmd/htunnel-ui`：桌面端连接器（账号密码登录、自动获取 token 和路由）
- `go/internal/protocol/tunnel.proto`：隧道消息协议定义（Protobuf）


## 功能
- Agent 与 Server 通过 WebSocket 长连接通信（`ws`/`wss`）
- 使用 JWT 做 Agent 认证（HS256）
- 服务端支持虚拟 IP 到真实内网 IP 的一对一静态映射
- Agent 内置 SOCKS5，并可托管 `tun2socks` 进程实现虚拟网卡流量接入

## 快速开始
### 1. 部署服务端
二选一：
- 本机运行：
```bash
cd go
go run ./cmd/htunnel-server -config configs/server.yaml
```
- Docker 部署：
```bash
docker pull loongtall/htunnel-server:latest
docker run --rm -p 8082:8082 \
  -v $(pwd)/go/configs/server.yaml:/app/configs/server.yaml:ro \
  loongtall/htunnel-server:latest
```
  - 如果要固定版本，可把 `latest` 换成具体 tag（如 `v2.1.3`）

### 2. 配置服务端
编辑 `go/configs/server.yaml`，至少确认三项：
- `listen.addr`：服务监听地址（默认 `:8082`）
- `auth.users`：客户端登录账号密码
- `auth.users.<name>.route_cidrs`：该用户下发到客户端的网段（用于本地路由）
- `network.vip_map`：虚拟 IP 到真实目标 IP 的映射  
- `network.allow_direct_ip_fallback`：是否允许未命中 `vip_map` 时直连目标 IP（默认 `false`）
  - `false`：只允许访问白名单映射（更安全，推荐生产）
  - `true`：`vip_map` 未命中时直接访问 `dst_vip`（更灵活，但权限边界更宽）

示例：
```yaml
auth:
  users:
    alice:
      password: "pass123"
      route_cidrs:
        - "10.2.2.123/32"
network:
  allow_direct_ip_fallback: false
  vip_map:
    "10.2.2.123": "172.28.52.13"
```

说明：如果用户未配置 `route_cidrs`，服务端会默认把 `vip_map` 中的 VIP 按 `/32` 下发给客户端。

### 3. 运行 Agent
- 从 GitHub Release 页面下载对应平台压缩包并解压  
  Release: `https://github.com/llongtao/http-tunnel/releases`
- 进入解压目录，执行：
```bash
./start.sh
```
  - `start.sh` 默认优先启动 UI（需要管理员权限），若 UI 不存在则回退启动 agent
  - 当 `auth.token` 为空时，agent 会使用 `auth.username/password` 调用 `/api/agent/login` 自动获取 token 和路由配置

## 目录
- `go/configs/server.yaml`：服务端配置样例
- `go/configs/agent.yaml`：agent 配置样例

## 本地运行
```bash
cd go
go mod tidy
go run ./cmd/htunnel-server -config configs/server.yaml
```

另开一个终端运行 agent：
```bash
cd go
go run ./cmd/htunnel-agent -config configs/agent.yaml
```

## 服务端 Docker 部署
构建镜像（项目根目录）：
```bash
docker build -t htunnel-server:latest .
```

运行容器：
```bash
docker run --rm -p 8082:8082 \
  -v $(pwd)/go/configs/server.yaml:/app/configs/server.yaml:ro \
  htunnel-server:latest
```

## 原生界面（Wails）
客户端支持原生桌面界面（非浏览器页面），推荐流程：
- 填服务端地址 + 账号密码
- 点击“连接”（自动向服务端登录并获取 token）
- 自动把 `agent_id` 设置为用户名（或服务端返回值）
- 自动下发 `route_cidrs` 到本地配置
- token 未过期时，桌面端启动后会自动连接

启动桌面端：
```bash
cd go
sudo go run -tags "desktop,production" ./cmd/htunnel-ui -config configs/agent.yaml
```

macOS 如果出现 `Undefined symbols ... _OBJC_CLASS_$_UTType`，请加上：
```bash
cd go
sudo CGO_LDFLAGS='-framework UniformTypeIdentifiers' go run -tags "desktop,production" ./cmd/htunnel-ui -config configs/agent.yaml
```

或使用根目录命令：
```bash
make run-ui
```

## 发布打包（包含 tun2socks）
用户不需要单独下载 `tun2socks`。发布时可直接打一个自包含包：

```bash
cd go
./scripts/release.sh
```
Windows 可用：
```powershell
cd go
.\scripts\release.ps1
```

产物位于 `go/dist/`，每个平台目录都包含：
- `bin/htunnel-agent`
- `bin/htunnel-ui`（`INCLUDE_UI=1` 默认启用）
- `bin/tun2socks`
- `bin/tap-windows-installer.exe`（可选，若存在会被打进 windows 包并被 `start-*.cmd` 自动调用）
- `drivers/windows/tap.win10`（可选，若存在会被打进 windows 包并被 `start-*.cmd` 自动调用 `devcon + OemVista.inf`）
- `configs/agent.yaml`
- `start-agent.sh` / `start-agent.ps1` / `start-agent.cmd`
- `start-ui.sh` / `start-ui.ps1` / `start-ui.cmd`
- `start.sh` / `start.ps1` / `start.cmd`（默认优先启动 UI，不存在 UI 时回退到 agent）

示例（mac）：
```bash
cd go/dist/htunnel-agent-darwin-arm64
./start-ui.sh
```

示例（windows，管理员 PowerShell）：
```powershell
cd go\dist\htunnel-agent-windows-amd64
.\start-ui.ps1
```

说明：UI 启动即要求管理员权限（避免连接时权限不足）。
建议优先使用 `start-ui.cmd` / `start.cmd`，脚本会自动申请管理员权限，且在缺少 TAP/Wintun 时会尝试运行包内 `bin\tap-windows-installer.exe`（若已打包）。
直接双击 `bin\htunnel-ui.exe` 可能会因权限或配置错误而直接退出。
若 Windows UI 仍然启动即退出，请确认系统已安装 Microsoft Edge WebView2 Runtime（Wails 桌面框架依赖）。

注意：`tun2socks` 依赖 cgo，请在目标平台本机打包（mac 打 mac 包，windows 打 windows 包）。

## 配置说明
### 服务端映射
`go/configs/server.yaml`:
```yaml
network:
  allow_direct_ip_fallback: false
  vip_map:
    "10.2.2.123": "192.168.0.25"
```
含义：访问 `10.2.2.123:<port>` 会被转发到 `192.168.0.25:<port>`。

可选：若将 `allow_direct_ip_fallback` 设为 `true`，当 `vip_map` 中不存在目标时，服务端会直接把 `dst_vip` 当作真实 IPv4 访问。

### 认证
服务端配置 JWT 参数 + 账号：
```yaml
auth:
  jwt:
    secret: "change-me"
    issuer: "htunnel"
    audience: "htunnel-agent"
    expire_hour: 24
  users:
    alice:
      password: "pass123"
      route_cidrs:
        - "10.2.2.123/32"
```

桌面端调用 `POST /api/agent/login`：
- 入参：`username`、`password`
- 出参：`token`、`agent_id`、`ws_url`、`route_cidrs`

`ws_url` 的生成逻辑（服务端）：
1. 若配置了 `listen.public_ws_url`，直接返回该地址（推荐）
2. 否则根据请求头推断：
   - `X-Forwarded-Proto` -> `ws/wss`
   - `X-Forwarded-Host` / `Host` -> 域名
   - `X-Forwarded-Port` -> 端口（仅当 Host 不带端口时）

反向代理场景最佳实践：
- 最稳妥：在 `server.yaml` 显式配置 `listen.public_ws_url`
- 同时让代理透传 `X-Forwarded-Proto`、`X-Forwarded-Host`、`X-Forwarded-Port`
- `public_ws_url` 建议写外网可访问地址，例如：
```yaml
listen:
  public_ws_url: "wss://tunnelv2.sms-uat.gree.com:30443/websocket/message"
```

agent 仍支持直接写 token（兼容旧方式）：
```yaml
server:
  base_url: "http://127.0.0.1:8082"
auth:
  token: "<jwt-token>"
  username: "alice"
  password: "pass123"
  password_enc: "<encrypted-by-ui>"
```

说明：桌面 UI 会把密码加密后写入 `password_enc`，不再持久化明文 `password`。
当通过命令行直接启动 agent 时，若 `token` 为空，会尝试使用 `username/password` 自动登录获取 token。

可用内置工具生成测试 token：
```bash
cd go
go run ./cmd/gen-jwt -secret change-me -issuer htunnel -audience htunnel-agent -agent agent-01
```

### TUN
agent 通过启动 `tun2socks` 接入虚拟网卡流量：
```yaml
tun:
  enabled: true
  binary: ""
  name: "utun9"
  addr: "10.2.2.2"
  gateway: "10.2.2.1"
  mask: "255.255.255.0"
  route_cidrs:
    - "10.2.2.123/32"
```
Windows 注意：
- `name: "utun9"` 仅适用于 macOS 示例。
- Windows 下 `tun.name` 为空或是 `utun*` 时，会自动探测现有 TAP/Wintun 网卡并自动填充。
- 若探测不到，`start-*.cmd` 会尝试执行 `bin\tap-windows-installer.exe` 自动安装驱动（如果该文件已随包提供）。
- 可用管理员 PowerShell 查看可选网卡名：`Get-NetAdapter | Format-Table -Auto Name, InterfaceDescription, Status`
- 如果仍没有 TAP/Wintun 网卡，请先安装对应驱动（例如 OpenVPN TAP-Windows 或 Wintun）。

开发环境可直接安装到 `go/bin`（中国网络可用镜像）：
```bash
make install-tun2socks
```

或手动执行：
```bash
cd go
mkdir -p bin
GOPROXY=https://goproxy.cn,direct GOBIN=$(pwd)/bin \
  go install -tags socks github.com/eycorsican/go-tun2socks/cmd/tun2socks@v1.16.11
```

`binary` 为空时，agent 会按以下顺序自动查找：
1. 可执行文件所在目录的 `bin/tun2socks`
2. 配置文件目录下的 `bin/tun2socks`
3. 当前目录的 `bin/tun2socks`
4. 系统 `PATH`

并通过 `auto_route_commands` 配置系统路由命令（需管理员权限）。
建议同时配置 `auto_route_cleanup_commands`，agent 退出时自动清理临时路由，避免残留。

## CI
GitHub Actions 已切换为 Go 流水线：`.github/workflows/go.yml`。
新增 agent 打包流水线：`.github/workflows/package-agent.yml`，目前产物包含：
- `windows-amd64`
- `darwin-amd64`
- `darwin-arm64`

当推送 `v*` tag（如 `v2.1.0`）时，会自动把上述产物作为附件上传到 GitHub Release。

新增服务端镜像发布流水线：`.github/workflows/publish-server-image.yml`。
- 触发：推送 `v*` tag 或手动触发
- 产物：`loongtall/htunnel-server:<tagname>`（例如 `v2.1.0`）和 `loongtall/htunnel-server:latest`
- 需要仓库 Secret：`DOCKERHUB_TOKEN`（Docker Hub Access Token）
