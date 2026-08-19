package com.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class OrderBroadcastTest {

    @Test
    void shouldNotifySkipsTheBuyer() {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertFalse(OrderBroadcast.shouldNotify(buyer, buyer));
        assertTrue(OrderBroadcast.shouldNotify(other, buyer));
    }

    @Test
    void shouldNotifyRejectsNullIds() {
        UUID id = UUID.randomUUID();
        assertFalse(OrderBroadcast.shouldNotify(null, id));
        assertFalse(OrderBroadcast.shouldNotify(id, null));
        assertFalse(OrderBroadcast.shouldNotify(null, null));
    }

    @Test
    void defaultTemplateFillsPlaceholders() {
        String out = MessageHelper.format(
                OrderBroadcast.DEFAULT_MESSAGE, "Steve", "Diamond", 64, "10.00");
        assertEquals("&eSteve &7created a buy order: &fDiamond &7× 64 &7@ &f10.00 &7each", out);
    }
}
