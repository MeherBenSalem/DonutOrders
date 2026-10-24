// Two Paper servers sharing one MySQL/MariaDB database: an order created on one is delivered on the other, the
// buyer is told on their own server, and two sellers on different servers race to fill the same order.
//
// Usage (after the plugin and fixture builds, see README.md):
//   ORDERLAB_MYSQL="jdbc-free host:port/database user password" node tools/smoke/network.mjs
// for example ORDERLAB_MYSQL="127.0.0.1:3306/donutorders_net orders test". The database must exist and be empty.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Server, joinBot, jdk, serverJar, economyJar, modrinth, pluginJar, pluginErrors } from './run.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const lab = process.env.ORDERLAB_HOME || path.join(os.homedir(), '.cache', 'donutorders-smoke');
const VERSION = process.env.NETWORK_VERSION || '1.21.4';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const [hostDb, user, password] = (process.env.ORDERLAB_MYSQL || '127.0.0.1:3306/donutorders_net orders test').split(' ');
const [hostPort, database] = hostDb.split('/');
const [host, port] = hostPort.split(':');

function prepare(name, serverPort, jars) {
  const dir = path.join(lab, 'network', name);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins', 'DonutOrders'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), ['online-mode=false', 'white-list=false', 'enforce-secure-profile=false',
    `server-port=${serverPort}`, 'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3',
    'simulation-distance=3', 'difficulty=peaceful', `motd=DonutOrders ${name}`].join('\n') + '\n');
  for (const jar of jars) fs.copyFileSync(jar, path.join(dir, 'plugins', path.basename(jar)));
  fs.writeFileSync(path.join(dir, 'plugins', 'DonutOrders', 'config.yml'), [
    'config-version: 2',
    'storage:', '  type: MYSQL', '  mysql:', `    host: ${host}`, `    port: ${port}`, `    database: ${database}`, `    username: ${user}`,
    `    password: "${password}"`,
    'sync:', '  poll-seconds: 1',
    'sell:', '  confirm: false',
    'anti-abuse:', '  same-address: "off"',
    'notifications:', '  join-delay-seconds: 1', '  batch-seconds: 1',
    'update-checker:', '  enabled: false', 'metrics:', '  enabled: false',
  ].join('\n') + '\n');
  return dir;
}

async function waitChat(bot, pattern, timeout = 20_000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const index = bot.chatLog.findIndex((line) => pattern.test(line));
    if (index >= 0) return bot.chatLog.splice(0, index + 1).pop();
    await sleep(100);
  }
  throw new Error(`${bot.username}: no chat matching ${pattern} (saw: ${bot.chatLog.slice(-5).join(' | ')})`);
}

