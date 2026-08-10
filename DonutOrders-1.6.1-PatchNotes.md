# DonutOrders v1.6.1

### Bug Fixes
* **Empty enchant picker** — New Order → Enchanted Book no longer opens an empty Select Enchant GUI. Modern Paper returns `false` from `Enchantment#canEnchantItem` for enchanted books; the picker now builds the list via stored-enchant checks so vanilla (and storeable) enchants appear again.

### Improvements
* Enchant list cache clears on `/orders admin reload`.

### Compatibility
* Same loaders as 1.6.0 (Paper / Folia / Purpur family). Drop-in jar replace.

### Upgrade Notes
1. Replace the DonutOrders jar with **1.6.1**.
2. Restart or use your preferred hot-swap flow, then open New Order → Enchanted Book to confirm the enchant list populates.
