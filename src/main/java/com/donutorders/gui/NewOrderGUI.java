package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * GUI: "ɴᴇᴡ ᴏʀᴅᴇʀ" — item picker for creating a buy order.
 *
 * <p>Displays the materials listed under {@code allowed-materials} in config.yml.
 * Clicking a material closes the GUI and starts the chat-input flow
 * (amount → price).
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]  Material picker buttons (paginated)
 * [45]    Previous-page button
 * [46]–[47] Filler
 * [48]    Filler
 * [49]    Filler
 * [50]–[52] Filler
 * [53]    Next-page button
 * </pre>
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
        super(Bukkit.createInventory(null, 54, "ɴᴇᴡ ᴏʀᴅᴇʀ"));
        this.guiManager = guiManager;
        this.materials  = loadAllowedMaterials();
        this.page       = page;
        this.maxPage    = materials.isEmpty() ? 0 : Math.max(0, (materials.size() - 1) / PAGE_SIZE);
        build();
    }

    private List<Material> loadAllowedMaterials() {
        FileConfiguration config = DonutOrders.getInstance().getConfig();
        List<String> names = config.getStringList("allowed-materials");
        List<Material> result = new ArrayList<>();
        for (String name : names) {
            try {
                Material material = Material.valueOf(name.toUpperCase());
                if (material.isItem() && material != Material.AIR) {
                    result.add(material);
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid material name in config — skip
            }
        }
        return result;
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, materials.size());

        for (int i = start; i < end; i++) {
            Material mat = materials.get(i);
            inventory.setItem(i - start, ItemUtils.createGuiItem(mat,
                "§f" + ItemUtils.prettyName(mat),
                Arrays.asList(
                    "§8━━━━━━━━━━━━━━━━━━━━",
                    "§7ʟᴇꜰᴛ ᴄʟɪᴄᴋ §eᴛᴏ ꜱᴇʟᴇᴄᴛ",
                    "§8━━━━━━━━━━━━━━━━━━━━"
                )));
        }

        // Pagination
        if (page > 0) {
            inventory.setItem(SLOT_PREV, ItemUtils.createGuiItem(
                Material.ARROW, "§7« ᴘʀᴇᴠɪᴏᴜꜱ",
                Arrays.asList("§8ᴘᴀɢᴇ " + page + " ᴏꜰ " + (maxPage + 1))));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, ItemUtils.createGuiItem(
                Material.ARROW, "§7ɴᴇxᴛ »",
                Arrays.asList("§8ᴘᴀɢᴇ " + (page + 2) + " ᴏꜰ " + (maxPage + 1))));
        }

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.BARRIER, "§c§lʙᴀᴄᴋ",
            Arrays.asList("§7ʀᴇᴛᴜʀɴ ᴛᴏ ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ.")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_PREV && page > 0) {
            guiManager.openNewOrderPicker(player); // Will rebuild at page 0; replace with page-1 variant if needed
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
            if (state != null) {
                NewOrderGUI next = new NewOrderGUI(guiManager, page + 1);
                state.gui = next;
                // openInventory fires InventoryCloseEvent which calls clearState();
                // re-register the state afterwards so the new page is protected.
                player.openInventory(next.getInventory());
                guiManager.setState(player.getUniqueId(), state);
            }
            return;
        }
        if (slot == SLOT_BACK) {
            guiManager.openYourOrders(player, 0);
            return;
        }

        // Material selection
        int matIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && matIndex < materials.size()) {
            Material selectedMat = materials.get(matIndex);
            ItemStack template   = new ItemStack(selectedMat, 1);

            // Store selected item in GUI state so chat callbacks can retrieve it
            GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
            if (state != null) {
                state.selectedItem = template;
            }

            // Close GUI before entering chat mode
            player.closeInventory();

            // Start the chat input flow: first ask for amount
            String amtPrompt = DonutOrders.getInstance().getMessages()
                    .getString("chat-prompt-amount",
                               "&eᴇɴᴛᴇʀ ᴛʜᴇ ᴀᴍᴏᴜɴᴛ ʏᴏᴜ ᴡᴀɴᴛ ᴛᴏ ʙᴜʏ (ᴏʀ &cᴄᴀɴᴄᴇʟ&e):");

            guiManager.getChatInput().requestInput(player, amtPrompt,
                amountStr -> handleAmountInput(player, template, amountStr),
                () -> {
                    player.sendMessage(DonutOrders.colorize(
                        DonutOrders.getInstance().getMessages()
                            .getString("chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.")));
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
            player.sendMessage(DonutOrders.colorize(
                DonutOrders.getInstance().getMessages()
                    .getString("chat-input-invalid-number",
                               "&cɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ᴡʜᴏʟᴇ ɴᴜᴍʙᴇʀ.")));
            // Re-request
            guiManager.getChatInput().requestInput(player,
                DonutOrders.getInstance().getMessages()
                    .getString("chat-prompt-amount", "&eᴇɴᴛᴇʀ ᴀᴍᴏᴜɴᴛ:"),
                s -> handleAmountInput(player, template, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }

        // Store pending amount in state
        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null) state.pendingAmount = amount;

        final int finalAmount = amount;
        String pricePrompt = DonutOrders.getInstance().getMessages()
                .getString("chat-prompt-price",
                           "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ (ᴏʀ &cᴄᴀɴᴄᴇʟ&e):");

        guiManager.getChatInput().requestInput(player, pricePrompt,
            priceStr -> handlePriceInput(player, template, finalAmount, priceStr),
            () -> {
                player.sendMessage(DonutOrders.colorize(
                    DonutOrders.getInstance().getMessages()
                        .getString("chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.")));
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
            player.sendMessage(DonutOrders.colorize(
                DonutOrders.getInstance().getMessages()
                    .getString("chat-input-invalid-price",
                               "&cɪɴᴠᴀʟɪᴅ ᴘʀɪᴄᴇ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ɴᴜᴍʙᴇʀ.")));
            guiManager.getChatInput().requestInput(player,
                DonutOrders.getInstance().getMessages()
                    .getString("chat-prompt-price", "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ:"),
                s -> handlePriceInput(player, template, amount, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }

        final double finalPrice = price;
        // Create the order via OrderManager; result is delivered on player's thread
        guiManager.getOrderManager().createOrder(player, template, amount, finalPrice,
            (success, errorMsg) -> {
                if (success) {
                    String msg = DonutOrders.getInstance().getMessages()
                        .getString("order-created",
                            "&aᴏʀᴅᴇʀ ᴄʀᴇᴀᴛᴇᴅ!")
                        .replace("{0}", ItemUtils.prettyName(template.getType()))
                        .replace("{1}", String.valueOf(amount))
                        .replace("{2}", com.donutorders.util.NumberFormatter.formatPrice(finalPrice))
                        .replace("{3}", com.donutorders.util.NumberFormatter.formatPrice(finalPrice * amount));
                    player.sendMessage(DonutOrders.colorize(
                        DonutOrders.getInstance().getMessages().getString("prefix", "") + msg));
                } else {
                    player.sendMessage(DonutOrders.colorize("§c" + errorMsg));
                }
                guiManager.openYourOrders(player, 0);
            });
    }
}
