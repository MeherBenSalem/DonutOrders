# Contributing

Thanks for helping improve DonutOrders.

## How to contribute

1. Fork the repository and create a branch from `main`.
2. Prefer small, focused changes with a clear purpose.
3. Match the existing Java style and package layout under `io.nightbeam.donutorders`.
4. Add or update unit tests under `src/test/java` when practical.
5. Open a pull request describing what changed and why.

## Development

- JDK 17+ to build. The plugin compiles against the Paper 1.20.1 API so one jar runs on 1.20.1 through 26.3:
  reach API that changed between versions through `Keyed`, reflection or names (see `util/Enchants.java`).
- Build: `mvn -B package`. Tests: `mvn -B test` (add `-Dorders.test.mysql.url=jdbc:mysql://localhost:3306/<db>` for
  the order book on MySQL or MariaDB).
- Real servers: `tools/smoke` runs the cross-version matrix and the two-server network test (see its README).
- Texts: write them as `messages().format("key", "default", ...)` in the code, then run
  `python3 tools/messages/extract.py` to update `messages.yml` (CI checks it is in sync).
- Design notes: [docs/DESIGN-2.0.md](docs/DESIGN-2.0.md).

## Licensing

By contributing, you agree that your contributions are licensed under the
Apache License, Version 2.0, unless you state otherwise in the pull request.
