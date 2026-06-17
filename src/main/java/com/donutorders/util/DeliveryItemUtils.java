package com.donutorders.util;

import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts and extracts deliverable items for buy-order fulfillment, including
 * matching stacks inside Shulker Boxes.
 *
 * <p>Loose items are taken from the delivery GUI snapshot first; Shulker
 * contents in the GUI snapshot are used next; Shulker boxes in the player's
 * inventory are used last. Nested shulkers (a shulker inside a shulker) are
 * not scanned.
 */
public final class DeliveryItemUtils {

    private DeliveryItemUtils() {}

    /** Returns {@code true} when {@code item} is any colour of Shulker Box. */
    public static boolean isShulkerBox(ItemStack item) {
        return item != null
                && item.getType() != org.bukkit.Material.AIR
                && Tag.SHULKER_BOXES.isTagged(item.getType());
    }

    /**
     * Returns {@code true} when {@code item} can be placed in the delivery GUI:
     * a matching loose stack or a shulker containing at least one matching stack.
     */
    public static boolean isDeliverablePlacement(ItemStack item, ItemStack template) {
        if (item == null || item.getType() == org.bukkit.Material.AIR || template == null) {
            return false;
        }
        if (item.isSimilar(template)) {
            return true;
        }
        return shulkerContainsDeliverable(item, template);
    }

    /** Returns {@code true} when the shulker contains at least one matching stack. */
    public static boolean shulkerContainsDeliverable(ItemStack shulker, ItemStack template) {
        return countInShulker(shulker, template) > 0;
    }

    /**
     * Counts matching items inside a shulker box. Returns {@code 0} when the
     * stack is not a shulker or has no readable inventory meta.
     */
    public static int countInShulker(ItemStack shulker, ItemStack template) {
        Inventory inventory = getShulkerInventory(shulker);
        if (inventory == null || template == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack inner : inventory.getContents()) {
            if (inner != null && inner.isSimilar(template)) {
                total += inner.getAmount();
            }
        }
        return total;
    }

    /**
     * Counts all deliverable items: loose stacks in the GUI snapshot, matching
     * contents of shulkers in the GUI snapshot, and matching contents of shulkers
     * in the player's inventory.
     */
    public static int countAvailable(Player player, ItemStack[] guiSlots, ItemStack template) {
        int total = 0;
        if (guiSlots != null) {
            for (ItemStack slot : guiSlots) {
                if (slot == null || slot.getType() == org.bukkit.Material.AIR) {
                    continue;
                }
                if (slot.isSimilar(template)) {
                    total += slot.getAmount();
                } else if (isShulkerBox(slot)) {
                    total += countInShulker(slot, template);
                }
            }
        }
        if (player != null) {
            total += countShulkersInInventory(player, template);
        }
        return total;
    }

    /**
     * Extracts exactly {@code needed} matching items into flat stacks for the
     * buyer stash. Mutates {@code guiSnapshot} and live shulkers in the player
     * inventory in priority order.
     *
     * @return flat item stacks totalling {@code needed} items (may be less if
     *         sources were depleted between count and extract)
     */
    public static ItemStack[] extract(Player player,
                                      ItemStack[] guiSnapshot,
                                      ItemStack template,
                                      int needed) {
        if (needed <= 0 || template == null) {
            return new ItemStack[0];
        }

        List<ItemStack> result = new ArrayList<>();
        int remaining = needed;

        if (guiSnapshot != null) {
            remaining = extractLooseFromSlots(guiSnapshot, template, remaining, result);
            if (remaining > 0) {
                remaining = extractFromShulkerSlots(guiSnapshot, template, remaining, result);
            }
        }

        if (remaining > 0 && player != null) {
            extractFromPlayerInventoryShulkers(player, template, remaining, result);
        }

        return result.toArray(new ItemStack[0]);
    }

