# SunnyMod passive shop-sign scanner

This build adds passive shop-sign scanning for Minecraft 1.21.11.

- Automatic scan: enabled by default.
- Manual command: `/shopscan` or `/shopscan <radius>`.
- Sign packet diagnostic: `/signdump packets on`.
- Scanner output: `config/sunnyMod/shop_data.csv`.
- Sign rows carry `Source=sign`; live chat inspections carry `Source=chat`.

See `SIGN_SCANNER_HANDOFF.md` for the complete implementation notes, evidence, limitations, build status, and continuation procedure.
