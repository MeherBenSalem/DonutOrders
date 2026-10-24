package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.util.Enchants;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Enchanted book orders: first the enchantment (from the server's registry), then the level. */
final class EnchantGui extends BaseGui {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45, BACK = 49, NEXT = 53;

    private int page;
    private final Enchantment selected;
    private List<Enchantment> shown = List.of();
    private int pages = 1;

    EnchantGui(GuiManager gui, int page, Enchantment selected) {
        super(gui);
        this.page = page;
        this.selected = selected;
    }

    @Override
    public Inventory render(Player player) {
        if (selected != null) return renderLevels();
        List<Enchantment> all = Enchants.all().stream().filter(enchantment -> gui.plugin().getConfig().getStringList("catalog.blocked-enchantments")
                .stream().noneMatch(key -> key.equalsIgnoreCase(Enchants.key(enchantment)))).toList();
        pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));
        shown = all.subList((page - 1) * PAGE_SIZE, Math.min(all.size(), page * PAGE_SIZE));
        Inventory inventory = create(6, name("gui.enchant.title", "Pick an enchantment"));
        for (int slot = 0; slot < shown.size(); slot++) {
            Enchantment enchantment = shown.get(slot);
            ItemStack book = Enchants.book(enchantment, Enchants.maxLevel(enchantment));
            inventory.setItem(slot, io.nightbeam.donutorders.util.ItemBuilder.of(book)
                    .name(Component.translatable(Enchants.translationKey(enchantment)).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .lore(lines(lore("gui.enchant.levels", "<gray>Levels 1 to %max%", "max", String.valueOf(Enchants.maxLevel(enchantment))),
                            Component.empty(), lore("gui.enchant.pick", "<yellow>Click</yellow> <gray>to choose the level"))).build());
        }
        if (page > 1) inventory.setItem(PREVIOUS, button("previous", Material.ARROW).name(name("gui.previous", "<yellow>Previous page")).build());
        if (page < pages) inventory.setItem(NEXT, button("next", Material.ARROW).name(name("gui.next", "<yellow>Next page")).build());
        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        fill(inventory, 45, 53);
        return inventory;
    }

    private Inventory renderLevels() {
        int max = Math.max(1, Math.min(10, Enchants.maxLevel(selected)));
        Inventory inventory = create(3, name("gui.enchant.level-title", "Pick a level"));
        int start = 13 - (max - 1) / 2;
        for (int level = 1; level <= max; level++) {
            ItemStack book = Enchants.book(selected, level);
            book.setAmount(level);
            inventory.setItem(start + level - 1, io.nightbeam.donutorders.util.ItemBuilder.of(book)
                    .lore(lines(lore("gui.enchant.pick-level", "<yellow>Click</yellow> <gray>to order this book"))).build());
        }
        inventory.setItem(22, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        fill(inventory);
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory()) return;
        int slot = event.getRawSlot();
        if (selected != null) {
            if (slot == 22) {
                click(player);
                gui.open(player, new EnchantGui(gui, 1, null));
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.ENCHANTED_BOOK) return;
            int level = Enchants.stored(clicked).values().stream().findFirst().orElse(0);
            if (level <= 0) return;
            click(player);
            gui.openCreate(player, ItemKeys.book(selected, level));
            return;
        }
        if (slot < PAGE_SIZE) {
            if (slot >= shown.size()) return;
            click(player);
            gui.open(player, new EnchantGui(gui, 1, shown.get(slot)));
            return;
        }
        if (slot == PREVIOUS && page > 1) {
            click(player);
            gui.open(player, new EnchantGui(gui, page - 1, null));
        } else if (slot == NEXT && page < pages) {
            click(player);
            gui.open(player, new EnchantGui(gui, page + 1, null));
        } else if (slot == BACK) {
            click(player);
            gui.openCatalog(player);
        }
    }
}
