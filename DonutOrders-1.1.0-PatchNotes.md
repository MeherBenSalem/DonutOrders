# DonutOrders v1.1.0

### New Features

* Added permission-based order limits via `donutorders.limit.<n>` nodes (e.g. `donutorders.limit.5`, `donutorders.limit.10`).
* Added support for rank-specific maximum active orders through optional `rank-limits` configuration.
* Added PlaceholderAPI support: `%donutorders_order_limit%` returns the player's currently applied limit.
* Added `/orders limit <player>` admin command (alias: `/donutorders limit <player>`).

### Improvements

* Added a caching system for permission-based limit lookups to avoid scanning permissions on every order creation.
* Limit cache refreshes automatically on player join, config reload, and LuckPerms permission recalculation.
* Order limit reached messages are now configurable via `messages.yml`.

### Configuration

* Added `orders.default-limit` as an optional config key (takes priority over legacy `orders.max-per-player` when set).
* Added optional `rank-limits` configuration section for group-based fallback limits.
* Added `messages.order-limit-reached` with `%limit%` and `%active_orders%` placeholders.

### Permissions

* `donutorders.admin.limit` — allows use of `/orders limit <player>`.
* `donutorders.limit.<n>` — grants a custom maximum active order count.

### Compatibility

* Fully backwards compatible with previous configurations.
* Existing `orders.max-per-player` values continue to work unchanged.
* No migration required.

### Notes

* Players with multiple `donutorders.limit.*` permissions automatically receive the highest available limit.
* When no limit permission is assigned, the configured default (`orders.default-limit` or `orders.max-per-player`) applies.
* LuckPerms and other permission plugins are fully supported; LuckPerms permission changes refresh the limit cache automatically.
