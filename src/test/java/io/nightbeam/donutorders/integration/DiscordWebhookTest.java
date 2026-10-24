package io.nightbeam.donutorders.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordWebhookTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void embedsAreValidJsonWithoutMentions() {
        long now = System.currentTimeMillis();
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "Ki\"ri", false, new ItemStack(Material.DIAMOND), "DIAMOND", "DIAMOND",
                MatchMode.TYPE, 64, 10, 0, 100.0D, 5400.0D, now, now + 60_000L, OrderStatus.ACTIVE, 0L, now, 0L);
        String json = DiscordWebhook.json(new DiscordWebhook.Post(DiscordWebhook.Kind.DELIVERED, order, 10, "$100.00", "$1,000.00", 1000.0D,
                "@everyone", UUID.randomUUID(), null), new YamlConfiguration());
        assertTrue(json.contains("\"allowed_mentions\":{\"parse\":[]}"));
        assertTrue(json.contains("Ki\\\"ri"));
        assertTrue(json.contains("\"Delivered\""));
        assertFalse(json.contains("\n"));
    }

    @Test
    void onlyConfiguredEventsAboveTheMinimumArePosted() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("discord.enabled", true);
        config.set("discord.webhook-url", "https://discord.com/api/webhooks/1/x");
        config.set("discord.events", java.util.List.of("FILLED", "SUSPICIOUS"));
        config.set("discord.min-value", 500);
        DiscordWebhook webhook = new DiscordWebhook(() -> config, java.util.logging.Logger.getLogger("test"));
        assertTrue(webhook.wants(DiscordWebhook.Kind.FILLED, 600));
        assertFalse(webhook.wants(DiscordWebhook.Kind.FILLED, 100));
        assertTrue(webhook.wants(DiscordWebhook.Kind.SUSPICIOUS, 0));
        assertFalse(webhook.wants(DiscordWebhook.Kind.CREATED, 10_000));
        webhook.shutdown();
    }
}
