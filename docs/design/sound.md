# Sound

NullSpace had almost no sound of its own: ship doors borrowed trapdoor sounds, turrets an arrow, wrecks fire, suits armour-equip, and jumps, docking, engines, air, alarms and computers were silent. This document is the design for a small synthesizer, a set of named sound cues built on it, hearing rules that follow the hull and its rooms, and a Synth block that players build and that tests use to tune every cue. Decided in a brainstorm on 2026-11-20, and built: the synth, the cues and the hearing rules, the Synth block, and the sounds of travel, air, engines, docking and combat, with Minecraft's own sounds following the same rules. The cues' values were set by reasoning, not yet by ear.

## The synthesizer

One voice mixes two sources:

- **Tone.** Sine (thumps, hums) or square (alarms, engines). Its pitch can sweep from a start to an end frequency over a time.
- **Noise.** White noise through a filter. Its brightness sets the filter's frequency: a hiss (bright), a rumble (dark) or a whoosh (changing). Its focus sets the filter's kind: at 0 a smoothing filter that lets everything below that frequency through, above 0 a band around it that narrows as focus rises. A focused noise has a pitch of its own, a machine's growl or a pitched whoosh, so when the sound shifts the shift is heard as a change of pitch, not only of brightness. A second, steep filter just above the first cuts what is left high up: a gentle filter alone leaves enough of white noise's highs that a low rumble is heard as a faint hiss over it, because the ear is far keener there. Bright noise is above most of that filter and hardly changes.

Three shapers act on the voice:

- **Envelope:** attack, hold and release, so a sound fades in and out instead of clicking.
- **Pulse:** gates the voice on and off at a rate and on-fraction, for beeps and alarms.
- **Live values:** a looping voice reads a value from the game every tick (thrust, air flow, oxygen) and maps it onto pitch, level, brightness or pace. Pitch shifts the tone and the noise's filter by the same ratio, so a machine's whole sound moves together: the engines' rumble rises with their tone as thrust grows. Brightness moves the noise alone: a stronger breach hisses higher. The game sends these values a few times a second, in steps, and a playing voice glides to each over about a fifth of a second, as its loudness does: a door shutting or a step into the next room fades a sound rather than switching it.

The voice is [`SynthVoice`](../../src/main/java/com/nullandkale/nullspace/model/sound/SynthVoice.java) and its settings [`SynthPatch`](../../src/main/java/com/nullandkale/nullspace/model/sound/SynthPatch.java). The synth runs on the client and feeds Minecraft's own sound engine as a streamed, mono, positioned sound ([`SynthStream`](../../src/client/java/com/nullandkale/nullspace/render/sound/SynthStream.java), through Fabric's custom-stream hook and the sound event `nullspace:synth`), so the volume sliders, distance falloff and stereo placement work as for any sound. The engine asks a stream for a second of audio at a time and queues four; the synth answers with a tenth of a second, so about 0.4 s is queued and a live sound keeps up with the game. A loop whose stream runs dry in a client hitch is started again on the same voice. At most eight voices play at once ([`ClientSounds`](../../src/client/java/com/nullandkale/nullspace/render/sound/ClientSounds.java)); the quietest where the player stands, by loudness and distance, give way. A loop that gives way, or finds no voice free, waits instead of being lost, and takes a voice again when one frees or when it becomes clearly louder than a playing sound (half as loud again, so two alike do not trade places), checked twice a second. Without this a loop the server had started would sound only when the player next moved and changed how it was heard, so walking about seemed to set sounds off.

A **cue** is a named patch, or a few layered ones with start delays, in [`cues.json`](../../src/main/resources/assets/nullspace/synth/cues.json) ([`SoundCues`](../../src/main/java/com/nullandkale/nullspace/model/sound/SoundCues.java)); reloading resources (F3+T) picks up changes without a restart. `/nullspace sound <cue>` plays one to yourself. Game code plays a cue by name and never chooses a sound itself.

## Hearing rules

