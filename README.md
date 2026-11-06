# NullSpace

A Minecraft total conversion built on **Minecraft Java 26.3**, **Fabric Loader**, and **Fabric API**. Gameplay starts with vanilla movement, combat, food, and world generation. No other gameplay mods are included.

## Toolchain

| Component | Pinned version |
| --- | --- |
| Minecraft Java | 26.3 |
| Java | JDK 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.164.0+26.3 |
| Fabric Loom | 1.18.4 |
| Gradle Wrapper | 9.7.1 |

Dependency versions are in `gradle.properties`; the Gradle distribution and its checksum are in `gradle/wrapper/gradle-wrapper.properties`. Minecraft 26.3 is unobfuscated: use the game's own names rather than Yarn mappings.

## Develop on Windows

Install a 64-bit JDK 25 and set `JAVA_HOME`, or extract a JDK 25 under `.tools/jdk-25*`. The PowerShell scripts prefer the project-local JDK and keep Gradle's cache in `.gradle-user-home`. They restore the shell's environment afterward.

```powershell
.\dev.ps1                  # Reopen the active dev world as Player451
.\dev.ps1 --restart        # Back up the old save, then choose settings in Create New World
.\dev.ps1 -ResetWorld      # Fresh Horizon space start; back up the previous closed World 5
.\gpu.ps1 -Apply           # Prefer the dedicated GPU for this repo JDK (Windows)
.\server.ps1               # Launch a dedicated development server
.\testt-server.ps1         # Server terminal + two side-by-side clients, connected automatically
.\test.ps1                 # Run checks, unit tests, and server GameTests
.\multiplayer-test.ps1      # Opt-in local two-client dedicated-server test
.\crash-test.ps1            # Opt-in real process crash/restart matrix
.\shipyard-crash-test.ps1   # Seven real ship-delivery crash/restart checkpoints
.\build.ps1                # Test and produce the mod JAR
.\verify.ps1 -Graphics -Multiplayer -Crash  # Combined local verification + durable artifacts
.\format.ps1 -Write         # Apply the pinned Java formatter
.\scripts\Invoke-Gradle.ps1 runClientGameTest  # Create/render a world and capture a screenshot
.\scripts\Invoke-Gradle.ps1 genSources         # Generate Minecraft sources for the IDE
```

The first run downloads Gradle, Minecraft, and its dependencies. Additional arguments pass through to Gradle, for example `.\build.ps1 --no-daemon`. Artifacts are written to `build/libs/`. Install the non-sources JAR alongside the pinned Fabric API in a Fabric Loader 0.19.5 / Minecraft 26.3 instance.

Development, playtest, and ship-lab launches use the fixed offline name `Player451`, which owns the existing World 5 survival character. Loom's random default names create different player UUIDs and can make inventory and position appear lost after a restart. Use `-PdevUsername=AnotherName` only when you want a separate character. [World 5 recovery notes](docs/world5-recovery.md) record the verified backup and diagnosis.

`dev.ps1` reopens the active development save, initially `run/saves/World 5`. Use `--restart` (or `-Restart`) after saving and quitting to move that save into `run/backups` and open Minecraft's **Create New World** screen. Choose the seed, world type, game mode and other generation settings there. The **Start** tab defaults to a Horizon space start; change its ship or choose a planet start. `-StartingShip <preset>` changes the preferred ship. The launcher remembers the actual folder you create, including a different world name. Cancelling creation leaves the old world in its backup and lets the next launch return to creation. A missing save also opens this UI.

`-ResetWorld` remains the explicit shortcut for the verified, seeded [World 5 story](docs/dev-world5-story.md), including its selected route and recovery pad. It bypasses the creation UI. For the title screen, use `.\scripts\Invoke-Gradle.ps1 runClient`; playtest and lab launches keep their existing behavior. For sky iteration, edit the GLSL under `src/client/resources/assets/nullspace/shaders/compute`, run `.\scripts\Invoke-Gradle.ps1 processClientResources processResources`, then press **F3+T** in the running client to reload the shader set. Java changes require restarting the client.