    /**
     * Returns shulker boxes from the GUI snapshot to the player after a
     * successful delivery (partially emptied shulkers are preserved).
     */
    public static void returnSnapshotShulkers(Player player, ItemStack[] guiSnapshot) {
        if (player == null || guiSnapshot == null) {
            return;
        }
        for (ItemStack slot : guiSnapshot) {
            if (slot == null || !isShulkerBox(slot)) {
                continue;
            }
            var overflow = player.getInventory().addItem(slot.clone());
            overflow.values().forEach(drop ->
                    player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int countShulkersInInventory(Player player, ItemStack template) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isShulkerBox(item)) {
                total += countInShulker(item, template);
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (isShulkerBox(item)) {
                total += countInShulker(item, template);
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isShulkerBox(offhand)) {
            total += countInShulker(offhand, template);
        }
        return total;
    }

    private static int extractLooseFromSlots(ItemStack[] slots,
                                             ItemStack template,
                                             int remaining,
                                             List<ItemStack> result) {
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack slot = slots[i];
            if (slot == null || !slot.isSimilar(template)) {
                continue;
            }
            int take = Math.min(slot.getAmount(), remaining);
            addToResult(result, slot, take);
            remaining -= take;
            if (take >= slot.getAmount()) {
                slots[i] = null;
            } else {
                slot.setAmount(slot.getAmount() - take);
            }
        }
        return remaining;
    }

    private static int extractFromShulkerSlots(ItemStack[] slots,
                                               ItemStack template,
                                               int remaining,
                                               List<ItemStack> result) {
        for (ItemStack slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (!isShulkerBox(slot)) {
                continue;
            }
            remaining = extractFromShulker(slot, template, remaining, result);
        }
        return remaining;
    }

    private static void extractFromPlayerInventoryShulkers(Player player,
                                                           ItemStack template,
                                                           int remaining,
                                                           List<ItemStack> result) {
        remaining = extractFromInventoryArray(
                player.getInventory().getStorageContents(), template, remaining, result);
        if (remaining <= 0) {
            return;
        }
        remaining = extractFromInventoryArray(
                player.getInventory().getArmorContents(), template, remaining, result);
        if (remaining <= 0) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isShulkerBox(offhand) && remaining > 0) {
            extractFromShulker(offhand, template, remaining, result);
        }
    }

    private static int extractFromInventoryArray(ItemStack[] items,
                                                 ItemStack template,
                                                 int remaining,
                                                 List<ItemStack> result) {
        for (ItemStack item : items) {
            if (remaining <= 0) {
                break;
            }
            if (!isShulkerBox(item)) {
                continue;
            }
            remaining = extractFromShulker(item, template, remaining, result);
        }
        return remaining;
    }

    private static int extractFromShulker(ItemStack shulker,
                                          ItemStack template,
                                          int remaining,
                                          List<ItemStack> result) {
        ItemMeta meta = shulker.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return remaining;
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return remaining;
        }

        Inventory inventory = shulkerBox.getInventory();
        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack inner = inventory.getItem(i);
            if (inner == null || !inner.isSimilar(template)) {
                continue;
            }
            int take = Math.min(inner.getAmount(), remaining);
            addToResult(result, inner, take);
            remaining -= take;
            if (take >= inner.getAmount()) {
                inventory.setItem(i, null);
            } else {
                inner.setAmount(inner.getAmount() - take);
            }
        }

        blockStateMeta.setBlockState(shulkerBox);
        shulker.setItemMeta(blockStateMeta);
        return remaining;
    }

    private static void addToResult(List<ItemStack> result, ItemStack source, int amount) {
        for (ItemStack existing : result) {
            if (existing.isSimilar(source)
                    && existing.getAmount() + amount <= existing.getMaxStackSize()) {
                existing.setAmount(existing.getAmount() + amount);
                return;
            }
        }
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        result.add(copy);
    }

    private static Inventory getShulkerInventory(ItemStack shulker) {
        if (!isShulkerBox(shulker)) {
            return null;
        }
        ItemMeta meta = shulker.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return null;
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return null;
        }
        return shulkerBox.getInventory();
    }
}
