// Captures the listing screenshots from a real game: a Paper server running DonutOrders and the unmodified vanilla
// Minecraft client (software rendering under Xvfb), driven through console commands and XTEST mouse/keyboard
// input. A mineflayer bot plays the other trader.
//
// Needs: Xvfb, ImageMagick (import), ffmpeg, sqlite3, python3 with python-xlib, portablemc, and the smoke-test
// cache (tools/smoke). Run from the repository root after `mvn package` and the fixture build:
//   ORDERLAB_PORT=25600 node tools/marketing/capture.mjs
import { spawn, execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Server, joinBot } from '../smoke/run.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const lab = process.env.ORDERLAB_HOME || path.join(os.homedir(), '.cache', 'donutorders-smoke');
const PORT = Number(process.env.ORDERLAB_PORT || 25600);
const VERSION = process.env.CAPTURE_VERSION || '1.21.11';
const PLAYER = 'Moonbeam';
const DISPLAY = ':99';
const W = 1920;
const H = 1080;
const SCALE = 3;
const shots = path.join(repo, 'marketing', 'images', 'screenshots');
const gifs = path.join(repo, 'marketing', 'images', 'gifs');
const python = process.env.CAPTURE_PYTHON || 'python3';
const portablemc = process.env.PORTABLEMC || 'portablemc';
const clientDir = process.env.CAPTURE_CLIENT_DIR || path.join(lab, 'client');

// Noon, clear sky, no command echo in chat. 1.21.11 renamed the game rules, so both spellings are sent.
const WORLD_SETUP = ['time set 6000', 'weather clear',
  'gamerule advance_time false', 'gamerule advance_weather false', 'gamerule show_advancement_messages false', 'gamerule send_command_feedback false',
  'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule announceAdvancements false', 'gamerule sendCommandFeedback false'];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const x = (...args) => execFileSync(python, [path.join(here, 'xinput.py'), ...args.map(String)], { env: { ...process.env, DISPLAY } });

/** Pixel centre of a container slot in a chest menu with the given number of rows (GUI scale 3). */
function slot(index, rows) {
  const left = Math.floor((W / SCALE - 176) / 2);
  const top = Math.floor((H / SCALE - (114 + rows * 18)) / 2);
  if (index >= rows * 9) {
    // The player's own inventory below the chest: 27 storage slots, then the hotbar 4 px lower.
    const own = index - rows * 9;
    const row = Math.floor(own / 9);
    const y = top + 18 + rows * 18 + 13 + row * 18 + (row === 3 ? 4 : 0);
    return [(left + 8 + (own % 9) * 18 + 8) * SCALE, (y + 8) * SCALE];
  }
  return [(left + 8 + (index % 9) * 18 + 8) * SCALE, (top + 18 + Math.floor(index / 9) * 18 + 8) * SCALE];
}

function screenshot(name, quality = 92) {
  fs.mkdirSync(shots, { recursive: true });
  const file = path.join(shots, `${name}.jpg`);
  execFileSync('import', ['-display', DISPLAY, '-window', 'root', '-quality', String(quality), file]);
  console.log(`  captured ${path.relative(repo, file)}`);
  return file;
}

async function hover(index, rows, settle = 700) {
  const [px, py] = slot(index, rows);
  x('move', px - 9, py - 9);
  await sleep(120);
  x('move', px, py);
  await sleep(settle);
}

async function clickSlot(index, rows, settle = 1200, button = 1) {
  const [px, py] = slot(index, rows);
  x('click', px, py, button);
  await sleep(settle);
}

/** Records the screen for `seconds` (x11grab); returns a promise for the finished MP4. */
function record(name, seconds) {
  const raw = path.join(lab, 'capture', `${name}.mp4`);
  const proc = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'x11grab', '-video_size', `${W}x${H}`, '-framerate', '20', '-i', `${DISPLAY}.0`,
    '-t', String(seconds), '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '16', raw], { stdio: 'ignore' });
  return new Promise((resolve) => proc.on('exit', () => resolve(raw)));
}

