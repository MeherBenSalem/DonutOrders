# DonutOrders 1.0.1 - Patch Notes

## 🐛 Bug Fixes
- Fixed a critical issue where items could duplicate when placing them in order slots
- Resolved a bug causing items to return to the sender while still being delivered to the receiver
- Improved inventory handling to prevent unintended item cloning
- Fixed items being silently lost when clicking Cancel in the delivery confirmation screen
- Fixed items being lost when pressing ESC to close the delivery confirmation screen

## ⚙️ Improvements
- Enhanced transaction safety for order processing
- Improved GUI handling during order confirmation and closing
- Added internal safeguards against double execution

## 🧪 Internal Changes
- Refactored item transfer logic to ensure atomic operations
- Added `confirmed` flag to `DeliverItemsGUI` — delivery GUI slots are now cleared before transitioning to the confirmation screen, eliminating the race between the close-event item return and the stash transfer
- Added `submitted` flag to `ConfirmDeliveryGUI` — guards `returnItems()` from running after `fulfillOrder` has already been dispatched
- `InventoryCloseEvent` now handles `ConfirmDeliveryGUI` closure (ESC key) and correctly returns the item snapshot to the player if the delivery was not confirmed
- Added validation checks to prevent duplication edge cases

---

✅ This update ensures a stable and duplication-free ordering system.
