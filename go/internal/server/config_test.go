package server

import "testing"

func TestConfigNormalize(t *testing.T) {
	var cfg Config
	cfg.normalize()
	if cfg.Listen.Addr == "" || cfg.Listen.Path == "" {
		t.Fatal("listen defaults not applied")
	}
	if cfg.Listen.LoginPath == "" {
		t.Fatal("login path default not applied")
	}
	if cfg.Timeouts.DialTimeoutSec <= 0 || cfg.Timeouts.PingIntervalSec <= 0 {
		t.Fatal("timeout defaults not applied")
	}
	if cfg.Auth.JWT.ExpireHour <= 0 {
		t.Fatal("jwt expire default not applied")
	}
	if cfg.Auth.Users == nil {
		t.Fatal("auth users should be initialized")
	}
	if cfg.Network.VIPMap == nil {
		t.Fatal("vip map should be initialized")
	}
}
