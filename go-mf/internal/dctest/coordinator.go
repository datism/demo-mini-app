package dctest

import (
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/uesim"
)

type UserCredentials struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type StepConfig struct {
	Action    string `json:"action"`
	TimeoutMS int    `json:"timeout_ms,omitempty"`
	DelayMS   int    `json:"delay_ms,omitempty"`
	StreamID  uint16 `json:"stream_id,omitempty"`
	Payload   string `json:"payload,omitempty"`
	Role      string `json:"role,omitempty"`
}

type ScenarioConfig struct {
	Name    string          `json:"name"`
	Caller  UserCredentials `json:"caller"`
	Callee  UserCredentials `json:"callee"`
	Expires int             `json:"expires,omitempty"`
	Steps   []StepConfig    `json:"steps"`
}

type ConfigSchema struct {
	Scenarios []ScenarioConfig `json:"scenarios"`
}

type DataMsg struct {
	StreamID    uint16
	Subprotocol string
	Payload     string
	ReceivedAt  time.Time
}

type TestStats struct {
	TotalAssertions int
	Passed          int
	Failed          int
	Duration        time.Duration
}

type TestCoordinator struct {
	cfg          *config.Config
	caller       *uesim.UESimulator
	callee       *uesim.UESimulator
	stats        TestStats
	receivedMsgs chan DataMsg
	mu           sync.Mutex
	sipStateChan chan string
	dcStateChan  chan uint16
}

func NewTestCoordinator(cfg *config.Config) *TestCoordinator {
	return &TestCoordinator{
		cfg:          cfg,
		receivedMsgs: make(chan DataMsg, 100),
		sipStateChan: make(chan string, 100),
		dcStateChan:  make(chan uint16, 100),
	}
}

func (tc *TestCoordinator) RecordPass() {
	tc.mu.Lock()
	tc.stats.TotalAssertions++
	tc.stats.Passed++
	tc.mu.Unlock()
}

func (tc *TestCoordinator) RecordFail() {
	tc.mu.Lock()
	tc.stats.TotalAssertions++
	tc.stats.Failed++
	tc.mu.Unlock()
}

func (tc *TestCoordinator) GetStats() TestStats {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	return tc.stats
}
