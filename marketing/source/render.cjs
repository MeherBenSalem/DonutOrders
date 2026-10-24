// Renders the listing artwork in marketing/images/ from real in-game captures and the bundled OFL fonts.
// Usage (from the repository root): NODE_PATH="$(npm root -g)" node marketing/source/render.cjs [name...]
// Requires the `playwright` package with a Chromium build available.
// Inputs: marketing/images/screenshots/*.jpg (tools/marketing/capture.mjs) and marketing/source/compat.json.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

// The release, the supported range and the platforms. Adding a Minecraft version is one line in
// release/supported-minecraft.json (and a rerun of tools/smoke for compat.json).
const RELEASE = { version: '2.0', name: 'The Supply Update' };
const root = path.resolve(__dirname, '..', '..');
const SUPPORTED = JSON.parse(fs.readFileSync(path.join(root, 'release', 'supported-minecraft.json'), 'utf8'));
const RANGE = `${SUPPORTED.min} – ${SUPPORTED.max}`;
const out = path.join(root, 'marketing', 'images');
const shots = path.join(out, 'screenshots');
const fonts = path.join(__dirname, 'fonts');
const url = (file) => 'file://' + file;
const shot = (name) => url(path.join(shots, name));

const css = `
@font-face { font-family: DoDisplay; font-weight: 600; src: url('${url(path.join(fonts, 'outfit-latin-600-normal.woff2'))}'); }
@font-face { font-family: DoDisplay; font-weight: 700; src: url('${url(path.join(fonts, 'outfit-latin-700-normal.woff2'))}'); }
@font-face { font-family: DoDisplay; font-weight: 800; src: url('${url(path.join(fonts, 'outfit-latin-800-normal.woff2'))}'); }
@font-face { font-family: DoDisplay; font-weight: 900; src: url('${url(path.join(fonts, 'outfit-latin-900-normal.woff2'))}'); }
@font-face { font-family: DoText; font-weight: 400; src: url('${url(path.join(fonts, 'inter-latin-400-normal.woff2'))}'); }
@font-face { font-family: DoText; font-weight: 500; src: url('${url(path.join(fonts, 'inter-latin-500-normal.woff2'))}'); }
@font-face { font-family: DoText; font-weight: 600; src: url('${url(path.join(fonts, 'inter-latin-600-normal.woff2'))}'); }
@font-face { font-family: DoText; font-weight: 700; src: url('${url(path.join(fonts, 'inter-latin-700-normal.woff2'))}'); }
:root { --bg:#100c16; --panel:#1a1522; --panel-hi:#231c2e; --line:rgba(255,236,246,.13); --text:#e6dfea; --dim:#a69cb0; --faint:#6f6679;
        --hi:#fff8fb; --gold:#ffc24b; --gold-hi:#ffe08a; --accent:#49d6a0; --accent-hi:#9af2cd; --pink:#ff6fae; --mint:#49d6a0; --sky:#6bc6ff; --red:#ff6b6b; }
* { box-sizing: border-box; }
html, body { margin: 0; background: var(--bg); color: var(--text); font-family: DoText, sans-serif; }
.stage { position: relative; overflow: hidden; background:
  radial-gradient(ellipse 70% 60% at 78% 40%, rgba(73,214,160,.15) 0%, rgba(73,214,160,0) 60%),
  radial-gradient(ellipse 60% 70% at 15% 85%, rgba(255,194,75,.10) 0%, rgba(255,194,75,0) 60%),
  linear-gradient(160deg, #18111f 0%, #100c16 55%, #0b0910 100%); }
canvas.sprinkles { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.display { font-family: DoDisplay, sans-serif; font-weight: 800; letter-spacing: -.01em; color: var(--hi); }
.pink { color: var(--accent); } .gold { color: var(--gold); } .mint { color: var(--mint); } .sky { color: var(--sky); }
.grad { background: linear-gradient(100deg, var(--accent) 0%, #6bc6ff 45%, var(--gold) 100%); -webkit-background-clip: text; background-clip: text; color: transparent; }
.kicker { font-family: DoDisplay, sans-serif; font-weight: 700; color: var(--accent); letter-spacing: .22em; font-size: 21px; text-transform: uppercase; }
.kicker .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: var(--gold); margin: 0 14px 3px 0; box-shadow: 0 0 14px rgba(255,194,75,.8); }
.rule { height: 3px; border-radius: 3px; background: linear-gradient(90deg, var(--accent), rgba(73,214,160,0)); }
ul.points { list-style: none; padding: 0; margin: 0; }
ul.points li { position: relative; padding-left: 32px; margin: 0 0 16px; font-size: 24px; line-height: 1.38; color: var(--text); }
ul.points li::before { content: ''; position: absolute; left: 0; top: 9px; width: 14px; height: 14px; border-radius: 50%;
  background: radial-gradient(circle at 50% 50%, var(--bg) 0 3px, var(--accent) 3.5px 7px); box-shadow: 0 0 10px rgba(73,214,160,.55); }
b.hi { color: var(--hi); font-weight: 600; }
code, .mono { font-family: 'DejaVu Sans Mono', ui-monospace, monospace; }
code.inline { font-size: .86em; color: var(--gold-hi); background: rgba(255,194,75,.10); border: 1px solid rgba(255,194,75,.25); padding: 1px 8px; border-radius: 7px; }
.frame { position: relative; border-radius: 16px; overflow: hidden; border: 1px solid rgba(255,236,246,.18);
  box-shadow: 0 34px 90px rgba(0,0,0,.7), 0 0 0 1px rgba(0,0,0,.6), 0 0 70px rgba(73,214,160,.13); background: #000; }
.frame > img, .frame > .crop { display: block; }
.label { position: absolute; font-family: DoDisplay, sans-serif; font-weight: 700; font-size: 19px; letter-spacing: .08em; text-transform: uppercase;
  color: var(--hi); background: rgba(16,12,22,.9); border: 1px solid rgba(73,214,160,.65); border-radius: 999px; padding: 7px 16px 6px; white-space: nowrap;
  box-shadow: 0 10px 28px rgba(0,0,0,.55); z-index: 3; }
.label.gold { border-color: rgba(255,194,75,.75); } .label.mint { border-color: rgba(73,214,160,.75); }
.pill { display: inline-flex; align-items: center; gap: 8px; border: 1px solid rgba(255,236,246,.28); border-radius: 999px; padding: 8px 18px 7px;
  margin-right: 10px; font-family: DoDisplay, sans-serif; font-weight: 700; font-size: 20px; letter-spacing: .06em; color: var(--hi); background: rgba(255,255,255,.04); }
.pill i { width: 9px; height: 9px; border-radius: 50%; background: var(--mint); box-shadow: 0 0 10px rgba(73,214,160,.8); }
.panel { background: linear-gradient(180deg, rgba(35,28,46,.94), rgba(22,17,30,.94)); border: 1px solid var(--line); border-radius: 18px;
  box-shadow: 0 26px 70px rgba(0,0,0,.6); }
.stat { flex: 1; padding: 16px 22px 14px; border-left: 1px solid var(--line); }
.stat:first-child { border-left: 0; }
.stat .t { font-family: DoDisplay; font-weight: 700; font-size: 15px; letter-spacing: .16em; text-transform: uppercase; color: var(--dim); }
.stat .v { font-family: DoDisplay; font-weight: 800; font-size: 30px; color: var(--hi); margin-top: 3px; white-space: nowrap; }
`;

