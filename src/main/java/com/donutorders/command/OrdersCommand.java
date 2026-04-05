package com.donutorders.command;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Handles the {@code /orders} command and its sub-commands.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /orders}          — opens the player's "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ" GUI.</li>
 *   <li>{@code /orders admin reload} — reloads config + messages (requires
 *       {@code donutorders.admin} permission).</li>
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

        // /orders admin reload
        if (args.length >= 2
                && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("reload")) {

            if (!player.hasPermission("donutorders.admin")) {
                player.sendMessage(DonutOrders.colorize(
                    DonutOrders.getInstance().getMessages()
                        .getString("no-permission",
                                   "&cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ.")));
                return true;
            }

            DonutOrders.getInstance().reloadPluginConfig();
            player.sendMessage(DonutOrders.colorize(
                DonutOrders.getInstance().getMessages()
                    .getString("config-reloaded", "&aᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ ʀᴇʟᴏᴀᴅᴇᴅ ꜱᴜᴄᴄᴇꜱꜱꜰᴜʟʟʏ.")));
            return true;
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
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }
}
