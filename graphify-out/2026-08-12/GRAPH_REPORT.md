# Graph Report - DonutOrders  (2026-08-04)

## Corpus Check
- 41 files · ~22,962 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 496 nodes · 1434 edges · 15 communities (13 shown, 2 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 189 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `db666eb6`
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
1. `GUIManager` - 64 edges
2. `Order` - 61 edges
3. `DonutOrders` - 35 edges
4. `StorageManager` - 31 edges
5. `OrderLimitManager` - 30 edges
6. `ChatInputHandler` - 19 edges
7. `BaseGUI` - 18 edges
8. `MessageHelper` - 18 edges
9. `PlayerGUIState` - 17 edges
10. `OrderManager` - 17 edges

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

## Communities (15 total, 2 thin omitted)

### Community 0 - "Order"
Cohesion: 0.08
Nodes (17): DecimalFormat, PreparedStatement, ClickType, ItemStack, Override, Player, OrderDetailGUI, ClickType (+9 more)

### Community 1 - "GUIManager"
Cohesion: 0.12
Nodes (20): ClickType, ItemStack, Override, Player, YourOrdersGUI, GUIManager, GUIType, COLLECT_STASH (+12 more)

### Community 2 - "DonutOrders.java"
Cohesion: 0.07
Nodes (20): Command, CommandExecutor, Entity, HikariDataSource, Location, Plugin, ResultSet, CommandSender (+12 more)

### Community 3 - "ItemUtils"
Cohesion: 0.07
Nodes (22): ItemFlag, EnchantLevelGUI, ClickType, Enchantment, ItemStack, Override, Player, EnchantPickerGUI (+14 more)

### Community 4 - ".get"
Cohesion: 0.08
Nodes (18): ClickType, ItemStack, Material, Override, Player, NewOrderGUI, OrderStatus, ACTIVE (+10 more)

### Community 5 - "OrderLimitManager"
Cohesion: 0.08
Nodes (22): LuckPerms, NotNull, Nullable, OfflinePlayer, Permissible, PlaceholderExpansion, PlayerJoinEvent, ConfirmDeliveryGUI (+14 more)

### Community 6 - "DonutOrders"
Cohesion: 0.09
Nodes (10): JavaPlugin, DonutOrders, Economy, FileConfiguration, Override, AllowedItemsManager, FileConfiguration, Material (+2 more)

### Community 7 - "DeliveryItemUtils"
Cohesion: 0.18
Nodes (9): DeliverItemsGUI, ClickType, ItemStack, Override, Player, DeliveryItemUtils, Inventory, ItemStack (+1 more)

### Community 8 - "BaseGUI"
Cohesion: 0.18
Nodes (10): BaseGUI, ClickType, Inventory, ItemStack, Player, CollectStashGUI, ClickType, ItemStack (+2 more)

### Community 9 - ".handleClick"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.5.1, Improvements, New Features, Upgrade Notes

### Community 10 - "InventoryListener.java"
Cohesion: 0.09
Nodes (16): AsyncPlayerChatEvent, InventoryClickEvent, InventoryCloseEvent, InventoryDragEvent, Listener, PlayerQuitEvent, ChatListener, EventHandler (+8 more)

### Community 11 - "GUIType"
Cohesion: 0.29
Nodes (6): Compatibility, Configuration, DonutOrders v1.5.0, Improvements, New Features, Upgrade Notes

### Community 13 - "DonutOrders v1.3.1"
Cohesion: 0.25
Nodes (7): Bug Fixes, Compatibility, Configuration, DonutOrders v1.3.1, Improvements, New Features, Upgrade Notes

## Knowledge Gaps
- **33 isolated node(s):** `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS`, `NEW_ORDER`, `ENCHANT_PICKER` (+28 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GUIManager` connect `GUIManager` to `Order`, `DonutOrders.java`, `ItemUtils`, `.get`, `OrderLimitManager`, `DonutOrders`, `DeliveryItemUtils`, `BaseGUI`, `InventoryListener.java`?**
  _High betweenness centrality (0.245) - this node is a cross-community bridge._
- **Why does `DonutOrders` connect `DonutOrders` to `GUIManager`, `DonutOrders.java`, `.get`, `OrderLimitManager`, `InventoryListener.java`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Why does `Order` connect `Order` to `GUIManager`, `DonutOrders.java`, `.get`, `OrderLimitManager`, `DonutOrders`, `DeliveryItemUtils`, `BaseGUI`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **What connects `com.donutorders:DonutOrders`, `PUBLIC_ORDERS`, `YOUR_ORDERS` to the rest of the system?**
  _33 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Order` be split into smaller, more focused modules?**
  _Cohesion score 0.07859649122807018 - nodes in this community are weakly interconnected._
- **Should `GUIManager` be split into smaller, more focused modules?**
  _Cohesion score 0.1166429587482219 - nodes in this community are weakly interconnected._
- **Should `DonutOrders.java` be split into smaller, more focused modules?**
  _Cohesion score 0.07467532467532467 - nodes in this community are weakly interconnected._