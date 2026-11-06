# UI clarity pass: proposal

Proposal, November 5. Review of the current screens (latest graphical run: `build/parallel-verification/20261105-191912-049572`) after the player found them still cluttered and hard to parse. It builds on the [interactive UI audit](ui-audit-notes.md) and is chunks 1a–1c of [the delivery plan](playtest-chunks.md). Nothing here is implemented. Labels marked **proposed** need approval before they are applied.

## Why the screens are hard to read

The problems repeat across screens, so fixing them per screen will not work. These are the shared causes:

1. **Everything looks the same.** The terminal screens (Controller, Scanner, Station Core, Pad Computer) use one font, one size and nearly one color. Labels, values, status, instructions and buttons all look alike. Plain text also gets the same shaded bar as buttons, so it is not clear what is clickable.
2. **Too many actions at once.** The Controller shows 12 buttons; the Station Core about 20. Rare actions (Loose blocks, Locate problem, Advanced, Remove build access) have the same weight as the one action that matters (Engage).
3. **Repeated instructions.** The Scanner tells you to "choose a place and Set destination" twice, and keyboard help takes two more lines. The Controller adds "Set distant destinations at the Scanner block" and "Ready: travel and arrival are automatic" to every view.
4. **Status as a run-on sentence.** "Fuel: loaded | Controls: online | Engines: ready | Air here: safe here" has to be read word by word. A fault in the middle of the line does not stand out.
5. **Internal values shown to players.** Station Core "Fuel: 1625506", grid cells "0,0 Empty", pad state "UNKNOWN", generator "Max 8/tick", "Station: 2/2 free".
6. **Equipment screens repeat the same fact.** Air Supply shows "Room air: 99%", then a "Room air: 99 / 100" bar, then a sentence about the leak. The Generator shows "Fuel loaded" and "Supplying ship; surplus charges batteries" beside three meters that already say this. Half of each screen is the player's inventory, even on Air Supply, which has no item slots.

The Ship Delivery Beacon works because it avoids all six: one list on the left, one detail panel on the right, a picture, one main action, and one quiet note at the bottom. It is the reference for this pass.

## Shared rules for every screen

1. **One layout.** Title and a one-line status at the top. The screen's single purpose in the middle. One main action, visually stronger than the rest. A few secondary actions in one row. A reserved message line at the bottom that nothing overlaps.
2. **Color has meaning.** Title: accent blue. Labels: dim gray. Values: white. Good: green. Needs attention: amber. Blocking problem: red. Shaded backgrounds only on things you can click.
3. **At most five visible actions.** Anything rarer goes under one **More** tab (the Controller already has a tab row for this).
4. **Status as short labeled items**, each colored by state, not a sentence: `Fuel ● Controls ● Engines ● Air ●`. A problem item turns amber or red and its fix appears on the message line.
5. **Say each thing once.** Remove instructions the layout already makes obvious. Keep one hint line, only when the player is stuck.
6. **No internal numbers.** Show what a player can act on: "Fuel: about 3 trips" instead of 1625506; room names instead of grid cells.
7. **Equipment screens:** hide the player inventory when the machine has no item slots. One meter per fact; no sentence repeating a meter. **Details** expands in place.

## Ship Controller

Current problems: 12 equal buttons; location, ship name and status on three loose lines; two instruction lines; the "Destination" line is the only highlighted row, so it reads like a button bar.

```text
SHIP CONTROLLER · Test ship
Here       Sol / Terra / Orbit
Going to   Sol / Luna                                  [ Change ]
Fuel ●  Controls ●  Engines ●  Air ●

[ Trip ]  [ Manual steps ]  [ More ]
Local travel -> hold in orbit · needs 2000 fuel
                     [  ENGAGE  ]      [ Stop ]

[ Fly manually ]   [ View ship ]   [ Close all doors ]

Scanner destination selected                              [ Close ]
```

- **More** tab holds Loose blocks, Check ship, Locate problem, Details and Advanced.
- The "set distant destinations at the Scanner" hint appears only when the player opens **Change** and the place they want is not in the local list.

