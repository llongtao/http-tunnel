.PHONY: build test run-server run-agent run-ui release install-tun2socks

build:
	cd go && go build ./...

test:
	cd go && go test ./...

run-server:
	cd go && go run ./cmd/htunnel-server -config configs/server.yaml

run-agent:
	cd go && go run ./cmd/htunnel-agent -config configs/agent.yaml

run-ui:
	cd go && if [ "$$(uname -s)" = "Darwin" ]; then \
		CGO_LDFLAGS="-framework UniformTypeIdentifiers" go run -tags "desktop,production" ./cmd/htunnel-ui -config configs/agent.yaml; \
	else \
		go run -tags "desktop,production" ./cmd/htunnel-ui -config configs/agent.yaml; \
	fi

release:
	cd go && ./scripts/release.sh

install-tun2socks:
	cd go && mkdir -p bin && GOPROXY="$${GOPROXY:-https://goproxy.cn,direct}" GOBIN="$$(pwd)/bin" \
		go install -tags socks github.com/eycorsican/go-tun2socks/cmd/tun2socks@v1.16.11
