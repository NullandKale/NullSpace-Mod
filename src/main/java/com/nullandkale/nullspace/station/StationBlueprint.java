package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.ship.ShipBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Pure block plan; placement never overwrites an existing player's build. */
public final class StationBlueprint {
  public record Plan(Map<BlockPos, BlockState> blocks, BlockPos core, List<BlockPos> docks) {
    public Plan {
      blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
      docks = List.copyOf(docks);
    }
  }

  private static void machine(
      Map<BlockPos, BlockState> out, BlockPos at, Block block, Direction face) {
    var state = block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face);
    if (block == ShipBlocks.CAMERA || block == ShipBlocks.MONITOR) {
      state = state.setValue(ShipBlocks.InventoryMachine.LEGACY_HULL, false);
      var backing = at.relative(face.getOpposite());
      if (!out.containsKey(backing) || out.get(backing).isAir())
        out.put(backing, Blocks.QUARTZ_BLOCK.defaultBlockState());
    }
    out.put(at, state);
  }

  private static void door(Map<BlockPos, BlockState> out, BlockPos at, Direction face) {
    var s = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, face);
    out.put(at, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    out.put(at.above(), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
  }

  private static void opening(Map<BlockPos, BlockState> out, BlockPos at) {
    out.put(at, Blocks.AIR.defaultBlockState());
    out.put(at.above(), Blocks.AIR.defaultBlockState());
  }

  public static void connect(
      Map<BlockPos, BlockState> out, StationLayout.Room a, StationLayout.Room b) {
    var face = StationLayout.direction(a, b);
    var da = StationLayout.doorway(a, face);
    var db = StationLayout.doorway(b, face.getOpposite());
    out.put(da.below(), Blocks.QUARTZ_BLOCK.defaultBlockState());
    out.put(db.below(), Blocks.QUARTZ_BLOCK.defaultBlockState());
    door(out, da, face);
    opening(out, db);
    out.put(da.above(2), Blocks.QUARTZ_BLOCK.defaultBlockState());
  }

  public static Plan build(StationLayout.Layout layout) {
    var out = new LinkedHashMap<BlockPos, BlockState>();
    var docks = new ArrayList<BlockPos>();
    BlockPos core = null;
    for (var room : layout.rooms()) {
      var b = room.origin();
      for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++)
          for (int y = 0; y < 9; y++) {
            boolean shell = x == 0 || z == 0 || x == 15 || z == 15 || y == 0 || y == 8;
            if (!shell) continue;
            boolean window = y >= 4 && y <= 6 && (x == 0 || x == 15 || z == 0 || z == 15);
            var block =
                y == 0 && (x + z) % 7 == 0
                    ? Blocks.SEA_LANTERN
                    : window ? Blocks.GLASS : Blocks.QUARTZ_BLOCK;
            out.put(b.offset(x, y, z), block.defaultBlockState());
          }
      // Lift the walking floor to y=2, giving every module the same doorway height.
      for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++)
          out.put(
              b.offset(x, 2, z),
              (x == 2 || z == 2 || x == 13 || z == 13
                      ? switch (room.role()) {
                        case ARRIVAL -> Blocks.CONCRETE.lightBlue();
                        case WORKSHOP -> Blocks.CONCRETE.orange();
                        case HABITATION -> Blocks.CONCRETE.green();
                        case ENGINEERING -> Blocks.CONCRETE.yellow();
                      }
                      : Blocks.QUARTZ_BLOCK)
                  .defaultBlockState());
      for (int x : new int[] {3, 8, 12})
        for (int z : new int[] {3, 8, 12})
          out.put(b.offset(x, 8, z), Blocks.SEA_LANTERN.defaultBlockState());
      machine(out, b.offset(4, 3, 1), ShipBlocks.AIR_SUPPLY, Direction.SOUTH);
      switch (room.role()) {
        case ARRIVAL -> {
          core = b.offset(4, 3, 4);
          machine(out, core, ShipBlocks.STATION_CORE, Direction.SOUTH);
          machine(out, b.offset(5, 3, 4), ShipBlocks.TERMINAL, Direction.SOUTH);
          machine(out, b.offset(6, 3, 4), ShipBlocks.LOCAL_SCANNER, Direction.SOUTH);
          machine(out, b.offset(7, 3, 4), ShipBlocks.MONITOR, Direction.SOUTH);
          out.put(b.offset(10, 3, 10), Blocks.BARREL.defaultBlockState());
        }
        case WORKSHOP -> {
          for (int x = 3; x < 7; x++) out.put(b.offset(x, 3, 3), Blocks.BARREL.defaultBlockState());
          out.put(b.offset(4, 3, 4), Blocks.CRAFTING_TABLE.defaultBlockState());
          out.put(b.offset(5, 3, 4), Blocks.FURNACE.defaultBlockState());
        }
        case HABITATION -> {
          for (int x = 3; x < 7; x++)
            out.put(b.offset(x, 3, 4), Blocks.OAK_STAIRS.defaultBlockState());
          out.put(b.offset(4, 3, 10), Blocks.BARREL.defaultBlockState());
        }
        case ENGINEERING -> {
          machine(out, b.offset(5, 3, 3), ShipBlocks.GENERATOR, Direction.SOUTH);
          machine(out, b.offset(6, 3, 3), ShipBlocks.GENERATOR, Direction.SOUTH);
          machine(out, b.offset(7, 3, 3), ShipBlocks.BATTERY, Direction.SOUTH);
          machine(out, b.offset(8, 3, 3), ShipBlocks.BATTERY, Direction.SOUTH);
          machine(out, b.offset(9, 3, 3), ShipBlocks.CAMERA, Direction.SOUTH);
        }
      }
    }
    if (core == null) core = layout.rooms().getFirst().origin().offset(1, 3, 1);
    for (var edge : layout.joints()) {
      connect(out, layout.room(edge.a()), layout.room(edge.b()));
    }
    boolean large = layout.rooms().stream().anyMatch(r -> r.x() > 1 || r.z() > 1);
    for (var room : layout.rooms()) {
      var faces = new ArrayList<Direction>();
      if (!large && room.z() == 0) faces.add(room.x() == 0 ? Direction.WEST : Direction.EAST);
      if (large) {
        if ((room.x() == 2 || room.x() == 6) && room.z() == 0) faces.add(Direction.NORTH);
        if ((room.x() == 1 || room.x() == 5) && room.z() == 7) faces.add(Direction.SOUTH);
        if ((room.z() == 1 || room.z() == 5) && room.x() == 0) faces.add(Direction.WEST);
        if ((room.z() == 2 || room.z() == 6) && room.x() == 7) faces.add(Direction.EAST);
      }
      for (var face : faces) {
        if (layout.rooms().stream()
            .anyMatch(
                r -> r.x() == room.x() + face.getStepX() && r.z() == room.z() + face.getStepZ()))
          continue;
        var at = StationLayout.doorway(room, face);
        compact(out, at, face);
        docks.add(at.below());
      }
    }
    return new Plan(out, core, docks);
  }

  public static void compact(Map<BlockPos, BlockState> out, BlockPos door, Direction facing) {
    var side = facing.getClockWise();
    for (var direction : List.of(side, side.getOpposite()))
      for (int y = 0; y < 2; y++)
        out.put(door.relative(direction).above(y), Blocks.QUARTZ_BLOCK.defaultBlockState());
    out.put(
        door,
        Blocks.OAK_DOOR
            .defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    out.put(
        door.above(),
        Blocks.OAK_DOOR
            .defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    machine(out, door.below(), ShipBlocks.DOCKING_BASE, facing);
    out.put(door.above(2), Blocks.QUARTZ_BLOCK.defaultBlockState());
    for (int depth = 1; depth <= 2; depth++) {
      out.put(door.relative(facing.getOpposite(), depth), Blocks.AIR.defaultBlockState());
      out.put(door.relative(facing.getOpposite(), depth).above(), Blocks.AIR.defaultBlockState());
    }
  }

  public static Plan foundation() {
    var out = new LinkedHashMap<BlockPos, BlockState>();
    for (int x = 4; x <= 11; x++)
      for (int z = 4; z <= 11; z++)
        out.put(new BlockPos(x, 2, z), Blocks.IRON_BLOCK.defaultBlockState());
    var core = new BlockPos(5, 3, 5);
    machine(out, core, ShipBlocks.STATION_CORE, Direction.SOUTH);
    return new Plan(out, core, List.of());
  }

  private StationBlueprint() {}
}
