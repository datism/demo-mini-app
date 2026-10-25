package dcas

import (
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/gorilla/websocket"
)

const CaroBoardSize = 9
const CaroWinCount = 5

type CaroGame struct {
	Board     [CaroBoardSize][CaroBoardSize]string
	Turn      string // "X" or "O"
	GameOver  bool
	Winner    string
	SessionID string
	ScoreX    int
	ScoreO    int
	Status    string
	PlayerX   string
	PlayerO   string
	MoveCount int
	WinCells  [][2]int
}

func (s *DCASServer) handleCaroMessage(sessionID string, data []byte, conn *websocket.Conn) {
	var msg struct {
		Service string `json:"service"`
		Action  string `json:"action"`
		Row     int    `json:"row"`
		Col     int    `json:"col"`
	}
	if err := json.Unmarshal(data, &msg); err != nil {
		log.Printf("[DCAS-CARO] Failed to parse caro message from %s: %v", sessionID, err)
		return
	}

	s.mu.RLock()
	callID := s.sessionCalls[sessionID]
	s.mu.RUnlock()

	if callID == "" {
		log.Printf("[DCAS-CARO] Session %s has no callID", sessionID)
		return
	}

	log.Printf("[DCAS-CARO] Session=%s CallID=%s Action=%s Row=%d Col=%d", sessionID, callID, msg.Action, msg.Row, msg.Col)

	s.caroMu.Lock()
	defer s.caroMu.Unlock()

	switch msg.Action {
	case "new_game":
		s.caroNewGame(callID, sessionID)
	case "move":
		s.caroMakeMove(callID, sessionID, msg.Row, msg.Col)
	case "sync_state":
		s.caroSyncState(callID, sessionID)
	default:
		log.Printf("[DCAS-CARO] Unknown caro action: %s", msg.Action)
	}
}

func (s *DCASServer) getAllCallSessions(callID string) []string {
	sessions := getSessionsFromRedis(callID)
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, sid := range s.callSessions[callID] {
		if !containsString(sessions, sid) {
			sessions = append(sessions, sid)
		}
	}
	for sid, cid := range s.sessionCalls {
		if cid == callID && !containsString(sessions, sid) {
			sessions = append(sessions, sid)
		}
	}
	return sessions
}

func (s *DCASServer) caroNewGame(callID, triggerSessionID string) {
	sessions := s.getAllCallSessions(callID)

	// Preserve scores and players from previous game
	var prevScoreX, prevScoreO int
	var prevPlayerX, prevPlayerO string
	prevGameVal, err := getFromRedis("caro:game:" + callID)
	if err == nil && prevGameVal != "" {
		var prevGame CaroGame
		if err := json.Unmarshal([]byte(prevGameVal), &prevGame); err == nil {
			prevScoreX = prevGame.ScoreX
			prevScoreO = prevGame.ScoreO
			prevPlayerX = prevGame.PlayerX
			prevPlayerO = prevGame.PlayerO
		}
	}

	game := &CaroGame{
		Turn:   "X",
		Status: "playing",
		ScoreX: prevScoreX,
		ScoreO: prevScoreO,
	}

	game.PlayerX = prevPlayerX
	game.PlayerO = prevPlayerO

	// Clean up stale players
	if game.PlayerX != "" && !containsString(sessions, game.PlayerX) {
		game.PlayerX = ""
	}
	if game.PlayerO != "" && !containsString(sessions, game.PlayerO) {
		game.PlayerO = ""
	}

	// Always ensure triggerSessionID is in the game
	if game.PlayerX == "" && game.PlayerO != triggerSessionID {
		game.PlayerX = triggerSessionID
	} else if game.PlayerO == "" && game.PlayerX != triggerSessionID {
		game.PlayerO = triggerSessionID
	}

	// Assign any remaining empty slots to other active sessions
	for _, sid := range sessions {
		if game.PlayerX == "" && sid != game.PlayerO {
			game.PlayerX = sid
		} else if game.PlayerO == "" && sid != game.PlayerX {
			game.PlayerO = sid
		}
	}

	gameBytes, _ := json.Marshal(game)
	setToRedis("caro:game:"+callID, string(gameBytes))

	log.Printf("[DCAS-CARO] New game created in Redis for CallID=%s. PlayerX=%s, PlayerO=%s, Score: X=%d O=%d",
		callID, game.PlayerX, game.PlayerO, game.ScoreX, game.ScoreO)

	// Send game_start to each player with their symbol
	for _, sid := range sessions {
		symbol := "X"
		if sid == game.PlayerO {
			symbol = "O"
		} else if sid != game.PlayerX {
			continue // Don't send if not in the game yet
		}
		resp := map[string]interface{}{
			"service":     "caro",
			"status":      "game_start",
			"your_symbol": symbol,
			"next_turn":   "X",
			"board":       s.boardToSlice(&game.Board),
			"score_x":     game.ScoreX,
			"score_o":     game.ScoreO,
		}
		s.sendToSession(sid, "1000", resp)
		s.sendToSession(sid, "1001", resp)
	}
}

