package agent

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

type TunRunner struct {
	cfg Config

	mu  sync.Mutex
	cmd *exec.Cmd

	cleanupOnce sync.Once
}

func NewTunRunner(cfg Config) *TunRunner {
	return &TunRunner{cfg: cfg}
}

func (r *TunRunner) Start(ctx context.Context) error {
	if !r.cfg.Tun.Enabled {
		return nil
	}
	if r.cfg.Tun.Addr == "" || r.cfg.Tun.Gateway == "" {
		return fmt.Errorf("tun.addr and tun.gateway are required when tun.enabled=true")
	}
	tunBinary, err := r.resolveTunBinary()
	if err != nil {
		return err
	}

	args := []string{
		"-proxyServer", r.cfg.Socks.Listen,
		"-tunName", r.cfg.Tun.Name,
		"-tunAddr", r.cfg.Tun.Addr,
		"-tunGw", r.cfg.Tun.Gateway,
		"-tunMask", r.cfg.Tun.Mask,
	}
	args = append(args, r.cfg.Tun.ExtraArgs...)

	cmd := exec.CommandContext(ctx, tunBinary, args...)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start tun2socks failed: %w", err)
	}

	r.mu.Lock()
	r.cmd = cmd
	r.mu.Unlock()

	log.Printf("tun2socks started: %s %s", tunBinary, strings.Join(args, " "))

	if err := r.runRouteCommands(); err != nil {
		log.Printf("run auto route commands failed: %v", err)
	}

	go func() {
		<-ctx.Done()
		r.runRouteCleanup("context canceled")
	}()

	go func() {
		err := cmd.Wait()
		r.runRouteCleanup("tun2socks exited")
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			log.Printf("tun2socks exited with error: %v", err)
		} else {
			log.Printf("tun2socks exited")
		}
	}()

	return nil
}

func (r *TunRunner) runRouteCleanup(reason string) {
	r.cleanupOnce.Do(func() {
		if len(r.cfg.Tun.AutoRouteCleanupCommands) == 0 {
			return
		}
		log.Printf("run route cleanup: %s", reason)
		if err := r.runCommands(r.cfg.Tun.AutoRouteCleanupCommands); err != nil {
			log.Printf("run route cleanup commands failed: %v", err)
		}
	})
}

func (r *TunRunner) runRouteCommands() error {
	return r.runCommands(r.cfg.Tun.AutoRouteCommands)
}

func (r *TunRunner) resolveTunBinary() (string, error) {
	binName := "tun2socks"
	if runtime.GOOS == "windows" {
		binName = "tun2socks.exe"
	}

	candidates := make([]string, 0, 8)
	if strings.TrimSpace(r.cfg.Tun.Binary) != "" {
		userPath := strings.TrimSpace(r.cfg.Tun.Binary)
		if filepath.IsAbs(userPath) {
			candidates = append(candidates, userPath)
		} else {
			candidates = append(candidates,
				filepath.Join(r.cfg.ConfigDir(), userPath),
				userPath,
			)
		}
	}

	execPath, err := os.Executable()
	if err == nil {
		execDir := filepath.Dir(execPath)
		candidates = append(candidates, filepath.Join(execDir, "bin", binName))
	}
	candidates = append(candidates,
		filepath.Join(r.cfg.ConfigDir(), "bin", binName),
		filepath.Join(".", "bin", binName),
		binName,
	)

	seen := map[string]struct{}{}
	for _, candidate := range candidates {
		if candidate == "" {
			continue
		}
		if _, ok := seen[candidate]; ok {
			continue
		}
		seen[candidate] = struct{}{}

		if filepath.IsAbs(candidate) || strings.Contains(candidate, string(filepath.Separator)) {
			if stat, err := os.Stat(candidate); err == nil && !stat.IsDir() {
				return candidate, nil
			}
			continue
		}

		if path, err := exec.LookPath(candidate); err == nil {
			return path, nil
		}
	}

	return "", fmt.Errorf("start tun2socks failed: executable not found; tried config path, ./bin/, executable-dir/bin/, and PATH")
}

func (r *TunRunner) runCommands(commands []string) error {
	for _, command := range commands {
		command = strings.TrimSpace(command)
		if command == "" {
			continue
		}
		var cmd *exec.Cmd
		if runtime.GOOS == "windows" {
			cmd = exec.Command("cmd", "/c", command)
		} else {
			cmd = exec.Command("/bin/sh", "-c", command)
		}
		output, err := cmd.CombinedOutput()
		if err != nil {
			return fmt.Errorf("route command failed: %s, output=%s, err=%w", command, string(output), err)
		}
		log.Printf("route command ok: %s output=%s", command, strings.TrimSpace(string(output)))
	}
	return nil
}
