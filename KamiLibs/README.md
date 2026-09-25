# KamiLibs

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--libs-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-libs)

The shared base for every Kami mod. On its own it adds nothing you can see in game. Install it whenever another Kami mod asks for it.

## What's inside

- **Networking and config** helpers, so every mod sends packets and saves settings the same way
- **UI kit** with one theme, scroll lists and line, candle and bar charts
- **Numismatics bridge** for coins and bank accounts
- **Cross-mod APIs** for claims, geology and the market, so the mods can talk to each other without hard links
- **Xaero map overlay** support, used to draw claims on the world map
- **Permission** helpers for LuckPerms

## Dependencies

| Mod | Needed |
|---|---|
| [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) | Required |
| [Create](https://modrinth.com/mod/create), [Numismatics](https://modrinth.com/mod/numismatics), [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) | Optional, only used when present |

## Used by

- [KamiGeology](../KamiGeology/README.md)
- [KamiClaims](../KamiClaims/README.md)
- [KamiEconomy](../KamiEconomy/README.md)

## Changelog

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: networking, config, UI kit, charts, Numismatics bridge and the claims, geology and market APIs.
