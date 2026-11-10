# DonutOrders 2.0.0: The Supply Update

**Supported platforms:** Paper, Folia and Purpur, Minecraft 1.20.1 through 26.3 (one jar). Plain Spigot is no longer
supported: the plugin needs Paper's API and says so on startup. Paper is a drop-in replacement.

A full rebuild of DonutOrders. Every order is now a conditional database update that only one click, thread or server
can win, and money moves only after the database has decided. Tested end to end on 14 Paper and Folia servers from
1.20.1 to 26.3, and on two servers sharing one MySQL database.

## The board
- Sorts: money per item, recently listed, most delivered, most paid, ending soon.
- Nine categories, search by item, enchantment or player (`/orders diamond`), and a "what I can deliver" filter.
- `/orders view <player>`. Each order shows the buyer, the price each, a progress bar, what is still wanted, what was
  paid out and the time left.

## Delivering and selling
- The delivery menu takes only items the order accepts, shows the payout (and tax) as you fill it, and has an "add
  from inventory" button.
- Shift-click an order on the board to deliver straight from your inventory.
- Shulker boxes are opened: the order takes what it wants, and the box comes back with the rest.
- **`/orders sell` and `/orders sell all`** sell into the best-paying orders first, after a confirmation that shows
  the payout.
- Anything an order did not take comes back. What does not fit goes to your mailbox; nothing is ever dropped.

## Creating orders
- An item picker with every item of your server, by category or search.
- "Order the item in your hand" for potions, goat horns, custom items and anything with variants.
- Enchanted books: an enchantment and level picker from the server's registry.
- The setup menu:
  - amount buttons, and typed amounts and prices (`2.5k`, `1m` and `3b` work);
  - a duration picker, and the full cost with fee and balance after;
  - "outbid the best order" and "use the average";
  - price insight (average, range, last price, volume and open demand) and a warning when the price is far above
    the market.
- `/order create <item|hand> <amount> <price> [time]` creates without menus.

## Your orders
- Collect delivered items any time; only whole stacks that fit are handed over. Collect all in one click.
- **Change an order**: add items, raise or lower the price, add time. The money difference is charged or refunded.
- Cancel for an instant refund of the money still held. Delivered items stay collectable.
- A mailbox, history of finished orders and of your deliveries, and your lifetime totals.

## Notifications
- Delivery alerts to the buyer, gathered per order (one line for a burst of deliveries), in chat, the action bar or
  both.
- A title and sound when an order fills.
- Book orders name their enchantment everywhere: "Enchanted Book (Mending)".
- A "while you were away" summary on join: deliveries, filled orders, refunds.
- Works across servers: a buyer on another server of the network is told there.
- `/order alerts` mutes delivery alerts and new-order announcements.
- Clickable new-order announcements, with a minimum value and a cooldown.

## Economy and rules
- Creation fee (flat, percent, cap, refund on cancel, exempt permission) and seller tax with permission tiers. Both
  can pay into an account.
- Price limits globally and per material or book, a cap on amount and on order value, and a creation cooldown.
- Open-order limits by permission (`donutorders.limit.<n>`) or LuckPerms group.
- Duration options with permission caps.
- Blacklist with wildcards, names, lore, model data and plugin data; an optional whitelist; disabled worlds and game
  modes.
- Matching rules for renamed, worn, enchanted and custom items, book levels, and custom-item ids (Nexo, Oraxen).
- Same-address deliveries: off, alert staff and Discord, or block.

## Staff and integrations
- Shift-right-click to cancel any order from the board (refunded, with an optional reason the buyer sees).
- `/orderadmin view`, `history`, `cancel`, `purge`, `info`, `freeze` and `unfreeze`.
- **Server orders**: unlimited or fixed demand paid by the server.
- Discord webhooks (created, delivered, filled, cancelled, expired, suspicious).
- PlaceholderAPI (17 placeholders, including best and average prices per material).
- A developer API with cancellable events and sell routing for `/sell` plugins.
- A daily transaction log and bStats.
- MySQL or MariaDB for several servers, with optional Redis for instant updates. Every message is in `messages.yml`
  (MiniMessage, `&` codes, hex, optional small caps).

## Fixed from 1.x
- Sellers were paid before the order was updated, so a race could pay twice. Payment now follows the database update.
- Stash items beyond 54 slots were discarded. Delivered items are now stored without limit.
- Expired orders refunded their money only when the buyer collected the stash. Expiry now refunds at once, and so do
  1.x orders it imports.
- Several delivery-menu and close-event paths could return items twice (issue #5 was fixed in 1.9.1). The new menu
  hands items over exactly once, through logouts, deaths and shutdowns.

## Upgrading from 1.x
1. Back up `plugins/DonutOrders/` (and your MySQL database).
2. Replace the jar and restart.
3. The plugin carries your data and settings over:
   - Orders and stash items are imported once. The 1.x tables are left untouched.
   - Open orders keep their held money. Expired orders whose money was never refunded are refunded once.
   - The 1.x `config.yml`, `messages.yml` and `items.yml` move to `legacy-1.x/`. Limits, rank limits, expiry, tax,
     broadcast, database and the item list (as `whitelist.materials`) are written into the new `config.yml`.
   - Messages start from the 2.0 defaults.
4. The permissions `donutorders.use`, `donutorders.admin` and `donutorders.limit.<n>` and the placeholder
   `%donutorders_order_limit%` keep working.
