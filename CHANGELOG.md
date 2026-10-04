# Changelog

All notable changes to Stats4Us will be documented in this file.

## [1.3.0] - 2026-10-04

### Fixed

- Players who have not joined since the server started (or were never whitelisted) now show their username instead of their UUID. Names are resolved from the user cache, whitelist, ops and ban lists, then looked up from Mojang in the background and remembered in `config/stats4us-names.json`. Commands and suggestions use the same names.

### Changed

- Rebuilt the Leaderboards tab: it now shows one leaderboard at a time. Search for a statistic or category (for example "blocks mined"), pick the specific entry (for example a block), then see every player ranked.
- Leaderboards list all players, 10 per page, with Previous/Next and page numbers. Players with equal values share a rank, and a player search jumps to and highlights that player.
- Added a breadcrumb trail to move back through category and statistic choices.
- Added 26.3 Support
- Releases are now published to Modrinth automatically with the changelog for each version.

## [1.1.0] - 2026-07-14

### Changed

- Updated to Minecraft `26.2`, Fabric Loader `0.19.3`, Fabric API `0.154.2+26.2`, and Fabric Loom `1.17`.
- Reduced dashboard work by rendering only the active tab.
- Added bookmarkable hash routes, player deep links, browser history, and keyboard tab navigation to the dashboard.
- Compressed dashboard responses when supported by the browser.
- Simplified the dashboard layout, controls, focus states, and mobile activity timeline.
- Dashboard addresses now use a reachable local address without blocking startup on external IP services.
- Reduced Graphs & Charts to a full-width Player Comparison with horizontal bar, pie, and table modes.
- Added sortable headers to player-detail, leaderboard, and comparison tables.
- Added selected-player statistic search and removed the global dashboard search.
- Continued collecting configured history while removing history graph UI.

### Added

- Added persistent operator commands to hide, unhide, and list dashboard-hidden players.
- Added `display.hiddenPlayers` configuration containing player UUID/id strings.

### Fixed

- Corrected the zero-stat toggle labels and states.
- Kept older history days available in the activity timeline while limiting line charts to recent samples.
- Preserved negative playtime changes instead of displaying them as zero.
- Prevented history totals from overflowing 32-bit integers.
- Prevented overlapping refresh requests and refresh-time UI flicker.
- Removed category totals that combined incompatible statistic units.
- Excluded hidden players from dashboard players, totals, online counts, leaderboards, charts, and future history samples.
- Improved wildcard-bind dashboard URLs by preferring site-local addresses, then usable non-loopback unicast addresses.

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
