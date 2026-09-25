# KamiEconomy

[![Modrinth](https://img.shields.io/badge/Modrinth-kamis--economy-1bd96a?logo=modrinth)](https://modrinth.com/mod/kamis-economy)

A server wide market built on Create: Numismatics. Players sell into a shared order book, buyers always get the best live price, and prices move with supply and demand. Special items go to the auction house.

## Features

- **Market** on `/economy`, open from anywhere
- **Live prices** that shift smoothly and stay inside set limits, so inflation stays in check
- **Price tooltips** on every item, with a small arrow showing where the price is heading
- **Charts** for each item as line, candle or bar graph
- **Auction house** for enchanted, renamed or other unique items, with bidding and buy now
- **Starter income**: basic resources like logs, wheat and cobblestone can always be sold to the system
- **Sales tax** on market sales, and a small fee on auctions
- **Crash safe**: every trade is logged first and replayed after a crash, so nothing gets lost or duplicated
- **Vendor blocks** from Numismatics only work inside market chunks when KamiClaims is installed

## Dependencies

| Mod | Needed |
|---|---|
| [KamiLibs](../KamiLibs/README.md) | Required |
| [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) | Required |
| [Create](https://modrinth.com/mod/create) | Required |
| [Numismatics](https://modrinth.com/mod/numismatics) | Required |
| [KamiClaims](../KamiClaims/README.md) | Optional, limits vendor blocks to market chunks |

## Config

Everything is in `config/kami/economy/`. Change a file and run `/economy reload`.

| File | What's in it |
|---|---|
| `market.json` | Price updates, limits per item and the sales tax |
| `auctions.json` | Auction fee and duration |
| `starter-items.json` | Items the server always buys, and at what price |
| `blocked-items.json` | Items that can't be traded or only go to auction |
| `general.json` | Price history, page size and vendor blocks |

Coin values come from `config/kami/library/coins.json`.

## Used by

- [KamiClaims](../KamiClaims/README.md) lists it as an optional companion.

## Changelog

### 0.0.2-alpha
- Config moved to `config/kami/economy/` and split into topic files with comments
- Reload the config in game with `/economy reload`
- `/market` is now `/economy` (or `/economy market`), admin tools moved to `/economy admin`
- Coin values now come from KamiLibs
- `maxMovePct` in `categoryBounds` now works per item
- Messages use the shared Kami chat style

### 0.0.1-alpha-003
- Rebuilt with the other mods, no changes.

### 0.0.1-alpha-002
- First release: market, live pricing, tooltips, charts, auction house, starter income, crash recovery and vendor rules.
