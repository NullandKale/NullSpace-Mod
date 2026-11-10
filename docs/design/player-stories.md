# Player stories: two ways to start

Features here are judged by whether a player can do something they care about, and not by whether a system exists. This document records the experience the game is aiming at, as player stories with "done when" conditions, and how close each is in the current build. It is the lens for deciding what to build or fix next, and it is deliberately honest that a passing structural test does not show a ship, station or screen is enjoyable. Review the actual build and the player's actions.

## The loop

Both starts lead into one game:

**Explore → find food, fuel and materials → bring them aboard → maintain or improve the ship → explore farther.**

Food keeps the player going, fuel keeps the ship going, and materials let them build, repair and improve. This loop should be well established before the game adds more resource types, quests, currencies or station templates.

| Start | Starting situation | First motivation |
| --- | --- | --- |
| Ship start | Begins in orbit aboard a working ship (see [starting in space](space-start.md)) | Explore to find fuel, food and resources, keep the ship going and improve it |
| Vanilla start | Begins in an ordinary new Minecraft world | Build a first ship, launch it, and begin exploring space |

## A. Starting aboard a ship

**A1. I understand the small ship I have been given.** When I start aboard, I want to understand where I am, what I have and how to take it somewhere. The ship is basic enough to understand by looking around, and my first interaction shows it is my working home with limited supplies.
*Done when:* I can find the controls, food and fuel and know what each is for, and can name a reasonable next action without reading every machine screen.
*Now:* the player starts in a sealed cabin by the helm with bread, coal, tools, a suit and the field guide, over a planet they can land on. The supplies allow a first expedition and some mistakes and are not an immediate rescue emergency. Still open: whether a new player notices the right thing first, which needs a fresh playtest without developer knowledge.

**A2. I choose my first useful expedition.** Because my supplies are limited, I want somewhere reachable that offers something I need.
*Done when:* I can form a concrete intention (food, fuel, material for a change) and have enough information to judge the trip and a real chance of getting those things on arrival.
*Now:* during a timed jump a signal names a growing station, damaged freighter, salvage ship or drifting cargo. Investigating pauses the trip, the player docks, collects real food, fuel and materials, returns and resumes (see [FTL journeys](ftl-journeys.md)). Supplies are normal items in containers of the matching room ([rooms and resources](ship-rooms-and-resources.md)), and the growing station has real crops. Whether a new player understands the signal still needs a playtest.

**A3. I return with something that matters.** After exploring, I bring something back that makes my situation better.
*Done when:* I can get back aboard, store my finds and use them for food, fuel or a chosen improvement, and I understand what the trip achieved and can pick another goal. The payoff comes from what I found and did. It needs no reward screen or invented currency.

## B. Starting in vanilla Minecraft

**B1. Building a ship becomes a goal.** While playing, I discover that I can build a ship and understand enough to start working toward one.
*Done when:* I can see a path from familiar gathering and crafting to a small working ship, and what reaching space would let me do.
*Open:* how the possibility is first met. A long tutorial, a free book or a generated structure should not be assumed to be the answer before walking through the opening. The guide today begins with pad registration and ship components, which does not establish this start.

**B2. I build my first ship and understand why it works.** My work turns into a ship that feels like mine.
*Done when:* I make progress in understandable steps and not a large unexplained checklist, choose the hull and interior from familiar materials, and when something fails I can find the part and fix it specifically.
*Now:* [recipes](ship-recipes.md) and the [first-ship walkthrough](../player/first-ship-walkthrough.md) give the materials, a block layout and the placement order. The free [Ship Beacon](shipyard.md) can bypass building a first ship, and its role in this start needs an explicit decision.

**B3. My first launch makes the building worthwhile.** When I launch the ship I built, I recognize that it works, enjoy reaching space and know what to do next.
*Done when:* departure and arrival are clear, I can orient myself, and I know how to get back. My ship and belongings stay mine throughout. The player then joins the same loop as the ship start without repeating an unrelated introduction.

## Stories after the starts converge

| Story | What the player needs to understand | What proves it works |
| --- | --- | --- |
| I plan an outing | Where I want to go, what I can learn about it, whether I have enough supplies, how to return | I can choose a sensible trip without commands or a developer interpreting the screen |
| I explore and gather | How to leave the ship safely, interact with the place and bring things back | Exploration produces real supplies, materials, knowledge or a place I want to build |
| I keep myself and my ship running | Where food and fuel go, what they support, what a shortage means | Using a supply has a clear result, and a shortage points to an action |
| I make the ship more useful | What a change improves and what it costs | I choose an improvement for a reason and notice it in play |
| I recover from trouble | What went wrong, what I can still do, how to retrieve my ship or belongings | A mistake gives me a way forward ([recall](ship-recall.md)) |
| I play alongside other pilots | I use my ship while friends use theirs | [Independent ship actions](independent-ship-actions.md): ships travel, dock and recall at once, with clear local reservations for shared pads and berths |

Travel works like this today: browse destinations on the Scanner or the controller list, select one, and press **Engage**, which previews and runs every phase (the individual **Launch**, **Jump**, **Land**, **Dock** and **Undock** buttons remain under Manual steps; see [ship navigation](ship-navigation.md)). Local travel needs no Scanner, and a powered stock Scanner shows more destinations.

## Why a station would matter

A station belongs in the loop when visiting it lets the player do something they care about, such as supplies, work on the ship, or a place to explore. The question for any station is: **what can I do here that makes this trip worthwhile?** Decide that before making more variants. Layout, equipment, signs and interactions follow from the purpose, and manufacturer style gives it character.

Public stations allow ordinary door use and crafting but protect storage and machinery from visitors ([stations](stations.md)). They should not be presented as fuel or food stops until those services exist.

## Where the build falls short

- The whole opening has not been playtested with a new player without timer acceleration or knowledge of the controls.
- Landable surfaces use real terrain, but a named system does not by itself create useful resource distribution. Planet resources and surface sites are not built (see [procedural generation](procedural-generation.md#status)).
- Dev worlds supply equipment and builds, and testing with those shortcuts does not prove the normal starts work. See the [World 5 Horizon playtest](../development/dev-world5-story.md).
- Free ship delivery is implemented, and its place in normal progression is unresolved.
- Waypoint and the Manufacturer Exchange do not yet have distinct jobs or reasons to visit.

## How a story gets refined

Walk **A1 → A2 → A3** first, to examine the shared exploration loop before the longer lead-in of building a ship, then **B1 → B2 → B3** from a genuinely fresh survival world. For each story: agree on the starting state and the wanted outcome, play it, record the first gap, make the smallest useful change and play it again. Do not skip the part under evaluation with dev commands.

Give feedback a job: fuel catches, a machine starts, a launch completes, clamps engage, a door becomes safe to cross. Let the real state change drive the visible response, sound and wording, and avoid extra effects or messages that do not help the player understand or enjoy that moment. Record what the player expected, what happened and the smallest change to try, for each moment: entering the game, choosing a first goal, making first progress, using an unfamiliar control, needing help, getting the first payoff, and choosing what to do next.

Related reviews: [recovery, ship and station design](recovery-stations-and-ship-design.md) (the November 5 playtest priorities), [UI refinement](ui-refinement-design.md) and the [UI audit](ui-audit-notes.md).
