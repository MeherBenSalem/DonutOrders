# DonutOrders v1.2.0

### New Features

* Added Shulker Box delivery support — matching items inside shulkers count toward order fulfillment.
* Shulker contents are scanned from shulkers placed in the delivery GUI and from shulkers anywhere in the seller's inventory.
* Added `items.yml` for configuring allowed order materials separately from `config.yml`.
* Added Modrinth update check on plugin startup (`update-check.enabled` in `config.yml`).
* Added `donutorders` as a command alias for `/orders`.

### Improvements

* Reduced plugin JAR size by bundling only required SQLite native libraries (~4 MB vs ~13 MB).
* Allowed materials are validated server-side when creating orders (prevents GUI bypass).
* Invalid material names in `items.yml` log warnings instead of crashing the plugin.
* `/orders admin reload` now reloads `config.yml`, `messages.yml`, and `items.yml`.

### Configuration

* New `items.yml` with `allowed-items` list (UPPERCASE Bukkit Material names).
* `allowed-materials` removed from `config.yml` — edit `items.yml` instead.
* Added `update-check.enabled` to toggle the Modrinth version check.

### Compatibility

* Existing servers with `allowed-materials` in `config.yml` are migrated to `items.yml` automatically on first load.
* Existing `items.yml` files are never overwritten on update.
* Loose-item delivery behavior is unchanged when no Shulker Boxes are involved.

### Notes

* Delivery removal priority: loose GUI items first, then shulker contents in the GUI, then shulker contents in the player inventory.
* Shulker boxes themselves are preserved — only matching contents are removed.
* Buyer stash always receives flat item stacks, not shulker boxes.
* Nested shulkers (a shulker inside a shulker) are not scanned.
