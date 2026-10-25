# Transaction History Logger

A client-side logger for the Sunny server's `All Transactions` GUI.

## Behavior

- Opens no menus and sends no commands; it only observes the GUI already opened by the player.
- Checks the currently visible page every five client ticks while the screen title is exactly `All Transactions`.
- Detects a changed page by the transaction UUIDs and slot positions, then appends any UUIDs not already present in the log.
- UUIDs are normalized to lowercase and are the unique key. Revisiting pages and restarting Minecraft do not create duplicate rows.
- Ignores GUI controls and decorative items by requiring a UUID display name and Seller, Buyer, Item name, and Date lore fields.

## Output

`config/sunnyMod/transaction_history.csv`

Columns: `TransactionId`, `ItemId`, `ItemName`, `VanillaName`, `Count`, `Seller`, `Buyer`, `TransactionDate`, `FirstCaptured`, `LastCaptured`, `SlotIndex`, `Lore`.

This is append-only: an existing transaction UUID is skipped rather than overwritten. `FirstCaptured` and `LastCaptured` currently both record the timestamp at which the unique transaction was first captured; they are retained as separate columns for compatibility with future enhancements.

## Build note

The project uses the existing Fabric Loom / Minecraft 1.21.11 configuration. Build with `./gradlew build` in an environment with network access for the Gradle wrapper and dependencies. This environment could not download Gradle because network DNS access was unavailable, so compilation could not be verified here.
