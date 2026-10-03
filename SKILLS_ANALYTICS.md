# SunnyMod Skills subsystem

The Skills subsystem is now split into two layers:

## Data / underlying layer

- `/skills` menu scanner: authoritative level/current-XP/required-XP snapshots.
- Progression boss-bar reader: high-frequency active-skill XP updates.
- Individual 5-second HUD freshness for each skill.
- Current XP/s: one-second window.
- Rolling XP/hr: 60-second history.
- Session XP, duration, XP events, level-up count and peak XP/s.
- Session money observed through SunnyMod's existing earnings detector.
- Inventory deltas observed during the active Skills session.
- Up to 50 completed local session summaries saved to `config/sunnyMod/skills_sessions.json`.
- Session is automatically closed and saved on disconnect.
- Raw `/skills` menu entries and tooltips are retained in memory for the Server Data view.
- Boss-bar diagnostics now log add/update/remove state changes rather than every render frame.
- High-volume raw packet probes are filtered to useful packet families.

## UI / above layer

- H HUD editor integration for the Skills panel.
- Individual stale skills disappear after 5 seconds without an update.
- F10 Skills dashboard.
- Overview: levels, progress, session XP, current XP/s and ETA.
- Session: aggregate XP/s, XP/hr, events, level-ups, peak rate, observed money and inventory gains.
- History: completed local sessions.
- Guide: explains exactly what each metric means and what it does not claim.
- Server Data: raw entries observed from the live `/skills` menu, including tooltips for things such as player level, rewards, prestige or other server-provided menu entries when present.
- Commands:
  - `/sunnymod skills open`
  - `/sunnymod skills status`
  - `/sunnymod skills reset`
  - `/sunnymod skills session reset`
  - `/sunnymod skills history`
  - `/sunnymod skills history clear`

## Important attribution rule

SunnyMod does not currently claim that an inventory delta is caused by a particular skill action. It is shown as an observed inventory change. Likewise, an XP event is an observed positive Skills XP change, not a guessed number of blocks broken, crops harvested, mobs killed, etc.

Those activity-specific attribution rules can be added later once the server's exact action signals have been captured and verified.
