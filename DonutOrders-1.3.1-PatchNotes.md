# DonutOrders v1.3.1

### New Features
* None

### Improvements
* New Order item picker Previous/Next now share the same Folia-safe open path

### Bug Fixes
* Fixed **Previous** in the New Order item picker always returning to page 1 instead of the previous page

### Configuration
* None

### Compatibility
* Drop-in update from 1.3.0
* Folia-supported unchanged
* No database migration required
* No command or permission changes

### Upgrade Notes
1. Replace the jar with `DonutOrders-1.3.1.jar`
2. Restart the server
3. Open `/orders` → New Order → go to page 2+ → press Previous and confirm it returns one page back
