package agent

import (
	"strings"
	"testing"
)

func TestBuildDarwinRouteCommands_UsesTokenMatchForIface(t *testing.T) {
	setup, cleanup, err := BuildRouteCommands("darwin", []string{"10.2.2.123/32"}, "10.2.2.2", "10.2.2.1", 0)
	if err != nil {
		t.Fatalf("BuildRouteCommands failed: %v", err)
	}
	if len(setup) != 1 || len(cleanup) != 1 {
		t.Fatalf("unexpected commands count setup=%d cleanup=%d", len(setup), len(cleanup))
	}
	cmd := setup[0]
	if !strings.Contains(cmd, `$1=="inet" && $2=="10.2.2.2" && $3=="-->" && $4=="10.2.2.1"`) {
		t.Fatalf("unexpected iface detect command: %s", cmd)
	}
	if strings.Contains(cmd, `\\.`) {
		t.Fatalf("command should not use escaped-dot regex now: %s", cmd)
	}
}

func TestEnsureRouteCommands_ReplacesLegacyDarwinPattern(t *testing.T) {
	var cfg Config
	cfg.Tun.Addr = "10.2.2.2"
	cfg.Tun.Gateway = "10.2.2.1"
	cfg.Tun.RouteCIDRs = []string{"10.2.2.123/32"}
	cfg.Tun.AutoRouteCommands = []string{
		`for i in $(seq 1 30); do IFACE=$(ifconfig | awk '/^[a-z0-9]+:/{gsub(":","",$1);i=$1} /inet 10\\.2\\.2\\.2 --> 10\\.2\\.2\\.1/{print i; exit}'); [ -n "$IFACE" ] && break; sleep 1; done`,
	}
	cfg.Tun.AutoRouteCleanupCommands = []string{`route -n delete -host 10.2.2.123 >/dev/null 2>&1 || true`}

	if err := cfg.EnsureRouteCommands("darwin"); err != nil {
		t.Fatalf("EnsureRouteCommands failed: %v", err)
	}
	if len(cfg.Tun.AutoRouteCommands) == 0 {
		t.Fatal("auto route commands should not be empty")
	}
	cmd := cfg.Tun.AutoRouteCommands[0]
	if !strings.Contains(cmd, `$1=="inet" && $2=="10.2.2.2"`) {
		t.Fatalf("legacy command was not replaced: %s", cmd)
	}
}

func TestBuildWindowsRouteCommands_WithInterfaceIndex(t *testing.T) {
	setup, cleanup, err := BuildRouteCommands("windows", []string{"10.2.2.123/32"}, "", "10.2.2.1", 17)
	if err != nil {
		t.Fatalf("BuildRouteCommands failed: %v", err)
	}
	if len(setup) != 1 || len(cleanup) != 1 {
		t.Fatalf("unexpected commands count setup=%d cleanup=%d", len(setup), len(cleanup))
	}
	if !strings.Contains(setup[0], "IF 17") {
		t.Fatalf("windows route command should include interface index, got: %s", setup[0])
	}
}
