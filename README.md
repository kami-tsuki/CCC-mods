# Kami Mods

A small family of NeoForge mods for Minecraft 1.21.1, written in Kotlin. They are built for my own modpack server, but anyone is welcome to use them.

| Mod | What it is about | Modrinth |
|---|---|---|
| [KamiLibs](KamiLibs/README.md) | Shared code every other Kami's mod needs | [kamis-libs](https://modrinth.com/mod/kamis-libs) |
| [KamiGeology](KamiGeology/README.md) | Realistic ore deposits | [kamis-geology](https://modrinth.com/mod/kamis-geology) |
| [KamiClaims](KamiClaims/README.md) | Countries, chunk claims, taxes and politics | [kamis-claims](https://modrinth.com/mod/kamis-claims) |
| [KamiEconomy](KamiEconomy/README.md) | Live market and auction house (Create:Numismatics based) | [kamis-economy](https://modrinth.com/mod/kamis-economy) |

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
