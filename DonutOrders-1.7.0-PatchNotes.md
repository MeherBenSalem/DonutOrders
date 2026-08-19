# DonutOrders v1.7.0

### New Features
* **New-order chat broadcast** — when a player creates a buy order, all other online players see a chat announce (item, amount, price). The creator still gets the private `order-created` confirmation only.

### Configuration
* `orders.broadcast-on-create` (default `true`) — set to `false` to disable the public announce.
* `order-created-broadcast` in `messages.yml` — `{0}` buyer, `{1}` item, `{2}` amount, `{3}` price per item. Missing keys fall back to jar defaults on `/orders admin reload`.

### Compatibility
* Same loaders as 1.6.1 (Paper / Folia / Purpur family). Folia-safe: each recipient is messaged on their entity thread.

### Upgrade Notes
1. Replace the DonutOrders jar with **1.7.0**. Keep your existing `plugins/DonutOrders/` folder.
2. Optional: add `broadcast-on-create: true` under `orders:` in `config.yml`, and `order-created-broadcast` to `messages.yml`, then `/orders admin reload`. If you skip this, in-code / jar defaults still enable the announce.
3. Confirm by creating an order on one account and checking chat on another.