## Scanner

Current problems: two instruction lines plus a keyboard line; every row and category shaded; "UNKNOWN" and "Station: 2/2 free" in the status column; Berths, Previous/Next, Set destination and Settings scattered over four rows.

```text
SCANNER · Stars / Sol / Terra                     [ Back ]
Search [_______________________________]
All   Stations   Ships   Planets & moons   Landing pads

  Terra Manufacturer Exchange    Station      2 berths free
  Terra Waypoint Station         Station      2 berths free
  Terra                          Planet       Orbit
  Terra Landing                  Landing pad  Clear

< 1 / 1 >                  [ SET DESTINATION ]   [ Upgrades ]
Selected: Terra Waypoint Station, in orbit around Terra   [ Close ]
```

- Name first, kind second (people scan for names). Categories are plain text; the selected one is accented, not shaded.
- **Berths** moves into the selected station's details; it is an optional detail per the approved categories.

## Equipment screens (Generator, Engine, Battery, Air Supply, Camera, Monitor, Docking Base)

```text
GENERATOR                         [ On ]   Name: Main generator ✎
Generating      ███████████░░░░  13
Ship demand     █████░░░░░░░░░░   5
To batteries    ████████░░░░░░░   8
Fuel  [■][ ][ ][ ][ ][ ]    Upgrades [ ][ ][ ]
                                       [ Details ]   [ Close ]
(player inventory)
```

```text
AIR SUPPLY                        [ On ]   Name: Cabin vent ✎
Room air  ██████████████████  99%       1 leak  [ Show leak ]
Power     [-] 4 / 8 [+]                 [ Show air opening ]
                                       [ Details ]   [ Close ]
(no player inventory: this machine has no item slots)
```

- The meters replace "Fuel loaded" and the "Supplying ship…" sentence. A problem (no fuel, demand above supply) turns the relevant meter amber and adds one message line.
- The Air Supply leak and air tuning itself still needs functional work (audit item 5); this pass only fixes presentation.

## Station Core (chunk 8)

The audit asks for a complete revamp with construction previews, which is chunk 8. For now, apply the shared rules only: name and status at the top, the room map with room names instead of "0,0 Empty", build and visitor controls grouped, people management under **More**. Replace "Fuel: 1625506 | Battery: 2400" with a readable supply estimate.

## Pad Computer

Already close. Join "Pad: 8 x 8 | Registered: Terra Pad" and the repeated "Registered Terra Pad (8 x 8)" into one line; merge "Previews: pad OFF | ship OFF" into the two Show buttons' on/off state; make **Bring back my ship** the main action.

## Wording to approve

| Where | Current | Proposed |
| --- | --- | --- |
| Controller | Current location | Here |
| Controller | Destination: … [ Choose ] | Going to … [ Change ] |
| Controller | Disengage | Stop |
| Controller | (new tab) | More |
| Controller | Minimum fuel: 2000 + docking reserve | needs 2000 fuel |
| Scanner | SCANNER / DESTINATIONS | SCANNER |
| Scanner | Settings / upgrades | Upgrades |
| Scanner | Station: 2/2 free | 2 berths free |
| Scanner | UNKNOWN (pad status) | Clear / Occupied / Unknown |
| Generator | Charging batteries | To batteries |
| Generator | Electricity: On | On |
| Air Supply | Device: On | On |
| Air Supply | allocate power | Power |
| All equipment | Edit name | Name: … ✎ |

"Here" and "Stop" change earlier approved labels (Current location, Disengage), so they need explicit re-approval or can stay as they are.

## Order of work

1. Shared terminal styling: color roles and no shaded backgrounds on plain text (one change in the terminal content helpers, affects every terminal screen).
2. Ship Controller and Scanner, then screenshot review with the player.
3. Equipment screens, one small group at a time (Generator/Engine/Battery, then Air Supply, then Camera/Monitor/Docking Base).
4. Pad Computer; Station Core basics (full revamp stays in chunk 8).

Each step gets focused graphical checks and screenshots for review; the full sweep runs once at the end of the batch.
