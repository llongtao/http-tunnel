package server

import "time"

type UserAccount struct {
	Password       string   `yaml:"password"`
	PasswordSHA256 string   `yaml:"password_sha256"`
	RouteCIDRs     []string `yaml:"route_cidrs"`
}

type Config struct {
	Listen struct {
		Addr      string `yaml:"addr"`
		Path      string `yaml:"path"`
		LoginPath string `yaml:"login_path"`
		TLS       struct {
			Enabled  bool   `yaml:"enabled"`
			CertFile string `yaml:"cert_file"`
			KeyFile  string `yaml:"key_file"`
		} `yaml:"tls"`
	} `yaml:"listen"`
	Auth struct {
		JWT struct {
			Secret       string `yaml:"secret"`
			Issuer       string `yaml:"issuer"`
			Audience     string `yaml:"audience"`
			ClockSkewSec int    `yaml:"clock_skew_sec"`
			ExpireHour   int    `yaml:"expire_hour"`
		} `yaml:"jwt"`
		Users map[string]UserAccount `yaml:"users"`
	} `yaml:"auth"`
	Network struct {
		VIPMap map[string]string `yaml:"vip_map"`
	} `yaml:"network"`
	Timeouts struct {
		DialTimeoutSec  int `yaml:"dial_timeout_sec"`
		WriteTimeoutSec int `yaml:"write_timeout_sec"`
		IdleSec         int `yaml:"idle_sec"`
		PingIntervalSec int `yaml:"ping_interval_sec"`
	} `yaml:"timeouts"`
}

func (c *Config) normalize() {
	if c.Listen.Addr == "" {
		c.Listen.Addr = ":8082"
	}
	if c.Listen.Path == "" {
		c.Listen.Path = "/websocket/message"
	}
	if c.Listen.LoginPath == "" {
		c.Listen.LoginPath = "/api/agent/login"
	}
	if c.Timeouts.DialTimeoutSec <= 0 {
		c.Timeouts.DialTimeoutSec = 10
	}
	if c.Timeouts.WriteTimeoutSec <= 0 {
		c.Timeouts.WriteTimeoutSec = 30
	}
	if c.Timeouts.IdleSec <= 0 {
		c.Timeouts.IdleSec = 120
	}
	if c.Timeouts.PingIntervalSec <= 0 {
		c.Timeouts.PingIntervalSec = 20
	}
	if c.Auth.JWT.ClockSkewSec < 0 {
		c.Auth.JWT.ClockSkewSec = 0
	}
	if c.Auth.JWT.ExpireHour <= 0 {
		c.Auth.JWT.ExpireHour = 24
	}
	if c.Auth.Users == nil {
		c.Auth.Users = map[string]UserAccount{}
	}
	if c.Network.VIPMap == nil {
		c.Network.VIPMap = map[string]string{}
	}
}

func (c *Config) dialTimeout() time.Duration {
	return time.Duration(c.Timeouts.DialTimeoutSec) * time.Second
}

func (c *Config) writeTimeout() time.Duration {
	return time.Duration(c.Timeouts.WriteTimeoutSec) * time.Second
}

func (c *Config) idleTimeout() time.Duration {
	return time.Duration(c.Timeouts.IdleSec) * time.Second
}

func (c *Config) pingInterval() time.Duration {
	return time.Duration(c.Timeouts.PingIntervalSec) * time.Second
}

func (c *Config) clockSkew() time.Duration {
	return time.Duration(c.Auth.JWT.ClockSkewSec) * time.Second
}