// Deterministic donut sprinkles drifting across the backdrop.
const sprinkles = (count = 70, opacity = 0.5) => `<canvas class="sprinkles"></canvas><script>
(() => { const c = document.currentScript.previousElementSibling; const r = c.getBoundingClientRect();
  c.width = r.width; c.height = r.height; const g = c.getContext('2d'); let s = 11;
  const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  const colors = ['#49d6a0', '#ffc24b', '#ff6fae', '#6bc6ff', '#fff3f8', '#b38bff'];
  for (let i = 0; i < ${count}; i++) { const x = rnd() * c.width, y = rnd() * c.height, a = rnd() * Math.PI, l = 10 + rnd() * 12, w = 4 + rnd() * 2;
    g.save(); g.translate(x, y); g.rotate(a); g.globalAlpha = ${opacity} * (.25 + rnd() * .75); g.fillStyle = colors[Math.floor(rnd() * colors.length)];
    g.beginPath(); g.roundRect(-l / 2, -w / 2, l, w, w / 2); g.fill(); g.restore(); } })();
</script>`;

const page = (width, height, body, transparent = false) => `<!doctype html><html><head><meta charset="utf-8"><style>${css}
body { width: ${width}px; height: ${height}px; } .stage { width: ${width}px; height: ${height}px; }
${transparent ? 'html, body, .stage { background: transparent !important; }' : ''}</style></head>
<body><div class="stage">${body}</div></body></html>`;

