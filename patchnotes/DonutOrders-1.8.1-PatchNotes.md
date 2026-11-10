# DonutOrders v1.8.1

### New Features
* None.

### Improvements
* Order creation chat input uses Paper Adventure `AsyncChatEvent` (legacy Bukkit chat still used when that event is absent).
* Amount and price parsing accepts trimmed values, commas, and whole decimals such as `64.0`.
* The `chat-cancel-keyword` from `messages.yml` is matched after stripping color codes.

### Bug Fixes
* Typing an amount or price (or the cancel keyword) while creating an order is no longer ignored on modern Paper/Folia.

### Configuration
* No config schema change. Existing `chat-cancel-keyword` still applies.

### Compatibility
* Paper and Folia, Minecraft 1.20.1 through 26.2, Java 17.

### Upgrade Notes
1. Replace the jar. Keep `plugins/DonutOrders/` config and data.
2. Restart, open `/orders`, create an order, type a whole number for amount then a price.
