package io.nightbeam.orderlab;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.api.DonutOrdersApi;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Enchants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Test-only fixture. Registers an in-memory Vault economy before DonutOrders enables, can plant a DonutOrders 1.x
 * database for the import test, and exposes console helpers the smoke runner uses. Report lines start with
 * "[LAB]" so the runner can parse them.
 */
public final class OrderLabPlugin extends JavaPlugin {

    private final LabEconomy economy = new LabEconomy();

    @Override
    public void onLoad() {
        // Vault's ServicesManager is Bukkit's own, so the economy can be registered before any plugin enables.
        Bukkit.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, economy, this, ServicePriority.Highest);
        File marker = new File(getDataFolder().getParentFile(), "DonutOrders/lab-legacy");
        if (marker.isFile()) {
            try {
                plantLegacy(new File(marker.getParentFile(), "donutorders.db"));
                marker.delete();
                report("legacy planted");
            } catch (Exception exception) {
                report("error legacy -> " + exception);
            }
        }
    }

    @Override
    public void onEnable() {
        report("fixture enabled on " + Bukkit.getName() + " " + Bukkit.getBukkitVersion());
    }

    static UUID offlineId(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A 1.x database: an open diamond order with five delivered diamonds in its stash, an expired iron order whose
     * money (40) was never refunded and two iron ingots in its stash, and a claimed gold order.
     */
    private void plantLegacy(File db) throws Exception {
        db.getParentFile().mkdirs();
        long now = System.currentTimeMillis();
        String buyer = offlineId("LegacyBuyer").toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS orders (order_id TEXT PRIMARY KEY, buyer_uuid TEXT NOT NULL, buyer_name TEXT NOT NULL,"
                    + " item_data TEXT NOT NULL, amount_req INTEGER NOT NULL, amount_filled INTEGER NOT NULL, price_per_item REAL NOT NULL,"
                    + " remaining_funds REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL,"
                    + " claimed_at INTEGER DEFAULT 0, claimed_by TEXT DEFAULT NULL, updated_at INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS order_stash (order_id TEXT PRIMARY KEY, stash_data TEXT NOT NULL)");
            Object[][] rows = {
                    {UUID.randomUUID().toString(), Material.DIAMOND, 20, 5, 10.0, 150.0, now + 86_400_000L, "ACTIVE", 5},
                    {UUID.randomUUID().toString(), Material.IRON_INGOT, 10, 2, 5.0, 40.0, now - 3_600_000L, "PENDING", 2},
                    {UUID.randomUUID().toString(), Material.GOLD_INGOT, 4, 4, 7.0, 0.0, now - 7_200_000L, "CLAIMED", 0},
            };
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO orders VALUES(?,?,?,?,?,?,?,?,?,?,?,0,NULL,?)");
                 PreparedStatement stash = connection.prepareStatement("INSERT INTO order_stash VALUES(?,?)")) {
                for (Object[] row : rows) {
                    insert.setString(1, (String) row[0]);
                    insert.setString(2, buyer);
                    insert.setString(3, "LegacyBuyer");
                    insert.setString(4, legacyItem(new ItemStack((Material) row[1])));
                    insert.setInt(5, (int) row[2]);
                    insert.setInt(6, (int) row[3]);
                    insert.setDouble(7, (double) row[4]);
                    insert.setDouble(8, (double) row[5]);
                    insert.setLong(9, now - 86_400_000L);
                    insert.setLong(10, (long) row[6]);
                    insert.setString(11, (String) row[7]);
                    insert.setLong(12, now);
                    insert.executeUpdate();
                    ItemStack[] slots = new ItemStack[54];
                    if ((int) row[8] > 0) slots[0] = new ItemStack((Material) row[1], (int) row[8]);
                    stash.setString(1, (String) row[0]);
                    stash.setString(2, legacyStash(slots));
                    stash.executeUpdate();
                }
            }
        }
    }

    private static String legacyItem(ItemStack item) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String legacyStash(ItemStack[] slots) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(slots.length);
            for (ItemStack slot : slots) out.writeObject(slot);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private void report(String line) {
        getLogger().info("[LAB] " + line);
    }

    private static OrderService orders() {
        return ((DonutOrdersPlugin) Bukkit.getPluginManager().getPlugin("DonutOrders")).orders();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "money" -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    economy.set(target.getUniqueId(), Double.parseDouble(args[2]));
                    report("money " + args[1] + " " + economy.balance(target.getUniqueId()));
                }
                case "balance" -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    report("balance " + args[1] + " " + economy.balance(target.getUniqueId()));
                }
                case "give", "hand", "book", "shulker", "count", "clear", "shulkercount" -> onPlayer(args);
                case "api" -> {
                    DonutOrdersApi api = DonutOrdersApi.get().orElseThrow();
                    report("api active=" + api.activeOrders().size() + " price=" + api.formatPrice(1234.5));
                }
                case "orders" -> {
                    // lab orders <player>: id:status:delivered/amount:material:waiting for each live order
                    UUID owner = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                    String list = orders().cache().ofOwner(owner).stream()
                            .map(order -> order.shortId() + ":" + order.status() + ":" + order.delivered() + "/" + order.amount() + ":"
                                    + order.material() + ":" + order.waiting())
                            .collect(Collectors.joining(","));
                    report("orders " + args[1] + " " + list);
                }
                case "board" -> {
                    // The first board page in the default sort: MATERIAL:owner per slot.
                    String list = orders().cache().active(System.currentTimeMillis()).stream()
                            .sorted(io.nightbeam.donutorders.model.OrderSort.MONEY_PER_ITEM.comparator()).limit(45)
                            .map(order -> order.material() + ":" + order.ownerName()).collect(Collectors.joining(","));
                    report("board " + list);
                }
                case "waiting" -> report("waiting " + args[1] + " " + orders().cache().waitingFor(offlineId(args[1])));
                case "seed" -> seed(Integer.parseInt(args[1]));
                case "sudo" -> {
                    Player player = Bukkit.getPlayerExact(args[1]);
                    String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    player.getScheduler().run(this, task -> report("sudo " + args[1] + " " + line + " -> " + player.performCommand(line)), null);
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            report("error " + String.join(" ", args) + " -> " + exception);
        }
        return true;
    }

    /** Commands that touch a player's inventory run on the player's own thread (Folia). */
    private void onPlayer(String[] args) {
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            report("error " + String.join(" ", args) + " -> not online");
            return;
        }
        player.getScheduler().run(this, task -> {
            try {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "give" -> {
                        Material material = Material.matchMaterial(args[2]);
                        player.getInventory().addItem(new ItemStack(material, Integer.parseInt(args[3])));
                        report("give " + args[1] + " " + material + " " + args[3]);
                    }
                    case "hand" -> {
                        Material material = Material.matchMaterial(args[2]);
                        player.getInventory().setItemInMainHand(new ItemStack(material, Integer.parseInt(args[3])));
                        report("hand " + args[1] + " " + material + " " + args[3]);
                    }
                    case "book" -> {
                        Enchantment enchantment = Enchants.byKey("minecraft:" + args[2]);
                        player.getInventory().addItem(Enchants.book(enchantment, Integer.parseInt(args[3])));
                        report("book " + args[1] + " " + args[2] + " " + args[3]);
                    }
                    case "shulker" -> {
                        // lab shulker <player> <material> <amount>: a shulker box holding that many items, plus a stick
                        ItemStack box = new ItemStack(Material.SHULKER_BOX);
                        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
                        ShulkerBox state = (ShulkerBox) meta.getBlockState();
                        Material material = Material.matchMaterial(args[2]);
                        int left = Integer.parseInt(args[3]);
                        for (int slot = 0; left > 0; slot++) {
                            int amount = Math.min(left, material.getMaxStackSize());
                            state.getInventory().setItem(slot, new ItemStack(material, amount));
                            left -= amount;
                        }
                        state.getInventory().addItem(new ItemStack(Material.STICK, 1));
                        meta.setBlockState(state);
                        box.setItemMeta(meta);
                        player.getInventory().addItem(box);
                        report("shulker " + args[1] + " " + material + " " + args[3]);
                    }
                    case "shulkercount" -> {
                        // lab shulkercount <player>: the items inside the first shulker box in the inventory
                        int total = 0;
                        for (ItemStack stack : player.getInventory().getStorageContents()) {
                            if (stack == null || stack.getType() != Material.SHULKER_BOX) continue;
                            ShulkerBox state = (ShulkerBox) ((BlockStateMeta) stack.getItemMeta()).getBlockState();
                            for (ItemStack content : state.getInventory().getContents()) if (content != null) total += content.getAmount();
                            break;
                        }
                        report("shulkercount " + args[1] + " " + total);
                    }
                    case "count" -> {
                        Material material = Material.matchMaterial(args[2]);
                        int total = 0;
                        for (ItemStack stack : player.getInventory().getContents()) {
                            if (stack != null && stack.getType() == material) total += stack.getAmount();
                        }
                        report("count " + args[1] + " " + material + " " + total);
                    }
                    case "clear" -> {
                        player.getInventory().clear();
                        report("clear " + args[1]);
                    }
                    default -> report("error unknown " + args[0]);
                }
            } catch (RuntimeException exception) {
                report("error " + String.join(" ", args) + " -> " + exception);
            }
        }, null);
    }

    /** lab seed <count>: a believable board from made-up buyers, for screenshots. */
    private void seed(int count) {
        OrderService orders = orders();
        Random random = new Random(7);
        List<String> buyers = List.of("Kiri", "BlazeKing", "Willow", "Pixelore", "Draxen", "Mossbeard", "Aurelia", "TheBaker");
        Object[][] pool = {
                {Material.DIAMOND, 640, 92.0}, {Material.NETHERITE_INGOT, 16, 11500.0}, {Material.ENDER_PEARL, 256, 28.0},
                {Material.BLAZE_ROD, 192, 135.0}, {Material.SHULKER_SHELL, 32, 3200.0}, {Material.GOLDEN_CARROT, 1024, 11.0},
                {Material.OBSIDIAN, 512, 21.0}, {Material.EXPERIENCE_BOTTLE, 768, 52.0}, {Material.TOTEM_OF_UNDYING, 8, 21000.0},
                {Material.ELYTRA, 1, 175000.0}, {Material.SLIME_BALL, 384, 34.0}, {Material.GUNPOWDER, 1024, 9.5},
                {Material.OAK_LOG, 2048, 4.0}, {Material.EMERALD, 320, 61.0}, {Material.NETHER_STAR, 4, 64000.0},
                {Material.STRING, 512, 6.5}, {Material.BONE, 640, 5.0}, {Material.ANCIENT_DEBRIS, 24, 8800.0},
                {Material.SUGAR_CANE, 1536, 3.25}, {Material.IRON_INGOT, 1280, 14.0}, {Material.GOLD_INGOT, 896, 19.0},
                {Material.WHEAT, 2048, 2.5}};
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Object[] entry = pool[i % pool.length];
            String name = buyers.get(i % buyers.size());
            ItemKeys.Spec spec = ItemKeys.material((Material) entry[0]);
            int amount = (int) entry[1];
            double price = Math.round((double) entry[2] * (0.85 + random.nextDouble() * 0.3) * 100.0) / 100.0;
            int delivered = random.nextInt(3) == 0 ? 0 : (int) (amount * random.nextDouble() * 0.8);
            long created = now - random.nextInt(36) * 3_600_000L - random.nextInt(60) * 60_000L;
            Order order = new Order(UUID.randomUUID(), offlineId(name), name, false, spec.template(), spec.key(), spec.template().getType().name(),
                    spec.mode(), amount, delivered, delivered, price, Math.round((amount - delivered) * price * 100.0) / 100.0, created,
                    created + (2 + random.nextInt(12)) * 86_400_000L, OrderStatus.ACTIVE, 0L, now, 0L);
            orders.repository().insert(order).thenRun(() -> orders.cache().apply(order));
        }
        Enchantment mending = Enchants.byKey("minecraft:mending");
        Enchantment sharpness = Enchants.byKey("minecraft:sharpness");
        for (Object[] book : new Object[][] {{mending, 1, 4200.0, "Willow"}, {sharpness, 5, 2600.0, "Draxen"}}) {
            if (book[0] == null) continue;
            ItemKeys.Spec spec = ItemKeys.book((Enchantment) book[0], (int) book[1]);
            Order order = new Order(UUID.randomUUID(), offlineId((String) book[3]), (String) book[3], false, spec.template(), spec.key(),
                    "ENCHANTED_BOOK", spec.mode(), 12, 3, 3, (double) book[2], 9 * (double) book[2], now - 5_400_000L, now + 3 * 86_400_000L,
                    OrderStatus.ACTIVE, 0L, now, 0L);
            orders.repository().insert(order).thenRun(() -> orders.cache().apply(order));
        }
        report("seed " + count);
    }
}
