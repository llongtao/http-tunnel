package agent

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os/exec"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"htunnel/go/internal/shared"
)

type Service struct {
	mu            sync.RWMutex
	cfg           Config
	configUpdated func(Config) error
}

func New(cfg Config) *Service {
	cfg.normalize()
	return &Service{cfg: cfg}
}

func (s *Service) Run(ctx context.Context) error {
	return s.RunWithReady(ctx, nil)
}

func (s *Service) RunWithReady(ctx context.Context, readyCh chan<- struct{}) error {
	if s.currentConfig().Server.URL == "" {
		return fmt.Errorf("server.url is required")
	}
	if err := s.ensureFreshToken(); err != nil {
		return err
	}
	cfg := s.currentConfig()
	if cfg.Tun.Enabled {
		if err := requireAdminPrivilege(); err != nil {
			return err
		}
		if err := validateTunConfig(runtime.GOOS, &cfg); err != nil {
			return err
		}
		s.updateConfig(func(c *Config) {
			c.Tun.Name = cfg.Tun.Name
			c.Tun.InterfaceIndex = cfg.Tun.InterfaceIndex
		})
	}
	if err := cfg.EnsureRouteCommands(runtime.GOOS); err != nil {
		return err
	}
	s.updateConfig(func(c *Config) {
		c.Tun.AutoRouteCommands = append([]string(nil), cfg.Tun.AutoRouteCommands...)
		c.Tun.AutoRouteCleanupCommands = append([]string(nil), cfg.Tun.AutoRouteCleanupCommands...)
	})

	session := NewSession(s.currentConfig())
	session.SetTokenProvider(func() string {
		return s.currentToken()
	})
	session.SetAuthRejectedHandler(func(reason string) (bool, error) {
		if !s.hasLoginCredentials() {
			return false, nil
		}
		if err := s.loginWithPassword(); err != nil {
			return false, err
		}
		log.Printf("refresh token after auth reject: %s", reason)
		return true, nil
	})
	if err := session.Start(ctx); err != nil {
		return err
	}

	if s.hasLoginCredentials() {
		go s.tokenRefreshLoop(ctx)
	}

	tunRunner := NewTunRunner(s.currentConfig())
	if err := tunRunner.Start(ctx); err != nil {
		return err
	}

	socks := NewSocksServer(s.currentConfig().Socks.Listen, session)
	errCh := make(chan error, 1)
	go func() {
		errCh <- socks.Run(ctx)
	}()

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	case <-socks.Ready():
	}
	if readyCh != nil {
		close(readyCh)
	}

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func validateTunConfig(goos string, cfg *Config) error {
	if goos != "windows" || !cfg.Tun.Enabled {
		return nil
	}
	name := strings.TrimSpace(cfg.Tun.Name)

	// On Windows, empty name (or macOS placeholder utun*) triggers auto-detection.
	if name == "" || strings.HasPrefix(strings.ToLower(name), "utun") {
		detected, err := detectWindowsTunAdapter()
		if err != nil {
			if name == "" {
				return fmt.Errorf("tun.name auto-detect failed: %w", err)
			}
			return fmt.Errorf("invalid tun.name=%q on windows and auto-detect failed: %w", name, err)
		}
		cfg.Tun.Name = detected.Name
		cfg.Tun.InterfaceIndex = detected.IfIndex
		log.Printf("auto selected windows tun adapter: %s (ifIndex=%d)", detected.Name, detected.IfIndex)
		return nil
	}

	ifIndex, err := detectWindowsTunInterfaceIndex(name)
	if err != nil {
		log.Printf("resolve tun interface index failed for %q: %v", name, err)
		return nil
	}
	cfg.Tun.InterfaceIndex = ifIndex
	return nil
}

type windowsAdapter struct {
	Name                 string `json:"Name"`
	InterfaceDescription string `json:"InterfaceDescription"`
	Status               string `json:"Status"`
	IfIndex              int    `json:"ifIndex"`
}