// A pixel-art donut (the project mark, shared with DonutAuctionHouse): mint icing with sprinkles on a golden ring.
const donutSvg = (size) => {
  const n = 24;
  const cells = [];
  const sprinkleAt = new Map([[[7, 7], '#6bc6ff'], [[10, 5], '#ffc24b'], [[14, 5], '#49d6a0'], [[17, 8], '#fff3f8'], [[18, 12], '#6bc6ff'],
    [[5, 12], '#49d6a0'], [[7, 16], '#ffc24b'], [[15, 18], '#fff3f8'], [[17, 15], '#b38bff'], [[12, 4], '#fff3f8'], [[5, 9], '#ffc24b'],
    [[11, 18], '#6bc6ff'], [[18, 10], '#ffc24b']].map(([[px, py], color]) => [px + ',' + py, color]));
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const dx = x + 0.5 - n / 2, dy = y + 0.5 - n / 2, d = Math.hypot(dx, dy), angle = Math.atan2(dy, dx);
      if (d > 11.2 || d < 3.5) continue;
      // Dough: golden, darker at the rims.
      let color = d > 10.3 || d < 4.2 ? '#a8692b' : (d > 9.4 ? '#c98536' : '#e0a14a');
      // Icing: scalloped edge plus two drips down the front.
      let edge = 8.2 + Math.sin(angle * 6) * 0.7;
      const drip = Math.exp(-Math.pow((angle - 1.2) / 0.16, 2)) * 2.2 + Math.exp(-Math.pow((angle - 2.25) / 0.14, 2)) * 1.6;
      edge += drip;
      if (d >= 4.2 && d < edge) {
        color = d < 5.0 ? '#b8f5dc' : (d > edge - 0.9 ? '#2fae7f' : '#49d6a0');
        const key = x + ',' + y;
        if (sprinkleAt.has(key)) color = sprinkleAt.get(key);
      }
      cells.push(`<rect x="${x}" y="${y}" width="1.02" height="1.02" fill="${color}"/>`);
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${n} ${n}" shape-rendering="crispEdges">${cells.join('')}</svg>`;
};

const header = (title) => page(1400, 120, `
  <div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:24px;
              background:linear-gradient(90deg,rgba(16,12,22,0),rgba(26,20,34,.94) 24%,rgba(26,20,34,.94) 76%,rgba(16,12,22,0))">
    <div class="rule" style="width:260px;transform:scaleX(-1)"></div>
    <div style="width:38px;height:38px">${donutSvg(38)}</div>
    <div class="display" style="font-size:50px;white-space:nowrap">${title}</div>
    <div style="width:38px;height:38px">${donutSvg(38)}</div>
    <div class="rule" style="width:260px"></div>
  </div>`, true);

const divider = () => page(1400, 40, `
  <div style="position:absolute;left:0;right:0;top:19px;height:2px;background:linear-gradient(90deg,rgba(73,214,160,0),rgba(73,214,160,.7) 30%,rgba(255,194,75,.7) 70%,rgba(255,194,75,0))"></div>
  <div style="position:absolute;left:688px;top:8px;width:24px;height:24px">${donutSvg(24)}</div>`, true);

