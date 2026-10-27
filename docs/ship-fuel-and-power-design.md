# Ship fuel, engines, generation, and batteries

Status: proposal for critique. Fuel, residues, solar panels, and hardware tiers described here are **not implemented**. The pad, sky, and development-profile fixes are separate changes. This extends [ship systems](ship-systems-and-viewports.md), [navigation](ship-navigation.md), and [recipes](ship-recipes.md).

## The intended survival loop

Build a basic engine, put wood or coal into it, keep the exhaust clear, and collect its combustion products. Fuel determines how long the engine can work; its hardware level determines how much thrust it can deliver. Install a generator or solar panels to run controls, air, doors, cameras, and other equipment. Batteries store surplus electricity and cover nights, peak loads, and emergencies.

An engine can also generate electricity. Enable its alternator when electrical supply matters more than maximum propulsion efficiency. A ship can have full batteries and no propulsion fuel, or plenty of fuel and a dead electrical bus. Both situations must be clearly explained at the helm.

## Requirements and proposed choices

These come directly from the requested design:

- Engines consume combustible fuel. Electrical generators and solar panels supply the shared ship power bus; batteries alone do not propel the ship.
- Engines and batteries have levels. This proposal interprets that as hardware tiers, alongside live throttle and battery charge levels.
- Fuels have clean and dirty characteristics.
- Burning fuel produces a useful combustion-product item.
- Engines can produce electricity with a small efficiency penalty.
- Keep ordinary vanilla resources useful and retain the existing shared bus and allocation terminal.

The following details are proposals, not settled requirements: three hardware tiers, vanilla furnace fuel as the acceptance baseline, two residue grades, fixed per-machine fuel inventories, solar plus a combustion generator in the first implementation, and the numerical balance below. The final Q&A collects the decisions that need input.

## Separate combustion from electricity

There are two accounting systems:

| Resource | Produced by | Consumed by | Stored in |
| --- | --- | --- | --- |
| Combustion energy | Burning an accepted fuel item | Propulsion or a fuel-burning generator | The machine's remaining burn buffer; unburned items in its fuel slots |
| Electrical energy | Generators, solar panels, engine alternators | Powered ship systems | Batteries |

Existing engines currently derive their readiness and thrust allowance from the electrical `ENGINES` allocation. Replace that with **engine demand/throttle** plus available combustion energy. Retain a small electrical engine-support load for control electronics; do not charge full propulsion output to the bus. The helm must distinguish `NO FUEL`, `CONTROLS UNPOWERED`, `ENGINE SUPPORT UNPOWERED`, `EXHAUST BLOCKED`, and `RESIDUE OUTPUT FULL`.

Electrical allocation remains integer power units per tick. One delivered power unit consumes one electrical energy unit per tick. Combustion uses fixed-point units derived from vanilla furnace burn duration, so fractional throttle does not round weak fuels up to free thrust or waste an entire coal item per tick.

## Fuel acceptance and cleanliness

Start with every item accepted by the server's loaded vanilla furnace-fuel rules: logs, planks, sticks, wooden tools, coal, charcoal, coal blocks, dried kelp blocks, lava buckets, and other registered fuels. Read the current data after reload. A stick works in the same engine as coal; it simply lasts less time. Stone, iron, glass, and other noncombustible items remain invalid. “Everything” needs confirmation in Q1 below.

Fuel metadata supplies burn duration, cleanliness, residue quantity/type, and any vanilla container remainder. Item-specific rules override tagged families; unclassified valid furnace fuels default to dirty fuel with a conservative generic residue. Invalid or conflicting definitions are rejected during reload, retaining the last valid rules.

| Fuel group | Proposed classification | Proposed result |
| --- | --- | --- |
| Coal, charcoal, coal blocks | Dirty | Carbon-rich combustion residue |
| Wood, sticks, wooden tools and fittings | Dirty | Mineral ash |
| Saplings, kelp and other biomass | Dirty | Mineral ash |
| Lava bucket | Dirty, high duration | Mineral ash plus the returned empty bucket |
| Later refined combustible pellet | Clean | A smaller quantity of carbon-rich residue |

