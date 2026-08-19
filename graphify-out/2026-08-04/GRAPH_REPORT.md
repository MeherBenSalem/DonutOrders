# Graph Report - DonutOrders  (2026-07-29)

## Corpus Check
- 38 files · ~21,289 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 458 nodes · 1343 edges · 15 communities (13 shown, 2 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 183 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `2c8ffb63`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Order
- GUIManager
- DonutOrders.java
- ItemUtils
- .get
- OrderLimitManager
- DonutOrders
- DeliveryItemUtils
- BaseGUI
- .handleClick
- InventoryListener.java
- GUIType
- GuiPaginationTest
- DonutOrders v1.3.1
- com.donutorders:DonutOrders

## God Nodes (most connected - your core abstractions)
1. `GUIManager` - 63 edges
2. `Order` - 61 edges
3. `DonutOrders` - 31 edges
4. `StorageManager` - 30 edges
5. `OrderLimitManager` - 23 edges
6. `ChatInputHandler` - 19 edges
7. `BaseGUI` - 18 edges
8. `MessageHelper` - 18 edges
9. `OrderManager` - 17 edges
10. `DeliveryItemUtils` - 17 edges

## Surprising Connections (you probably didn't know these)
- `DonutOrders` --references--> `ChatInputHandler`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/ChatInputHandler.java
- `DonutOrders` --references--> `GUIManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/GUIManager.java
- `DonutOrders` --references--> `OrderManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/manager/OrderManager.java
- `DonutOrders` --references--> `StorageManager`  [EXTRACTED]
  src/main/java/com/donutorders/DonutOrders.java → src/main/java/com/donutorders/storage/StorageManager.java
- `DonutOrdersExpansion` --references--> `DonutOrders`  [EXTRACTED]
  src/main/java/com/donutorders/placeholder/DonutOrdersExpansion.java → src/main/java/com/donutorders/DonutOrders.java

## Import Cycles
- None detected.

## Communities (15 total, 2 thin omitted)

### Community 0 - "Order"
Cohesion: 0.07
Nodes (20): HikariDataSource, PreparedStatement, ResultSet, Economy, ItemStack, Logger, Player, OrderManager (+12 more)

### Community 1 - "GUIManager"
Cohesion: 0.09
Nodes (25): Override, ClickType, ItemStack, Material, Override, Player, NewOrderGUI, ClickType (+17 more)

### Community 2 - "DonutOrders.java"
Cohesion: 0.07
Nodes (22): AsyncPlayerChatEvent, Command, CommandExecutor, Entity, Location, LuckPerms, PlayerQuitEvent, CommandSender (+14 more)

### Community 3 - "ItemUtils"
Cohesion: 0.07
Nodes (21): ItemFlag, EnchantLevelGUI, ClickType, Enchantment, ItemStack, Override, Player, EnchantPickerGUI (+13 more)

### Community 4 - ".get"
Cohesion: 0.14
Nodes (3): OrderDetailGUI, YourOrdersGUI, MessageHelper

### Community 5 - "OrderLimitManager"
Cohesion: 0.10
Nodes (17): Listener, NotNull, Nullable, OfflinePlayer, Permissible, PlaceholderExpansion, PlayerJoinEvent, LuckPermsLimitListener (+9 more)

### Community 6 - "DonutOrders"
Cohesion: 0.09
Nodes (10): JavaPlugin, DonutOrders, Economy, FileConfiguration, Override, AllowedItemsManager, FileConfiguration, Material (+2 more)

### Community 7 - "DeliveryItemUtils"
Cohesion: 0.18
Nodes (9): DeliverItemsGUI, ClickType, ItemStack, Override, Player, DeliveryItemUtils, Inventory, ItemStack (+1 more)

### Community 8 - "BaseGUI"
Cohesion: 0.19
Nodes (10): BaseGUI, ClickType, Inventory, ItemStack, Player, CollectStashGUI, ClickType, ItemStack (+2 more)

### Community 9 - ".handleClick"
Cohesion: 0.28
Nodes (7): DecimalFormat, ConfirmDeliveryGUI, ClickType, ItemStack, Override, Player, NumberFormatter

### Community 10 - "InventoryListener.java"
Cohesion: 0.24
Nodes (6): InventoryClickEvent, InventoryCloseEvent, InventoryDragEvent, InventoryListener, EventHandler, PlayerInteractionState

### Community 11 - "GUIType"
Cohesion: 0.20
Nodes (10): GUIType, COLLECT_STASH, CONFIRM_DELIVERY, DELIVER_ITEMS, ENCHANT_LEVEL, ENCHANT_PICKER, NEW_ORDER, ORDER_DETAIL (+2 more)

### Community 13 - "DonutOrders v1.3.1"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.3.1, Improvements, New Features, Upgrade Notes

## Knowledge Gaps
- **22 isolated node(s):** `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS`, `NEW_ORDER`, `ENCHANT_PICKER` (+17 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GUIManager` connect `GUIManager` to `Order`, `DonutOrders.java`, `ItemUtils`, `.get`, `DonutOrders`, `DeliveryItemUtils`, `BaseGUI`, `.handleClick`, `InventoryListener.java`?**
  _High betweenness centrality (0.246) - this node is a cross-community bridge._
- **Why does `DonutOrders` connect `DonutOrders` to `Order`, `GUIManager`, `DonutOrders.java`, `.get`, `OrderLimitManager`?**
  _High betweenness centrality (0.133) - this node is a cross-community bridge._
- **Why does `Order` connect `Order` to `GUIManager`, `.get`, `DonutOrders`, `DeliveryItemUtils`, `BaseGUI`, `.handleClick`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **What connects `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS` to the rest of the system?**
  _22 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Order` be split into smaller, more focused modules?**
  _Cohesion score 0.07453416149068323 - nodes in this community are weakly interconnected._
- **Should `GUIManager` be split into smaller, more focused modules?**
  _Cohesion score 0.09335839598997493 - nodes in this community are weakly interconnected._
- **Should `DonutOrders.java` be split into smaller, more focused modules?**
  _Cohesion score 0.06734006734006734 - nodes in this community are weakly interconnected._