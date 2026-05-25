# Changelog

All notable changes to Stats4Us will be documented in this file.

## [1.0.0] - 2026-05-25

### Added

- Server-side Fabric mod for Minecraft `26.1.2`.
- Vanilla statistics reading from the world `stats/` directory.
- Support for online and offline players, including known offline names from `usercache.json`.
- Web dashboard with:
  - Players tab with searchable player list and drill-down player details.
  - Leaderboards tab grouped by statistic category.
  - Graphs & Charts tab with player comparison modes, category totals, playtime growth, online-player trend, and player activity timeline.
  - All Stats tab with zero-stat hiding.
- Configurable web dashboard port and bind address.
- Configurable statistic display filters, including enabled stat types, explicit enabled stats, hidden stats, featured stats, and zero-value display.
- Historical sampling for activity graphs and timelines.
- Operator-only `/stats4us` commands for:
  - Reloading config.
  - Viewing dashboard URL.
  - Searching available stats.
  - Viewing online/offline player stats.
  - Getting, setting, and adding stat values.
- Offline-player support for stat editing commands.
- Audit logging for stat modification commands.
- Public dashboard address detection with local fallback warning.
- MIT license.

### Changed

- Package namespace uses `com.deanxbox.stats4us`.
- Dashboard uses a professional dark Minecraft-inspired theme.
- Server tick work is throttled so history checks run once per second instead of every server tick.
- Offline stats files are cached by last-modified timestamp to reduce repeated disk parsing.

### Notes

- Tested on a Minecraft server and confirmed to work well.
- Historical charts begin collecting detailed timeline data only after Stats4Us has been installed and running.
- If the dashboard port is exposed publicly, server owners should place it behind trusted network rules or external authentication.
