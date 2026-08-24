package com.donutorders.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInputParserTest {

    @Test
    void parseAmountAcceptsPlainIntegers() {
        assertEquals(64, ChatInputParser.parseAmount("64").orElse(-1));
        assertEquals(1, ChatInputParser.parseAmount("1").orElse(-1));
    }

    @Test
    void parseAmountAcceptsWholeDecimalsAndCommas() {
        assertEquals(64, ChatInputParser.parseAmount("64.0").orElse(-1));
        assertEquals(1000, ChatInputParser.parseAmount("1,000").orElse(-1));
        assertEquals(10, ChatInputParser.parseAmount(" 10 ").orElse(-1));
    }

    @Test
    void parseAmountRejectsZeroFractionAndNegatives() {
        assertTrue(ChatInputParser.parseAmount("1.5").isEmpty());
        assertTrue(ChatInputParser.parseAmount("0").isEmpty());
        assertTrue(ChatInputParser.parseAmount("-3").isEmpty());
        assertTrue(ChatInputParser.parseAmount("").isEmpty());
        assertTrue(ChatInputParser.parseAmount("abc").isEmpty());
    }

    @Test
    void parsePriceAcceptsDecimals() {
        assertEquals(10.5, ChatInputParser.parsePrice("10.5").orElse(-1), 0.0001);
        assertEquals(2.0, ChatInputParser.parsePrice("2").orElse(-1), 0.0001);
    }

    @Test
    void cancelMatchesKeywordIgnoringColorAndCase() {
        assertTrue(ChatInputParser.isCancel("cancel", "cancel"));
        assertTrue(ChatInputParser.isCancel("CANCEL", "cancel"));
        assertTrue(ChatInputParser.isCancel("&ccancel", "cancel"));
        assertTrue(ChatInputParser.isCancel("cancel", "&cCancel"));
        assertFalse(ChatInputParser.isCancel("64", "cancel"));
        assertFalse(ChatInputParser.isCancel("cancelled", "cancel"));
    }
}
