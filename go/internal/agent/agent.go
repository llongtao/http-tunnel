package agent

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"runtime"
	"time"
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
	if s.cfg.Auth.Token == "" {
		return fmt.Errorf("auth.token is required")
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
	if runtime.GOOS == "windows" {
		cmd := exec.Command("net", "session")
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("administrator privilege required on windows")
		}
		return nil
	}

	if os.Geteuid() != 0 {
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
