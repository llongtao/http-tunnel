package agent

import "path/filepath"

type Config struct {
	Server struct {
		URL               string `yaml:"url"`
		BaseURL           string `yaml:"base_url"`
		ConnectTimeoutSec int    `yaml:"connect_timeout_sec"`
		Path              string `yaml:"path"`
	} `yaml:"server"`
	Auth struct {
		Token       string `yaml:"token"`
		Username    string `yaml:"username"`
		Password    string `yaml:"password"`
		PasswordEnc string `yaml:"password_enc"`
	} `yaml:"auth"`
	Agent struct {
		ID      string `yaml:"id"`
		Version string `yaml:"version"`
	} `yaml:"agent"`
	Socks struct {
		Listen string `yaml:"listen"`
	} `yaml:"socks"`
	Tun struct {
		Enabled                  bool     `yaml:"enabled"`
		Binary                   string   `yaml:"binary"`
		Name                     string   `yaml:"name"`
		InterfaceIndex           int      `yaml:"interface_index"`
		Addr                     string   `yaml:"addr"`
		Gateway                  string   `yaml:"gateway"`
		Mask                     string   `yaml:"mask"`
		RouteCIDRs               []string `yaml:"route_cidrs"`
		ExtraArgs                []string `yaml:"extra_args"`
		AutoRouteCommands        []string `yaml:"auto_route_commands"`
		AutoRouteCleanupCommands []string `yaml:"auto_route_cleanup_commands"`
	} `yaml:"tun"`
	Runtime struct {
		PingIntervalSec int `yaml:"ping_interval_sec"`
		WriteTimeoutSec int `yaml:"write_timeout_sec"`
	} `yaml:"runtime"`

	configDir string `yaml:"-"`
}

func (c *Config) SetConfigPath(configPath string) {
	if configPath == "" {
		c.configDir = "."
		return
	}
	absPath, err := filepath.Abs(configPath)
	if err != nil {
		c.configDir = "."
		return
	}
	c.configDir = filepath.Dir(absPath)
}

func (c *Config) ConfigDir() string {
	if c.configDir == "" {
		return "."
	}
	return c.configDir
}

func (c *Config) normalize() {
	if c.Server.ConnectTimeoutSec <= 0 {
		c.Server.ConnectTimeoutSec = 10
	}
	if c.Agent.ID == "" {
		c.Agent.ID = "agent-unknown"
	}
	if c.Agent.Version == "" {
		c.Agent.Version = "0.1.0"
	}
	if c.Socks.Listen == "" {
		c.Socks.Listen = "127.0.0.1:1080"
	}
	if c.Tun.Mask == "" {
		c.Tun.Mask = "255.255.255.0"
	}
	if c.Runtime.PingIntervalSec <= 0 {
		c.Runtime.PingIntervalSec = 20
	}
	if c.Runtime.WriteTimeoutSec <= 0 {
		c.Runtime.WriteTimeoutSec = 30
	}
}