/** A 640x360 GIF from a recording, cropped to the 1280x720 centre of the screen (the menu and its tooltips). */
function gif(raw, name, speed = 1) {
  fs.mkdirSync(gifs, { recursive: true });
  const file = path.join(gifs, `${name}.gif`);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', raw, '-vf',
    `setpts=PTS/${speed},crop=1280:720:320:180,fps=12,scale=640:360:flags=lanczos,split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`,
    file]);
  console.log(`  recorded ${path.relative(repo, file)}`);
}

function writeClientOptions() {
  fs.mkdirSync(clientDir, { recursive: true });
  fs.writeFileSync(path.join(clientDir, 'options.txt'), [
    'version:4671', 'guiScale:3', 'renderDistance:10', 'simulationDistance:5', 'maxFps:30', 'graphicsMode:1',
    'pauseOnLostFocus:false', 'narrator:0', 'tutorialStep:none', 'skipMultiplayerWarning:true', 'joinedFirstServer:true',
    'onboardAccessibility:false', 'lang:en_us', 'fov:0.0', 'bobView:false', 'chatScale:1.0', 'chatWidth:1.0',
    'soundCategory_master:0.0', 'darkMojangStudiosBackground:true', 'hideServerAddress:true', 'renderClouds:"fancy"',
    'entityShadows:true', 'particles:0', 'mipmapLevels:4', 'ao:true', 'menuBackgroundBlurriness:3',
  ].join('\n') + '\n');
}

function prepareServer() {
  const dir = path.join(lab, 'capture', 'server');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins', 'DonutOrders'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    'online-mode=false', 'white-list=false', 'enforce-secure-profile=false', `server-port=${PORT}`, 'level-seed=8675309',
    'difficulty=peaceful', 'spawn-monsters=false', 'view-distance=10', 'simulation-distance=5', 'spawn-protection=0',
    'motd=DonutOrders', 'gamemode=survival', 'allow-flight=true',
  ].join('\n') + '\n');
  const target = path.join(repo, 'target');
  const plugin = fs.readdirSync(target).find((f) => /^DonutOrders-.*\.jar$/.test(f));
  const jars = [path.join(target, plugin), path.join(repo, 'tools', 'smoke', 'fixture', 'target', 'OrderLabFixture.jar'),
    path.join(lab, 'cache', 'plugins', 'Vault-1.7.3.jar'), path.join(lab, 'cache', 'plugins', 'PlaceholderAPI.jar')];
  for (const jar of jars) fs.copyFileSync(jar, path.join(dir, 'plugins', path.basename(jar)));
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'config.yml'), [
    'config-version: 2',
    'economy:', '  seller-tax:', '    enabled: true', '    percent: 5', '  deposit-to: ServerBank',
    'anti-abuse:', '  same-address: "off"',
    'broadcast:', '  cooldown-seconds: 0',
    // Alerts and the filled title follow a sale at once instead of after the 3-second gathering window, so the
    // shots do not depend on how fast the busy capture server ticks.
    'notifications:', '  batch-seconds: 0',
    'update-checker:', '  enabled: false', 'metrics:', '  enabled: false',
  ].join('\n') + '\n');
  return dir;
}

