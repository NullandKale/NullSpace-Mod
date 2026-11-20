# Sound

NullSpace has almost no sound of its own: ship doors borrow trapdoor sounds, turrets an arrow, wrecks fire, suits armour-equip. Jumps, launches, docking, engines, air, alarms and computers are silent. This document is the design for a small synthesizer, a set of named sound cues built on it, hearing rules that follow the hull and its rooms, and a Synth block that players build and that tests use to tune every cue. Decided in a brainstorm on 2026-11-20. The synth, the cues, the hearing rules for cues, the Synth block and the travel and air sounds are built; engines, docking, combat, terminal sounds and vanilla sounds are still to come.

## The synthesizer

One voice mixes two sources:

- **Tone.** Sine (chimes, hums) or square (alarms, computer blips). Its pitch can sweep from a start to an end frequency over a time.
- **Noise.** White noise through one smoothing filter. Its brightness makes it a hiss (bright), a rumble (dark) or a whoosh (changing).

Three shapers act on the voice:

- **Envelope:** attack, hold and release, so a sound fades in and out instead of clicking.
- **Pulse:** gates the voice on and off at a rate and on-fraction, for beeps and alarms.
- **Live values:** a looping voice reads a value from the game every tick (thrust, air flow, oxygen) and maps it onto pitch, level or brightness.

The voice is [`SynthVoice`](../../src/main/java/com/nullandkale/nullspace/model/sound/SynthVoice.java) and its settings [`SynthPatch`](../../src/main/java/com/nullandkale/nullspace/model/sound/SynthPatch.java). The synth runs on the client and feeds Minecraft's own sound engine as a streamed, mono, positioned sound ([`SynthStream`](../../src/client/java/com/nullandkale/nullspace/render/sound/SynthStream.java), through Fabric's custom-stream hook and the sound event `nullspace:synth`), so the volume sliders, distance falloff and stereo placement work as for any sound. The engine asks a stream for a second of audio at a time and queues four; the synth answers with a tenth of a second, so about 0.4 s is queued and a live sound keeps up with the game. A loop whose stream runs dry in a client hitch is started again on the same voice. At most eight voices play at once ([`ClientSounds`](../../src/client/java/com/nullandkale/nullspace/render/sound/ClientSounds.java)); the quietest give way.

A **cue** is a named patch, or a few layered ones with start delays, in [`cues.json`](../../src/main/resources/assets/nullspace/synth/cues.json) ([`SoundCues`](../../src/main/java/com/nullandkale/nullspace/model/sound/SoundCues.java)); reloading resources (F3+T) picks up changes without a restart. `/nullspace sound <cue>` plays one to yourself. Game code plays a cue by name and never chooses a sound itself.

## Hearing rules

- **In space and on airless worlds**, a listener inside a hull hears only sources inside that hull. Everything outside it is silent: other ships, mobs, explosions. A listener outside on EVA hears only their own suit (breathing, low-air warnings) and their own screens.
- **On worlds with air**, hearing is ordinary, and a source outside the listener's hull comes through it muffled.
- **Rooms muffle.** The server knows each hull's rooms and doors from the air system ([`AirSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSystem.java)). For a source in another room it takes the clearest path between the two rooms: each open door or opening crossed darkens and quiets the sound a little, a closed door a lot, and a source with no path but through walls is a faint thud or nothing. Muffling is a level drop and the synth's smoothing filter on the whole voice. The rules are [`Hearing`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/Hearing.java) and [`RoomAcoustics`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/RoomAcoustics.java): a closed door costs 1 and a wall 3, and a path's cost muffles by 0.45, 0.65, 0.8, 0.88 or not at all. The server ([`ShipSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/ShipSounds.java)) sends the muffling with each cue it starts and refreshes it for looping cues twice a second, so walking between rooms or shutting a door changes what is heard.
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

Played now: the jolt with every jump step and the chime on arrival ([`TravelSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/TravelSounds.java)), the drone while cruising, and from the air system's last step ([`AirSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSounds.java)) a hiss at the strongest breach (a whistle when it rushes), a vent at each Air Supply while its room fills (softer once it is over 80%), and, for a player whose air is below 40%, the low-air alarm, which speeds up as the air falls and needs no power. Air particles follow the flow's strength: more, larger and brighter for a stronger breach, and a single faint wisp at a supply topping up a room over 80%. `engine.hum`, `dock.clamp`, `alarm.breach` and the `ui` cues are in the file for the Synth's presets and wait for their game hooks.

## The Synth block

A craftable ship device. It plays from where it stands, so the room muffling applies to it, and it is how a cue is tuned by ear.

- **Its patch:** tone (off, sine or square; start and end pitch, sweep time, level), noise (level, brightness), envelope (attack, hold, release), pulse (rate, on-fraction) and loop. A preset loads any game cue as a starting point. The code is [`content/synth`](../../src/main/java/com/nullandkale/nullspace/content/synth) and the screen [`SynthTerminal`](../../src/main/java/com/nullandkale/nullspace/ui/apps/SynthTerminal.java).
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
ship synth 2 from 660              a number first picks another Synth on the ship
```

One setting can also be changed alone: `wave`, `from`, `to`, `sweep`, `tonelevel`, `noiselevel`, `bright`, `attack`, `hold`, `release`, `rate`, `duty` (`ship synth from 880`). The screen's buttons send these.

Tuning a cue: load it as a preset, adjust it until it sounds right, `dump` it and paste the text into the cue file.

## Tests

Unit tests cover the patch text, the voice (one-shots end, loops sustain, muffling darkens, the pulse gates, the sweep moves) and the cue file. [`SoundGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/sound/SoundGameTest.java) checks the hearing rules in a ship split by a door and the Synth block from the terminal, as a loop and from redstone; [`AirSoundsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/systems/AirSoundsGameTest.java) breaches a cabin on Luna; [`SynthClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/content/synth/SynthClientGameTest.java) uses the screen and checks the client's sound engine really reads the voice.

## Power and recipe

- The Synth block is made from a note block, a circuit board and iron, and draws a little power from the ship like any device.
- Alarms keep sounding from a battery when generation stops: a ship that loses power still warns its crew. Engines and air fall silent with the systems they belong to.
