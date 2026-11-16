# Flight physics

**Status (November 15): built and tested, not yet played.** Ship flight now has momentum, mass, thrust limits, spin with inertia and flight assist, as designed below. The model is [`FlightDynamics`](../../src/main/java/com/nullandkale/nullspace/model/flight/FlightDynamics.java); the pilot and the autopilot run it from [`NavigationService`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/NavigationService.java). The engine exhaust follows the thrust and speed ([`EngineEffects`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineEffects.java)). Ships nobody is flying are simulated too, and each engine upgrade tier burns its own colour. The constants are first guesses to tune by playing. Docking did not need rewriting (see [What changed](#what-changed)). What is not built is under [Not built](#not-built).

Decided with the owner: **do it**, in the order proposed (translation, then rotation, then the autopilot and docking), with assist on by default.

## Organizing the design

The same four parts the [station work](tiny-ships-and-stations.md#organizing-the-design) uses:

| Part | Owns |
| --- | --- |
| Lore and content identity | What it should feel like to move a ship: weight, momentum, the cost of every burn |
| Gameplay | What the pilot does, what is hard, what is assisted, what the other systems (docking, autopilot, fuel, combat) need from it |
| Engine requirements | What the simulation must guarantee: determinism, save compatibility, multiplayer authority, bounded numbers |
| Generation methods | The model itself: the integrator, the thrust and torque laws, the assist controller and its constants |

## What it does now

[`FlightDynamics.step`](../../src/main/java/com/nullandkale/nullspace/model/flight/FlightDynamics.java) is called once per server tick (20 Hz) by [`NavigationService`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/NavigationService.java) for a piloted ship and for the autopilot, and by [`DockingManeuver`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java) and `Piloting.automate`. Its own description is "deterministic 20 Hz arcade flight". What it does:

| Behaviour | Now | What is wrong with it |
| --- | --- | --- |
| Velocity | Each tick the velocity is multiplied by 0.975, then acceleration is added. | That is **drag**, in space: a ship slows to a stop by itself within a few seconds of letting go. Real space has none. |
| Acceleration | `power * 0.6` per tick (`power` is the engine power the ship is given, 0 to 8) along the ship's forward or up direction. | It does not depend on the **ship's mass**, on how many engines it has, or on which way they point. A tiny ship and a four-deck liner accelerate the same. |
| Top speed | A hard cap of `12 + power * 8`. | Speed is bounded by a number, not by anything physical. |
| Brake | Velocity times 0.65 each tick while held. | A free, instant, fuel-less stop: it ignores how fast you were going. |
| No power | `power == 0` sets the velocity to zero and **freezes the rotation**. | Losing engine power stops the ship dead instead of letting it coast. |
| Reverse, up, down | Accelerate exactly like forward. | Engines point one way; there is no cost or limit for thrusting against them. |
| Turning | A fixed 2.25 degrees of yaw, 1.75 of pitch and a fixed roll per tick while a key is held, none the moment it is released. | **No angular momentum**: the ship turns, and stops turning, instantly. |
| Size | Every ship the same. | A large ship turns as fast as a small one. |

The result is a ship that starts, turns and stops the instant the pilot asks, and never drifts: "too perfect". Docking and the autopilot are built on that, issuing button presses that the model obeys exactly.

## Lore and content identity

A ship is a heavy thing in the dark. The feel the owner is after:

- **Momentum.** A burn changes your speed; nothing else does. Let go of the stick and you keep going.
- **Weight.** A big ship is slow to start, slow to turn and slow to stop. A small one is nimble. Engines are the other half: more engines, or better ones, mean more push.
- **Every move has a cost.** Stopping means turning around and burning against your motion, in fuel and in time. Docking is a careful, slow approach, not a button.
- **Things go wrong physically.** Losing engine power leaves you drifting at the speed you had. A broken engine pushes a ship off line. (The [crash start](crash-start.md) breaks engines, so this is also where its repair matters.)

## Gameplay

**Flight assist.** A fully Newtonian ship is hard to fly, and docking by hand would take practice most players will not want to spend. So the model is physical underneath and **assisted by default**, in the way many space games do it:

- With **assist on** (the default), the pilot gives a *wanted* motion (forward, turn, stop), and the ship's computer works out the thrust and torque to get it, limited by what the engines can actually do. Letting go asks for zero velocity and zero spin, so the ship brakes itself, but it **takes real time, distance and fuel**, more for a heavy ship or one going fast. The motion is smooth and physically limited, not instant.
- With **assist off**, thrust and torque go straight to the ship. Nothing damps anything. The pilot flies it like a real ship, and can flip and burn, coast, and spin up and keep spinning.

Assist is a ship control, off the helm, and the autopilot and docking always use it.

What the other systems need:

- **Docking and the autopilot** are written as sequences of button presses against the old model. They must become controllers that plan the approach and brake in time. This is the largest piece of work and the most likely to break something (see Engine requirements).
- **Fuel** is spent per tick of thrust now. It should be spent for the thrust actually produced, so braking costs as much as accelerating.
- **Combat** ([`ShipCombat`](../../src/main/java/com/nullandkale/nullspace/gameplay/combat/ShipCombat.java)) reads the ship's speed and position; a fast, heavy ship should be harder to turn onto a target.
- **The exhaust** already scales with thrust and speed.

## Engine requirements

What the simulation must guarantee:

- **Determinism.** A fixed step of 0.05 seconds, the same on every server and in tests, with no dependence on the machine's frame time. The model is a pure function from the previous state and the inputs to the next state, like `FlightDynamics.step` today.
- **Mass.** A ship's mass comes from its hull: the blocks the hull scan already counts for a transfer ([`HullScan`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/HullScan.java)), plus its fuel and cargo if that is wanted. It is cached and recomputed when the ship changes, not every tick.
- **Thrust.** Each powered, connected engine produces thrust along its exhaust direction (the direction [`ShipHeading`](../../src/main/java/com/nullandkale/nullspace/model/flight/ShipHeading.java) already uses to find the bow). The ship's forward thrust is the sum. Thrust against the engines (reverse, up, down, sideways) needs a source: either **manoeuvring thrusters** (a weaker, built-in force, so retro-burning is slower than going forward), or a rotation of the ship to point the engines the other way. This is a decision (below).
- **Angular motion.** The ship keeps an angular velocity that torque changes. Torque comes from manoeuvring thrusters, and scales with the ship's size (a moment of inertia from the hull's extent and mass).
- **Saved state.** New fields on the ship record for angular velocity, beside the existing `simVX`, `simVY`, `simVZ`, `simYaw`, `simPitch` and `simRoll`. An old save has none and starts with zero spin, so nothing is lost and the old record is valid.
- **Bounded numbers.** No speed cap is physical, but the local space is finite (`LOCAL_RADIUS`, 8192 blocks) and the world uses single-precision positions in places. A **flight governor**, a soft top speed the computer will not exceed by default and the pilot can lift with assist off, keeps the ship in range and the arithmetic safe.
- **Multiplayer.** The server owns the state, as now. Clients are sent the position and orientation each tick; nothing about the physics runs on the client.
- **Other readers.** The scene frames ([`SceneFrames`](../../src/main/java/com/nullandkale/nullspace/gameplay/scene/SceneFrames.java), [`ScenePanels`](../../src/main/java/com/nullandkale/nullspace/gameplay/scene/ScenePanels.java)) and the pilot screen read `simVX..` and `thrustDemand`; the names stay, so they keep working.
- **Tests.** The model is pure, so it is tested without the game: no input keeps the velocity and spin unchanged (momentum); a burn of known force on a known mass gives the known acceleration; stopping from a speed takes the distance the physics says; a heavier ship accelerates and turns less; assist reaches a wanted velocity without exceeding the thrust; and the docking and autopilot tests still complete.

## Generation methods

The model, with constants to be tuned by playing:

1. **Step.** Semi-implicit Euler at 0.05 s: update velocity from acceleration, then position from the new velocity; likewise angular velocity then orientation (as a quaternion, normalised each step).
2. **Acceleration** `a = F / m`, with `F` the sum of the engines' thrust along their directions, and `m` the ship's mass. No drag.
3. **Starting constants.** A reference ship of 800 hull blocks with two engines should reach 20 blocks per second in about 6 seconds (about 3.3 blocks per second squared) and stop from that speed in about the same time with assist on and thrusters at 60 percent of the main engines. A four-deck ship of three times the mass should take about three times as long. These come from the old model's feel (it reached its cap in about a second, which is what "too perfect" means) and are the first thing to adjust in play.
4. **Torque** `α = τ / I`, with `I` growing with mass and the square of the ship's length. A small ship should turn through 90 degrees in about 2 seconds; a large one in about 6.
5. **Assist** is a damped controller that asks for the acceleration that would bring the ship to the wanted velocity and spin in a set time, then clamps it to the available thrust and torque, so a heavy ship cannot do what a light one can. It spends fuel for the thrust it uses.
6. **Governor.** With assist on, the wanted speed is capped at a default (to be chosen, about the old `12 + power * 8` cap so travel times stay similar); assist off removes the cap.
7. **Power loss.** With no engine power, thrust is zero and the ship keeps its velocity and spin.

## What changed

All of it is in one change; the phases landed together because each is small on its own.

- **Momentum, no drag.** Velocity is no longer damped. A ship keeps its velocity and spin until engines or thrusters change them. Letting go of the keys, losing power or running out of fuel leaves the ship drifting.
- **Mass and thrust.** Acceleration is `1.1 * power * 800 / mass` blocks a second squared (power 0 to 8; mass is the hull's blocks, at least 150, counted from the ship's box and saved as `simMass`, recounted every ten seconds so building changes how it handles). The reference ship (800 blocks) on power 3 reaches 20 blocks a second in about six seconds, and a ship three times as heavy takes three times as long.
- **Thrusters.** Reverse and sideways push at 60 percent of the main engines, up and down at 50 percent.
- **Spin.** Turning is angular momentum, saved on the ship as `simSpinYaw`, `simSpinPitch` and `simSpinRoll`. The spin-up and top turn rate scale with how light the ship is: the reference ship reaches 45 degrees a second, a ship three times as heavy about 16.
- **Flight assist** is on by default. The keys ask for a motion and the computer gets there within what the engines and thrusters can give: letting go brakes the ship over seconds (about 4 seconds from 30 blocks a second on the reference ship), and braking burns fuel like any thrust. The speed it aims for at full stick is `12 + 8 * power`. **G** at the helm turns assist off (the HUD shows "ASSIST ON/OFF"); then the keys fire the engines and thrusters directly, nothing damps the ship, and the absolute limit is 400 blocks a second. Computer scripts and the autopilot always fly with assist on.
- **Fuel** is spent for the thrust actually used, so braking costs as much as accelerating; a ship out of fuel coasts.
- **Docking** moves a ship along its own scripted path ([`DockingManeuver`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java)) and never ran on the flight step, so it needed no change and flies the same. It stays "perfect" by design: it is a guided approach, not a free flight.
- **Unpiloted ships keep moving.** Leaving the helm, ending a script or removing a computer no longer zeroes the ship, and neither does sitting down at the helm of a moving one. [`NavigationService`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/NavigationService.java) simulates every ship in space that has velocity or spin and has no pilot, no script, no docking motion, no journey and no transfer. With assist on and engine power and fuel, the computer brakes it to rest over seconds (the status reads "BRAKING / NO PILOT"); with no power or fuel it drifts on at the speed it had ("DRIFTING / NO ENGINE POWER"). An obstacle or the local boundary stops it ("DRIFT STOPPED / OBSTACLE"). Moving between worlds (launch and landing) clears the motion, so a ship never arrives already moving; docking, journeys and storage already stopped the ship themselves. Motion is saved with the ship, so a world reloaded mid-drift keeps drifting.
- **Engine upgrades colour the exhaust.** Each engine's upgrade tier sets its plume colours: plain engines burn amber, a first upgrade blue, an advanced one violet, an exotic one green, and a creative one white-gold ([`EngineEffects.palette`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineEffects.java)). Upgrades also raise the ship's engine power, so a higher tier is faster as well as brighter.
- **The helm view shows the exhaust.** The pilot screen and the sky seen from a window are drawn by the space scene renderer, not Minecraft's, so Minecraft's particles never appeared in them. The server now sends each thrusting ship's nozzles with its scene entry, and [`ExhaustPuffs`](../../src/client/java/com/nullandkale/nullspace/render/scene/ExhaustPuffs.java) draws the same exhaust inside the scene: dust puffs with vanilla's own sprites, sizes, colour shading, lifetimes and slow drift, laid out by the same plume and palette ([`EngineEffects`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineEffects.java)), in a pass after the ship and the planets. Puffs live in the universe, so they trail a moving ship and curve when it turns. Other ships' exhaust now shows in the sky from a window too; the observer's own ship is left to the world's particles so nothing is drawn twice.
- **The exhaust** follows two numbers the flight step saves on the ship: `thrustShare` (how hard the engines work, 0 to 1) and `speedShare` (speed against the ship's cruising speed). More of either makes a longer, bigger, denser plume; the smallest plume is still large.

## Not built

- **Fuel in the mass.** Mass is the hull only (the recommendation).
- **Anything about how it feels.** The numbers are untested by play. The first thing to adjust is the acceleration constant and the turn rates.
- **A damage link.** A broken Engine already reduces `power`, which lowers acceleration and the cruising speed, but nothing pushes a ship off line when one engine fails.
- **Drift hazards.** An unpiloted ship that drifts into something is stopped as an obstacle; it is not damaged, and nothing warns the crew that it is drifting.

## Open

1. **Tuning.** The acceleration constant, the turn rates, the thruster shares and the governor are first guesses.
2. **Fuel in the mass.** Whether a loaded ship should handle worse.
3. **Warning the crew.** Whether a ship that is drifting with no power or no pilot should say so on the controller and the helm.

## Where this is tested

- [`FlightDynamicsTest`](../../src/test/java/com/nullandkale/nullspace/model/flight/FlightDynamicsTest.java) and [`FlightInvariantTest`](../../src/test/java/com/nullandkale/nullspace/model/flight/FlightInvariantTest.java) (pure): momentum and spin persist with no input and assist off; losing power leaves a ship drifting; assist stops a ship but takes real time and distance and burns fuel; assist never asks more than the engines give in a tick; a heavier ship accelerates and turns less (three times the mass, a third of the acceleration); more power pushes harder; the reference ship reaches 20 blocks a second in about six seconds; assist holds the governor and assist off does not; spin carries on when released and assist damps it over time; invalid input is rejected; long random flights stay finite, bounded and consistent.
- [`EnginePlumeTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/systems/EnginePlumeTest.java): the plume grows with thrust and with speed and stays within bounds, and is never small.
- [`FlightPhysicsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/FlightPhysicsGameTest.java), a real pilot lease on a real ship in its own test environment: thrust gives speed and a counted mass; letting go does not stop the ship at once and braking burns thrust; with assist off nothing slows it and there is no exhaust. [`NavigationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/NavigationGameTest.java) (its thrust check now waits seconds for a heavy ship), [`ShipControlGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/ShipControlGameTest.java), [`ExpansionGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/scene/ExpansionGameTest.java) (computer scripts) and [`DockingPresetGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/docking/DockingPresetGameTest.java) pass unchanged in behaviour.
- [`FlightPhysicsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/FlightPhysicsGameTest.java) also covers the unpiloted ship: one with no power drifts on at its speed and spin and moves through space; one with power and fuel brakes itself to rest over seconds. [`EngineEffectsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/systems/EngineEffectsGameTest.java) checks that engine upgrades change the exhaust's tier, and [`EnginePlumeTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/systems/EnginePlumeTest.java) that each tier has its own colours. [`NavigationClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/NavigationClientGameTest.java) (the real helm) checks that releasing the helm leaves the ship's momentum.
- Recorded videos from the real pilot screen (a pilot at the real controls: accelerate, let go, turn, assist off and coast, assist on and brake) and of the four tier colours from behind the engines ([`EffectsVideoClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/EffectsVideoClientGameTest.java), opt-in). Playing it is the real test.
