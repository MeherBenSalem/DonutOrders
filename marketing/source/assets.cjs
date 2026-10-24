// The listing artwork. Each entry returns { html, width, height, transparent?, file? } for render.cjs.
// Crops are pixel boxes [x, y, w, h] of the 1920x1080 captures in images/screenshots/ (GUI scale 3), measured by
// tools/marketing/crops.py into crops.json: the chest part of each menu joined with the hovered slot's tooltip, and
// "<name>-tip" for a tooltip alone.
const fs = require('fs');
const path = require('path');
const r = require('./render.cjs');

const HEADERS = {
  board: 'The Board', delivering: 'Delivering', selling: 'Selling', creating: 'New Orders', insight: 'Price Insight',
  'your-orders': 'Your Orders', notifications: 'Notifications', economy: 'Economy & Rules', safety: 'Built to Be Safe',
  integrations: 'Integrations', staff: 'Staff Tools', upgrade: 'Upgrading from 1.x', config: 'Configuration',
  compatibility: 'Compatibility', commands: 'Commands & Permissions', faq: 'Questions', gallery: 'Gallery',
};

const MEASURED = path.join(__dirname, 'crops.json');
const CROP = Object.assign({
  // Chat strips (bottom left) and the title over the world are fixed regions.
  'delivered-chat-line': [0, 860, 1100, 110],
  'sold-chat-line': [0, 900, 1100, 70],
  'alert-chat-line': [0, 900, 1250, 70],
  'price-check': [0, 830, 1150, 140],
  'filled-scene': [400, 200, 1120, 470],
}, fs.existsSync(MEASURED) ? JSON.parse(fs.readFileSync(MEASURED, 'utf8')) : {});
const FILES = {
  'delivered-chat-line': 'delivered-chat', 'sold-chat-line': 'sold-chat', 'alert-chat-line': 'delivery-alert',
  'filled-scene': 'filled-title',
};

/** Width and height of a crop scaled to fit maxW x maxH. */
const fitted = (name, maxW, maxH) => {
  const box = CROP[name];
  if (!box) throw new Error(`no crop for ${name}`);
  const width = Math.round(Math.min(maxW, maxH * box[2] / box[3]));
  return [width, Math.round(width * box[3] / box[2])];
};
const fileOf = (name) => (FILES[name] || name.replace(/-tip$/, '')) + '.jpg';

/** A framed crop of a capture, as wide as possible within maxW x maxH. */
const shotArt = (name, maxW, maxH, style = '') => r.framed(r.region(fileOf(name), ...CROP[name], fitted(name, maxW, maxH)[0]), style);

// The card art area is 850 x 780. main() sits top-left, inset() bottom-right; each label is pinned to its frame.
const ART_W = 850;
const ART_H = 780;
const main = (name, maxW, maxH, top, text, kind = '') =>
  `${shotArt(name, maxW, maxH, `position:absolute;left:0;top:${top}px`)}${text ? r.label(text, `left:24px;top:${top - 22}px`, kind) : ''}`;
const inset = (name, maxW, maxH, bottom, text, kind = 'gold') => {
  const [w, h] = fitted(name, maxW, maxH);
  const left = ART_W + 20 - w;
  const top = ART_H - bottom - h;
  const labelTop = h < 200 ? top - 50 : top - 22;
  return `${shotArt(name, maxW, maxH, `position:absolute;left:${left}px;top:${top}px`)}${text ? r.label(text, `left:${left + 24}px;top:${labelTop}px`, kind) : ''}`;
};

const card = (key, options) => () => ({ width: 1600, height: 900, file: `card-${key}.png`, html: r.card(options) });

