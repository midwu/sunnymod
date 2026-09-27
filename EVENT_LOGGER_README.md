# SunnyMod Event Logger

Integrated into SunnyMod 0.20.5 / Minecraft 1.21.11.

## Output

`config/sunnyMod/event_log.txt`

The logger records raw server/client-visible events before we attempt to interpret them as Skills data.

## Text/UI channels

- `CHAT` — ordinary player chat.
- `GAME` — server/system game messages.
- `ACTIONBAR` — server messages displayed above the hotbar.
- `TITLE` / `SUBTITLE` / `TITLE_TIMING`.
- `BOSSBAR_ADD` / `BOSSBAR_UPDATE` / `BOSSBAR_REMOVE` — boss/progression bars, including name, percentage, color and style.
- `XP_PACKET` — vanilla Minecraft XP bar/level updates.
- `HEALTH` — vanilla health/food updates.

## Structured server channels

The packet probe records raw `toString()` representations for high-value server->client packets: inventories/slot updates, actionbar packets, sounds, particles, cooldowns, advancements, statistics, player-list data, scoreboards, recipe-book updates, trade offers and custom payloads.

This is intentionally broad but does not attempt to record every world/chunk packet. The goal is to discover useful data channels without producing an unusable log.

## Skills investigation

When you next play with this build, reproduce the Skills actions you care about. Then send the resulting `event_log.txt`. We can use it to determine exactly which events represent skill XP, level-ups, ability unlocks, cooldowns and other progression state.

No parsing or automatic Skills interpretation is performed yet.
