package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"regexp"
	"strings"
)

const indexHTML = `<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>IMS DC PCAP Exporter Dashboard</title>
  <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --primary: #6366f1;
      --primary-hover: #4f46e5;
      --bg: #090d16;
      --card-bg: rgba(17, 24, 39, 0.75);
      --border: rgba(255, 255, 255, 0.08);
      --text: #f3f4f6;
      --text-muted: #9ca3af;
    }
    * {
      margin: 0; padding: 0; box-sizing: border-box;
    }
    body {
      background: radial-gradient(circle at 50% 50%, #1e1b4b 0%, var(--bg) 100%);
      color: var(--text);
      font-family: 'Outfit', sans-serif;
      min-height: 100vh;
      display: flex;
      align-items: flex-start;
      justify-content: center;
      padding: 10px 20px;
    }
    .container {
      width: 100%;
      max-width: 500px;
      background: var(--card-bg);
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      border: 1px solid var(--border);
      border-radius: 24px;
      padding: 40px;
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5), 0 0 40px rgba(99, 102, 241, 0.1);
      transition: all 0.3s ease;
    }
    h1 {
      font-size: 28px;
      font-weight: 700;
      text-align: center;
      margin-bottom: 8px;
      background: linear-gradient(135deg, #a5b4fc 0%, #6366f1 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .subtitle {
      font-size: 14px;
      color: var(--text-muted);
      text-align: center;
      margin-bottom: 30px;
    }
    .form-group {
      margin-bottom: 20px;
    }
    label {
      display: block;
      font-size: 12px;
      font-weight: 600;
      margin-bottom: 8px;
      color: #cbd5e1;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    input {
      width: 100%;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 12px 16px;
      color: #fff;
      font-family: inherit;
      font-size: 15px;
      outline: none;
      transition: all 0.2s ease;
    }
    input:focus {
      border-color: var(--primary);
      box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.25);
      background: rgba(255, 255, 255, 0.08);
    }
    .btn {
      width: 100%;
      background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
      border: none;
      border-radius: 12px;
      padding: 14px;
      color: #fff;
      font-size: 15px;
      font-weight: 700;
      cursor: pointer;
      box-shadow: 0 8px 20px rgba(99, 102, 241, 0.25);
      transition: all 0.2s ease;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      margin-top: 10px;
    }
    .btn:hover {
      transform: translateY(-2px);
      box-shadow: 0 12px 24px rgba(99, 102, 241, 0.35);
    }
    .btn:active {
      transform: translateY(0);
    }
  </style>
</head>
<body>
  <div class="container">
    <h1>PCAP Exporter</h1>
    <div class="subtitle">Trích xuất gói tin mạng từ ClickHouse (SIP & HTTP)</div>

    <form id="exportForm" onsubmit="handleSubmit(event)">
      <div class="form-group">
        <label for="query">Từ khóa tìm kiếm (Call-ID, Số ĐT, IP...)</label>
        <input type="text" id="query" placeholder="Không bắt buộc nếu chọn khoảng thời gian">
      </div>

      <div class="form-group">
        <label for="start">Thời gian bắt đầu</label>
        <input type="datetime-local" id="start">
      </div>

      <div class="form-group">
        <label for="end">Thời gian kết thúc</label>
        <input type="datetime-local" id="end">
      </div>

      <button type="submit" class="btn">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        Tải xuống PCAP
      </button>
    </form>
  </div>

  <script>
    function handleSubmit(event) {
      event.preventDefault();
      
      const queryVal = document.getElementById('query').value.trim();
      const startVal = document.getElementById('start').value;
      const endVal = document.getElementById('end').value;
      
      if (!queryVal && (!startVal || !endVal)) {
        alert('Vui lòng nhập từ khóa tìm kiếm hoặc chọn khoảng thời gian!');
        return;
      }
      
      let url = '/pcap?';
      let params = [];
      
      if (queryVal) {
        params.push('query=' + encodeURIComponent(queryVal));
      }
      
      if (startVal && endVal) {
        const startSec = Math.floor(new Date(startVal).getTime() / 1000);
        const endSec = Math.floor(new Date(endVal).getTime() / 1000);
        
        if (startSec >= endSec) {
          alert('Thời gian bắt đầu phải nhỏ hơn thời gian kết thúc!');
          return;
        }
        params.push('start=' + startSec);
        params.push('end=' + endSec);
      } else if ((startVal && !endVal) || (!startVal && endVal)) {
        alert('Vui lòng nhập cả thời gian bắt đầu và kết thúc!');
        return;
      }
      
      window.location.href = url + params.join('&');
    }
  </script>
</body>
</html>`

func handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(indexHTML))
}

var (
	viaRegex     = regexp.MustCompile(`(?i)Via:\s*SIP/2.0/(?:UDP|TCP)\s*([0-9a-fA-F.:]+):([0-9]+)`)
	reqLineRegex = regexp.MustCompile(`(?i)^[A-Z]+\s*sip:(?:[^@\s]+)@([0-9a-fA-F.:]+)(?::([0-9]+))?`)
	hostRegex    = regexp.MustCompile(`(?i)Host:\s*([0-9a-fA-F.:]+)(?::([0-9]+))?`)
)

func parseIPv4(ipStr string) []byte {
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return []byte{127, 0, 0, 1}
	}
	ipv4 := ip.To4()
	if ipv4 == nil {
		return []byte{127, 0, 0, 1}
	}
	return ipv4
}

func parseSIPAddrs(payload string) (srcIP string, srcPort int, dstIP string, dstPort int) {
	srcIP = "127.0.0.1"
	srcPort = 5060
	dstIP = "127.0.0.1"
	dstPort = 5060

	// Try parsing Via for Source
	if matches := viaRegex.FindStringSubmatch(payload); len(matches) > 2 {
		srcIP = matches[1]
		fmt.Sscanf(matches[2], "%d", &srcPort)
	}

	// Try parsing Request-Line for Destination
	if matches := reqLineRegex.FindStringSubmatch(payload); len(matches) > 1 {
		dstIP = matches[1]
		if len(matches) > 2 && matches[2] != "" {
			fmt.Sscanf(matches[2], "%d", &dstPort)
		}
	}

	return
}

func parseHTTPAddrs(payload string, isRequest bool) (srcIP string, srcPort int, dstIP string, dstPort int) {
	srcIP = "127.0.0.1"
	srcPort = 80
	dstIP = "127.0.0.1"
	dstPort = 80

	var host string
	var port int = 80
	if matches := hostRegex.FindStringSubmatch(payload); len(matches) > 1 {
		host = matches[1]
		if len(matches) > 2 && matches[2] != "" {
			fmt.Sscanf(matches[2], "%d", &port)
		}
	}

	if host != "" {
		if isRequest {
			dstIP = host
			dstPort = port
		} else {
			srcIP = host
			srcPort = port
		}
	}
	return
}

