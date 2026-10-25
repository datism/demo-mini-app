package logger

import (
	"encoding/json"
	"log"
	"os"
	"regexp"
	"strings"
	"time"
)

type LogEntry struct {
	Timestamp string `json:"timestamp"`
	Level     string `json:"level"`
	Service   string `json:"service"`
	Module    string `json:"module"`
	PodIP     string `json:"pod_ip,omitempty"`
	PodName   string `json:"pod_name,omitempty"`
	NodeName  string `json:"node_name,omitempty"`
	CallID    string `json:"call_id,omitempty"`
	Message   string `json:"message"`
}

type JSONLogWriter struct {
	ServiceName string
	PodIP       string
	PodName     string
	NodeName    string
	MinLevelVal int
}

var (
	callIDRegex = regexp.MustCompile(`(?i)(?:call[-_]?id|session[-_]?id|session)=(?P<val>[a-zA-Z0-9_\-@\.]+)`)
	imsdcRegex  = regexp.MustCompile(`[a-zA-Z0-9_\-]+@imsdc`)

	levelSeverity = map[string]int{
		"DEBUG": 1,
		"INFO":  2,
		"WARN":  3,
		"ERROR": 4,
	}
)

func InitLogger(serviceName string) {
	podIP := os.Getenv("POD_IP")
	if podIP == "" {
		podIP = os.Getenv("PUBLIC_IP") // Fallback
	}
	podName := os.Getenv("POD_NAME")
	if podName == "" {
		podName, _ = os.Hostname()
	}
	nodeName := os.Getenv("NODE_NAME")
	if nodeName == "" {
		nodeName = "docker-desktop"
	}

	minLevelStr := strings.ToUpper(os.Getenv("LOG_LEVEL"))
	minLevelVal, ok := levelSeverity[minLevelStr]
	if !ok {
		minLevelVal = 2 // Default to INFO
	}

	writer := &JSONLogWriter{
		ServiceName: serviceName,
		PodIP:       podIP,
		PodName:     podName,
		NodeName:    nodeName,
		MinLevelVal: minLevelVal,
	}

	// Disable Go default log timestamp prefix
	log.SetFlags(0)
	log.SetOutput(writer)
}

func (w *JSONLogWriter) Write(p []byte) (n int, err error) {
	rawMsg := string(p)
	// Strip trailing newline
	if len(rawMsg) > 0 && rawMsg[len(rawMsg)-1] == '\n' {
		rawMsg = rawMsg[:len(rawMsg)-1]
	}

	// Determine log level
	level := "INFO"
	lowerMsg := strings.ToLower(rawMsg)
	if strings.Contains(lowerMsg, "error") || strings.Contains(lowerMsg, "failed") || strings.Contains(lowerMsg, "panic") || strings.Contains(lowerMsg, "fatal") {
		level = "ERROR"
	} else if strings.Contains(lowerMsg, "warn") || strings.Contains(lowerMsg, "warning") {
		level = "WARN"
	} else if strings.Contains(lowerMsg, "debug") {
		level = "DEBUG"
	}

	// Filter out logs below configured log level
	if levelSeverity[level] < w.MinLevelVal {
		return len(p), nil
	}

	// Extract module name if format starts with [TAG]
	module := "system"
	msg := rawMsg
	if strings.HasPrefix(rawMsg, "[") {
		idx := strings.Index(rawMsg, "]")
		if idx > 0 {
			module = strings.ToLower(rawMsg[1:idx])
			msg = strings.TrimSpace(rawMsg[idx+1:])
		}
	}

	// Extract CallID / SessionID from log message
	callID := ""
	if match := callIDRegex.FindStringSubmatch(rawMsg); len(match) > 1 {
		callID = match[1]
	} else if match := imsdcRegex.FindString(rawMsg); match != "" {
		callID = match
	}

	// Build JSON log entry
	entry := LogEntry{
		Timestamp: time.Now().UTC().Format(time.RFC3339Nano),
		Level:     level,
		Service:   w.ServiceName,
		Module:    module,
		PodIP:     w.PodIP,
		PodName:   w.PodName,
		NodeName:  w.NodeName,
		CallID:    callID,
		Message:   msg,
	}

	data, err := json.Marshal(entry)
	if err != nil {
		return os.Stdout.Write(p)
	}

	// Write to stdout with newline
	return os.Stdout.Write(append(data, '\n'))
}
