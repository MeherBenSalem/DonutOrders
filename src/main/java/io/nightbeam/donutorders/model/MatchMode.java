package io.nightbeam.donutorders.model;

/** How delivered items are compared with the item an order asks for. */
public enum MatchMode {
    /** The material, with configurable tolerance for renamed, damaged, enchanted and custom items. */
    TYPE,
    /** An enchanted book with a given enchantment and level. */
    BOOK,
    /** The exact item (potions, goat horns, custom items): everything but the stack size must match. */
    EXACT;

    public static MatchMode parse(String value) {
        if (value == null) return TYPE;
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return TYPE;
        }
    }
}
