package io.nightbeam.donutorders.model;

import java.util.UUID;

/** What a player is looking at on the board. */
public record BrowseRequest(int page, OrderSort sort, OrderCategory category, String search, UUID owner, String ownerName,
                            boolean deliverableOnly) {

    public static BrowseRequest initial(OrderSort sort) {
        return new BrowseRequest(1, sort, OrderCategory.ALL, "", null, null, false);
    }

    public BrowseRequest withPage(int page) {
        return new BrowseRequest(Math.max(1, page), sort, category, search, owner, ownerName, deliverableOnly);
    }

    public BrowseRequest withSort(OrderSort sort) {
        return new BrowseRequest(1, sort, category, search, owner, ownerName, deliverableOnly);
    }

    public BrowseRequest withCategory(OrderCategory category) {
        return new BrowseRequest(1, sort, category, search, owner, ownerName, deliverableOnly);
    }

    public BrowseRequest withSearch(String search) {
        return new BrowseRequest(1, sort, category, search == null ? "" : search.trim(), owner, ownerName, deliverableOnly);
    }

    public BrowseRequest withOwner(UUID owner, String ownerName) {
        return new BrowseRequest(1, sort, category, search, owner, ownerName, deliverableOnly);
    }

    public BrowseRequest withDeliverableOnly(boolean deliverableOnly) {
        return new BrowseRequest(1, sort, category, search, owner, ownerName, deliverableOnly);
    }

    public boolean filtered() {
        return !search.isBlank() || owner != null || category != OrderCategory.ALL || deliverableOnly;
    }
}
