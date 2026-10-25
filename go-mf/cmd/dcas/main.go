package main

import (
	"fmt"
	"log"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/dcas"
	"github.com/demo/ims-dc-mf/internal/logger"
	"github.com/demo/ims-dc-mf/internal/redisclient"
)

func main() {
	logger.InitLogger("dcas")
	log.Println("========================================")
	log.Println("  DCAS Node (Interactive App Server)")
	log.Println("  3GPP TS 23.228 Annex AC PoC Demo")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[DCAS] Failed to load configuration: %v", err)
	}

	redisclient.Init()

	httpAddr := fmt.Sprintf("0.0.0.0:%d", cfg.Services.Dcas.Port)
	tcpAddr := fmt.Sprintf("0.0.0.0:%d", cfg.Services.Dcas.WsPort)
	dcsfAddr := fmt.Sprintf("%s:%d", cfg.Services.Dcsf.Host, cfg.Services.Dcsf.Port)
	server := dcas.NewDCASServer(httpAddr, tcpAddr, dcsfAddr)
	if err := server.Start(); err != nil {
		log.Fatalf("[DCAS] Server failed: %v", err)
	}
}
