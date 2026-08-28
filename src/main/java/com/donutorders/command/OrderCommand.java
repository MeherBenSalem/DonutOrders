package com.donutorders.command;

import com.donutorders.manager.GUIManager;
import com.donutorders.util.MessageHelper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Handles {@code /order} — the buyer's personal order views.
 *
 * <ul>
 *   <li>{@code /order} — active orders (ACTIVE + PENDING)</li>
 *   <li>{@code /order history} — completed / expired / cancelled / claimed orders</li>
 * </ul>
 */
public class OrderCommand implements CommandExecutor, TabCompleter {

    private final GUIManager guiManager;

    public OrderCommand(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageHelper.send(sender, "player-only",
                "&cᴏɴʟʏ ᴘʟᴀʏᴇʀꜱ ᴄᴀɴ ᴜꜱᴇ ᴛʜɪꜱ ᴄᴏᴍᴍᴀɴᴅ.");
            return true;
        }

        if (!player.hasPermission("donutorders.use")) {
            MessageHelper.send(player, "no-permission",
                "&cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪꜱꜱɪᴏɴ ᴛᴏ ᴅᴏ ᴛʜᴀᴛ.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("history")) {
            guiManager.openYourOrderHistory(player, 0);
            return true;
        }

        if (args.length >= 1) {
            MessageHelper.send(player, "command.order.usage",
                "&cᴜꜱᴀɢᴇ: /order [history]");
            return true;
        }

        guiManager.openYourActiveOrders(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("history");
        }
        return Collections.emptyList();
    }
}
