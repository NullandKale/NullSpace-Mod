package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.shipyard.*;
import com.nullandkale.nullspace.templates.VesselTemplate;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Uncrewed, salvageable places. A saved identity owns real blocks and finite supplies. */
public final class EncounterSites {
  private static final ServerContext.Key<Map<String, Integer>> RETRY = new ServerContext.Key<>();

  public static StationBlueprint.Plan plan(int kind, long seed) {
    if (kind == 0) return growing(seed);
    String design = design(kind, seed);
    var preset = ShipBlueprints.get(design);
    var blocks = new LinkedHashMap<>(ShipBlueprints.blocks(preset));
    var controller = preset.helm();
    blocks.put(
        controller,
        ShipBlocks.STATION_CORE
            .defaultBlockState()
            .setValue(
                HorizontalDirectionalBlock.FACING,
                blocks.get(controller).getValue(HorizontalDirectionalBlock.FACING)));
    if (kind == 1) {
      // A blocked interior cargo door is repairable with an ordinary pickaxe. The dock stays
      // intact.
      blocks.put(new BlockPos(12, 1, 10), Blocks.COBBLESTONE.defaultBlockState());
      blocks.put(new BlockPos(12, 2, 10), Blocks.COBBLESTONE.defaultBlockState());
    }
    return new StationBlueprint.Plan(blocks, controller, docks(blocks));
  }

  public static String design(int kind, long seed) {
    if (kind == 0) return "";
    var designs =
        kind == 1
            ? List.of("rivet_hauler", "halcyon_courier", "bastion_tender")
            : List.of(
                "rivet_bolt",
                "halcyon_swift",
                "bastion_ward",
                "rivet_ranger",
                "halcyon_horizon",
                "bastion_pioneer");
    return designs.get((int) Math.floorMod(seed, designs.size()));
  }

