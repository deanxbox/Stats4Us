# Stats4Us

Stats4Us is a server-side Fabric mod for Minecraft `26.1.2` that exposes normal vanilla player statistics through a configurable web dashboard and operator-only commands.

## Features

- Reads vanilla statistics from the world `stats/` files.
- Uses live in-memory stats for online players, including playtime.
- Includes all vanilla statistic categories shown by Minecraft:
  - General/custom stats
  - Blocks mined
  - Items crafted, used, broken, picked up, and dropped
  - Mobs killed and killed by
- Web dashboard available on the server's normal IP/host plus the configured port.
- Dashboard tabs for per-player stats, category leaderboards, and an all-stat catalog.
- Leaderboards can hide stats where every player has a zero value.
- All Stats can also hide zero-only stats to keep the page readable.
- Players tab uses a compact player list with click-to-view detail pages.
- Graphs & Charts tab provides built-in bar charts and distribution graphs for top players and category totals.
- Historical activity tracking records server/player samples over time for activity timelines and trend graphs.
- Operator-only `/stats4us` commands for viewing and editing player stats.

## Configuration

The mod creates `config/stats4us.json` on first launch.

Important fields:

- `web.enabled`: enable/disable the dashboard.
- `web.bindAddress`: default `0.0.0.0`, meaning the dashboard listens on all server network interfaces. Use your normal Minecraft server IP/hostname with `web.port` to open it.
- `web.port`: default `8765`.
- `display.enabledStatTypes`: stat categories to include.
- `display.enabledStats`: if non-empty, only these exact `type|value` keys are shown.
- `display.hiddenStats`: exact `type|value` keys to hide.
- `display.showZeroValues`: show stats with value `0`.
- `history.enabled`: enable/disable historical samples for graphs.
- `history.sampleIntervalSeconds`: seconds between activity samples, default `300`.
- `history.maxSamples`: maximum stored samples, default `2016`.
- `history.trackedStats`: exact `type|value` stat keys recorded over time.

Historical data is saved to `config/stats4us-history.json`. Existing Minecraft stats files contain current cumulative totals, so detailed timelines start once this mod has been running and sampling.

Example stat key:

```text
minecraft:custom|minecraft:play_time
```

## Commands

All commands require permission level `2`. Player arguments work for online players, known offline player names from `usercache.json`, UUIDs, and existing world `stats/*.json` files.

```text
/stats4us reload
/stats4us web
/stats4us stats [search]
/stats4us player <online/offline player or uuid> [search]
/stats4us get <online/offline player or uuid> <stat name/key>
/stats4us set <online/offline player or uuid> <amount> <stat name/key>
/stats4us add <online/offline player or uuid> <amount> <stat name/key>
```

Examples:

```text
/stats4us stats deaths
/stats4us player Steve deaths
/stats4us get Steve deaths
/stats4us set Steve 0 deaths
/stats4us add Steve 10 mob kills
/stats4us get Steve minecraft:custom|minecraft:play_time
```
