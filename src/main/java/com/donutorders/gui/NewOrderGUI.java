package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * GUI: item picker for creating a buy order.
 *
 * <p>Displays the materials listed in {@code items.yml}.
 * Clicking a material closes the GUI and starts the chat-input flow
 * (amount → price).
 */
public class NewOrderGUI extends BaseGUI {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BACK = 49;

    private final GUIManager guiManager;
    private final List<Material> materials;
    private final int page;
    private final int maxPage;

    /** First-page constructor. */
    public NewOrderGUI(GUIManager guiManager) {
        this(guiManager, 0);
    }

    public NewOrderGUI(GUIManager guiManager, int page) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.new-order.title", "ɴᴇᴡ ᴏʀᴅᴇʀ")));
        this.guiManager = guiManager;
        this.materials  = loadAllowedMaterials();
        this.page       = page;
        this.maxPage    = materials.isEmpty() ? 0 : Math.max(0, (materials.size() - 1) / PAGE_SIZE);
        build();
    }

    private List<Material> loadAllowedMaterials() {
        return DonutOrders.getInstance().getAllowedItemsManager().getAllowedMaterials();
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, materials.size());

        for (int i = start; i < end; i++) {
            Material mat = materials.get(i);
            inventory.setItem(i - start, ItemUtils.createGuiItem(mat,
                MessageHelper.getNamed("gui.new-order.material.name", "&f{item}",
                    "item", ItemUtils.prettyName(mat)),
                MessageHelper.getList("gui.new-order.material.lore")));
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

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.BARRIER,
            MessageHelper.get("gui.new-order.back.name", "&c&lʙᴀᴄᴋ"),
            MessageHelper.getList("gui.new-order.back.lore")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_PREV && page > 0) {
            guiManager.openNewOrderPicker(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            guiManager.openNewOrderPicker(player, page + 1);
            return;
        }
        if (slot == SLOT_BACK) {
            guiManager.openYourOrders(player, 0);
            return;
        }

        int matIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && matIndex < materials.size()) {
            Material selectedMat = materials.get(matIndex);
            ItemStack template   = new ItemStack(selectedMat, 1);

            GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
            if (state != null) {
                state.selectedItem = template;
            }

            player.closeInventory();

            String cancelWord = MessageHelper.cancelKeyword();
            String amtPrompt = MessageHelper.get("chat-prompt-amount",
                    "&eᴇɴᴛᴇʀ ᴛʜᴇ ᴀᴍᴏᴜɴᴛ ʏᴏᴜ ᴡᴀɴᴛ ᴛᴏ ʙᴜʏ (ᴏʀ &c{0}&e):",
                    cancelWord);

            guiManager.getChatInput().requestInput(player, amtPrompt,
                amountStr -> handleAmountInput(player, template, amountStr),
                () -> {
                    MessageHelper.send(player, "chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.");
                    guiManager.openYourOrders(player, 0);
                }
            );
        }
    }

    // ── Chat input chain ──────────────────────────────────────────────────────

    private void handleAmountInput(Player player, ItemStack template, String raw) {
        int amount;
        try {
            amount = Integer.parseInt(raw);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            MessageHelper.send(player, "chat-input-invalid-number",
                "&cɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ᴡʜᴏʟᴇ ɴᴜᴍʙᴇʀ.");
            String cancelWord = MessageHelper.cancelKeyword();
            guiManager.getChatInput().requestInput(player,
                MessageHelper.get("chat-prompt-amount",
                    "&eᴇɴᴛᴇʀ ᴛʜᴇ ᴀᴍᴏᴜɴᴛ ʏᴏᴜ ᴡᴀɴᴛ ᴛᴏ ʙᴜʏ (ᴏʀ &c{0}&e):", cancelWord),
                s -> handleAmountInput(player, template, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null) state.pendingAmount = amount;

        final int finalAmount = amount;
        String cancelWord = MessageHelper.cancelKeyword();
        String pricePrompt = MessageHelper.get("chat-prompt-price",
                "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ (ᴏʀ &c{0}&e):", cancelWord);

        guiManager.getChatInput().requestInput(player, pricePrompt,
            priceStr -> handlePriceInput(player, template, finalAmount, priceStr),
            () -> {
                MessageHelper.send(player, "chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.");
                guiManager.openYourOrders(player, 0);
            }
        );
    }

    private void handlePriceInput(Player player, ItemStack template, int amount, String raw) {
        double price;
        try {
            price = Double.parseDouble(raw);
            if (price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            MessageHelper.send(player, "chat-input-invalid-price",
                "&cɪɴᴠᴀʟɪᴅ ᴘʀɪᴄᴇ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ɴᴜᴍʙᴇʀ.");
            String cancelWord = MessageHelper.cancelKeyword();
            guiManager.getChatInput().requestInput(player,
                MessageHelper.get("chat-prompt-price",
                    "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ (ᴏʀ &c{0}&e):", cancelWord),
                s -> handlePriceInput(player, template, amount, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }

        final double finalPrice = price;
        guiManager.getOrderManager().createOrder(player, template, amount, finalPrice,
            (success, errorMsg) -> {
                if (success) {
                    MessageHelper.sendPrefixed(player, "order-created",
                        "&aᴏʀᴅᴇʀ ᴄʀᴇᴀᴛᴇᴅ!",
                        ItemUtils.prettyName(template.getType()),
                        String.valueOf(amount),
                        com.donutorders.util.NumberFormatter.formatPrice(finalPrice),
                        com.donutorders.util.NumberFormatter.formatPrice(finalPrice * amount));
                } else {
                    // errorMsg is already colorized from OrderManager / MessageHelper
                    player.sendMessage(errorMsg != null ? errorMsg
                            : MessageHelper.get("order-create-failed",
                                "&cꜰᴀɪʟᴇᴅ ᴛᴏ ᴄʀᴇᴀᴛᴇ ᴏʀᴅᴇʀ. ᴘʟᴇᴀꜱᴇ ᴛʀʏ ᴀɢᴀɪɴ."));
                }
                guiManager.openYourOrders(player, 0);
            });
    }
}
