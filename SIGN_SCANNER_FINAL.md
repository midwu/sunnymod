# SunnyMod Sign Scanner — Finalization Notes

## Status
The passive shop-sign scanner is implemented and integrated into SunnyMod.

## What it does
- Reads `SignBlockEntity` data already present in the client world; it does not click signs or send interaction packets.
- Recognizes the server's observed shop format:
  - line 1: owner
  - line 2: `Selling N`, `Buying N`, `Out of Stock`, or `Out of Space`
  - line 3: item
  - line 4: `$price ...`
- Handles comma-formatted prices and stock values.
- Scans only loaded chunks; it never forces chunk loading.
- Merges by block coordinates into `config/sunnyMod/shop_data.csv`.
- Marks scanner rows as `Source=sign` and existing/legacy chat rows as chat-origin data.
- A fresh chat inspection is authoritative for stock for the configured trust window (`chatStockTrustMinutes`, default 60 minutes).
- Unchanged sign observations do not rewrite the row or timestamp. This prevents constant disk writes and preserves meaningful age data.
- Warp changes request a scan.
- Automatic scans are throttled (default manual key press / ~5 seconds, with an additional 5-second wall-clock guard).
- Manual commands: `/shopscan` and `/shopscan <radius>`.
- Existing packet diagnostic remains available via `/signdump packets on|off`.

## Important limitations
- The scanner can only see signs that Minecraft has already loaded into the client world.
- A sign disappearing from a chunk is not treated as proof that a shop was deleted; old rows are retained to avoid false deletions.
- `Out of Stock` / `Out of Space` does not intrinsically reveal whether the shop was a seller or buyer. If an existing row has a known SELLING/BUYING action, that action is preserved.
- If a brand-new empty shop is first discovered only as `Out of Stock`/`Out of Space`, its action is `UNKNOWN` until another source supplies the direction.

## Build status
A Gradle build was attempted with the project's Gradle 9.5.1 wrapper. The environment could not resolve `services.gradle.org`, so dependency/toolchain download failed with `UnknownHostException`. This is an environment/network limitation, not a compiler result. Run `./gradlew build` on a machine with access to the Gradle distribution and Maven repositories.

## Recommended verification
1. Install the resulting JAR in the Minecraft 1.21.11 Fabric client.
2. Enable Sign Scanner in Mod Menu.
3. Use `/shopscan 256` while standing in a shop area.
4. Check `config/sunnyMod/shop_data.csv` for `Source=sign` rows.
5. Compare one sign with `/signdump 64`.
6. Inspect `/signdump packets on`, move/refresh through a shop area, then turn it off.
7. Click a shop so the server returns live stock; confirm the row becomes `Source=chat` and is not immediately replaced by stale sign stock.
8. Change warp and verify a new scan occurs manually.
9. Run the existing Profit Finder and verify sign-origin stock is accepted and marked as sign stock in its results.