On other platforms, use `./gradlew` with JDK 25. For IDE import, open this directory as a Gradle project and select JDK 25 as the Gradle JVM.

The dedicated server uses a local development directory. On its first launch, review Minecraft's EULA and set `eula=true` in the generated `eula.txt` before restarting. The automated GameTest harness accepts the EULA for its disposable test server through Loom's `configureTests` configuration.

`testt-server.ps1` is the interactive two-player sandbox. It opens a separate server console, waits until the server is ready, then connects `NullSpace_One` and `NullSpace_Two` in left/right windows. The default space start gives each new player a Sparrow above a random planet. Use `-StartingShip mule`, `-VanillaStart`, `-Port 25565`, or `-Instance another-name` as needed. Worlds and player profiles persist under `run-multiplayer/<instance>/`; changing the starter setting affects new players. Type `stop` in the server console to save and shut down. This loopback-only launcher accepts the EULA for its local sandbox and uses offline player identities. [Launcher details](docs/local-multiplayer-sandbox.md).

## Layout and testing

The latest [test review and coverage additions](docs/test-gap-review.md) include real pack reloads, world/GPU lifetimes and launch/landing crash recovery. [Verification evidence](docs/test-gap-verification.json) records the passing matrix and built JAR.

- `src/main`: shared code, safe to load on a dedicated server.
- `src/client`: rendering, input, and other client-only code.
- `src/test/java`: JUnit tests for pad discovery, ship systems, atmosphere, scene geometry/protocols, optical math, and failure cases.
- `src/gametest`: test-only mod, excluded from release JARs. Server tests cover hulls, pressure, airlocks, survival, entity/container round trips, two passengers, offline arrivals, and journal recovery. The client test drives the terminal and loading-screen round trip.

The [runtime architecture](docs/architecture.md) describes ownership, persistence, catalogs and maintenance. The [regression coverage matrix](docs/regression-coverage.md) lists deterministic simulations, malformed-input checks, native server behavior and graphical/multiplayer scenarios.

`build` and `check` include server GameTests. Multiplayer and process-crash harnesses are local opt-in scripts, excluded from build/check and CI; see [local verification](docs/local-verification.md). Client GameTests run separately because they need graphics. GitHub Actions builds the mod and runs the server tests; it uploads JARs on success and reports on failure.

## References

