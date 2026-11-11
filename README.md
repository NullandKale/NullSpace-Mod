# NullSpace

A Minecraft mod about building ships that really fly. Build a hull on a landing pad, launch it into space with everything aboard, jump between star systems, dock at stations and salvage derelicts. Then land somewhere new.

Built for **Minecraft Java 26.3** on **Fabric**.

## Play

Follow **[How to set up NullSpace](docs/player/how-to-setup.md)** to install it with the Modrinth App and join a server. You need:

- Minecraft Java 26.3 with Fabric Loader 0.19.5
- [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3
- the latest `nullspace-*.jar` from [Releases](https://github.com/NullandKale/NullSpace-Mod/releases)

New to it? Read the [first ship walkthrough](docs/player/first-ship-walkthrough.md). In game, `/nullspace guide` gives you the field guide.

## What's in it

- **Real ships.** Any sealed hull with a controller, generator, engine and Air Supply can launch. Every block and entity aboard travels: chests, redstone, animals, passengers.
- **Air and power.** Rooms hold air and leak through holes. Generators burn furnace fuel to power engines, Air Supplies, doors and cameras. Wear a suit outside.
- **A galaxy per world.** Sol and four random star systems, with nebulae, asteroid fields and a black hole or pulsar. Landable planets each have their own terrain. A star map plans hops within your engine's range.
- **Journeys.** Interstellar trips take time, and signals along the way lead to derelicts, drifting cargo and ambushes.
- **Stations and docking.** Dock automatically at stations and other ships, then walk through. You can also build your own station in space, room by room.
- **Derelicts.** Claim abandoned ships as your own. Lost crew and repair bots defend them, and loot gets better the farther you go.
- **Pads, storage and titles.** Register landing pads, store ships, recall them, and hand ships over with a Ship Title.

## Develop

You need a 64-bit **JDK 25**. Either set `JAVA_HOME`, or extract a JDK under `.tools/jdk-25*`, which the scripts prefer. On Windows the scripts in [`scripts/`](scripts) wrap Gradle; elsewhere, use `./gradlew`.

| Task | Command |
| --- | --- |
| Run the game (dev world) | `.\scripts\run\dev.ps1` |
| Run a dev server | `.\scripts\run\server.ps1` |
| Server plus two clients | `.\scripts\run\local-server.ps1` |
| Unit and server tests | `.\scripts\test\test.ps1` |
| One test class | `.\scripts\test\Run-FocusedTests.ps1 -Classes <full.class.Name>` (add `-Client` for graphical tests) |
| Everything, in parallel | `.\scripts\test\parallel-test.ps1 -Graphics` |
| Build the JAR | `.\scripts\build\build.ps1` (output in `build/libs/`) |
| Format Java | `.\scripts\build\format.ps1 -Write` |

Dependency versions are in [`gradle.properties`](gradle.properties). Minecraft 26.3 is unobfuscated, so the code uses the game's own names.

### Where things are

The four source sets share one package layout: `main` (common and server), `client` (client only), `test` (unit tests) and `gametest` (in-game tests). Packages under `com.nullandkale.nullspace` are layers, with features inside each layer:

| Layer | What it holds |
| --- | --- |
| `core` | Server lifetime, saving, Minecraft mixins |
| `content` | Registered blocks, items, entities, machine menus, the field guide |
| `model` | Pure rules with no world access: air, power, flight, galaxy, space scenes, the computer VM |
| `gameplay` | Features acting on the world: ships, travel, crew, ship systems, navigation, docking, stations, pads, ownership |
| `worldgen` | Generated structures: ship and station blueprints, encounters, sites |
| `ui` | Terminal screens and menus (`client` adds the Minecraft screens) |
| `render` | Client rendering: space scenes, compute shaders, effects |
| `dev` | Development worlds, test galleries and authoring tools |

### Documentation

- [`docs/player`](docs/player): setup, the first ship and the in-game guide
- [`docs/design`](docs/design): how each feature works and why
- [`docs/development`](docs/development): [architecture](docs/development/architecture.md), [testing](docs/development/local-verification.md) and the [playtest roadmap](docs/development/playtest-chunks.md)
- [`docs/release`](docs/release): the [release policy](docs/release/release-policy.md) and alpha notes

Releases come from tags: pushing `vX.Y.Z` builds, tests and publishes the JAR through GitHub Actions.

## License

No license has been chosen yet.
