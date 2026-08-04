# DonutOrders v1.5.1

### New Features
* None

### Improvements
* Folia async scheduling hardened — `runAsync` always uses Paper's `AsyncScheduler` on regionalised servers; legacy `BukkitScheduler` async calls are never used on Folia
* Startup load falls back to synchronous DB read if async scheduling is unavailable, then continues enable on the global thread
* Scheduled task failures are logged instead of crashing the server thread

### Bug Fixes
* Fixed **Folia enable crash** (`UnsupportedOperationException` from `CraftScheduler.runTaskAsynchronously` during `StorageManager.loadAll`) on Folia 1.21.x
* All scheduler entry points (`runAtEntity`, `runGlobal`, `runGlobalRepeating`, `runAsync`, etc.) now catch `UnsupportedOperationException` and route to the correct Paper/Folia scheduler as a safety net

### Configuration
* None

### Compatibility
* Drop-in update from 1.5.0 (and older 1.3.x / 1.4.x builds still on broken Folia async scheduling)
* `folia-supported: true` unchanged in `plugin.yml`
* No database migration required
* Tested build target: Paper/Folia 1.20.6+ API; runtime tested on JDK 21

### Upgrade Notes
1. Replace the jar with `DonutOrders-1.5.1.jar`
2. Restart the server (recommended over hot-reload for scheduler changes)
3. Confirm console shows `Folia detected — using regionised schedulers.` followed by `Enabled successfully` with no `UnsupportedOperationException`
4. Run `/orders` on a Folia 1.21.4 server to verify the marketplace opens
