# Repository review before the World 5 reset

Review focus: whether the current repository supports a coherent space-start outing, including supplies, travel, investigation, docking, return, persistence and recovery. This review covered subsystem boundaries and player-facing paths across the repository, with repository-wide syntax, content and regression checks. It was not limited to the new journey files.

## Findings fixed

| Finding | Player impact | Change |
| --- | --- | --- |
| Space start checked the former `playerdata` directory. Minecraft 26.3 uses `players/data`. | Enabling space start could treat an existing player as a new arrival. | Use native `LevelResource` player-data paths and preserve both current/backup files and pre-migration layouts. Native tests cover current files and backups. |
| Direct jump calls could retain an unrelated controller destination. | The paid route and the destination shown after resume could disagree. | Normalize direct jumps to the requested region while retaining matching final pads/ports. Resume also handles older journey records without a saved selection. |
| Destination selection remained editable during cruise; remote encounter ports appeared as ordinary jump targets. | A player could get conflicting destinations or choose a port that could only be reached through a route signal. | Keep paid destinations fixed during cruise, show readable denials, and expose encounter ports locally after investigation. Resume explains missing fuel/power. |
| Encounter registration failures could escape the server tick. | A storage/save failure during automatic generation could stop the server. | Retain the saved encounter plan, log the affected address and retry after 60 seconds. A fault-injection test verifies failure containment and backoff. |
| The starting guide stopped at choosing a destination. | The next actions for timed travel, useful signals and returning were missing. | Add short ship-start pages covering Jump, Investigate, Dock, collect, return and Resume. Update the planetary travel chapter. |
| `dev.ps1` assumed World 5 already existed and had no cohesive reset flow. | A fresh save could fail quick-play or send the player through unrelated developer fixtures. | Prepare a native space-start world with the real ship picker, stage and validate it, then back up and replace only the closed World 5. Explicit resets preserve prior worlds. |

The prepared profile uses the Halcyon Horizon, tier III machine upgrades, normal starter food/tools/suit, a selected resource encounter route, and an empty Home recovery pad on breathable Terra. Recovery-pad preparation generates terrain before querying its height and verifies the complete landing column. It does not add a shipyard, lab, showcase or developer room to the outing.

## Areas reviewed

| Area | Boundaries checked | Verification used |
| --- | --- | --- |
| Startup and development launches | Stable Player451 identity; native world ownership; first-arrival checks; world-scoped settings; explicit reset and backup paths. | Native startup tests, ship-picker GUI save/reopen, reset lock/path rejection checks and ordinary client startup probe. |
| Ship topology and physical travel | Connected blocks across decks, hull pressure, source/destination reservations, real entities/inventories and durable transfer journals. | Preflight, native round trips, concurrent actions, recovery tests and graphical launch/landing cases. |
| Fuel, power, air and upgrades | Furnace fuel and returned containers, shared reserves, hopper rules, upgrade slots, power allocation, stored air and equipment feedback. | Pure systems tests, real inventory/recipe tests and equipment GUI tests. |
| Navigation and encounters | One final destination, per-vessel journeys, paid resume, offline pause, seeded sites, collision separation and recoverable registration failure. | Journey-plan tests, multiplayer fixture cases, real docking/looting/resume and controller clicks. |
| Stations, doors and docking | Public and salvage access, builder permissions, automatic maneuvers, sealed entrances, physical collars and blackout traversal. | Native station/template/graph/access tests and graphical boarding scenarios. |
| Recovery and ownership | Recall authorization, unavailable crew, preserved ship identity/cargo and cancellation of recovered journeys. | Recall and independent-action tests; prepared Home pad and forced recovery spawn. |
| Shipyard and manufacturer templates | Fifteen preset definitions, room layouts, airlocks, equipment and resumable delivery. | Manufacturer, preset, shipyard and dismantling cases. |
| Controllers, equipment menus and terminals | Session/proximity validation, authoritative action dispatch, visible reasons, direct actions and bounded payloads. | Terminal unit tests, native authorization tests and graphical input. |
| Computers and scripting | Virtual filesystem, explicit native capabilities, execution/storage budgets, persistence and fair scheduling. | Computer, compiler, malformed-input, integration and lifecycle cases. No guest host-process/network capability was found in the reviewed interfaces. |
| Rendering and scene/terrain protocols | Client-only ownership, bounded scene/visual data, private inventory exclusion, resource cleanup, cameras and monitors. | Scene/protocol tests, resource reload/lifetime tests and graphical rendering cases. |
| Catalogs, crafting, guide and assets | Active/retired IDs, wood tags, generated wrappers, dimension definitions, texture scale and model shape. | JSON parse sweep, recipe tests, guide tests and `verifyContentAssets`. |
| Scripts, CI and test storage | Pinned toolchain, PowerShell syntax, isolated launches, exact completion inventories, cleanup boundaries and preserved player/recovery worlds. | Script parser/safety checks, existing harness checks, source fingerprints, JAR snapshots and cleanup receipts. |

The inventory snapshot is `build/repo-review-inventory.json`: 820 source/script/document/CI files at capture time, including 318 Java files, 268 JSON files and 17 main-code package areas. Generated caches and runtime worlds were excluded from that inventory. No invalid JSON was found. The regression receipts retain the exact compiled inputs and test inventory for the final build.

## Remaining design and validation work

- The basic-ship opening and vanilla crafting progression still need balance work. This rich Horizon profile is for exercising the shared systems and exploration story.
- Public Waypoint/Manufacturer stations are not shops or guaranteed supply stops. Useful food/fuel/materials currently come from encounters, ordinary harvesting and crafting. Active NPC traffic, trade and combat AI remain outside this pass.
- Generated planetary surfaces still use the current flat templates. Named systems and first landing pads do not establish a complete resource-distribution design.
- Terra recovery in this dev profile is deliberately provisioned. The universal space-start death/onboarding policy still needs a design decision; ordinary players can build/register their own pads and use recall.
- Normal save/reload and physical transfer recovery are covered. A process-kill matrix for the initial FTL fuel debit has not been established; the current journey tests do not prove power-loss atomicity for fuel across block-entity and metadata writes.
- Preflight still uses typed block failures in the existing scan API. A broad migration of all expected validation failures to result values is separate from the contained encounter IO failures and readable controller denials fixed here.

The [World 5 walkthrough](dev-world5-story.md) is the concrete player story for this pass. [Verification and installation evidence](world5-story-verification.json) records the passing checks, prepared identity, installed save and backup location.