  private static List<BlockPos> docks(
      Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
    return blocks.entrySet().stream()
        .filter(e -> e.getValue().is(ShipBlocks.DOCKING_BASE))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static StationBlueprint.Plan growing(long seed) {
    var t = new VesselTemplate();
    var footprint = new LinkedHashSet<BlockPos>();
    VesselTemplate.rectangle(footprint, 2, 2, 18, 22);
    var hull =
        switch ((int) Math.floorMod(seed, 3)) {
          case 0 -> Blocks.STONE_BRICKS;
          case 1 -> Blocks.QUARTZ_BLOCK;
          default -> Blocks.POLISHED_DEEPSLATE;
        };
    t.shell(footprint, 0, 7, hull, Blocks.IRON_BLOCK);
    t.bulkhead(9, 3, 17, 0, 7, 10, hull);
    t.dock(new BlockPos(10, 1, 2), Direction.NORTH, hull);
    var core = new BlockPos(4, 1, 5);
    t.machine(core, ShipBlocks.STATION_CORE, Direction.EAST);
    t.machine(new BlockPos(4, 1, 6), ShipBlocks.GENERATOR, Direction.EAST);
    t.machine(new BlockPos(4, 1, 7), ShipBlocks.BATTERY, Direction.EAST);
    t.machine(new BlockPos(5, 1, 3), ShipBlocks.AIR_SUPPLY, Direction.SOUTH);
    t.machine(new BlockPos(5, 1, 21), ShipBlocks.AIR_SUPPLY, Direction.NORTH);
    t.put(15, 1, 5, Blocks.BARREL);
    t.put(15, 1, 6, Blocks.CRAFTING_TABLE);
    t.put(15, 1, 7, Blocks.FURNACE);
    for (int x : new int[] {5, 6, 7, 13, 14, 15}) {
      for (int z = 12; z <= 19; z++) {
        t.state(
            new BlockPos(x, 0, z),
            Blocks.FARMLAND
                .defaultBlockState()
                .setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE,
                    7));
        t.state(new BlockPos(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
      }
    }
    for (int x : new int[] {4, 16}) for (int z = 12; z <= 19; z++) t.put(x, 0, z, Blocks.WATER);
    // Water is inside an intact pressure hull, with a solid bottom below it.
    // Raise the complete plan by one block to leave room for that bottom.
    t.light(10, 6, 5);
    t.light(7, 6, 15);
    t.light(13, 6, 15);
    var blocks = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
    t.finish(21, 8, 25).forEach((p, state) -> blocks.put(p.above(), state));
    for (int x = 2; x <= 18; x++)
      for (int z = 2; z <= 22; z++)
        blocks.put(new BlockPos(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
    return new StationBlueprint.Plan(blocks, core.above(), docks(blocks));
  }

  public static void tick(MinecraftServer server) {
    if (server.getTickCount() % 20 != 0 || StationService.building(server)) return;
    var store = ShipStore.get(server);
    var retry = ServerContext.get(server).service(RETRY, HashMap::new);
    for (var tag : List.copyOf(store.ships())) {
      var traveller = (CompoundTag) tag;
      if (!JourneyService.contact(traveller)) continue;
      String address = traveller.getCompoundOrEmpty("journey").getStringOr("encounter", "");
      var site = store.section("encounters").getCompoundOrEmpty(address);
      if (!site.getStringOr("state", "").equals("planned")
          || retry.getOrDefault(address, 0) > server.getTickCount()) continue;
      try {
        String id =
            UUID.nameUUIDFromBytes(
                    ("nullspace:" + address).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        var metadata = new CompoundTag();
        metadata.putBoolean("worldGenerated", true);
        metadata.putBoolean("salvage", true);
        metadata.putInt("encounterKind", site.getIntOr("kind", 0));
        metadata.putLong("encounterSeed", site.getLongOr("seed", 1));
        metadata.putString(
            "encounterDesign", design(site.getIntOr("kind", 0), site.getLongOr("seed", 1)));
        var vessel =
            StationService.queueAmbient(
                server,
                id,
                site.getStringOr("name", "Signal"),
                address,
                new Vec3(0, 0, 600),
                plan(site.getIntOr("kind", 0), site.getLongOr("seed", 1)),
                metadata);
        if (vessel == null) {
          retry.put(address, server.getTickCount() + 1200);
          NullSpace.LOGGER.warn(
              "No clear storage for encounter {}; retrying in 60 seconds", address);
          return;
        }
        site.putString("vessel", id);
        site.putString("state", "placed");
        store.save();
      } catch (RuntimeException failure) {
        retry.put(address, server.getTickCount() + 1200);
        NullSpace.LOGGER.error(
            "Encounter construction failed for {}; saved plan retained, retrying in 60 seconds",
            address,
            failure);
      }
      return;
    }
  }

  /** Called only during inaccessible initial construction, before the ready marker is saved. */
  public static void stock(ServerLevel level, CompoundTag ship) {
    if (ship.getBooleanOr("encounterStocked", false)) return;
    int kind = ship.getIntOr("encounterKind", 0);
    var base = ShipStore.pos(ship, "base");
    long seed = ship.getLongOr("encounterSeed", 1);
    if (kind == 0) {
      fill(
          level,
          base.offset(15, 2, 5),
          "Growing station supplies",
          new ItemStack(Items.BREAD, 12),
          new ItemStack(Items.WHEAT_SEEDS, 16),
          new ItemStack(Items.IRON_HOE),
          new ItemStack(Items.COAL, 8));
    } else {
      String design = ship.getStringOr("encounterDesign", design(kind, seed));
      var rooms = ShipRooms.rooms(design);
      // A saved construction plan can predate the room layout. Keep its original reward rather
      // than dropping items into coordinates where that older hull has no container.
      if (rooms.stream()
          .filter(r -> r.supplies() != null)
          .anyMatch(r -> !(level.getBlockEntity(base.offset(r.supplies())) instanceof Container))) {
        stockOlderPlan(level, ship, kind);
        return;
      }
      boolean workshop =
          rooms.stream()
              .anyMatch(r -> r.use() == ShipRooms.Use.WORKSHOP || r.use() == ShipRooms.Use.LAB);
      var random = new Random(seed ^ 0x726f6f6dL);
      for (var room : rooms) {
        if (room.supplies() == null) continue;
        var items = new ArrayList<ItemStack>();
        switch (room.use()) {
          case ENGINEERING -> items.add(new ItemStack(Items.COAL, kind == 1 ? 32 : 8));
          case GALLEY -> {
            items.add(new ItemStack(Items.BREAD, kind == 1 ? 6 : 3));
            items.add(new ItemStack(Items.CARROT, 2 + random.nextInt(4)));
          }
          case CARGO -> {
            items.add(new ItemStack(Items.COPPER_INGOT, 8 + random.nextInt(9)));
            items.add(new ItemStack(Items.GLASS, 4 + random.nextInt(5)));
            if (!workshop) repairSupplies(items, kind);
          }
          case WORKSHOP, LAB -> repairSupplies(items, kind);
          case CREW -> {
            items.add(new ItemStack(Items.PAPER, 4 + random.nextInt(5)));
            items.add(new ItemStack(Blocks.WOOL.white(), 3));
          }
          default -> {}
        }
        fill(
            level,
            base.offset(room.supplies()),
            room.name() + " supplies",
            items.toArray(ItemStack[]::new));
      }
    }
    ship.putBoolean("encounterStocked", true);
  }

  private static void repairSupplies(List<ItemStack> items, int kind) {
    items.add(new ItemStack(Items.IRON_INGOT, kind == 1 ? 12 : 16));
    if (kind == 1) items.add(new ItemStack(Items.IRON_PICKAXE));
    else {
      items.add(new ItemStack(ShipBlocks.SYSTEM_UPGRADE, 2));
      items.add(new ItemStack(Items.REDSTONE, 12));
    }
  }

  private static void stockOlderPlan(ServerLevel level, CompoundTag ship, int kind) {
    for (var at :
        BlockPos.betweenClosed(ShipStore.bounds(ship).min(), ShipStore.bounds(ship).max())) {
      if (!(level.getBlockState(at).is(Blocks.BARREL) || level.getBlockState(at).is(Blocks.CHEST)))
        continue;
      if (!(level.getBlockEntity(at) instanceof Container)) continue;
      var items = new ArrayList<ItemStack>();
      items.add(new ItemStack(Items.COAL, kind == 1 ? 32 : 8));
      repairSupplies(items, kind);
      if (kind == 1) items.add(new ItemStack(Items.BREAD, 6));
      fill(level, at, "Salvage supplies", items.toArray(ItemStack[]::new));
      ship.putBoolean("encounterStocked", true);
      return;
    }
  }

  private static void fill(ServerLevel level, BlockPos at, String name, ItemStack... items) {
    if (!(level.getBlockEntity(at) instanceof Container container)) return;
    container.clearContent();
    for (int i = 0; i < items.length; i++) container.setItem(i, items[i]);
    if (level.getBlockEntity(at)
        instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity block)
      block.applyComponents(
          block.collectComponents(),
          net.minecraft.core.component.DataComponentPatch.builder()
              .set(
                  net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                  net.minecraft.network.chat.Component.literal(name))
              .build());
    container.setChanged();
  }

  private EncounterSites() {}
}
