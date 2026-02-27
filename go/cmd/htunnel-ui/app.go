//go:build desktop

package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"gopkg.in/yaml.v3"

	"htunnel/go/internal/agent"
	"htunnel/go/internal/shared"
)

const authKeyFileName = ".htunnel-ui.key"

type App struct {
	ctx context.Context

	configPath string

	mu       sync.Mutex
	running  bool
	cancel   context.CancelFunc
	started  time.Time
	lastErr  string
	runCount int64
}

type ConfigView struct {
	ConfigPath string `json:"configPath"`

	ServerAddr string `json:"serverAddr"`
	Username   string `json:"username"`
	Password   string `json:"password"`
}

type StatusView struct {
	Running   bool   `json:"running"`
	StartedAt int64  `json:"startedAt"`
	LastError string `json:"lastError"`
	RunCount  int64  `json:"runCount"`
}

type LoginRequest struct {
	Server string `json:"server"`
	User   string `json:"user"`
	Pass   string `json:"pass"`
}

type loginResponse struct {
	Token      string   `json:"token"`
	AgentID    string   `json:"agent_id"`
	WSURL      string   `json:"ws_url"`
	RouteCIDRs []string `json:"route_cidrs"`
}

func NewApp(configPath string) *App {
	return &App{configPath: configPath}
}

func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
}

func (a *App) LoadConfig() (ConfigView, error) {
	cfg, err := a.readConfig()
	if err != nil {
		return ConfigView{}, err
	}
	return cfgToView(cfg, a.configPath), nil
}

func (a *App) Connect(req LoginRequest) (StatusView, error) {
	cfg, err := a.readConfig()
	if err != nil {
		return a.GetStatus(), err
	}

	if strings.TrimSpace(req.Server) != "" {
		serverBase, err := normalizeServerBaseURL(req.Server)
		if err != nil {
			return a.GetStatus(), err
		}
		cfg.Server.BaseURL = serverBase
	}
	if strings.TrimSpace(req.User) != "" {
		cfg.Auth.Username = strings.TrimSpace(req.User)
	}
	if req.Pass != "" {
		cfg.Auth.Password = req.Pass
	}

	if cfg.Server.BaseURL == "" {
		if inferred, err := normalizeServerBaseURL(cfg.Server.URL); err == nil {
			cfg.Server.BaseURL = inferred
		}
	}
	if cfg.Server.BaseURL == "" {
		return a.GetStatus(), fmt.Errorf("server address is required")
	}
	if cfg.Auth.Username == "" || cfg.Auth.Password == "" {
		return a.GetStatus(), fmt.Errorf("username/password required")
	}

	// Manual connect always refreshes server-issued runtime config (token/agent/ws/routes).
	loginResp, err := a.login(cfg.Server.BaseURL, cfg.Auth.Username, cfg.Auth.Password)
	if err != nil {
		return a.GetStatus(), err
	}
	cfg.Auth.Token = strings.TrimSpace(loginResp.Token)
	cfg.Agent.ID = cfg.Auth.Username
	if strings.TrimSpace(loginResp.AgentID) != "" {
		cfg.Agent.ID = strings.TrimSpace(loginResp.AgentID)
	}
	cfg.Server.URL = strings.TrimSpace(loginResp.WSURL)
	if cfg.Server.URL == "" {
		cfg.Server.URL = inferWSURLFromServerBase(cfg.Server.BaseURL)
	}
	cfg.Tun.RouteCIDRs = append([]string(nil), loginResp.RouteCIDRs...)

	if err := applyLocalDefaults(&cfg); err != nil {
		return a.GetStatus(), err
	}
	if err := a.saveConfig(cfg); err != nil {
		return a.GetStatus(), err
	}
	if err := a.StartAgent(); err != nil {
		return a.GetStatus(), err
	}
	return a.GetStatus(), nil
}

func (a *App) AutoConnect() (StatusView, error) {
	cfg, err := a.readConfig()
	if err != nil {
		return a.GetStatus(), err
	}
	if cfg.Server.URL == "" || !isTokenValid(cfg.Auth.Token) {
		return a.GetStatus(), nil
	}
	if err := applyLocalDefaults(&cfg); err != nil {
		return a.GetStatus(), err
	}
	if err := a.saveConfig(cfg); err != nil {
		return a.GetStatus(), err
	}
	if err := a.StartAgent(); err != nil {
		return a.GetStatus(), err
	}
	return a.GetStatus(), nil
}

func (a *App) StartAgent() error {
	a.mu.Lock()
	if a.running {
		a.mu.Unlock()
		return nil
	}
	runCtx, cancel := context.WithCancel(context.Background())
	a.cancel = cancel
	a.running = true
	a.started = time.Now()
	a.lastErr = ""
	a.runCount++
	a.mu.Unlock()

	cfg, err := a.readConfig()
	if err != nil {
		a.mu.Lock()
		a.running = false
		a.cancel = nil
		a.lastErr = err.Error()
		a.mu.Unlock()
		cancel()
		return err
	}

	svc := agent.New(cfg)
	go func() {
		err := svc.Run(runCtx)
		a.mu.Lock()
		defer a.mu.Unlock()
		a.running = false
		a.cancel = nil
		if err != nil && runCtx.Err() == nil {
			a.lastErr = err.Error()
		}
	}()
	return nil
}