Clean means lower waste and lower fouling per unit of useful work; it does not mean residue-free. The table is a gameplay classification, not a chemistry simulation. Dirty fuel remains useful throughout the game. Higher-level engines accept dirty fuel too; they do not silently invalidate a player's existing fuel stockpile.

Introduce a data field for fouling now, but initially make waste volume the main downside of dirty fuel. Engine damage, maintenance cleaning, toxic gases, and cascading failures need separate agreement before becoming survival penalties. Dirty exhaust can have visible particles while the engine burns, without treating the particles as cabin oxygen or poisoning the ship.

For the first clean fuel, propose a shaped **fuel pellet** recipe using charcoal, paper, and copper processing hardware as a reusable workstation rather than a consumed ingot in each pellet. Refining consumes electricity and reduces total available energy slightly: it trades energy and processing time for cleaner operation and compact storage. It must not produce more fuel energy than it consumes or create a residue recycling loop. The exact crafting grid/workstation is open in Q5; this document does not add a recipe yet.

## Combustion products

Use two items initially: **mineral ash** and **carbon-rich residue**. Their names and textures should make them easy to distinguish in a chest. Fuel rules determine the yield using cumulative burn energy, not the number of input items. A coal block and its coal ingredients must have equivalent total energy and waste, apart from any explicit vanilla burn-duration advantage.

Proposed uses:

- Mineral ash: a shaped ash-and-dirt recipe produces a soil amendment for farming. Its effect needs balancing separately from bone meal; it should not make infinite new dirt from nothing.
- Carbon-rich residue: a shaped recipe with sand and planks produces a filter cartridge. This can become an air-system consumable when filtration is implemented; until that feature exists, give it a concrete crafting use such as a shaped dark construction block recipe.

Burn products are not accepted as engine fuel by default. Do not convert one residue into another fuel with greater recoverable energy. If we prefer one universal residue item, keep its yield data-driven and simplify these recipes accordingly.

Engines reserve output capacity before accepting a new fuel item. For long-burning items, reserve the complete pending residue and container return, persist that reservation, then materialize the residue as combustion work completes. If output space runs out, finish only the already reserved work and refuse further fuel. The player sees `Empty the residue output to continue`, not a silent stall. Hopper extraction of finished output must not release a reservation for waste that has not been produced yet.

The initial implementation can delay fractional residue until enough has accumulated for a whole item. Persist that fractional amount; breaking and replacing a machine must not erase it or manufacture a whole item repeatedly. On destruction, unfinished burn energy is lost and already earned fractional waste remains accounted for; the exact break/drop policy is in Q7.

## Engines and levels

Keep the existing engine as **level I**. Levels II and III are shaped upgrades using the lower-level engine and ordinary iron, copper, glass, redstone, pistons, and circuit boards. Do not require a new ore or another mod.

| Engine | Maximum thrust budget | Fuel efficiency | Alternator ceiling | Role |
| --- | --- | --- | --- | --- |
| I | 2 thrust units | 1.00x | 2 electrical power | Starter ships |
| II | 4 thrust units | 1.15x useful work per fuel | 4 electrical power | Larger ships or fewer engine blocks |
| III | 8 thrust units | 1.30x useful work per fuel | 6 electrical power | Efficient compact machinery |

These values start from the existing navigation budget of up to eight propulsion units. Preserve the current movement feel initially: levels change available acceleration and endurance, rather than introducing mass, torque, or a new flight model in the same update. Engine direction and exhaust-clearance rules remain visible on the equipment page.

Throttle requests are 0–100%. Sum actual delivered thrust across enabled, connected engines, subject to the current navigation cap. Allocate work deterministically and rotate equal-priority machines to avoid draining one engine while leaving the others untouched. Simultaneous forward and vertical inputs share a bounded thrust request; each axis must not independently claim the same combustion budget.

