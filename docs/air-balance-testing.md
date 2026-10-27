# Air balance: findings and test plan

Written 2026-10-26. This is a findings and plan document; no production code or tuning values were changed. The numbers below come from a throwaway simulation that called `SpatialAir.step` and `Atmosphere.rebuild` directly. The harness itself is not in the repository.

## Observed problem

On the World 5 test ship, with the door and a trapdoor open (about 3 m² exposed to vacuum) and the air machine paused, the cabin loses air very slowly and the player is not endangered quickly. The expected behavior is a fast, clearly dangerous breach. The particle effect also needs tuning.

## Measurements

Setup: a sealed rectangular room, a 2-cell door in one end wall and a 1-cell trapdoor in the ceiling (the "door only" rows have no trapdoor), exterior pressure 0, no supply, no occupants. Air was stepped in 5-tick batches exactly as `AirSystem.tick` does. "Unbreathable" means the cell holds less than `Atmosphere.BREATHABLE` (250 of `FULL` 1000). Room sizes are illustrative, not the dimensions of the World 5 ship.

| Room (x × y × z, cells) | Openings | Mean air < 50% | Cell beside opening unbreathable | Far-end eye cell unbreathable |
| --- | --- | --- | --- | --- |
| 5 × 3 × 4 (60) | 3 | 11.8 s | 17.0 s | 26.0 s |
| 5 × 3 × 4 (60) | 2 | 19.0 s | 27.3 s | 42.0 s |
| 8 × 3 × 5 (120) | 3 | 24.3 s | 32.8 s | 55.3 s |
| 8 × 3 × 5 (120) | 2 | 41.3 s | 55.0 s | 95.0 s |
| 12 × 4 × 6 (288) | 3 | 61.8 s | 76.3 s | 149.5 s |
| 12 × 4 × 6 (288) | 2 | 105.8 s | 127.3 s | 257.0 s |
| 20 × 4 × 8 (640) | 3 | 146.8 s | 155.0 s | 424.5 s |
| 20 × 4 × 8 (640) | 2 | 263.3 s | 266.8 s | 865.8 s |

On top of that, once the player's cell is unbreathable `SpaceSurvival` takes about 16 s to run the air counter from 300 down to -20, then deals 4 damage per second, so death needs 5 hits: about **20 s after the cell becomes unbreathable**. A 3 m² breach in a 288-cell cabin therefore kills in roughly 170 s from the far end.

## Causes in the code

1. **Exterior faces use the interior diffusion rate.** `SpatialAir.step` moves `(air − neighbor) / 12` per face per tick for every face, including faces that open to vacuum. The leak is limited to the single cell beside the opening and spreads inward by diffusion (about 1.7 cells² per second), so drain time grows with room volume times room length, divided by opening area. Real venting to vacuum would be far faster than inter-room mixing.
2. **A large survival buffer.** The player has about 16 s of air counter after the surrounding air is gone, before damage begins (`SpaceSurvival`).
3. **Two models disagree.** `Atmosphere.tick` (the older lumped model) removes `leaks × 3000 × ticks` per room, which would empty a 60-cell room in a few ticks. `AirSystem` no longer uses it, so it is dead code that suggests faster leaks than the game actually has.
4. **The paused air machine is the only case that matters.** A running air supply adds up to 2000 per tick into its front cell, against about 250 per tick of initial leakage for three openings. A running machine therefore hides breaches of this size entirely.

## Particles: current behavior

From `AirFlowParticles` (client) and `AirEffects` (server):

- Server sends the 32 strongest flows, sorted by amount, every 5 ticks, to every player within 100 blocks; only the last sub-tick of each step records flows.
- Interior flows below an amount of 4 are dropped, and a room's flows are hidden unless it has a leak to lower pressure or is below 99% full.
- The client spawns 2 `CLOUD` particles per flow with lifetime 8 ticks and speed `min(0.35, 0.025 + amount / 1000)` blocks per tick. A typical opening flow of about 80 gives roughly 0.1 blocks per tick, under a metre of travel.
- Because only the strongest flows are kept, particles cluster at the opening and the room-wide pull is not visible; the effect fades out before the room is empty because of the amount threshold.

## Test plan

### 1. Specify targets first

State the intended behavior as bands, for example "a 3 m² breach in an 8 × 3 × 5 cabin makes the player's cell unbreathable in X to Y seconds and kills in A to B seconds". Bands, not exact values, so deliberate tuning stays easy and accidental drift fails. These targets are a design decision and are not set yet.

### 2. Pure-unit balance harness (`src/test`)

- A `BalanceScenario` describes room dimensions, openings, supplies, occupants and exterior pressure, and runs `Atmosphere.rebuild` then repeated `SpatialAir.step(..., 5, exterior)`.
- It returns a timeline: time for mean air to fall below 90/50/25%, time the player's cell becomes unbreathable, and time of first damage and death. The last two apply the player constants from `SpaceSurvival` (air counter 300, floor -20, 4 damage per second, 20 HP). Those constants should be extracted to named values so the test and the game share them.
- Runs in milliseconds, so it can sweep a matrix.

### 3. Assertions

- Band checks against the spec for a handful of reference scenarios.
- Relationships: more opening area drains faster; a larger room drains slower; a closed room never loses air; total gas is conserved except for what escapes through exterior faces.
- Supply versus breach: for which opening areas one air machine holds pressure at equilibrium, and that a paused machine does not.
- Regression guard that `AirSystem` and the harness use the same step size and exterior pressure.

### 4. Real geometry fixture

Add a small tool that exports the sealed-cell grid and the opening cells of the World 5 ship (as `ShipScan.seals` sees them) to a compact resource file, so the unit harness tests the actual ship without loading Minecraft. This tool does not exist yet.

### 5. One GameTest for wiring

Open the real door and trapdoor on a fixture ship and check that `ShipSystems.breathable` changes, the player's air counter falls and damage is applied, and closing the door stops the loss. Keep the timing-sensitive numbers in the unit harness because real-time tests are slow; use a small room so the test finishes within a few hundred ticks.

### 6. Tuning report

An opt-in Gradle task (like `benchmarkSystems`) that prints the scenario matrix to `build/reports`. Keep the baseline table above in this document and update it when values change.

### 7. Particle tests

- Unit: flow selection (cap of 32, determinism, ordering, visibility rule, packet size limit of 192 values) in `AirEffects`/`SpatialAir`.
- Client GameTest (extend `ExpansionClientGameTest`): for a fixed breach, assert the number of live particles and their initial speed, so a retune cannot silently double or halve the effect.
- The visual result still needs a human review of screenshots; tests only pin the parameters.

## Candidate tuning levers

- A separate, larger conductance for exterior faces (the main fix for slow venting).
- How much a full cell drains per tick through a hole.
- The player's air reserve and damage per second.
- The 250 breathable threshold.
- Particle lifetime, speed, count per flow, the minimum flow amount, and how many flows are sent.

## Status

Nothing in this plan is implemented. Next step is to choose the target bands, then build the harness (steps 2 and 3) with those bands as its first assertions.
