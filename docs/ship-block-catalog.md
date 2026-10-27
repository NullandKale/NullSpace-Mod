# Vanilla ship block catalog

Generated from the pinned Minecraft registry and the same policy used by ship preflight. Run `./test.ps1`, then `./scripts/Update-ShipBlockCatalog.ps1` to refresh. See [the support guide](ship-block-support.md) for gameplay rules and verification scope.

- SUPPORTED: 674 block IDs
- CONDITIONAL: 584 block IDs
- UNSUPPORTED: 28 block IDs

These are **transport eligibility** labels, not proof that every possible state or arrangement works. Every row requires a connected, bounded ship; no pending scheduled ticks or queued actions; an intact cabin and usable exit; and clear launch/landing columns. Conditions describe additional restrictions. Supported transport does not make a partial block airtight, retarget respawn/compass bindings, or supply a machine with power, water, fuel, or a valid vanilla structure.

| Block ID | Name | Transport | Block entity | Additional conditions |
| --- | --- | --- | --- | --- |
| `minecraft:acacia_button` | Acacia Button | SUPPORTED | - |  |
| `minecraft:acacia_door` | Acacia Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:acacia_fence` | Acacia Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_fence_gate` | Acacia Fence Gate | SUPPORTED | - |  |
| `minecraft:acacia_hanging_sign` | Acacia Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_leaves` | Acacia Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_log` | Acacia Log | SUPPORTED | - |  |
| `minecraft:acacia_planks` | Acacia Planks | SUPPORTED | - |  |
| `minecraft:acacia_pressure_plate` | Acacia Pressure Plate | SUPPORTED | - |  |
| `minecraft:acacia_sapling` | Acacia Sapling | SUPPORTED | - |  |
| `minecraft:acacia_shelf` | Acacia Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_sign` | Acacia Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_slab` | Acacia Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_stairs` | Acacia Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_trapdoor` | Acacia Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:acacia_wall_hanging_sign` | Acacia Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_wall_sign` | Acacia Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:acacia_wood` | Acacia Wood | SUPPORTED | - |  |
| `minecraft:activator_rail` | Activator Rail | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:air` | Air | UNSUPPORTED | - | Empty space, not captured ship material. |
| `minecraft:allium` | Allium | SUPPORTED | - |  |
| `minecraft:amethyst_block` | Block of Amethyst | SUPPORTED | - |  |
| `minecraft:amethyst_cluster` | Amethyst Cluster | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:ancient_debris` | Ancient Debris | SUPPORTED | - |  |
| `minecraft:andesite` | Andesite | SUPPORTED | - |  |
| `minecraft:andesite_slab` | Andesite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:andesite_stairs` | Andesite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:andesite_wall` | Andesite Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:anvil` | Anvil | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:attached_melon_stem` | Attached Melon Stem | SUPPORTED | - |  |
| `minecraft:attached_pumpkin_stem` | Attached Pumpkin Stem | SUPPORTED | - |  |
| `minecraft:azalea` | Azalea | SUPPORTED | - |  |
| `minecraft:azalea_leaves` | Azalea Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:azure_bluet` | Azure Bluet | SUPPORTED | - |  |
| `minecraft:bamboo` | Bamboo | SUPPORTED | - |  |
| `minecraft:bamboo_block` | Block of Bamboo | SUPPORTED | - |  |
| `minecraft:bamboo_button` | Bamboo Button | SUPPORTED | - |  |
| `minecraft:bamboo_door` | Bamboo Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:bamboo_fence` | Bamboo Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_fence_gate` | Bamboo Fence Gate | SUPPORTED | - |  |
| `minecraft:bamboo_hanging_sign` | Bamboo Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_mosaic` | Bamboo Mosaic | SUPPORTED | - |  |
| `minecraft:bamboo_mosaic_slab` | Bamboo Mosaic Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_mosaic_stairs` | Bamboo Mosaic Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_planks` | Bamboo Planks | SUPPORTED | - |  |
| `minecraft:bamboo_pressure_plate` | Bamboo Pressure Plate | SUPPORTED | - |  |
| `minecraft:bamboo_sapling` | Bamboo Shoot | SUPPORTED | - |  |
| `minecraft:bamboo_shelf` | Bamboo Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_sign` | Bamboo Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_slab` | Bamboo Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_stairs` | Bamboo Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_trapdoor` | Bamboo Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:bamboo_wall_hanging_sign` | Bamboo Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bamboo_wall_sign` | Bamboo Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:barrel` | Barrel | SUPPORTED | `minecraft:barrel` |  |
| `minecraft:barrier` | Barrier | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:basalt` | Basalt | SUPPORTED | - |  |
| `minecraft:beacon` | Beacon | CONDITIONAL | `minecraft:beacon` | Effects recalculate from the destination environment and physical structure. |
| `minecraft:bedrock` | Bedrock | SUPPORTED | - |  |
| `minecraft:bee_nest` | Bee Nest | CONDITIONAL | `minecraft:beehive` | Stored bees travel. Flower memories follow captured flowers; planetary flower memories are cleared. |
| `minecraft:beehive` | Beehive | CONDITIONAL | `minecraft:beehive` | Stored bees travel. Flower memories follow captured flowers; planetary flower memories are cleared. |
| `minecraft:beetroots` | Beetroots | SUPPORTED | - |  |
| `minecraft:bell` | Bell | SUPPORTED | `minecraft:bell` |  |
| `minecraft:big_dripleaf` | Big Dripleaf | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:big_dripleaf_stem` | Big Dripleaf Stem | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_button` | Birch Button | SUPPORTED | - |  |
| `minecraft:birch_door` | Birch Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:birch_fence` | Birch Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_fence_gate` | Birch Fence Gate | SUPPORTED | - |  |
| `minecraft:birch_hanging_sign` | Birch Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_leaves` | Birch Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_log` | Birch Log | SUPPORTED | - |  |
| `minecraft:birch_planks` | Birch Planks | SUPPORTED | - |  |
| `minecraft:birch_pressure_plate` | Birch Pressure Plate | SUPPORTED | - |  |
| `minecraft:birch_sapling` | Birch Sapling | SUPPORTED | - |  |
| `minecraft:birch_shelf` | Birch Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_sign` | Birch Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_slab` | Birch Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_stairs` | Birch Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_trapdoor` | Birch Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:birch_wall_hanging_sign` | Birch Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_wall_sign` | Birch Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:birch_wood` | Birch Wood | SUPPORTED | - |  |
| `minecraft:black_banner` | Black Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:black_bed` | Black Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:black_candle` | Black Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:black_candle_cake` | Cake with Black Candle | SUPPORTED | - |  |
| `minecraft:black_carpet` | Black Carpet | SUPPORTED | - |  |
| `minecraft:black_concrete` | Black Concrete | SUPPORTED | - |  |
| `minecraft:black_concrete_powder` | Black Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:black_concrete_slab` | Black Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:black_concrete_stairs` | Black Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:black_glazed_terracotta` | Black Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:black_shulker_box` | Black Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:black_stained_glass` | Black Stained Glass | SUPPORTED | - |  |
| `minecraft:black_stained_glass_pane` | Black Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:black_terracotta` | Black Terracotta | SUPPORTED | - |  |
| `minecraft:black_wall_banner` | Black Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:black_wool` | Black Wool | SUPPORTED | - |  |
| `minecraft:black_wool_slab` | Black Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:black_wool_stairs` | Black Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blackstone` | Blackstone | SUPPORTED | - |  |
| `minecraft:blackstone_slab` | Blackstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blackstone_stairs` | Blackstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blackstone_wall` | Blackstone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blast_furnace` | Blast Furnace | SUPPORTED | `minecraft:blast_furnace` |  |
| `minecraft:blue_banner` | Blue Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:blue_bed` | Blue Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:blue_candle` | Blue Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blue_candle_cake` | Cake with Blue Candle | SUPPORTED | - |  |
| `minecraft:blue_carpet` | Blue Carpet | SUPPORTED | - |  |
| `minecraft:blue_concrete` | Blue Concrete | SUPPORTED | - |  |
| `minecraft:blue_concrete_powder` | Blue Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:blue_concrete_slab` | Blue Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blue_concrete_stairs` | Blue Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blue_glazed_terracotta` | Blue Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:blue_ice` | Blue Ice | SUPPORTED | - |  |
| `minecraft:blue_orchid` | Blue Orchid | SUPPORTED | - |  |
| `minecraft:blue_shulker_box` | Blue Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:blue_stained_glass` | Blue Stained Glass | SUPPORTED | - |  |
| `minecraft:blue_stained_glass_pane` | Blue Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blue_terracotta` | Blue Terracotta | SUPPORTED | - |  |
| `minecraft:blue_wall_banner` | Blue Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:blue_wool` | Blue Wool | SUPPORTED | - |  |
| `minecraft:blue_wool_slab` | Blue Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:blue_wool_stairs` | Blue Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bone_block` | Bone Block | SUPPORTED | - |  |
| `minecraft:bookshelf` | Bookshelf | SUPPORTED | - |  |
| `minecraft:brain_coral` | Brain Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brain_coral_block` | Brain Coral Block | SUPPORTED | - |  |
| `minecraft:brain_coral_fan` | Brain Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brain_coral_wall_fan` | Brain Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brewing_stand` | Brewing Stand | SUPPORTED | `minecraft:brewing_stand` |  |
| `minecraft:brick_slab` | Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brick_stairs` | Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brick_wall` | Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bricks` | Bricks | SUPPORTED | - |  |
| `minecraft:brown_banner` | Brown Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:brown_bed` | Brown Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:brown_candle` | Brown Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brown_candle_cake` | Cake with Brown Candle | SUPPORTED | - |  |
| `minecraft:brown_carpet` | Brown Carpet | SUPPORTED | - |  |
| `minecraft:brown_concrete` | Brown Concrete | SUPPORTED | - |  |
| `minecraft:brown_concrete_powder` | Brown Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:brown_concrete_slab` | Brown Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brown_concrete_stairs` | Brown Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brown_glazed_terracotta` | Brown Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:brown_mushroom` | Brown Mushroom | SUPPORTED | - |  |
| `minecraft:brown_mushroom_block` | Brown Mushroom Block | SUPPORTED | - |  |
| `minecraft:brown_shulker_box` | Brown Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:brown_stained_glass` | Brown Stained Glass | SUPPORTED | - |  |
| `minecraft:brown_stained_glass_pane` | Brown Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brown_terracotta` | Brown Terracotta | SUPPORTED | - |  |
| `minecraft:brown_wall_banner` | Brown Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:brown_wool` | Brown Wool | SUPPORTED | - |  |
| `minecraft:brown_wool_slab` | Brown Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:brown_wool_stairs` | Brown Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bubble_column` | Bubble Column | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bubble_coral` | Bubble Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bubble_coral_block` | Bubble Coral Block | SUPPORTED | - |  |
| `minecraft:bubble_coral_fan` | Bubble Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:bubble_coral_wall_fan` | Bubble Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:budding_amethyst` | Budding Amethyst | SUPPORTED | - |  |
| `minecraft:bush` | Bush | SUPPORTED | - |  |
| `minecraft:cactus` | Cactus | SUPPORTED | - |  |
| `minecraft:cactus_flower` | Cactus Flower | SUPPORTED | - |  |
| `minecraft:cake` | Cake | SUPPORTED | - |  |
| `minecraft:calcite` | Calcite | SUPPORTED | - |  |
| `minecraft:calibrated_sculk_sensor` | Calibrated Sculk Sensor | CONDITIONAL | `minecraft:calibrated_sculk_sensor` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. No pending vibrations or block actions; summoning shriekers are excluded. |
| `minecraft:campfire` | Campfire | CONDITIONAL | `minecraft:campfire` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:candle` | Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:candle_cake` | Cake with Candle | SUPPORTED | - |  |
| `minecraft:carrots` | Carrots | SUPPORTED | - |  |
| `minecraft:cartography_table` | Cartography Table | SUPPORTED | - |  |
| `minecraft:carved_pumpkin` | Carved Pumpkin | SUPPORTED | - |  |
| `minecraft:cauldron` | Cauldron | SUPPORTED | - |  |
| `minecraft:cave_air` | Cave Air | UNSUPPORTED | - | Empty space, not captured ship material. |
| `minecraft:cave_vines` | Cave Vines | SUPPORTED | - |  |
| `minecraft:cave_vines_plant` | Cave Vines Plant | SUPPORTED | - |  |
| `minecraft:chain_command_block` | Chain Command Block | UNSUPPORTED | `minecraft:command_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:cherry_button` | Cherry Button | SUPPORTED | - |  |
| `minecraft:cherry_door` | Cherry Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:cherry_fence` | Cherry Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_fence_gate` | Cherry Fence Gate | SUPPORTED | - |  |
| `minecraft:cherry_hanging_sign` | Cherry Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_leaves` | Cherry Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_log` | Cherry Log | SUPPORTED | - |  |
| `minecraft:cherry_planks` | Cherry Planks | SUPPORTED | - |  |
| `minecraft:cherry_pressure_plate` | Cherry Pressure Plate | SUPPORTED | - |  |
| `minecraft:cherry_sapling` | Cherry Sapling | SUPPORTED | - |  |
| `minecraft:cherry_shelf` | Cherry Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_sign` | Cherry Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_slab` | Cherry Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_stairs` | Cherry Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_trapdoor` | Cherry Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:cherry_wall_hanging_sign` | Cherry Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_wall_sign` | Cherry Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cherry_wood` | Cherry Wood | SUPPORTED | - |  |
| `minecraft:chest` | Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:chipped_anvil` | Chipped Anvil | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:chiseled_bookshelf` | Chiseled Bookshelf | SUPPORTED | `minecraft:chiseled_bookshelf` |  |
| `minecraft:chiseled_cinnabar` | Chiseled Cinnabar | SUPPORTED | - |  |
| `minecraft:chiseled_copper` | Chiseled Copper | SUPPORTED | - |  |
| `minecraft:chiseled_deepslate` | Chiseled Deepslate | SUPPORTED | - |  |
| `minecraft:chiseled_nether_bricks` | Chiseled Nether Bricks | SUPPORTED | - |  |
| `minecraft:chiseled_polished_blackstone` | Chiseled Polished Blackstone | SUPPORTED | - |  |
| `minecraft:chiseled_quartz_block` | Chiseled Quartz Block | SUPPORTED | - |  |
| `minecraft:chiseled_red_sandstone` | Chiseled Red Sandstone | SUPPORTED | - |  |
| `minecraft:chiseled_resin_bricks` | Chiseled Resin Bricks | SUPPORTED | - |  |
| `minecraft:chiseled_sandstone` | Chiseled Sandstone | SUPPORTED | - |  |
| `minecraft:chiseled_stone_bricks` | Chiseled Stone Bricks | SUPPORTED | - |  |
| `minecraft:chiseled_sulfur` | Chiseled Sulfur | SUPPORTED | - |  |
| `minecraft:chiseled_tuff` | Chiseled Tuff | SUPPORTED | - |  |
| `minecraft:chiseled_tuff_bricks` | Chiseled Tuff Bricks | SUPPORTED | - |  |
| `minecraft:chorus_flower` | Chorus Flower | SUPPORTED | - |  |
| `minecraft:chorus_plant` | Chorus Plant | SUPPORTED | - |  |
| `minecraft:cinnabar` | Cinnabar | SUPPORTED | - |  |
| `minecraft:cinnabar_brick_slab` | Cinnabar Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cinnabar_brick_stairs` | Cinnabar Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cinnabar_brick_wall` | Cinnabar Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cinnabar_bricks` | Cinnabar Bricks | SUPPORTED | - |  |
| `minecraft:cinnabar_slab` | Cinnabar Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cinnabar_stairs` | Cinnabar Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cinnabar_wall` | Cinnabar Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:clay` | Clay | SUPPORTED | - |  |
| `minecraft:closed_eyeblossom` | Closed Eyeblossom | SUPPORTED | - |  |
| `minecraft:coal_block` | Block of Coal | SUPPORTED | - |  |
| `minecraft:coal_ore` | Coal Ore | SUPPORTED | - |  |
| `minecraft:coarse_dirt` | Coarse Dirt | SUPPORTED | - |  |
| `minecraft:cobbled_deepslate` | Cobbled Deepslate | SUPPORTED | - |  |
| `minecraft:cobbled_deepslate_slab` | Cobbled Deepslate Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobbled_deepslate_stairs` | Cobbled Deepslate Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobbled_deepslate_wall` | Cobbled Deepslate Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobblestone` | Cobblestone | SUPPORTED | - |  |
| `minecraft:cobblestone_slab` | Cobblestone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobblestone_stairs` | Cobblestone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobblestone_wall` | Cobblestone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cobweb` | Cobweb | SUPPORTED | - |  |
| `minecraft:cocoa` | Cocoa | SUPPORTED | - |  |
| `minecraft:command_block` | Command Block | UNSUPPORTED | `minecraft:command_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:comparator` | Redstone Comparator | CONDITIONAL | `minecraft:comparator` | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:composter` | Composter | SUPPORTED | - |  |
| `minecraft:conduit` | Conduit | CONDITIONAL | `minecraft:conduit` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Effects recalculate from the destination environment and physical structure. |
| `minecraft:copper_bars` | Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_block` | Block of Copper | SUPPORTED | - |  |
| `minecraft:copper_bulb` | Copper Bulb | SUPPORTED | - |  |
| `minecraft:copper_chain` | Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_chest` | Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_door` | Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:copper_golem_statue` | Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_grate` | Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_lantern` | Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:copper_ore` | Copper Ore | SUPPORTED | - |  |
| `minecraft:copper_torch` | Copper Torch | SUPPORTED | - |  |
| `minecraft:copper_trapdoor` | Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:copper_wall_torch` | Copper Torch | SUPPORTED | - |  |
| `minecraft:cornflower` | Cornflower | SUPPORTED | - |  |
| `minecraft:cracked_deepslate_bricks` | Cracked Deepslate Bricks | SUPPORTED | - |  |
| `minecraft:cracked_deepslate_tiles` | Cracked Deepslate Tiles | SUPPORTED | - |  |
| `minecraft:cracked_nether_bricks` | Cracked Nether Bricks | SUPPORTED | - |  |
| `minecraft:cracked_polished_blackstone_bricks` | Cracked Polished Blackstone Bricks | SUPPORTED | - |  |
| `minecraft:cracked_stone_bricks` | Cracked Stone Bricks | SUPPORTED | - |  |
| `minecraft:crafter` | Crafter | CONDITIONAL | `minecraft:crafter` | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:crafting_table` | Crafting Table | SUPPORTED | - |  |
| `minecraft:creaking_heart` | Creaking Heart | UNSUPPORTED | `minecraft:creaking_heart` | Creaking hearts retain a world-bound protector. Leave this heart on the planet. |
| `minecraft:creeper_head` | Creeper Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:creeper_wall_head` | Creeper Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:crimson_button` | Crimson Button | SUPPORTED | - |  |
| `minecraft:crimson_door` | Crimson Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:crimson_fence` | Crimson Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_fence_gate` | Crimson Fence Gate | SUPPORTED | - |  |
| `minecraft:crimson_fungus` | Crimson Fungus | SUPPORTED | - |  |
| `minecraft:crimson_hanging_sign` | Crimson Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_hyphae` | Crimson Hyphae | SUPPORTED | - |  |
| `minecraft:crimson_nylium` | Crimson Nylium | SUPPORTED | - |  |
| `minecraft:crimson_planks` | Crimson Planks | SUPPORTED | - |  |
| `minecraft:crimson_pressure_plate` | Crimson Pressure Plate | SUPPORTED | - |  |
| `minecraft:crimson_roots` | Crimson Roots | SUPPORTED | - |  |
| `minecraft:crimson_shelf` | Crimson Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_sign` | Crimson Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_slab` | Crimson Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_stairs` | Crimson Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_stem` | Crimson Stem | SUPPORTED | - |  |
| `minecraft:crimson_trapdoor` | Crimson Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:crimson_wall_hanging_sign` | Crimson Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crimson_wall_sign` | Crimson Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:crying_obsidian` | Crying Obsidian | SUPPORTED | - |  |
| `minecraft:cut_copper` | Cut Copper | SUPPORTED | - |  |
| `minecraft:cut_copper_slab` | Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cut_copper_stairs` | Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cut_red_sandstone` | Cut Red Sandstone | SUPPORTED | - |  |
| `minecraft:cut_red_sandstone_slab` | Cut Red Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cut_sandstone` | Cut Sandstone | SUPPORTED | - |  |
| `minecraft:cut_sandstone_slab` | Cut Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_banner` | Cyan Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:cyan_bed` | Cyan Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:cyan_candle` | Cyan Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_candle_cake` | Cake with Cyan Candle | SUPPORTED | - |  |
| `minecraft:cyan_carpet` | Cyan Carpet | SUPPORTED | - |  |
| `minecraft:cyan_concrete` | Cyan Concrete | SUPPORTED | - |  |
| `minecraft:cyan_concrete_powder` | Cyan Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:cyan_concrete_slab` | Cyan Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_concrete_stairs` | Cyan Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_glazed_terracotta` | Cyan Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:cyan_shulker_box` | Cyan Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:cyan_stained_glass` | Cyan Stained Glass | SUPPORTED | - |  |
| `minecraft:cyan_stained_glass_pane` | Cyan Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_terracotta` | Cyan Terracotta | SUPPORTED | - |  |
| `minecraft:cyan_wall_banner` | Cyan Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:cyan_wool` | Cyan Wool | SUPPORTED | - |  |
| `minecraft:cyan_wool_slab` | Cyan Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:cyan_wool_stairs` | Cyan Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:damaged_anvil` | Damaged Anvil | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:dandelion` | Dandelion | SUPPORTED | - |  |
| `minecraft:dark_oak_button` | Dark Oak Button | SUPPORTED | - |  |
| `minecraft:dark_oak_door` | Dark Oak Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:dark_oak_fence` | Dark Oak Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_fence_gate` | Dark Oak Fence Gate | SUPPORTED | - |  |
| `minecraft:dark_oak_hanging_sign` | Dark Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_leaves` | Dark Oak Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_log` | Dark Oak Log | SUPPORTED | - |  |
| `minecraft:dark_oak_planks` | Dark Oak Planks | SUPPORTED | - |  |
| `minecraft:dark_oak_pressure_plate` | Dark Oak Pressure Plate | SUPPORTED | - |  |
| `minecraft:dark_oak_sapling` | Dark Oak Sapling | SUPPORTED | - |  |
| `minecraft:dark_oak_shelf` | Dark Oak Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_sign` | Dark Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_slab` | Dark Oak Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_stairs` | Dark Oak Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_trapdoor` | Dark Oak Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:dark_oak_wall_hanging_sign` | Dark Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_wall_sign` | Dark Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_oak_wood` | Dark Oak Wood | SUPPORTED | - |  |
| `minecraft:dark_prismarine` | Dark Prismarine | SUPPORTED | - |  |
| `minecraft:dark_prismarine_slab` | Dark Prismarine Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dark_prismarine_stairs` | Dark Prismarine Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:daylight_detector` | Daylight Detector | SUPPORTED | `minecraft:daylight_detector` |  |
| `minecraft:dead_brain_coral` | Dead Brain Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_brain_coral_block` | Dead Brain Coral Block | SUPPORTED | - |  |
| `minecraft:dead_brain_coral_fan` | Dead Brain Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_brain_coral_wall_fan` | Dead Brain Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_bubble_coral` | Dead Bubble Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_bubble_coral_block` | Dead Bubble Coral Block | SUPPORTED | - |  |
| `minecraft:dead_bubble_coral_fan` | Dead Bubble Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_bubble_coral_wall_fan` | Dead Bubble Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_bush` | Dead Bush | SUPPORTED | - |  |
| `minecraft:dead_fire_coral` | Dead Fire Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_fire_coral_block` | Dead Fire Coral Block | SUPPORTED | - |  |
| `minecraft:dead_fire_coral_fan` | Dead Fire Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_fire_coral_wall_fan` | Dead Fire Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_horn_coral` | Dead Horn Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_horn_coral_block` | Dead Horn Coral Block | SUPPORTED | - |  |
| `minecraft:dead_horn_coral_fan` | Dead Horn Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_horn_coral_wall_fan` | Dead Horn Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_tube_coral` | Dead Tube Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_tube_coral_block` | Dead Tube Coral Block | SUPPORTED | - |  |
| `minecraft:dead_tube_coral_fan` | Dead Tube Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dead_tube_coral_wall_fan` | Dead Tube Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:decorated_pot` | Decorated Pot | CONDITIONAL | `minecraft:decorated_pot` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate` | Deepslate | SUPPORTED | - |  |
| `minecraft:deepslate_brick_slab` | Deepslate Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_brick_stairs` | Deepslate Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_brick_wall` | Deepslate Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_bricks` | Deepslate Bricks | SUPPORTED | - |  |
| `minecraft:deepslate_coal_ore` | Deepslate Coal Ore | SUPPORTED | - |  |
| `minecraft:deepslate_copper_ore` | Deepslate Copper Ore | SUPPORTED | - |  |
| `minecraft:deepslate_diamond_ore` | Deepslate Diamond Ore | SUPPORTED | - |  |
| `minecraft:deepslate_emerald_ore` | Deepslate Emerald Ore | SUPPORTED | - |  |
| `minecraft:deepslate_gold_ore` | Deepslate Gold Ore | SUPPORTED | - |  |
| `minecraft:deepslate_iron_ore` | Deepslate Iron Ore | SUPPORTED | - |  |
| `minecraft:deepslate_lapis_ore` | Deepslate Lapis Lazuli Ore | SUPPORTED | - |  |
| `minecraft:deepslate_redstone_ore` | Deepslate Redstone Ore | SUPPORTED | - |  |
| `minecraft:deepslate_tile_slab` | Deepslate Tile Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_tile_stairs` | Deepslate Tile Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_tile_wall` | Deepslate Tile Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:deepslate_tiles` | Deepslate Tiles | SUPPORTED | - |  |
| `minecraft:detector_rail` | Detector Rail | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:diamond_block` | Block of Diamond | SUPPORTED | - |  |
| `minecraft:diamond_ore` | Diamond Ore | SUPPORTED | - |  |
| `minecraft:diorite` | Diorite | SUPPORTED | - |  |
| `minecraft:diorite_slab` | Diorite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:diorite_stairs` | Diorite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:diorite_wall` | Diorite Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dirt` | Dirt | SUPPORTED | - |  |
| `minecraft:dirt_path` | Dirt Path | SUPPORTED | - |  |
| `minecraft:dispenser` | Dispenser | CONDITIONAL | `minecraft:dispenser` | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:dragon_egg` | Dragon Egg | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:dragon_head` | Dragon Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:dragon_wall_head` | Dragon Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:dried_ghast` | Dried Ghast | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:dried_kelp_block` | Dried Kelp Block | SUPPORTED | - |  |
| `minecraft:dripstone_block` | Dripstone Block | SUPPORTED | - |  |
| `minecraft:dropper` | Dropper | CONDITIONAL | `minecraft:dropper` | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:emerald_block` | Block of Emerald | SUPPORTED | - |  |
| `minecraft:emerald_ore` | Emerald Ore | SUPPORTED | - |  |
| `minecraft:enchanting_table` | Enchanting Table | SUPPORTED | `minecraft:enchanting_table` |  |
| `minecraft:end_gateway` | End Gateway | UNSUPPORTED | `minecraft:end_gateway` | Active portals cannot travel. Remove the portal before launching. |
| `minecraft:end_portal` | End Portal | UNSUPPORTED | `minecraft:end_portal` | Active portals cannot travel. Remove the portal before launching. |
| `minecraft:end_portal_frame` | End Portal Frame | SUPPORTED | - |  |
| `minecraft:end_rod` | End Rod | SUPPORTED | - |  |
| `minecraft:end_stone` | End Stone | SUPPORTED | - |  |
| `minecraft:end_stone_brick_slab` | End Stone Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:end_stone_brick_stairs` | End Stone Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:end_stone_brick_wall` | End Stone Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:end_stone_bricks` | End Stone Bricks | SUPPORTED | - |  |
| `minecraft:ender_chest` | Ender Chest | CONDITIONAL | `minecraft:ender_chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Personal ender inventory remains player-owned. |
| `minecraft:exposed_chiseled_copper` | Exposed Chiseled Copper | SUPPORTED | - |  |
| `minecraft:exposed_copper` | Exposed Copper | SUPPORTED | - |  |
| `minecraft:exposed_copper_bars` | Exposed Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_bulb` | Exposed Copper Bulb | SUPPORTED | - |  |
| `minecraft:exposed_copper_chain` | Exposed Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_chest` | Exposed Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_door` | Exposed Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:exposed_copper_golem_statue` | Exposed Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_grate` | Exposed Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_lantern` | Exposed Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_copper_trapdoor` | Exposed Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:exposed_cut_copper` | Exposed Cut Copper | SUPPORTED | - |  |
| `minecraft:exposed_cut_copper_slab` | Exposed Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_cut_copper_stairs` | Exposed Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:exposed_lightning_rod` | Exposed Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:farmland` | Farmland | SUPPORTED | - |  |
| `minecraft:fern` | Fern | SUPPORTED | - |  |
| `minecraft:fire` | Fire | UNSUPPORTED | - | Extinguish open fire before launching. Campfire blocks are supported. |
| `minecraft:fire_coral` | Fire Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:fire_coral_block` | Fire Coral Block | SUPPORTED | - |  |
| `minecraft:fire_coral_fan` | Fire Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:fire_coral_wall_fan` | Fire Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:firefly_bush` | Firefly Bush | SUPPORTED | - |  |
| `minecraft:fletching_table` | Fletching Table | SUPPORTED | - |  |
| `minecraft:flower_pot` | Flower Pot | SUPPORTED | - |  |
| `minecraft:flowering_azalea` | Flowering Azalea | SUPPORTED | - |  |
| `minecraft:flowering_azalea_leaves` | Flowering Azalea Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:frogspawn` | Frogspawn | SUPPORTED | - |  |
| `minecraft:frosted_ice` | Frosted Ice | SUPPORTED | - |  |
| `minecraft:furnace` | Furnace | SUPPORTED | `minecraft:furnace` |  |
| `minecraft:gilded_blackstone` | Gilded Blackstone | SUPPORTED | - |  |
| `minecraft:glass` | Glass | SUPPORTED | - |  |
| `minecraft:glass_pane` | Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:glow_lichen` | Glow Lichen | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:glowstone` | Glowstone | SUPPORTED | - |  |
| `minecraft:gold_block` | Block of Gold | SUPPORTED | - |  |
| `minecraft:gold_ore` | Gold Ore | SUPPORTED | - |  |
| `minecraft:golden_dandelion` | Golden Dandelion | SUPPORTED | - |  |
| `minecraft:granite` | Granite | SUPPORTED | - |  |
| `minecraft:granite_slab` | Granite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:granite_stairs` | Granite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:granite_wall` | Granite Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:grass_block` | Grass Block | SUPPORTED | - |  |
| `minecraft:gravel` | Gravel | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:gray_banner` | Gray Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:gray_bed` | Gray Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:gray_candle` | Gray Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:gray_candle_cake` | Cake with Gray Candle | SUPPORTED | - |  |
| `minecraft:gray_carpet` | Gray Carpet | SUPPORTED | - |  |
| `minecraft:gray_concrete` | Gray Concrete | SUPPORTED | - |  |
| `minecraft:gray_concrete_powder` | Gray Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:gray_concrete_slab` | Gray Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:gray_concrete_stairs` | Gray Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:gray_glazed_terracotta` | Gray Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:gray_shulker_box` | Gray Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:gray_stained_glass` | Gray Stained Glass | SUPPORTED | - |  |
| `minecraft:gray_stained_glass_pane` | Gray Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:gray_terracotta` | Gray Terracotta | SUPPORTED | - |  |
| `minecraft:gray_wall_banner` | Gray Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:gray_wool` | Gray Wool | SUPPORTED | - |  |
| `minecraft:gray_wool_slab` | Gray Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:gray_wool_stairs` | Gray Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_banner` | Green Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:green_bed` | Green Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:green_candle` | Green Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_candle_cake` | Cake with Green Candle | SUPPORTED | - |  |
| `minecraft:green_carpet` | Green Carpet | SUPPORTED | - |  |
| `minecraft:green_concrete` | Green Concrete | SUPPORTED | - |  |
| `minecraft:green_concrete_powder` | Green Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:green_concrete_slab` | Green Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_concrete_stairs` | Green Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_glazed_terracotta` | Green Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:green_shulker_box` | Green Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:green_stained_glass` | Green Stained Glass | SUPPORTED | - |  |
| `minecraft:green_stained_glass_pane` | Green Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_terracotta` | Green Terracotta | SUPPORTED | - |  |
| `minecraft:green_wall_banner` | Green Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:green_wool` | Green Wool | SUPPORTED | - |  |
| `minecraft:green_wool_slab` | Green Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:green_wool_stairs` | Green Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:grindstone` | Grindstone | SUPPORTED | - |  |
| `minecraft:hanging_roots` | Hanging Roots | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:hay_block` | Hay Bale | SUPPORTED | - |  |
| `minecraft:heavy_core` | Heavy Core | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:heavy_weighted_pressure_plate` | Heavy Weighted Pressure Plate | SUPPORTED | - |  |
| `minecraft:honey_block` | Honey Block | SUPPORTED | - |  |
| `minecraft:honeycomb_block` | Honeycomb Block | SUPPORTED | - |  |
| `minecraft:hopper` | Hopper | SUPPORTED | `minecraft:hopper` |  |
| `minecraft:horn_coral` | Horn Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:horn_coral_block` | Horn Coral Block | SUPPORTED | - |  |
| `minecraft:horn_coral_fan` | Horn Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:horn_coral_wall_fan` | Horn Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:ice` | Ice | SUPPORTED | - |  |
| `minecraft:infested_chiseled_stone_bricks` | Infested Chiseled Stone Bricks | SUPPORTED | - |  |
| `minecraft:infested_cobblestone` | Infested Cobblestone | SUPPORTED | - |  |
| `minecraft:infested_cracked_stone_bricks` | Infested Cracked Stone Bricks | SUPPORTED | - |  |
| `minecraft:infested_deepslate` | Infested Deepslate | SUPPORTED | - |  |
| `minecraft:infested_mossy_stone_bricks` | Infested Mossy Stone Bricks | SUPPORTED | - |  |
| `minecraft:infested_stone` | Infested Stone | SUPPORTED | - |  |
| `minecraft:infested_stone_bricks` | Infested Stone Bricks | SUPPORTED | - |  |
| `minecraft:iron_bars` | Iron Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:iron_block` | Block of Iron | SUPPORTED | - |  |
| `minecraft:iron_chain` | Iron Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:iron_door` | Iron Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:iron_ore` | Iron Ore | SUPPORTED | - |  |
| `minecraft:iron_trapdoor` | Iron Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:jack_o_lantern` | Jack o'Lantern | SUPPORTED | - |  |
| `minecraft:jigsaw` | Jigsaw Block | UNSUPPORTED | `minecraft:jigsaw` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:jukebox` | Jukebox | SUPPORTED | `minecraft:jukebox` |  |
| `minecraft:jungle_button` | Jungle Button | SUPPORTED | - |  |
| `minecraft:jungle_door` | Jungle Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:jungle_fence` | Jungle Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_fence_gate` | Jungle Fence Gate | SUPPORTED | - |  |
| `minecraft:jungle_hanging_sign` | Jungle Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_leaves` | Jungle Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_log` | Jungle Log | SUPPORTED | - |  |
| `minecraft:jungle_planks` | Jungle Planks | SUPPORTED | - |  |
| `minecraft:jungle_pressure_plate` | Jungle Pressure Plate | SUPPORTED | - |  |
| `minecraft:jungle_sapling` | Jungle Sapling | SUPPORTED | - |  |
| `minecraft:jungle_shelf` | Jungle Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_sign` | Jungle Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_slab` | Jungle Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_stairs` | Jungle Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_trapdoor` | Jungle Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:jungle_wall_hanging_sign` | Jungle Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_wall_sign` | Jungle Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:jungle_wood` | Jungle Wood | SUPPORTED | - |  |
| `minecraft:kelp` | Kelp | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:kelp_plant` | Kelp Plant | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:ladder` | Ladder | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lantern` | Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lapis_block` | Block of Lapis Lazuli | SUPPORTED | - |  |
| `minecraft:lapis_ore` | Lapis Lazuli Ore | SUPPORTED | - |  |
| `minecraft:large_amethyst_bud` | Large Amethyst Bud | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:large_fern` | Large Fern | SUPPORTED | - |  |
| `minecraft:lava` | Lava | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lava_cauldron` | Lava Cauldron | SUPPORTED | - |  |
| `minecraft:leaf_litter` | Leaf Litter | SUPPORTED | - |  |
| `minecraft:lectern` | Lectern | SUPPORTED | `minecraft:lectern` |  |
| `minecraft:lever` | Lever | SUPPORTED | - |  |
| `minecraft:light` | Light | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_banner` | Light Blue Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:light_blue_bed` | Light Blue Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:light_blue_candle` | Light Blue Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_candle_cake` | Cake with Light Blue Candle | SUPPORTED | - |  |
| `minecraft:light_blue_carpet` | Light Blue Carpet | SUPPORTED | - |  |
| `minecraft:light_blue_concrete` | Light Blue Concrete | SUPPORTED | - |  |
| `minecraft:light_blue_concrete_powder` | Light Blue Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:light_blue_concrete_slab` | Light Blue Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_concrete_stairs` | Light Blue Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_glazed_terracotta` | Light Blue Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:light_blue_shulker_box` | Light Blue Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:light_blue_stained_glass` | Light Blue Stained Glass | SUPPORTED | - |  |
| `minecraft:light_blue_stained_glass_pane` | Light Blue Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_terracotta` | Light Blue Terracotta | SUPPORTED | - |  |
| `minecraft:light_blue_wall_banner` | Light Blue Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:light_blue_wool` | Light Blue Wool | SUPPORTED | - |  |
| `minecraft:light_blue_wool_slab` | Light Blue Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_blue_wool_stairs` | Light Blue Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_banner` | Light Gray Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:light_gray_bed` | Light Gray Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:light_gray_candle` | Light Gray Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_candle_cake` | Cake with Light Gray Candle | SUPPORTED | - |  |
| `minecraft:light_gray_carpet` | Light Gray Carpet | SUPPORTED | - |  |
| `minecraft:light_gray_concrete` | Light Gray Concrete | SUPPORTED | - |  |
| `minecraft:light_gray_concrete_powder` | Light Gray Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:light_gray_concrete_slab` | Light Gray Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_concrete_stairs` | Light Gray Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_glazed_terracotta` | Light Gray Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:light_gray_shulker_box` | Light Gray Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:light_gray_stained_glass` | Light Gray Stained Glass | SUPPORTED | - |  |
| `minecraft:light_gray_stained_glass_pane` | Light Gray Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_terracotta` | Light Gray Terracotta | SUPPORTED | - |  |
| `minecraft:light_gray_wall_banner` | Light Gray Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:light_gray_wool` | Light Gray Wool | SUPPORTED | - |  |
| `minecraft:light_gray_wool_slab` | Light Gray Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_gray_wool_stairs` | Light Gray Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:light_weighted_pressure_plate` | Light Weighted Pressure Plate | SUPPORTED | - |  |
| `minecraft:lightning_rod` | Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lilac` | Lilac | SUPPORTED | - |  |
| `minecraft:lily_of_the_valley` | Lily of the Valley | SUPPORTED | - |  |
| `minecraft:lily_pad` | Lily Pad | SUPPORTED | - |  |
| `minecraft:lime_banner` | Lime Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:lime_bed` | Lime Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:lime_candle` | Lime Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lime_candle_cake` | Cake with Lime Candle | SUPPORTED | - |  |
| `minecraft:lime_carpet` | Lime Carpet | SUPPORTED | - |  |
| `minecraft:lime_concrete` | Lime Concrete | SUPPORTED | - |  |
| `minecraft:lime_concrete_powder` | Lime Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:lime_concrete_slab` | Lime Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lime_concrete_stairs` | Lime Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lime_glazed_terracotta` | Lime Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:lime_shulker_box` | Lime Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:lime_stained_glass` | Lime Stained Glass | SUPPORTED | - |  |
| `minecraft:lime_stained_glass_pane` | Lime Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lime_terracotta` | Lime Terracotta | SUPPORTED | - |  |
| `minecraft:lime_wall_banner` | Lime Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:lime_wool` | Lime Wool | SUPPORTED | - |  |
| `minecraft:lime_wool_slab` | Lime Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lime_wool_stairs` | Lime Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:lodestone` | Lodestone | CONDITIONAL | - | Existing lodestone compasses retain their original dimension/coordinates. |
| `minecraft:loom` | Loom | SUPPORTED | - |  |
| `minecraft:magenta_banner` | Magenta Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:magenta_bed` | Magenta Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:magenta_candle` | Magenta Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magenta_candle_cake` | Cake with Magenta Candle | SUPPORTED | - |  |
| `minecraft:magenta_carpet` | Magenta Carpet | SUPPORTED | - |  |
| `minecraft:magenta_concrete` | Magenta Concrete | SUPPORTED | - |  |
| `minecraft:magenta_concrete_powder` | Magenta Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:magenta_concrete_slab` | Magenta Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magenta_concrete_stairs` | Magenta Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magenta_glazed_terracotta` | Magenta Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:magenta_shulker_box` | Magenta Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:magenta_stained_glass` | Magenta Stained Glass | SUPPORTED | - |  |
| `minecraft:magenta_stained_glass_pane` | Magenta Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magenta_terracotta` | Magenta Terracotta | SUPPORTED | - |  |
| `minecraft:magenta_wall_banner` | Magenta Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:magenta_wool` | Magenta Wool | SUPPORTED | - |  |
| `minecraft:magenta_wool_slab` | Magenta Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magenta_wool_stairs` | Magenta Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:magma_block` | Magma Block | SUPPORTED | - |  |
| `minecraft:mangrove_button` | Mangrove Button | SUPPORTED | - |  |
| `minecraft:mangrove_door` | Mangrove Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:mangrove_fence` | Mangrove Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_fence_gate` | Mangrove Fence Gate | SUPPORTED | - |  |
| `minecraft:mangrove_hanging_sign` | Mangrove Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_leaves` | Mangrove Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_log` | Mangrove Log | SUPPORTED | - |  |
| `minecraft:mangrove_planks` | Mangrove Planks | SUPPORTED | - |  |
| `minecraft:mangrove_pressure_plate` | Mangrove Pressure Plate | SUPPORTED | - |  |
| `minecraft:mangrove_propagule` | Mangrove Propagule | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_roots` | Mangrove Roots | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_shelf` | Mangrove Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_sign` | Mangrove Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_slab` | Mangrove Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_stairs` | Mangrove Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_trapdoor` | Mangrove Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:mangrove_wall_hanging_sign` | Mangrove Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_wall_sign` | Mangrove Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mangrove_wood` | Mangrove Wood | SUPPORTED | - |  |
| `minecraft:medium_amethyst_bud` | Medium Amethyst Bud | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:melon` | Melon | SUPPORTED | - |  |
| `minecraft:melon_stem` | Melon Stem | SUPPORTED | - |  |
| `minecraft:moss_block` | Moss Block | SUPPORTED | - |  |
| `minecraft:moss_carpet` | Moss Carpet | SUPPORTED | - |  |
| `minecraft:mossy_cobblestone` | Mossy Cobblestone | SUPPORTED | - |  |
| `minecraft:mossy_cobblestone_slab` | Mossy Cobblestone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_cobblestone_stairs` | Mossy Cobblestone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_cobblestone_wall` | Mossy Cobblestone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_stone_brick_slab` | Mossy Stone Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_stone_brick_stairs` | Mossy Stone Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_stone_brick_wall` | Mossy Stone Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mossy_stone_bricks` | Mossy Stone Bricks | SUPPORTED | - |  |
| `minecraft:moving_piston` | Moving Piston | UNSUPPORTED | - | No portable block-entity factory. Wait for this piston to finish moving before launching. |
| `minecraft:mud` | Mud | SUPPORTED | - |  |
| `minecraft:mud_brick_slab` | Mud Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mud_brick_stairs` | Mud Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mud_brick_wall` | Mud Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:mud_bricks` | Mud Bricks | SUPPORTED | - |  |
| `minecraft:muddy_mangrove_roots` | Muddy Mangrove Roots | SUPPORTED | - |  |
| `minecraft:mushroom_stem` | Mushroom Stem | SUPPORTED | - |  |
| `minecraft:mycelium` | Mycelium | SUPPORTED | - |  |
| `minecraft:nether_brick_fence` | Nether Brick Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:nether_brick_slab` | Nether Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:nether_brick_stairs` | Nether Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:nether_brick_wall` | Nether Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:nether_bricks` | Nether Bricks | SUPPORTED | - |  |
| `minecraft:nether_gold_ore` | Nether Gold Ore | SUPPORTED | - |  |
| `minecraft:nether_portal` | Nether Portal | UNSUPPORTED | - | Active portals cannot travel. Remove the portal before launching. |
| `minecraft:nether_quartz_ore` | Nether Quartz Ore | SUPPORTED | - |  |
| `minecraft:nether_sprouts` | Nether Sprouts | SUPPORTED | - |  |
| `minecraft:nether_wart` | Nether Wart | SUPPORTED | - |  |
| `minecraft:nether_wart_block` | Nether Wart Block | SUPPORTED | - |  |
| `minecraft:netherite_block` | Block of Netherite | SUPPORTED | - |  |
| `minecraft:netherrack` | Netherrack | SUPPORTED | - |  |
| `minecraft:note_block` | Note Block | SUPPORTED | - |  |
| `minecraft:oak_button` | Oak Button | SUPPORTED | - |  |
| `minecraft:oak_door` | Oak Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:oak_fence` | Oak Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_fence_gate` | Oak Fence Gate | SUPPORTED | - |  |
| `minecraft:oak_hanging_sign` | Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_leaves` | Oak Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_log` | Oak Log | SUPPORTED | - |  |
| `minecraft:oak_planks` | Oak Planks | SUPPORTED | - |  |
| `minecraft:oak_pressure_plate` | Oak Pressure Plate | SUPPORTED | - |  |
| `minecraft:oak_sapling` | Oak Sapling | SUPPORTED | - |  |
| `minecraft:oak_shelf` | Oak Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_sign` | Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_slab` | Oak Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_stairs` | Oak Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_trapdoor` | Oak Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:oak_wall_hanging_sign` | Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_wall_sign` | Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oak_wood` | Oak Wood | SUPPORTED | - |  |
| `minecraft:observer` | Observer | CONDITIONAL | - | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:obsidian` | Obsidian | SUPPORTED | - |  |
| `minecraft:ochre_froglight` | Ochre Froglight | SUPPORTED | - |  |
| `minecraft:open_eyeblossom` | Open Eyeblossom | SUPPORTED | - |  |
| `minecraft:orange_banner` | Orange Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:orange_bed` | Orange Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:orange_candle` | Orange Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_candle_cake` | Cake with Orange Candle | SUPPORTED | - |  |
| `minecraft:orange_carpet` | Orange Carpet | SUPPORTED | - |  |
| `minecraft:orange_concrete` | Orange Concrete | SUPPORTED | - |  |
| `minecraft:orange_concrete_powder` | Orange Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:orange_concrete_slab` | Orange Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_concrete_stairs` | Orange Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_glazed_terracotta` | Orange Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:orange_poplar_leaves` | Orange Poplar Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_shulker_box` | Orange Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:orange_stained_glass` | Orange Stained Glass | SUPPORTED | - |  |
| `minecraft:orange_stained_glass_pane` | Orange Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_terracotta` | Orange Terracotta | SUPPORTED | - |  |
| `minecraft:orange_tulip` | Orange Tulip | SUPPORTED | - |  |
| `minecraft:orange_wall_banner` | Orange Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:orange_wool` | Orange Wool | SUPPORTED | - |  |
| `minecraft:orange_wool_slab` | Orange Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:orange_wool_stairs` | Orange Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxeye_daisy` | Oxeye Daisy | SUPPORTED | - |  |
| `minecraft:oxidized_chiseled_copper` | Oxidized Chiseled Copper | SUPPORTED | - |  |
| `minecraft:oxidized_copper` | Oxidized Copper | SUPPORTED | - |  |
| `minecraft:oxidized_copper_bars` | Oxidized Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_bulb` | Oxidized Copper Bulb | SUPPORTED | - |  |
| `minecraft:oxidized_copper_chain` | Oxidized Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_chest` | Oxidized Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_door` | Oxidized Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:oxidized_copper_golem_statue` | Oxidized Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_grate` | Oxidized Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_lantern` | Oxidized Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_copper_trapdoor` | Oxidized Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:oxidized_cut_copper` | Oxidized Cut Copper | SUPPORTED | - |  |
| `minecraft:oxidized_cut_copper_slab` | Oxidized Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_cut_copper_stairs` | Oxidized Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:oxidized_lightning_rod` | Oxidized Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:packed_ice` | Packed Ice | SUPPORTED | - |  |
| `minecraft:packed_mud` | Packed Mud | SUPPORTED | - |  |
| `minecraft:pale_hanging_moss` | Pale Hanging Moss | SUPPORTED | - |  |
| `minecraft:pale_moss_block` | Pale Moss Block | SUPPORTED | - |  |
| `minecraft:pale_moss_carpet` | Pale Moss Carpet | SUPPORTED | - |  |
| `minecraft:pale_oak_button` | Pale Oak Button | SUPPORTED | - |  |
| `minecraft:pale_oak_door` | Pale Oak Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:pale_oak_fence` | Pale Oak Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_fence_gate` | Pale Oak Fence Gate | SUPPORTED | - |  |
| `minecraft:pale_oak_hanging_sign` | Pale Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_leaves` | Pale Oak Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_log` | Pale Oak Log | SUPPORTED | - |  |
| `minecraft:pale_oak_planks` | Pale Oak Planks | SUPPORTED | - |  |
| `minecraft:pale_oak_pressure_plate` | Pale Oak Pressure Plate | SUPPORTED | - |  |
| `minecraft:pale_oak_sapling` | Pale Oak Sapling | SUPPORTED | - |  |
| `minecraft:pale_oak_shelf` | Pale Oak Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_sign` | Pale Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_slab` | Pale Oak Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_stairs` | Pale Oak Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_trapdoor` | Pale Oak Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:pale_oak_wall_hanging_sign` | Pale Oak Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_wall_sign` | Pale Oak Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pale_oak_wood` | Pale Oak Wood | SUPPORTED | - |  |
| `minecraft:pearlescent_froglight` | Pearlescent Froglight | SUPPORTED | - |  |
| `minecraft:peony` | Peony | SUPPORTED | - |  |
| `minecraft:petrified_oak_slab` | Petrified Oak Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:piglin_head` | Piglin Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:piglin_wall_head` | Piglin Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:pink_banner` | Pink Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:pink_bed` | Pink Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:pink_candle` | Pink Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pink_candle_cake` | Cake with Pink Candle | SUPPORTED | - |  |
| `minecraft:pink_carpet` | Pink Carpet | SUPPORTED | - |  |
| `minecraft:pink_concrete` | Pink Concrete | SUPPORTED | - |  |
| `minecraft:pink_concrete_powder` | Pink Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:pink_concrete_slab` | Pink Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pink_concrete_stairs` | Pink Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pink_glazed_terracotta` | Pink Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:pink_petals` | Pink Petals | SUPPORTED | - |  |
| `minecraft:pink_shulker_box` | Pink Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:pink_stained_glass` | Pink Stained Glass | SUPPORTED | - |  |
| `minecraft:pink_stained_glass_pane` | Pink Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pink_terracotta` | Pink Terracotta | SUPPORTED | - |  |
| `minecraft:pink_tulip` | Pink Tulip | SUPPORTED | - |  |
| `minecraft:pink_wall_banner` | Pink Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:pink_wool` | Pink Wool | SUPPORTED | - |  |
| `minecraft:pink_wool_slab` | Pink Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pink_wool_stairs` | Pink Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:piston` | Piston | CONDITIONAL | - | Stationary only; extended pistons need a matching head and base within the ship. |
| `minecraft:piston_head` | Piston Head | CONDITIONAL | - | Wait for this piston head to finish moving before launching. Stationary only; extended pistons need a matching head and base within the ship. |
| `minecraft:pitcher_crop` | Pitcher Crop | SUPPORTED | - |  |
| `minecraft:pitcher_plant` | Pitcher Plant | SUPPORTED | - |  |
| `minecraft:player_head` | Player Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:player_wall_head` | Player Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:podzol` | Podzol | SUPPORTED | - |  |
| `minecraft:pointed_dripstone` | Pointed Dripstone | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_andesite` | Polished Andesite | SUPPORTED | - |  |
| `minecraft:polished_andesite_slab` | Polished Andesite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_andesite_stairs` | Polished Andesite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_basalt` | Polished Basalt | SUPPORTED | - |  |
| `minecraft:polished_blackstone` | Polished Blackstone | SUPPORTED | - |  |
| `minecraft:polished_blackstone_brick_slab` | Polished Blackstone Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_blackstone_brick_stairs` | Polished Blackstone Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_blackstone_brick_wall` | Polished Blackstone Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_blackstone_bricks` | Polished Blackstone Bricks | SUPPORTED | - |  |
| `minecraft:polished_blackstone_button` | Polished Blackstone Button | SUPPORTED | - |  |
| `minecraft:polished_blackstone_pressure_plate` | Polished Blackstone Pressure Plate | SUPPORTED | - |  |
| `minecraft:polished_blackstone_slab` | Polished Blackstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_blackstone_stairs` | Polished Blackstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_blackstone_wall` | Polished Blackstone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_cinnabar` | Polished Cinnabar | SUPPORTED | - |  |
| `minecraft:polished_cinnabar_slab` | Polished Cinnabar Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_cinnabar_stairs` | Polished Cinnabar Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_cinnabar_wall` | Polished Cinnabar Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_deepslate` | Polished Deepslate | SUPPORTED | - |  |
| `minecraft:polished_deepslate_slab` | Polished Deepslate Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_deepslate_stairs` | Polished Deepslate Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_deepslate_wall` | Polished Deepslate Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_diorite` | Polished Diorite | SUPPORTED | - |  |
| `minecraft:polished_diorite_slab` | Polished Diorite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_diorite_stairs` | Polished Diorite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_granite` | Polished Granite | SUPPORTED | - |  |
| `minecraft:polished_granite_slab` | Polished Granite Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_granite_stairs` | Polished Granite Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_sulfur` | Polished Sulfur | SUPPORTED | - |  |
| `minecraft:polished_sulfur_slab` | Polished Sulfur Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_sulfur_stairs` | Polished Sulfur Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_sulfur_wall` | Polished Sulfur Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_tuff` | Polished Tuff | SUPPORTED | - |  |
| `minecraft:polished_tuff_slab` | Polished Tuff Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_tuff_stairs` | Polished Tuff Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:polished_tuff_wall` | Polished Tuff Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_button` | Poplar Button | SUPPORTED | - |  |
| `minecraft:poplar_door` | Poplar Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:poplar_fence` | Poplar Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_fence_gate` | Poplar Fence Gate | SUPPORTED | - |  |
| `minecraft:poplar_hanging_sign` | Poplar Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_log` | Poplar Log | SUPPORTED | - |  |
| `minecraft:poplar_planks` | Poplar Planks | SUPPORTED | - |  |
| `minecraft:poplar_pressure_plate` | Poplar Pressure Plate | SUPPORTED | - |  |
| `minecraft:poplar_sapling` | Poplar Sapling | SUPPORTED | - |  |
| `minecraft:poplar_shelf` | Poplar Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_sign` | Poplar Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_slab` | Poplar Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_stairs` | Poplar Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_trapdoor` | Poplar Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:poplar_wall_hanging_sign` | Poplar Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_wall_sign` | Poplar Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:poplar_wood` | Poplar Wood | SUPPORTED | - |  |
| `minecraft:poppy` | Poppy | SUPPORTED | - |  |
| `minecraft:potatoes` | Potatoes | SUPPORTED | - |  |
| `minecraft:potent_sulfur` | Potent Sulfur | UNSUPPORTED | `minecraft:potent_sulfur` | Sulfur eruption timing cannot be preserved yet. Leave this block on the planet. |
| `minecraft:potted_acacia_sapling` | Potted Acacia Sapling | SUPPORTED | - |  |
| `minecraft:potted_allium` | Potted Allium | SUPPORTED | - |  |
| `minecraft:potted_azalea_bush` | Potted Azalea | SUPPORTED | - |  |
| `minecraft:potted_azure_bluet` | Potted Azure Bluet | SUPPORTED | - |  |
| `minecraft:potted_bamboo` | Potted Bamboo | SUPPORTED | - |  |
| `minecraft:potted_birch_sapling` | Potted Birch Sapling | SUPPORTED | - |  |
| `minecraft:potted_blue_orchid` | Potted Blue Orchid | SUPPORTED | - |  |
| `minecraft:potted_brown_mushroom` | Potted Brown Mushroom | SUPPORTED | - |  |
| `minecraft:potted_cactus` | Potted Cactus | SUPPORTED | - |  |
| `minecraft:potted_cherry_sapling` | Potted Cherry Sapling | SUPPORTED | - |  |
| `minecraft:potted_closed_eyeblossom` | Potted Closed Eyeblossom | SUPPORTED | - |  |
| `minecraft:potted_cornflower` | Potted Cornflower | SUPPORTED | - |  |
| `minecraft:potted_crimson_fungus` | Potted Crimson Fungus | SUPPORTED | - |  |
| `minecraft:potted_crimson_roots` | Potted Crimson Roots | SUPPORTED | - |  |
| `minecraft:potted_dandelion` | Potted Dandelion | SUPPORTED | - |  |
| `minecraft:potted_dark_oak_sapling` | Potted Dark Oak Sapling | SUPPORTED | - |  |
| `minecraft:potted_dead_bush` | Potted Dead Bush | SUPPORTED | - |  |
| `minecraft:potted_fern` | Potted Fern | SUPPORTED | - |  |
| `minecraft:potted_flowering_azalea_bush` | Potted Flowering Azalea | SUPPORTED | - |  |
| `minecraft:potted_golden_dandelion` | Potted Golden Dandelion | SUPPORTED | - |  |
| `minecraft:potted_jungle_sapling` | Potted Jungle Sapling | SUPPORTED | - |  |
| `minecraft:potted_lily_of_the_valley` | Potted Lily of the Valley | SUPPORTED | - |  |
| `minecraft:potted_mangrove_propagule` | Potted Mangrove Propagule | SUPPORTED | - |  |
| `minecraft:potted_oak_sapling` | Potted Oak Sapling | SUPPORTED | - |  |
| `minecraft:potted_open_eyeblossom` | Potted Open Eyeblossom | SUPPORTED | - |  |
| `minecraft:potted_orange_tulip` | Potted Orange Tulip | SUPPORTED | - |  |
| `minecraft:potted_oxeye_daisy` | Potted Oxeye Daisy | SUPPORTED | - |  |
| `minecraft:potted_pale_oak_sapling` | Potted Pale Oak Sapling | SUPPORTED | - |  |
| `minecraft:potted_pink_tulip` | Potted Pink Tulip | SUPPORTED | - |  |
| `minecraft:potted_poplar_sapling` | Potted Poplar Sapling | SUPPORTED | - |  |
| `minecraft:potted_poppy` | Potted Poppy | SUPPORTED | - |  |
| `minecraft:potted_red_mushroom` | Potted Red Mushroom | SUPPORTED | - |  |
| `minecraft:potted_red_tulip` | Potted Red Tulip | SUPPORTED | - |  |
| `minecraft:potted_spruce_sapling` | Potted Spruce Sapling | SUPPORTED | - |  |
| `minecraft:potted_torchflower` | Potted Torchflower | SUPPORTED | - |  |
| `minecraft:potted_warped_fungus` | Potted Warped Fungus | SUPPORTED | - |  |
| `minecraft:potted_warped_roots` | Potted Warped Roots | SUPPORTED | - |  |
| `minecraft:potted_white_tulip` | Potted White Tulip | SUPPORTED | - |  |
| `minecraft:potted_wither_rose` | Potted Wither Rose | SUPPORTED | - |  |
| `minecraft:powder_snow` | Powder Snow | SUPPORTED | - |  |
| `minecraft:powder_snow_cauldron` | Powder Snow Cauldron | SUPPORTED | - |  |
| `minecraft:powered_rail` | Powered Rail | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:prismarine` | Prismarine | SUPPORTED | - |  |
| `minecraft:prismarine_brick_slab` | Prismarine Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:prismarine_brick_stairs` | Prismarine Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:prismarine_bricks` | Prismarine Bricks | SUPPORTED | - |  |
| `minecraft:prismarine_slab` | Prismarine Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:prismarine_stairs` | Prismarine Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:prismarine_wall` | Prismarine Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:pumpkin` | Pumpkin | SUPPORTED | - |  |
| `minecraft:pumpkin_stem` | Pumpkin Stem | SUPPORTED | - |  |
| `minecraft:purple_banner` | Purple Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:purple_bed` | Purple Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:purple_candle` | Purple Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purple_candle_cake` | Cake with Purple Candle | SUPPORTED | - |  |
| `minecraft:purple_carpet` | Purple Carpet | SUPPORTED | - |  |
| `minecraft:purple_concrete` | Purple Concrete | SUPPORTED | - |  |
| `minecraft:purple_concrete_powder` | Purple Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:purple_concrete_slab` | Purple Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purple_concrete_stairs` | Purple Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purple_glazed_terracotta` | Purple Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:purple_shulker_box` | Purple Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:purple_stained_glass` | Purple Stained Glass | SUPPORTED | - |  |
| `minecraft:purple_stained_glass_pane` | Purple Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purple_terracotta` | Purple Terracotta | SUPPORTED | - |  |
| `minecraft:purple_wall_banner` | Purple Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:purple_wool` | Purple Wool | SUPPORTED | - |  |
| `minecraft:purple_wool_slab` | Purple Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purple_wool_stairs` | Purple Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purpur_block` | Purpur Block | SUPPORTED | - |  |
| `minecraft:purpur_pillar` | Purpur Pillar | SUPPORTED | - |  |
| `minecraft:purpur_slab` | Purpur Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:purpur_stairs` | Purpur Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:quartz_block` | Block of Quartz | SUPPORTED | - |  |
| `minecraft:quartz_bricks` | Quartz Bricks | SUPPORTED | - |  |
| `minecraft:quartz_pillar` | Quartz Pillar | SUPPORTED | - |  |
| `minecraft:quartz_slab` | Quartz Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:quartz_stairs` | Quartz Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:rail` | Rail | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:raw_copper_block` | Block of Raw Copper | SUPPORTED | - |  |
| `minecraft:raw_gold_block` | Block of Raw Gold | SUPPORTED | - |  |
| `minecraft:raw_iron_block` | Block of Raw Iron | SUPPORTED | - |  |
| `minecraft:red_banner` | Red Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:red_bed` | Red Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:red_candle` | Red Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_candle_cake` | Cake with Red Candle | SUPPORTED | - |  |
| `minecraft:red_carpet` | Red Carpet | SUPPORTED | - |  |
| `minecraft:red_concrete` | Red Concrete | SUPPORTED | - |  |
| `minecraft:red_concrete_powder` | Red Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:red_concrete_slab` | Red Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_concrete_stairs` | Red Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_glazed_terracotta` | Red Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:red_mushroom` | Red Mushroom | SUPPORTED | - |  |
| `minecraft:red_mushroom_block` | Red Mushroom Block | SUPPORTED | - |  |
| `minecraft:red_nether_brick_slab` | Red Nether Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_nether_brick_stairs` | Red Nether Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_nether_brick_wall` | Red Nether Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_nether_bricks` | Red Nether Bricks | SUPPORTED | - |  |
| `minecraft:red_poplar_leaves` | Red Poplar Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_sand` | Red Sand | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:red_sandstone` | Red Sandstone | SUPPORTED | - |  |
| `minecraft:red_sandstone_slab` | Red Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_sandstone_stairs` | Red Sandstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_sandstone_wall` | Red Sandstone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_shrub` | Red Shrub | SUPPORTED | - |  |
| `minecraft:red_shulker_box` | Red Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:red_stained_glass` | Red Stained Glass | SUPPORTED | - |  |
| `minecraft:red_stained_glass_pane` | Red Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_terracotta` | Red Terracotta | SUPPORTED | - |  |
| `minecraft:red_tulip` | Red Tulip | SUPPORTED | - |  |
| `minecraft:red_wall_banner` | Red Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:red_wool` | Red Wool | SUPPORTED | - |  |
| `minecraft:red_wool_slab` | Red Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:red_wool_stairs` | Red Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:redstone_block` | Block of Redstone | SUPPORTED | - |  |
| `minecraft:redstone_lamp` | Redstone Lamp | SUPPORTED | - |  |
| `minecraft:redstone_ore` | Redstone Ore | SUPPORTED | - |  |
| `minecraft:redstone_torch` | Redstone Torch | SUPPORTED | - |  |
| `minecraft:redstone_wall_torch` | Redstone Torch | SUPPORTED | - |  |
| `minecraft:redstone_wire` | Redstone Wire | CONDITIONAL | - | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:reinforced_deepslate` | Reinforced Deepslate | SUPPORTED | - |  |
| `minecraft:repeater` | Redstone Repeater | CONDITIONAL | - | Stop clocks and wait for scheduled ticks and queued actions before travel. |
| `minecraft:repeating_command_block` | Repeating Command Block | UNSUPPORTED | `minecraft:command_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:resin_block` | Block of Resin | SUPPORTED | - |  |
| `minecraft:resin_brick_slab` | Resin Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:resin_brick_stairs` | Resin Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:resin_brick_wall` | Resin Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:resin_bricks` | Resin Bricks | SUPPORTED | - |  |
| `minecraft:resin_clump` | Resin Clump | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:respawn_anchor` | Respawn Anchor | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:rooted_dirt` | Rooted Dirt | SUPPORTED | - |  |
| `minecraft:rose_bush` | Rose Bush | SUPPORTED | - |  |
| `minecraft:sand` | Sand | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:sandstone` | Sandstone | SUPPORTED | - |  |
| `minecraft:sandstone_slab` | Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sandstone_stairs` | Sandstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sandstone_wall` | Sandstone Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:scaffolding` | Scaffolding | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sculk` | Sculk | SUPPORTED | - |  |
| `minecraft:sculk_catalyst` | Sculk Catalyst | CONDITIONAL | `minecraft:sculk_catalyst` | No active sculk-spreading cursors. |
| `minecraft:sculk_sensor` | Sculk Sensor | CONDITIONAL | `minecraft:sculk_sensor` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. No pending vibrations or block actions; summoning shriekers are excluded. |
| `minecraft:sculk_shrieker` | Sculk Shrieker | CONDITIONAL | `minecraft:sculk_shrieker` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. A naturally generated shrieker can summon a world-bound encounter. Use a player-placed shrieker instead. No pending vibrations or block actions; summoning shriekers are excluded. |
| `minecraft:sculk_vein` | Sculk Vein | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sea_lantern` | Sea Lantern | SUPPORTED | - |  |
| `minecraft:sea_pickle` | Sea Pickle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:seagrass` | Seagrass | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:shelf_mushroom` | Shelf Mushroom | SUPPORTED | - |  |
| `minecraft:short_dry_grass` | Short Dry Grass | SUPPORTED | - |  |
| `minecraft:short_grass` | Short Grass | SUPPORTED | - |  |
| `minecraft:shroomlight` | Shroomlight | SUPPORTED | - |  |
| `minecraft:shulker_box` | Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:skeleton_skull` | Skeleton Skull | SUPPORTED | `minecraft:skull` |  |
| `minecraft:skeleton_wall_skull` | Skeleton Skull | SUPPORTED | `minecraft:skull` |  |
| `minecraft:slime_block` | Slime Block | SUPPORTED | - |  |
| `minecraft:small_amethyst_bud` | Small Amethyst Bud | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:small_dripleaf` | Small Dripleaf | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smithing_table` | Smithing Table | SUPPORTED | - |  |
| `minecraft:smoker` | Smoker | SUPPORTED | `minecraft:smoker` |  |
| `minecraft:smooth_basalt` | Smooth Basalt | SUPPORTED | - |  |
| `minecraft:smooth_quartz` | Smooth Quartz Block | SUPPORTED | - |  |
| `minecraft:smooth_quartz_slab` | Smooth Quartz Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_quartz_stairs` | Smooth Quartz Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_red_sandstone` | Smooth Red Sandstone | SUPPORTED | - |  |
| `minecraft:smooth_red_sandstone_slab` | Smooth Red Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_red_sandstone_stairs` | Smooth Red Sandstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_sandstone` | Smooth Sandstone | SUPPORTED | - |  |
| `minecraft:smooth_sandstone_slab` | Smooth Sandstone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_sandstone_stairs` | Smooth Sandstone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:smooth_stone` | Smooth Stone | SUPPORTED | - |  |
| `minecraft:smooth_stone_slab` | Smooth Stone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sniffer_egg` | Sniffer Egg | SUPPORTED | - |  |
| `minecraft:snow` | Snow | SUPPORTED | - |  |
| `minecraft:snow_block` | Snow Block | SUPPORTED | - |  |
| `minecraft:soul_campfire` | Soul Campfire | CONDITIONAL | `minecraft:campfire` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:soul_fire` | Soul Fire | UNSUPPORTED | - | Extinguish open fire before launching. Campfire blocks are supported. |
| `minecraft:soul_lantern` | Soul Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:soul_sand` | Soul Sand | SUPPORTED | - |  |
| `minecraft:soul_soil` | Soul Soil | SUPPORTED | - |  |
| `minecraft:soul_torch` | Soul Torch | SUPPORTED | - |  |
| `minecraft:soul_wall_torch` | Soul Torch | SUPPORTED | - |  |
| `minecraft:spawner` | Monster Spawner | UNSUPPORTED | `minecraft:mob_spawner` | Spawner and vault encounter data cannot travel yet. Leave this block on the planet. |
| `minecraft:sponge` | Sponge | SUPPORTED | - |  |
| `minecraft:spore_blossom` | Spore Blossom | SUPPORTED | - |  |
| `minecraft:spruce_button` | Spruce Button | SUPPORTED | - |  |
| `minecraft:spruce_door` | Spruce Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:spruce_fence` | Spruce Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_fence_gate` | Spruce Fence Gate | SUPPORTED | - |  |
| `minecraft:spruce_hanging_sign` | Spruce Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_leaves` | Spruce Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_log` | Spruce Log | SUPPORTED | - |  |
| `minecraft:spruce_planks` | Spruce Planks | SUPPORTED | - |  |
| `minecraft:spruce_pressure_plate` | Spruce Pressure Plate | SUPPORTED | - |  |
| `minecraft:spruce_sapling` | Spruce Sapling | SUPPORTED | - |  |
| `minecraft:spruce_shelf` | Spruce Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_sign` | Spruce Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_slab` | Spruce Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_stairs` | Spruce Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_trapdoor` | Spruce Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:spruce_wall_hanging_sign` | Spruce Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_wall_sign` | Spruce Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:spruce_wood` | Spruce Wood | SUPPORTED | - |  |
| `minecraft:sticky_piston` | Sticky Piston | CONDITIONAL | - | Stationary only; extended pistons need a matching head and base within the ship. |
| `minecraft:stone` | Stone | SUPPORTED | - |  |
| `minecraft:stone_brick_slab` | Stone Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:stone_brick_stairs` | Stone Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:stone_brick_wall` | Stone Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:stone_bricks` | Stone Bricks | SUPPORTED | - |  |
| `minecraft:stone_button` | Stone Button | SUPPORTED | - |  |
| `minecraft:stone_pressure_plate` | Stone Pressure Plate | SUPPORTED | - |  |
| `minecraft:stone_slab` | Stone Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:stone_stairs` | Stone Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:stonecutter` | Stonecutter | SUPPORTED | - |  |
| `minecraft:straw_bed` | Straw Bed | SUPPORTED | - |  |
| `minecraft:stripped_acacia_log` | Stripped Acacia Log | SUPPORTED | - |  |
| `minecraft:stripped_acacia_wood` | Stripped Acacia Wood | SUPPORTED | - |  |
| `minecraft:stripped_bamboo_block` | Block of Stripped Bamboo | SUPPORTED | - |  |
| `minecraft:stripped_birch_log` | Stripped Birch Log | SUPPORTED | - |  |
| `minecraft:stripped_birch_wood` | Stripped Birch Wood | SUPPORTED | - |  |
| `minecraft:stripped_cherry_log` | Stripped Cherry Log | SUPPORTED | - |  |
| `minecraft:stripped_cherry_wood` | Stripped Cherry Wood | SUPPORTED | - |  |
| `minecraft:stripped_crimson_hyphae` | Stripped Crimson Hyphae | SUPPORTED | - |  |
| `minecraft:stripped_crimson_stem` | Stripped Crimson Stem | SUPPORTED | - |  |
| `minecraft:stripped_dark_oak_log` | Stripped Dark Oak Log | SUPPORTED | - |  |
| `minecraft:stripped_dark_oak_wood` | Stripped Dark Oak Wood | SUPPORTED | - |  |
| `minecraft:stripped_jungle_log` | Stripped Jungle Log | SUPPORTED | - |  |
| `minecraft:stripped_jungle_wood` | Stripped Jungle Wood | SUPPORTED | - |  |
| `minecraft:stripped_mangrove_log` | Stripped Mangrove Log | SUPPORTED | - |  |
| `minecraft:stripped_mangrove_wood` | Stripped Mangrove Wood | SUPPORTED | - |  |
| `minecraft:stripped_oak_log` | Stripped Oak Log | SUPPORTED | - |  |
| `minecraft:stripped_oak_wood` | Stripped Oak Wood | SUPPORTED | - |  |
| `minecraft:stripped_pale_oak_log` | Stripped Pale Oak Log | SUPPORTED | - |  |
| `minecraft:stripped_pale_oak_wood` | Stripped Pale Oak Wood | SUPPORTED | - |  |
| `minecraft:stripped_poplar_log` | Stripped Poplar Log | SUPPORTED | - |  |
| `minecraft:stripped_poplar_wood` | Stripped Poplar Wood | SUPPORTED | - |  |
| `minecraft:stripped_spruce_log` | Stripped Spruce Log | SUPPORTED | - |  |
| `minecraft:stripped_spruce_wood` | Stripped Spruce Wood | SUPPORTED | - |  |
| `minecraft:stripped_warped_hyphae` | Stripped Warped Hyphae | SUPPORTED | - |  |
| `minecraft:stripped_warped_stem` | Stripped Warped Stem | SUPPORTED | - |  |
| `minecraft:structure_block` | Structure Block | UNSUPPORTED | `minecraft:structure_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:structure_void` | Structure Void | SUPPORTED | - |  |
| `minecraft:sugar_cane` | Sugar Cane | SUPPORTED | - |  |
| `minecraft:sulfur` | Sulfur | SUPPORTED | - |  |
| `minecraft:sulfur_brick_slab` | Sulfur Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_brick_stairs` | Sulfur Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_brick_wall` | Sulfur Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_bricks` | Sulfur Bricks | SUPPORTED | - |  |
| `minecraft:sulfur_slab` | Sulfur Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_spike` | Sulfur Spike | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_stairs` | Sulfur Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sulfur_wall` | Sulfur Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:sunflower` | Sunflower | SUPPORTED | - |  |
| `minecraft:suspicious_gravel` | Suspicious Gravel | CONDITIONAL | `minecraft:brushable_block` | Must be supported and not being brushed. |
| `minecraft:suspicious_sand` | Suspicious Sand | CONDITIONAL | `minecraft:brushable_block` | Must be supported and not being brushed. |
| `minecraft:sweet_berry_bush` | Sweet Berry Bush | SUPPORTED | - |  |
| `minecraft:tall_dry_grass` | Tall Dry Grass | SUPPORTED | - |  |
| `minecraft:tall_grass` | Tall Grass | SUPPORTED | - |  |
| `minecraft:tall_seagrass` | Tall Seagrass | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:target` | Target | SUPPORTED | - |  |
| `minecraft:terracotta` | Terracotta | SUPPORTED | - |  |
| `minecraft:test_block` | Test Block | UNSUPPORTED | `minecraft:test_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:test_instance_block` | Test Instance Block | UNSUPPORTED | `minecraft:test_instance_block` | World editing, command, and test blocks cannot travel. Remove this block before launching. |
| `minecraft:tinted_glass` | Tinted Glass | SUPPORTED | - |  |
| `minecraft:tnt` | TNT | SUPPORTED | - |  |
| `minecraft:torch` | Torch | SUPPORTED | - |  |
| `minecraft:torchflower` | Torchflower | SUPPORTED | - |  |
| `minecraft:torchflower_crop` | Torchflower Crop | SUPPORTED | - |  |
| `minecraft:trapped_chest` | Trapped Chest | CONDITIONAL | `minecraft:trapped_chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:trial_spawner` | Trial Spawner | UNSUPPORTED | `minecraft:trial_spawner` | Spawner and vault encounter data cannot travel yet. Leave this block on the planet. |
| `minecraft:tripwire` | Tripwire | SUPPORTED | - |  |
| `minecraft:tripwire_hook` | Tripwire Hook | SUPPORTED | - |  |
| `minecraft:tube_coral` | Tube Coral | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tube_coral_block` | Tube Coral Block | SUPPORTED | - |  |
| `minecraft:tube_coral_fan` | Tube Coral Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tube_coral_wall_fan` | Tube Coral Wall Fan | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff` | Tuff | SUPPORTED | - |  |
| `minecraft:tuff_brick_slab` | Tuff Brick Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff_brick_stairs` | Tuff Brick Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff_brick_wall` | Tuff Brick Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff_bricks` | Tuff Bricks | SUPPORTED | - |  |
| `minecraft:tuff_slab` | Tuff Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff_stairs` | Tuff Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:tuff_wall` | Tuff Wall | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:turtle_egg` | Turtle Egg | SUPPORTED | - |  |
| `minecraft:twisting_vines` | Twisting Vines | SUPPORTED | - |  |
| `minecraft:twisting_vines_plant` | Twisting Vines Plant | SUPPORTED | - |  |
| `minecraft:vault` | Vault | UNSUPPORTED | `minecraft:vault` | Spawner and vault encounter data cannot travel yet. Leave this block on the planet. |
| `minecraft:verdant_froglight` | Verdant Froglight | SUPPORTED | - |  |
| `minecraft:vine` | Vines | SUPPORTED | - |  |
| `minecraft:void_air` | Void Air | UNSUPPORTED | - | Empty space, not captured ship material. |
| `minecraft:wall_torch` | Torch | SUPPORTED | - |  |
| `minecraft:warped_button` | Warped Button | SUPPORTED | - |  |
| `minecraft:warped_door` | Warped Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:warped_fence` | Warped Fence | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_fence_gate` | Warped Fence Gate | SUPPORTED | - |  |
| `minecraft:warped_fungus` | Warped Fungus | SUPPORTED | - |  |
| `minecraft:warped_hanging_sign` | Warped Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_hyphae` | Warped Hyphae | SUPPORTED | - |  |
| `minecraft:warped_nylium` | Warped Nylium | SUPPORTED | - |  |
| `minecraft:warped_planks` | Warped Planks | SUPPORTED | - |  |
| `minecraft:warped_pressure_plate` | Warped Pressure Plate | SUPPORTED | - |  |
| `minecraft:warped_roots` | Warped Roots | SUPPORTED | - |  |
| `minecraft:warped_shelf` | Warped Shelf | CONDITIONAL | `minecraft:shelf` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_sign` | Warped Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_slab` | Warped Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_stairs` | Warped Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_stem` | Warped Stem | SUPPORTED | - |  |
| `minecraft:warped_trapdoor` | Warped Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:warped_wall_hanging_sign` | Warped Hanging Sign | CONDITIONAL | `minecraft:hanging_sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_wall_sign` | Warped Sign | CONDITIONAL | `minecraft:sign` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:warped_wart_block` | Warped Wart Block | SUPPORTED | - |  |
| `minecraft:water` | Water | UNSUPPORTED | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:water_cauldron` | Water Cauldron | SUPPORTED | - |  |
| `minecraft:waxed_chiseled_copper` | Waxed Chiseled Copper | SUPPORTED | - |  |
| `minecraft:waxed_copper_bars` | Waxed Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_block` | Waxed Block of Copper | SUPPORTED | - |  |
| `minecraft:waxed_copper_bulb` | Waxed Copper Bulb | SUPPORTED | - |  |
| `minecraft:waxed_copper_chain` | Waxed Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_chest` | Waxed Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_door` | Waxed Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:waxed_copper_golem_statue` | Waxed Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_grate` | Waxed Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_lantern` | Waxed Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_copper_trapdoor` | Waxed Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:waxed_cut_copper` | Waxed Cut Copper | SUPPORTED | - |  |
| `minecraft:waxed_cut_copper_slab` | Waxed Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_cut_copper_stairs` | Waxed Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_chiseled_copper` | Waxed Exposed Chiseled Copper | SUPPORTED | - |  |
| `minecraft:waxed_exposed_copper` | Waxed Exposed Copper | SUPPORTED | - |  |
| `minecraft:waxed_exposed_copper_bars` | Waxed Exposed Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_bulb` | Waxed Exposed Copper Bulb | SUPPORTED | - |  |
| `minecraft:waxed_exposed_copper_chain` | Waxed Exposed Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_chest` | Waxed Exposed Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_door` | Waxed Exposed Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:waxed_exposed_copper_golem_statue` | Waxed Exposed Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_grate` | Waxed Exposed Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_lantern` | Waxed Exposed Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_copper_trapdoor` | Waxed Exposed Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:waxed_exposed_cut_copper` | Waxed Exposed Cut Copper | SUPPORTED | - |  |
| `minecraft:waxed_exposed_cut_copper_slab` | Waxed Exposed Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_cut_copper_stairs` | Waxed Exposed Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_exposed_lightning_rod` | Waxed Exposed Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_lightning_rod` | Waxed Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_chiseled_copper` | Waxed Oxidized Chiseled Copper | SUPPORTED | - |  |
| `minecraft:waxed_oxidized_copper` | Waxed Oxidized Copper | SUPPORTED | - |  |
| `minecraft:waxed_oxidized_copper_bars` | Waxed Oxidized Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_bulb` | Waxed Oxidized Copper Bulb | SUPPORTED | - |  |
| `minecraft:waxed_oxidized_copper_chain` | Waxed Oxidized Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_chest` | Waxed Oxidized Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_door` | Waxed Oxidized Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:waxed_oxidized_copper_golem_statue` | Waxed Oxidized Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_grate` | Waxed Oxidized Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_lantern` | Waxed Oxidized Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_copper_trapdoor` | Waxed Oxidized Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:waxed_oxidized_cut_copper` | Waxed Oxidized Cut Copper | SUPPORTED | - |  |
| `minecraft:waxed_oxidized_cut_copper_slab` | Waxed Oxidized Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_cut_copper_stairs` | Waxed Oxidized Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_oxidized_lightning_rod` | Waxed Oxidized Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_chiseled_copper` | Waxed Weathered Chiseled Copper | SUPPORTED | - |  |
| `minecraft:waxed_weathered_copper` | Waxed Weathered Copper | SUPPORTED | - |  |
| `minecraft:waxed_weathered_copper_bars` | Waxed Weathered Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_bulb` | Waxed Weathered Copper Bulb | SUPPORTED | - |  |
| `minecraft:waxed_weathered_copper_chain` | Waxed Weathered Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_chest` | Waxed Weathered Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_door` | Waxed Weathered Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:waxed_weathered_copper_golem_statue` | Waxed Weathered Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_grate` | Waxed Weathered Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_lantern` | Waxed Weathered Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_copper_trapdoor` | Waxed Weathered Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:waxed_weathered_cut_copper` | Waxed Weathered Cut Copper | SUPPORTED | - |  |
| `minecraft:waxed_weathered_cut_copper_slab` | Waxed Weathered Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_cut_copper_stairs` | Waxed Weathered Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:waxed_weathered_lightning_rod` | Waxed Weathered Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_chiseled_copper` | Weathered Chiseled Copper | SUPPORTED | - |  |
| `minecraft:weathered_copper` | Weathered Copper | SUPPORTED | - |  |
| `minecraft:weathered_copper_bars` | Weathered Copper Bars | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_bulb` | Weathered Copper Bulb | SUPPORTED | - |  |
| `minecraft:weathered_copper_chain` | Weathered Copper Chain | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_chest` | Weathered Copper Chest | CONDITIONAL | `minecraft:chest` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_door` | Weathered Copper Door | CONDITIONAL | - | Closed, matching halves with a full sealing frame are required for travel. |
| `minecraft:weathered_copper_golem_statue` | Weathered Copper Golem Statue | CONDITIONAL | `minecraft:copper_golem_statue` | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_grate` | Weathered Copper Grate | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_lantern` | Weathered Copper Lantern | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_copper_trapdoor` | Weathered Copper Trapdoor | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. Decorative panels may travel; airtight hatches need a closed four-sided horizontal frame. |
| `minecraft:weathered_cut_copper` | Weathered Cut Copper | SUPPORTED | - |  |
| `minecraft:weathered_cut_copper_slab` | Weathered Cut Copper Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_cut_copper_stairs` | Weathered Cut Copper Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weathered_lightning_rod` | Weathered Lightning Rod | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:weeping_vines` | Weeping Vines | SUPPORTED | - |  |
| `minecraft:weeping_vines_plant` | Weeping Vines Plant | SUPPORTED | - |  |
| `minecraft:wet_sponge` | Wet Sponge | SUPPORTED | - |  |
| `minecraft:wheat` | Wheat Crops | SUPPORTED | - |  |
| `minecraft:white_banner` | White Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:white_bed` | White Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:white_candle` | White Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:white_candle_cake` | Cake with White Candle | SUPPORTED | - |  |
| `minecraft:white_carpet` | White Carpet | SUPPORTED | - |  |
| `minecraft:white_concrete` | White Concrete | SUPPORTED | - |  |
| `minecraft:white_concrete_powder` | White Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:white_concrete_slab` | White Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:white_concrete_stairs` | White Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:white_glazed_terracotta` | White Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:white_shulker_box` | White Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:white_stained_glass` | White Stained Glass | SUPPORTED | - |  |
| `minecraft:white_stained_glass_pane` | White Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:white_terracotta` | White Terracotta | SUPPORTED | - |  |
| `minecraft:white_tulip` | White Tulip | SUPPORTED | - |  |
| `minecraft:white_wall_banner` | White Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:white_wool` | White Wool | SUPPORTED | - |  |
| `minecraft:white_wool_slab` | White Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:white_wool_stairs` | White Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:wildflowers` | Wildflowers | SUPPORTED | - |  |
| `minecraft:wither_rose` | Wither Rose | SUPPORTED | - |  |
| `minecraft:wither_skeleton_skull` | Wither Skeleton Skull | SUPPORTED | `minecraft:skull` |  |
| `minecraft:wither_skeleton_wall_skull` | Wither Skeleton Skull | SUPPORTED | `minecraft:skull` |  |
| `minecraft:yellow_banner` | Yellow Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:yellow_bed` | Yellow Bed | CONDITIONAL | - | Existing player respawn bindings are not retargeted; use obeys vanilla dimension rules. |
| `minecraft:yellow_candle` | Yellow Candle | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_candle_cake` | Cake with Yellow Candle | SUPPORTED | - |  |
| `minecraft:yellow_carpet` | Yellow Carpet | SUPPORTED | - |  |
| `minecraft:yellow_concrete` | Yellow Concrete | SUPPORTED | - |  |
| `minecraft:yellow_concrete_powder` | Yellow Concrete Powder | CONDITIONAL | - | Must be supported from below and settled. |
| `minecraft:yellow_concrete_slab` | Yellow Concrete Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_concrete_stairs` | Yellow Concrete Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_glazed_terracotta` | Yellow Glazed Terracotta | SUPPORTED | - |  |
| `minecraft:yellow_poplar_leaves` | Yellow Poplar Leaves | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_shulker_box` | Yellow Shulker Box | SUPPORTED | `minecraft:shulker_box` |  |
| `minecraft:yellow_stained_glass` | Yellow Stained Glass | SUPPORTED | - |  |
| `minecraft:yellow_stained_glass_pane` | Yellow Stained Glass Pane | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_terracotta` | Yellow Terracotta | SUPPORTED | - |  |
| `minecraft:yellow_wall_banner` | Yellow Banner | SUPPORTED | `minecraft:banner` |  |
| `minecraft:yellow_wool` | Yellow Wool | SUPPORTED | - |  |
| `minecraft:yellow_wool_slab` | Yellow Wool Slab | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:yellow_wool_stairs` | Yellow Wool Stairs | CONDITIONAL | - | Drain liquids and remove waterlogging before launching. Filled cauldrons are supported. |
| `minecraft:zombie_head` | Zombie Head | SUPPORTED | `minecraft:skull` |  |
| `minecraft:zombie_wall_head` | Zombie Head | SUPPORTED | `minecraft:skull` |  |
