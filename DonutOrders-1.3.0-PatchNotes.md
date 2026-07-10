# DonutOrders v1.3.0

### New Features

* Full translation support for the entire player-facing interface via `messages.yml`.
* GUI titles, buttons, item names, lore, status labels, pagination text, chat prompts, success/error messages, and order expiry formatting are all configurable without recompiling.
* New `MessageHelper` system with color codes (`&` and `#RRGGBB`), positional placeholders (`{0}`, `{1}`, …), and named placeholders (`{item}`, `{buyer}`, `{price}`, etc.).
* Translable chat cancel keyword via `chat-cancel-keyword` (default: `cancel`).

### Improvements

* Missing translation keys safely fall back to English defaults shipped in the jar.
* `/orders admin reload` reloads `messages.yml` (and config) so translations update without a full restart.
* Order cancel refund message now shows the correct refund amount.

### Configuration

* Expanded `plugins/DonutOrders/messages.yml` with organized sections: general, chat, orders, delivery, collect, status, time, buttons, and all GUI screens.
* Existing servers keep their current `messages.yml`; new keys fall back to jar defaults until added or the file is regenerated.

### Compatibility

* No database migration required.
* Drop-in jar upgrade from v1.2.x.
* Open GUIs keep old text until reopened after a reload; run `/orders admin reload` after editing translations.
