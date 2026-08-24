package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.util.ChatInputParser;
import com.donutorders.util.EnchantOrderUtils;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * GUI: item picker for creating a buy order.
 *
 * <p>Displays the materials listed in {@code items.yml}.
 * Clicking a material closes the GUI and starts the chat-input flow
 * (amount → price), or opens the enchant picker for enchanted books.
 */
public class NewOrderGUI extends BaseGUI {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CLEAR = 46;
    private static final int SLOT_SEARCH = 48;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_NO_RESULTS = 22;

    private final GUIManager guiManager;
    private final List<Material> materials;
    private final String searchQuery;
    private final int page;
    private final int maxPage;

    /** First-page constructor (no search). */
    public NewOrderGUI(GUIManager guiManager) {
        this(guiManager, 0, null);
    }

    public NewOrderGUI(GUIManager guiManager, int page) {
        this(guiManager, page, null);
    }

    public NewOrderGUI(GUIManager guiManager, int page, String searchQuery) {
        super(Bukkit.createInventory(null, 54, buildTitle(searchQuery)));
        this.guiManager  = guiManager;
        this.searchQuery = normalizeSearchQuery(searchQuery);
        this.materials   = filterMaterials(loadAllowedMaterials(), this.searchQuery);
        this.page        = page;
        this.maxPage     = materials.isEmpty() ? 0 : Math.max(0, (materials.size() - 1) / PAGE_SIZE);
        build();
    }

    private static String buildTitle(String searchQuery) {
        String normalized = normalizeSearchQuery(searchQuery);
        if (normalized == null) {
            return MessageHelper.get("gui.new-order.title", "ɴᴇᴡ ᴏʀᴅᴇʀ");
        }
        return MessageHelper.getNamed("gui.new-order.title-filtered",
                "&fɴᴇᴡ ᴏʀᴅᴇʀ &8| &e{query}",
                "query", normalized);
    }

    private static String normalizeSearchQuery(String searchQuery) {
        if (searchQuery == null || searchQuery.isBlank()) {
            return null;
        }
        return searchQuery.trim();
    }

    /**
     * Filters {@code materials} by a case-insensitive query against
     * {@link Material#name()} (underscores as spaces) and {@link ItemUtils#prettyName}.
     */
    public static List<Material> filterMaterials(List<Material> materials, String searchQuery) {
        String query = normalizeSearchQuery(searchQuery);
        if (query == null) {
            return new ArrayList<>(materials);
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        return materials.stream()
                .filter(mat -> matchesMaterialSearch(mat, lowerQuery))
                .collect(Collectors.toList());
    }

    private static boolean matchesMaterialSearch(Material material, String lowerQuery) {
        String enumName = material.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        String prettyName = ItemUtils.prettyName(material).toLowerCase(Locale.ROOT);
        return enumName.contains(lowerQuery) || prettyName.contains(lowerQuery);
    }

    private List<Material> loadAllowedMaterials() {
        return DonutOrders.getInstance().getAllowedItemsManager().getAllowedMaterials();
    }

    private void build() {
        if (materials.isEmpty() && searchQuery != null) {
            inventory.setItem(SLOT_NO_RESULTS, ItemUtils.createGuiItem(
                    Material.BARRIER,
                    MessageHelper.get("gui.new-order.no-results.name", "&c&lɴᴏ ʀᴇꜱᴜʟᴛꜱ"),
                    MessageHelper.getList("gui.new-order.no-results.lore",
                            "query", searchQuery)));
        } else {
            int start = page * PAGE_SIZE;
            int end   = Math.min(start + PAGE_SIZE, materials.size());

            for (int i = start; i < end; i++) {
                Material mat = materials.get(i);
                inventory.setItem(i - start, ItemUtils.createGuiItem(mat,
                    MessageHelper.getNamed("gui.new-order.material.name", "&f{item}",
                        "item", ItemUtils.prettyName(mat)),
                    MessageHelper.getList("gui.new-order.material.lore")));
            }
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

        if (searchQuery != null) {
            inventory.setItem(SLOT_CLEAR, ItemUtils.createGuiItem(
                    Material.RED_STAINED_GLASS_PANE,
                    MessageHelper.get("gui.new-order.clear-search.name", "&c&lᴄʟᴇᴀʀ ꜱᴇᴀʀᴄʜ"),
                    MessageHelper.getList("gui.new-order.clear-search.lore",
                            "query", searchQuery)));
        }

        inventory.setItem(SLOT_SEARCH, ItemUtils.createGuiItem(
                Material.COMPASS,
                MessageHelper.get("gui.new-order.search.name", "&e&lꜱᴇᴀʀᴄʜ"),
                MessageHelper.getList("gui.new-order.search.lore")));

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
        if (slot == SLOT_CLEAR && searchQuery != null) {
            guiManager.openNewOrderPickerWithSearch(player, null);
            return;
        }
        if (slot == SLOT_SEARCH) {
            beginSearchFlow(player);
            return;
        }
        if (slot == SLOT_BACK) {
            guiManager.openYourOrders(player, 0);
            return;
        }

        int matIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && matIndex < materials.size()) {
            Material selectedMat = materials.get(matIndex);
            if (EnchantOrderUtils.needsEnchantPicker(selectedMat)) {
                guiManager.openEnchantPicker(player, 0);
                return;
            }

            ItemStack template = new ItemStack(selectedMat, 1);
            beginAmountPriceFlow(guiManager, player, template);
        }
    }

    private void beginSearchFlow(Player player) {
        player.closeInventory();

        String cancelWord = MessageHelper.cancelKeyword();
        String prompt = MessageHelper.get("gui.new-order.search-prompt",
                "&eᴇɴᴛᴇʀ ᴀɴ ɪᴛᴇᴍ ɴᴀᴍᴇ ᴛᴏ ꜱᴇᴀʀᴄʜ (ᴏʀ &c{0}&e):", cancelWord);

        guiManager.getChatInput().requestInput(player, prompt,
                input -> guiManager.openNewOrderPickerWithSearch(player, input),
                () -> {
                    MessageHelper.send(player, "chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.");
                    guiManager.openNewOrderPicker(player, page);
                });
    }

    // ── Chat input chain ──────────────────────────────────────────────────────

    /**
     * Closes the GUI and starts the amount → price chat flow for {@code template}.
     */
    public static void beginAmountPriceFlow(GUIManager guiManager, Player player, ItemStack template) {
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
            amountStr -> handleAmountInput(guiManager, player, template, amountStr),
            () -> {
                MessageHelper.send(player, "chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.");
                guiManager.openYourOrders(player, 0);
            }
        );
    }

