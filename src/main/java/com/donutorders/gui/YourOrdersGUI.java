package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import com.donutorders.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * GUI: the buyer's personal order management screen (active, history, or admin history).
 */
public class YourOrdersGUI extends BaseGUI {

    public enum ViewMode {
        ACTIVE,
        HISTORY,
        ADMIN_HISTORY
    }

    private static final int PAGE_SIZE   = 45;
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_BROWSE = 48;
    private static final int SLOT_NEW    = 49;
    private static final int SLOT_NEXT   = 53;

    private final GUIManager guiManager;
    private final List<Order> orders;
    private final int page;
    private final int maxPage;
    private final ViewMode viewMode;
    private final String adminTargetName;
    private final java.util.UUID adminTargetUuid;

    public YourOrdersGUI(GUIManager guiManager, List<Order> orders, int page,
                         ViewMode viewMode, String adminTargetName) {
        this(guiManager, orders, page, viewMode, null, adminTargetName);
    }

    public YourOrdersGUI(GUIManager guiManager, List<Order> orders, int page,
                         ViewMode viewMode, java.util.UUID adminTargetUuid,
                         String adminTargetName) {
        super(Bukkit.createInventory(null, 54, titleFor(viewMode, adminTargetName)));
        this.guiManager = guiManager;
        this.orders     = orders;
        this.page       = page;
        this.maxPage    = orders.isEmpty() ? 0 : Math.max(0, (orders.size() - 1) / PAGE_SIZE);
        this.viewMode   = viewMode;
        this.adminTargetName = adminTargetName;
        this.adminTargetUuid = adminTargetUuid;
        build();
    }

    private static String titleFor(ViewMode viewMode, String adminTargetName) {
        return switch (viewMode) {
            case ACTIVE -> MessageHelper.get("gui.your-orders.title", "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ");
            case HISTORY -> MessageHelper.get("gui.order-history.title", "ᴏʀᴅᴇʀ ʜɪꜱᴛᴏʀʏ");
            case ADMIN_HISTORY -> MessageHelper.getNamed(
                    "gui.order-history.admin-title",
                    "ᴏʀᴅᴇʀ ʜɪꜱᴛᴏʀʏ: {player}",
                    "player", adminTargetName != null ? adminTargetName : "?");
        };
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, orders.size());

        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildOrderItem(orders.get(i)));
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV, ItemUtils.createGuiItem(
                Material.ARROW,
                MessageHelper.get("buttons.previous.name", "&7« ᴘʀᴇᴠɪᴏᴜꜱ"),
                MessageHelper.getList("buttons.previous.lore",
                    "page", String.valueOf(page),
                    "max_page", String.valueOf(maxPage + 1))));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, ItemUtils.createGuiItem(
                Material.ARROW,
                MessageHelper.get("buttons.next.name", "&7ɴᴇxᴛ »"),
                MessageHelper.getList("buttons.next.lore",
                    "page", String.valueOf(page + 2),
                    "max_page", String.valueOf(maxPage + 1))));
        }

        if (viewMode == ViewMode.ACTIVE) {
            inventory.setItem(SLOT_NEW, ItemUtils.createGuiItem(
                Material.LIME_STAINED_GLASS_PANE,
                MessageHelper.get("gui.your-orders.new-order.name", "&a&lɴᴇᴡ ᴏʀᴅᴇʀ"),
                MessageHelper.getList("gui.your-orders.new-order.lore")));

            inventory.setItem(SLOT_BROWSE, ItemUtils.createGuiItem(
                Material.COMPASS,
                MessageHelper.get("gui.your-orders.browse-market.name", "&b&lʙʀᴏᴡꜱᴇ ᴍᴀʀᴋᴇᴛ"),
                MessageHelper.getList("gui.your-orders.browse-market.lore")));
        } else if (viewMode == ViewMode.HISTORY) {
            inventory.setItem(SLOT_NEW, ItemUtils.createGuiItem(
                Material.BOOK,
                MessageHelper.get("gui.order-history.active-orders.name", "&e&lᴀᴄᴛɪᴠᴇ ᴏʀᴅᴇʀꜱ"),
                MessageHelper.getList("gui.order-history.active-orders.lore")));

            inventory.setItem(SLOT_BROWSE, ItemUtils.createGuiItem(
                Material.COMPASS,
                MessageHelper.get("gui.your-orders.browse-market.name", "&b&lʙʀᴏᴡꜱᴇ ᴍᴀʀᴋᴇᴛ"),
                MessageHelper.getList("gui.your-orders.browse-market.lore")));
        }

        fillEmpty();
    }

    private ItemStack buildOrderItem(Order order) {
        Material mat = order.getItemTemplate().getType();
        String statusColor = MessageHelper.statusColor(order.getStatus());
        String statusName  = MessageHelper.statusName(order.getStatus());

        boolean hasStash = order.getStatus() == OrderStatus.PENDING
                || order.getAmountFulfilled() > 0
                || order.getStatus() == OrderStatus.EXPIRED
                || order.getStatus() == OrderStatus.CANCELLED;

        String stashLine = hasStash
                ? MessageHelper.get("gui.your-orders.stash-has-items", "&e⬛ ꜱᴛᴀꜱʜ ʜᴀꜱ ɪᴛᴇᴍꜱ")
                : MessageHelper.get("gui.your-orders.stash-empty", "&7ꜱᴛᴀꜱʜ ɪꜱ ᴇᴍᴘᴛʏ");

        return ItemUtils.createGuiItem(mat,
            MessageHelper.getNamed("gui.your-orders.order-item.name",
                "{status_color}{item}",
                "status_color", statusColor,
                "item", ItemUtils.describeOrderItem(order.getItemTemplate())),
            MessageHelper.getList("gui.your-orders.order-item.lore",
                "status_color", statusColor,
                "status", statusName,
                "fulfilled", NumberFormatter.format(order.getAmountFulfilled()),
                "requested", NumberFormatter.format(order.getAmountRequested()),
                "price", NumberFormatter.formatPrice(order.getPricePerItem()),
                "expiry", order.getFormattedExpiry(),
                "stash_line", stashLine));
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (viewMode == ViewMode.ACTIVE) {
            if (slot == SLOT_NEW) {
                guiManager.openNewOrderPicker(player);
                return;
            }
            if (slot == SLOT_BROWSE) {
                guiManager.openPublicOrders(player, 0);
                return;
            }
        } else if (viewMode == ViewMode.HISTORY) {
            if (slot == SLOT_NEW) {
                guiManager.openYourActiveOrders(player, 0);
                return;
            }
            if (slot == SLOT_BROWSE) {
                guiManager.openPublicOrders(player, 0);
                return;
            }
        }

        if (slot == SLOT_PREV && page > 0) {
            reopen(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            reopen(player, page + 1);
            return;
        }

        if (viewMode == ViewMode.ADMIN_HISTORY) {
            return;
        }

        int orderIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && orderIndex < orders.size()) {
            guiManager.openOrderDetail(player, orders.get(orderIndex).getOrderId());
        }
    }

    private void reopen(Player player, int newPage) {
        switch (viewMode) {
            case ACTIVE -> guiManager.openYourActiveOrders(player, newPage);
            case HISTORY -> guiManager.openYourOrderHistory(player, newPage);
            case ADMIN_HISTORY -> {
                if (adminTargetUuid != null) {
                    guiManager.openAdminOrderHistory(
                            player, adminTargetUuid, adminTargetName, newPage);
                }
            }
        }
    }
}
