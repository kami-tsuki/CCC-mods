# KamiLibs

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--libs-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-libs)

The shared base for every Kami mod. On its own it adds nothing you can see in game. Install it whenever another Kami mod asks for it.

## What's inside

- **Networking** helpers, so every mod sends packets the same way
- **Config system** behind `config/kami/`: commented files split by topic, safe reloads with `/kami reload` and automatic moves from old config paths
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

## Config

| File | What's in it |
|---|---|
| `config/kami/library/coins.json` | Coin items and their worth in spurs, shared by KamiClaims and KamiEconomy |

| Command | Use |
|---|---|
| `/kami` | Show the config folder and all Kami mods |
| `/kami reload [mod]` | Reload every Kami config, or just one mod |

Both need operator rights.

## Used by

- [KamiGeology](../KamiGeology/README.md)
- [KamiClaims](../KamiClaims/README.md)
- [KamiEconomy](../KamiEconomy/README.md)

## Changelog

### Unreleased
- New config system: every Kami mod keeps its settings in `config/kami/<mod>/`, split by topic and commented
- `/kami reload [mod]` reloads configs and tells you exactly which file has an error
- Coin values moved here, so claims and economy always agree on them

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: networking, config, UI kit, charts, Numismatics bridge and the claims, geology and market APIs.