// A feature card: copy on the left, artwork on the right. Bullets never end on a one-word line.
const card = ({ kicker, title, points, art, width = 1600, height = 900, copyWidth = 560, artTop = 60, dim = 0.62 }) => page(width, height, `
  ${backdrop(dim)}
  ${sprinkles(54, 0.32)}
  <div style="position:absolute;left:80px;top:0;bottom:0;width:${copyWidth}px;display:flex;flex-direction:column;justify-content:center;z-index:2">
    <div class="kicker"><span class="dot"></span>${kicker}</div>
    <div class="display" style="font-size:64px;line-height:1.02;margin:20px 0 24px">${title}</div>
    <div class="rule" style="width:62%;margin-bottom:30px"></div>
    <ul class="points">${points.map((point) => `<li>${point.replace(/ (\S+)$/, '&nbsp;$1')}</li>`).join('')}</ul>
  </div>
  <div style="position:absolute;left:${copyWidth + 130}px;right:60px;top:${artTop}px;bottom:60px;display:flex;flex-direction:column;justify-content:center">${art}</div>`);

// An exact region (x, y, w, h in 1920x1080 pixels) of a capture, scaled to W pixels wide.
const region = (file, x, y, w, h, W) => `<div class="crop" style="position:relative;width:${W}px;height:${Math.round(W * h / w)}px;overflow:hidden">
    <img src="${shot(file)}" style="position:absolute;max-width:none;width:${Math.round(W * 1920 / w)}px;left:${Math.round(-x * W / w)}px;top:${Math.round(-y * W / w)}px;image-rendering:${W / w >= 1.5 ? 'pixelated' : 'auto'}"></div>`;
const framed = (inner, style = '') => `<div class="frame" style="${style}">${inner}</div>`;
const label = (text, style, kind = '') => `<div class="label ${kind}" style="${style}">${text}</div>`;
const stats = (items) => `<div class="panel" style="display:flex;margin-top:22px">${items.map(([t, v]) =>
  `<div class="stat"><div class="t">${t}</div><div class="v">${v}</div></div>`).join('')}</div>`;

// Chest-menu crop boxes at GUI scale 3 (176 px wide menus centred in 1920x1080), with room for tooltips.
const MENU6 = [696, 207, 528, 666];
const MENU5 = [696, 234, 528, 612];
const MENU3 = [696, 288, 528, 504];
const wide = ([x, y, w, h], extraRight = 0, extraLeft = 0, extraTop = 0, extraBottom = 0) =>
  [x - extraLeft, y - extraTop, w + extraLeft + extraRight, h + extraTop + extraBottom];

// The blurred in-game scene behind every card (a copy of screenshots/scenery.jpg, see marketing/README.md).
const BACKDROP = path.join(__dirname, 'backdrop.jpg');
const backdrop = (dim = 0.62, blur = 7) => fs.existsSync(BACKDROP) ? `
  <img src="${url(BACKDROP)}" style="position:absolute;inset:-30px;width:calc(100% + 60px);height:calc(100% + 60px);object-fit:cover;filter:blur(${blur}px) saturate(1.08)">
  <div style="position:absolute;inset:0;background:linear-gradient(90deg,rgba(14,10,20,.96) 0%,rgba(14,10,20,.86) 36%,rgba(14,10,20,${dim}) 70%,rgba(14,10,20,${Math.min(1, dim + 0.1)}) 100%)"></div>` : '';

