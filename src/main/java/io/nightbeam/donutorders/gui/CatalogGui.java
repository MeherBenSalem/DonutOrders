package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.OrderCategory;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.util.ItemNames;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Picks what a new order asks for: every item the server has (and the rules allow), by category or search.
 * Enchanted books lead to the enchantment picker; potions and other items with variants are ordered by
 * holding one.
 */
final class CatalogGui extends BaseGui {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45, CATEGORY = 46, SEARCH = 47, HELD = 49, BACK = 50, NEXT = 53;
    private static volatile List<Material> all;

    private final OrderCategory category;
    private final String search;
    private int page;
    private List<Material> shown = List.of();
    private int pages = 1;

    CatalogGui(GuiManager gui, OrderCategory category, String search, int page) {
        super(gui);
        this.category = category;
        this.search = search == null ? "" : search;
        this.page = page;
    }

    @Override
    public Inventory render(Player player) {
        List<Material> matching = materials(player);
        pages = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));
        shown = matching.subList((page - 1) * PAGE_SIZE, Math.min(matching.size(), page * PAGE_SIZE));
        Inventory inventory = create(6, name("gui.catalog.title", "New order: pick an item (%page%/%pages%)", "page", String.valueOf(page),
                "pages", String.valueOf(pages)));
        for (int slot = 0; slot < shown.size(); slot++) {
            Material material = shown.get(slot);
            inventory.setItem(slot, io.nightbeam.donutorders.util.ItemBuilder.of(material).hideAll().name(ItemNames.component(new ItemStack(material))
                            .colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .lore(lines(material == Material.ENCHANTED_BOOK
                            ? lore("gui.catalog.pick-book", "<yellow>Click</yellow> <gray>to choose the enchantment")
                            : lore("gui.catalog.pick", "<yellow>Click</yellow> <gray>to order this"))).build());
        }
        if (page > 1) inventory.setItem(PREVIOUS, button("previous", Material.ARROW).name(name("gui.previous", "<yellow>Previous page")).build());
        if (page < pages) inventory.setItem(NEXT, button("next", Material.ARROW).name(name("gui.next", "<yellow>Next page")).build());
        List<Component> categoryLines = new ArrayList<>();
        for (OrderCategory option : OrderCategory.values()) {
            if (option == OrderCategory.SPAWNERS) continue;
            categoryLines.add(messages().component(option == category ? "gui.board.option-selected" : "gui.board.option",
                    option == category ? "<#49d6a0>▶ <white>%option%" : "<dark_gray>  %option%", "option", messages().category(option)));
        }
        inventory.setItem(CATEGORY, gui.button("category", category.icon()).name(name("gui.board.category", "<#49d6a0>Category"))
                .lore(categoryLines).build());
        inventory.setItem(SEARCH, button("search", Material.OAK_SIGN).name(name("gui.board.search", "<#49d6a0>Search"))
                .lore(lines(search.isBlank() ? null : lore("gui.board.search-current", "<gray>Searching: <white>%search%", "search", search),
                        search.isBlank() ? null : lore("gui.board.search-clear", "<yellow>Right-click</yellow> <gray>to clear")))
                .glow(!search.isBlank()).build());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) {
            ItemStack held = hand.clone();
            held.setAmount(1);
            inventory.setItem(HELD, gui.button("held", Material.ITEM_FRAME).name(name("gui.catalog.held", "<#49d6a0>Order the item in your hand"))
                    .lore(lines(lore("gui.catalog.held-line", "<gray>%item%", "item", ItemNames.fragment(held)),
                            lore("gui.catalog.held-hint", "<gray>For potions, custom items and anything with variants"))).build());
        } else {
            inventory.setItem(HELD, button("held-empty", Material.ITEM_FRAME).name(name("gui.catalog.held", "<#49d6a0>Order the item in your hand"))
                    .lore(lines(lore("gui.catalog.held-none", "<gray>Hold an item to order exactly that item"))).build());
        }
        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        fill(inventory, 45, 53);
        return inventory;
    }

    private List<Material> materials(Player player) {
        String needle = search.toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Material> matching = new ArrayList<>();
        for (Material material : catalog()) {
            if (!category.matches(material)) continue;
            if (!needle.isEmpty() && !material.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (gui.orders().rules().checkItem(player, new ItemStack(material)).isPresent()) continue;
            matching.add(material);
        }
        return matching;
    }

    /** Every orderable item of this server: featured ones first, then A to Z. */
    private List<Material> catalog() {
        List<Material> known = all;
        if (known != null) return known;
        List<String> featured = gui.plugin().getConfig().getStringList("catalog.featured").stream()
                .map(name -> name.toUpperCase(Locale.ROOT)).toList();
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_") || material.isAir() || !material.isItem()) continue;
            if (ItemKeys.EXACT_ONLY.contains(material.name())) continue;
            if (world != null && !enabled(material, world)) continue;
            materials.add(material);
        }
        materials.sort(Comparator.comparingInt((Material material) -> {
            int index = featured.indexOf(material.name());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(Material::name));
        all = List.copyOf(materials);
        return all;
    }

    static void clearCache() {
        all = null;
    }

    /** Items behind an experimental data pack that is off (bundles on 1.20) are left out. */
    private static boolean enabled(Material material, World world) {
        try {
            Method method = Material.class.getMethod("isEnabledByFeature", World.class);
            return (boolean) method.invoke(material, world);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            return true;
        }
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory()) return;
        int slot = event.getRawSlot();
        if (slot < PAGE_SIZE) {
            if (slot >= shown.size()) return;
            Material material = shown.get(slot);
            click(player);
            if (material == Material.ENCHANTED_BOOK) gui.open(player, new EnchantGui(gui, 1, null));
            else gui.openCreate(player, ItemKeys.material(material));
            return;
        }
        switch (slot) {
            case PREVIOUS -> reopen(player, category, search, page - 1);
            case NEXT -> reopen(player, category, search, page + 1);
            case CATEGORY -> {
                OrderCategory next = event.isRightClick() ? category.previous() : category.next();
                if (next == OrderCategory.SPAWNERS) next = event.isRightClick() ? next.previous() : next.next();
                reopen(player, next, search, 1);
            }
            case SEARCH -> {
                if (event.isRightClick() && !search.isBlank()) {
                    reopen(player, category, "", 1);
                    return;
                }
                gui.ask(player, "input.catalog-search", "&eType part of an item name in chat, or &6%cancel% &eto go back.",
                        (who, text) -> gui.open(who, new CatalogGui(gui, category, text, 1)),
                        () -> gui.open(player, new CatalogGui(gui, category, search, page)));
            }
            case HELD -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) return;
                click(player);
                gui.openCreate(player, ItemKeys.fromHeld(hand));
            }
            case BACK -> {
                click(player);
                gui.openMyOrders(player, 1);
            }
            default -> {
            }
        }
    }

    private void reopen(Player player, OrderCategory category, String search, int page) {
        click(player);
        gui.open(player, new CatalogGui(gui, category, search, page));
    }
}
