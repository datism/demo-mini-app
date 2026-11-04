package config

import (
	"strings"
	"time"
)

// Hotline numbers are answered by the network (P2A): IMS-AS acts as the UAS
// and the phone auto-launches the mapped mini-app.
const (
	HotlineSmartMenu = "198"
	HotlineAirline   = "1900"
)

var hotlineApps = map[string]string{
	HotlineSmartMenu: AppIDSmartMenu,
	HotlineAirline:   AppIDFlightBooking,
}

// IsHotline reports whether user is a network-answered hotline number.
func IsHotline(user string) bool {
	_, ok := hotlineApps[normalizeHotline(user)]
	return ok
}

// HotlineApp returns the mini-app a hotline launches, or "" for normal users.
func HotlineApp(user string) string {
	return hotlineApps[normalizeHotline(user)]
}

// normalizeHotline accepts "198", "+84198", "tel:198" and "sip:198@domain".
func normalizeHotline(user string) string {
	user = strings.TrimPrefix(strings.TrimPrefix(user, "sip:"), "tel:")
	if i := strings.IndexAny(user, "@;"); i != -1 {
		user = user[:i]
	}
	return strings.TrimPrefix(user, "+84")
}

// hotlineRingDelay: how long a certified hotline rings (180) before the network answers it,
// so the caller sees the verified-caller screen first. 198 answers at once.
var hotlineRingDelay = map[string]time.Duration{
	HotlineAirline: 5 * time.Second,
}

// HotlineRingDelay returns how long user rings before it is answered (0 = answer at once).
func HotlineRingDelay(user string) time.Duration {
	return hotlineRingDelay[normalizeHotline(user)]
}
