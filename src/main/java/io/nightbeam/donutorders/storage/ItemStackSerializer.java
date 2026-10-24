package io.nightbeam.donutorders.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Items are stored as Paper's binary format ({@code nbt:} + Base64), which records the data version and
 * runs Mojang's data fixers when read, so listings survive Minecraft upgrades (for example the 1.20.5
 * item component change). Rows written by 1.x releases use Bukkit object streams and remain readable.
 */
public final class ItemStackSerializer {

    static final String BINARY_PREFIX = "nbt:";
    private static volatile java.util.function.Function<ItemStack, String> testWriter;
    private static volatile java.util.function.Function<String, ItemStack> testReader;

    private ItemStackSerializer() {
    }

    /** Unit tests run without a Minecraft server and swap in their own encoding. */
    static void useTestCodec(java.util.function.Function<ItemStack, String> writer, java.util.function.Function<String, ItemStack> reader) {
        testWriter = writer;
        testReader = reader;
    }

    public static String serialize(ItemStack itemStack) {
        if (testWriter != null) return testWriter.apply(itemStack);
        try {
            return BINARY_PREFIX + Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
        } catch (RuntimeException | LinkageError unsupported) {
            // Test servers and forks without the binary codec still have Bukkit serialization.
            return serializeLegacy(itemStack);
        }
    }

    public static ItemStack deserialize(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("No item data.");
        }
        if (testReader != null) return testReader.apply(encoded);
        if (encoded.startsWith(BINARY_PREFIX)) {
            try {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded.substring(BINARY_PREFIX.length())));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Unable to deserialize ItemStack: " + exception.getMessage(), exception);
            }
        }
        return deserializeLegacy(encoded);
    }

    static String serializeLegacy(ItemStack itemStack) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream objectOutputStream = new BukkitObjectOutputStream(outputStream)) {
            objectOutputStream.writeObject(itemStack);
            objectOutputStream.flush();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize ItemStack.", exception);
        }
    }

    static ItemStack deserializeLegacy(String encoded) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to deserialize ItemStack: not Base64.", exception);
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
             BukkitObjectInputStream objectInputStream = new BukkitObjectInputStream(inputStream)) {
            Object object = objectInputStream.readObject();
            if (object instanceof ItemStack itemStack) {
                return itemStack;
            }
            throw new IllegalStateException("Serialized value is not an ItemStack.");
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Unable to deserialize ItemStack.", exception);
        }
    }
}
