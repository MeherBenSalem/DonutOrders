package com.donutorders.command;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
 * Handles {@code /orderadmin} — console-safe admin commands.
 *
 * <ul>
 *   <li>{@code /orderadmin reload}</li>
 *   <li>{@code /orderadmin history &lt;player&gt;}</li>
 *   <li>{@code /orderadmin simulate} (player only)</li>
 * </ul>
 */
public class OrderAdminCommand implements CommandExecutor, TabCompleter {

    private final GUIManager guiManager;

    public OrderAdminCommand(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission("donutorders.admin")) {
            MessageHelper.send(sender, "no-permission",
                "&cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ ᴛᴏ ᴅᴏ ᴛʜᴀᴛ.");
            return true;
        }

        if (args.length == 0) {
            MessageHelper.send(sender, "command.orderadmin.usage",
                "&cᴜꜱᴀɢᴇ: /orderadmin <reload|history|simulate> [player]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                DonutOrders.getInstance().reloadPluginConfig();
                MessageHelper.send(sender, "config-reloaded",
                    "&aᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ ʀᴇʟᴏᴀᴅᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ.");
                return true;
            }
            case "simulate" -> {
                if (!(sender instanceof Player player)) {
                    MessageHelper.send(sender, "player-only",
                        "&cᴏɴʟʏ ᴘʟᴀʏᴇʀꜱ ᴄᴀɴ ʀᴜɴ ᴛʜᴇ ꜱɪᴍᴜʟᴀᴛɪᴏɴ.");
                    return true;
                }
                runConcurrencySimulation(player);
                return true;
            }
            case "history" -> {
                if (!(sender instanceof Player admin)) {
                    MessageHelper.send(sender, "player-only",
                        "&cᴏɴʟʏ ᴘʟᴀʏᴇʀꜱ ᴄᴀɴ ᴏᴘᴇɴ ᴛʜᴇ ʜɪꜱᴛᴏʀʏ ɢᴜɪ.");
                    return true;
                }
                if (args.length < 2) {
                    MessageHelper.send(admin, "command.orderadmin.history-usage",
                        "&cᴜꜱᴀɢᴇ: /orderadmin history <player>");
                    return true;
                }
                resolveTarget(args[1]).ifPresentOrElse(
                    target -> guiManager.openAdminOrderHistory(
                        admin, target.uuid(), target.name(), 0),
                    () -> MessageHelper.send(admin, "player-not-found",
                        "&cᴘʟᴀʏᴇʀ ɴᴏᴛ ꜰᴏᴜɴᴅ: &f{player}", "player", args[1]));
                return true;
            }
            default -> {
                MessageHelper.send(sender, "command.orderadmin.usage",
                    "&cᴜꜱᴀɢᴇ: /orderadmin <reload|history|simulate> [player]");
                return true;
            }
        }
    }

    private Optional<TargetPlayer> resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new TargetPlayer(online.getUniqueId(), online.getName()));
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            String display = offline.getName() != null ? offline.getName() : name;
            return Optional.of(new TargetPlayer(offline.getUniqueId(), display));
        }

        for (Order order : guiManager.getStorage().getAllOrders()) {
            if (order.getBuyerName() != null
                    && order.getBuyerName().equalsIgnoreCase(name)) {
                return Optional.of(new TargetPlayer(order.getBuyerUUID(), order.getBuyerName()));
            }
        }
        return Optional.empty();
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
        if (!sender.hasPermission("donutorders.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(Arrays.asList("reload", "history", "simulate"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filterPrefix(names, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private record TargetPlayer(UUID uuid, String name) {}
}
