package io.nightbeam.donutorders.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

public final class ShulkerBoxSupport {

    private ShulkerBoxSupport() {
    }

    public static boolean isShulkerBox(Material material) {
        return material.name().contains("SHULKER_BOX");
    }

    public static boolean isShulkerBox(ItemStack item) {
        return item != null && isShulkerBox(item.getType());
    }

    public static List<ItemStack> getContents(ItemStack shulkerItem) {
        if (!isShulkerBox(shulkerItem)) {
            return List.of();
        }

        ItemMeta meta = shulkerItem.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return List.of();
        }

        BlockState blockState = blockStateMeta.getBlockState();
        if (!(blockState instanceof ShulkerBox shulkerBox)) {
            return List.of();
        }

        ItemStack[] contents = shulkerBox.getInventory().getContents();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack content : contents) {
            if (content != null && content.getType() != Material.AIR) {
                items.add(content.clone());
            }
        }
        return items;
    }

    public static int getItemCount(ItemStack shulkerItem) {
        if (!isShulkerBox(shulkerItem)) {
            return 0;
        }

        ItemMeta meta = shulkerItem.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return 0;
        }

        BlockState blockState = blockStateMeta.getBlockState();
        if (!(blockState instanceof ShulkerBox shulkerBox)) {
            return 0;
        }

        int count = 0;
        for (ItemStack content : shulkerBox.getInventory().getContents()) {
            if (content != null && content.getType() != Material.AIR) {
                count++;
            }
        }
        return count;
    }
}
