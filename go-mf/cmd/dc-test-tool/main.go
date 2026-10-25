package main

import (
	"flag"
	"fmt"
	"log"
	"os"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/dctest"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	fmt.Println("=================================================================")
	fmt.Println("   IMS DATA CHANNEL END-TO-END AUTOMATED SIMULATION TEST TOOL    ")
	fmt.Println("=================================================================")

	configPath := flag.String("config", "deploy/test_scenarios.json", "path to scenario JSON file")
	flag.Parse()

	if *configPath == "" {
		log.Fatalf("[ERROR] Config file path must be specified")
	}

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[ERROR] Failed to load microservice system config: %v", err)
	}

	stats, err := dctest.RunTestSuites(cfg, *configPath)
	if err != nil {
		log.Fatalf("[TEST RUNNER ERROR] Failed to execute test suites: %v", err)
	}

	fmt.Println("\n=================================================================")
	fmt.Println("                      TESTING SUITE SUMMARY REPORT               ")
	fmt.Println("=================================================================")
	fmt.Printf("  Assertions Passed:    %d/%d\n", stats.Passed, stats.TotalAssertions)
	fmt.Printf("  Assertions Failed:    %d\n", stats.Failed)
	fmt.Printf("  Total Run Duration:   %s\n", stats.Duration)
	fmt.Println("=================================================================")

	if stats.Failed > 0 {
		fmt.Println("  [RESULT] ❌ TEST SUITE FAILED")
		os.Exit(1)
	} else {
		fmt.Println("  [RESULT] ✅ ALL SCENARIOS PASSED SUCCESSFULLY")
		os.Exit(0)
	}
}
