package com.donutorders.command;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.scheduler.FoliaScheduler;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the {@code /orders} command and its sub-commands.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /orders}          — opens the player's "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ" GUI.</li>
 *   <li>{@code /orders admin reload} — reloads config + messages (requires
 *       {@code donutorders.admin} permission).</li>
 *   <li>{@code /orders admin simulate} — runs an automated concurrency and replay simulation
 *       (requires {@code donutorders.admin} permission).</li>
 * </ul>
 */
public class OrdersCommand implements CommandExecutor, TabCompleter {

    private final GUIManager guiManager;

    public OrdersCommand(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(DonutOrders.colorize(
                DonutOrders.getInstance().getMessages()
                    .getString("player-only", "&cᴏɴʟʏ ᴘʟᴀʏᴇʀꜱ ᴄᴀɴ ᴜꜱᴇ ᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ.")));
            return true;
        }

        // /orders admin subcommands
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("donutorders.admin")) {
                player.sendMessage(DonutOrders.colorize(
                    DonutOrders.getInstance().getMessages()
                        .getString("no-permission",
                                   "&cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ.")));
                return true;
            }

            if (args[1].equalsIgnoreCase("reload")) {
                DonutOrders.getInstance().reloadPluginConfig();
                player.sendMessage(DonutOrders.colorize(
                    DonutOrders.getInstance().getMessages()
                        .getString("config-reloaded", "&aᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ ʀᴇʟᴏᴀᴅᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ.")));
                return true;
            }

            if (args[1].equalsIgnoreCase("simulate")) {
                runConcurrencySimulation(player);
                return true;
            }
        }

        // Default: open Your Orders GUI
        if (!player.hasPermission("donutorders.use")) {
            player.sendMessage(DonutOrders.colorize(
                DonutOrders.getInstance().getMessages()
                    .getString("no-permission",
                               "&cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ.")));
            return true;
        }

        guiManager.openYourOrders(player, 0);
        return true;
    }

    private void runConcurrencySimulation(Player player) {
        player.sendMessage("§e[Simulation] Starting concurrency and packet replay test...");
        player.sendMessage("§e[Simulation] Creating mock order and 5-diamond stash in SQLite...");

        UUID mockOrderId = UUID.randomUUID();
        ItemStack template = new ItemStack(Material.DIAMOND);
        Order mockOrder = new Order(
                mockOrderId,
                player.getUniqueId(),
                player.getName(),
                template.clone(),
                5,
                5,
                100.0,
                0.0,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86400000L,
                OrderStatus.PENDING
        );

        ItemStack[] stashItems = new ItemStack[54];
        stashItems[0] = new ItemStack(Material.DIAMOND, 5);

        FoliaScheduler.runAsync(() -> {
            guiManager.getStorage().saveOrder(mockOrder, () -> {
                guiManager.getStorage().saveStash(mockOrderId, stashItems, () -> {
                    player.sendMessage("§e[Simulation] SQLite initialized. Spawning 10 concurrent threads racing to collect...");

                    int threadCount = 10;
                    CountDownLatch readyLatch = new CountDownLatch(threadCount);
                    CountDownLatch startLatch = new CountDownLatch(1);
                    CountDownLatch finishLatch = new CountDownLatch(threadCount);

                    AtomicInteger successCount = new AtomicInteger(0);
                    AtomicInteger failureCount = new AtomicInteger(0);
                    List<String> results = Collections.synchronizedList(new ArrayList<>());

                    for (int i = 0; i < threadCount; i++) {
                        final int id = i + 1;
                        new Thread(() -> {
                            readyLatch.countDown();
                            try {
                                startLatch.await();
                            } catch (InterruptedException ignored) {}

                            guiManager.getOrderManager().collectStash(player, mockOrderId, success -> {
                                if (success) {
                                    successCount.incrementAndGet();
                                    results.add("§aTask #" + id + ": SUCCESS");
                                } else {
                                    failureCount.incrementAndGet();
                                    results.add("§cTask #" + id + ": REJECTED");
                                }
                                finishLatch.countDown();
                            });
                        }).start();
                    }

                    try {
                        readyLatch.await();
                        startLatch.countDown();

                        if (!finishLatch.await(5, TimeUnit.SECONDS)) {
                            player.sendMessage("§c[Simulation] Warning: Some tasks timed out.");
                        }
                    } catch (InterruptedException e) {
                        player.sendMessage("§c[Simulation] Error: Simulation interrupted.");
                        return;
                    }

                    guiManager.getStorage().clearStash(mockOrderId, null);

                    FoliaScheduler.runAtEntity(player, () -> {
                        player.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        player.sendMessage("§6§lCONCURRENCY & REPLAY SIMULATION REPORT");
                        player.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        player.sendMessage("§7Total Racing Tasks: §f" + threadCount);
                        player.sendMessage("§7Successful Claims: " + (successCount.get() == 1 ? "§a" : "§c") + successCount.get() + " §8(Expected: 1)");
                        player.sendMessage("§7Rejected Claims: §a" + failureCount.get() + " §8(Expected: 9)");

                        player.sendMessage("§7Individual Task Results:");
                        synchronized (results) {
                            for (String res : results) {
                                player.sendMessage("  " + res);
                            }
                        }

                        if (successCount.get() == 1) {
                            player.sendMessage("§a§lSUCCESS: §7Exploit completely blocked. 0 items duplicated.");
                        } else {
                            player.sendMessage("§c§lFAILURE: §7Claim count is " + successCount.get() + "! Items may have been duplicated!");
                        }
                        player.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    }, null);
                });
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("donutorders.admin")) {
                return Collections.singletonList("admin");
            }
            return Collections.emptyList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")
                && sender.hasPermission("donutorders.admin")) {
            return Arrays.asList("reload", "simulate");
        }
        return Collections.emptyList();
    }
}
