package mf

import (
	"log"
	"net"
	"sync"
)

// slidesRelay forwards the second audio/video pair of a call (the shared content,
// a=content:slides) between the two phones. The pair has its own fixed MF ports so
// it never mixes with the camera/microphone pair:
//
//	caller leg: base+1 audio, base+3 video     callee leg: base+7 audio, base+9 video
//
// (next to the existing caller +2/+4 and callee +6/+8 ports, all inside the UDP range
// the deployment publishes). A packet read on one leg's socket goes out of the other
// leg's socket to the address that leg last sent from. Phones learn each other's
// endpoint only through this latching, so the receiving phone sends a short
// keepalive datagram (under 12 bytes, never forwarded) to open its path.
//
// Like the existing global RTP sockets this serves one call at a time.
type slidesRelay struct {
	conns [2][2]*net.UDPConn // [leg][media]: leg 0 caller, 1 callee; media 0 audio, 1 video
	mu    sync.Mutex
	addrs [2][2]*net.UDPAddr // last source seen on each socket
}

// slidesPorts returns the four ports for a public base port, in [leg][media] order.
func slidesPorts(base int) [2][2]int {
	return [2][2]int{{base + 1, base + 3}, {base + 7, base + 9}}
}

// rtpMinLen is the size of an RTP header; anything shorter is a keepalive.
const rtpMinLen = 12

func newSlidesRelay(base int) *slidesRelay {
	r := &slidesRelay{}
	ports := slidesPorts(base)
	for leg := 0; leg < 2; leg++ {
		for media := 0; media < 2; media++ {
			c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: ports[leg][media]})
			if err != nil {
				log.Printf("[MF] WARNING: content-share RTP port %d not available: %v", ports[leg][media], err)
				continue
			}
			// A keyframe of a shared video arrives as hundreds of packets at once; the default
			// socket buffer (~200 KB) can overflow while the relay goroutine is descheduled.
			_ = c.SetReadBuffer(4 << 20)
			_ = c.SetWriteBuffer(4 << 20)
			r.conns[leg][media] = c
			go r.run(leg, media)
		}
	}
	return r
}

func (r *slidesRelay) close() {
	for leg := range r.conns {
		for media := range r.conns[leg] {
			if c := r.conns[leg][media]; c != nil {
				c.Close()
			}
		}
	}
}

func (r *slidesRelay) run(leg, media int) {
	conn := r.conns[leg][media]
	buf := make([]byte, 65535)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		r.mu.Lock()
		r.addrs[leg][media] = src
		peer := r.addrs[1-leg][media]
		r.mu.Unlock()
		if n < rtpMinLen || peer == nil {
			continue // keepalive, or the other phone is not known yet
		}
		if out := r.conns[1-leg][media]; out != nil {
			_, _ = out.WriteToUDP(buf[:n], peer)
		}
	}
}
