package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.scene.SceneVolumes;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.space.VoxelVolume;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Saved, retractable boarding collars. The normal boarding handoff joins their storage frames. */
public final class DockingTunnel {
  private static BlockState state(String kind) {
    return switch (kind) {
      case "window" -> Blocks.GLASS.defaultBlockState();
      case "light" -> Blocks.SEA_LANTERN.defaultBlockState();
      case "stop" -> Blocks.BARRIER.defaultBlockState();
      default -> Blocks.CONCRETE.gray().defaultBlockState();
    };
  }

  private static CompoundTag plan(ServerLevel level, DockingService.Port port, int length) {
    var result = new CompoundTag();
    result.putString("id", UUID.randomUUID().toString());
    var blocks = new ListTag();
    var forward = port.facing();
    var right = forward.getClockWise();
    for (int depth = 1; depth <= length + 1; depth++)
      for (int side = -1; side <= 1; side++)
        for (int y = -1; y <= 2; y++) {
          boolean stop = depth == length + 1;
          if (stop ? side != 0 || y < 0 || y > 1 : side == 0 && y >= 0 && y <= 1) continue;
          var at = port.outer().relative(forward, depth).relative(right, side).offset(0, y, 0);
          if (!level.getBlockState(at).isAir()) continue;
          var block = new CompoundTag();
          block.putLong("at", at.asLong());
          block.putString("kind", stop ? "stop" : y == 2 ? "light" : y < 0 ? "floor" : "window");
          blocks.add(block);
        }
    result.put("blocks", blocks);
    return result;
  }

  public static void ensure(
      MinecraftServer server,
      CompoundTag dock,
      DockingService.Port source,
      DockingService.Port target) {
    if (dock.getBooleanOr("construction", false)
        || target == null
        || !source.inner().equals(source.outer())
        || !target.inner().equals(target.outer())) return;
    var level = server.getLevel(TravelService.SPACE);
    if (!dock.contains("tunnel")) {
      int length =
          Math.max(1, (int) Math.ceil(Math.clamp(dock.getDoubleOr("gap", .05), .05, 4.05) / 2));
      var tunnel = new CompoundTag();
      tunnel.put("source", plan(level, source, length));
      tunnel.put("target", plan(level, target, length));
      dock.put("tunnel", tunnel);
      try {
        WorldStore.get(server).save();
      } catch (RuntimeException failed) {
        dock.remove("tunnel");
        throw failed;
      }
    }
    for (String side : List.of("source", "target"))
      for (var tag :
          dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks")) {
        var block = (CompoundTag) tag;
        var at = WorldStore.pos(block, "at");
        if (level.getBlockState(at).isAir())
          level.setBlock(at, state(block.getStringOr("kind", "floor")), 18);
      }
  }

  public static boolean occupied(MinecraftServer server, CompoundTag dock) {
    var level = server.getLevel(TravelService.SPACE);
    for (String side : List.of("source", "target"))
      for (var tag :
          dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks")) {
        var block = (CompoundTag) tag;
        if (!block.getStringOr("kind", "").equals("floor")) continue;
        if (!level
            .getEntitiesOfClass(
                net.minecraft.server.level.ServerPlayer.class,
                new AABB(WorldStore.pos(block, "at").above()).expandTowards(0, 1, 0))
            .isEmpty()) return true;
      }
    return false;
  }

  /** Include the protected walking space up to the saved collar's end stop. */
  static double boardingReach(CompoundTag dock, String vessel, DockingService.Port port) {
    if (dock == null || !dock.getStringOr("state", "").equals("CLAMPED")) return 1;
    String side =
        dock.getStringOr("source", "").equals(vessel)
            ? "source"
            : dock.getStringOr("target", "").equals(vessel) ? "target" : "";
    double reach = 1;
    for (var tag :
        dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks")) {
      var block = (CompoundTag) tag;
      if (!block.getStringOr("kind", "").equals("stop")) continue;
      var delta = WorldStore.pos(block, "at").subtract(port.outer());
      double depth = Vec3.atLowerCornerOf(delta).dot(DockingMotion.normal(port.facing()));
      reach = Math.max(reach, depth - .5);
    }
    return reach;
  }

  /** Keep the saved plan until block removal has been flushed before releasing the link. */
  public static void retract(MinecraftServer server, CompoundTag dock) {
    var level = server.getLevel(TravelService.SPACE);
    for (String side : List.of("source", "target"))
      for (var tag :
          dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks")) {
        var block = (CompoundTag) tag;
        var at = WorldStore.pos(block, "at");
        if (level.getBlockState(at).equals(state(block.getStringOr("kind", "floor"))))
          level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
      }
  }

