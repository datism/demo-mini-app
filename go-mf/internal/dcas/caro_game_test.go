package dcas

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func boardWith(cells [][2]int, sym string) [CaroBoardSize][CaroBoardSize]string {
	var b [CaroBoardSize][CaroBoardSize]string
	for _, c := range cells {
		b[c[0]][c[1]] = sym
	}
	return b
}

func TestCheckCaroWin(t *testing.T) {
	s := &DCASServer{}
	cases := []struct {
		name    string
		cells   [][2]int
		last    [2]int
		wantLen int
	}{
		{"horizontal", [][2]int{{4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}}, [2]int{4, 2}, 5},
		{"vertical", [][2]int{{0, 3}, {1, 3}, {2, 3}, {3, 3}, {4, 3}}, [2]int{0, 3}, 5},
		{"diagonal touching edge", [][2]int{{4, 4}, {5, 5}, {6, 6}, {7, 7}, {8, 8}}, [2]int{8, 8}, 5},
		{"anti-diagonal", [][2]int{{0, 8}, {1, 7}, {2, 6}, {3, 5}, {4, 4}}, [2]int{2, 6}, 5},
		{"six in a row", [][2]int{{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}}, [2]int{8, 3}, 6},
		{"four is not a win", [][2]int{{2, 2}, {2, 3}, {2, 4}, {2, 5}}, [2]int{2, 5}, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := boardWith(tc.cells, "X")
			got := s.checkCaroWin(&b, tc.last[0], tc.last[1], "X")
			if len(got) != tc.wantLen {
				t.Fatalf("len(winCells) = %d, want %d (%v)", len(got), tc.wantLen, got)
			}
		})
	}
}

func TestCaroErrorMessagesAreCleanUTF8(t *testing.T) {
	if len(caroErrorMessages) != 5 {
		t.Fatalf("expected 5 caro error messages, got %d", len(caroErrorMessages))
	}
	for _, m := range caroErrorMessages {
		if !utf8.ValidString(m) {
			t.Errorf("invalid UTF-8: %q", m)
		}
		for _, bad := range []string{"Ã", "Ä", "á»"} {
			if strings.Contains(m, bad) {
				t.Errorf("message %q contains mojibake sequence %q", m, bad)
			}
		}
	}
}