func fetchWindowsAdapters() ([]windowsAdapter, error) {
	cmd := exec.Command(
		"powershell",
		"-NoProfile",
		"-ExecutionPolicy", "Bypass",
		"-Command",
		"[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Get-NetAdapter -ErrorAction Stop | Select-Object Name,InterfaceDescription,Status,ifIndex | ConvertTo-Json -Compress",
	)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("query adapters failed: %w, output=%s", err, strings.TrimSpace(string(output)))
	}

	adapters, err := parseWindowsAdapters(output)
	if err != nil {
		return nil, err
	}
	return adapters, nil
}

func detectWindowsTunAdapter() (windowsAdapter, error) {
	adapters, err := fetchWindowsAdapters()
	if err != nil {
		return windowsAdapter{}, err
	}
	type candidate struct {
		adapter windowsAdapter
		score   int
	}
	var cands []candidate
	for _, adapter := range adapters {
		score, ok := windowsTunCandidateScore(adapter)
		if !ok {
			continue
		}
		cands = append(cands, candidate{adapter: adapter, score: score})
	}
	if len(cands) == 0 {
		return windowsAdapter{}, fmt.Errorf("no TAP/Wintun adapter found; install a TAP/Wintun driver or set tun.name manually (check with Get-NetAdapter)")
	}

	sort.Slice(cands, func(i, j int) bool {
		if cands[i].score != cands[j].score {
			return cands[i].score > cands[j].score
		}
		return cands[i].adapter.Name < cands[j].adapter.Name
	})
	return cands[0].adapter, nil
}

func detectWindowsTunInterfaceIndex(name string) (int, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return 0, fmt.Errorf("empty adapter name")
	}
	adapters, err := fetchWindowsAdapters()
	if err != nil {
		return 0, err
	}
	for _, adapter := range adapters {
		if strings.EqualFold(strings.TrimSpace(adapter.Name), name) {
			if adapter.IfIndex <= 0 {
				return 0, fmt.Errorf("adapter %q has invalid ifIndex=%d", adapter.Name, adapter.IfIndex)
			}
			return adapter.IfIndex, nil
		}
	}
	return 0, fmt.Errorf("adapter %q not found", name)
}

func parseWindowsAdapters(raw []byte) ([]windowsAdapter, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return nil, fmt.Errorf("query adapters returned empty output")
	}

	if raw[0] == '[' {
		var adapters []windowsAdapter
		if err := json.Unmarshal(raw, &adapters); err != nil {
			return nil, fmt.Errorf("parse adapter list failed: %w", err)
		}
		return adapters, nil
	}

	var single windowsAdapter
	if err := json.Unmarshal(raw, &single); err != nil {
		return nil, fmt.Errorf("parse adapter item failed: %w", err)
	}
	return []windowsAdapter{single}, nil
}

