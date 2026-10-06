# SunnyMod Sign Scanner — Manual Scan

## Default behavior
- Automatic periodic scanning is disabled.
- Press **F6** to scan shop signs in the loaded client area around the player.
- The default radius is **10 chunks = 160 blocks**.
- The key can be changed in **Options → Controls → Key Binds → Sunny Mod → Scan Shops**.
- `/shopscan` performs the same scan.
- `/shopscan <radius>` performs a one-off scan with the requested block radius.
- Changing warp does **not** trigger a scan.

## Configuration
The ModMenu **Sign Scan Radius** setting defaults to 160 blocks. The slider permits 16–512 blocks.

## Build
Run `./gradlew build` from the project root with Java 21 and access to the configured Gradle/Maven repositories.
