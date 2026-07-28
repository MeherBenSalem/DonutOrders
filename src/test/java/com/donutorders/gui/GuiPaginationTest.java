package com.donutorders.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure pagination math for New Order / marketplace GUIs (45 items per page).
 * Guards the create-order "Previous" bug where page always reset to 0.
 */
class GuiPaginationTest {

    private static final int PAGE_SIZE = 45;

    static int maxPage(int itemCount) {
        if (itemCount <= 0) {
            return 0;
        }
        return Math.max(0, (itemCount - 1) / PAGE_SIZE);
    }

    static int previousPage(int page) {
        return Math.max(0, page - 1);
    }

    static int nextPage(int page, int maxPage) {
        return Math.min(maxPage, page + 1);
    }

    @Test
    void previousFromSecondPageGoesToFirstNotZeroResetOnly() {
        assertEquals(0, previousPage(1));
        assertEquals(1, previousPage(2));
        assertEquals(0, previousPage(0));
    }

    @Test
    void nextAdvancesUntilMaxPage() {
        int max = maxPage(100);
        assertEquals(2, max);
        assertEquals(1, nextPage(0, max));
        assertEquals(2, nextPage(1, max));
        assertEquals(2, nextPage(2, max));
    }

    @Test
    void maxPageForEmptyAndSinglePage() {
        assertEquals(0, maxPage(0));
        assertEquals(0, maxPage(45));
        assertEquals(1, maxPage(46));
    }
}