func windowsTunCandidateScore(adapter windowsAdapter) (int, bool) {
	name := strings.ToLower(strings.TrimSpace(adapter.Name))
	desc := strings.ToLower(strings.TrimSpace(adapter.InterfaceDescription))
	status := strings.ToLower(strings.TrimSpace(adapter.Status))
	joined := name + " " + desc

	if name == "" {
		return 0, false
	}
	switch status {
	case "disabled", "not present", "notpresent", "unknown":
		return 0, false
	}
	if strings.Contains(joined, "isatap") {
		return 0, false
	}
	statusScore := 0
	switch status {
	case "up":
		statusScore = 20
	case "disconnected", "dormant":
		statusScore = 10
	}
	if strings.Contains(joined, "wintun") {
		return 100 + statusScore, true
	}
	if strings.Contains(joined, "tap-windows") || strings.Contains(joined, "tap-win32") {
		return 90 + statusScore, true
	}
	if strings.Contains(joined, "tap adapter") || strings.Contains(joined, "openvpn tap") {
		return 80 + statusScore, true
	}
	return 0, false
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

func (s *Service) SetConfigUpdatedHandler(fn func(Config) error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.configUpdated = fn
}

type loginResponse struct {
	Token      string   `json:"token"`
	AgentID    string   `json:"agent_id"`
	WSURL      string   `json:"ws_url"`
	RouteCIDRs []string `json:"route_cidrs"`
}

func (s *Service) loginWithPassword() error {
	snapshot := s.currentConfig()
	username := strings.TrimSpace(snapshot.Auth.Username)
	password := snapshot.Auth.Password
	if username == "" || strings.TrimSpace(password) == "" {
		return fmt.Errorf("auth.token is required or set auth.username/auth.password for login")
	}

	baseURL := strings.TrimSpace(snapshot.Server.BaseURL)
	if baseURL == "" {
		baseURL = inferHTTPBaseFromWSURL(snapshot.Server.URL)
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

	timeout := time.Duration(snapshot.Server.ConnectTimeoutSec) * time.Second
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

	s.updateConfig(func(cfg *Config) {
		cfg.Auth.Token = strings.TrimSpace(lr.Token)
		if strings.TrimSpace(lr.AgentID) != "" {
			cfg.Agent.ID = strings.TrimSpace(lr.AgentID)
		} else if cfg.Agent.ID == "" {
			cfg.Agent.ID = username
		}
		if ws, err := inferWSFromHTTPBase(baseURL); err == nil {
			cfg.Server.URL = ws
		} else if strings.TrimSpace(lr.WSURL) != "" {
			cfg.Server.URL = strings.TrimSpace(lr.WSURL)
		}
		if len(lr.RouteCIDRs) > 0 {
			cfg.Tun.RouteCIDRs = append([]string(nil), lr.RouteCIDRs...)
		}
	})
	updated := s.currentConfig()
	log.Printf("login success: user=%s ws=%s routes=%d", username, updated.Server.URL, len(updated.Tun.RouteCIDRs))
	if err := s.persistConfig(updated); err != nil {
		log.Printf("persist refreshed config failed: %v", err)
	}
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

func (s *Service) currentConfig() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

func (s *Service) currentToken() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return strings.TrimSpace(s.cfg.Auth.Token)
}

func (s *Service) hasLoginCredentials() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return strings.TrimSpace(s.cfg.Auth.Username) != "" && strings.TrimSpace(s.cfg.Auth.Password) != ""
}

func (s *Service) updateConfig(fn func(*Config)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(&s.cfg)
}

func (s *Service) persistConfig(cfg Config) error {
	s.mu.RLock()
	fn := s.configUpdated
	s.mu.RUnlock()
	if fn == nil {
		return nil
	}
	return fn(cfg)
}

func (s *Service) ensureFreshToken() error {
	token := s.currentToken()
	if !tokenNeedsRefresh(token, time.Minute) {
		return nil
	}
	if !s.hasLoginCredentials() {
		if strings.TrimSpace(token) == "" {
			return fmt.Errorf("auth.token is required or set auth.username/auth.password for login")
		}
		return nil
	}
	return s.loginWithPassword()
}

func (s *Service) tokenRefreshLoop(ctx context.Context) {
	const retryDelay = 30 * time.Second
	for {
		delay := nextTokenRefreshDelay(s.currentToken(), 5*time.Minute)
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}

		if err := s.loginWithPassword(); err != nil {
			log.Printf("token refresh failed: %v", err)
			timer.Stop()
			timer = time.NewTimer(retryDelay)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			continue
		}
		log.Printf("token refreshed")
	}
}

func nextTokenRefreshDelay(token string, margin time.Duration) time.Duration {
	exp, ok := tokenExpiry(token)
	if !ok {
		return 0
	}
	ttl := time.Until(exp)
	if ttl <= 0 {
		return 0
	}
	if margin <= 0 {
		margin = time.Minute
	}
	if ttl <= margin {
		return 0
	}
	if ttl <= 2*margin {
		margin = ttl / 2
	}
	refreshAt := exp.Add(-margin)
	delay := time.Until(refreshAt)
	if delay < 0 {
		return 0
	}
	return delay
}

func tokenNeedsRefresh(token string, margin time.Duration) bool {
	return nextTokenRefreshDelay(token, margin) == 0
}

func tokenExpiry(token string) (time.Time, bool) {
	token = strings.TrimSpace(token)
	if token == "" {
		return time.Time{}, false
	}
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return time.Time{}, false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return time.Time{}, false
	}
	var claims struct {
		Exp int64 `json:"exp"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return time.Time{}, false
	}
	if claims.Exp <= 0 {
		return time.Time{}, false
	}
	return time.Unix(claims.Exp, 0), true
}
