package com.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModrinthUpdateCheckerTest {

    @Test
    void compareVersionsOrdersSemver() {
        assertTrue(ModrinthUpdateChecker.compareVersions("1.8.0", "1.7.0") > 0);
        assertTrue(ModrinthUpdateChecker.compareVersions("1.7.0", "1.8.0") < 0);
        assertEquals(0, ModrinthUpdateChecker.compareVersions("1.8.0", "1.8.0"));
    }

    @Test
    void compareVersionsStripsSuffixes() {
        assertEquals(0, ModrinthUpdateChecker.compareVersions("1.8.0+paper-1.21.1", "1.8.0"));
        assertTrue(ModrinthUpdateChecker.compareVersions("1.8.0+paper", "1.7.9") > 0);
        assertEquals(0, ModrinthUpdateChecker.compareVersions("1.8.0-SNAPSHOT", "1.8.0"));
    }

    @Test
    void normalizeVersionRemovesBuildMetadata() {
        assertEquals("1.8.0", ModrinthUpdateChecker.normalizeVersion("1.8.0+paper-1.21.1"));
        assertEquals("1.7.0", ModrinthUpdateChecker.normalizeVersion("1.7.0-beta"));
    }

    @Test
    void pickNewestVersionNumberUsesDatePublished() {
        String json = """
                [
                  {"version_number":"1.6.0","date_published":"2026-08-07T00:00:00Z"},
                  {"version_number":"1.8.0","date_published":"2026-08-21T12:00:00Z"},
                  {"version_number":"1.7.0","date_published":"2026-08-12T00:00:00Z"}
                ]
                """;
        assertEquals("1.8.0", ModrinthUpdateChecker.pickNewestVersionNumber(json));
    }

    @Test
    void pickNewestVersionNumberReturnsNullWhenEmpty() {
        assertNull(ModrinthUpdateChecker.pickNewestVersionNumber("[]"));
        assertNull(ModrinthUpdateChecker.pickNewestVersionNumber(""));
    }
}
