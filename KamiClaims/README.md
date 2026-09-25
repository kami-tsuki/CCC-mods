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
- **GUI** on `K` or `/claims`, with a clickable map, plus an overlay for Xaero's World Map
- **LuckPerms** nodes for every action

## Commands

All commands start with `/claims`, or `/kami claims` if you prefer the long form. `/claims help` lists everything you may use.

| Command | Use |
|---|---|
| `/claims` | Open the GUI, or show your country in chat without the client mod |
| `/claims create <name>` | Found a country |
| `/claims claim [type] [radius]` | Claim the chunk you stand in |
| `/claims info [country]` | Treasury, upkeep and chunks |
| `/claims invite <player>` | Invite a player |
| `/claims plot ...` | Claim, release and share plots |
| `/claims province ...` | Rule other countries as provinces |
| `/claims admin ...` | Server tools for admins |

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

## Config

Everything is in `config/kami/claims/`. Change a file and run `/claims reload`.

| File | What's in it |
|---|---|
| `general.json` | Free chunks, debt, inactivity, invites and other time limits |
| `chunk-types.json` | Every chunk type with its price and default rules |
| `jobs.json` | Jobs, quotas and pay limits |
| `plots.json` | Plot limits, rent and lockout days |
| `protection.json` | Rules for unclaimed land, mobs and pistons |
| `ranks.json` | Lowest rank that may use each feature |
| `messages.json` | Border titles, notifications and mail |
| `client.json` | Map and HUD toggles, on each player's own game |

Coin values come from `config/kami/library/coins.json`.

## Used by

- [KamiEconomy](../KamiEconomy/README.md) uses claims to decide where vendor blocks may be placed.

## Changelog

### 0.0.2-alpha
- Config moved to `config/kami/claims/` and split into topic files with comments
- Reload the config in game with `/claims reload`
- Commands moved: `/country ...` is now `/claims ...`, `/plot` is `/claims plot` and `/countryadmin` is `/claims admin`
- Coin values now come from KamiLibs

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: countries, claims, chunk types, upkeep and debt, plots, jobs, provinces, protection, GUI, Xaero overlay and LuckPerms support.
