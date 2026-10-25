package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/uesim"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	log.Println("========================================")
	log.Println("  UE Simulator Node (Client Mock)")
	log.Println("  3GPP TS 26.114 PoC Demo")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[UE-SIM] Failed to load configuration: %v", err)
	}

	role := os.Getenv("UE_ROLE")         // "caller" or "callee"
	username := os.Getenv("UE_USERNAME") // "userA" or "userB"
	password := os.Getenv("UE_PASSWORD")
	target := os.Getenv("UE_TARGET")     // e.g. "userB" or "198"

	if role == "" || username == "" || password == "" {
		log.Fatalf("[UE-SIM] Missing required environment variables UE_ROLE, UE_USERNAME, UE_PASSWORD")
	}
	sim := uesim.NewUESimulator(cfg, role, username, password, target)
	if err := sim.Start(); err != nil {
		log.Fatalf("[UE-SIM] Failed to start simulator: %v", err)
	}

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Printf("[UE-%s] Shutting down UE Simulator...", username)
}