const assets = {
  icon: () => ({ width: 512, height: 512, html: r.page(512, 512, `
    <div style="position:absolute;inset:0;background:radial-gradient(circle at 50% 42%,#1f3a32 0%,#0f1e19 58%,#080f0d 100%)"></div>
    ${r.sprinkles(26, 0.55)}
    <div style="position:absolute;left:46px;top:46px;width:420px;height:420px;border-radius:50%;
      background:radial-gradient(circle,rgba(73,214,160,.30) 0%,rgba(73,214,160,0) 66%)"></div>
    <div style="position:absolute;left:64px;top:56px;filter:drop-shadow(0 18px 26px rgba(0,0,0,.6))">${r.donutSvg(384)}</div>
    <div style="position:absolute;right:40px;bottom:40px;display:flex;align-items:center;gap:10px;padding:12px 24px 11px 20px;border-radius:999px;
      background:linear-gradient(180deg,#ffd76a,#f0a92e);box-shadow:0 10px 26px rgba(0,0,0,.55),inset 0 -3px 0 rgba(120,70,0,.35)">
      <span style="font-family:DoDisplay;font-weight:900;font-size:44px;line-height:1;color:#3b2203;letter-spacing:.04em">BUY</span></div>`) }),

  divider: () => ({ width: 1400, height: 40, transparent: true, html: r.divider() }),

  banner: () => ({ width: 1800, height: 600, html: r.page(1800, 600, `
    ${r.backdrop(0.5, 6)}
    ${r.sprinkles(60, 0.4)}
    <div style="position:absolute;left:870px;top:-40px;width:1120px;height:700px;perspective:1700px">
      <div style="transform:rotateY(-17deg) rotateX(4deg) translateZ(0);transform-origin:left center;margin-top:62px">
        ${shotArt('board-hover', 860, 500)}
      </div>
    </div>
    <div style="position:absolute;inset:0;background:linear-gradient(90deg,rgba(14,10,20,.95) 0%,rgba(14,10,20,.86) 40%,rgba(14,10,20,.15) 62%,rgba(14,10,20,0) 100%)"></div>
    <div style="position:absolute;left:90px;top:0;bottom:0;width:820px;display:flex;flex-direction:column;justify-content:center;z-index:2">
      <div class="kicker"><span class="dot"></span>${r.RELEASE.version} · ${r.RELEASE.name}</div>
      <div style="display:flex;align-items:center;gap:26px;margin:22px 0 6px">
        <div style="width:118px;height:118px;filter:drop-shadow(0 12px 20px rgba(0,0,0,.6))">${r.donutSvg(118)}</div>
        <div class="display" style="font-size:104px;line-height:.92">Donut<br><span class="grad">Orders</span></div>
      </div>
      <div class="rule" style="width:72%;margin:26px 0 24px"></div>
      <div style="font-size:31px;line-height:1.35;color:var(--text)">Post what you want. Get it delivered. Pay only for<br>what arrives, on a board nobody can dupe. <b class="hi">Free.</b></div>
      <div style="margin-top:30px;display:flex;align-items:center">
        <span class="pill"><i></i>Paper</span><span class="pill"><i></i>Folia</span><span class="pill"><i></i>Purpur</span>
        <span style="font-family:DoDisplay;font-weight:700;font-size:26px;color:var(--gold-hi);margin-left:12px">${r.RANGE}</span>
      </div>
    </div>`) }),

  board: card('board', {
    kicker: 'The Board', title: 'Every order in one place',
    points: [
      'The four SMP sorts: <b class="hi">money per item</b>, recent, most delivered, most paid',
      'Nine categories and search by item, enchantment or player',
      '<b class="hi">Only what I can deliver</b> in one click',
      'Buyer, price, progress and time left on every order',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('board-book-hover', 850, 450, 40, 'Every order, every detail')}
      ${inset('board-category-tip', 300, 290, 0, 'Nine categories')}
    </div>`,
  }),

  deliver: card('deliver', {
    kicker: 'Delivering', title: 'Deliver in two clicks',
    points: [
      'Only items the order takes go in; the payout shows as you fill it',
      '<b class="hi">Add from inventory</b>, or shift-click on the board',
      '<b class="hi">Shulker boxes are opened</b>, the box comes back',
      'Paid on the spot; extras come straight back',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('deliver-filled', 800, 560, 40, 'The delivery menu')}
      ${inset('delivered-chat-line', 760, 110, 10, 'Paid at once')}
    </div>`,
  }),

  sell: card('sell', {
    kicker: 'Selling', title: 'Sell everything in one command',
    points: [
      '<code class="inline">/orders sell all</code>: everything any order wants',
      'Best price first, across every open order',
      'See the payout and the tax before anything moves',
      'Sell plugins can route into orders through the API',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('sell-confirm', 850, 470, 40, 'One confirmation')}
      ${inset('sold-chat-line', 760, 70, 40, 'And it is done')}
    </div>`,
  }),

  create: card('create', {
    kicker: 'New Orders', title: 'Order anything',
    points: [
      'Every item your server has, by category or search',
      'Enchanted books by enchantment and level',
      '<b class="hi">Order the item in your hand</b>: potions, custom items',
      'Prices like <code class="inline">2.5k</code>, a duration, the full cost up front',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('catalog', 600, 470, 40, 'The item picker')}
      ${inset('create-confirm', 480, 360, 0, 'Before you confirm')}
    </div>`,
  }),

  insight: card('insight', {
    kicker: 'Price Insight', title: 'Know what to pay',
    points: [
      'Average, range and last price of recent deliveries',
      'Open demand and the best price on the board',
      '<b class="hi">Outbid</b> or <b class="hi">use the average</b> in one click',
      'A warning when a price is far above the market',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('create-insight', 780, 480, 40, 'While you create')}
      ${inset('price-check', 760, 150, 10, '/orders price')}
    </div>`,
  }),

  orders: card('orders', {
    kicker: 'Your Orders', title: 'Nothing is ever lost',
    points: [
      'Collect one order or <b class="hi">collect all</b>; whole stacks only',
      '<b class="hi">Change an order</b>: more items, a new price, more time',
      'Cancel for an instant refund; items stay to collect',
      'A mailbox for anything that did not fit',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('my-orders', 700, 440, 40, 'Ready to collect')}
      ${inset('edit', 440, 290, 0, 'Change an order')}
    </div>`,
  }),

  notifications: card('notifications', {
    kicker: 'Notifications', title: 'Never miss a delivery',
    points: [
      'Who delivered how many, one line for a busy order',
      'A title and a sound when an order fills',
      'A summary of what happened while you were away',
      'Follows you to whichever server you are on',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('filled-scene', 830, 500, 70, 'The moment it fills')}
      ${inset('alert-chat-line', 800, 60, 60, 'Delivery alerts')}
    </div>`,
  }),

  economy: card('economy', {
    kicker: 'Economy & Rules', title: 'Your economy, your rules',
    points: [
      'Creation fee and seller tax, with tiers and a server account',
      'Price, amount and value caps, per item too',
      'Limits by permission or LuckPerms group',
      'Blacklist, whitelist, worlds, game modes, cooldown',
    ],
    art: `<div style="position:relative;height:780px">
      <div style="position:absolute;left:0;top:10px">${r.codePanel('config.yml (defaults)', r.yaml(r.yamlSection('src/main/resources/config.yml', 'economy').filter((line) => !line.trim().startsWith('#'))), { width: 600, size: 18 })}</div>
      ${inset('create-confirm', 440, 330, 20, 'What the buyer sees')}
    </div>`,
  }),

  safety: card('safety', {
    kicker: 'Built to Be Safe', title: 'Dupe-safe by design',
    points: [
      'Every delivery, cancel and expiry is a claim only one click can win',
      'Money moves only after the database decides',
      'Items in transit are tracked through logouts and restarts',
      'Folia-safe, and stored in Paper’s versioned item format',
    ],
    art: (() => {
      const seller = (name, server, result, ok) => `<div class="panel" style="padding:20px 24px;width:340px">
        <div style="font-family:DoDisplay;font-weight:700;font-size:15px;letter-spacing:.16em;text-transform:uppercase;color:var(--dim)">${server}</div>
        <div style="font-family:DoDisplay;font-weight:800;font-size:28px;color:var(--hi);margin:4px 0 10px">${name} sells 10 iron</div>
        <div style="font-size:20px;color:${ok ? 'var(--mint)' : 'var(--gold)'};font-weight:600">${result}</div></div>`;
      const sql = `<span style="color:var(--accent-hi)">UPDATE</span> donut_orders <span style="color:var(--accent-hi)">SET</span> delivered = ?, escrow = ?
 <span style="color:var(--accent-hi)">WHERE</span> order_id = ?
   <span style="color:var(--accent-hi)">AND</span> version = ?
   <span style="color:var(--accent-hi)">AND</span> status = <span style="color:var(--gold-hi)">'ACTIVE'</span>`;
      return `<div style="position:relative;height:760px;display:flex;flex-direction:column;justify-content:center;gap:24px">
        <div style="display:flex;justify-content:space-between">
          ${seller('Seller', 'Server west', 'Wins the row: paid 100', true)}
          ${seller('Rival', 'Server east', 'Order full: iron handed back', false)}
        </div>
        <div style="display:flex;justify-content:space-around;font-family:DoDisplay;font-weight:800;font-size:34px;color:var(--accent)"><span>↓</span><span>↓</span></div>
        ${r.codePanel('One order, one winner (MySQL, MariaDB or SQLite)', sql, { width: 760, size: 21 })}
        ${r.stats([['Wanted', '10'], ['Delivered', '10'], ['Paid', '$100'], ['Lost', '0']])}
      </div>`;
    })(),
  }),

  integrations: card('integrations', {
    kicker: 'Integrations', title: 'Plays well with others',
    points: [
      'Discord webhooks for orders, deliveries and suspicious trades',
      '<b class="hi">PlaceholderAPI</b>: best and average prices per item',
      'An API with events and <b class="hi">sell routing</b> for /sell plugins',
      'Vault, VaultUnlocked, LuckPerms, MySQL and Redis',
    ],
    art: (() => {
      const field = (name, value) => `<div style="min-width:150px"><div style="font-weight:700;font-size:16px;color:#f2f3f5">${name}</div><div style="font-size:16px;color:#dbdee1">${value}</div></div>`;
      // A preview of the embed the webhook sends (fields and layout from DiscordWebhook.json).
      const embed = `<div style="width:560px;border-radius:10px;background:#2b2d31;padding:22px 20px 14px;border-left:5px solid #5b8def;font-family:DoText;box-shadow:0 26px 70px rgba(0,0,0,.6)">
        <div style="font-size:15px;color:#dbdee1;font-weight:600;margin-bottom:6px">Willow</div>
        <div style="font-size:18px;color:#f2f3f5;font-weight:700;margin-bottom:6px">Delivery</div>
        <div style="font-size:16px;color:#dbdee1;line-height:1.45;margin-bottom:12px"><b style="color:#f2f3f5">Diamond</b></div>
        <div style="display:flex;gap:18px;flex-wrap:wrap;margin-bottom:12px">${field('Delivered', '192')}${field('Paid', '$21,120.00')}${field('Seller', 'Willow')}${field('Progress', '192/640')}${field('Buyer', 'Moonbeam')}</div>
        <div style="font-size:13px;color:#949ba4">DonutOrders · 3fa2c1d0</div></div>`;
      const papi = ['%donutorders_active%', '%donutorders_collect%', '%donutorders_earned%', '%donutorders_best_DIAMOND%', '%donutorders_average_DIAMOND%']
        .map((p) => `<span style="color:var(--gold-hi)">${p}</span>`).join('\n');
      const api = `<span style="color:var(--accent-hi)">DonutOrdersApi</span>.get().ifPresent(api -&gt;
    api.sellToOrders(player, items).thenAccept(result -&gt;
        shop.sell(player, result.leftovers())));`;
      return `<div style="position:relative;height:780px">
        <div style="position:absolute;left:0;top:34px">${embed}</div>
        ${r.label('Discord webhook', 'left:24px;top:8px', 'mint')}
        <div style="position:absolute;right:-10px;top:330px">${r.codePanel('PlaceholderAPI', papi, { width: 460, size: 18 })}</div>
        <div style="position:absolute;left:0;bottom:0">${r.codePanel('Developer API: sell routing', api, { width: 700, size: 18 })}</div>
      </div>`;
    })(),
  }),

  staff: card('staff', {
    kicker: 'Staff Tools', title: 'Moderate the board',
    points: [
      '<b class="hi">Shift-right-click</b> any order to cancel it, refunded',
      '<code class="inline">/orderadmin view</code>, <code class="inline">cancel</code>, <code class="inline">info</code> and <code class="inline">freeze</code>',
      'Server orders for events and item sinks',
      'Alerts for trades on one connection, and a daily log',
    ],
    art: `<div style="position:relative;height:780px">
      ${main('admin-board', 850, 440, 40, 'Staff view')}
      <div style="position:absolute;right:-20px;bottom:0">${r.codePanel('Console · /orderadmin info', r.consoleText('admin-info.txt').map(r.escapeHtml).join('\n'), { width: 720, size: 15, wrap: true })}</div>
    </div>`,
  }),

  upgrade: card('upgrade', {
    kicker: 'Upgrading from 1.x', title: 'Upgrade in place',
    points: [
      'Open orders keep their money; stashed items wait to collect',
      'Unrefunded 1.x expiries are refunded exactly once',
      'Limits, tax, expiry, database and item list carried over',
      'Old files kept in <code class="inline">legacy-1.x/</code>, old tables untouched',
    ],
    art: `<div style="position:relative;height:780px;display:flex;flex-direction:column;justify-content:center">
      ${r.codePanel('Console · first start of 2.0 on a 1.x server', r.consoleText('upgrade.txt').map(r.escapeHtml).join('\n'), { width: 850, size: 16, wrap: true })}
    </div>`,
  }),

  config: card('config', {
    kicker: 'Configuration', title: 'Make it yours',
    points: [
      'Every word in <code class="inline">messages.yml</code>, menus included',
      'MiniMessage, &amp; codes and hex; optional small caps',
      'Player-made text can never inject formatting',
      'Change any button’s item and model',
    ],
    art: `<div style="position:relative;height:780px">
      <div style="position:absolute;left:0;top:10px">${r.codePanel('messages.yml', r.yaml(r.yamlSection('src/main/resources/messages.yml', 'notify').slice(0, 7)), { width: 820, size: 17, wrap: true })}</div>
      ${inset('filled-scene', 600, 300, 20, 'In game')}
    </div>`,
  }),

  compatibility: card('compatibility', {
    kicker: 'Compatibility', title: 'Tested on every version',
    points: [
      'Paper, Folia, Purpur and other Paper forks',
      `Minecraft ${r.RANGE}, one jar`,
      'Real clients create, deliver, sell and collect on each',
      'Two servers on one MySQL database, raced',
    ],
    art: (() => {
      const data = r.compat();
      const targets = data ? data.targets : [];
      const tile = (t) => `<div class="panel" style="padding:14px 18px 12px;border-color:${t.ok ? 'rgba(73,214,160,.45)' : 'rgba(255,107,107,.6)'}">
        <div style="font-family:DoDisplay;font-weight:700;font-size:14px;letter-spacing:.16em;text-transform:uppercase;color:var(--dim)">${t.project}</div>
        <div style="font-family:DoDisplay;font-weight:800;font-size:31px;color:var(--hi);line-height:1.1">${t.version}</div>
        <div style="font-size:16px;color:${t.ok ? 'var(--mint)' : 'var(--red)'};font-weight:600;margin-top:3px">${t.ok ? '✔' : '✖'} ${t.passed}/${t.steps} checks · Java ${t.java}</div></div>`;
      const network = data && data.network ? `<div class="panel" style="padding:14px 18px 12px;border-color:${data.network.ok ? 'rgba(107,198,255,.55)' : 'rgba(255,107,107,.6)'}">
        <div style="font-family:DoDisplay;font-weight:700;font-size:14px;letter-spacing:.16em;text-transform:uppercase;color:var(--dim)">2 servers · MySQL</div>
        <div style="font-family:DoDisplay;font-weight:800;font-size:31px;color:var(--hi);line-height:1.1">Network</div>
        <div style="font-size:16px;color:${data.network.ok ? 'var(--sky)' : 'var(--red)'};font-weight:600;margin-top:3px">${data.network.ok ? '✔' : '✖'} ${data.network.passed}/${data.network.steps} checks · race</div></div>` : '';
      return `<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:16px;width:780px">${targets.map(tile).join('')}${network}</div>
        <div style="margin-top:18px;font-size:18px;color:var(--dim)">DonutOrders ${data ? data.plugin : ''} · automated run of tools/smoke on ${data ? data.date : ''}</div>`;
    })(),
  }),
};

for (const [key, title] of Object.entries(HEADERS)) {
  assets[`header-${key}`] = () => ({ width: 1400, height: 120, transparent: true, html: r.header(title) });
}
module.exports = assets;
