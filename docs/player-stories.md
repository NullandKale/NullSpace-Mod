# Player stories: two ways to start

Working design document. These stories describe the experience we want to refine. They are not claims that the complete progression is implemented.

The [next recovery, ship and station design](recovery-stations-and-ship-design.md) develops the November 5 playtest priorities: keeping a stranded player in the game, beds aboard moving ships, storing/recovering ships, clearer controllers, useful station construction and better authored builds. Its accepted decisions and open questions take precedence over older proposals.

The current [World 5 Horizon playtest](dev-world5-story.md) exercises the shared exploration story with a fully upgraded ship, a selected freighter route and a Terra recovery pad. The [repository review](repo-review-2026-11-04.md) records what was checked and fixed before that playtest. The basic-ship and vanilla starts below remain the broader progression targets.

The [space-start menu and server option](space-start.md) implement the entry point for the ship start: choose a ship while creating a world, or enable a starting ship for new server players. [Timed journeys and encounters](ftl-journeys.md) add a first resource loop: optional growing stations, damaged freighters and salvage ships, with real supplies and docking. The stories below still guide refinement of discovery, balance and onboarding.

The [first-ship walkthrough](first-ship-walkthrough.md) expands B2/B3 into current material costs, an exact block layout, placement order and the buttons used for a first launch and return. Use that level of detail for subsequent stories.

[Ship rooms and resources](ship-rooms-and-resources.md) adds recognizable living and working spaces to the manufacturer fleet and puts encounter supplies in the rooms where players would look for them. Its test story follows finding fuel, food and parts, bringing them home, and revisiting a depleted ship.

The [UI usability review](ui-usability-audit.md) audits the controls encountered along these stories, with a linked inventory of the current clicks and proposed changes.

The [UI fix design](ui-refinement-design.md) turns that review into shared interaction rules, screen designs and ordered implementation slices. Its first UI test is registering a pad, loading fuel, launching and returning Home, including fuel and engine-exit failures. The first journey encounters now provide resource opportunities; their onboarding and balance still need a fresh-player playtest.

## What we have agreed

| Start | Starting situation | First motivation |
| --- | --- | --- |
| Ship start | The player begins somewhere in space aboard the simplest basic ship. | Explore to find fuel, food and resources. Keep the ship going and improve it. |
| Vanilla start | The player begins in an ordinary new Minecraft world. | Build their own first ship, launch it and begin exploring space. |

Both starts lead into the same game:

**Explore -> find food, fuel and materials -> bring them aboard -> maintain or improve the ship -> explore farther.**

Food supports the player. Fuel supports operating the ship. Materials let the player build, repair and improve things. We should establish this loop before deciding that it needs more resource types, quests, currencies or station templates.

**Your notes:**

## A. Starting aboard a ship

### A1. I understand the small ship I have been given

**Player story:** When I start aboard my ship, I want to understand where I am, what I have and how to take it somewhere.

The ship should be basic enough to understand by looking around. The first interaction should make it clear that this is my working home, with limited supplies and places I can explore.

**Done when:**

- I can find the controls, food and fuel, and understand what each is for.
- I can identify a reasonable next action without reading every machine screen or getting a developer explanation.

**Still to decide:** The starting location, exact provisions and which capabilities make a ship the simplest usable one. It needs a safe way to leave and return. Its supplies must allow a first expedition and some mistakes, rather than making the opening an immediate rescue emergency.

**Your notes:**

### A2. I choose my first useful expedition

**Player story:** Because my supplies are limited, I want to find somewhere reachable that offers something I need or want.

**Done when:**

- I can form a concrete intention such as finding food, collecting fuel or getting material for a change to my ship.
- I have enough information to judge the trip and a real opportunity to obtain those things when I arrive.

**First implemented opportunity:** During a timed jump, a signal identifies a growing station, damaged freighter or salvage ship. Investigate pauses the trip; the player docks, collects food/fuel/materials, returns and resumes. Supplies are normal items in accessible containers, and the growing station has real crops. We still need to playtest how well a new player understands the signal and judges the risk and fuel needed to investigate.

**Your notes:**

### A3. I return with something that matters

**Player story:** After exploring, I want to bring something back that makes my situation better.

**Done when:** I can get back aboard, store my finds and use them for food, fuel or a chosen improvement. I understand what the trip achieved and can choose another goal.

The payoff should come from what I found and did. It does not need a separate reward screen or an invented upgrade currency.

**Your notes:**

## B. Starting in vanilla Minecraft

### B1. Building a ship becomes a goal I can work toward

**Player story:** While playing Minecraft, I want to discover that I can build a ship and understand enough to begin working toward one.

**Done when:** I can see a path from familiar gathering and crafting to a small working ship. I understand what reaching space would let me do, without needing to learn all the mod's equipment first.

**Still to decide:** How the player first encounters this possibility. Do not assume a long tutorial, a free book or a generated structure is the answer before walking through the opening.

