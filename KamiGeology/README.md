# KamiGeology

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--geology-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-geology)

Ores stop being random specks. KamiGeology swaps them for real deposits that depend on the biome, so finding a good vein takes some searching and pays off when you do.

## Features

- **Deposits** shaped like sheets, seams, bands, pipes, pods or clouds, placed on a loose grid
- **Biome provinces** that decide which ores show up where
- **Richness grades** that change how dense a deposit is and how much it drops
- **Prospectors** in 8 tiers, from Wooden up to Netherite-Diamond. The first one checks the ground right under you, the rest open a heatmap of the area.
- **Modded ores** tagged `c:ores` get picked up on their own
- **Config** in `config/kami/geology/`: `general.json`, `provinces.json` and one file per ore in `ores/`, all generated and commented on first start. Reload with `/kami reload geology`.

## Commands

All commands need operator rights.

| Command | Use |
|---|---|
| `/kami_geology heatmap` | Open the deposit map |
| `/kami_geology info` | List deposits nearby |
| `/kami_geology find <ore>` | Find the closest deposit of an ore |
| `/kami_geology audit` | Check that every production chain has enough ore |

## Dependencies

| Mod | Needed |
|---|---|
| [KamiLibs](../KamiLibs/README.md) | Required |
| [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) | Required |

The mod is needed on both server and client. The client part draws the heatmap.

## Used by

No other mod needs it. It offers deposit data through the geology API in KamiLibs for mods that want it.

## Changelog

### Unreleased
- Config moved from `config/kami_geology/` to `config/kami/geology/`, existing files move over on their own
- `/kami_geology reload` is now `/kami reload geology` and lists any file it had to skip

### 0.0.1-alpha-003
- Redesigned prospector textures for all 8 tiers
- Fixed and rebalanced the prospector recipes
- Better mod compatibility when removing original ores
- Heatmap and scan tweaks

### 0.0.1-alpha-002
- First release: deposits, biome provinces, richness grades, prospectors, heatmap and commands.
