package io.nightbeam.donutorders.model;

import org.bukkit.inventory.ItemStack;

/** A stack waiting in storage: a delivered item for an order's owner, or an item in a player's mailbox. */
public record StoredStack(long id, ItemStack item) {
}
