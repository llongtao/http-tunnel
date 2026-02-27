package main

import (
	"context"
	"flag"
	"log"
	"os/signal"
	"syscall"

	"htunnel/go/internal/agent"
	"htunnel/go/internal/shared"
)

func main() {
	configPath := flag.String("config", "configs/agent.yaml", "path to agent config")
	flag.Parse()

	var cfg agent.Config
	if err := shared.LoadYAML(*configPath, &cfg); err != nil {
		log.Fatalf("load config failed: %v", err)
	}
	cfg.SetConfigPath(*configPath)

	svc := agent.New(cfg)

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	if err := svc.Run(ctx); err != nil {
		log.Fatalf("agent stopped with error: %v", err)
	}
	log.Println("agent stopped")
}
