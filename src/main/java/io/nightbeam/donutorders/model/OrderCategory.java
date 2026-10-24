package io.nightbeam.donutorders.model;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;

/**
 * Board and item-picker categories. Materials are compared by name, so the list works on every version (names
 * that do not exist yet on an older server simply never match).
 */
public enum OrderCategory {
    ALL("COMPASS"),
    BLOCKS("GRASS_BLOCK"),
    TOOLS("DIAMOND_PICKAXE"),
    FOOD("GOLDEN_CARROT"),
    COMBAT("DIAMOND_SWORD"),
    POTIONS("POTION"),
    BOOKS("ENCHANTED_BOOK"),
    INGREDIENTS("BLAZE_POWDER"),
    UTILITIES("ENDER_CHEST"),
    SPAWNERS("SPAWNER");

    private static final Set<String> TOOL_NAMES = Set.of("SHEARS", "FLINT_AND_STEEL", "FISHING_ROD", "BRUSH", "SPYGLASS",
            "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "BUCKET", "WATER_BUCKET", "LAVA_BUCKET");
    private static final Set<String> COMBAT_NAMES = Set.of("BOW", "CROSSBOW", "TRIDENT", "SHIELD", "MACE", "ARROW",
            "SPECTRAL_ARROW", "TOTEM_OF_UNDYING", "WIND_CHARGE", "END_CRYSTAL");
    private static final Set<String> POTION_NAMES = Set.of("POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW",
            "OMINOUS_BOTTLE", "EXPERIENCE_BOTTLE");
    private static final Set<String> BOOK_NAMES = Set.of("BOOK", "WRITABLE_BOOK", "WRITTEN_BOOK", "ENCHANTED_BOOK", "KNOWLEDGE_BOOK");
    private static final Set<String> INGREDIENT_NAMES = Set.of("BLAZE_POWDER", "BLAZE_ROD", "BREEZE_ROD", "GUNPOWDER", "STRING",
            "SPIDER_EYE", "FERMENTED_SPIDER_EYE", "GLISTERING_MELON_SLICE", "GHAST_TEAR", "MAGMA_CREAM", "RABBIT_FOOT",
            "PHANTOM_MEMBRANE", "SUGAR", "REDSTONE", "GLOWSTONE_DUST", "NETHER_WART", "DRAGON_BREATH", "TURTLE_SCUTE",
            "SCUTE", "ARMADILLO_SCUTE", "SLIME_BALL", "BONE", "BONE_MEAL", "LEATHER", "FEATHER", "INK_SAC", "GLOW_INK_SAC",
            "ENDER_PEARL", "ENDER_EYE", "SHULKER_SHELL", "NETHER_STAR", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
            "NAUTILUS_SHELL", "HEART_OF_THE_SEA", "ECHO_SHARD", "DIAMOND", "EMERALD", "IRON_INGOT", "GOLD_INGOT",
            "COPPER_INGOT", "NETHERITE_INGOT", "NETHERITE_SCRAP", "COAL", "CHARCOAL", "LAPIS_LAZULI", "QUARTZ",
            "AMETHYST_SHARD", "RAW_IRON", "RAW_GOLD", "RAW_COPPER", "IRON_NUGGET", "GOLD_NUGGET", "CLAY_BALL", "BRICK",
            "NETHER_BRICK", "PAPER", "STICK", "FLINT", "RESIN_CLUMP", "HONEYCOMB", "HEAVY_CORE");
    private static final Set<String> UTILITY_NAMES = Set.of("ENDER_CHEST", "CHEST", "TRAPPED_CHEST", "BARREL", "ELYTRA", "LEAD",
            "NAME_TAG", "COMPASS", "RECOVERY_COMPASS", "CLOCK", "MAP", "FILLED_MAP", "SADDLE", "FIREWORK_ROCKET",
            "BEACON", "CONDUIT", "ANVIL", "ENCHANTING_TABLE", "BREWING_STAND", "HOPPER", "BUNDLE", "TRIAL_KEY",
            "OMINOUS_TRIAL_KEY", "GOAT_HORN", "BELL", "LODESTONE", "RESPAWN_ANCHOR");

    private final String icon;

    OrderCategory(String icon) {
        this.icon = icon;
    }

    public Material icon() {
        Material material = Material.matchMaterial(icon);
        return material == null ? Material.CHEST : material;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public OrderCategory next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public OrderCategory previous() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }

    public boolean matches(Material material) {
        String name = material.name();
        return switch (this) {
            case ALL -> true;
            case SPAWNERS -> name.equals("SPAWNER") || name.equals("TRIAL_SPAWNER") || name.endsWith("_SPAWN_EGG");
            case POTIONS -> POTION_NAMES.contains(name);
            case BOOKS -> BOOK_NAMES.contains(name);
            case TOOLS -> name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                    || (name.endsWith("_AXE") && !name.endsWith("_PICKAXE")) || TOOL_NAMES.contains(name);
            case COMBAT -> name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.endsWith("_SPEAR") || COMBAT_NAMES.contains(name);
            case FOOD -> material.isEdible() && !name.equals("SPIDER_EYE") && !name.equals("ROTTEN_FLESH");
            case INGREDIENTS -> INGREDIENT_NAMES.contains(name) || name.endsWith("_DYE");
            case UTILITIES -> UTILITY_NAMES.contains(name) || name.endsWith("SHULKER_BOX") || name.endsWith("_BED");
            case BLOCKS -> material.isBlock() && !UTILITY_NAMES.contains(name) && !name.endsWith("SHULKER_BOX")
                    && !name.equals("SPAWNER") && !name.equals("TRIAL_SPAWNER");
        };
    }

    public static Optional<OrderCategory> parse(String input) {
        if (input == null) return Optional.empty();
        String normalized = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (OrderCategory category : values()) if (category.name().equals(normalized)) return Optional.of(category);
        return Optional.empty();
    }
}