- **In space and on airless worlds**, a listener inside a hull hears only sources inside that hull. Everything outside it is silent: other ships, mobs, explosions. A listener outside on EVA hears only their own suit (breathing, low-air warnings) and their own screens.
- **On worlds with air**, hearing is ordinary, and a source outside the listener's hull comes through it muffled.
- **Rooms muffle.** The server knows each hull's rooms and doors from the air system ([`AirSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSystem.java)). For a source in another room it takes the clearest path between the two rooms: each open door or opening crossed darkens and quiets the sound a little, a closed door a lot, and a source with no path but through walls is a faint thud or nothing. Muffling is a level drop and the synth's smoothing filter on the whole voice. The rules are [`Hearing`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/Hearing.java) and [`RoomAcoustics`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/RoomAcoustics.java): a closed door costs 1 and a wall 3, and a path's cost muffles by 0.45, 0.65, 0.8, 0.88 or not at all. The server ([`ShipSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/ShipSounds.java)) sends the muffling with each cue it starts and refreshes it for looping cues twice a second, so walking between rooms or shutting a door changes what is heard.
- **Docked ships** join through their docking tunnel: with both docking doors open the other ship's rooms are rooms of your own; with a door shut they are muffled or silent.
- **Vanilla sounds** (blocks, mobs, doors, explosions) follow the same rules as far as Minecraft's sound engine allows ([`VanillaHearing`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/VanillaHearing.java)). The server sends each nearby player a sound as they hear it: not at all when it is silent for them, quieter when muffled ([`VanillaSoundMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/VanillaSoundMixin.java)). Without extra audio filtering an ordinary sound can only be made quieter, not darker. An explosion is the exception ([`ExplosionSoundMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/ExplosionSoundMixin.java)): muffled, it plays a quieter, lower recording (`nullspace:muffled_explosion` in [`sounds.json`](../../src/main/resources/assets/nullspace/sounds.json)), and unheard it still bursts and pushes but makes no sound. Two kinds are left alone: sounds a client makes for itself (the player's own steps and blocks, a furnace's crackle, a wreck's fire), which never pass through the server, and block-breaking sounds, which travel with their particles. A vanilla sound never makes the server work out a changed hull's rooms; it uses them as they were last worked out.

## The cues

| Cue | Tone | Noise | Shape |
| --- | --- | --- | --- |
| `ship.hum` | low sine with a quiet, slightly detuned fifth over it | dark band, quiet | steady loop aboard any hull in space |
| `ftl.jolt` | low sine sweeping down | dark burst | sharp attack, about a second of fade, with the [jump flash](flight-motion-and-transitions.md) |
| `ftl.drone` | very low sine | dark, quiet | loop that swells with the FTL streaks |
| `air.hiss` | none | bright | loop; level and brightness follow the flow through an opening |
| `air.refill` | none | soft | gentle loop while a room climbs from about 80% to full |
| `air.leak` | high sine, slightly wavering | bright, quiet | loop while a breach vents |
| `alarm.lowair` | square | none | beeps that speed up as the air falls |
| `alarm.breach` | square, two pitches alternating | none | klaxon |
| `engine.hum` | low, softened square | dark | loop; pitch and level follow thrust |
| `dock.clamp` | short low thump | short dark burst | then a hiss |
| `dock.release` | a hiss | then a short low thump | undocking |
| `rcs.puff` | short low sine sweeping down | a pitched puff | a maneuvering jet, heard through the hull |
| `weapon.fire` | square sweeping down | heavy burst | the ship's weapon |
| `turret.fire` | short high square sweeping down | bright tick | a turret's shot |
| `hull.impact` | very low sine sweeping down | dark burst | a hit on the hull |

Where each cue is played:

