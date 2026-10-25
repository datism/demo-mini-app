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