Coasting consumes no propulsion fuel. Commanded acceleration, rotation, and active braking consume it. No free rotational steering during a propulsion blackout. The existing velocity damping/stop-on-zero-power behavior needs deliberate treatment: proposal is to retain a safe arcade brake, display it as assisted braking, and account for its requested work. Keep emergency damping behavior explicit rather than allowing a refactor to silently change piloting. Q6 asks how much inertial behavior is wanted.

Engines do not consume cabin air. Their reaction package is abstracted into the combustible fuel model, so the same engine works on a planet and in vacuum. Separate oxidizer tanks are a future decision, not an implicit extra requirement.

## Engine electricity generation

Each engine has an alternator switch and requested electrical output. Proposal: generating up to its rated output costs **10% more fuel for the same propulsion work at full electrical output**, with the extra cost scaling with actual electrical work. Do not charge the penalty if the bus cannot use the output.

The alternator has two operating cases:

- Under thrust: split combustion work between propulsion and electricity, calculate both from one debit, and report the efficiency loss.
- Stationary generation: the engine runs as a generator with zero thrust, consumes an explicit fuel amount per electrical energy unit, and never rotates or translates the ship.

The extra 10% is a balance target, not a rule that electricity is free at zero throttle. A stationary engine must always consume fuel to generate. At full throttle with a combustion-throughput limit, electrical generation reduces available thrust if the extra fuel throughput cannot be supplied.

Automatic alternator mode covers an electrical deficit, then turns down when dedicated supply is sufficient. The engine terminal shows `Thrust 75% | Alternator 2/4 | Fuel cost +5%`, with actual values calculated from delivered work. Engine propulsion availability and electrical output are computed together, once per server tick.

## Electrical generation

| Source | Fuel / condition | Proposed output | Behavior |
| --- | --- | --- | --- |
| Existing generator → combustion generator I | Same combustible fuel rules | Up to 8 power | Runs to demand, creates residue, useful in enclosed machinery rooms with a clear exhaust |
| Engine alternator | Engine fuel and enabled alternator | See engine table | Optional combined propulsion/generation |
| Solar panel | Direct sunlight and a clear collecting face | Up to 2 power per panel at the initial reference brightness | No fuel; output depends on the actual star, orientation, occlusion, and weather |
| Later generation sources | Separate designs | Undecided | The bus accepts them through the same producer interface |

Solar must use the **server's celestial definition and ship pose**, not screen brightness or a client screenshot. On a planet, local day/night, precipitation, and obstruction reduce production. In space there is no rain; facing away from the star, hull shadowing, and eclipses reduce output. Sample cached occlusion at a bounded rate, updating on hull/pose changes, rather than tracing from every panel every tick. Set an initial brightness cap so flying toward a star does not overflow generation or bypass every other source.

The first implementation should include solar and the fuel-burning generator. Geothermal, nuclear, wind, and other sources are possible extensions; they are not automatically committed by this proposal. Q8 lets you choose a third source if desired.

Dispatch solar first, then efficient dedicated generators, then demand-driven engine alternators. Permit player override in engineering. Charge batteries from real surplus. Stop consuming generator fuel when there is no useful electrical demand or available battery capacity, retaining any remaining burn buffer.

## Batteries and levels

| Battery | Capacity | Charge / discharge ceiling | Time supplying a 4-power load alone |
| --- | --- | --- | --- |
| I, existing battery | 1,200 energy | 4 per tick | 15 seconds |
| II | 4,800 energy | 8 per tick | 60 seconds |
| III | 19,200 energy | 16 per tick | 240 seconds |

Those durations expose a limitation of the current battery: it is a short buffer, not enough for a long survival blackout. Keep them visible for critique; Q3 asks whether the level-I baseline should instead last minutes. Values are proposals, and changing them must update tooltips, diagnostics, recipes, tests, and the guide together.

Capacity and transfer rate are separate. A huge nearly full battery can still fail to supply a high instantaneous load. Several batteries combine both limits. Charge and discharge fairly without moving energy between equal-priority batteries on an otherwise idle bus.

Controls: enabled, charge-only, discharge-only, emergency reserve, and normal. A reserve percentage protects life support and controls while shedding optional cameras/weapons first. The player must explicitly override the reserve before other loads spend it. Battery tiers are hardware levels; state of charge is displayed separately as a percentage and remaining energy.

