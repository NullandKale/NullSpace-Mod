# Sound

NullSpace has almost no sound of its own: ship doors borrow trapdoor sounds, turrets an arrow, wrecks fire, suits armour-equip. Jumps, launches, docking, engines, air, alarms and computers are silent. This document is the design for a small synthesizer, a set of named sound cues built on it, hearing rules that follow the hull and its rooms, and a Synth block that players build and that tests use to tune every cue. Decided in a brainstorm on 2026-11-20; nothing here is built yet.

## The synthesizer

One voice mixes two sources:

- **Tone.** Sine (chimes, hums) or square (alarms, computer blips). Its pitch can sweep from a start to an end frequency over a time.
- **Noise.** White noise through one smoothing filter. Its brightness makes it a hiss (bright), a rumble (dark) or a whoosh (changing).

Three shapers act on the voice:

- **Envelope:** attack, hold and release, so a sound fades in and out instead of clicking.
- **Pulse:** gates the voice on and off at a rate and on-fraction, for beeps and alarms.
- **Live values:** a looping voice reads a value from the game every tick (thrust, air flow, oxygen) and maps it onto pitch, level or brightness.

The synth runs on the client and feeds Minecraft's own sound engine as a streamed, mono, positioned sound, so the volume sliders, distance falloff and stereo placement work as for any sound. At most about eight voices play at once; the quietest and farthest are dropped first.

A **cue** is a named patch (or a few layered ones) with its settings, kept in a resource file so that reloading resources picks up changes without a restart. Game code plays a cue by name and never chooses a sound itself.

## Hearing rules

- **In space and on airless worlds**, a listener inside a hull hears only sources inside that hull. Everything outside it is silent: other ships, mobs, explosions. A listener outside on EVA hears only their own suit (breathing, low-air warnings) and their own screens.
- **On worlds with air**, hearing is ordinary, and a source outside the listener's hull comes through it muffled.
- **Rooms muffle.** The server knows each hull's rooms and doors from the air system ([`AirSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSystem.java)). For a source in another room it takes the clearest path between the two rooms: each open door or opening crossed darkens and quiets the sound a little, a closed door a lot, and a source with no path but through walls is a faint thud or nothing. Muffling is a level drop and the synth's smoothing filter on the whole voice. The server sends the muffling with each cue it starts and refreshes it for looping cues about twice a second, so walking between rooms or shutting a door changes what is heard.
- **Docked ships** join through their docking tunnel: with both docking doors open the other ship's rooms are rooms of your own; with a door shut they are muffled or silent.
- **Vanilla sounds** (blocks, mobs, doors, explosions) follow the same rules as far as Minecraft's sound engine allows: silent outside the hull in vacuum, quieter from other rooms. Without extra audio filtering they can only be made quieter, not darker. This is the last part to build.

## The cues

| Cue | Tone | Noise | Shape |
| --- | --- | --- | --- |
| `ftl.jolt` | low sine sweeping down | dark burst | sharp attack, about a second of fade, with the [jump flash](flight-motion-and-transitions.md) |
| `ftl.drone` | very low sine | dark, quiet | loop that swells with the FTL streaks |
| `ftl.arrive` | two sine notes rising | none | arrival chime |
| `air.hiss` | none | bright | loop; level and brightness follow the flow through an opening |
| `air.refill` | none | soft | gentle loop while a room climbs from about 80% to full |
| `air.leak` | high sine, slightly wavering | bright, quiet | loop while a breach vents |
| `alarm.lowair` | square | none | beeps that speed up as the air falls |
| `alarm.breach` | square, two pitches alternating | none | klaxon |
| `engine.hum` | low, softened square | dark | loop; pitch and level follow thrust |
| `dock.clamp` | short low thump | short dark burst | then a hiss |
| `ui.blip`, `ui.error` | short square, low buzz | none | terminal and controller feedback |

The first batch is the synth, the cue file, the hearing rules for cues and the Synth block, then the FTL and air cues and the low-air alarm; air particles get their requested tuning (shown more often, opacity following flow strength, a gentle fill from about 80% to full) in the same pass. Engines, docking, combat and terminal sounds follow once the first set has been heard and tuned.

## The Synth block

A craftable ship device. It plays from where it stands, so the room muffling applies to it, and it is how a cue is tuned by ear.

- **Its patch:** tone (off, sine or square; start and end pitch, sweep time, level), noise (level, brightness), envelope (attack, hold, release), pulse (rate, on-fraction) and loop. A preset loads any game cue as a starting point.
- **Its screen:** right-clicking opens a terminal-style panel, like the Ship Controller's, with each setting and `[-]`/`[+]`, the preset list, and Play, Stop and Loop.
- **Redstone:** a redstone pulse plays its patch, like a note block; what it plays is whatever the screen has programmed.
- **The terminal:** it is a ship device, reached through the existing `ship` command of any ship terminal and `ship.<command>` in scripts ([`VirtualComputer`](../../src/main/java/com/nullandkale/nullspace/model/computer/VirtualComputer.java)), so players can script alarms and tests can drive it:

```
ship synth                         list the Synth blocks on this ship
ship synth play ftl.jolt           play a named cue
ship synth tone square 880 440 0.4
ship synth noise 0.3 0.8
ship synth env 0.01 0.1 0.5
ship synth pulse 4 0.5
ship synth loop on
ship synth stop
ship synth dump                    print the current patch as cue text
```

Tuning a cue: load it as a preset, adjust it until it sounds right, `dump` it and paste the text into the cue file.

## Power and recipe

- The Synth block is made from a note block, a circuit board and iron, and draws a little power from the ship like any device.
- Alarms keep sounding from a battery when generation stops: a ship that loses power still warns its crew. Engines and air fall silent with the systems they belong to.
