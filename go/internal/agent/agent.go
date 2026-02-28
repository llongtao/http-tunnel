package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"time"

	"htunnel/go/internal/shared"
)

type Service struct {
	cfg Config
}

func New(cfg Config) *Service {
	cfg.normalize()
	return &Service{cfg: cfg}
}

func (s *Service) Run(ctx context.Context) error {
	if s.cfg.Server.URL == "" {
		return fmt.Errorf("server.url is required")
	}
	if strings.TrimSpace(s.cfg.Auth.Token) == "" {
		if err := s.loginWithPassword(); err != nil {
			return err
		}
	}
	if err := s.cfg.EnsureRouteCommands(runtime.GOOS); err != nil {
		return err
	}

	if s.cfg.Tun.Enabled {
		if err := requireAdminPrivilege(); err != nil {
			return err
		}
	}

	session := NewSession(s.cfg)
	go session.Run(ctx)

	tunRunner := NewTunRunner(s.cfg)
	if err := tunRunner.Start(ctx); err != nil {
		return err
	}

	socks := NewSocksServer(s.cfg.Socks.Listen, session)
	if err := socks.Run(ctx); err != nil {
		return err
	}
	return nil
}

func requireAdminPrivilege() error {
	ok, err := shared.HasAdminPrivileges()
	if err != nil {
		return fmt.Errorf("check administrator privilege failed: %w", err)
	}
	if !ok {
		if runtime.GOOS == "windows" {
			return fmt.Errorf("administrator privilege required on windows")
		}
		return fmt.Errorf("root/admin privilege required when tun.enabled=true")
	}
	return nil
}

func WaitForSocket(ctx context.Context, addr string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		conn, err := net.DialTimeout("tcp", addr, 500*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("socket not ready: %s", addr)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func init() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
}

type loginResponse struct {
	Token      string   `json:"token"`
	AgentID    string   `json:"agent_id"`
	WSURL      string   `json:"ws_url"`
	RouteCIDRs []string `json:"route_cidrs"`
}

func (s *Service) loginWithPassword() error {
	username := strings.TrimSpace(s.cfg.Auth.Username)
	password := s.cfg.Auth.Password
	if username == "" || strings.TrimSpace(password) == "" {
		return fmt.Errorf("auth.token is required or set auth.username/auth.password for login")
	}

	baseURL := strings.TrimSpace(s.cfg.Server.BaseURL)
	if baseURL == "" {
		baseURL = inferHTTPBaseFromWSURL(s.cfg.Server.URL)
	}
	if baseURL == "" {
		return fmt.Errorf("cannot infer server base url; set server.base_url")
	}
	loginURL := strings.TrimRight(baseURL, "/") + "/api/agent/login"

	payload := map[string]string{
		"username": username,
		"password": password,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal login payload failed: %w", err)
	}

	timeout := time.Duration(s.cfg.Server.ConnectTimeoutSec) * time.Second
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	client := &http.Client{Timeout: timeout}
	req, err := http.NewRequest(http.MethodPost, loginURL, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build login request failed: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("login request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("login failed status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(respBody)))
	}

	var lr loginResponse
	if err := json.Unmarshal(respBody, &lr); err != nil {
		return fmt.Errorf("parse login response failed: %w", err)
	}
	if strings.TrimSpace(lr.Token) == "" {
		return fmt.Errorf("login response missing token")
	}

	s.cfg.Auth.Token = strings.TrimSpace(lr.Token)
	if strings.TrimSpace(lr.AgentID) != "" {
		s.cfg.Agent.ID = strings.TrimSpace(lr.AgentID)
	} else if s.cfg.Agent.ID == "" {
		s.cfg.Agent.ID = username
	}
	if ws, err := inferWSFromHTTPBase(baseURL); err == nil {
		s.cfg.Server.URL = ws
	} else if strings.TrimSpace(lr.WSURL) != "" {
		s.cfg.Server.URL = strings.TrimSpace(lr.WSURL)
	}
	if len(lr.RouteCIDRs) > 0 {
		s.cfg.Tun.RouteCIDRs = append([]string(nil), lr.RouteCIDRs...)
	}
	log.Printf("login success: user=%s ws=%s routes=%d", username, s.cfg.Server.URL, len(s.cfg.Tun.RouteCIDRs))
	return nil
}

func inferHTTPBaseFromWSURL(wsURL string) string {
	u, err := url.Parse(strings.TrimSpace(wsURL))
	if err != nil {
		return ""
	}
	switch strings.ToLower(u.Scheme) {
	case "ws":
		u.Scheme = "http"
	case "wss":
		u.Scheme = "https"
	case "http", "https":
	default:
		return ""
	}
	u.Path = ""
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return strings.TrimRight(u.String(), "/")
}

func inferWSFromHTTPBase(baseURL string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil {
		return "", err
	}
	switch strings.ToLower(u.Scheme) {
	case "http":
		u.Scheme = "ws"
	case "https":
		u.Scheme = "wss"
	case "ws", "wss":
		// keep as-is
	default:
		return "", fmt.Errorf("unsupported scheme: %s", u.Scheme)
	}
	u.Path = "/websocket/message"
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return u.String(), nil
}
