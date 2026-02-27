//go:build desktop

package main

import (
	"embed"
	"flag"
	"log"
	"path/filepath"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed frontend/dist
var assets embed.FS

func main() {
	configPath := flag.String("config", "configs/agent.yaml", "path to agent config")
	flag.Parse()

	absPath, err := filepath.Abs(*configPath)
	if err != nil {
		log.Fatalf("invalid config path: %v", err)
	}

	app := NewApp(absPath)

	err = wails.Run(&options.App{
		Title:  "HTunnel Agent",
		Width:  640,
		Height: 460,
		MinWidth:  560,
		MinHeight: 420,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		OnStartup: app.Startup,
		Bind: []interface{}{
			app,
		},
	})
	if err != nil {
		log.Fatal(err)
	}
}
