# KamiClaims

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--claims-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-claims)

Claim land together as a country. Every chunk costs upkeep, so land means responsibility. Countries grow, pay taxes, hire workers, fall into debt and form provinces.

## Features

- **Countries** with ranks: President, Chancellor, Officer, Citizen, Allied and Banished
- **Chunk claims** that must connect, with a capital and 9 free chunks to start
- **Chunk types** like Mining, Farming, Factory, Market, Residential and more, each with its own rules and price
- **Treasury and upkeep** paid in Numismatics coins every real day. Unpaid chunks build debt and are lost after a while.
- **Player plots** inside residential chunks, with rent and their own permissions
- **Jobs** with quotas and daily pay, counted on their own from what players mine or farm
- **Provinces** so a country can rule land that is far away
- **Protection** against griefing, explosions, fire, fluids, pistons and PvP, all set per chunk type
- **GUI** on `K` or `/country`, with a clickable map, plus an overlay for Xaero's World Map
- **LuckPerms** nodes for every action

## Dependencies

| Mod | Needed |
|---|---|
| [KamiLibs](../KamiLibs/README.md) | Required |
| [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) | Required |
| [Create](https://modrinth.com/mod/create) | Required |
| [Numismatics](https://modrinth.com/mod/numismatics) | Required |
| [LuckPerms](https://luckperms.net) | Optional, for permission nodes |
| [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) | Optional, shows claims on the map |
| [KamiEconomy](../KamiEconomy/README.md) | Optional |

Clients can join without the mod. With it installed, they get the GUI and the map overlay.

## Used by

- [KamiEconomy](../KamiEconomy/README.md) uses claims to decide where vendor blocks may be placed.

## Changelog

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: countries, claims, chunk types, upkeep and debt, plots, jobs, provinces, protection, GUI, Xaero overlay and LuckPerms support.
