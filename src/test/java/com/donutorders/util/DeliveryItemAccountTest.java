package com.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for GitHub issue #5: putting items in the delivery box,
 * proceeding to confirm, then cancelling doubled the items because Cancel and
 * InventoryCloseEvent both cloned the same snapshot.
 */
class DeliveryItemAccountTest {

    @Test
    void issue5LegacyCancelThenCloseDuplicatesSnapshot() {
        ItemStack[] snapshot = { stack(Material.DIAMOND, 16) };

        int afterCancel = count(legacyReturnAlways(snapshot));
        int afterClose = count(legacyReturnAlways(snapshot));

        assertEquals(16, afterCancel);
        assertEquals(16, afterClose);
        assertEquals(32, afterCancel + afterClose, "pre-fix cancel+close duplicated the box");
    }

    @Test
    void issue5CancelThenCloseReturnsItemsOnce() {
        ItemStack[] snapshot = { stack(Material.DIAMOND, 16) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        int afterCancel = count(account.takeForReturn());
        int afterClose = count(account.takeForReturn());

        assertEquals(16, afterCancel);
        assertEquals(0, afterClose);
        assertEquals(DeliveryItemAccount.State.RETURNED, account.state());
        assertEquals(0, ItemUtils.countItems(account.snapshot()));
    }

    @Test
    void escCloseAloneReturnsItemsOnce() {
        ItemStack[] snapshot = { stack(Material.IRON_INGOT, 32), stack(Material.IRON_INGOT, 8) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        assertEquals(40, count(account.takeForReturn()));
        assertEquals(0, count(account.takeForReturn()));
        assertEquals(0, count(account.takeForReturn()));
    }

    @Test
    void doubleCancelDoesNotDuplicate() {
        ItemStack[] snapshot = { stack(Material.DIAMOND, 4) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        assertEquals(4, count(account.takeForReturn()));
        assertEquals(0, count(account.takeForReturn()));
        assertFalse(account.trySubmit());
    }

    @Test
    void submitThenCloseOrCancelDoesNotReturn() {
        ItemStack[] snapshot = { stack(Material.DIAMOND, 16) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        assertTrue(account.trySubmit());
        assertEquals(DeliveryItemAccount.State.SUBMITTED, account.state());
        assertEquals(0, count(account.takeForReturn()));
        assertEquals(16, ItemUtils.countItems(account.snapshot()));
        assertFalse(account.trySubmit());
    }

    @Test
    void cancelThenSubmitIsRejected() {
        ItemStack[] snapshot = { stack(Material.DIAMOND, 16) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        assertEquals(16, count(account.takeForReturn()));
        assertFalse(account.trySubmit());
    }

    @Test
    void quitThenCloseDoesNotDuplicate() {
        ItemStack[] snapshot = { stack(Material.EMERALD, 7) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);

        int onQuit = count(account.takeForReturn());
        int onClose = count(account.takeForReturn());
        int onDisable = count(account.takeForReturn());

        assertEquals(7, onQuit);
        assertEquals(0, onClose);
        assertEquals(0, onDisable);
    }

    @Test
    void deathDropsReceiveItemsOnce() {
        ItemStack[] snapshot = { stack(Material.GOLD_INGOT, 12) };
        DeliveryItemAccount account = new DeliveryItemAccount(snapshot);
        List<ItemStack> drops = new ArrayList<>();

        drops.addAll(account.takeForReturn());
        drops.addAll(account.takeForReturn());

        assertEquals(12, count(drops));
    }

    @Test
    void deliverItemsLiveSlotsCancelThenCloseDoesNotDuplicate() {
        ItemStack[] slots = { stack(Material.DIAMOND, 8), null, stack(Material.DIAMOND, 2) };

        int first = takeLiveSlots(slots);
        int second = takeLiveSlots(slots);

        assertEquals(10, first);
        assertEquals(0, second);
    }

    /**
     * Old ConfirmDeliveryGUI.returnItems: clone the snapshot without consuming it.
     */
    private static List<ItemStack> legacyReturnAlways(ItemStack[] snapshot) {
        List<ItemStack> given = new ArrayList<>();
        for (ItemStack item : snapshot) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            given.add(item.clone());
        }
        return given;
    }

    /**
     * DeliverItemsGUI live-slot return: give then null the slot.
     */
    private static int takeLiveSlots(ItemStack[] slots) {
        int total = 0;
        for (int i = 0; i < slots.length; i++) {
            ItemStack item = slots[i];
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            total += item.getAmount();
            slots[i] = null;
        }
        return total;
    }

    private static ItemStack stack(Material material, int amount) {
        return new ItemStack(material, amount);
    }

    private static int count(Iterable<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                total += item.getAmount();
            }
        }
        return total;
    }
}
