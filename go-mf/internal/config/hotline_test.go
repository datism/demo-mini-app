package config

import "testing"

func TestHotlineApps(t *testing.T) {
	cases := map[string]string{"198": "smart-menu", "1900": "flight-booking", "userB": "", "19001": "", "+841900": "flight-booking", "sip:1900@ims.example": "flight-booking", "tel:198": "smart-menu"}
	for user, want := range cases {
		if got := HotlineApp(user); got != want {
			t.Errorf("HotlineApp(%q) = %q, want %q", user, got, want)
		}
		if IsHotline(user) != (want != "") {
			t.Errorf("IsHotline(%q) mismatch", user)
		}
	}
}
