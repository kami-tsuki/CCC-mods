# Kami Mods

A small family of NeoForge mods for Minecraft 1.21.1, written in Kotlin. They are built for my own modpack server, but anyone is welcome to use them.

| Mod | What it is about | Modrinth |
|---|---|---|
| [KamiLibs](KamiLibs/README.md) | Shared code every other Kami's mod needs | [kamis-libs](https://modrinth.com/mod/kamis-libs) |
| [KamiGeology](KamiGeology/README.md) | Realistic ore deposits | [kamis-geology](https://modrinth.com/mod/kamis-geology) |
| [KamiClaims](KamiClaims/README.md) | Countries, chunk claims, taxes and politics | [kamis-claims](https://modrinth.com/mod/kamis-claims) |
| [KamiEconomy](KamiEconomy/README.md) | Live market and auction house (Create:Numismatics based) | [kamis-economy](https://modrinth.com/mod/kamis-economy) |
| [KamiEssentials](KamiEssentials/README.md) | Server commands, safe trading, invisible mode, chat, tab list and sidebar | [8EJssQyq](https://modrinth.com/project/8EJssQyq) |

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1 or newer
- Java 21
- [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge)

## Building

Everything lives in one Gradle project. From the repo root:

```sh
./gradlew build                          # build all mods
./gradlew :KamiGeology:build             # build one mod
./gradlew build -Pdeploy_enabled=false   # build without copying jars to the test setup
```

Jars end up in `build/<Mod>/libs/`. 
With deploy enabled, the build also copies each jar into the local test server and client instance set in `gradle.properties`. 
`scripts/start-server.ps1` syncs the test server with the client instance and starts it.

## Versions

Versions look like `0.0.1-alpha-003`.

- `version_major`, `version_minor`, `version_patch` and `version_tag` in `gradle.properties` make up the first part.
- The last number is the build number. It counts the commits since the version was last changed.
- The tag decides the Modrinth channel: `alpha` goes to alpha, `beta` goes to beta, and an empty tag is a full release.

## Commands

Every command follows `/kami <mod> <command>`. The same command also works without the `kami` in front, so `/kami claims info` and `/claims info` do the same thing.

| Command | Use |
|---|---|
| `/kami` | List all Kami mods |
| `/kami reload` | Reload every Kami config |
| `/<mod> help` | List the commands of one mod, only the ones you may use |
| `/<mod> reload` | Reload one mod's config |

The mods are `library`, `claims`, `economy`, `geology` and `essentials`. KamiEssentials also adds short root commands like `/msg`, `/trade` and `/invsee`.

## Configs

All settings live in one folder on the server, one subfolder per mod:

```
config/kami/
├── library/   coins.json
├── claims/    general, chunk-types, jobs, plots, protection, ranks, messages, client
├── economy/   general, market, auctions, starter-items, blocked-items
├── geology/   general, provinces, ores/<ore>.json
└── essentials/ trade, chat, display
```

- Every file is created on first start, with a short comment above each setting.
- Edit a file, save it and run `/kami reload`. Use `/claims reload` to reload a single mod.
- If a file has a typo, it stays untouched. The mod keeps its current values and the command tells you which file broke and why.
- Values out of range get fixed and written back. Unknown settings are removed.
- Coin values are shared: KamiClaims and KamiEconomy both read `library/coins.json`.
- Configs from 0.0.1 (`config/kami_claims.json` and friends) move over on their own. The old file stays in the new folder as `.old`.

## Pipeline

The workflow lives in `.github/workflows/mods.yml` and runs on every push to `main`, `develop` and `release/v*`.

## AI disclaimer

I use AI tools (mainly Claude) while working on these mods.
I do **NOT** use AI for Art (Textures) or Soundeffects besides Concepts.
I use AI to **polish** Texts (like this one, yes) but i re-read it, adjust it and make sure the informations are true.
I read, test and play with everything before it gets released, and all design decisions are mine. 
**If you find something odd, please open an issue.**

## License

MIT. See [LICENSE](LICENSE).
