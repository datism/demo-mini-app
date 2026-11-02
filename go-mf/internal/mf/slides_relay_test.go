package mf

import (
	"net"
	"strings"
	"testing"
	"time"
)

func udpClient(t *testing.T) *net.UDPConn {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func rtpPacket(payload byte) []byte {
	p := make([]byte, 20)
	p[0] = 0x80
	p[1] = 97
	p[12] = payload
	return p
}

func TestSlidesPorts(t *testing.T) {
	if got := slidesPorts(40000); got != [2][2]int{{40001, 40003}, {40007, 40009}} {
		t.Fatalf("got %v", got)
	}
}

// The sender (caller leg) streams to the MF; the receiver's keepalive opens its
// path; the MF delivers the sender's RTP to the receiver's address and drops the keepalive.
func TestSlidesRelayForwardsSenderToReceiver(t *testing.T) {
	base := 47100
	r := newSlidesRelay(base)
	defer r.close()
	ports := slidesPorts(base)
	for media := 0; media < 2; media++ {
		sender, receiver := udpClient(t), udpClient(t)
		callerMF := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: ports[0][media]}
		calleeMF := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: ports[1][media]}

		// A packet before the receiver is known goes nowhere.
		sender.WriteToUDP(rtpPacket(1), callerMF)
		time.Sleep(100 * time.Millisecond)
		// Receiver keepalive (1 byte): learns its address, is not forwarded to the sender.
		receiver.WriteToUDP([]byte{0}, calleeMF)
		time.Sleep(100 * time.Millisecond)
		sender.WriteToUDP(rtpPacket(2), callerMF)

		buf := make([]byte, 100)
		receiver.SetReadDeadline(time.Now().Add(time.Second))
		n, from, err := receiver.ReadFromUDP(buf)
		if err != nil || n != 20 || buf[12] != 2 {
			t.Fatalf("media %d: receiver got n=%d payload=%d err=%v", media, n, buf[12], err)
		}
		if from.Port != ports[1][media] {
			t.Fatalf("media %d: packet must come from the callee-leg port %d, came from %d", media, ports[1][media], from.Port)
		}
		sender.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
		if n, _, err := sender.ReadFromUDP(buf); err == nil {
			t.Fatalf("media %d: keepalive must not be forwarded to the sender (%d bytes)", media, n)
		}
	}
}

func TestSlidesRelayWorksWhenCalleeIsTheSender(t *testing.T) {
	base := 47200
	r := newSlidesRelay(base)
	defer r.close()
	ports := slidesPorts(base)
	sender, receiver := udpClient(t), udpClient(t)
	receiver.WriteToUDP([]byte{0}, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: ports[0][1]})
	time.Sleep(100 * time.Millisecond)
	sender.WriteToUDP(rtpPacket(7), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: ports[1][1]})
	buf := make([]byte, 100)
	receiver.SetReadDeadline(time.Now().Add(time.Second))
	if n, _, err := receiver.ReadFromUDP(buf); err != nil || n != 20 || buf[12] != 7 {
		t.Fatalf("caller receiver got n=%d err=%v", n, err)
	}
}

// The shared-content pair comes after the camera pair and must not replace its ports.
func TestParseSDPMediaKeepsCameraPortsWhenSlidesPairFollows(t *testing.T) {
	sdp := "v=0\r\no=- 1 1 IN IP4 10.0.0.9\r\ns=-\r\nc=IN IP4 10.0.0.9\r\nt=0 0\r\n" +
		"m=audio 41000 RTP/AVP 8\r\nm=video 41002 RTP/AVP 97\r\n" +
		"m=video 42000 RTP/AVP 97\r\na=content:slides\r\nm=audio 42002 RTP/AVP 8\r\na=content:slides\r\n"
	info := parseSDPMedia(sdp)
	if info.AudioPort != 41000 || info.VideoPort != 41002 {
		t.Fatalf("camera ports replaced by the slides pair: %+v", info)
	}
	_, audio, video, _ := splitSDPToMediaBlocks(sdp)
	if !strings.Contains(audio, "41000") || !strings.Contains(video, "41002") {
		t.Fatalf("camera blocks replaced by the slides pair: %q / %q", audio, video)
	}
}
