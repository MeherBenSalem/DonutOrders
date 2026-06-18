# DonutOrders v1.2.1

### Bug Fixes

* Fixed plugin failing to enable on Folia and Folia-based servers (e.g. Canvas) with `UnsupportedOperationException` during async database load.
* Async database I/O and network tasks now use Paper's `AsyncScheduler` on Folia instead of the deprecated Bukkit async scheduler.

### Compatibility

* No config or database migration required. Drop-in replacement for v1.2.0.