- [Fabric 26.3 release and porting notes](https://fabricmc.net/2026/09/15/263.html)
- [Fabric developer documentation](https://docs.fabricmc.net/develop/)
- [Fabric automated testing](https://docs.fabricmc.net/develop/automatic-testing)
- [Official example mod](https://github.com/FabricMC/fabric-example-mod/tree/26.3)

Fabric's general documentation currently targets 26.2; check 26.3 source signatures when an example differs.

## Space rendering

The client requests **OpenGL 4.3 core** and renders celestial surfaces, asteroid fields, atmospheres, nebulae, and comet tails with compute shaders. Real ship blocks and players remain rasterized. The same scene definition serves windows/EVA, cameras, monitors, and F2 capture. See the [renderer implementation record](docs/space-compute-renderer-plan.md) for protocol limits, quality properties, tests, measured timings, and composition limitations.

On Windows, `.\gpu.ps1 -Apply` sets the high-performance GPU preference for the repo JDK; `.\gpu.ps1 -Restore` restores its saved prior values. New Minecraft launches on this machine have verified the **NVIDIA RTX 3070 Laptop GPU / OpenGL 4.3**. Existing processes need restarting to change adapters. Installed launcher JREs must be configured separately in Windows Graphics settings.

The legacy renderer remains selectable with the JVM argument `-Dnullspace.compute=false` before starting Minecraft. Other backends do not have a compute implementation here.

## Build, launch, and return

To obtain a preset ship, craft a [Ship Beacon](docs/shipyard.md), place it on a registered pad's ring and choose a design, then **Deliver ship**. Purchases are free for now. `/nullspace shipyard` builds and visits the operator showroom with fifteen preset ships and three empty delivery pads. The [manufacturer fleet](docs/procedural-ships.md#current-review-set) includes scouts, freighters and two-deck explorers. Earlier showrooms and existing builds are preserved.

Launch with `./dev.ps1`, enable cheats in a development world, and obtain the block:

```mcfunction
/give @s nullspace:pad_computer
```

Build a rectangle of grass blocks, dirt, or any mixture (up to 32 x 32) with a complete one-block cobblestone border at the same elevation. Put the computer on the border with its observer-face texture pointing inward. Right-click it, inspect the pad, then use the arrow keys to select Name, type a unique name, then select Register and press Enter. Grass turning into dirt under a ship is allowed and does not invalidate its registered pad. Inspection briefly marks the footprint and the top of the 32-block build volume with particles.

Register stores the **pad destination**. Opening engineering can establish a stable prelaunch ship identity; upgrading opts that ship into powered systems. Enter a pad name first, then use Register pad. The transferable block snapshot is captured when you launch from the ship controller or ship computer. Reopening a pad computer restores its saved name.

Registrations persist in `nullspace-pads.json` inside the world save. Manual edits require `/nullspace reload-pads` or a server restart. Destination status is inspected in bounded batches and may initially show UNKNOWN. Renaming the same computer preserves its pad ID. Interactions require proximity to a real pad computer; discovery is bounded and never force-loads chunks. The ship computer separately checks the hull, equipment, occupants, and full launch-column clearance.

Obtain ship equipment with `/give @s nullspace:ship_computer` and `/give @s nullspace:engine`. Put exactly one ship computer or ship controller facing into a sealed standing-height cabin, and at least one engine with its observer face/exhaust pointing into clear exterior air. The ship needs its own floor and a complete, closed door with solid blocks below, above, and beside both halves. Glass blocks seal; panes, slabs, stairs, and leaves do not. Keep every part within the pad and its 32-block build height. Unconnected blocks or any obstruction above the pad up to the world ceiling prevent launch.

Board, close all captured doors (including interior doors), right-click the ship computer, and select Inspect then Launch. The loading screen stays up through client readiness. In space, edit the ordinary stationary ship blocks and use its containers normally. Inspect reports engines, sealed compartments, air-source status, and cargo readiness; failures highlight the relevant position/leak path. Stair routes and elevation changes are validated with player collision/headroom. Select Destinations to list READY/OCCUPIED/UNAVAILABLE/UNKNOWN pads; edit Destination and choose Land to return. The departure pad is selected automatically, including after a rename. Landing preserves orientation, checks the current ship and destination, and carries your edits and cargo back.

For EVA, equip all four `nullspace:space_helmet`, `nullspace:space_chestplate`, `nullspace:space_leggings`, and `nullspace:space_boots` items. They currently reuse iron-armor visuals. The automatic tether reaches **72 blocks** from the computer and supplies air only while the room in front of that computer remains sealed. Suit flight activates automatically outside a breathable cabin and turns off when you reenter one; no double-tap is needed. Sealed cabins have normal gravity; a breached cabin also activates suit flight. These are the legacy rules. Upgraded ships use finite room air, powered supplies, and tether consumption; portable oxygen tanks remain future work; generators now accept furnace fuel. Opening a direct exterior door vents its room; a manually operated two-door airlock protects a separate cabin. Missing suit pieces or a vented oxygen source consume air and then inflict vacuum damage, even with Water Breathing or full food. Creative and spectator are exempt from exposure. Space beds do not set spawn; death uses your normal planetary respawn.

Containers supported for travel: chests, barrels, and shulker boxes; signs are also supported. Contained mobs and dropped items travel with UUID/state intact, including elapsed creeper fuses. Other block entities, active contraptions, scheduled block ticks, fluids, falling blocks, TNT, vehicles, mounts, and leashes fail preflight. Build placement in space stays inside the ship's 32-cube allocation. All named pads are public in this first version.

Ship metadata and pending reconnect positions live in the save's `nullspace/ships.nbt`; durable transfer records live in `nullspace/transfers/`. Interrupted uncommitted moves roll back before normal play resumes; committed moves remain at their destination. Inventories stay with their players. Transfers are serialized per server, with readiness timeouts and temporary chunk tickets. The implementation plan and verification notes are in [docs/ship-and-space-plan.md](docs/ship-and-space-plan.md).

Powered systems and the scene renderer are described in [ship systems, power, and remote viewports](docs/ship-systems-and-viewports.md), including current implementation limits. Use `/nullspace lab systems` for plot 23 or `/nullspace lab plot 24` for its target ship. These powered spheres have one Ship Controller for travel, weapons and Advanced power/air/door commands, and live camera monitors. Engineering has arrow-key actions and a text command field (`help`, `power cameras 2`, `doors list`, `doors all close`). A ready Ship Controller initializes ship systems automatically. Right-click machinery for native fuel/upgrade slots and live controls; detailed terminals are under Advanced. Each Engine, Generator, Battery and Scanner accepts three universal System Upgrade items. Generators share real furnace fuel with engines and support top/side hopper insertion and bottom container-return extraction. Thin wall monitors join in rectangles and use a named camera picker. See the [editable UI reference](docs/ship-block-ui-design.md). Space contains a shared simulated scene with stars, finite bodies, and other real ships.

[Space stations and automatic docking](docs/stations.md) are available from the ship controller. Use **Find Waypoint**, select a port as the **Destination**, then press **Dock**. New preset ships include a door on one Docking Base. After mating, open the door and walk through to board. Build your own station directly in space with a Station Core and room kits; ordinary bulkhead doors form the graph used by room assembly and route diagnostics. Existing saved ships are preserved.

After dying in space, use a **Pad Computer**, choose **Recall ships**, then **Recall selected ship** to recover your empty ship to a clear registered pad. Recovery preserves cargo and damage and needs no ship fuel. See [ship recall](docs/ship-recall.md).

## Prepared test world and text terminals

Run `.\playtest.ps1` to create/open `NullSpace-Dev` directly. The first launch prepares three 8 x 8 pads: a valid pad at x=0, a broken border at x=16, and a valid mixed grass/dirt pad at x=32. You start in creative mode beside the first computer with computers, blocks, glass, doors, levers, and torches. The world and your edits persist between launches; fixtures are seeded once. Ordinary `dev.ps1` still opens the normal game menus.

Computers use an 80-column, 25-row text terminal. Up/Down selects actions (Inspect/Name/Register for pads; Inspect/Launch/Destination/Land/Destinations for ships); type while Name or Destination is selected, Left/Right moves its cursor, Backspace/Delete edits, and Enter runs an action (or advances from Name to Register). Page Up/Down scrolls long results. Escape closes the terminal. Clicking an action also runs it; clicking Name or Destination focuses its text field. Blank required fields show an error and focus the field. Home/End moves to the start/end of the text. No mouse is required. The character grid scales to the window and does not pause the server.

The prepared-world launcher is gated to development mode and its dedicated save name. It does not seed normal worlds. An additional per-player supply marker grants a ship computer, engine, and full suit on the next launch. Existing saves receive supplies without rebuilding plots or changing your ship. Full inventories drop the supplies beside you.

## Walking ship test gallery

Run **`.\lab.ps1`** for `NullSpace-Lab`, then use **`/nullspace lab`** to visit the expanded gallery. New saves start at its entrance; existing saves gain it on their next launch while preserving your position and edits. `.\playtest.ps1` also prepares it without replacing plot 1.

The gallery has **eight ready ships, four repair examples, and eight registered blank pads**, with labels, lit walkways, entry stairs, and supply barrels. Ready ships cover glass hulls, cargo, an airlock, an upper-deck controller, slabs, twin engines, and button-operated iron doors. Walk in, close the doors, right-click the ship computer, Inspect, then Launch. Blank pads are 12 x 12 with 32-block build height; use them for landing or your own builds.

**Plot 21 is Meridian**, a spherical **32 x 32 x 32** ship with five decks, ten separately sealed rooms, an entry airlock, internal stairs, four engines, and a bridge on deck 3. Use **`/nullspace lab sphere`** to visit it. Plot 22 is an empty registered **32 x 32** pad for landing the sphere or building another large ship. Both are added beside the original gallery on the next lab launch.

`/nullspace lab plot <1-24>` visits a numbered plot. `/nullspace lab reference` boards the original standalone reference ship, which remains intact. Generation runs once and preserves repairs, custom builds, renamed pads, containers, and departed ships. The command supplies missing suit pieces and food; entrance barrels hold building equipment. Use `/gamemode survival` to test hazards; creative is immune.

See [the gallery map and plot guide](docs/test-gallery.md) for every scenario.

**`/nullspace lab recipes`** visits twenty-eight labeled recipe chests in newly generated galleries (existing galleries retain their layout). Each chest shows a 3 x 3 ingredient grid on the left and its output on the right, with a crafting table alongside. Samples can be taken; returning preserves your edits. The annex is added on the next prepared-world launch. All ship parts and suit pieces now have native shaped recipes and recipe-book unlocks, including machine casings, circuit boards, and a computer base used to craft specialized controllers. See [ship recipes and material costs](docs/ship-recipes.md).

**`/nullspace guide`** gives the [in-game field guide](docs/in-game-guide.md), a written book with clickable contents and all 28 recipes shown as real item icons. Hover for names; click crafted parts to follow their recipes. Survival recipe: a normal book above a copper ingot. Right-click to read; lecterns are supported.

## Character terminal foundation

Computers use a reusable 80 x 25 cell buffer with per-cell colors, cursor addressing, streamed ANSI output, and a display/input device API. Pad and equipment menus use that device. The ship controller has one searchable Destination picker shared with scanners and separate Launch, Jump, Land, Dock and Undock buttons. See [the terminal protocol and extension API](docs/terminal.md).

Ships support [third-person cameras, local piloting, and navigation](docs/ship-navigation.md). Use **Pilot** or **Ship camera** at the controller. W/S thrust, A/D yaw, Up/Down pitch, Q/E roll, Space/Ctrl lift, R brakes, and mouse drag/wheel orbits and zooms. Select a region, pad or port once as the **Destination**, then Launch, Jump, Land or Dock as needed. Jumping keeps the final pad or port selected.


## Pad previews and hatches

Pad computers work from any side or corner of the cobblestone ring, regardless of facing. Their **Pad preview** and **Ship preview** actions independently highlight blocks and fade over 30 seconds. Framed trapdoor hatches support pressure, ladder exits, and engineering door groups. Furnaces, blast furnaces, and smokers carry their contents and cooking state through travel. See [construction and diagnostic details](docs/pad-previews-and-hatches.md).


## Ship block compatibility

See [supported blocks, conditions, and exclusions](docs/ship-block-support.md), plus the [complete vanilla block catalog](docs/ship-block-catalog.md). Ships now support workshop/storage machinery, decoration, stored bees, onboard soil, settled redstone and stationary pistons. Pending actions, fluids, and world-bound special blocks still fail preflight with a specific explanation.

Computer scripts, flat planetary surfaces, scanners, spatial air and graphics controls are documented in [the implementation guide](docs/planet-computers-and-rendering.md). See [NullOS](docs/nullos.md) for shell commands, language syntax, native ship APIs and isolation limits.
