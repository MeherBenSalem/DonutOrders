// The flow every server in the matrix runs. Slots match the default menu layouts in the plugin.
// Smoke config (run.mjs): 10% seller tax paid to "Bank", durations from 1 s, expiry check every 5 s, no sell
// confirmation, and a DonutOrders 1.x database planted by the fixture before the plugin loads.
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function waitWindow(bot, trigger, timeout = 15_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('windowOpen', onOpen);
      reject(new Error(`${bot.username}: no window opened`));
    }, timeout);
    const onOpen = (window) => {
      clearTimeout(timer);
      // Let the server finish sending the contents.
      setTimeout(() => resolve(window), 700);
    };
    bot.once('windowOpen', onOpen);
    trigger();
  });
}

async function waitChat(bot, pattern, timeout = 15_000) {
  const start = Date.now();
  let seen = 0;
  while (Date.now() - start < timeout) {
    for (; seen < bot.chatLog.length; seen++) {
      if (pattern.test(bot.chatLog[seen])) {
        const line = bot.chatLog[seen];
        bot.chatLog.splice(0, seen + 1);
        return line;
      }
    }
    await sleep(100);
  }
  throw new Error(`${bot.username}: no chat matching ${pattern} (saw: ${bot.chatLog.slice(-5).join(' | ')})`);
}

function slotOf(window, name, from = 0, to = 44) {
  for (let i = from; i <= to; i++) if (window.slots[i]?.name === name) return i;
  return -1;
}

const number = (line) => Number(line.trim().split(' ').pop());
const near = (a, b) => Math.abs(a - b) < 0.001;

/** Runs one named check; a failure is recorded and the scenario continues where it can. */
async function check(step, label, body) {
  try {
    const detail = await body();
    step(label, detail !== false, typeof detail === 'string' ? detail : '');
    return detail !== false;
  } catch (error) {
    step(label, false, error.message);
    return false;
  }
}

