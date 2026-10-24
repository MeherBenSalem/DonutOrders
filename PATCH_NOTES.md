# Patch notes

## 2.0.0: The Supply Update

A full rebuild. See [DonutOrders-2.0.0-PatchNotes.md](DonutOrders-2.0.0-PatchNotes.md) for everything.

- **Paper, Folia and Purpur, 1.20.1 to 26.3**, tested on 14 real servers and on two servers sharing one MySQL database.
  Plain Spigot is no longer supported.
- **Safe by design**: every delivery, cancel, expiry and edit is a conditional database update, and money moves only
  after it. Items in transit are tracked until they are delivered or handed back.
- **Board**: four SMP sorts plus ending soon, categories, search, a "what I can deliver" filter, quick deliver, and
  shulker unpacking.
- **`/orders sell`**: sell everything into the best orders.
- **New orders**: an item picker, "order the item in your hand", a book picker, k/m/b prices, durations, price insight
  and an overpay warning.
- **Your orders**: collect, change (amount, price, time), cancel, mailbox and history.
- **Notifications**: batched alerts, a title when an order fills, a join summary, cross-server delivery.
- **Rules and economy**: fees, tax tiers, price, amount and total caps, limits, durations, a blacklist and whitelist,
  matching rules, same-address alerts, server orders, and staff tools.
- **Integrations**: Discord, PlaceholderAPI, an API with sell routing, and Redis.
- **Upgrading**: 1.x orders, stash items and settings are carried over once.

## 1.9.2

- Added Minecraft **26.3** support (Paper / Folia / Spigot / Purpur); existing 1.20.6, 1.21.x, 26.1.x, and 26.2 support is unchanged
- Compiled against Paper API `26.3.build.49-alpha` (latest published 26.3 API jar); `plugin.yml` `api-version` stays `1.20` so older servers still load
- Tested against Paper 26.3 build #133 (ALPHA), Paper 26.2 build #129, and Paper 1.20.6 build #151

## 1.9.1

- Fixed delivery item duplication when cancelling after proceeding from the deliver-items box (GitHub #5): cancel and GUI close no longer both return the same snapshot
- Delivery items are returned exactly once on cancel, ESC-close, disconnect, death, and plugin disable; double-click confirm/cancel cannot duplicate or lose the box contents

## 1.9.0

- Split commands: `/order` (your active orders), `/order history`, `/orders` (public market), `/orderadmin` (reload, history, simulate)
- Order history GUI for completed, expired, cancelled, and claimed orders
- `/orderadmin reload` works from console; admin history view for any player

## 1.8.1

- New-order chat input (amount, price, cancel keyword) now reads Paper's Adventure chat event so integers are accepted on 1.20.1–26.2
- Amount/price parsing ignores color codes and commas; whole values like `64.0` work
- Folia no longer treats a retired entity callback as cancel while you are typing a number

## 1.8.0

- Added bStats metrics (plugin ID **33559**)
- Hardened Modrinth update check for [donut-orders](https://modrinth.com/plugin/donut-orders): pick newest release by date, strip version suffixes, warn in console, notify `donutorders.admin` on join (`update-check.notify-ops-on-join`)
- Confirmed support matrix: Bukkit / Spigot / Paper / Purpur / Folia on Minecraft **1.20.1–26.2**
- Relicensed to Apache-2.0; added OSS contributor docs; removed committed build/AI clutter

## 1.7.0

- New-order chat broadcast to other online players (`orders.broadcast-on-create`, `order-created-broadcast`)
- Folia-safe: recipients messaged on their entity thread

## 1.6.1

- Fixed empty enchanted-book Select Enchant picker on modern Paper
- Enchant list cache clears on `/orders admin reload`

## 1.6.0

- MySQL storage backend and cross-server order sync
- `updated_at` tracking and optimistic updates to reduce duplicate payouts

## 1.5.1

- Folia enable crash fix (`runAsync` / scheduler hardening)

## 1.5.0

- Enchanted book orders, VIP order limits, New Order item search

## 1.3.1

- Fixed New Order picker Previous page always returning to page 1
