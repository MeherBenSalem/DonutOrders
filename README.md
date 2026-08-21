# DonutOrders

Player-driven buy-order marketplace for Bukkit, Spigot, Paper, Purpur, and Folia.
Players post buy orders; others fulfill them for payment via Vault.

## Features

- Public buy orders with delivery and collection GUIs
- Enchanted-book orders (enchant + level picker)
- Rank / permission order limits (`donutorders.limit.*`)
- SQLite (default) or MySQL with optional cross-server sync
- Folia-safe scheduling
- Optional PlaceholderAPI expansion
- Configurable chat broadcast when a new order is created
- Modrinth update check on startup (ops notified on join)
- Anonymous bStats metrics (chart ID 33559)

## Requirements

- Java 17+
- Minecraft **1.20.1 through 26.2** (including 1.21.x and 26.1.x)
- Software: **Bukkit, Spigot, Paper, Purpur, or Folia**
- Vault + a Vault-compatible economy plugin

## Installation

1. Download the jar from [Modrinth](https://modrinth.com/plugin/donut-orders) or
   [CurseForge](https://www.curseforge.com/minecraft/bukkit-plugins/donutorders).
2. Place it in your `plugins` folder.
3. Restart the server and configure `plugins/DonutOrders/` as needed.

## Usage

- `/orders` — open the marketplace GUI
- `/orders admin reload` — reload config and messages (admin)

## Building

```bash
mvn -B package
```

The shaded jar is written to `target/DonutOrders-<version>.jar`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
Security reports: [.github/SECURITY.md](.github/SECURITY.md).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
