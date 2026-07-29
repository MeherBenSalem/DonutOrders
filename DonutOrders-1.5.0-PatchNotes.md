# DonutOrders v1.5.0

### New Features
* **Enchanted book orders** — New Order → Enchanted Book opens an enchant picker, then a level picker (Sharpness, Efficiency, Unbreaking, and other vanilla book enchants). Listings show names like `Sharpness V Enchanted Book`; sellers must deliver the exact enchanted book
* **VIP order limits** — per-rank and per-permission active order caps via `OrderLimitManager` (wired into order creation, join/quit cache, LuckPerms updates, and PlaceholderAPI)
* **Item search in New Order picker** — compass button opens a chat search; filter persists across pagination; clear-search button when a filter is active

### Improvements
* `/orders admin reload` refreshes cached order limits for all online players
* New Order GUI title shows the active search filter when filtering
* Order list / delivery / wrong-item messages use enchant-aware item names

### Configuration
* `ENCHANTED_BOOK` added to default `items.yml` allowed list
* `orders.default-limit` — default active order cap (default: `2`)
* `orders.max-per-player` — legacy alias kept in sync with `default-limit`
* `rank-limits` — per-LuckPerms-group caps (e.g. `default: 2`, `vip: 5`)
* Optional permission override: `donutorders.limit.<n>` (highest wins)
* New `messages.yml` keys for enchant picker and New Order search UI

### Compatibility
* Drop-in update from 1.3.x / 1.4.x
* Folia-supported unchanged
* No database migration required
* Soft-depends on **LuckPerms** and **PlaceholderAPI** (optional; features degrade gracefully when absent)
* Placeholder: `%donutorders_order_limit%`

### Upgrade Notes
1. Replace the jar with `DonutOrders-1.5.0.jar`
2. Restart the server (or reload after reviewing config)
3. If upgrading an existing install, add `ENCHANTED_BOOK` under `allowed-items` in `items.yml`, then `/orders admin reload`
4. Review `config.yml` — add or adjust `rank-limits` if you use VIP ranks:
   ```yaml
   rank-limits:
     default: 2
     vip: 5
   ```
   Or grant LuckPerms permission `donutorders.limit.5` instead of/in addition to rank limits.
5. Ensure VIP players have LuckPerms group `vip` (maps to `group.vip`) or the permission node above.
6. Test `/orders` → New Order → Enchanted Book + Search (compass), and confirm VIP players can hold more active orders than default.
