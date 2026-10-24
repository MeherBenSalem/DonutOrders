# DonutOrders

Free, open-source buy orders for **Paper**, **Folia** and **Purpur** servers on Minecraft **1.20.1 through 26.3**.

Players post what they want and the price they pay for each item. Anyone can deliver into an order and gets paid
instantly. The money is held when the order is made, so every delivery is paid. DonutOrders is the board players know
from the big SMP servers, rebuilt so items and money can never be lost or duplicated, with Vault or VaultUnlocked as
the economy.

## Features

- **The board** (`/orders`): 45 orders a page with the four familiar sorts (money per item, recently listed, most
  delivered, most paid) plus ending soon. It also has nine categories, search by item, enchantment or player
  (`/orders <search>`), a "what I can deliver" filter and `/orders view <player>`.
- **Delivering**: click an order and drop items in, or add everything matching from your inventory with one button.
  Shift-click delivers straight from your inventory. Shulker boxes are opened for you: the order takes what it wants,
  and the box comes back with the rest. Wrong items are refused at the slot, and anything extra comes back.
- **Selling into orders** (`/orders sell`, or `/orders sell all`): sells your held item, or everything any order wants,
  to the best-paying orders first. A confirmation shows the payout before anything moves.
- **New orders**: an item picker with every item your server has, by category or search, or order the exact item in
  your hand (potions, goat horns, custom items).
  - Enchanted books get an enchantment and level picker built from the server's registry, so new and data-pack
    enchantments appear on their own.
  - The setup menu covers amount, price per item (`2.5k` and `1m` work), duration, the full cost and fee, and "outbid
    the best order".
  - It also shows **price insight** (the average, range and last price of recent deliveries) and warns when a price is
    far above the market.
- **Your orders** (`/order`): collect delivered items (only whole stacks that fit; the rest waits) and collect all in
  one click. You can also change an order (add items, raise or lower the price, add time; the money difference is
  charged or refunded) or cancel it with an instant refund. A mailbox holds anything that did not fit, and history
  shows your finished orders and your deliveries.
- **Notifications**: a delivery alert to the buyer, gathered so a busy order sends one line, not fifty. A title when
  an order fills, and a "while you were away" summary on join. Players on another server of the network are told
  there. Alerts can be muted with `/order alerts`.
- **Economy and rules**:
  - An optional creation fee and seller tax, each with permission tiers and an account to pay into.
  - Price, amount and total caps, also per material or per book.
  - Open-order limits by permission or LuckPerms group, durations with permission caps, and a creation cooldown.
  - A blacklist (wildcards, names, lore, model data, plugin data), an optional whitelist, disabled worlds and game
    modes, and a staff freeze switch.
- **Matching that cannot be gamed**: plain orders refuse renamed, worn and custom items by default (each configurable),
  so a custom "Super Diamond" is never sold as a diamond. Full shulker boxes and bundles are never sold as empty ones.
  Exact orders match by a custom-item plugin's id (Nexo, Oraxen) when one is set.
- **Server orders** (`/orderadmin server wheat unlimited 3 7d`): demand paid by the server, for events and item sinks.
- **Staff tools**:
  - Shift-right-click any order on the board to cancel it (the buyer is refunded).
  - `/orderadmin view|history|cancel <id> [reason]|purge|info|freeze`.
  - Alerts for deliveries between players on one connection.
- **Integrations**: Discord webhooks (created, delivered, filled, cancelled, expired, suspicious), PlaceholderAPI, a
  developer API with cancellable events and sell routing for `/sell` plugins, bStats, and a daily transaction log.
- **Text**: every word is in `messages.yml`, written in MiniMessage, `&` codes or hex colors. Optional small caps in
  menus. Item and enchantment names appear in each player's own language, and player-made text can never inject
  formatting.
- **Safe by design**:
  - Every delivery, cancel, expiry, edit and collection is a conditional database update that exactly one click,
    thread or server can win.
  - Money moves only after the database has decided.
  - Items in transit are tracked until they are delivered or handed back, through logouts, failures and shutdowns.
  - Items are stored in Paper's versioned format, so orders survive Minecraft upgrades. Folia-safe throughout.

## Requirements

- Paper, Folia, Purpur or another Paper fork, **1.20.1–26.3**. Plain Spigot/Bukkit lacks Paper's API; the plugin says
  so and disables itself.
