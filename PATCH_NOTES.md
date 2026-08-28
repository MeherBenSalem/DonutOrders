# Patch notes

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
