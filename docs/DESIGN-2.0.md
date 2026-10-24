# DonutOrders 2.0: The Supply Update

Design notes for the 2.0 rewrite. 1.9.2 is the baseline; DonutAuctionHouse 2.0 is the sibling plugin whose
foundations (storage, scheduling, messages, smoke matrix) this release shares.

## Goals

1. **Money and items can never be duplicated or lost.** The database is the source of truth and every state change
   is a conditional update that only one server, thread or click can win.
2. **Premium feature parity and beyond**, from a survey of free and paid buy-order plugins (see "Market" below).
3. **One jar for Paper, Folia and Purpur, Minecraft 1.20.1 to 26.3**, proven by a real-server smoke matrix.
4. **Painless upgrade from 1.x**: orders, delivered items, settings and the item whitelist carry over.

## Market

What serious order plugins have (and what reviews keep asking for), and how 2.0 answers:

| Expectation | 1.9.2 | 2.0 |
| --- | --- | --- |
| Board sorts (money per item, recently listed, most delivered, most paid) | newest only | all four, plus ending soon |
| Categories, search by item, enchantment or player | no | yes, `/orders <search>` too |
| k/m/b input and compact display | no | `2.5k`, `1m`, `3b` everywhere |
| Shulker delivery that keeps the rest | partial | unpacks, returns the box and the rest |
| Expiry choice | fixed days | duration picker, permission caps |
| Slot limits by rank | yes | yes, plus per-order caps |
| Price/amount/total caps, cooldown, blacklist, no self-delivery | partial | yes, per material too |
| Fees | seller tax | creation fee and seller tax with permission tiers and an account to pay into |
| Delivery alerts, join summary, mailbox for overflow | partial | yes, batched, cross-server, toggleable |
| Order editing (raise price, add amount, extend) | no | yes, escrow topped up or refunded |
| Price insight and overpay warning | no | average, range, best open order, warning above a threshold |
| Sell routing (`/sell`-style into the best orders) | no | `/orders sell` and an API for sell plugins |
| Server orders (infinite demand for events and sinks) | no | yes |
| Admin tools, audit log, freeze switch, Discord | partial | yes |
| Cross-server | 5 s polling, payouts before the update | conditional SQL, optional Redis for instant refresh |
| Developer API | no | services API with cancellable events |

Bugs seen in other plugins that the design rules out: refunds repeated because a status was not persisted,
`NaN`/`Infinity` prices, close events handled twice, concurrent deliveries overfilling an order, cancelled orders
returning after a restart, lost item data on collection, shulker and bundle tricks in delivery menus.

## Platform

- Paper API 1.20.1 at compile time, Java 17 bytecode, `api-version: 1.20`, `folia-supported: true`.
- Paper, Folia, Purpur and other Paper forks. Plain Spigot lacks Adventure and the region schedulers; the plugin
  explains that and disables itself (1.x claimed Spigot support; Paper is a drop-in replacement).
- API that changed shape across versions (enchantments, potion types, glint, inventory actions) is reached
  through `Keyed`, reflection or names, never through a call that can link differently on a newer server.
- Inventories are only touched on the owning player's thread; money moves on the player's thread or the global
  region thread; SQL runs on a private executor.

## Model

```
donut_orders            one row per order
  order_id, owner_uuid, owner_name, server_order,
  item_data (template, Paper binary format), item_key, material, match_mode,
  amount, delivered, collected, price, escrow,
  created_at, expires_at, status, closed_at, updated_at, version
donut_order_items       delivered stacks waiting for the owner (unbounded, no 54-slot stash)
donut_order_deliveries  audit trail; feeds price insight, statistics and history
donut_order_mail        items returned to a player that did not fit (delivery leftovers, admin actions)
donut_order_notices     notifications for players who were offline or on another server
donut_order_payouts     money owed when an economy call failed; retried
donut_orders_meta       schema version, 1.x import marker
```

Status: `ACTIVE` → `FILLED` | `CANCELLED` | `EXPIRED`. An order leaves "your orders" for history once it is
closed and everything delivered has been collected.

`escrow` is the money still held for undelivered items. It is moved by the same statement that changes the
status or the delivered count, and a refund always pays the stored escrow, never a recomputed one.

### Matching

| Mode | Created from | Accepts |
| --- | --- | --- |
| `TYPE` | the item picker or a plain held item | the material; renamed, damaged, enchanted and custom (model data, plugin data, lore) items per `matching.*` |
| `BOOK` | the enchantment picker | enchanted books with that enchantment; higher levels and extra enchantments per `matching.books.*` |
| `EXACT` | "order the item in your hand" (potions, goat horns, custom items) | items similar to the template (amount aside) |

Filled shulker boxes are opened: matching contents are delivered, the box and everything else go back. Shulker
and bundle orders only accept empty containers.

## Flows

**Create.** Rules, limits and price window checked; `amount × price + fee` withdrawn on the player's thread; row
inserted; on failure the full amount is refunded. Broadcast (clickable), webhook, event, log.

**Deliver.** Items sit in the delivery menu (or are taken from the inventory by quick-deliver and `/orders sell`)
and are tracked by a one-shot ticket. One transaction re-reads the order, caps the count to what is still wanted,
updates `delivered`/`escrow`/`status` with `WHERE delivered = ? AND status = 'ACTIVE'`, stores the stacks and the
audit row. Only after the commit is the seller paid (minus tax) and the overflow handed back; anything that does
not fit goes to the mailbox. If the seller logs out mid-flight, payment and returns are completed offline. On
shutdown, finished tickets are settled and unfinished ones returned.

**Collect.** Rows are deleted one by one (each delete must hit) before the stacks are handed over, whole stacks
only. Collect one order, collect everything, or empty the mailbox.

**Cancel / expire.** `UPDATE ... SET status, escrow = 0 WHERE status = 'ACTIVE' AND escrow = ?` then pay the read
escrow. Delivered items stay collectable. Expiry runs every 30 seconds on every server; only one wins.

**Edit.** Add amount, change price, extend. The difference is charged first (or refunded after) and the update is
conditional on the delivered count and price the player saw.

## Commands

| Command | Purpose |
| --- | --- |
| `/orders [search]` | the board, optionally filtered |
| `/orders sell [hand\|all]` | sell into the best open orders after a confirmation |
| `/orders price` | price insight for the held item |
| `/orders view <player>` | a player's open orders |
| `/order` | your orders, new order, collect, history |
| `/order create <item\|hand> <amount> <price> [duration]` | create without menus |
| `/order collect`, `/order history`, `/order notify` | collect all, history, toggle alerts |
| `/orderadmin reload\|info\|view\|cancel\|purge\|server\|freeze` | staff tools |

## Upgrade from 1.x

- `orders` and `order_stash` rows are imported once into the new tables (a meta flag records it); open orders
  keep their escrow, stash contents become collectable items. The old tables are left untouched.
- `config.yml`, `messages.yml` and `items.yml` from 1.x are moved to `legacy-1.x/`, and their settings
  (limits, rank limits, expiry, tax, broadcast, database, allowed items as a whitelist) are written into the new
  config.
- Permissions `donutorders.use`, `donutorders.admin` and `donutorders.limit.<n>` keep working.
- PlaceholderAPI `%donutorders_order_limit%` keeps working.