- Java **17+** for 1.20.1–1.20.4, **21+** for 1.20.5–1.21.11 and **25+** for 26.x (Minecraft's own requirements).
- Vault or VaultUnlocked, plus an economy plugin that registers Vault's Economy service.
- Optional: PlaceholderAPI, LuckPerms, and MySQL/MariaDB (plus Redis) for several servers.

## Installation

1. Put `DonutOrders-<version>.jar` in `plugins/`.
2. Install Vault or VaultUnlocked and your economy plugin.
3. Restart the server.
4. Edit `plugins/DonutOrders/config.yml` and `messages.yml`, then `/orderadmin reload`.

**Upgrading from 1.x**: replace the jar and restart.
- Open orders keep their held money, and items waiting in 1.x stashes become collectable.
- Expired orders whose money 1.x never refunded are refunded once.
- Your settings (limits, rank limits, expiry, tax, broadcast, database, the item list) are carried into the new
  `config.yml`, and the old files are kept in `legacy-1.x/`.
- The 1.x tables are left untouched. Back up the database first. See the
  [2.0.0 notes](DonutOrders-2.0.0-PatchNotes.md).

## Commands

| Command | Description |
|---|---|
| `/orders` (`/market`) | The order board |
| `/orders <search>` | Orders for an item, enchantment or player |
| `/orders sell [all]` | Sell the held item (or everything orders want) to the best orders |
| `/orders price` | What the held item sells for: best open order and recent prices |
| `/orders view <player>` | A player's open orders |
| `/order` | Your orders: collect, change, cancel, new order, mailbox, history |
| `/order new` | The item picker for a new order |
| `/order create <item\|hand> <amount> <price> [time]` | Create without menus (`enchanted_book:sharpness:5` for books) |
| `/order collect` / `mailbox` / `history` / `alerts` / `cancel <id>` | Shortcuts |
| `/orderadmin view <player>` / `history <player>` | A player's orders and history (staff) |
| `/orderadmin cancel <id> [reason]` | Cancel any order; the buyer is refunded and told the reason |
| `/orderadmin server <item\|hand> <amount\|unlimited> <price> [time]` | A server-paid order |
| `/orderadmin freeze` / `unfreeze` / `purge <days>` / `info` / `reload` | Staff tools |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `donutorders.use` | everyone | The board and your orders (includes create, deliver and sell) |
| `donutorders.create` / `.deliver` / `.sell` | everyone | Create orders, deliver, use `/orders sell` |
| `donutorders.create.exact` | everyone | Order the exact item in your hand |
| `donutorders.limit.<n>` / `.unlimited` | — | Open-order limit (also `limits.permissions` and LuckPerms groups) |
| `donutorders.duration.<tier>` | — | Longer durations (`durations.permissions`) |
| `donutorders.tax.<tier>` / `donutorders.fee.exempt` | — | Lower seller tax, no creation fee |
| `donutorders.bypass.restrictions` / `.cooldown` / `.blacklist` / `.amount` | op | Skip a rule |
| `donutorders.admin` | op | `/orderadmin`, cancelling orders from the board |
| `donutorders.admin.alerts` / `donutorders.update.notify` | op | Suspicious-delivery and update notices |

## PlaceholderAPI

| Placeholders | Value |
|---|---|
| `%donutorders_active%`, `%donutorders_limit%`, `%donutorders_slots_left%` | Open orders, limit and slots left |
| `%donutorders_collect%` | Items waiting to be collected |
| `%donutorders_delivered%`, `%donutorders_earned%` | Items you delivered and what you earned |
| `%donutorders_received%`, `%donutorders_spent%` | Items you received and what you paid |
| `%donutorders_market_active%`, `%donutorders_market_value%` | Open orders and money held on the whole board |
| `%donutorders_best_DIAMOND%`, `%donutorders_average_DIAMOND%` | Best open price and average delivered price of a material |
| `%donutorders_demand_DIAMOND%`, `%donutorders_orders_DIAMOND%` | Items wanted and open orders for a material |

The 1.x `%donutorders_order_limit%` still works.

## Developer API

```java
DonutOrdersApi.get().ifPresent(api -> {
    api.bestPrice(item, player.getUniqueId());                // what open orders pay for this item
    api.sellToOrders(player, items).thenAccept(result -> {    // sell routing for /sell plugins
        // result.sold(), result.earned(), result.leftovers() (sell those elsewhere)
    });
    api.createServerOrder(new ItemStack(Material.WHEAT), Order.UNLIMITED, 3.0, Duration.ofDays(7));
});
```

Events are in `io.nightbeam.donutorders.api.event`:
- Cancellable: `OrderPreCreateEvent` and `OrderPreDeliverEvent`.
- Asynchronous: `OrderCreatedEvent`, `OrderDeliveredEvent` and `OrderClosedEvent` (filled, cancelled, cancelled by
  staff, expired).

Add `softdepend: [DonutOrders]` to your plugin.yml.

## Several servers

Set `storage.type: MYSQL` on every server and point them at the same database (MySQL 8 or MariaDB).
- Each delivery, cancel and expiry is a conditional row update. Two servers can never overfill an order or refund it
  twice; the cross-server test races two sellers on two servers into one order.
- Servers see each other's changes through polling (`sync.poll-seconds`) or, at once, through Redis (`sync.redis`).
- Buyers are told about deliveries on whichever server they are on.

## Building and testing

```bash
mvn package            # target/DonutOrders-<version>.jar (Java 17), runs the unit tests
```

`mvn test` runs the unit tests and the order book on native SQLite. Add
`-Dorders.test.mysql.url=jdbc:mysql://localhost:3306/<db>` to run the order book on MySQL or MariaDB.

[`tools/smoke`](tools/smoke/README.md) has the end-to-end checks:
- The cross-version matrix on real Paper and Folia servers, with network clients, from 1.20.1 to 26.3.
- The two-server network test.

`python3 tools/messages/extract.py` regenerates `messages.yml` from the texts in the code.

Releases are tagged for every loader and Minecraft version in
[`release/supported-minecraft.json`](release/supported-minecraft.json). Listing copy and artwork are in
[`marketing/`](marketing/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](.github/SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