func (a *App) StopAgent() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.running || a.cancel == nil {
		return nil
	}
	a.cancel()
	return nil
}

func (a *App) GetStatus() StatusView {
	a.mu.Lock()
	defer a.mu.Unlock()
	status := StatusView{
		Running:   a.running,
		LastError: a.lastErr,
		RunCount:  a.runCount,
	}
	if !a.started.IsZero() {
		status.StartedAt = a.started.Unix()
	}
	return status
}

func (a *App) readConfig() (agent.Config, error) {
	cfg := defaultAgentConfig()
	if _, err := os.Stat(a.configPath); err == nil {
		if err := shared.LoadYAML(a.configPath, &cfg); err != nil {
			return agent.Config{}, err
		}
	}
	cfg.SetConfigPath(a.configPath)
	if err := a.hydratePassword(&cfg); err != nil {
		return agent.Config{}, err
	}
	return cfg, nil
}

func defaultAgentConfig() agent.Config {
	var cfg agent.Config
	cfg.Server.URL = "ws://127.0.0.1:8082/websocket/message"
	cfg.Server.BaseURL = "http://127.0.0.1:8082"
	cfg.Server.ConnectTimeoutSec = 10
	cfg.Agent.ID = ""
	cfg.Agent.Version = "0.1.0"
	cfg.Socks.Listen = "127.0.0.1:1080"
	cfg.Tun.Enabled = true
	cfg.Tun.Name = "utun9"
	cfg.Tun.Addr = "10.2.2.2"
	cfg.Tun.Gateway = "10.2.2.1"
	cfg.Tun.Mask = "255.255.255.0"
	cfg.Tun.RouteCIDRs = []string{"10.2.2.123/32"}
	cfg.Runtime.PingIntervalSec = 20
	cfg.Runtime.WriteTimeoutSec = 30
	return cfg
}

func cfgToView(cfg agent.Config, configPath string) ConfigView {
	serverAddr := strings.TrimSpace(cfg.Server.BaseURL)
	if serverAddr == "" {
		if inferred, err := normalizeServerBaseURL(cfg.Server.URL); err == nil {
			serverAddr = inferred
		}
	}
	return ConfigView{
		ConfigPath: configPath,
		ServerAddr: serverAddr,
		Username:   cfg.Auth.Username,
		Password:   cfg.Auth.Password,
	}
}

func normalizeServerBaseURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", fmt.Errorf("server address is required")
	}
	if !strings.Contains(raw, "://") {
		raw = "http://" + raw
	}

	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("invalid server address: %w", err)
	}
	switch strings.ToLower(u.Scheme) {
	case "http", "https":
		u.Path = ""
		u.RawPath = ""
		u.RawQuery = ""
		u.Fragment = ""
		return strings.TrimRight(u.String(), "/"), nil
	case "ws":
		u.Scheme = "http"
		u.Path = ""
		u.RawPath = ""
		u.RawQuery = ""
		u.Fragment = ""
		return strings.TrimRight(u.String(), "/"), nil
	case "wss":
		u.Scheme = "https"
		u.Path = ""
		u.RawPath = ""
		u.RawQuery = ""
		u.Fragment = ""
		return strings.TrimRight(u.String(), "/"), nil
	default:
		return "", fmt.Errorf("unsupported server scheme: %s", u.Scheme)
	}
}

func inferWSURLFromServerBase(serverBase string) string {
	u, err := url.Parse(serverBase)
	if err != nil {
		return "ws://127.0.0.1:8082/websocket/message"
	}
	switch strings.ToLower(u.Scheme) {
	case "https":
		u.Scheme = "wss"
	default:
		u.Scheme = "ws"
	}
	u.Path = "/websocket/message"
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return u.String()
}

func parseLoginErrorMessage(body []byte) string {
	var obj map[string]interface{}
	if err := json.Unmarshal(body, &obj); err != nil {
		return strings.TrimSpace(string(body))
	}
	if msg, ok := obj["error"].(string); ok {
		return strings.TrimSpace(msg)
	}
	return strings.TrimSpace(string(body))
}