async function main() {
  const java = await jdk(21);
  const jars = [pluginJar(), path.join(here, 'fixture', 'target', 'OrderLabFixture.jar'), await economyJar('Vault'),
    await modrinth('placeholderapi', 'PlaceholderAPI.jar')];
  const jar = await serverJar('paper', VERSION);
  const east = new Server(prepare('east', 25611, jars), java, jar);
  const west = new Server(prepare('west', 25612, jars), java, jar);
  const results = [];
  const step = (label, ok, detail = '') => {
    results.push({ label, ok, detail });
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` (${detail})` : ''}`);
  };
  const check = async (label, body) => {
    try {
      const detail = await body();
      step(label, true, typeof detail === 'string' ? detail : '');
    } catch (error) {
      step(label, false, error.message);
    }
  };
  const number = (line) => Number(line.trim().split(' ').pop());
  const balance = async (server, name) => number(await server.lab(`balance ${name}`, new RegExp(`\\[LAB\\] (balance ${name}|error) .*`)));
  const orders = async (server, name) => (await server.lab(`orders ${name}`, new RegExp(`\\[LAB\\] (orders ${name}|error) .*`)))
    .replace(/.*\[LAB\] orders \S+ ?/, '').split(',').filter(Boolean);

  east.start();
  await east.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'east start');
  west.start();
  await west.waitFor(/Done \([0-9.,]+s\)!/, 300_000, 'west start');
  await sleep(3000);
  console.log(`\n== network: two Paper ${VERSION} servers, MySQL ${host}:${port}/${database}`);
  const bots = [];
  try {
    await east.lab('money Buyer 5000');
    await west.lab('money Seller 0');
    await east.lab('money Rival 0');
    const buyer = await joinBot('Buyer', VERSION, null, 25611);
    const seller = await joinBot('Seller', VERSION, null, 25612);
    const rival = await joinBot('Rival', VERSION, null, 25611);
    bots.push(buyer, seller, rival);

    await check('an order made on east shows on west', async () => {
      buyer.chat('/order create diamond 5 100 1d');
      await waitChat(buyer, /Order created/);
      for (let i = 0; i < 40; i++) {
        if ((await orders(west, 'Buyer')).some((entry) => entry.includes('DIAMOND:0'))) return 'visible on west';
        await sleep(250);
      }
      throw new Error('west never saw the order');
    });

    await check('delivered on west, told and collected on east', async () => {
      await west.lab('give Seller DIAMOND 5');
      await sleep(500);
      seller.chat('/orders sell all');
      await waitChat(seller, /Delivered|Sold/);
      if (await balance(west, 'Seller') !== 500) throw new Error(`seller has ${await balance(west, 'Seller')} on west`);
      await waitChat(buyer, /delivered/i, 20_000);
      buyer.chat('/order collect');
      await waitChat(buyer, /Collected/);
      const count = number(await east.lab('count Buyer DIAMOND', /\[LAB\] (count Buyer|error) .*/));
      if (count !== 5) throw new Error(`buyer has ${count} diamonds`);
      return 'paid 500 on west, 5 diamonds on east';
    });

    await check('two servers race to fill one order', async () => {
      buyer.chat('/order create iron_ingot 10 10 1d');
      await waitChat(buyer, /Order created/);
      await west.lab('give Seller IRON_INGOT 10');
      await east.lab('give Rival IRON_INGOT 10');
      for (let i = 0; i < 40; i++) {
        if ((await orders(west, 'Buyer')).some((entry) => entry.includes('IRON_INGOT'))) break;
        await sleep(250);
      }
      await sleep(500);
      seller.chat('/orders sell all');
      rival.chat('/orders sell all');
      await sleep(4000);
      const paid = (await balance(west, 'Seller')) - 500 + (await balance(east, 'Rival'));
      const kept = number(await west.lab('count Seller IRON_INGOT', /\[LAB\] (count Seller|error) .*/))
        + number(await east.lab('count Rival IRON_INGOT', /\[LAB\] (count Rival|error) .*/));
      const entry = (await orders(east, 'Buyer')).find((line) => line.includes('IRON_INGOT'));
      if (paid !== 100 || kept !== 10 || !entry.includes(':10/10:')) {
        throw new Error(`paid ${paid}, kept ${kept}, order ${entry}`);
      }
      return 'exactly 10 delivered, 100 paid, 10 handed back';
    });
  } finally {
    for (const bot of bots) bot.quit();
    await sleep(1000);
    await Promise.all([east.stop(), west.stop()]);
  }
  const errors = [...pluginErrors(east.lines), ...pluginErrors(west.lines)];
  for (const error of errors.slice(0, 20)) console.log(`  ERROR ${error}`);
  const ok = results.every((r) => r.ok) && errors.length === 0;
  console.log(`\nnetwork: ${results.filter((r) => r.ok).length}/${results.length} checks, ${errors.length} plugin errors: ${ok ? 'PASS' : 'FAIL'}`);
  fs.mkdirSync(path.join(lab, 'reports'), { recursive: true });
  fs.writeFileSync(path.join(lab, 'reports', `network-${new Date().toISOString().replace(/[:.]/g, '-')}.json`),
    JSON.stringify({ version: VERSION, results, errors, ok }, null, 2));
  process.exit(ok ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
