package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/logger"
	"github.com/demo/ims-dc-mf/internal/redisclient"
)

func main() {
	logger.InitLogger("ims-as")
	log.Println("========================================")
	log.Println("  IMS AS Node (Application Server B2BUA)")
	log.Println("  3GPP TS 23.228 Annex AC PoC Demo")
	log.Println("========================================")

	// Load Shared config
	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[IMS-AS] Failed to load configuration: %v", err)
	}

	redisclient.Init()

	b2bua := imsas.NewB2BUA(cfg)
	if err := b2bua.Start(); err != nil {
		log.Fatalf("[IMS-AS] Failed to start B2BUA: %v", err)
	}

	// Handle graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[IMS-AS] Shutting down IMS-AS B2BUA...")
}
