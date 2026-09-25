# KamiEssentials

[![Modrinth](https://img.shields.io/badge/Modrinth-KamiEssentials-1bd96a?logo=modrinth)](https://modrinth.com/project/8EJssQyq)

The everyday server commands for a Kami server, with a cleaner chat, tab list and sidebar. Every command has its own permission node, so LuckPerms decides who gets what.

## Features

- **Inventory and ender chest views** of any player, online or offline. Changes to an offline player are saved straight into their player file
- **Invisible mode**: the player vanishes from the world, the tab list and mob targeting, and everyone else sees a normal leave message. Turning it off shows a join message. Invisible players who join or leave stay silent
- **Safe trading** in a chest window: each side only touches their own offer, any change resets both accepts, a short countdown runs before the swap, and the trade only goes through if both players have room. Closing, leaving, dying or walking away gives every item back
- **Private messages** with `/msg` and `/r`, with a click to reply and a soft ping sound
- **Country chat** with `/cc`, as a toggle or for a single message
- **Chat** with the country tag in the country's color, hover cards for player and country, and clickable links
- **Join, leave and death messages** with a green **[+]**, a red **[-]** and a gray [☠]
- **Tab list** with country tags, a title, and online count, ping and TPS in the footer
- **Sidebar** with country, balance, playtime, kills, deaths, online count and ping. Each player can hide it

Country tags need [KamiClaims](../KamiClaims/README.md). Without it, names show without a tag.

## Commands

| Command | Use | Permission |
|---|---|---|
| `/invsee <player>` | Open a player's inventory | `invsee`, editing needs `invsee.edit` |
| `/enderchest [player]` or `/ec` | Open your own or another player's ender chest | `enderchest`, `enderchest.others`, `enderchest.edit` |
| `/balance [player]` or `/bal` | Show a Numismatics bank balance | `balance`, `balance.others` |
| `/invis [player]` | Turn invisible mode on or off | `invis`, `invis.others` |
| `/scoreboard [true\|false\|toggle]` | Show or hide your sidebar | `scoreboard` |
| `/msg <player> <text>`, `/tell`, `/w` | Send a private message | `msg` |
| `/r <text>` | Reply to your last private message | `msg` |
| `/trade <player>` | Ask a player to trade, or accept if they already asked you | `trade` |
| `/trade accept\|deny <player>`, `/trade cancel` | Answer or cancel a trade request | `trade` |
| `/countrychat [text]` or `/cc` | Toggle country chat, or send one message | `countrychat` |

Every command also works as `/essentials <command>` and `/kami essentials <command>`. The vanilla `/scoreboard` subcommands still work for operators.

## Permissions

All nodes start with `kami_essentials.`.

| Node | Default |
|---|---|
| `balance`, `scoreboard`, `msg`, `trade`, `countrychat` | Everyone |
| `invsee`, `invsee.edit`, `enderchest`, `enderchest.others`, `enderchest.edit` | Operators |
| `balance.others` | Operators |
| `invis`, `invis.others`, `invis.see` | Operators. `invis.see` lets a player see invisible players |
| `trade.anywhere` | Operators. Skips the trade distance check |

## Dependencies

| Mod | Needed |
|---|---|
| [KamiLibs](../KamiLibs/README.md) | Required |
| [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) | Required |
| [Create](https://modrinth.com/mod/create) | Required |
| [Numismatics](https://modrinth.com/mod/numismatics) | Required |
| [KamiClaims](../KamiClaims/README.md) | Optional, adds country tags and country chat |
| [LuckPerms](https://luckperms.net/) | Optional, to manage the permission nodes |

## Config

Everything is in `config/kami/essentials/`. Change a file and run `/essentials reload`.

| File | What's in it |
|---|---|
| `trade.json` | Trade distance (`-1` anywhere, `-2` same dimension, otherwise blocks, default 16), countdown and request timeout |
| `chat.json` | Turn the new chat, join and leave, and death messages on or off |
| `display.json` | Tab list title, sidebar title and which sidebar lines to show |

Invisible players, hidden sidebars and country chat toggles are saved in `<world>/kami_essentials.json`.

## Changelog

### 0.0.2-alpha
- First release: invsee, ender chest, balance, invisible mode, sidebar toggle, private messages, trading, country chat, new chat, tab list and sidebar.
