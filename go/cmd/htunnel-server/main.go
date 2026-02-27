package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"htunnel/go/internal/server"
	"htunnel/go/internal/shared"
)

func main() {
	configPath := flag.String("config", "configs/server.yaml", "path to server config")
	flag.Parse()

	var cfg server.Config
	if err := shared.LoadYAML(*configPath, &cfg); err != nil {
		log.Fatalf("load config failed: %v", err)
	}

	svc := server.New(cfg)

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	if err := svc.Run(ctx); err != nil {
		log.Fatalf("server stopped with error: %v", err)
	}
	log.Println("server stopped")
	_ = os.Stdout.Sync()
}
