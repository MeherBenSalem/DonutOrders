# Cross-version smoke matrix

Boots real servers across the supported range and plays the order board with
[mineflayer](https://github.com/PrismarineJS/mineflayer) bots over the network protocol. Every check goes through
the real plugin, the real economy bridge and real inventory packets.

| Target | Java | Bot protocol | Economy bridge |
| --- | --- | --- | --- |
| Paper 1.20.1, 1.20.4 | 17 | same version | Vault / VaultUnlocked |
| Paper 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.11 | 21 | same version | alternating |
| Paper 26.1.2, 26.2, 26.3 | 25 | 26.1 through ViaVersion + ViaBackwards | alternating |
| Folia 1.20.4, 1.21.4, 1.21.11, 26.3 | 17 / 21 / 25 | as above | VaultUnlocked |

Each target runs one scenario (`scenario.mjs`). The setup:
- PlaceholderAPI is installed.
- A smoke `config.yml` sets a 10% seller tax paid to a `Bank` account, durations from one second, and an expiry check
  every 5 seconds.
- A DonutOrders **1.x database** is planted by the fixture before the plugin loads.

The scenario:

1. The plugin enables, imports the 1.x orders and stashes, and registers its API and PlaceholderAPI expansion.
2. The imported 1.x order that expired with its money unrefunded is refunded exactly once. Its stash items are
   collectable.
3. `Buyer` creates a diamond order (`/order create`) and the money is held.
4. `Seller` shift-clicks it on the board. The test checks that 10 diamonds are delivered, 6 come back, the seller is
   paid after tax and the tax account is paid.
5. `Buyer` gets the delivery alert and collects the diamonds.
6. An enchanted-book order is filled through the delivery menu: a shift-click moves the book in, then Deliver.
7. A shulker box with 32 emeralds and a stick is sold into a 20-emerald order with `/orders sell all`. The box comes
   back with 12 emeralds and the stick.
8. An order is cancelled (refund) and another expires after three seconds (refund).
9. A server order (`/orderadmin server wheat unlimited 3`) takes 30 wheat and pays after tax.
10. `papi parse` and console `/orderadmin info` answer.
11. The console is scanned for plugin errors: severe lines, stack frames in `io.nightbeam.donutorders`, and linkage
    errors.

## Several servers

`network.mjs` starts two Paper servers that share one MySQL or MariaDB database.
- An order created on one server shows on the other.
- It is delivered there, and the buyer is told and collects on their own server.
- Two sellers on different servers sell into the same 10-item order at the same moment. Exactly 10 are delivered,
  exactly 100 is paid, and the rest comes back.

```bash
ORDERLAB_MYSQL="127.0.0.1:3306/donutorders_net user password" node tools/smoke/network.mjs
```

## Running it

```bash
mvn -B -DskipTests package
mvn -B -f tools/smoke/fixture/pom.xml package -Ddonutorders.version=2.0.0 \
    -Ddonutorders.jar="$PWD/target/DonutOrders-2.0.0.jar"
npm --prefix tools/smoke install
node tools/smoke/run.mjs                          # all targets (about 45 minutes)
node tools/smoke/run.mjs paper-1.20.1 folia-26.3  # selected targets
```

JDKs (Temurin 17/21/25), server jars (PaperMC Fill API), Vault, VaultUnlocked, PlaceholderAPI and
ViaVersion/ViaBackwards are downloaded on first use and cached in `$ORDERLAB_HOME` (default
`~/.cache/donutorders-smoke`). Servers listen on `$ORDERLAB_PORT` (default 25599). A JSON report is written to
`$ORDERLAB_HOME/reports/`. `node tools/smoke/compat.mjs` turns the newest report into
`marketing/source/compat.json` for the compatibility card.

`fixture/` is a test-only plugin, never to be installed on a real server. It provides:
- an in-memory Vault economy;
- the 1.x database plant;
- console helpers: `lab money`, `balance`, `give`, `hand`, `book`, `shulker`, `shulkercount`, `count`, `clear`,
  `orders`, `board`, `waiting`, `seed`, `sudo` and `api`.
