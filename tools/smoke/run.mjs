// Boots real Paper and Folia servers across the supported range and drives DonutOrders with mineflayer
// bots. Every version runs the same scenario (1.x import, create, deliver, collect, books, shulkers, sell,
// cancel, expiry, server orders, admin commands) and the console is scanned for plugin errors.
//
// Usage (from the repository root, after `mvn package` and the fixture build, see README.md):
//   node tools/smoke/run.mjs                 # the whole matrix
//   node tools/smoke/run.mjs paper-26.3      # selected targets (project-version)
//
// Downloads (JDKs, server jars, Vault/VaultUnlocked, ViaVersion/ViaBackwards) are cached in
// $ORDERLAB_HOME (default ~/.cache/donutorders-smoke).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import mineflayer from 'mineflayer';
import { scenario } from './scenario.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const lab = process.env.ORDERLAB_HOME || path.join(os.homedir(), '.cache', 'donutorders-smoke');
const cache = path.join(lab, 'cache');
const PORT = Number(process.env.ORDERLAB_PORT || 25599);

// Bots speak the newest protocol mineflayer knows; newer servers get ViaVersion + ViaBackwards.
export const MATRIX = [
  { project: 'paper', version: '1.20.1', java: 17, bot: '1.20.1', economy: 'Vault' },
  { project: 'paper', version: '1.20.4', java: 17, bot: '1.20.4', economy: 'VaultUnlocked' },
  { project: 'paper', version: '1.20.6', java: 21, bot: '1.20.6', economy: 'Vault' },
  { project: 'paper', version: '1.21.1', java: 21, bot: '1.21.1', economy: 'VaultUnlocked' },
  { project: 'paper', version: '1.21.4', java: 21, bot: '1.21.4', economy: 'Vault' },
  { project: 'paper', version: '1.21.8', java: 21, bot: '1.21.8', economy: 'VaultUnlocked' },
  { project: 'paper', version: '1.21.11', java: 21, bot: '1.21.11', economy: 'Vault' },
  { project: 'paper', version: '26.1.2', java: 25, bot: '26.1', economy: 'VaultUnlocked', via: true },
  { project: 'paper', version: '26.2', java: 25, bot: '26.1', economy: 'Vault', via: true },
  { project: 'paper', version: '26.3', java: 25, bot: '26.1', economy: 'VaultUnlocked', via: true },
  { project: 'folia', version: '1.20.4', java: 17, bot: '1.20.4', economy: 'VaultUnlocked' },
  { project: 'folia', version: '1.21.4', java: 21, bot: '1.21.4', economy: 'VaultUnlocked' },
  { project: 'folia', version: '1.21.11', java: 21, bot: '1.21.11', economy: 'VaultUnlocked' },
  { project: 'folia', version: '26.3', java: 25, bot: '26.1', economy: 'VaultUnlocked', via: true },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function fetchJson(url) {
  const response = await fetch(url, { headers: { 'User-Agent': 'NightBeam-DonutOrders-smoke' } });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  return response.json();
}

async function download(url, file) {
  if (fs.existsSync(file)) return file;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const response = await fetch(url, { headers: { 'User-Agent': 'NightBeam-DonutOrders-smoke' } });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  fs.writeFileSync(file + '.part', Buffer.from(await response.arrayBuffer()));
  fs.renameSync(file + '.part', file);
  return file;
}

export async function jdk(major) {
  const dir = path.join(cache, 'jdk', String(major));
  const java = path.join(dir, 'bin', 'java');
  if (fs.existsSync(java)) return java;
  const archive = await download(`https://api.adoptium.net/v3/binary/latest/${major}/ga/linux/x64/jdk/hotspot/normal/eclipse`, dir + '.tar.gz');
  fs.mkdirSync(dir, { recursive: true });
  await run('tar', ['-xzf', archive, '-C', dir, '--strip-components=1']);
  fs.rmSync(archive);
  return java;
}

export async function serverJar(project, version) {
  const file = path.join(cache, 'servers', `${project}-${version}.jar`);
  if (fs.existsSync(file)) return file;
  const build = await fetchJson(`https://fill.papermc.io/v3/projects/${project}/versions/${version}/builds/latest`);
  return download(build.downloads['server:default'].url, file);
}

export async function modrinth(project, file, loader = 'paper') {
  const target = path.join(cache, 'plugins', file);
  if (fs.existsSync(target)) return target;
  const versions = await fetchJson(`https://api.modrinth.com/v2/project/${project}/version?loaders=${encodeURIComponent(JSON.stringify([loader]))}`);
  const release = versions.find((v) => v.version_type === 'release') || versions[0];
  return download(release.files.find((f) => f.primary)?.url || release.files[0].url, target);
}

export async function economyJar(name) {
  if (name === 'Vault') return download('https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar', path.join(cache, 'plugins', 'Vault-1.7.3.jar'));
  return modrinth('vaultunlocked', 'VaultUnlocked.jar');
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', ...options });
    child.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`))));
  });
}

export function pluginJar() {
  if (process.env.PLUGIN_JAR) return process.env.PLUGIN_JAR;
  const target = path.join(repo, 'target');
  const jar = fs.readdirSync(target).find((f) => /^DonutOrders-.*\.jar$/.test(f));
  if (!jar) throw new Error('Build the plugin first: mvn -DskipTests package');
  return path.join(target, jar);
}

/** One running server: stdin for console commands, and a line log that waitFor() scans. */
export class Server {
  constructor(dir, java, jar) {
    this.dir = dir;
    this.java = java;
    this.jar = jar;
    this.lines = [];
    this.cursor = 0;
    this.waiters = [];
  }

  start() {
    const log = fs.createWriteStream(path.join(this.dir, 'console.log'));
    this.process = spawn(this.java, ['-Xms1G', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dfile.encoding=UTF-8',
      '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', this.jar, '--nogui'], { cwd: this.dir });
    let buffer = '';
    const onData = (chunk) => {
      log.write(chunk);
      buffer += chunk.toString('utf8');
      let index;
      while ((index = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, index).replace(/\x1b\[[0-9;]*m/g, '');
        buffer = buffer.slice(index + 1);
        this.lines.push(line);
        this.waiters = this.waiters.filter((waiter) => !waiter.test(line));
      }
    };
    this.process.stdout.on('data', onData);
    this.process.stderr.on('data', onData);
    this.exited = new Promise((resolve) => this.process.on('exit', resolve));
  }

  /** Resolves with the first line after the cursor that matches. */
  waitFor(pattern, timeout = 30_000, label = String(pattern)) {
    for (let i = this.cursor; i < this.lines.length; i++) {
      const match = this.lines[i].match(pattern);
      if (match) {
        this.cursor = i + 1;
        return Promise.resolve(match);
      }
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((waiter) => waiter !== entry);
        reject(new Error(`timed out waiting for ${label}`));
      }, timeout);
      const entry = {
        test: (line) => {
          const match = line.match(pattern);
          if (!match) return false;
          clearTimeout(timer);
          this.cursor = this.lines.length;
          resolve(match);
          return true;
        },
      };
      this.waiters.push(entry);
    });
  }

  mark() {
    this.cursor = this.lines.length;
  }

  command(line) {
    this.process.stdin.write(line + '\n');
  }

  /** Runs a fixture command and returns the [LAB] report line it prints. */
  async lab(line, expect) {
    this.mark();
    this.command('lab ' + line);
    const match = await this.waitFor(expect || new RegExp(`\\[LAB\\] (${line.split(' ')[0]}|error) .*`), 20_000, `lab ${line}`);
    if (match[0].includes('[LAB] error')) throw new Error(match[0]);
    return match[0];
  }

  async stop() {
    if (!this.process || this.process.exitCode !== null) return;
    this.command('stop');
    const timeout = sleep(90_000).then(() => 'timeout');
    if ((await Promise.race([this.exited, timeout])) === 'timeout') this.process.kill('SIGKILL');
  }
}

/** A mineflayer bot with its chat recorded so scenarios can wait for plugin messages. */
export async function joinBot(name, version, chatFile, port = PORT) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version, auth: 'offline', checkTimeoutInterval: 120_000 });
  if (chatFile) bot.on('message', (message) => fs.appendFileSync(chatFile, `${message.toString()}\t${JSON.stringify(message.json)}\n`));
  // The bots never move, so they send no movement packets: when ViaBackwards translates an older
  // protocol, newer servers can reject them ("Invalid move player packet").
  bot.physicsEnabled = false;
  bot.once('login', () => { bot.physicsEnabled = false; });
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, params) => (/^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, params));
  bot.chatLog = [];
  bot.on('messagestr', (message) => bot.chatLog.push(message));
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} did not spawn`)), 120_000);
    bot.once('spawn', () => { clearTimeout(timer); resolve(); });
    bot.once('kicked', (reason) => { clearTimeout(timer); reject(new Error(`${name} kicked: ${JSON.stringify(reason)}`)); });
    bot.once('error', (error) => { clearTimeout(timer); reject(error); });
  });
  await sleep(1500);
  return bot;
}

