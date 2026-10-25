// Headless check for static/apps/caro/index.html.
// Feeds fake DCAS messages through window.receiveData and screenshots each scenario.
// Usage: cd go-mf/test/caro_ui && npm run check
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const PAGE = 'file://' + path.resolve(__dirname, '../../static/apps/caro/index.html');
const OUT = path.join(__dirname, 'out');
// WebView area on Galaxy S24 between the native top bar and the call controls.
const VIEWPORT = { width: 360, height: 490 };

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== undefined ? '  (' + detail + ')' : ''}`);
  if (!ok) failures++;
}

function emptyBoard() {
  return Array.from({ length: 9 }, () => Array(9).fill(''));
}

function gameStart(symbol, extra) {
  return Object.assign({
    service: 'caro', status: 'game_start', your_symbol: symbol, next_turn: 'X',
    board: emptyBoard(), score_x: 0, score_o: 0, turn_ms_left: 20000,
  }, extra || {});
}

async function send(page, msg) {
  await page.evaluate((m) => window.receiveData(JSON.stringify(m)), msg);
}

const scenarios = [];
function scenario(name, fn) { scenarios.push({ name, fn }); }

// ---------------------------------------------------------------- scenarios

scenario('layout', async (page) => {
  await send(page, gameStart('X', { score_x: 2, score_o: 1 }));
  const r = await page.evaluate(() => {
    const rect = (id) => document.getElementById(id).getBoundingClientRect().toJSON();
    return {
      bg: getComputedStyle(document.getElementById('game-container')).backgroundColor,
      board: rect('board'),
      panel: rect('panel'),
      vw: innerWidth,
      vh: innerHeight,
    };
  });
  check('container has no dark overlay', r.bg === 'rgba(0, 0, 0, 0)', r.bg);
  check('top of screen is clear for the video', r.panel.top >= r.vh * 0.3, `panel.top=${r.panel.top}`);
  check('board fits inside the viewport',
    r.board.left >= 0 && r.board.right <= r.vw && r.board.bottom <= r.vh, JSON.stringify(r.board));
  check('board is at least 260px wide', r.board.width >= 260, `width=${r.board.width}`);
  check('my score shown', (await page.textContent('#me-score')).trim() === '2');
  check('opponent score shown', (await page.textContent('#opp-score')).trim() === '1');
  check('my pill is active on my turn', await page.$eval('#pill-me', (p) => p.classList.contains('active')));
});

scenario('feedback', async (page) => {
  await send(page, gameStart('X'));

  // Own move: optimistic pop, tone, vibration, sent to server
  await page.click('.cell[data-row="4"][data-col="4"]');
  let fx = await page.evaluate(() => window.__fx || []);
  check('own move plays tone 880', fx.includes('tone:880'), fx.join(','));
  check('own move vibrates 25ms', fx.includes('buzz:25'), fx.join(','));
  check('own move pops', await page.$eval('.cell[data-row="4"][data-col="4"]', (c) => c.classList.contains('pop')));
  check('move sent to server', await page.evaluate(
    () => window.__sent.some((m) => m.action === 'move' && m.row === 4 && m.col === 4)));

  // Server rejects it: toast + shake + rollback
  await send(page, { service: 'caro', status: 'error', message: 'Ô này đã có quân.' });
  check('toast shows server message', (await page.textContent('#toast')).includes('Ô này đã có quân.'));
  check('toast visible', await page.$eval('#toast', (t) => t.classList.contains('show')));
  check('board shakes', await page.$eval('#board-wrap', (b) => b.classList.contains('shake')));
  check('optimistic piece rolled back',
    (await page.textContent('.cell[data-row="4"][data-col="4"]')).trim() === '');

  // Opponent move: lower tone + pop
  const b = emptyBoard();
  b[0][0] = 'O';
  await page.evaluate(() => { window.__fx = []; });
  await send(page, {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 0, col: 0, player: 'O' },
    next_turn: 'X', move_count: 1, score_x: 0, score_o: 0, winner: '', draw: false, turn_ms_left: 20000,
  });
  fx = await page.evaluate(() => window.__fx || []);
  check('opponent move plays tone 660', fx.includes('tone:660'), fx.join(','));
  check('opponent move pops', await page.$eval('.cell[data-row="0"][data-col="0"]', (c) => c.classList.contains('pop')));

  // Not my turn: local toast, nothing sent
  await send(page, {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 0, col: 0, player: 'O' },
    next_turn: 'O', move_count: 1, score_x: 0, score_o: 0, winner: '', draw: false, turn_ms_left: 20000,
  });
  const sentBefore = await page.evaluate(() => window.__sent.filter((m) => m.action === 'move').length);
  await page.click('.cell[data-row="2"][data-col="2"]');
  check('not-your-turn toast', (await page.textContent('#toast')).includes('Chưa đến lượt của bạn.'));
  check('no move sent on opponent turn',
    (await page.evaluate(() => window.__sent.filter((m) => m.action === 'move').length)) === sentBefore);
});

function winMessage() {
  const b = emptyBoard();
  // Unordered on purpose: DCAS sends forward cells then backward cells.
  const cells = [[4, 4], [4, 0], [4, 1], [4, 2], [4, 3]];
  cells.forEach(([r, c]) => { b[r][c] = 'X'; });
  return {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 4, col: 4, player: 'X' },
    next_turn: 'O', move_count: 9, score_x: 1, score_o: 0, winner: 'X', draw: false,
    win_cells: cells, turn_ms_left: 0,
  };
}

scenario('win', async (page) => {
  await send(page, gameStart('X'));
  await send(page, winMessage());
  const line = await page.evaluate(() => {
    const l = document.querySelector('#win-line line');
    return {
      shown: document.getElementById('win-line').classList.contains('show'),
      x1: +l.getAttribute('x1'), y1: +l.getAttribute('y1'),
      x2: +l.getAttribute('x2'), y2: +l.getAttribute('y2'),
    };
  });
  check('win line shown', line.shown);
  check('win line spans (4,0) to (4,4)',
    line.x1 === 50 && line.y1 === 450 && line.x2 === 450 && line.y2 === 450, JSON.stringify(line));
  check('result card waits for the line',
    !(await page.$eval('#winner-overlay', (o) => o.classList.contains('show'))));
  await page.waitForTimeout(1200);
  check('result card shown', await page.$eval('#winner-overlay', (o) => o.classList.contains('show')));
  check('winner text', (await page.textContent('#winner-text')).includes('Bạn thắng'));
  check('score on card', (await page.textContent('#winner-score')).includes('1'));
  const fx = await page.evaluate(() => window.__fx || []);
  check('confetti on the winner phone', fx.includes('confetti'), fx.join(','));
  check('winner vibration', fx.includes('buzz:[40,60,40,60,40]'), fx.join(','));
});

scenario('lose', async (page) => {
  await send(page, gameStart('O'));
  await send(page, winMessage());
  await page.waitForTimeout(1200);
  check('loser text', (await page.textContent('#winner-text')).includes('Bạn thua'));
  const fx = await page.evaluate(() => window.__fx || []);
  check('no confetti on the loser phone', !fx.includes('confetti'), fx.join(','));
});

scenario('new-game-clears-win', async (page) => {
  await send(page, gameStart('X'));
  await send(page, winMessage());
  await send(page, gameStart('X', { score_x: 1 }));
  await page.waitForTimeout(1200);
  check('win line hidden after new game',
    !(await page.$eval('#win-line', (s) => s.classList.contains('show'))));
  check('result card does not pop up after new game',
    !(await page.$eval('#winner-overlay', (o) => o.classList.contains('show'))));
});

scenario('timer', async (page) => {
  const ring = () => page.evaluate(() => {
    const active = document.querySelector('.score-pill.active');
    const r = active && active.querySelector('.timer-ring');
    return {
      pill: active ? active.id : null,
      visible: !!r && getComputedStyle(r).display !== 'none',
      warn: !!r && r.classList.contains('warn'),
      danger: !!r && r.classList.contains('danger'),
      text: document.getElementById('turn-timer').textContent,
    };
  });

  await send(page, gameStart('X', { turn_ms_left: 20000 }));
  await page.waitForTimeout(200);
  let st = await ring();
  check('ring visible on my pill', st.pill === 'pill-me' && st.visible, JSON.stringify(st));
  check('timer text starts at 20s', st.text === '· 20s' || st.text === '· 19s', st.text);

  await send(page, gameStart('X', { turn_ms_left: 8000 }));
  await page.waitForTimeout(150);
  st = await ring();
  check('amber at <=10s', st.warn && !st.danger, JSON.stringify(st));

  await send(page, gameStart('X', { turn_ms_left: 4000 }));
  await page.waitForTimeout(150);
  st = await ring();
  check('red at <=5s', st.danger, JSON.stringify(st));

  await send(page, {
    service: 'caro', status: 'update_board', board: emptyBoard(), next_turn: 'O', move_count: 0,
    score_x: 0, score_o: 0, winner: '', draw: false, timeout: true, timed_out: 'X', turn_ms_left: 20000,
  });
  await page.waitForTimeout(150);
  st = await ring();
  check('timeout toast for the player who ran out',
    (await page.textContent('#toast')).includes('Hết giờ! Bạn mất lượt'));
  check('ring moves to opponent after timeout', st.pill === 'pill-opp' && st.visible, JSON.stringify(st));

  await send(page, winMessage());
  await page.waitForTimeout(150);
  st = await ring();
  check('no ring when the game is over', st.pill === null && st.text === '', JSON.stringify(st));
});

scenario('timer-opponent-timeout', async (page) => {
  await send(page, gameStart('O', { next_turn: 'X' }));
  await send(page, {
    service: 'caro', status: 'update_board', board: emptyBoard(), next_turn: 'O', move_count: 0,
    score_x: 0, score_o: 0, winner: '', draw: false, timeout: true, timed_out: 'X', turn_ms_left: 20000,
  });
  check('opponent-timeout toast', (await page.textContent('#toast')).includes('Đối thủ hết giờ'));
});

scenario('timer-rollback', async (page) => {
  await send(page, gameStart('X', { turn_ms_left: 15000 }));
  await page.waitForTimeout(200);
  await page.click('.cell[data-row="0"][data-col="0"]');
  await page.waitForTimeout(100);
  await send(page, { service: 'caro', status: 'error', message: 'Nước đi không hợp lệ.' });
  await page.waitForTimeout(200);
  const st = await page.evaluate(() => {
    const a = document.querySelector('.score-pill.active');
    return { pill: a ? a.id : null, timing: !!a && a.classList.contains('timing'), text: document.getElementById('turn-timer').textContent };
  });
  const secs = parseInt(st.text.replace(/\D/g, ''), 10);
  check('rollback restores previous countdown', st.pill === 'pill-me' && st.timing && secs >= 13 && secs <= 15, JSON.stringify(st));
});

scenario('timer-no-server-value', async (page) => {
  await send(page, gameStart('X', { turn_ms_left: 15000 }));
  await send(page, {
    service: 'caro', status: 'update_board', board: emptyBoard(), next_turn: 'O', move_count: 0,
    score_x: 0, score_o: 0, winner: '', draw: false,
  });
  await page.waitForTimeout(150);
  const st = await page.evaluate(() => ({
    timing: document.querySelectorAll('.score-pill.timing').length,
    text: document.getElementById('turn-timer').textContent,
  }));
  check('no running ring without turn_ms_left', st.timing === 0 && st.text === '', JSON.stringify(st));
});

scenario('timer-no-tick-after-win', async (page) => {
  await send(page, gameStart('X', { turn_ms_left: 3500 }));
  await send(page, winMessage());
  const before = await page.evaluate(() => (window.__fx || []).filter((f) => f === 'tick').length);
  await page.waitForTimeout(1500);
  const after = await page.evaluate(() => (window.__fx || []).filter((f) => f === 'tick').length);
  check('no tick after the game is over', after === before, before + ' -> ' + after);
});

scenario('new-game-during-delay', async (page) => {
  await send(page, gameStart('X'));
  await send(page, winMessage());
  await page.waitForTimeout(300);
  await page.click('#header-new-game-btn');
  await page.waitForTimeout(1200);
  check('result card stays hidden', !(await page.$eval('#winner-overlay', (o) => o.classList.contains('show'))));
  const fx = await page.evaluate(() => window.__fx || []);
  check('no confetti after early new game', !fx.includes('confetti'), fx.join(','));
});

// ---------------------------------------------------------------- runner

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  for (const s of scenarios) {
    const page = await browser.newPage({ viewport: VIEWPORT });
    await page.addInitScript(() => {
      window.__sent = [];
      window.AndroidBridge = { sendData: (str) => window.__sent.push(JSON.parse(str)) };
    });
    // Dark stand-in for the remote video so screenshots resemble the device.
    await page.goto(PAGE);
    await page.evaluate(() => {
      document.documentElement.style.background = 'linear-gradient(#334155, #0f172a)';
    });
    console.log(`--- ${s.name}`);
    try {
      await s.fn(page);
    } catch (e) {
      check(`${s.name} ran without errors`, false, e.message);
    }
    await page.screenshot({ path: path.join(OUT, `${s.name}.png`) });
    await page.close();
  }
  await browser.close();
  console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
  process.exit(failures ? 1 : 0);
})();
