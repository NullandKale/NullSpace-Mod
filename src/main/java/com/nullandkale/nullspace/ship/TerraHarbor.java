package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.pad.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** A reserved public arrival floor, separate from the landing pad and its ship volume. */
public final class TerraHarbor {
  public static BlockPos ensure(MinecraftServer server) {
    var store = ShipStore.get(server);
    var site = store.section("harbor");
    var level = server.overworld();
    if (!site.contains("base")) {
      var spawn = level.getRespawnData().pos();
      BlockPos base = null;
      for (int attempt = 0; attempt < 128 && base == null; attempt++) {
        int x = spawn.getX() + 96 + (attempt % 16) * 64;
        int z = spawn.getZ() + 96 + (attempt / 16) * 64;
        int high = level.getMinY();
        for (int dx = 0; dx < 48; dx += 8)
          for (int dz = 0; dz < 40; dz += 8) {
            level.getChunk(new BlockPos(x + dx, 0, z + dz));
            high = Math.max(high, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
          }
        if (high == level.getMinY()) high = 64;
        if (high + 36 >= level.getMaxY()) continue;
        var candidate = new BlockPos(x, high, z);
        var area = new ShipScan.Bounds(candidate, candidate.offset(47, 35, 39));
        boolean clear = true;
        for (var at : BlockPos.betweenClosed(area.min(), area.max()))
          if (!level.getBlockState(at).isAir()) { clear = false; break; }
        if (clear && ShipEntities.inBounds(level, area).isEmpty()) base = candidate;
      }
      if (base == null) throw new IllegalStateException("No clear site for Terra Harbor");
      site.putLong("base", base.asLong());
      store.save(); // Reserve before building, so interrupted construction resumes at the same site.
    }
    var base = ShipStore.pos(site, "base");
    for (int x = 0; x < 48; x += 16)
      for (int z = 0; z < 40; z += 16) level.getChunk(base.offset(x, 0, z));
    if (!site.getBooleanOr("built", false)) {
      for (int x = 0; x < 48; x++)
        for (int z = 0; z < 40; z++)
          level.setBlock(base.offset(x, 0, z), (x < 14 ? Blocks.STONE_BRICKS : Blocks.SMOOTH_STONE).defaultBlockState(), 18);
      for (int x = 0; x < 14; x++)
        for (int z = 0; z < 40; z++) {
          if ((x + z) % 7 == 0) level.setBlock(base.offset(x, 0, z), Blocks.SEA_LANTERN.defaultBlockState(), 18);
          for (int y = 1; y <= 4; y++)
            if ((x == 0 || z == 0 || z == 39) && !(z >= 17 && z <= 21))
              level.setBlock(base.offset(x, y, z), (y == 2 ? Blocks.GLASS_PANE : Blocks.STONE_BRICKS).defaultBlockState(), 18);
        }
      level.setBlock(base.offset(2, 1, 4), Blocks.CRAFTING_TABLE.defaultBlockState(), 18);
      level.setBlock(base.offset(2, 1, 5), Blocks.FURNACE.defaultBlockState(), 18);
      level.setBlock(base.offset(2, 1, 6), Blocks.COMPOSTER.defaultBlockState(), 18);
      // A renewable food source uses normal world rules; arriving does not refill a locker.
      for (int z = 26; z < 36; z++) {
        level.setBlock(base.offset(3, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), 18);
        level.setBlock(base.offset(3, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 18);
        level.setBlock(base.offset(4, 0, z), Blocks.WATER.defaultBlockState(), 18);
      }
      var computer = base.offset(14, 1, 20);
      level.setBlock(computer, PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.WEST), 18);
      try {
        PadComputers.register(level, computer, new PadScanner.Pad(base.getX()+15, base.getX()+46, base.getY(), base.getZ()+4, base.getZ()+35), "Terra Harbor", false);
      } catch (java.io.IOException error) { throw new IllegalStateException("Cannot register Terra Harbor", error); }
      server.saveEverything(false, true, true);
      site.putBoolean("built", true);
      store.save();
    }
    return base;
  }

  public static Vec3 arrival(MinecraftServer server) {
    var base = ensure(server);
    var level = server.overworld();
    for (int z = 8; z <= 24; z += 2)
      for (int x = 6; x <= 11; x += 2) {
        var feet = base.offset(x, 1, z);
        var box = new net.minecraft.world.phys.AABB(feet).setMaxY(feet.getY()+1.8).deflate(.05);
        if (level.getBlockState(feet.below()).isSolidRender()
            && level.noCollision(box) && level.getEntities(null, box).isEmpty())
          return Vec3.atBottomCenterOf(feet);
      }
    throw new IllegalStateException("Terra Harbor arrival area is occupied; rescue will retry");
  }

  public static boolean protectedAt(ServerLevel level, BlockPos at) {
    if (level != level.getServer().overworld()) return false;
    var site = ShipStore.get(level.getServer()).section("harbor");
    if (!site.getBooleanOr("built", false)) return false;
    var base = ShipStore.pos(site, "base");
    // Protect the arrival floor and boundary. The garden remains harvestable.
    return at.getX() >= base.getX() && at.getX() < base.getX()+14
        && at.getZ() >= base.getZ() && at.getZ() < base.getZ()+40
        && at.getY() >= base.getY() && at.getY() <= base.getY()+4
        && !(at.getX() == base.getX()+3 && at.getZ() >= base.getZ()+26 && at.getZ() < base.getZ()+36 && at.getY() == base.getY()+1);
  }

  private TerraHarbor() {}
}