No passive energy creation, and no initial full charge on placement. Upgrading preserves stored energy and cannot increase it just because capacity increased. A broken battery drops a stateful battery item with its tier and stored charge; crafting, stacking, placement, transfer, and explosion handling must preserve or explicitly destroy that energy without duplication.

## Starting fuel balance

Use vanilla burn ticks as **combustion units (CU)**. Coal's 1,600 furnace ticks provide 1,600 CU; a 100-tick stick provides 100 CU. Start a level-I engine at **0.25 CU per tick at full rated thrust**. That gives one coal about **5 minutes 20 seconds** of continuous full-throttle operation before alternator use. Lower throttle uses proportionally less fuel. This is a proposed playtest target; Q2 asks for the desired endurance.

For dedicated generation and stationary alternator operation, start from **1 CU → 80 electrical energy** before machine efficiency. At a continuous 8-power load, coal then lasts about **13 minutes 20 seconds**. A 2-power level-I stationary alternator would use 0.025 CU per tick; at full rated propulsion plus full alternator it consumes 0.275 CU per tick, matching the 10% penalty target. Publish the conversion and throughput limits in data rather than embedding independent contradictory constants in each controller.

Track milli-CU and remainders with bounded integer arithmetic. Treat no-load operation, tiny fuel items, full batteries, low throttle, partial residue yields, and server restart as conservation cases. A UI preview must never consume fuel or charge a battery.

Launch, landing, and fast travel need an explicit charge policy. Proposal: quote a fixed maneuver fuel cost, reserve it during preflight, and debit at the same durable commit boundary as travel. Reject with a useful shortfall message before moving blocks. For local thrust, spend continuously. Q4 asks whether fast travel should use the same fuel or eventually a separate resource. Do not hard-code an arbitrary per-block mass cost while mass is not part of the flight simulation.

## Inventories, controls, and automation

Give engines and combustion generators real local inventories: three fuel slots, two residue slots, and one container-return slot. Shared electrical power does not imply invisible fuel transport between engines. Start with vanilla hoppers: inputs on top/sides, extraction underneath, no extraction of output reservations. Keep the machine's inventory authoritative when standing alone, registered, launched, landed, mined, or damaged.

Machine right-click opens its focused fuel/inventory page with real item icons, fuel duration, residue status, tier, throttle/output, and actionable fault text. The existing 80-column terminal remains the engineering/control interface. It summarizes the fleet of devices and changes allocation/modes; it should not become a huge inventory screen drawn from text characters.

The helm shows a short preflight summary: usable engines, fuel endurance at current throttle, electricity surplus/deficit, battery endurance, and blocked exhausts. Full calculations stay on engineering pages. Tooltips state whether a fuel is clean/dirty, burn value, and expected product before the player inserts it.

Expose the same server-side operations to the custom computer OS: read engine/battery/generator state, set throttle and alternator mode, set reserves/priorities, and query fuel compatibility. Native handlers validate ownership, connection, loaded chunks, and budgets. Scripts use the existing device API and cannot bypass fuel consumption, put items into another player's machine, or force-load an unloaded ship.

## Implementation boundaries and save safety

Introduce fuel definitions, per-machine combustion state, propulsion demand/results, generation dispatch, and battery policy as separate components. Let one ship tick plan the work and atomically apply the resulting debits/credits. `PowerBus` stays the electrical allocator; `FlightDynamics` receives actual available thrust, not a renamed electrical grant.

Real machine inventories need registered portable block entities plus transfer support. Ship-owned device UUIDs continue to identify controls and grouping; inventory state has one authoritative owner, not a second mutable copy in both the block entity and ship metadata. The existing travel journal snapshots the inventory, remaining burn energy, reservations, battery charge, tier, and policy together. Rollback and replay must not duplicate input fuel, residues, bucket returns, or energy.

Machine inventory menus use normal server-validated synchronization. Client packets carry requests, never authoritative fuel values. Ship-local computation pauses physical generation/consumption when chunks unload. Detached scripts may keep computing as already agreed; their world actions wait for loaded chunks. No offline catch-up fuel burn or battery charging in this first model.

