package com.donutorders.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a delivery snapshot so it is either returned to the player or submitted
 * for fulfillment — never both, and never more than once.
 *
 * <p>ConfirmDeliveryGUI previously cloned the snapshot on Cancel and again when
 * {@code InventoryCloseEvent} fired for the same screen (issue #5). This guard
 * is the single settlement for cancel, ESC-close, quit, death, and disable.
 */
public final class DeliveryItemAccount {

    public enum State {
        /** Snapshot still held by the confirm GUI. */
        HELD,
        /** Handed to {@code OrderManager.fulfillOrder}. */
        SUBMITTED,
        /** Given back to the player (or death drops). */
        RETURNED
    }

    private final ItemStack[] snapshot;
    private final AtomicReference<State> state = new AtomicReference<>(State.HELD);

    public DeliveryItemAccount(ItemStack[] snapshot) {
        this.snapshot = snapshot != null ? snapshot : new ItemStack[0];
    }

    /** Live snapshot array passed into fulfillment. Not a copy. */
    public ItemStack[] snapshot() {
        return snapshot;
    }

    public State state() {
        return state.get();
    }

    /**
     * Marks the snapshot as submitted for fulfillment.
     *
     * @return {@code true} if this call won settlement; {@code false} if the
     *         items were already returned or submitted (double-click confirm)
     */
    public boolean trySubmit() {
        return state.compareAndSet(State.HELD, State.SUBMITTED);
    }

    /**
     * Atomically takes the snapshot for returning to the player.
     * Clears the backing array so a raw loop over {@link #snapshot()} cannot
     * duplicate even if a caller ignores the empty list.
     *
     * @return clones to give the player; empty when already settled
     */
    public List<ItemStack> takeForReturn() {
        if (!state.compareAndSet(State.HELD, State.RETURNED)) {
            return List.of();
        }
        return copyAndClear();
    }

    private List<ItemStack> copyAndClear() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            snapshot[i] = null;
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            out.add(item.clone());
        }
        return List.copyOf(out);
    }
}
