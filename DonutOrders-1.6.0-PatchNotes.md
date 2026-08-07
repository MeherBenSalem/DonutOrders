# DonutOrders v1.6.0

### New Features
* **MySQL storage backend** — set `database.type: mysql` in `config.yml` so multiple servers share one database
* **Cross-server order sync** — orders created or fulfilled on one server appear on all servers sharing the MySQL database (polls every `database.sync-interval-seconds`, default 5)

### Improvements
* `updated_at` column on orders for change tracking and safe multi-server merges
* Optimistic DB updates on fulfill/collect prevent duplicate payouts when two servers race
* Critical paths refresh the order from the database before mutating

### Bug Fixes
* None

### Configuration
```yaml
database:
  type: mysql          # sqlite (default) or mysql
  sync-interval-seconds: 5
  mysql:
    host: localhost
    port: 3306
    database: donutorders
    username: donutorders
    password: ""
    pool-size: 10
```

### Compatibility
* SQLite remains the default — no config change required for single-server setups
* Existing SQLite databases auto-migrate (`updated_at` column added on startup)
* Drop-in jar replacement; restart all network servers after switching to MySQL

### Upgrade Notes
1. For cross-server: create a MySQL database, grant access, set `database.type: mysql` on every server
2. Replace the jar with `DonutOrders-1.6.0.jar` on all servers
3. Restart servers — console should show `(MySQL backend)` when configured correctly
4. Verify an order placed on server A appears in `/orders` on server B within a few seconds
