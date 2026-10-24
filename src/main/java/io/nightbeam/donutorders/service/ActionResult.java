package io.nightbeam.donutorders.service;

/** The outcome of a player action: whether it worked and the (already formatted) message to show. */
public record ActionResult(boolean success, String message) {

    public static ActionResult success(String message) {
        return new ActionResult(true, message);
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, message);
    }
}