func handlePCAPExport(w http.ResponseWriter, r *http.Request) {
	callID := r.URL.Query().Get("callid")
	searchVal := r.URL.Query().Get("query")
	if searchVal == "" {
		searchVal = callID
	}

	startVal := r.URL.Query().Get("start")
	endVal := r.URL.Query().Get("end")

	var startNs, endNs int64
	if startVal != "" {
		var startSec int64
		if _, err := fmt.Sscanf(startVal, "%d", &startSec); err == nil {
			startNs = startSec * 1000000000
		}
	}
	if endVal != "" {
		var endSec int64
		if _, err := fmt.Sscanf(endVal, "%d", &endSec); err == nil {
			endNs = endSec * 1000000000
		}
	}

	user := os.Getenv("CLICKHOUSE_USER")
	if user == "" {
		user = "qryn"
	}
	pass := os.Getenv("CLICKHOUSE_PASSWORD")
	if pass == "" {
		pass = "demo"
	}
	chURL := fmt.Sprintf("http://clickhouse-server:8123/?database=qryn&default_format=JSON&user=%s&password=%s", user, pass)

	var resolvedCallIDs []string
	if searchVal != "" {
		// Step 1: Resolve all call-ids matching the search keyword (within optionally provided time window)
		var step1Conds []string
		escapedSearch := strings.ReplaceAll(searchVal, "'", "''")
		step1Conds = append(step1Conds, fmt.Sprintf("(s.string LIKE '%%%%%s%%%%' OR t.labels LIKE '%%%%%s%%%%')", escapedSearch, escapedSearch))
		step1Conds = append(step1Conds, "JSONExtractString(t.labels, 'call_id') != ''")
		step1Conds = append(step1Conds, "JSONExtractString(t.labels, 'container') = 'heplify-server'")
		if startNs > 0 {
			step1Conds = append(step1Conds, fmt.Sprintf("s.timestamp_ns >= %d", startNs))
		}
		if endNs > 0 {
			step1Conds = append(step1Conds, fmt.Sprintf("s.timestamp_ns <= %d", endNs))
		}

		step1Query := fmt.Sprintf(
			"SELECT DISTINCT JSONExtractString(t.labels, 'call_id') as cid "+
				"FROM qryn.samples_v3 s "+
				"LEFT JOIN qryn.time_series t ON s.fingerprint = t.fingerprint "+
				"WHERE %s",
			strings.Join(step1Conds, " AND "),
		)

		log.Printf("[PCAP-EXPORTER] Step 1: Querying unique Call-IDs matching %q: %s", searchVal, step1Query)
		resp1, err := http.Post(chURL, "text/plain", strings.NewReader(step1Query))
		if err == nil && resp1.StatusCode == http.StatusOK {
			type CIDRow struct {
				CID string `json:"cid"`
			}
			type CIDResponse struct {
				Data []CIDRow `json:"data"`
			}
			var cidResp CIDResponse
			if err := json.NewDecoder(resp1.Body).Decode(&cidResp); err == nil {
				for _, row := range cidResp.Data {
					if row.CID != "" {
						resolvedCallIDs = append(resolvedCallIDs, row.CID)
					}
				}
			}
			resp1.Body.Close()
		}
		log.Printf("[PCAP-EXPORTER] Step 1 resolved Call-IDs: %v", resolvedCallIDs)
	}

	// Step 2: Fetch all matching packets
	var step2Conds []string
	if searchVal != "" {
		escapedSearch := strings.ReplaceAll(searchVal, "'", "''")
		orExprs := []string{fmt.Sprintf("s.string LIKE '%%%%%s%%%%'", escapedSearch)}
		for _, cid := range resolvedCallIDs {
			escapedCID := strings.ReplaceAll(cid, "'", "''")
			orExprs = append(orExprs, fmt.Sprintf("JSONExtractString(t.labels, 'call_id') = '%s'", escapedCID))
			orExprs = append(orExprs, fmt.Sprintf("s.string LIKE '%%%%%s%%%%'", escapedCID))
		}
		step2Conds = append(step2Conds, fmt.Sprintf("(%s)", strings.Join(orExprs, " OR ")))
	}
	if startNs > 0 {
		step2Conds = append(step2Conds, fmt.Sprintf("s.timestamp_ns >= %d", startNs))
	}
	if endNs > 0 {
		step2Conds = append(step2Conds, fmt.Sprintf("s.timestamp_ns <= %d", endNs))
	}
	step2Conds = append(step2Conds, "JSONExtractString(t.labels, 'container') = 'heplify-server'")

	if len(step2Conds) == 1 { // Only contains the heplify-server check
		http.Error(w, "At least one search filter (query or time range) is required", http.StatusBadRequest)
		return
	}

	step2Query := fmt.Sprintf(
		"SELECT s.timestamp_ns AS timestamp_ns, s.string AS string, t.labels AS labels "+
			"FROM qryn.samples_v3 s "+
			"LEFT JOIN qryn.time_series t ON s.fingerprint = t.fingerprint "+
			"WHERE %s "+
			"ORDER BY s.timestamp_ns ASC",
		strings.Join(step2Conds, " AND "),
	)

	log.Printf("[PCAP-EXPORTER] Step 2: Fetching packets: %s", step2Query)
	resp, err := http.Post(chURL, "text/plain", strings.NewReader(step2Query))
	if err != nil {
		http.Error(w, fmt.Sprintf("Failed to query ClickHouse: %v", err), http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		http.Error(w, fmt.Sprintf("ClickHouse error (status %d): %s", resp.StatusCode, string(body)), http.StatusInternalServerError)
		return
	}

	type CHRow struct {
		TimestampNS string `json:"timestamp_ns"`
		String      string `json:"string"`
		Labels      string `json:"labels"`
	}
	type CHResponse struct {
		Data []CHRow `json:"data"`
	}

	var chResp CHResponse
	if err := json.NewDecoder(resp.Body).Decode(&chResp); err != nil {
		http.Error(w, fmt.Sprintf("Failed to parse ClickHouse response: %v", err), http.StatusInternalServerError)
		return
	}

	if len(chResp.Data) == 0 {
		http.Error(w, "No packets found matching your query", http.StatusNotFound)
		return
	}

	filename := "export.pcap"
	if callID != "" {
		filename = fmt.Sprintf("call_%s.pcap", callID)
	} else if r.URL.Query().Get("query") != "" {
		filename = fmt.Sprintf("query_%s.pcap", r.URL.Query().Get("query"))
	} else {
		filename = "range_export.pcap"
	}

	w.Header().Set("Content-Type", "application/vnd.tcpdump.pcap")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))

	// Global PCAP Header (24 bytes)
	binary.Write(w, binary.LittleEndian, uint32(0xa1b2c3d4))
	binary.Write(w, binary.LittleEndian, uint16(2))
	binary.Write(w, binary.LittleEndian, uint16(4))
	binary.Write(w, binary.LittleEndian, int32(0))
	binary.Write(w, binary.LittleEndian, uint32(0))
	binary.Write(w, binary.LittleEndian, uint32(65535))
	binary.Write(w, binary.LittleEndian, uint32(1)) // LinkType 1 = Ethernet

	for _, row := range chResp.Data {
		var timeNs int64
		fmt.Sscanf(row.TimestampNS, "%d", &timeNs)
		sec := uint32(timeNs / 1000000000)
		usec := uint32((timeNs % 1000000000) / 1000)

		isHTTP := strings.HasPrefix(row.String, "POST ") ||
			strings.HasPrefix(row.String, "PATCH ") ||
			strings.HasPrefix(row.String, "DELETE ") ||
			strings.HasPrefix(row.String, "GET ") ||
			strings.HasPrefix(row.String, "HTTP/")

		payload := []byte(row.String)

		// Dummy Ethernet Header (14 bytes)
		eth := make([]byte, 14)
		eth[12] = 0x08
		eth[13] = 0x00 // Type: IPv4

		var ipProto byte = 17
		if isHTTP {
			ipProto = 6
		}

		srcIP := "127.0.0.1"
		srcPort := 0
		dstIP := "127.0.0.1"
		dstPort := 0

		// 1. Try to read from ClickHouse labels if available
		if row.Labels != "" {
			var labelMap map[string]string
			if err := json.Unmarshal([]byte(row.Labels), &labelMap); err == nil {
				if val, ok := labelMap["src_ip"]; ok && val != "" {
					srcIP = val
				}
				if val, ok := labelMap["dst_ip"]; ok && val != "" {
					dstIP = val
				}
				if val, ok := labelMap["src_port"]; ok && val != "" {
					fmt.Sscanf(val, "%d", &srcPort)
				}
				if val, ok := labelMap["dst_port"]; ok && val != "" {
					fmt.Sscanf(val, "%d", &dstPort)
				}
			}
		}

		// 2. Fallback to parsing payload text if IP/port are still default/empty
		if srcIP == "127.0.0.1" || srcPort == 0 || dstIP == "127.0.0.1" || dstPort == 0 {
			var fSrcIP, fDstIP string
			var fSrcPort, fDstPort int
			if isHTTP {
				isReq := !strings.HasPrefix(row.String, "HTTP/")
				fSrcIP, fSrcPort, fDstIP, fDstPort = parseHTTPAddrs(row.String, isReq)
			} else {
				fSrcIP, fSrcPort, fDstIP, fDstPort = parseSIPAddrs(row.String)
			}

			if srcIP == "127.0.0.1" {
				srcIP = fSrcIP
			}
			if srcPort == 0 {
				srcPort = fSrcPort
			}
			if dstIP == "127.0.0.1" {
				dstIP = fDstIP
			}
			if dstPort == 0 {
				dstPort = fDstPort
			}
		}

		// Fallback defaults if still zero
		if srcPort == 0 {
			if isHTTP {
				srcPort = 80
			} else {
				srcPort = 5060
			}
		}
		if dstPort == 0 {
			if isHTTP {
				dstPort = 80
			} else {
				dstPort = 5060
			}
		}

		srcIPBytes := parseIPv4(srcIP)
		dstIPBytes := parseIPv4(dstIP)

		// Dummy IP Header (20 bytes)
		var ipLen uint16
		if isHTTP {
			ipLen = uint16(20 + 20 + len(payload))
		} else {
			ipLen = uint16(20 + 8 + len(payload))
		}

		ip := []byte{
			0x45, 0x00,
			byte(ipLen >> 8), byte(ipLen & 0xff),
			0x00, 0x01,
			0x00, 0x00,
			0x40,
			ipProto,
			0x00, 0x00,
			srcIPBytes[0], srcIPBytes[1], srcIPBytes[2], srcIPBytes[3],
			dstIPBytes[0], dstIPBytes[1], dstIPBytes[2], dstIPBytes[3],
		}

		var protoHeader []byte
		if isHTTP {
			protoHeader = make([]byte, 20)
			protoHeader[0], protoHeader[1] = byte(srcPort >> 8), byte(srcPort & 0xff)
			protoHeader[2], protoHeader[3] = byte(dstPort >> 8), byte(dstPort & 0xff)
			protoHeader[12] = 0x50
			protoHeader[13] = 0x18
			protoHeader[14], protoHeader[15] = 0xff, 0xff
		} else {
			udpLen := uint16(8 + len(payload))
			protoHeader = []byte{
				byte(srcPort >> 8), byte(srcPort & 0xff),
				byte(dstPort >> 8), byte(dstPort & 0xff),
				byte(udpLen >> 8), byte(udpLen & 0xff),
				0x00, 0x00,
			}
		}

		packetLen := uint32(len(eth) + len(ip) + len(protoHeader) + len(payload))

		binary.Write(w, binary.LittleEndian, sec)
		binary.Write(w, binary.LittleEndian, usec)
		binary.Write(w, binary.LittleEndian, packetLen)
		binary.Write(w, binary.LittleEndian, packetLen)

		w.Write(eth)
		w.Write(ip)
		w.Write(protoHeader)
		w.Write(payload)
	}
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/", handleIndex)
	mux.HandleFunc("/pcap", handlePCAPExport)

	addr := "0.0.0.0:8095"
	log.Printf("[pcap-exporter] Standalone HTTP server listening on http://%s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("[pcap-exporter] Server failed: %v", err)
	}
}