/** Completed deliveries for price insight, written straight into the database. */
function seedHistory(dir) {
  const db = path.join(dir, 'plugins', 'DonutOrders', 'donutorders.db');
  const now = Date.now();
  const rows = [];
  const deliveries = [['DIAMOND', 64, 88], ['DIAMOND', 128, 91], ['DIAMOND', 32, 95], ['DIAMOND', 64, 90], ['DIAMOND', 16, 97],
    ['DIAMOND', 96, 92], ['ENDER_PEARL', 64, 27], ['ENDER_PEARL', 128, 29], ['OBSIDIAN', 256, 20], ['GOLDEN_CARROT', 512, 10.5]];
  deliveries.forEach(([material, amount, price], i) => {
    const at = now - (i + 1) * 3_600_000;
    rows.push(`INSERT INTO donut_order_deliveries(order_id, seller_uuid, seller_name, owner_uuid, owner_name, item_key, material, amount, price,`
      + ` tax, delivered_at) VALUES ('${crypto.randomUUID()}', '${crypto.randomUUID()}', 'Willow', '${crypto.randomUUID()}', 'Draxen',`
      + ` '${material}', '${material}', ${amount}, ${price}, 0, ${at});`);
  });
  execFileSync('sqlite3', [db], { input: rows.join('\n') });
}

async function board(server) {
  const line = await server.lab('board', /\[LAB\] board .*/);
  return line.replace(/.*\[LAB\] board /, '').split(',').map((entry) => {
    const [material, owner] = entry.split(':');
    return { material, owner };
  });
}

async function mine(server, player) {
  const line = await server.lab(`orders ${player}`, new RegExp(`\\[LAB\\] orders ${player}.*`));
  return line.replace(/.*\[LAB\] orders \S+ ?/, '').split(',').filter(Boolean).map((entry) => {
    const [id, status, progress, material, waiting] = entry.split(':');
    return { id, status, progress, material, waiting: Number(waiting) };
  });
}

/**
 * A bot runs /orders sell all, accepts the confirmation menu (sell.confirm is on by default) and waits for
 * the sale message. The buyer's alert and title follow within a tick (notifications.batch-seconds is 0 here).
 */
async function sellAll(bot) {
  const opened = new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), 5000);
    bot.once('windowOpen', (window) => { clearTimeout(timer); resolve(window); });
  });
  bot.chat('/orders sell all');
  const window = await opened;
  if (!window) throw new Error(`${bot.username}: no sell confirmation opened`);
  await sleep(600);
  const seen = bot.chatLog.length;
  await bot.clickWindow(11, 0, 0);
  for (let waited = 0; waited < 8000; waited += 100) {
    if (bot.chatLog.slice(seen).some((line) => /\b(Delivered|Sold)\b/.test(line))) return;
    await sleep(100);
  }
  throw new Error(`${bot.username}: the sale was not confirmed in chat`);
}

const find = (list, predicate, fallback = 0) => {
  const index = list.findIndex(predicate);
  return index < 0 ? fallback : index;
};

/** A server started with DonutOrders 1.x files: its console shows the upgrade (for the upgrade card). */
async function upgradeConsole(java, jar) {
  const dir = path.join(lab, 'capture', 'upgrade');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins', 'DonutOrders'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), ['online-mode=false', `server-port=${PORT}`, 'level-type=minecraft\\:flat',
    'generate-structures=false', 'view-distance=3'].join('\n') + '\n');
  const target = path.join(repo, 'target');
  const plugin = fs.readdirSync(target).find((f) => /^DonutOrders-.*\.jar$/.test(f));
  for (const file of [path.join(target, plugin), path.join(repo, 'tools', 'smoke', 'fixture', 'target', 'OrderLabFixture.jar'),
    path.join(lab, 'cache', 'plugins', 'Vault-1.7.3.jar')]) fs.copyFileSync(file, path.join(dir, 'plugins', path.basename(file)));
  // The 1.x files as 1.9.2 wrote them (trimmed), and a 1.x database planted by the fixture.
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'config.yml'), ['update-check:', '  enabled: false', 'orders:',
    '  default-limit: 3', '  default-expiry-days: 7', '  tax-percent: 2.5', '  broadcast-on-create: true', 'database:', '  type: sqlite',
    '  filename: donutorders.db', 'rank-limits:', '  default: 3', '  vip: 8'].join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'items.yml'), 'allowed-items:\n  - DIAMOND\n  - EMERALD\n  - IRON_INGOT\n  - GOLD_INGOT\n');
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'lab-legacy'), '');
  const server = new Server(dir, java, jar);
  server.start();
  await server.waitFor(/DonutOrders ready/, 300_000, 'upgrade start');
  await sleep(1000);
  const lines = server.lines.filter((line) => /\[DonutOrders\]/.test(line) && !/Loading server plugin|Enabling DonutOrders/.test(line))
    .map((line) => line.replace(/^\[[^\]]*INFO\]:? ?/, ''));
  await server.stop();
  fs.mkdirSync(path.join(repo, 'marketing', 'source', 'console'), { recursive: true });
  fs.writeFileSync(path.join(repo, 'marketing', 'source', 'console', 'upgrade.txt'), lines.join('\n') + '\n');
  console.log(`  upgrade console: ${lines.length} lines`);
}