const escapeHtml = (text) => String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// YAML with light highlighting: comments, keys, strings and numbers.
const yaml = (lines) => lines.map((line) => {
  const comment = line.match(/^(\s*)(#.*)$/);
  if (comment) return `${comment[1]}<span style="color:var(--faint)">${escapeHtml(comment[2])}</span>`;
  const pair = line.match(/^(\s*)([^:#'"]+?):(\s*)(.*)$/);
  if (!pair) return escapeHtml(line);
  const [, indent, key, gap, value] = pair;
  let shown = escapeHtml(value);
  if (/^'.*'$|^".*"$/.test(value)) shown = `<span style="color:var(--gold-hi)">${shown}</span>`;
  else if (/^-?[0-9.]+$|^(true|false)$/.test(value)) shown = `<span style="color:var(--sky)">${shown}</span>`;
  return `${indent}<span style="color:var(--accent-hi)">${escapeHtml(key)}</span>:${gap}${shown}`;
}).join('\n');

/** A dark editor or console panel with a title bar. */
const codePanel = (title, body, { width = 760, size = 19, style = '', wrap = false } = {}) => `<div class="panel" style="width:${width}px;overflow:hidden;${style}">
  <div style="display:flex;align-items:center;gap:9px;padding:13px 18px;border-bottom:1px solid var(--line);background:rgba(255,255,255,.03)">
    <i style="width:12px;height:12px;border-radius:50%;background:#ff6b6b;opacity:.8"></i><i style="width:12px;height:12px;border-radius:50%;background:#ffc24b;opacity:.8"></i>
    <i style="width:12px;height:12px;border-radius:50%;background:#49d6a0;opacity:.8"></i>
    <span style="margin-left:10px;font-family:DoDisplay;font-weight:700;font-size:17px;letter-spacing:.1em;text-transform:uppercase;color:var(--dim)">${title}</span></div>
  <pre class="mono" style="margin:0;padding:18px 22px 20px;font-size:${size}px;line-height:1.5;color:var(--text);${wrap ? 'white-space:pre-wrap;overflow-wrap:anywhere' : 'white-space:pre'}">${body}</pre></div>`;

/** Lines start..end (1-based, inclusive) of a file in the repository. */
const fileLines = (file, start, end) => fs.readFileSync(path.join(root, file), 'utf8').split('\n').slice(start - 1, end);
/** The lines of a YAML section: from `key:` up to the next line with the same or less indentation. */
const yamlSection = (file, key) => {
  const lines = fs.readFileSync(path.join(root, file), 'utf8').split('\n');
  const start = lines.findIndex((line) => line.replace(/^\s+/, '').startsWith(key + ':'));
  if (start < 0) throw new Error(`${key} not found in ${file}`);
  const indent = lines[start].match(/^\s*/)[0].length;
  let end = start + 1;
  while (end < lines.length && (lines[end].trim() === '' || lines[end].match(/^\s*/)[0].length > indent)) end++;
  while (end > start && lines[end - 1].trim() === '') end--;
  return lines.slice(start, end);
};

/** Results of the cross-version matrix (tools/smoke), written by tools/smoke/compat.mjs. */
const COMPAT_FILE = path.join(__dirname, 'compat.json');
const compat = () => (fs.existsSync(COMPAT_FILE) ? JSON.parse(fs.readFileSync(COMPAT_FILE, 'utf8')) : null);
const consoleText = (name) => {
  const file = path.join(__dirname, 'console', name);
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trimEnd().split('\n') : [];
};

const exists = (name) => fs.existsSync(path.join(shots, name));
module.exports = { page, card, region, framed, label, stats, header, divider, donutSvg, sprinkles, MENU6, MENU5, MENU3, wide, shot, exists,
  backdrop, codePanel, yaml, escapeHtml, fileLines, yamlSection, compat, consoleText, RELEASE, RANGE, SUPPORTED, root, out };

if (require.main === module) {
  const assets = require('./assets.cjs');
  (async () => {
    const wanted = process.argv.slice(2);
    const browser = await chromium.launch();
    const context = await browser.newContext({ deviceScaleFactor: 1 });
    for (const [name, build] of Object.entries(assets)) {
      if (wanted.length && !wanted.includes(name)) continue;
      const { html, width, height, transparent, file } = build();
      const tab = await context.newPage();
      await tab.setViewportSize({ width, height });
      // Loaded from a file so the bundled fonts and captures (file:// URLs) are allowed.
      const temp = path.join(__dirname, `.render-${name}.html`);
      fs.writeFileSync(temp, html);
      await tab.goto(url(temp));
      await tab.evaluate(() => document.fonts.ready);
      await tab.waitForLoadState('networkidle');
      await tab.waitForTimeout(150);
      fs.rmSync(temp, { force: true });
      const target = path.join(out, file || `${name}.png`);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      await tab.screenshot({ path: target, omitBackground: !!transparent, clip: { x: 0, y: 0, width, height } });
      await tab.close();
      console.log(`rendered ${path.relative(root, target)}`);
    }
    await browser.close();
  })().catch((error) => { console.error(error); process.exit(1); });
}
