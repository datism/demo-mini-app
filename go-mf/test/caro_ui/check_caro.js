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
