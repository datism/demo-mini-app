package imsas

import (
	"hash/fnv"
	"log"
	"net"
	"os"
	"sort"
	"strings"
	"time"
)

func getContainerLocalIP() string {
	prefIface := os.Getenv("INTERFACE_NAME")
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	if prefIface != "" {
		for _, iface := range ifaces {
			if iface.Name == prefIface {
				if iface.Flags&net.FlagUp == 0 {
					continue
				}
				addrs, _ := iface.Addrs()
				for _, addr := range addrs {
					var ip net.IP
					switch v := addr.(type) {
					case *net.IPNet:
						ip = v.IP
					case *net.IPAddr:
						ip = v.IP
					}
					if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
						return ip.String()
					}
				}
			}
		}
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
				return ip.String()
			}
		}
	}
	return "127.0.0.1"
}

func getClusterIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, iface := range ifaces {
		if iface.Name == "eth0" {
			addrs, _ := iface.Addrs()
			for _, addr := range addrs {
				var ip net.IP
				switch v := addr.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}
				if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
					return ip.String()
				}
			}
		}
	}
	return "127.0.0.1"
}

func (b *B2BUA) isLocalIP(ip string) bool {
	if ip == "127.0.0.1" || ip == "localhost" {
		return true
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return false
	}
	for _, iface := range ifaces {
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			var ipNet *net.IPNet
			switch v := addr.(type) {
			case *net.IPNet:
				ipNet = v
			}
			if ipNet != nil && ipNet.IP.String() == ip {
				return true
			}
		}
	}
	return false
}

func startReplicaRefresher() {
	globalReplicaOnce.Do(func() {
		// Initial refresh
		refreshReplicaIPs()
		go func() {
			ticker := time.NewTicker(3 * time.Second)
			defer ticker.Stop()
			for range ticker.C {
				refreshReplicaIPs()
			}
		}()
	})
}

func refreshReplicaIPs() {
	ips, err := net.LookupIP("ims-as-headless.ims.mnc001.mcc452.3gppnetwork.org")
	if err != nil {
		return
	}
	var newIPs []string
	for _, ip := range ips {
		if ip.To4() != nil {
			newIPs = append(newIPs, ip.String())
		}
	}
	if len(newIPs) > 0 {
		globalReplicaMu.Lock()
		globalReplicaIPs = newIPs
		globalReplicaMu.Unlock()
	}
}

func (b *B2BUA) isReplicaIP(ip string) bool {
	startReplicaRefresher()
	globalReplicaMu.RLock()
	defer globalReplicaMu.RUnlock()
	for _, replicaIP := range globalReplicaIPs {
		if replicaIP == ip {
			return true
		}
	}
	return false
}

func resolveHostCached(host string) []string {
	dnsCacheMu.RLock()
	ips, exists := dnsCache[host]
	t, _ := dnsCacheTime[host]
	dnsCacheMu.RUnlock()

	if exists && time.Since(t) < dnsCacheTTL {
		return ips
	}

	lookupHost := host
	if net.ParseIP(host) == nil && !strings.HasSuffix(host, ".") {
		lookupHost = host + "."
	}
	resolvedIPs, err := net.LookupIP(lookupHost)
	if err != nil || len(resolvedIPs) == 0 {
		if exists {
			return ips
		}
		return nil
	}

	var ipStrings []string
	for _, ip := range resolvedIPs {
		if ip.To4() != nil {
			ipStrings = append(ipStrings, ip.String())
		}
	}

	dnsCacheMu.Lock()
	dnsCache[host] = ipStrings
	dnsCacheTime[host] = time.Now()
	dnsCacheMu.Unlock()

	return ipStrings
}

func resolveAndHashHost(host, callID string) string {
	if host == "" {
		return "127.0.0.1"
	}
	if net.ParseIP(host) != nil {
		return host
	}

	ipStrings := resolveHostCached(host)
	if len(ipStrings) == 0 {
		return host
	}

	if len(ipStrings) <= 1 {
		if len(ipStrings) == 1 {
			return ipStrings[0]
		}
		return host
	}
	sort.Strings(ipStrings)
	h := fnv.New32a()
	_, _ = h.Write([]byte(callID))
	hashVal := h.Sum32()
	chosenIP := ipStrings[hashVal%uint32(len(ipStrings))]
	log.Printf("[IMS-AS] Resolved '%s' to %v. Picked '%s' for CallID '%s' (hash=%d)", host, ipStrings, chosenIP, callID, hashVal)
	return chosenIP
}