function prepare(target, jars) {
  const dir = path.join(lab, 'servers', `${target.project}-${target.version}`);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    // 26.x defaults white-list to true.
    'online-mode=false', 'white-list=false', 'enforce-secure-profile=false', `server-port=${PORT}`, 'level-type=minecraft\\:flat', 'generate-structures=false',
    'spawn-protection=0', 'view-distance=3', 'simulation-distance=3', 'spawn-monsters=false', 'spawn-animals=false', 'motd=DonutOrders smoke',
    // 26.x moved spawn-monsters into a game rule; peaceful keeps hostile mobs (slimes on flat worlds) away everywhere.
    'difficulty=peaceful',
  ].join('\n') + '\n');
  for (const jar of jars) fs.copyFileSync(jar, path.join(dir, 'plugins', path.basename(jar)));
  // Settings the scenario relies on; the plugin adds every other default on start.
  fs.mkdirSync(path.join(dir, 'plugins', 'DonutOrders'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'config.yml'), [
    'config-version: 2',
    'economy:', '  seller-tax:', '    enabled: true', '    percent: 10', '  deposit-to: Bank',
    'durations:', '  min: 1s',
    'orders:', '  expiry-check-seconds: 5',
    'sell:', '  confirm: false',
    'anti-abuse:', '  same-address: "off"',
    'notifications:', '  join-delay-seconds: 1', '  batch-seconds: 1',
    'update-checker:', '  enabled: false',
    'metrics:', '  enabled: false',
  ].join('\n') + '\n');
  // The fixture plants a DonutOrders 1.x database before the plugin loads (the import test).
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'lab-legacy'), '');
  return dir;
}