  private record Collar(CompoundTag dock, CompoundTag plan) {}

  private static List<Collar> collars(ServerLevel level, CompoundTag ship) {
    var result = new ArrayList<Collar>();
    for (var tag : WorldStore.get(level.getServer()).ships()) {
      var owner = (CompoundTag) tag;
      var dock = owner.getCompoundOrEmpty("dock");
      if (!dock.getStringOr("source", "").equals(owner.getStringOr("id", ""))
          || !dock.getStringOr("state", "").equals("CLAMPED")) continue;
      String side =
          dock.getStringOr("source", "").equals(ship.getStringOr("id", ""))
              ? "source"
              : dock.getStringOr("target", "").equals(ship.getStringOr("id", "")) ? "target" : "";
      var plan = dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side);
      if (!side.isEmpty() && !plan.getListOrEmpty("blocks").isEmpty())
        result.add(new Collar(dock, plan));
    }
    return result;
  }

  public static int sections(ServerLevel level, CompoundTag ship) {
    return collars(level, ship).size();
  }

  /** Exterior collar blocks can extend beyond a vessel's reserved 32-block render section. */
  public static void render(ServerLevel level, CompoundTag ship, CompoundTag subject) {
    var sections = subject.getListOrEmpty("sections");
    for (var collar : collars(level, ship)) {
      var plan = collar.plan();
      int x = Integer.MAX_VALUE, y = x, z = x;
      for (var tag : plan.getListOrEmpty("blocks")) {
        var at = WorldStore.pos((CompoundTag) tag, "at");
        x = Math.min(x, at.getX());
        y = Math.min(y, at.getY());
        z = Math.min(z, at.getZ());
      }
      var base = new BlockPos(x, y, z);
      var cells = new int[VoxelVolume.MAX_CELLS];
      var light = new byte[VoxelVolume.MAX_CELLS];
      Arrays.fill(light, (byte) 15);
      for (var tag : plan.getListOrEmpty("blocks")) {
        var block = (CompoundTag) tag;
        if (block.getStringOr("kind", "").equals("stop")) continue;
        var at = WorldStore.pos(block, "at");
        if (!level.getBlockState(at).equals(state(block.getStringOr("kind", "floor")))) continue;
        var local = at.subtract(base);
        cells[local.getX() + local.getZ() * 32 + local.getY() * 1024] =
            Block.getId(level.getBlockState(at));
      }
      var data = new CompoundTag();
      String id = "dock-" + plan.getStringOr("id", "");
      data.putString("id", id);
      data.putString("geometryId", id);
      data.putString("parent", ship.getStringOr("id", ""));
      data.putLong("geometryRevision", 1);
      data.putLong("base", base.asLong());
      var at =
          SceneService.pose(ship)
              .toUniverse(Vec3.atLowerCornerOf(base.subtract(WorldStore.pos(ship, "base"))));
      data.putDouble("x", at.x);
      data.putDouble("y", at.y);
      data.putDouble("z", at.z);
      for (String angle : List.of("yaw", "pitch", "roll"))
        data.putDouble(
            angle,
            ship.getDoubleOr(
                "sim" + Character.toUpperCase(angle.charAt(0)) + angle.substring(1), 0));
      data.putBoolean("ready", true);
      data.put("geometry", SceneVolumes.geometry(SceneVolumes.blocks(cells), light));
      sections.add(data);
    }
    if (!sections.isEmpty()) subject.put("sections", sections);
  }

  public static boolean protectedAt(ServerLevel level, BlockPos at) {
    for (var tag : WorldStore.get(level.getServer()).ships()) {
      var dock = ((CompoundTag) tag).getCompoundOrEmpty("dock");
      for (String side : List.of("source", "target"))
        for (var cell :
            dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks"))
          if (WorldStore.pos((CompoundTag) cell, "at").equals(at)) return true;
    }
    return false;
  }

  /** Temporary collars are not cargo, hull extensions or launch-column obstructions. */
  public static Set<BlockPos> positions(ServerLevel level) {
    if (!level.dimension().equals(TravelService.SPACE)) return Set.of();
    var result = new HashSet<BlockPos>();
    for (var tag : WorldStore.get(level.getServer()).ships()) {
      var dock = ((CompoundTag) tag).getCompoundOrEmpty("dock");
      for (String side : List.of("source", "target"))
        for (var cell :
            dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty(side).getListOrEmpty("blocks")) {
          var block = (CompoundTag) cell;
          var at = WorldStore.pos(block, "at");
          if (level.hasChunkAt(at)
              && level.getBlockState(at).equals(state(block.getStringOr("kind", "floor"))))
            result.add(at);
        }
    }
    return result;
  }

  private DockingTunnel() {}
}
