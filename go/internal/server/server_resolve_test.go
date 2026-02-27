package server

import "testing"

func TestResolveTargetIP_Mapped(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Network.VIPMap["10.2.2.123"] = "172.28.52.13"
	s := &wsSession{cfg: cfg}

	got, err := s.resolveTargetIP("10.2.2.123")
	if err != nil {
		t.Fatalf("resolveTargetIP failed: %v", err)
	}
	if got != "172.28.52.13" {
		t.Fatalf("unexpected ip: %s", got)
	}
}

func TestResolveTargetIP_DirectFallbackDisabled(t *testing.T) {
	var cfg Config
	cfg.normalize()
	s := &wsSession{cfg: cfg}

	if _, err := s.resolveTargetIP("10.8.0.10"); err == nil {
		t.Fatal("expected error when direct fallback is disabled")
	}
}

func TestResolveTargetIP_DirectFallbackEnabled(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Network.AllowDirectIPFallback = true
	s := &wsSession{cfg: cfg}

	got, err := s.resolveTargetIP("10.8.0.10")
	if err != nil {
		t.Fatalf("resolveTargetIP failed: %v", err)
	}
	if got != "10.8.0.10" {
		t.Fatalf("unexpected ip: %s", got)
	}
}
