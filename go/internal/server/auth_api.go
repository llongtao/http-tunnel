package server

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sort"
	"strings"

	"htunnel/go/internal/shared"
)

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token      string   `json:"token"`
	AgentID    string   `json:"agent_id"`
	WSURL      string   `json:"ws_url"`
	RouteCIDRs []string `json:"route_cidrs"`
}

func (s *Service) handleAgentLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}

	var req loginRequest
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request payload"})
		return
	}
	username := strings.TrimSpace(req.Username)
	password := req.Password
	if username == "" || password == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "username/password required"})
		return
	}

	account, ok := s.cfg.Auth.Users[username]
	if !ok || !matchPassword(account, password) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid credentials"})
		return
	}

	claims := shared.BuildAgentJWTClaims(
		s.cfg.Auth.JWT.Issuer,
		s.cfg.Auth.JWT.Audience,
		username,
		s.cfg.Auth.JWT.ExpireHour,
	)
	token, err := shared.GenerateHS256JWT(s.cfg.Auth.JWT.Secret, claims)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "generate token failed"})
		return
	}

	resp := loginResponse{
		Token:      token,
		AgentID:    username,
		WSURL:      resolveWSURL(r, s.cfg.Listen.PublicWSURL, s.cfg.Listen.Path, s.cfg.Listen.TLS.Enabled),
		RouteCIDRs: resolveRouteCIDRs(account, s.cfg.Network.VIPMap),
	}
	writeJSON(w, http.StatusOK, resp)
}

func matchPassword(account UserAccount, password string) bool {
	if account.Password != "" {
		return subtle.ConstantTimeCompare([]byte(account.Password), []byte(password)) == 1
	}
	if account.PasswordSHA256 != "" {
		sum := sha256.Sum256([]byte(password))
		encoded := hex.EncodeToString(sum[:])
		return subtle.ConstantTimeCompare([]byte(strings.ToLower(account.PasswordSHA256)), []byte(encoded)) == 1
	}
	return false
}

func resolveWSURL(r *http.Request, publicWSURL string, wsPath string, tlsEnabled bool) string {
	if ws := strings.TrimSpace(publicWSURL); ws != "" {
		if normalized := normalizePublicWSURL(ws, wsPath); normalized != "" {
			return normalized
		}
	}
	return inferWSURLFromRequest(r, wsPath, tlsEnabled)
}

func inferWSURLFromRequest(r *http.Request, wsPath string, tlsEnabled bool) string {
	scheme := "ws"
	if tlsEnabled {
		scheme = "wss"
	}
	if xf := firstHeaderValue(r.Header.Get("X-Forwarded-Proto")); xf != "" {
		switch strings.ToLower(xf) {
		case "https":
			scheme = "wss"
		case "http":
			scheme = "ws"
		}
	} else if r.TLS != nil {
		scheme = "wss"
	}

	host := strings.TrimSpace(firstHeaderValue(r.Header.Get("X-Forwarded-Host")))
	if host == "" {
		host = strings.TrimSpace(r.Host)
	}
	if !hostHasPort(host) {
		if p := firstHeaderValue(r.Header.Get("X-Forwarded-Port")); p != "" {
			host = net.JoinHostPort(host, p)
		}
	}
	if host == "" {
		host = "127.0.0.1:8082"
	}
	return fmt.Sprintf("%s://%s%s", scheme, host, wsPath)
}

func normalizePublicWSURL(raw string, wsPath string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	switch strings.ToLower(u.Scheme) {
	case "http":
		u.Scheme = "ws"
	case "https":
		u.Scheme = "wss"
	case "ws", "wss":
	default:
		return ""
	}
	if u.Path == "" || u.Path == "/" {
		u.Path = wsPath
	}
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return u.String()
}

func firstHeaderValue(v string) string {
	if idx := strings.Index(v, ","); idx >= 0 {
		return strings.TrimSpace(v[:idx])
	}
	return strings.TrimSpace(v)
}

func hostHasPort(host string) bool {
	if host == "" {
		return false
	}
	if _, _, err := net.SplitHostPort(host); err == nil {
		return true
	}
	// net.SplitHostPort fails for plain hostnames and ipv6-without-brackets.
	if strings.Count(host, ":") == 0 {
		return false
	}
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		return false
	}
	// host containing ':' but not split-able is treated as already having port.
	return true
}

func resolveRouteCIDRs(account UserAccount, vipMap map[string]string) []string {
	if cidrs := normalizeCIDRs(account.RouteCIDRs); len(cidrs) > 0 {
		return cidrs
	}

	raw := make([]string, 0, len(vipMap))
	for vip := range vipMap {
		addr, err := netip.ParseAddr(strings.TrimSpace(vip))
		if err != nil || !addr.Is4() {
			continue
		}
		raw = append(raw, netip.PrefixFrom(addr, 32).String())
	}
	return normalizeCIDRs(raw)
}

func normalizeCIDRs(items []string) []string {
	out := make([]string, 0, len(items))
	seen := map[string]struct{}{}
	for _, item := range items {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		pfx, err := netip.ParsePrefix(item)
		if err != nil {
			if ip := net.ParseIP(item); ip != nil && ip.To4() != nil {
				pfx = netip.PrefixFrom(netip.AddrFrom4([4]byte{ip[12], ip[13], ip[14], ip[15]}), 32)
			} else {
				continue
			}
		}
		normalized := pfx.Masked().String()
		if _, exists := seen[normalized]; exists {
			continue
		}
		seen[normalized] = struct{}{}
		out = append(out, normalized)
	}
	sort.Strings(out)
	return out
}

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