**Your notes:**

### B2. I build my first ship and understand why it works

**Player story:** As I gather and build, I want my work to turn into a ship that feels like mine.

**Done when:**

- I can make useful progress in understandable steps, rather than assembling a large unexplained checklist.
- I can use familiar Minecraft materials and make choices about the hull and interior.
- When something does not work, I can find the affected part and make a specific repair.

**Still to decide:** The smallest first build, its material effort and how the player learns its requirements. The current free Ship Beacon delivery can bypass building a first ship; its role in this start needs an explicit decision.

**Your notes:**

### B3. My first launch makes the building worthwhile

**Player story:** When I launch the ship I built, I want to recognize that it works, appreciate reaching space and understand what I can do next.

**Done when:** Departure and arrival are clear, I can orient myself, and I know how to get back to my surface home. My ship and belongings remain mine throughout the trip.

The player now joins the same exploration, food, fuel and resource loop as the ship start. They should not have to repeat an unrelated introduction.

**Your notes:**

## Shared stories after the starts converge

| Story | What the player needs to understand | What proves it works |
| --- | --- | --- |
| I plan an outing. | Where I want to go, what I can find out about it, whether I have enough supplies and how to return. | I can choose a sensible trip without commands or a developer interpreting the screen. |
| I explore and gather. | How to leave the ship safely, interact with the place and bring useful things back. | Exploration produces real supplies, materials, knowledge or a place I want to build. |
| I keep myself and my ship running. | Where food and fuel go, what they support and what a shortage means. | Using a supply produces a clear result, and a shortage leads to an understandable action. |
| I make the ship more useful to me. | What a change will improve and what it costs. | I can choose an improvement for a reason and notice the result in play. |
| I recover from trouble. | What went wrong, what I can still do and how to retrieve my ship or belongings. | A mistake gives me a way forward. Respawn and recovery need to make sense for both starting situations. |
| I play alongside other pilots. | I can use my ship while friends use theirs. | [Independent ship actions](independent-ship-actions.md) let different ships prepare, travel, dock and recall concurrently; shared ships, pads and berths still have clear local reservations. |

The November 5 navigation direction supersedes the earlier five-button design: browse destinations on the Scanner, select a course at the Ship Controller, then **Engage**. The controller previews and executes the required launch, departure, travel and docking/landing phases. Local travel works without a Scanner; a powered stock Scanner enables interstellar travel. See the [current design proposal](recovery-stations-and-ship-design.md) for map organization and concrete trips. The running implementation still has the earlier controls until this redesign is implemented.

## Why a station would matter

A station belongs in the loop when visiting it helps the player do something they care about. Supplies, work on the ship or exploration are possible purposes; we still need to choose and support them in play.

The question for the first station is: **what can I actually do here that makes this trip worthwhile?** Decide that before making more station variants. Its layout, equipment, signs and interactions should support that purpose. Manufacturer style can then give it character.

Current public stations allow ordinary door interaction and crafting, but protect storage and machinery from visitors. We should not present them as fuel or food stops until those services actually exist.

**Your notes:**

## Where the current build falls short

- Timed journeys now lead to real food, fuel and salvage opportunities. We still need to playtest the whole opening with a new player, without timer acceleration or supplied knowledge of the controls.
- The generated surfaces use flat terrain templates. More named systems do not by themselves establish useful resource distribution or meaningful exploration.
- The guide begins with pad registration and ship components. It does not establish either starting experience.
- Dev worlds can supply equipment and prepared builds. Testing with those shortcuts does not prove the normal starts work.
- Free completed ship delivery is implemented. Its place in normal progression is unresolved.
- Repeating Waypoint and Manufacturer Exchange does not yet give destinations distinct jobs or reasons to visit.

## How we refine this, one step at a time

Recommended first walkthrough: **A1 -> A2 -> A3**. It lets us examine the shared exploration loop before adding the longer lead-in of building a ship. Then walk **B1 -> B2 -> B3** from a genuinely fresh survival world.

For each story, agree on the starting state and wanted outcome, play it, record the first gap, make the smallest useful change and play it again. Do not skip the part being evaluated with dev commands.

Give feedback a job: fuel catches, a machine starts, a launch completes, clamps engage, a door becomes safe to cross. Use the actual state change to drive the visible response, sound and wording. Avoid extra effects or messages that do not help the player understand or enjoy that moment.

| Moment | What the player wanted or expected | What happened | Smallest change to try |
| --- | --- | --- | --- |
| Entered the game | | | |
| Chose a first goal | | | |
| Made first useful progress | | | |
| Used an unfamiliar control | | | |
| Needed help | | | |
| Got the first payoff | | | |
| Chose what to do next | | | |

**Next discussion:** Walk through the first few minutes of the ship start: the ship, its starting provisions, what the player first notices and the first reachable opportunity for useful supplies.
