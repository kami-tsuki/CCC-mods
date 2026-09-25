# KamiLibs

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--libs-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-libs)

The shared base for every Kami mod. On its own it adds nothing you can see in game. Install it whenever another Kami mod asks for it.

## What's inside

- **Networking** helpers, so every mod sends packets the same way
- **Config system** behind `config/kami/`: commented files split by topic, safe reloads with `/kami reload` and automatic moves from old config paths
- **Chat theme** so every Kami message looks the same: mod tag, highlighted values, clickable coordinates and buttons
- **Command system** that puts every mod under `/kami <mod>`, with a short `/<mod>` alias, a `help` list and a `reload` command for free
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
| `/kami` | List all Kami mods |
| `/kami reload` | Reload every Kami config |
| `/library reload` | Reload `coins.json` only |

Reloading needs operator rights.

## Used by

- [KamiGeology](../KamiGeology/README.md)
- [KamiClaims](../KamiClaims/README.md)
- [KamiEconomy](../KamiEconomy/README.md)

## Changelog

### 0.0.2-alpha
- New config system: every Kami mod keeps its settings in `config/kami/<mod>/`, split by topic and commented
- `/kami reload` reloads configs and tells you exactly which file has an error
- New command layout: `/kami <mod> <command>`, or `/<mod> <command>` for short, with `help` and `reload` in every mod
- Coin values moved here, so claims and economy always agree on them
- One chat style for all Kami mods, with highlighted values and clickable parts
- Shared logging: every Kami line in the log starts with `[KAMI|MOD|LEVEL]`, so you can filter by mod

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: networking, config, UI kit, charts, Numismatics bridge and the claims, geology and market APIs.
