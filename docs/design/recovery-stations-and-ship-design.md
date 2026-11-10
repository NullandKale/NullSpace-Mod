# Recovery, respawn and the station experience

A player should always have a way forward, should understand what their controls will do, and should have a reason to care about a ship or station beyond getting its machinery to pass validation. This document is the design behind three things that came out of the November 5 playtest: **never being stranded** (rescue and respawn aboard a ship), **keeping ships safe** (storage and titles), and **making a station worth visiting and building**. It states what is built, what changed from the original proposal, and what is still open. How storage, deploy and titles work in detail is in [ship ownership, storage and recall](ship-recall.md), and the station mechanics are in [stations](stations.md).

## Decisions

| Decision | Status |
| --- | --- |
| Emergency rescue keeps the player's carried inventory. The ship and its cargo stay behind | Built |
| A bed aboard a ship sets a respawn aboard that ship | Built |
| A physical **Ship Title** represents a ship and can be handed to another player. The server holds the vessel, and the paper authorizes it | Built |
| A guaranteed public recovery area on breathable Terra (**Terra Harbor**) | Built |
| Storing a ship is the normal, reversible action. Scrapping is a separate, owner-only, confirmed action | Built |
| Ship and station appearance, interiors and useful activities deserve equal attention | Agreed. Ongoing |
| One excellent starter ship and one useful service station before expanding the generator | Ships are generated; the station is partly done (see below) |
| Choose a place and press **Engage**: launch, undock, travel, dock and land are phases of one trip | Built (`CourseService`). The individual buttons remain under **Manual steps** ([ship navigation](ship-navigation.md)) |
| The Scanner owns the full destination browser and the controller shows the chosen destination | Built as agreed: both share one destination selection |
| A starter-dock flow for building a station (ghost preview, one first useful section) | Not built; station building uses room kits ([stations](stations.md#build-your-own-station)) |

## What was wrong

- Recall needed a registered, clear, empty pad. A docked ship or offline crew could block it. That is useful transport with restrictions, and could not be the only way to recover.
- Space beds did not set a spawn point, and ordinary bed bindings did not follow a ship that moved. A bedroom gave no security.
- Starting a station produced an exposed platform and then asked the player to understand tiles, kits and expansion before getting a useful room.
- Station and ship interiors often read as boxes with equipment inserted afterward, and the reference station was a repeated square perimeter.
- A public station protected its storage and machinery, so its looks did not tell a visitor what they could use.

## Recovery must not depend on the thing that failed

Three actions are different and have different names:

| Action | Moves | Leaves | Needs |
| --- | --- | --- | --- |
| **Rescue** | The living player and what they carry, to Terra Harbor | Their ship, its cargo and other players | No ship power, fuel, controller, pad, scanner or money |
| **Store / Recall** | The same owned ship, into storage or onto a pad | Other players and unrelated builds | Ownership, and a safe transfer. No working engines, fuel or empty pad needed to *store* |
| **Deploy** | A stored ship, onto a clear compatible pad | Other stored ships | Its title (unless the owner), and a reserved destination that fits |

### Rescue

Rescue is reachable from the pause menu (the **Rescue** screen) and with `/nullspace rescue`, `/nullspace rescue cancel`, so it works after the controller is destroyed, after the player falls outside the hull, and when every destination is unavailable. Nothing the player has unlocked hides it.

The request says: *"Rescue takes you to Terra Harbor. You keep what you are carrying. Your ship and its cargo stay here."* A three-second countdown (60 ticks) shows on screen and can be cancelled. It persists across a reconnect, and it creates no second inventory (the rescue is a saved *intent* about where the player will be, never a copy of their things). A dead player cannot call it. If a boarding or physical transfer is in progress the rescue waits and never splits it.

On arrival the player is removed from every ship's crew list and any stale arrival anchor, set down in Terra Harbor's arrival hall with a full air supply and flight cleared, and given ten seconds (200 ticks) of protection against an immediate fall or suffocation loop. Rescue does not restore items lost in an earlier death or grant fuel, charge or upgrades. The ship stays listed in **My ships** with a recovery action. If the arrival area is occupied it retries, because an empty fuel locker or a blocked room must never make rescue unavailable.

### Terra Harbor

A small public arrival hall and ship pad beside it, on breathable Terra (`TerraHarbor`). It keeps clear of registered pads and surface ships, relocating or rebuilding an older layout, and offers ordinary surface resources and a place to recover by playing Minecraft, so a player with nothing can get going again. It is also the fallback for crew whose ship moved while they were offline.

## Respawn aboard a ship

Right-clicking a bed aboard a ship sets **Respawn aboard this ship** (`ShipRespawns`). Space does not need to skip the night for the bed to serve as a home. The binding is the vessel's stable id plus the bed's ship-local position and a *placement identity*, never an absolute position in the hidden space allocation, so a ship that launches, lands or moves keeps its bed, and a storage slot reused by another ship can never redirect someone's respawn. When a bed is placed again at the same spot it is a new placement, which an old binding does not match.

At respawn the binding is resolved against the current ship and its blocks:

| Situation | Result |
| --- | --- |
| The same bed exists and has a safe, breathable standing spot | Respawn at that bed aboard the same ship |
| The ship is stored | "Your ship is in storage": respawn at Terra Harbor |
| The ship is between locations | "Your ship is between locations": Terra Harbor |
| The ship no longer exists, or the player lost access | Terra Harbor with the reason |
| The bed was broken or replaced, or its spot is unsafe | "Your ship bed is missing or unsafe": Terra Harbor |
| The player never set a ship bed | Ordinary vanilla respawn |

The reason is always said. Death uses the normal inventory and drop rules: it does not issue another starter ship, restore old cargo or erase the right to recover an existing ship ([ship ownership](ship-recall.md)). Beds at a station follow its access rules, so a bed cannot be set where the player is not allowed.

## Titles, My ships and freeing a pad

The title, handover and storage rules are in [ship ownership, storage and recall](ship-recall.md). In short: **My ships** at the Pad Computer lists owned ships and ships the player may recall, with **Deploy** and **Store**. A ship on the pad can be stored at once, with its title handed to the owner, and an empty generator or a damaged helm never stops a wreck being stored. An abandoned ship (owner and crew away a full day) can be cleared by anyone into its owner's storage without being taken. The owner can also scrap a ship permanently: **Scrap** shows the blocks that would be captured, asks for a named confirmation, and is never labeled Sell, because there is nothing to sell to. A stored ship is never deleted by a restart or a full pad.

The reasoning for a physical title was that a ship is valuable and players trade. The server keeps the one real vessel and the paper only authorizes it (with a number that advances on every change), so copying or losing a paper never duplicates or destroys a ship, and a lost one can be replaced by the registered owner. Whether finding a dropped title should let a stranger claim the ship was settled conservatively: a title must first be **signed over** by the owner before anyone else can take ownership.

## Controls around intentions

The principles for the controller are in [ship systems](ship-systems-redesign.md#principles-for-the-controls) and the destination model in [ship navigation](ship-navigation.md). The parts of the original proposal that shaped the current design:

- **The Scanner is the full map.** It browses destinations by geography (stars, systems, a body's orbit and surface, stations, ships), and one station is one entry showing berths and services. It sets the ship's destination directly and starts nothing. The controller shows the same saved destination, so there is no marking step or second Scanner view inside the controller. Public and private visibility are preserved, and "all destinations" never reveals another player's private storage or undiscovered encounters.
- **Built-in travel within a system needs no Scanner** (planets, the home pad and saved places). A powered Scanner supplies the wider catalog and interstellar destinations, and upgrades improve range and detection but never gate ordinary local travel. A place the player has already charted stays visible when the Scanner is off, marked as cached. Knowing a place and being able to reach it are different states, and the screen explains both.
- **Faults stay visible and name the first thing to do** (for example fuel pointing to the generator, not an unrelated screen).
- **Dropping out of FTL to investigate a vessel** continues into docking unless the player cancels, and after an investigated encounter the controller presents the saved journey destination again, so resuming is one step ([FTL journeys](ftl-journeys.md)).
- **Starting a station is an in-world action**: in space, hold a Station Core and Shift-right-click empty space or a non-interactive block at the intended site (`StationDeployment`).

### The Station Core

A station's core screen should explain this place and let builders extend it, and should not look like a ship helm with broken equipment. The design is a header (name, access, power, air, berths free), a map of the real rooms and doors with names and berth locations, and areas for ships and berths, services, and people and access. Visitors get a simple map and service list, while construction and permission controls belong to builders. Repairs still happen at the real block, and signs and routes should let a visitor find a workshop, fuel locker or their ship without browsing a diagnostic graph. Today the core offers named fields for renaming, inviting builders and access, plus the room build controls ([the station reference](station-reference.md)); a room-map view is not built.

### Building a station that produces a useful first place

The design intent is that the first section includes a dock, a walking room, the core, basic air and power provision and a small work and storage area, with its cost (including initial fuel and air) stated openly, and not an exposed platform followed by a second unexplained step. The player would see a ghost of the whole section and its materials before committing, a failed build would not eat the resources or leave duplicate structures, and the ship would dock automatically when the section passes its checks. Grid coordinates and 32-block render sections are implementation details that the player should not need. Hand building remains supported, and a diagram never overrides a wall someone really removed. The current build needs room kits and a tile choice, as described in [stations](stations.md#build-your-own-station), and the ghost preview and starter-dock flow remain future work.

## Authored builds

One generated starter ship and one useful service station should be excellent before the whole catalog is expanded. A good build has an outline recognizable from outside, rooms of different sizes joined by an obvious route, furnishing that tells you what each room is for, and signs and equipment placed where a visitor would look. Ship interiors are covered by [the ship generator](ship-generator.md) and [rooms and resources](ship-rooms-and-resources.md), and the station tiles by [station pieces](station-modules-and-systems.md). The remaining question is the station's purpose: what can a visitor do here that makes the trip worthwhile, and which services (fuel, food, repairs) it should actually provide ([player stories](player-stories.md#why-a-station-would-matter)).

## Where this is tested

`PlayerRescueGameTest` covers rescue (the countdown, cancel, reconnect, arrival and protection). Storage, titles and abandonment are covered by `ShipStorageGameTest` and `ShipStorageClientGameTest`.