func (s *DCASServer) checkLateJoinCaro(callID, sessionID string) {
	// Wait a brief moment to ensure Redis is written and updated
	time.Sleep(50 * time.Millisecond)

	s.caroMu.Lock()
	defer s.caroMu.Unlock()

	gameVal, err := getFromRedis("caro:game:" + callID)
	if err != nil || gameVal == "" {
		return
	}

	var game CaroGame
	if err := json.Unmarshal([]byte(gameVal), &game); err != nil {
		return
	}

	activeSessions := s.getAllCallSessions(callID)
	changed := false

	if game.PlayerX != "" && !containsString(activeSessions, game.PlayerX) {
		game.PlayerX = ""
		changed = true
	}
	if game.PlayerO != "" && !containsString(activeSessions, game.PlayerO) {
		game.PlayerO = ""
		changed = true
	}

	if game.PlayerX == "" && game.PlayerO != sessionID {
		game.PlayerX = sessionID
		changed = true
	} else if game.PlayerO == "" && game.PlayerX != sessionID {
		game.PlayerO = sessionID
		changed = true
	}

	if changed {
		gameBytes, _ := json.Marshal(&game)
		setToRedis("caro:game:"+callID, string(gameBytes))
		log.Printf("[DCAS-CARO] Late-join rebind. PlayerX=%s, PlayerO=%s for CallID=%s", game.PlayerX, game.PlayerO, callID)

		// Broadcast update to all sessions
		for _, sid := range activeSessions {
			symbol := "X"
			if sid == game.PlayerO {
				symbol = "O"
			} else if sid != game.PlayerX {
				continue
			}
			resp := map[string]interface{}{
				"service":     "caro",
				"status":      "game_start",
				"your_symbol": symbol,
				"next_turn":   game.Turn,
				"board":       s.boardToSlice(&game.Board),
				"score_x":     game.ScoreX,
				"score_o":     game.ScoreO,
			}
			s.sendToSession(sid, "1000", resp)
			s.sendToSession(sid, "1001", resp)
		}
	}
}

func (s *DCASServer) caroMakeMove(callID, sessionID string, row, col int) {
	gameVal, err := getFromRedis("caro:game:" + callID)
	if err != nil || gameVal == "" {
		// Auto-create game if not exists
		s.caroNewGame(callID, sessionID)
		return
	}

	var game CaroGame
	if err := json.Unmarshal([]byte(gameVal), &game); err != nil {
		s.caroNewGame(callID, sessionID)
		return
	}

	if game.Status != "playing" {
		s.sendCaroError(sessionID, "Game Ä‘Ã£ káº¿t thÃºc. Báº¥m 'VÃ¡n má»›i' Ä‘á»ƒ chÆ¡i láº¡i.")
		return
	}

	// Determine player symbol
	playerSymbol := ""
	if sessionID == game.PlayerX {
		playerSymbol = "X"
	} else if sessionID == game.PlayerO {
		playerSymbol = "O"
	} else {
		// Assign unassigned slot
		if game.PlayerX == "" {
			game.PlayerX = sessionID
			playerSymbol = "X"
		} else if game.PlayerO == "" {
			game.PlayerO = sessionID
			playerSymbol = "O"
		} else {
			s.sendCaroError(sessionID, "BÃ n cá» Ä‘Ã£ Ä‘á»§ 2 ngÆ°á»i chÆ¡i.")
			return
		}
	}

	// Validate turn
	if game.Turn != playerSymbol {
		s.sendCaroError(sessionID, "ChÆ°a Ä‘áº¿n lÆ°á»£t cá»§a báº¡n.")
		return
	}

	// Validate position
	if row < 0 || row >= CaroBoardSize || col < 0 || col >= CaroBoardSize {
		s.sendCaroError(sessionID, "Vá»‹ trÃ­ khÃ´ng há»£p lá»‡.")
		return
	}
	if game.Board[row][col] != "" {
		s.sendCaroError(sessionID, "Ã” nÃ y Ä‘Ã£ cÃ³ quÃ¢n.")
		return
	}

	// Place the move
	game.Board[row][col] = playerSymbol
	game.MoveCount++

	// Check win
	winCells := s.checkCaroWin(&game.Board, row, col, playerSymbol)
	winner := ""
	draw := false
	if len(winCells) > 0 {
		game.Status = "win_" + playerSymbol
		game.WinCells = winCells
		winner = playerSymbol
		// Increment winner's score
		if playerSymbol == "X" {
			game.ScoreX++
		} else {
			game.ScoreO++
		}
	} else if game.MoveCount >= CaroBoardSize*CaroBoardSize {
		game.Status = "draw"
		draw = true
	}

	// Switch turn
	nextTurn := "O"
	if playerSymbol == "O" {
		nextTurn = "X"
	}
	if winner == "" && !draw {
		game.Turn = nextTurn
	}

	// Save back to Redis
	updatedGameBytes, _ := json.Marshal(&game)
	setToRedis("caro:game:"+callID, string(updatedGameBytes))

	sessions := s.getAllCallSessions(callID)
	boardSlice := s.boardToSlice(&game.Board)
	moveCount := game.MoveCount
	scoreX := game.ScoreX
	scoreO := game.ScoreO

	s.mu.RLock()
	legacyPeer, isInterwork := s.interworkingSessions[sessionID]
	s.mu.RUnlock()
	if isInterwork {
		log.Printf("[DCAS Interworking Engine] Caro move detected under Interworking mode. Notifying legacy peer via SMS...")
		link := fmt.Sprintf("http://%s/view-caro?session=%s", s.dcsfAddr, sessionID)
		log.Printf("[DCAS Interworking Engine] SMS SENT to %s: \"Den luot ban choi Caro. Click vao link de tiep tuc: %s\"", legacyPeer, link)
	}

	log.Printf("[DCAS-CARO] Move: %s placed at (%d,%d). MoveCount=%d Winner=%s Draw=%v Score: X=%d O=%d",
		playerSymbol, row, col, moveCount, winner, draw, scoreX, scoreO)

	// Broadcast update to all sessions
	resp := map[string]interface{}{
		"service":    "caro",
		"status":     "update_board",
		"board":      boardSlice,
		"last_move":  map[string]interface{}{"row": row, "col": col, "player": playerSymbol},
		"next_turn":  nextTurn,
		"move_count": moveCount,
		"score_x":    scoreX,
		"score_o":    scoreO,
		"winner":     winner,
		"draw":       draw,
	}
	if len(winCells) > 0 {
		resp["win_cells"] = winCells
	}

	for _, sid := range sessions {
		s.sendToSession(sid, "1000", resp)
		s.sendToSession(sid, "1001", resp)
	}
}