async function main() {
  const java = path.join(lab, 'cache', 'jdk', '21', 'bin', 'java');
  await upgradeConsole(java, path.join(lab, 'cache', 'servers', `paper-${VERSION}.jar`));
  if (process.argv.includes('--upgrade-only')) return;
  const dir = prepareServer();
  const jar = path.join(lab, 'cache', 'servers', `paper-${VERSION}.jar`);
  const first = new Server(dir, java, jar);
  first.start();
  await first.waitFor(/DonutOrders ready/, 300_000, 'first start');
  await first.lab('seed 40', /\[LAB\] seed .*/);
  await sleep(2000);
  first.command('stop');
  await first.exited;
  seedHistory(dir);

  const server = new Server(dir, java, jar);
  server.start();
  await server.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'server start');
  // The plugin may log "ready" before or after the server's "Done".
  for (let i = 0; i < 240 && !server.lines.some((line) => /DonutOrders ready/.test(line)); i++) await sleep(250);
  for (const command of WORLD_SETUP) server.command(command);
  await server.lab('money Moonbeam 2500000');
  await server.lab('money Kiri 50000');

  const xvfb = spawn('Xvfb', [DISPLAY, '-screen', '0', `${W}x${H}x24`, '-nolisten', 'tcp'], { stdio: 'ignore' });
  await sleep(1500);
  writeClientOptions();
  const client = spawn(portablemc, ['--main-dir', clientDir, 'start', `mojang:${VERSION}`, '-u', PLAYER, '--join-server', '127.0.0.1',
    '--join-server-port', String(PORT), '--resolution', `${W}x${H}`, '--jvm', java, '--jvm-arg=-Xmx3G'],
  { env: { ...process.env, DISPLAY, LIBGL_ALWAYS_SOFTWARE: '1', __GL_SYNC_TO_VBLANK: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
  const clientLog = fs.createWriteStream(path.join(lab, 'capture', 'client.log'));
  client.stdout.pipe(clientLog);
  client.stderr.pipe(clientLog);
  try {
    await server.waitFor(new RegExp(`${PLAYER} joined the game`), 600_000, 'client join');
    console.log('client joined');
    await scenes(server);
  } finally {
    client.kill('SIGTERM');
    await sleep(2000);
    await server.stop();
    xvfb.kill('SIGTERM');
  }
}

async function scenes(server) {
  // A view over a cherry grove: the backdrop of every menu.
  server.mark();
  server.command('locate biome minecraft:cherry_grove');
  const located = await server.waitFor(/cherry_grove is at \[(-?\d+), [^,]+, (-?\d+)\]/, 120_000, 'locate');
  const [cx, cz] = [Number(located[1]), Number(located[2])];
  server.command(`spreadplayers ${cx} ${cz} 0 1 false ${PLAYER}`);
  await sleep(1500);
  server.command(`execute as ${PLAYER} at @s run setblock ~ ~4 ~ minecraft:cherry_leaves[persistent=true]`);
  server.command(`execute as ${PLAYER} at @s run tp @s ~ ~5 ~ 135 14`);
  server.command(`recipe give ${PLAYER} *`);
  server.command(`advancement grant ${PLAYER} only minecraft:story/mine_diamond`);
  server.command(`advancement grant ${PLAYER} only minecraft:story/form_obsidian`);
  await sleep(50_000); // terrain loads slowly with software rendering
  x('key', 'F1');
  await sleep(1500);
  screenshot('scenery');
  x('key', 'F1');
  await sleep(800);

  // Moonbeam's own orders, created through the plugin's commands.
  await server.lab(`sudo ${PLAYER} order create diamond 640 110 7d`);
  await server.lab(`sudo ${PLAYER} order create enchanted_book:mending:1 3 4500 3d`);
  await server.lab(`sudo ${PLAYER} order create golden_carrot 1024 11.5 14d`);
  await server.lab(`sudo ${PLAYER} order create blaze_rod 256 140 1d`);
  await sleep(1500);

  // Kiri sells diamonds into the best order (Moonbeam's): the delivery alert lands in Moonbeam's chat.
  const kiri = await joinBot('Kiri', VERSION, null, PORT);
  try {
    await server.lab('give Kiri DIAMOND 192');
    await sleep(800);
    await sellAll(kiri);
    await sleep(1500);
    screenshot('delivery-alert');
    // Two of the three mending books: the order is not filled yet.
    for (let i = 0; i < 2; i++) await server.lab('book Kiri mending 1');
    await sleep(500);
    await sellAll(kiri);
    await sleep(3000);
  } finally {
    kiri.quit();
  }
  // Chat lines fade after 10 seconds: the menu shots start with a clean chat.
  await sleep(10500);
  const sold = (await mine(server, PLAYER)).find((o) => o.material === 'DIAMOND');
  if (!sold || sold.progress.startsWith('0/')) throw new Error(`Kiri's diamonds did not deliver: ${JSON.stringify(sold)}`);

  // The board.
  const page = await board(server);
  const top = find(page, (o) => o.owner !== PLAYER && o.material === 'NETHERITE_INGOT');
  const pearls = find(page, (o) => o.material === 'ENDER_PEARL');
  const book = find(page, (o) => o.material === 'ENCHANTED_BOOK' && o.owner !== PLAYER);
  console.log(`  board: netherite ${top}, pearls ${pearls}, book ${book}`);
  await server.lab(`sudo ${PLAYER} orders`);
  await sleep(2500);
  await hover(top, 6);
  screenshot('board-hover');
  await hover(book, 6);
  screenshot('board-book-hover');
  await hover(46, 6);
  screenshot('board-sort');
  await hover(47, 6);
  screenshot('board-category');
  x('key', 'Escape');
  await sleep(800);

  // Delivering: ender pearls (loose and in a shulker box) into the delivery menu.
  await server.lab(`give ${PLAYER} ENDER_PEARL 48`);
  await server.lab(`shulker ${PLAYER} ENDER_PEARL 96`);
  await sleep(500);
  const recording = record('deliver', 12);
  await sleep(400);
  await server.lab(`sudo ${PLAYER} orders`);
  await sleep(1800);
  await hover(pearls, 6, 900);
  await clickSlot(pearls, 6, 1800);
  await clickSlot(47, 6, 1500);
  await hover(49, 6, 1200);
  screenshot('deliver-filled');
  await clickSlot(49, 6, 1500);
  // The board reopens after a delivery; close it so the chat line is not dimmed.
  x('key', 'Escape');
  await sleep(900);
  screenshot('delivered-chat');
  gif(await recording, 'deliver');
  x('key', 'Escape');
  await sleep(800);

  // Selling everything into the best orders.
  await server.lab(`give ${PLAYER} OBSIDIAN 320`);
  await server.lab(`give ${PLAYER} GOLDEN_CARROT 256`);
  await server.lab(`give ${PLAYER} SLIME_BALL 64`);
  await sleep(500);
  await server.lab(`sudo ${PLAYER} orders sell all`);
  await sleep(2500);
  await hover(13, 3, 1200);
  screenshot('sell-confirm');
  await clickSlot(11, 3, 3000);
  screenshot('sold-chat');

  // A new order: the item picker, then the setup menu with price insight.
  await server.lab(`sudo ${PLAYER} order new`);
  await sleep(2500);
  await hover(0, 6);
  screenshot('catalog');
  await clickSlot(0, 6, 2500);
  await hover(47, 6, 1500);
  screenshot('create-insight');
  await clickSlot(26, 6, 900);
  await clickSlot(26, 6, 900);
  await hover(49, 6, 1200);
  screenshot('create-confirm');
  x('key', 'Escape');
  await sleep(800);

  // Enchanted books: category Books, then the enchantment and level pickers.
  await server.lab(`sudo ${PLAYER} order new`);
  await sleep(2500);
  for (let i = 0; i < 6; i++) await clickSlot(46, 6, 700);
  await clickSlot(1, 6, 2000);
  await hover(20, 6);
  screenshot('enchant-picker');
  x('key', 'Escape');
  await sleep(800);

  // Your orders: items waiting, money held, collect all.
  const own = await mine(server, PLAYER);
  const diamonds = find(own, (o) => o.material === 'DIAMOND');
  await server.lab(`sudo ${PLAYER} order`);
  await sleep(2500);
  await hover(diamonds, 6);
  screenshot('my-orders');
  await hover(49, 6);
  screenshot('my-orders-collect');
  // Changing an order: add 64, raise the price, add a day.
  await clickSlot(diamonds, 6, 2000, 3);
  await clickSlot(25, 6, 700);
  await clickSlot(33, 6, 700);
  await clickSlot(39, 6, 700);
  await hover(49, 6, 1200);
  screenshot('edit');
  x('key', 'Escape');
  await sleep(800);

  // Price check in chat.
  server.command(`execute as ${PLAYER} run item replace entity @s weapon.mainhand with diamond 32`);
  await sleep(500);
  await server.lab(`sudo ${PLAYER} orders price`);
  await sleep(2500);
  screenshot('price-check');

  // The last mending book fills Moonbeam's order: the title.
  const filler = await joinBot('Kiri', VERSION, null, PORT);
  try {
    await server.lab('book Kiri mending 1');
    await sleep(500);
    await sellAll(filler);
    // The title fades in over 0.25 s and stays for 2.5 s.
    await sleep(1100);
    screenshot('filled-title');
    await sleep(4000);
    screenshot('filled-chat');
  } finally {
    filler.quit();
  }

  // Staff: the board in staff mode, and the console report.
  server.command(`op ${PLAYER}`);
  await sleep(1000);
  await server.lab(`sudo ${PLAYER} orders`);
  await sleep(2500);
  await hover(top, 6);
  screenshot('admin-board');
  x('key', 'Escape');
  await sleep(800);
  server.mark();
  const from = server.lines.length;
  server.command('orderadmin info');
  await sleep(2500);
  const report = server.lines.slice(from).map((line) => line.replace(/^\[[^\]]*INFO\]:? ?/, '')).filter((line) => line.trim());
  fs.mkdirSync(path.join(repo, 'marketing', 'source', 'console'), { recursive: true });
  fs.writeFileSync(path.join(repo, 'marketing', 'source', 'console', 'admin-info.txt'), report.join('\n') + '\n');
  console.log(`  console: ${report.length} lines`);
}

/** Servers started by this run, killed if it fails so their ports are free for the next one. */
const started = [];
const originalStart = Server.prototype.start;
Server.prototype.start = function start() {
  started.push(this);
  return originalStart.call(this);
};

main().catch((error) => {
  console.error(error);
  for (const server of started) if (server.process && server.process.exitCode === null) server.process.kill('SIGKILL');
  process.exit(1);
});
