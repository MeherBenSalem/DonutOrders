# DonutOrders 1.0.2 - Patch Notes

## 🛡️ Critical Security Patch
- **Item Duplication Exploit Fixed**: Completely resolved a critical exploit where delayed or replayed `ClickSlot` inventory packets could trigger order collection multiple times before the server-side database state could update.
- **WaterTight Concurrency Protections**:
  - Implemented an atomic, in-memory compare-and-set claim lock (`tryLockClaim` / `unlockClaim`) to guarantee that concurrent stash claims from replayed/spam packets fail immediately.
  - Added a state-based collection lifecycle: orders now transition to `PENDING` upon completion/cancellation/expiration and to `CLAIMED` immediately when collection starts in-memory, ensuring absolute claim idempotency.
  - Implemented automatic SQLite database schema migration (`claimed_by` and `claimed_at` columns) to persist claiming information and protect against exploits across server restarts or hot reloads.
  - Added tick-based packet rate-limiting at the listener level to reject rapid click spam (max 2 clicks per tick) and click replays targeting the exact same slot in a single tick.

## ⚙️ Improvements & Lifecycle Hardening
- **Safe Expiry Flow**: Redesigned the order expiration checker to safely transition orders to `PENDING` (pending collection) rather than performing immediate, unsafe synchronous refunds. Players can now safely retrieve their expired order refunds from their personal stash GUI at any time.
- **Thread-Safety & Folia Compatibility**: Guaranteed thread-safe SQLite operations by usingHikariCP WAL-mode queries dispatched asynchronously and synchronizing on player entity threads for Vault deposits and item insertions.

## 🧪 Admin & Testing Command
- **Concurrency Simulator**: Registered the `/orders admin simulate` command (requires `donutorders.admin` permission). This executes a live high-latency packet replay and concurrent race simulation in SQLite, spawning 10 concurrent threads racing to collect the exact same stash at the exact same millisecond. Reports back in chat showing exactly 1 successful claim and 9 rejections.

---

✅ This update guarantees a bulletproof, exploit-immune server-side authoritative inventory system for Paper and Folia.