func (s *DCASServer) checkCaroWin(board *[CaroBoardSize][CaroBoardSize]string, row, col int, symbol string) [][2]int {
	// 4 directions: horizontal, vertical, diagonal-down, diagonal-up
	directions := [][2]int{{0, 1}, {1, 0}, {1, 1}, {1, -1}}

	for _, dir := range directions {
		var cells [][2]int
		cells = append(cells, [2]int{row, col})

		// Count forward
		for i := 1; i < CaroWinCount; i++ {
			r := row + dir[0]*i
			c := col + dir[1]*i
			if r < 0 || r >= CaroBoardSize || c < 0 || c >= CaroBoardSize {
				break
			}
			if board[r][c] != symbol {
				break
			}
			cells = append(cells, [2]int{r, c})
		}
		// Count backward
		for i := 1; i < CaroWinCount; i++ {
			r := row - dir[0]*i
			c := col - dir[1]*i
			if r < 0 || r >= CaroBoardSize || c < 0 || c >= CaroBoardSize {
				break
			}
			if board[r][c] != symbol {
				break
			}
			cells = append(cells, [2]int{r, c})
		}

		if len(cells) >= CaroWinCount {
			return cells
		}
	}
	return nil
}

func (s *DCASServer) boardToSlice(board *[CaroBoardSize][CaroBoardSize]string) [CaroBoardSize][CaroBoardSize]string {
	return *board
}

func (s *DCASServer) sendCaroError(sessionID, message string) {
	resp := map[string]interface{}{
		"service": "caro",
		"status":  "error",
		"message": message,
	}
	s.sendToSession(sessionID, "1000", resp)
	s.sendToSession(sessionID, "1001", resp)
}

func (s *DCASServer) caroSyncState(callID, sessionID string) {
	gameVal, err := getFromRedis("caro:game:" + callID)
	if err != nil || gameVal == "" {
		// Game hasn't started yet, auto-create it so clients don't hang
		log.Printf("[DCAS-CARO] Game not found for CallID=%s during sync_state, auto-creating...", callID)
		s.caroNewGame(callID, sessionID)
		return
	}
	var game CaroGame
	if err := json.Unmarshal([]byte(gameVal), &game); err != nil {
		return
	}

	activeSessions := s.getAllCallSessions(callID)

	changed := false
	if game.PlayerX != "" && !containsString(activeSessions, game.PlayerX) {
		game.PlayerX = ""
		changed = true
	}
	if game.PlayerO != "" && !containsString(activeSessions, game.PlayerO) {
		game.PlayerO = ""
		changed = true
	}

	if game.PlayerX == "" && sessionID != game.PlayerO {
		game.PlayerX = sessionID
		changed = true
	} else if game.PlayerO == "" && sessionID != game.PlayerX {
		game.PlayerO = sessionID
		changed = true
	}

	if changed {
		gameBytes, _ := json.Marshal(&game)
		setToRedis("caro:game:"+callID, string(gameBytes))
	}

	playerSymbol := "X"
	if sessionID == game.PlayerO {
		playerSymbol = "O"
	}

	resp := map[string]interface{}{
		"service":     "caro",
		"status":      "game_start",
		"your_symbol": playerSymbol,
		"next_turn":   game.Turn,
		"board":       s.boardToSlice(&game.Board),
		"score_x":     game.ScoreX,
		"score_o":     game.ScoreO,
	}

	s.sendToSession(sessionID, "1000", resp)
	s.sendToSession(sessionID, "1001", resp)
}
