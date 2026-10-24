package io.nightbeam.donutorders.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Whole-stack hand-overs: a stack is given only when all of it fits, otherwise it stays where it is. */
public final class InventorySpace {

    private InventorySpace() {
    }

    public static boolean fits(PlayerInventory inventory, ItemStack item) {
        return plan(inventory.getStorageContents(), List.of(item)).size() == 1;
    }

    /**
     * Which of the stacks (by index) fit into the storage slots together, trying them in order. Stacks merge
     * into similar partial stacks first, like the game does.
     */
    public static List<Integer> plan(ItemStack[] storage, List<ItemStack> stacks) {
        ItemStack[] slots = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) slots[i] = storage[i] == null || storage[i].getType().isAir() ? null : storage[i].clone();
        List<Integer> fitting = new ArrayList<>();
        for (int index = 0; index < stacks.size(); index++) {
            ItemStack stack = stacks.get(index);
            if (stack == null || stack.getType().isAir()) continue;
            ItemStack[] trial = new ItemStack[slots.length];
            for (int i = 0; i < slots.length; i++) trial[i] = slots[i] == null ? null : slots[i].clone();
            if (add(trial, stack)) {
                slots = trial;
                fitting.add(index);
            }
        }
        return fitting;
    }

    private static boolean add(ItemStack[] slots, ItemStack stack) {
        int left = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        for (ItemStack slot : slots) {
            if (left <= 0) break;
            if (slot != null && slot.isSimilar(stack) && slot.getAmount() < max) {
                int move = Math.min(left, max - slot.getAmount());
                slot.setAmount(slot.getAmount() + move);
                left -= move;
            }
        }
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (slots[i] != null) continue;
            ItemStack placed = stack.clone();
            placed.setAmount(Math.min(left, max));
            slots[i] = placed;
            left -= placed.getAmount();
        }
        return left <= 0;
    }
}