    private static void handleAmountInput(GUIManager guiManager, Player player,
                                          ItemStack template, String raw) {
        var parsed = ChatInputParser.parseAmount(raw);
        if (parsed.isEmpty()) {
            MessageHelper.send(player, "chat-input-invalid-number",
                "&cɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ᴡʜᴏʟᴇ ɴᴜᴍʙᴇʀ.");
            String cancelWord = MessageHelper.cancelKeyword();
            guiManager.getChatInput().requestInput(player,
                MessageHelper.get("chat-prompt-amount",
                    "&eᴇɴᴛᴇʀ ᴛʜᴇ ᴀᴍᴏᴜɴᴛ ʏᴏᴜ ᴡᴀɴᴛ ᴛᴏ ʙᴜʏ (ᴏʀ &c{0}&e):", cancelWord),
                s -> handleAmountInput(guiManager, player, template, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }
        int amount = parsed.getAsInt();

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null) state.pendingAmount = amount;

        final int finalAmount = amount;
        String cancelWord = MessageHelper.cancelKeyword();
        String pricePrompt = MessageHelper.get("chat-prompt-price",
                "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ (ᴏʀ &c{0}&e):", cancelWord);

        guiManager.getChatInput().requestInput(player, pricePrompt,
            priceStr -> handlePriceInput(guiManager, player, template, finalAmount, priceStr),
            () -> {
                MessageHelper.send(player, "chat-input-cancelled", "&7ɪɴᴘᴜᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ.");
                guiManager.openYourOrders(player, 0);
            }
        );
    }

    private static void handlePriceInput(GUIManager guiManager, Player player,
                                         ItemStack template, int amount, String raw) {
        var parsed = ChatInputParser.parsePrice(raw);
        if (parsed.isEmpty()) {
            MessageHelper.send(player, "chat-input-invalid-price",
                "&cɪɴᴠᴀʟɪᴅ ᴘʀɪᴄᴇ. ᴘʟᴇᴀꜱᴇ ᴇɴᴛᴇʀ ᴀ ᴘᴏꜱɪᴛɪᴠᴇ ɴᴜᴍʙᴇʀ.");
            String cancelWord = MessageHelper.cancelKeyword();
            guiManager.getChatInput().requestInput(player,
                MessageHelper.get("chat-prompt-price",
                    "&eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ ᴘᴇʀ ɪᴛᴇᴍ (ᴏʀ &c{0}&e):", cancelWord),
                s -> handlePriceInput(guiManager, player, template, amount, s),
                () -> guiManager.openYourOrders(player, 0));
            return;
        }
        double price = parsed.getAsDouble();

        final double finalPrice = price;
        guiManager.getOrderManager().createOrder(player, template, amount, finalPrice,
            (success, errorMsg) -> {
                if (success) {
                    MessageHelper.sendPrefixed(player, "order-created",
                        "&aᴏʀᴅᴇʀ ᴄʀᴇᴀᴛᴇᴅ!",
                        ItemUtils.describeOrderItem(template),
                        String.valueOf(amount),
                        com.donutorders.util.NumberFormatter.formatPrice(finalPrice),
                        com.donutorders.util.NumberFormatter.formatPrice(finalPrice * amount));
                } else {
                    player.sendMessage(errorMsg != null ? errorMsg
                            : MessageHelper.get("order-create-failed",
                                "&cꜰᴀɪʟᴇᴅ ᴛᴏ ᴄʀᴇᴀᴛᴇ ᴏʀᴅᴇʀ. ᴘʟᴇᴀꜱᴇ ᴛʀʏ ᴀɢᴀɪɴ."));
                }
                guiManager.openYourOrders(player, 0);
            });
    }
}
