package io.nightbeam.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class MoneyAndParsingTest {

    @Test
    void roundsToCentsAndRejectsNonsense() {
        assertEquals(0.30D, Money.times(3, 0.1D));
        assertEquals(1234.57D, Money.round(1234.565D));
        assertEquals(0.0D, Money.round(Double.NaN));
        assertEquals(0.0D, Money.round(Double.POSITIVE_INFINITY));
        assertEquals(0.0D, Money.round(-5));
        assertEquals(0.0D, Money.minus(1, 2));
        assertEquals(0.7D, Money.minus(1.0D, 0.3D));
        assertEquals(6.4E9D, Money.times(64_000_000L, 100.0D));
        assertFalse(Money.positive(0.004D));
        assertTrue(Money.positive(0.01D));
    }

    @Test
    void readsPricesPlayersType() {
        assertEquals(2500.0D, PriceParser.parse("2.5k", true).getAsDouble());
        assertEquals(1_250_000.0D, PriceParser.parse("1.25m", true).getAsDouble());
        assertEquals(3.0E9D, PriceParser.parse("3b", true).getAsDouble());
        assertEquals(2500.0D, PriceParser.parse("2,500", true).getAsDouble());
        assertEquals(12.5D, PriceParser.parse("$12.50", true).getAsDouble());
        for (String bad : new String[] {"NaN", "Infinity", "-5", "1e9", "0x10", "", "abc", "10d", "1.2.3"}) {
            assertEquals(OptionalDouble.empty(), PriceParser.parse(bad, true), bad);
        }
    }

    @Test
    void readsDurations() {
        assertEquals(3_600_000L, Durations.parse("1h").orElseThrow());
        assertEquals(36L * 3_600_000L, Durations.parse("1d12h").orElseThrow());
        assertEquals(2L * 604_800_000L, Durations.parse("2w").orElseThrow());
        assertEquals(5_000L, Durations.parse("5s").orElseThrow());
        assertEquals(12L * 3_600_000L, Durations.parse("12").orElseThrow());
        assertTrue(Durations.parse("soon").isEmpty());
        assertTrue(Durations.parse("1x").isEmpty());
        assertTrue(Durations.parse("0h").isEmpty());
    }

    @Test
    void smallCapsLeaveTagsPlaceholdersAndCodesAlone() {
        assertEquals("<gold>ᴏʀᴅᴇʀꜱ</gold> %player% &aᴏᴋ &#ff00aaʜɪ", MessageUtil.smallCaps("<gold>Orders</gold> %player% &aOK &#ff00aaHi"));
        assertEquals("ᴘᴀɢᴇ 1/2", MessageUtil.smallCaps("Page 1/2"));
        assertEquals("50% ᴏꜰꜰ", MessageUtil.smallCaps("50% off"));
    }

    @Test
    void escapesPlayerText() {
        String template = "<gray>%player% wants %item%";
        String filled = MessageUtil.substitute(template, java.util.Map.of("player", "<red>Evil</red>&c", "item", MessageUtil.trusted("<lang:item.minecraft.diamond>")));
        assertTrue(filled.contains("\\<red>"));
        assertTrue(filled.contains("&&c"));
        assertTrue(filled.contains("<lang:item.minecraft.diamond>"));
    }
}
