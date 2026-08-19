# Graph Report - DonutOrders  (2026-08-12)

## Corpus Check
- 47 files · ~25,118 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 495 nodes · 1608 edges · 20 communities (18 shown, 2 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 196 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `59e21d65`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Order
- GUIType
- DonutOrders.java
- EnchantOrderUtils
- GUIManager
- DonutOrdersExpansion
- DonutOrders
- org.bukkit.inventory.ItemStack
- .buildOrderItem
- DonutOrders v1.5.1
- OrderLimitManager
- DonutOrders v1.5.0
- org.junit.jupiter.api.Test
- DonutOrders v1.3.1
- com.donutorders:DonutOrders
- FoliaScheduler
- tmp-publish-1.7.0.mjs
- DonutOrders v1.6.0
- DonutOrders v1.6.1
- DonutOrders v1.7.0

## God Nodes (most connected - your core abstractions)
1. `Order` - 74 edges
2. `GUIManager` - 65 edges
3. `DonutOrders` - 46 edges
4. `StorageManager` - 39 edges
5. `MessageHelper` - 31 edges
6. `OrderLimitManager` - 30 edges
7. `ItemUtils` - 28 edges
8. `FoliaScheduler` - 21 edges
9. `DeliveryItemUtils` - 21 edges
10. `OrderStatus` - 20 edges

## Surprising Connections (you probably didn't know these)
- `DonutOrders` --references--> `ChatInputHandler`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/ChatInputHandler.java
- `DonutOrders` --references--> `GUIManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/GUIManager.java
- `DonutOrders` --references--> `OrderLimitManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/OrderLimitManager.java
- `DonutOrders` --references--> `OrderManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/OrderManager.java
- `DonutOrders` --references--> `StorageManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/storage/StorageManager.java

## Import Cycles
- None detected.

## Communities (20 total, 2 thin omitted)

### Community 0 - "Order"
Cohesion: 0.08
Nodes (8): HikariDataSource, net.milkbowl.vault.economy.Economy, PreparedStatement, ResultSet, OrderManager, Order, ItemStack, StorageManager

### Community 1 - "GUIType"
Cohesion: 0.20
Nodes (10): GUIType, COLLECT_STASH, CONFIRM_DELIVERY, DELIVER_ITEMS, ENCHANT_LEVEL, ENCHANT_PICKER, NEW_ORDER, ORDER_DETAIL (+2 more)

### Community 2 - "DonutOrders.java"
Cohesion: 0.09
Nodes (24): com.zaxxer.hikari.HikariDataSource, java.text.DecimalFormat, java.util.logging.Logger, net.luckperms.api.event.user.UserDataRecalculateEvent, net.luckperms.api.LuckPerms, org.bukkit.command.Command, org.bukkit.command.CommandExecutor, org.bukkit.command.CommandSender (+16 more)

### Community 3 - "EnchantOrderUtils"
Cohesion: 0.19
Nodes (5): org.bukkit.enchantments.Enchantment, EnchantLevelGUI, EnchantPickerGUI, EnchantOrderUtils, ItemStack

### Community 4 - "GUIManager"
Cohesion: 0.09
Nodes (20): org.bukkit.entity.Player, org.bukkit.event.inventory.ClickType, org.bukkit.inventory.Inventory, BaseGUI, CollectStashGUI, Override, Override, Override (+12 more)

### Community 5 - "DonutOrdersExpansion"
Cohesion: 0.28
Nodes (6): me.clip.placeholderapi.expansion.PlaceholderExpansion, org.bukkit.OfflinePlayer, org.jetbrains.annotations.NotNull, org.jetbrains.annotations.Nullable, DonutOrdersExpansion, Override

### Community 6 - "DonutOrders"
Cohesion: 0.10
Nodes (7): org.bukkit.configuration.file.FileConfiguration, org.bukkit.Material, org.bukkit.plugin.java.JavaPlugin, DonutOrders, Override, AllowedItemsManager, OrderBroadcast

### Community 8 - ".buildOrderItem"
Cohesion: 0.16
Nodes (3): ConfirmDeliveryGUI, Override, ItemStack

### Community 9 - "DonutOrders v1.5.1"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.5.1, Improvements, New Features, Upgrade Notes

### Community 10 - "OrderLimitManager"
Cohesion: 0.07
Nodes (19): InventoryClickEvent, InventoryCloseEvent, InventoryDragEvent, java.util.regex.Pattern, org.bukkit.event.EventHandler, org.bukkit.event.Listener, org.bukkit.event.player.AsyncPlayerChatEvent, org.bukkit.event.player.PlayerJoinEvent (+11 more)

### Community 11 - "DonutOrders v1.5.0"
Cohesion: 0.29
Nodes (6): Compatibility, Configuration, DonutOrders v1.5.0, Improvements, New Features, Upgrade Notes

### Community 12 - "org.junit.jupiter.api.Test"
Cohesion: 0.11
Nodes (5): org.junit.jupiter.api.Test, GuiPaginationTest, NewOrderGUITest, EnchantOrderUtilsTest, OrderBroadcastTest

### Community 13 - "DonutOrders v1.3.1"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.3.1, Improvements, New Features, Upgrade Notes

### Community 15 - "FoliaScheduler"
Cohesion: 0.14
Nodes (5): org.bukkit.entity.Entity, org.bukkit.Location, org.bukkit.plugin.Plugin, FoliaScheduler, ModrinthUpdateChecker

### Community 16 - "tmp-publish-1.7.0.mjs"
Cohesion: 0.22
Nodes (8): body, cfForm, changelog, env, form, gameVersions, loaders, meta

### Community 17 - "DonutOrders v1.6.0"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.6.0, Improvements, New Features, Upgrade Notes

### Community 18 - "DonutOrders v1.6.1"
Cohesion: 0.33
Nodes (5): Bug Fixes, Compatibility, DonutOrders v1.6.1, Improvements, Upgrade Notes

### Community 19 - "DonutOrders v1.7.0"
Cohesion: 0.33
Nodes (5): Compatibility, Configuration, DonutOrders v1.7.0, New Features, Upgrade Notes

## Knowledge Gaps
- **55 isolated node(s):** `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS`, `NEW_ORDER`, `ENCHANT_PICKER` (+50 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GUIManager` connect `GUIManager` to `Order`, `GUIType`, `DonutOrders.java`, `EnchantOrderUtils`, `DonutOrders`, `.buildOrderItem`, `OrderLimitManager`, `FoliaScheduler`?**
  _High betweenness centrality (0.132) - this node is a cross-community bridge._
- **Why does `Order` connect `Order` to `.buildOrderItem`, `DonutOrders.java`, `GUIManager`, `org.bukkit.inventory.ItemStack`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **Why does `DonutOrders` connect `DonutOrders` to `Order`, `DonutOrders.java`, `GUIManager`, `DonutOrdersExpansion`, `.buildOrderItem`, `OrderLimitManager`, `FoliaScheduler`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **What connects `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS` to the rest of the system?**
  _55 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Order` be split into smaller, more focused modules?**
  _Cohesion score 0.08346134152585766 - nodes in this community are weakly interconnected._
- **Should `DonutOrders.java` be split into smaller, more focused modules?**
  _Cohesion score 0.09437386569872959 - nodes in this community are weakly interconnected._
- **Should `GUIManager` be split into smaller, more focused modules?**
  _Cohesion score 0.08571428571428572 - nodes in this community are weakly interconnected._