- **Aboard in space** ([`ShipAmbience`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/ShipAmbience.java)): the hum, for everyone inside a hull in space, at the same level wherever they stand, under every other sound. The fifth's slight detuning makes it beat slowly, so it is never quite still. On EVA there is silence, and on a world the world's own sounds take over.
- **Travel** ([`TravelSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/TravelSounds.java)): the jolt with every jump step and the drone while cruising. Arrival has no chime of its own.
- **Air**, from the air system's last step ([`AirSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSounds.java)): a hiss at the strongest breach (a whistle when it rushes); a vent at each Air Supply while its room fills (softer once it is over 80%); the breach klaxon for the whole crew while the hull loses at least 10 air a tick to the outside, which needs power for the controls (a battery counts); and, for a player whose air is below 40%, the low-air alarm, which speeds up as the air falls and needs no power. Air particles follow the flow's strength: more, larger and brighter for a stronger breach, and a single faint wisp at a supply topping up a room over 80%.
- **Engines** ([`EngineSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineSounds.java)): the hum while the engines thrust, under the same rule as the exhaust plume ([`EngineEffects`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineEffects.java)). It plays at the engine nearest the middle of the ship's engines; harder thrust makes it louder and higher, and speed makes it brighter.
- **Docking**: the clamp at the ship's docking door as it locks on ([`DockingManeuver`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java)), heard by both crews, and the release as undocking starts ([`DockingService`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingService.java)). While the maneuver moves the ship, in and out, its engines burn with the plume and hum of piloted flight, harder the faster it goes, and maneuvering jets puff cold gas from the hull, a short puff heard through the hull about every 0.4 s ([`DockingThrusters`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingThrusters.java)). A jet fires from the side of the hull away from where the ship is pushed, and a turn fires a pair at the two ends of the hull in opposite directions. Nothing burns while the ship waits for the other crew's consent or once it is clamped.
- **Combat**: the weapon's shot at the weapon ([`ShipCombat`](../../src/main/java/com/nullandkale/nullspace/gameplay/combat/ShipCombat.java)) and a turret's shot at the turret ([`Turrets`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/Turrets.java)), in place of the old arrow sound. A hit on a hull is heard by everyone aboard at the point of impact ([`ShipSounds.hull`](../../src/main/java/com/nullandkale/nullspace/gameplay/sound/ShipSounds.java)): the hull carries it, so it is heard even in vacuum, through a wall at worst.
- **Terminals** make no sound of their own when an action is taken or refused.

## The Synth block

A craftable ship device. It plays from where it stands, so the room muffling applies to it, and it is how a cue is tuned by ear.

- **Its patch:** tone (off, sine or square; start and end pitch, sweep time, level), noise (level, brightness, focus), envelope (attack, hold, release), pulse (rate, on-fraction) and loop. A preset loads any game cue as a starting point. The code is [`content/synth`](../../src/main/java/com/nullandkale/nullspace/content/synth) and the screen [`SynthTerminal`](../../src/main/java/com/nullandkale/nullspace/ui/apps/SynthTerminal.java).
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

One setting can also be changed alone: `wave`, `from`, `to`, `sweep`, `tonelevel`, `noiselevel`, `bright`, `focus`, `attack`, `hold`, `release`, `rate`, `duty` (`ship synth from 880`). The screen's buttons send these. The noise clause takes the focus as an optional third number (`ship synth noise 0.3 0.8 0.5`), and a patch writes it only when it is above 0.

Tuning a cue: load it as a preset, adjust it until it sounds right, `dump` it and paste the text into the cue file.

A Synth can also play one of the game's cues exactly as the game plays it: every layer, with its live values (loudness, pitch, brightness, pace) and a least muffling fixed. The sound gallery sets this on its stands. Changing the patch on the screen or from a terminal ends it, so a stand is also a starting point for tuning.

## The sound gallery

`/nullspace shipyard sounds` builds a lit, closed hall to listen to every cue ([`SoundGallery`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/SoundGallery.java)). Each stand is a Synth playing its cue as the game does, with a button on top for a sound that plays once or a lever for a loop, and a sign with its group, cue, the moment it stands for and its control.

- **North wall:** every cue, in groups: travel, air, alarms, engines, docking, combat and ambience. Where the game changes a cue as it plays, the values that matter stand side by side: a small, a strong and a rushing breach; a room filling and one nearly full; the low-air alarm at 39% and at 5% air; the engines at light thrust, half thrust and full thrust at speed. Those values come from the same functions the game uses ([`AirSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSounds.java), [`EngineSounds`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineSounds.java)), so the gallery follows any change to them.
- **South wall:** the full engine and a hit on the hull, each clear, through a closed door, through two closed doors and through a wall, muffled as the hearing rules muffle them.

The hall is built in the same place on every visit; the first visit finds the ground and the world remembers its height.

## Tests

Unit tests cover the patch text, the voice (one-shots end, loops sustain, muffling darkens, the pulse gates, the sweep moves) and the cue file, including every cue the game plays.

[`SoundGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/sound/SoundGameTest.java) uses a ship split by a door to check:

- the hearing rules;
- Minecraft's sounds as actually sent to a player, and explosions;
- the engine hum through the door, and a blow on the hull;
- the Synth block from the terminal, as a loop and from redstone.

The other tests:

- [`AirSoundsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/systems/AirSoundsGameTest.java) breaches a cabin on Luna: the hiss, the klaxon, then the low-air alarm.
- [`DefenceGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/defence/DefenceGameTest.java) checks that a player hears a turret's shot.
- [`SynthClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/content/synth/SynthClientGameTest.java) uses the screen and checks the client's sound engine really reads the voice.
- [`SoundGalleryGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/SoundGalleryGameTest.java) checks every cue has a stand with the right control, and that a lever and buttons play their cues, muffled where they should be.
- [`SoundGalleryClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/SoundGalleryClientGameTest.java) takes pictures of the hall for review and hears a lever and a button on the client.

## Power and recipe

- The Synth block is made from a note block, a circuit board and iron, and draws a little power from the ship like any device.
- Alarms keep sounding from a battery when generation stops: a ship that loses power still warns its crew. Engines and air fall silent with the systems they belong to.