/** Plugin errors in the console: severe/warning lines from the plugin and stack frames in its packages. */
export function pluginErrors(lines) {
  const problems = [];
  lines.forEach((line, index) => {
    const ours = /\[DonutOrders\]/.test(line) && /(ERROR|SEVERE)/.test(line);
    const frame = /at io\.nightbeam\.donutorders\./.test(line);
    const load = /Could not load '.*DonutOrders|Error occurred while enabling DonutOrders|NoSuchMethodError|NoClassDefFoundError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError/.test(line);
    if (ours || load || (frame && !problems.includes(lines[index - 1]))) problems.push(line);
  });
  return problems;
}

async function runTarget(target) {
  const name = `${target.project}-${target.version}`;
  const started = Date.now();
  const java = await jdk(target.java);
  const jars = [pluginJar(), path.join(here, 'fixture', 'target', 'OrderLabFixture.jar'), await economyJar(target.economy),
    await modrinth('placeholderapi', 'PlaceholderAPI.jar')];
  if (target.via) {
    jars.push(await modrinth('viaversion', 'ViaVersion.jar'));
    jars.push(await modrinth('viabackwards', 'ViaBackwards.jar'));
  }
  const dir = prepare(target, jars);
  const server = new Server(dir, java, await serverJar(target.project, target.version));
  const result = { target: name, java: target.java, bot: target.bot, economy: target.economy, steps: [], errors: [] };
  server.start();
  try {
    await server.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'server start');
    await scenario({ server, target, joinBot: (botName) => joinBot(botName, target.bot, path.join(dir, `chat-${botName}.log`)), step: (label, ok, detail = '') => {
      result.steps.push({ label, ok, detail });
      console.log(`  ${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` (${detail})` : ''}`);
    } });
  } catch (error) {
    result.steps.push({ label: 'scenario', ok: false, detail: error.message });
    console.log(`  FAIL scenario (${error.message})`);
  } finally {
    await server.stop();
  }
  result.errors = pluginErrors(server.lines);
  result.ok = result.steps.length > 0 && result.steps.every((s) => s.ok) && result.errors.length === 0;
  result.seconds = Math.round((Date.now() - started) / 1000);
  return result;
}

async function main() {
  const wanted = process.argv.slice(2);
  const targets = MATRIX.filter((t) => wanted.length === 0 || wanted.includes(`${t.project}-${t.version}`));
  const results = [];
  for (const target of targets) {
    console.log(`\n== ${target.project} ${target.version} (Java ${target.java}, bot ${target.bot}${target.via ? ' via ViaBackwards' : ''}, ${target.economy})`);
    const result = await runTarget(target);
    for (const error of result.errors.slice(0, 20)) console.log(`  ERROR ${error}`);
    results.push(result);
  }
  fs.mkdirSync(path.join(lab, 'reports'), { recursive: true });
  const report = path.join(lab, 'reports', `smoke-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
  fs.writeFileSync(report, JSON.stringify(results, null, 2));
  console.log('\n| Server | Java | Steps | Plugin errors | Result |\n| --- | --- | --- | --- | --- |');
  for (const r of results) {
    console.log(`| ${r.target} | ${r.java} | ${r.steps.filter((s) => s.ok).length}/${r.steps.length} | ${r.errors.length} | ${r.ok ? 'PASS' : 'FAIL'} |`);
  }
  console.log(`\nReport: ${report}`);
  process.exit(results.every((r) => r.ok) ? 0 : 1);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
