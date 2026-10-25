package main

import (
	"fmt"
	"log"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/dcsf"
	"github.com/demo/ims-dc-mf/internal/logger"
	"github.com/demo/ims-dc-mf/internal/redisclient"
)

func main() {
	logger.InitLogger("dcsf")
	log.Println("========================================")
	log.Println("  DCSF Node (Data Channel Signalling)")
	log.Println("  3GPP TS 23.228 Annex AC PoC Demo")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[DCSF] Failed to load configuration: %v", err)
	}

	redisclient.Init()

	addr := fmt.Sprintf("0.0.0.0:%d", cfg.Services.Dcsf.Port)
	server := dcsf.NewDCSFServer(addr, cfg)
	if err := server.Start(); err != nil {
		log.Fatalf("[DCSF] Server failed: %v", err)
	}
}
