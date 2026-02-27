package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHandleAgentLoginSuccess(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Listen.Path = "/websocket/message"
	cfg.Auth.JWT.Secret = "change-me"
	cfg.Auth.JWT.Issuer = "htunnel"
	cfg.Auth.JWT.Audience = "htunnel-agent"
	cfg.Auth.Users["alice"] = UserAccount{
		Password:   "pass123",
		RouteCIDRs: []string{"10.2.2.123/32", "10.2.3.0/24"},
	}
	cfg.Network.VIPMap["10.2.2.123"] = "172.28.52.13"

	svc := New(cfg)
	body := bytes.NewBufferString(`{"username":"alice","password":"pass123"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/agent/login", body)
	req.Host = "127.0.0.1:8082"
	rec := httptest.NewRecorder()

	svc.handleAgentLogin(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 got %d body=%s", rec.Code, rec.Body.String())
	}

	var resp loginResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal response failed: %v", err)
	}
	if resp.Token == "" {
		t.Fatal("token should not be empty")
	}
	if resp.AgentID != "alice" {
		t.Fatalf("agent id mismatch: %s", resp.AgentID)
	}
	if resp.WSURL != "ws://127.0.0.1:8082/websocket/message" {
		t.Fatalf("ws url mismatch: %s", resp.WSURL)
	}
	if len(resp.RouteCIDRs) != 2 {
		t.Fatalf("route cidrs mismatch: %+v", resp.RouteCIDRs)
	}
}

func TestHandleAgentLoginUsesPublicWSURL(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Listen.Path = "/websocket/message"
	cfg.Listen.PublicWSURL = "wss://tunnelv2.sms-uat.gree.com:30443/websocket/message"
	cfg.Auth.JWT.Secret = "change-me"
	cfg.Auth.Users["alice"] = UserAccount{Password: "pass123"}

	svc := New(cfg)
	body := bytes.NewBufferString(`{"username":"alice","password":"pass123"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/agent/login", body)
	req.Host = "127.0.0.1:8082"
	rec := httptest.NewRecorder()

	svc.handleAgentLogin(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 got %d body=%s", rec.Code, rec.Body.String())
	}
	var resp loginResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.WSURL != "wss://tunnelv2.sms-uat.gree.com:30443/websocket/message" {
		t.Fatalf("unexpected ws_url: %s", resp.WSURL)
	}
}

func TestHandleAgentLoginUsesForwardedHostAndPort(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Auth.JWT.Secret = "change-me"
	cfg.Auth.Users["alice"] = UserAccount{Password: "pass123"}

	svc := New(cfg)
	body := bytes.NewBufferString(`{"username":"alice","password":"pass123"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/agent/login", body)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "tunnelv2.sms-uat.gree.com")
	req.Header.Set("X-Forwarded-Port", "30443")
	rec := httptest.NewRecorder()

	svc.handleAgentLogin(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 got %d body=%s", rec.Code, rec.Body.String())
	}
	var resp loginResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.WSURL != "wss://tunnelv2.sms-uat.gree.com:30443/websocket/message" {
		t.Fatalf("unexpected ws_url: %s", resp.WSURL)
	}
}

func TestHandleAgentLoginFallbackRouteFromVIPMap(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Auth.JWT.Secret = "change-me"
	cfg.Auth.Users["alice"] = UserAccount{Password: "pass123"}
	cfg.Network.VIPMap["10.2.2.123"] = "172.28.52.13"

	svc := New(cfg)
	body := bytes.NewBufferString(`{"username":"alice","password":"pass123"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/agent/login", body)
	req.Host = "127.0.0.1:8082"
	rec := httptest.NewRecorder()

	svc.handleAgentLogin(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 got %d", rec.Code)
	}
	var resp loginResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.RouteCIDRs) != 1 || resp.RouteCIDRs[0] != "10.2.2.123/32" {
		t.Fatalf("unexpected route cidrs: %+v", resp.RouteCIDRs)
	}
}

func TestHandleAgentLoginUnauthorized(t *testing.T) {
	var cfg Config
	cfg.normalize()
	cfg.Auth.JWT.Secret = "change-me"
	cfg.Auth.Users["alice"] = UserAccount{Password: "pass123"}
	svc := New(cfg)

	body := bytes.NewBufferString(`{"username":"alice","password":"wrong"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/agent/login", body)
	rec := httptest.NewRecorder()
	svc.handleAgentLogin(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected status 401 got %d", rec.Code)
	}
}