export async function scenario({ server, target, joinBot, step }) {
  const balance = async (name) => number(await server.lab(`balance ${name}`, new RegExp(`\\[LAB\\] (balance ${name}|error) .*`)));
  const count = async (name, material) => number(await server.lab(`count ${name} ${material}`, new RegExp(`\\[LAB\\] (count ${name}|error) .*`)));
  const expectBalance = async (name, expected) => {
    // Payments land on the player's thread a moment after the database answers.
    for (let i = 0; i < 20; i++) {
      const value = await balance(name);
      if (near(value, expected)) return value;
      await sleep(250);
    }
    throw new Error(`${name} has ${await balance(name)}, expected ${expected}`);
  };
  const orderIds = async (name) => (await server.lab(`orders ${name}`, new RegExp(`\\[LAB\\] (orders ${name}|error) .*`)))
    .replace(/.*\[LAB\] orders \S+ ?/, '').split(',').filter(Boolean).map((entry) => {
      const [id, status, progress, material, waiting] = entry.split(':');
      return { id, status, progress, material, waiting: Number(waiting) };
    });

  // The plugin opens its database in the background, so "ready" may come after the server's "Done".
  const logged = async (pattern, timeout = 30_000) => {
    for (const start = Date.now(); Date.now() - start < timeout; await sleep(250)) if (server.lines.some((l) => pattern.test(l))) return true;
    return false;
  };
  step('plugin enabled', await logged(/DonutOrders ready:/));
  step('1.x data imported', await logged(/Imported 3 orders and 2 stored stacks from DonutOrders 1\.x/));
  await check(step, 'public API registered', async () => (await server.lab('api')).replace(/.*\[LAB\] /, ''));
  step('PlaceholderAPI hooked', server.lines.some((l) => /placeholders registered/.test(l)));

  await server.lab('money Buyer 10000');
  await server.lab('money Seller 0');
  await server.lab('money Bank 0');
  const seller = await joinBot('Seller');
  const buyer = await joinBot('Buyer');
  step('bots joined', true, `${target.bot}`);

  try {
    // 1.x import: the expired order's 40 was never refunded by 1.x; 2.0 expires it and refunds it once.
    await check(step, '1.x expired order refunded once', async () => {
      for (let i = 0; i < 60; i++) {
        if (near(await balance('LegacyBuyer'), 40)) break;
        await sleep(500);
      }
      await expectBalance('LegacyBuyer', 40);
      await sleep(6000);
      await expectBalance('LegacyBuyer', 40);
      const waiting = number(await server.lab('waiting LegacyBuyer', /\[LAB\] (waiting|error) .*/));
      if (waiting !== 7) throw new Error(`LegacyBuyer has ${waiting} items waiting, expected 7`);
      return 'refund 40, 7 items to collect';
    });

    const created = await check(step, 'buyer creates a diamond order', async () => {
      buyer.chat('/order create diamond 10 100 1d');
      await waitChat(buyer, /Order created/);
      await expectBalance('Buyer', 9000);
      return 'held 1000';
    });

    if (created) {
      await check(step, 'seller quick-delivers from the board', async () => {
        await server.lab('give Seller DIAMOND 16');
        const board = await waitWindow(seller, () => seller.chat('/orders'));
        const slot = slotOf(board, 'diamond');
        if (slot < 0) throw new Error('no diamond order on the board');
        await seller.clickWindow(slot, 0, 1);
        await waitChat(seller, /Delivered/);
        seller.closeWindow(seller.currentWindow || board);
        await expectBalance('Seller', 900);
        await expectBalance('Bank', 100);
        const left = await count('Seller', 'DIAMOND');
        if (left !== 6) throw new Error(`seller kept ${left} diamonds, expected 6`);
        return 'paid 900 after 10% tax, 6 diamonds back';
      });

      await check(step, 'buyer is told and collects', async () => {
        await waitChat(buyer, /delivered/i, 10_000);
        buyer.chat('/order collect');
        await waitChat(buyer, /Collected/);
        const got = await count('Buyer', 'DIAMOND');
        if (got !== 10) throw new Error(`buyer has ${got} diamonds`);
        return '10 diamonds collected';
      });
    }

    await check(step, 'book order through the delivery menu', async () => {
      // Leftover diamonds would sell to the 1.x diamond order later on.
      await server.lab('clear Seller');
      buyer.chat('/order create enchanted_book:sharpness:5 1 500 1d');
      await waitChat(buyer, /Order created/);
      await server.lab('book Seller sharpness 5');
      await sleep(400);
      const board = await waitWindow(seller, () => seller.chat('/orders'));
      const slot = slotOf(board, 'enchanted_book');
      if (slot < 0) throw new Error('no book order on the board');
      const menu = await waitWindow(seller, () => seller.clickWindow(slot, 0, 0));
      const book = slotOf(menu, 'enchanted_book', 54, menu.slots.length - 1);
      if (book < 0) throw new Error('the book is not in the seller inventory');
      await seller.clickWindow(book, 0, 1);
      await sleep(800);
      if (slotOf(menu, 'enchanted_book', 0, 44) < 0) throw new Error('shift-click did not move the book into the menu');
      await seller.clickWindow(49, 0, 0);
      await waitChat(seller, /Delivered/);
      await expectBalance('Seller', 1350);
      return 'delivered, paid 450';
    });

    await check(step, 'shulker boxes are opened', async () => {
      buyer.chat('/order create emerald 20 10 1d');
      await waitChat(buyer, /Order created/);
      await server.lab('shulker Seller EMERALD 32');
      await sleep(400);
      seller.chat('/orders sell all');
      await waitChat(seller, /Delivered|Sold/);
      await expectBalance('Seller', 1530);
      const inside = number(await server.lab('shulkercount Seller', /\[LAB\] (shulkercount|error) .*/));
      if (inside !== 13) throw new Error(`the box holds ${inside} items, expected 12 emeralds and a stick`);
      return '20 taken, box back with 12 and the stick';
    });

    await check(step, 'cancel refunds the held money', async () => {
      const before = await balance('Buyer');
      buyer.chat('/order create stone 64 2 1d');
      await waitChat(buyer, /Order created/);
      await expectBalance('Buyer', before - 128);
      const stone = (await orderIds('Buyer')).find((order) => order.material === 'STONE' && order.status === 'ACTIVE');
      if (!stone) throw new Error('stone order not found');
      buyer.chat(`/order cancel ${stone.id}`);
      await waitChat(buyer, /cancelled/i);
      await expectBalance('Buyer', before);
      return 'refunded 128';
    });

    await check(step, 'expired orders are refunded', async () => {
      const before = await balance('Buyer');
      buyer.chat('/order create dirt 10 5 3s');
      await waitChat(buyer, /Order created/);
      await expectBalance('Buyer', before - 50);
      await waitChat(buyer, /expired/i, 25_000);
      await expectBalance('Buyer', before);
      return 'refunded 50';
    });

    await check(step, 'server order takes items', async () => {
      server.mark();
      server.command('orderadmin server wheat unlimited 3 1d');
      await server.waitFor(/Server order \w+ created/, 15_000, 'server order');
      await server.lab('give Seller WHEAT 30');
      await sleep(400);
      const before = await balance('Seller');
      seller.chat('/orders sell all');
      await waitChat(seller, /Delivered|Sold/);
      await expectBalance('Seller', before + 81);
      const left = await count('Seller', 'WHEAT');
      if (left !== 0) throw new Error(`seller kept ${left} wheat`);
      return 'paid 81 for 30 wheat';
    });

    await check(step, 'editing an order charges the difference', async () => {
      const before = await balance('Buyer');
      buyer.chat('/order create cobblestone 10 2 1d');
      await waitChat(buyer, /Order created/);
      await expectBalance('Buyer', before - 20);
      const mine = await waitWindow(buyer, () => buyer.chat('/order'));
      const slot = slotOf(mine, 'cobblestone');
      if (slot < 0) throw new Error('cobblestone order not in /order');
      await waitWindow(buyer, () => buyer.clickWindow(slot, 1, 0));
      await buyer.clickWindow(25, 0, 0);
      await sleep(400);
      await buyer.clickWindow(49, 0, 0);
      await waitChat(buyer, /Order updated/);
      await expectBalance('Buyer', before - 148);
      if (buyer.currentWindow) buyer.closeWindow(buyer.currentWindow);
      return 'charged 128 for 64 more';
    });

    await check(step, 'placeholders answer', async () => {
      server.mark();
      server.command('papi parse Buyer %donutorders_active%/%donutorders_limit%/%donutorders_best_DIAMOND%');
      const match = await server.waitFor(/(\d+)\/(\d+|∞)\/(\S+)/, 10_000, 'papi parse');
      return match[0];
    });

    await check(step, 'staff info answers', async () => {
      server.mark();
      server.command('orderadmin info');
      await server.waitFor(/Storage:/, 10_000, 'orderadmin info');
      return 'ok';
    });
  } finally {
    seller.quit();
    buyer.quit();
    await sleep(1000);
  }
}
