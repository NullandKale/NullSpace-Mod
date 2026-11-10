# Testing against other mods

Ships carry whatever is aboard, so other mods' blocks, fluids, entities and items have to survive a launch and a landing, and keep behaving once they arrive. The compat tests load real mods beside NullSpace and fly a ship with everything they register.

```powershell
.\scripts\test\Run-CompatTests.ps1                                                    # every compat test, every mod
.\scripts\test\Run-CompatTests.ps1 -Classes com.nullandkale.nullspace.compat.BiomesOPlentyCompatGameTest
```

## The compat module

`src/compat` is a Gradle source set with **its own dependencies**: the mods under test. They are pinned in `gradle.properties` (`compat_*`, Modrinth version ids) and declared in `build.gradle` under `compatMods`, from Modrinth's Maven. Gradle downloads and caches them, `prepareCompatMods` copies the jars to `build/compat-mods`, and the runner loads them into a disposable test server's `mods` folder. The module compiles against the mods, so a per-mod test can use that mod's own classes and API.

The mods are used only in compat runs. They are not on the classpath of the shipped jar, the normal test suite or the normal game tests, and the jars are never committed or redistributed: many mod licenses forbid it. Mods that need others list them as `compatMods` too (Biomes O' Plenty needs GlitchCore and TerraBlender, neither bundled).

## What is tested

| Class | What it does | Needs mod-specific code? |
| --- | --- | --- |
| `ModCompatGameTest` | One of every entity type that fits in a cabin, and every block (default state plus each property turned once, in batches) with doors swapped into real doorways. Flown out and back; block state, block entity data, saved entity data, leads and riders are compared. | No: registry driven |
| `ModBehaviorGameTest` (containers) | Every vanilla and modded container, filled with named and damaged items, a shulker box of mod items, and the mod's own items, flown out and back. Chest boats get the same. | No |
| `ModBehaviorGameTest` (ticking) | Every block that ticks, holds data or flows, walled in with glass in a spot where it can stand (floor or ceiling). Ticked with fixed random seeds before the trip and after it, and left alone for real game time with and without a trip. Reports anything that acts differently afterwards, or lasts without travel but not with it. | No |
| `BiomesOPlentyCompatGameTest` | What a sweep cannot set up: Blood and Liquid Null contained in basins, leaves staying on a tree, grass spreading the same way, the anomaly block's block entity, and a pad floored with Origin Grass. | Yes, per mod |

Every class passes at once when no other mod is installed (per-mod classes skip when their mod is not loaded), so they are safe to register in the normal suite later.

## Reading a result

Each run writes a report per test to its work folder (`build/focused-tests/<time>-*-*/compat-*.txt`) with counts and every anomaly:

| Anomaly | Meaning |
| --- | --- |
| `REFUSED` | The ship would not launch with this block aboard, which would stall a player. |
| `CHANGED`, `BLOCK ENTITY LOST / DATA CHANGED` | The block, or its saved data, did not survive. |
| `TICKS DIFFERENTLY after the trip` | The same random ticks give a different result after the trip. |
| `LOST after the trip` | It lasts 40 ticks where it was placed but not after travelling. |
| `PAD DAMAGE` | A block's ticks turned the pad floor into something a ship will not launch from. |
| `NOT SAVED` | The entity type is marked no-save, so it cannot be carried. |

An anomaly that someone has looked at and accepted goes in `src/compat/resources/compat/known-issues.json` (category, then the id shown), so only a new problem fails the run.

## What the first mod found

Biomes O' Plenty's **Origin Grass Block** spreads onto dirt, and it turned a pad's dirt into itself. The pad code accepted only exactly vanilla grass and dirt, so the ship was refused ("Replace this pad-floor block with grass or dirt"). The same bug would have made it impossible to build a pad in a biome whose natural surface is a modded grass, such as the Origin Valley. Pad floors are now the tag `nullspace:pad_floor`, which holds dirt and the standard `minecraft:grass_blocks` tag that mods already use; a data pack can add more. See `PadFloor`.

Everything else Biomes O' Plenty registers (492 blocks, 28 entity types, two fluids) travels unchanged.

## The first batch: Waystones, Carry On, Chunk Loaders, Moving Elevators, Farmer's Delight

The generic sweeps (about 660 blocks, 29 entity types, 210 containers, 930 ticking blocks) found nothing in these five, apart from the points below. The harness refuels the reference ship before every leg, because hundreds of trips in a row otherwise empty its generator.

- **Waystones: warp plate and warp portal cannot be placed by the harness.** Placing a warp plate the way a player does throws a `NullPointerException` inside Waystones, and the warp portal is refused ("saved data is still loading or missing") because it needs a warp plate to hold its data. Both are listed as `unplaceable` and are an upstream matter, not a ship matter.
- **Waystones: a waystone comes back as a new waystone.** `PositionKeyedModsCompatGameTest.aWaystoneStaysOneWaystone` finds the database size unchanged (no ghost entry at the source or in space), but the waystone's UUID is different after a round trip. Anything that remembers the old one, such as a player's activated list, an attuned shard or a bound scroll, no longer points at it. This is recorded in `known-issues.json` as `waystone identity`. It is probably the block entity being removed without its normal removal path during the transfer; fixing it would mean carrying the identity through the transfer. Not fixed yet.
- **Chunk Loaders:** a loader keeps its chunk after the trip and leaves no ticket in space. (A loader holds only chunks the player picked in its screen, so the test asks for one the way that screen does.)
- **Moving Elevators:** the controller keeps exactly one group on the surface and leaves none in space.
- **Waystones: a Sharestone above the hull** makes a launch refuse with "Launch path obstruction". That is the ship's own rule, not a defect.

## Adding a mod

1. Add its pinned version to `gradle.properties` and a `compatMods` line in `build.gradle`, with anything it needs.
2. Run the suite. The generic classes test everything it registers with no further code.
3. For the unusual parts (its own fluids, growth, spreading, multiblocks, claims, anything keyed to world position), write a small class like `BiomesOPlentyCompatGameTest`, using the helpers in `ModCompatGameTest` and `EntityTravelGameTest`.
4. Triage what the run reports: fix our side where a rule is too narrow (as with the pad floor), or record the finding in `known-issues.json` with a reason.

A mod with no Fabric build for the current Minecraft version cannot be fetched from Modrinth; use a local jar in `build/compat-mods` to try it.

## Not covered yet

Item entities and items in inventories of entities other than chest boats, per-mod entity AI, mods that hook ship-level events, and Forge/NeoForge-only mods (NullSpace is Fabric).