func (a *App) login(serverBase, username, password string) (loginResponse, error) {
	loginURL := strings.TrimRight(serverBase, "/") + "/api/agent/login"
	payload := map[string]string{
		"username": username,
		"password": password,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return loginResponse{}, fmt.Errorf("marshal login request failed: %w", err)
	}

	httpClient := &http.Client{Timeout: 10 * time.Second}
	httpReq, err := http.NewRequest(http.MethodPost, loginURL, bytes.NewReader(body))
	if err != nil {
		return loginResponse{}, fmt.Errorf("create login request failed: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := httpClient.Do(httpReq)
	if err != nil {
		return loginResponse{}, fmt.Errorf("login request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		message := parseLoginErrorMessage(respBody)
		if message == "" {
			message = fmt.Sprintf("login failed with status %d", resp.StatusCode)
		}
		return loginResponse{}, fmt.Errorf(message)
	}

	var loginResp loginResponse
	if err := json.Unmarshal(respBody, &loginResp); err != nil {
		return loginResponse{}, fmt.Errorf("parse login response failed: %w", err)
	}
	if strings.TrimSpace(loginResp.Token) == "" {
		return loginResponse{}, fmt.Errorf("server returned empty token")
	}
	return loginResp, nil
}

func applyLocalDefaults(cfg *agent.Config) error {
	if cfg.Server.URL == "" {
		cfg.Server.URL = inferWSURLFromServerBase(cfg.Server.BaseURL)
	}
	if cfg.Agent.ID == "" {
		cfg.Agent.ID = cfg.Auth.Username
	}
	if cfg.Socks.Listen == "" {
		cfg.Socks.Listen = "127.0.0.1:1080"
	}
	if cfg.Tun.Name == "" {
		cfg.Tun.Name = "utun9"
	}
	if cfg.Tun.Addr == "" {
		cfg.Tun.Addr = "10.2.2.2"
	}
	if cfg.Tun.Gateway == "" {
		cfg.Tun.Gateway = "10.2.2.1"
	}
	if cfg.Tun.Mask == "" {
		cfg.Tun.Mask = "255.255.255.0"
	}
	cfg.Tun.Enabled = true
	cfg.Tun.AutoRouteCommands = nil
	cfg.Tun.AutoRouteCleanupCommands = nil
	if err := cfg.EnsureRouteCommands(runtime.GOOS); err != nil {
		return err
	}
	return nil
}

func (a *App) saveConfig(cfg agent.Config) error {
	if err := os.MkdirAll(filepath.Dir(a.configPath), 0o755); err != nil {
		return fmt.Errorf("create config directory failed: %w", err)
	}
	if err := a.sealPassword(&cfg); err != nil {
		return err
	}
	data, err := yaml.Marshal(&cfg)
	if err != nil {
		return fmt.Errorf("marshal config failed: %w", err)
	}
	if err := os.WriteFile(a.configPath, data, 0o644); err != nil {
		return fmt.Errorf("write config failed: %w", err)
	}
	return nil
}

func (a *App) hydratePassword(cfg *agent.Config) error {
	if strings.TrimSpace(cfg.Auth.Password) != "" {
		return nil
	}
	enc := strings.TrimSpace(cfg.Auth.PasswordEnc)
	if enc == "" {
		return nil
	}
	key, err := a.loadOrCreateAuthKey()
	if err != nil {
		return fmt.Errorf("load local credential key failed: %w", err)
	}
	plain, err := decryptString(enc, key)
	if err != nil {
		return fmt.Errorf("decrypt saved password failed: %w", err)
	}
	cfg.Auth.Password = plain
	return nil
}

func (a *App) sealPassword(cfg *agent.Config) error {
	pass := cfg.Auth.Password
	if pass == "" {
		cfg.Auth.PasswordEnc = ""
		return nil
	}
	key, err := a.loadOrCreateAuthKey()
	if err != nil {
		return fmt.Errorf("load local credential key failed: %w", err)
	}
	enc, err := encryptString(pass, key)
	if err != nil {
		return fmt.Errorf("encrypt password failed: %w", err)
	}
	cfg.Auth.PasswordEnc = enc
	// Never persist plaintext password.
	cfg.Auth.Password = ""
	return nil
}

func (a *App) loadOrCreateAuthKey() ([]byte, error) {
	keyPath := filepath.Join(filepath.Dir(a.configPath), authKeyFileName)
	if data, err := os.ReadFile(keyPath); err == nil {
		if len(data) != 32 {
			return nil, fmt.Errorf("invalid key size: %d", len(data))
		}
		return data, nil
	} else if !os.IsNotExist(err) {
		return nil, err
	}

	if err := os.MkdirAll(filepath.Dir(keyPath), 0o755); err != nil {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyPath, key, 0o600); err != nil {
		return nil, err
	}
	return key, nil
}

func encryptString(plain string, key []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	encrypted := gcm.Seal(nil, nonce, []byte(plain), nil)
	out := append(nonce, encrypted...)
	return "v1:" + base64.RawURLEncoding.EncodeToString(out), nil
}

func decryptString(cipherText string, key []byte) (string, error) {
	raw := strings.TrimSpace(cipherText)
	raw = strings.TrimPrefix(raw, "v1:")
	data, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(data) < gcm.NonceSize() {
		return "", fmt.Errorf("ciphertext too short")
	}
	nonce := data[:gcm.NonceSize()]
	payload := data[gcm.NonceSize():]
	plain, err := gcm.Open(nil, nonce, payload, nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

func isTokenValid(token string) bool {
	token = strings.TrimSpace(token)
	if token == "" {
		return false
	}
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	var claims map[string]interface{}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return false
	}
	expRaw, ok := claims["exp"]
	if !ok {
		return false
	}
	expFloat, ok := expRaw.(float64)
	if !ok {
		return false
	}
	exp := int64(expFloat)
	now := time.Now().Unix()
	return exp-now > 30
}