Existing engines, generators, and batteries retain their IDs and become level I. Existing battery energy remains unchanged unless a separately approved capacity change requires migration. Existing development generators currently generate free electricity; migrating them straight to empty survival generators can strand a ship. Proposal: an explicit legacy conversion notice and a one-time recovery kit containing a finite fuel allowance, obtainable from its helm exactly once and recorded durably. No perpetual exemption or unlimited restart gifts. Q9 asks whether you prefer that transition or immediate strict survival rules.

## Acceptance tests

Before shipping fuel consumption, cover:

- Every loaded vanilla furnace fuel and container remainder; invalid items; item/tag overrides; live reload; ordinary versus enchanted/name-bearing fuel items.
- Equal-work conservation across coal/coal blocks, low throttle, simultaneous input axes, fractional CU, tiny sticks, mixed fuel slots, and full/no electrical demand.
- Every tier, alternator on/off/auto, stationary generation, throughput saturation, disabled or disconnected engines, blocked exhaust, and insufficient support power.
- Residue reservation under hoppers, a full output, pending fractional waste, output removal, break/replacement, destruction, and long-burning lava buckets.
- Solar day/night/rain/thunder, airless planets, ship rotation, obstruction, eclipse, distance limits, chunk unloading, and bounded occlusion work.
- Battery capacity/rate limits, zero-energy placement, upgrade charge preservation, reserve behavior, fair distribution, no idle circulation, and damaged battery drops.
- No resource mutation during inspection; two players using the same menu; stale/out-of-range packets; script API parity with player actions.
- Launch/landing rollback and fresh-process recovery at every existing journal boundary, with exact inventory/energy totals before and after.
- Reload migration, one-time legacy allowance replay prevention, full server restart, no forced chunk loads, and stable playtest identities.

## Q&A for your critique

Reply with question numbers and your choices; these are proposals until you decide.

1. **What does “burning everything” include?** A: every vanilla furnace fuel, plus data-defined additions (proposed). B: a broader combustible tag including things such as food, leather, and paper, with our own burn values. C: literally any item, including stone/metals, with an explicit fictional conversion rule. Which items should definitely burn?
2. **How long should fuel last?** Is about 5 minutes of full thrust per coal in a starter engine right, or should it be closer to 1, 10, or 30 minutes? Is a proportional 10% maximum alternator penalty the right tradeoff?
3. **What are the levels?** Are three engine/battery hardware tiers what you mean? Should the first battery remain the current 15-second buffer at 4-power draw, or start around 1–2 minutes instead?
4. **What pays for travel?** A: combustible fuel for launch/landing/jumps and local thrust (proposed). B: fuel for local maneuvers, electricity for jumps. C: a later dedicated jump resource. Should the helm protect a landing fuel reserve by default?
5. **How should clean fuel and waste work?** A: two residue items, dirty vanilla fuels and a craftable/refined clean pellet (proposed). B: one universal residue item. C: more distinct byproducts. Are farming/filter/construction uses appealing, and should refining use an existing generator/engine menu or a separate machine?
6. **What happens when engines stop?** A: keep the current easy arcade flight and make brake/steering costs explicit (proposed). B: preserve velocity and require fueled braking, with a separate emergency rescue option. Should dirty fuel eventually foul engines, or should its downside remain extra waste for now?
7. **How physical should fuel handling be?** A: individual engine inventories fed by hoppers (proposed). B: one ship fuel bunker that supplies connected engines. When a burning engine is mined, should its remaining burn energy be discarded (proposed) or saved in its dropped item?
8. **Which electrical sources should launch together?** A: combustion generator, solar, and engine alternators (proposed). B: those plus another source now. If B, which source? Is celestial orientation/shadowing worth including immediately, or should the first solar panel use a simpler clear-sky check?
9. **How should existing survival ships migrate?** A: one finite, durably recorded conversion fuel kit (proposed). B: require players to supply fuel immediately. C: another migration rule. Should batteries also retain the old capacity until explicitly upgraded?
