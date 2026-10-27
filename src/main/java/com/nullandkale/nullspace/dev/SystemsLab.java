package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.nio.file.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Additive powered ships. Original gallery, sphere and player builds are never retrofitted in
 * place.
 */
public final class SystemsLab {
  public static BlockPos ensure(MinecraftServer server) throws java.io.IOException {
    var path = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-systems-lab-v1.nbt");
    if (Files.exists(path)) {
      var saved = ShipStore.read(path);
      if (!saved.getBooleanOr("complete", false))
        throw new IllegalStateException(
            "Systems lab creation was interrupted; existing blocks preserved");
      return ShipStore.pos(saved, "origin");
    }
    var gallery = ShipGallery.ensure(server);
    var annex = SphereLab.ensure(server, gallery);
    var level = server.overworld();
    BlockPos origin = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      int x = annex.origin().getX() + SphereLab.WIDTH + 8 + attempt * 96, z = annex.origin().getZ();
      var area = new com.nullandkale.nullspace.pad.PadScanner.Pad(x, x + 79, 0, z, z + 45);
      if (PadRegistry.read(server).stream()
          .anyMatch(
              p ->
                  p.dimension().equals(level.dimension().identifier().toString())
                      && area.overlaps(p.bounds()))) continue;
      for (int cx = x >> 4; cx <= (x + 79) >> 4; cx++)
        for (int cz = z >> 4; cz <= (z + 45) >> 4; cz++) level.getChunk(cx, cz);
      int y = annex.origin().getY();
      for (int dx = 0; dx < 80; dx++)
        for (int dz = 0; dz < 46; dz++)
          y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) + 4);
      if (y + 36 < level.getMaxY()) {
        origin = new BlockPos(x, y, z);
        break;
      }
    }
    if (origin == null) throw new IllegalStateException("No clear systems lab site");
    var saved = new CompoundTag();
    saved.putLong("origin", origin.asLong());
    saved.putBoolean("complete", false);
    ShipStore.write(path, saved);
    for (int x = 0; x < 80; x++)
      for (int z = 0; z < 46; z++) {
        level.setBlock(
            origin.offset(x, 0, z),
            ((x % 5 == 0 && z % 5 == 0) ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray())
                .defaultBlockState(),
            18);
        if (x == 0 || x == 79 || z == 0 || z == 45)
          level.setBlock(origin.offset(x, 1, z), Blocks.GLASS.defaultBlockState(), 18);
      }
    for (int plot = 23; plot <= 24; plot++) {
      var base = origin.offset(plot == 23 ? 4 : 44, 0, 10);
      String name = gallery.prefix() + " " + plot + " " + (plot == 23 ? "Systems" : "Target");
      int suffix = 2;
      String candidate = name;
      while (true) {
        String test = candidate;
        if (PadRegistry.read(server).stream().noneMatch(p -> p.name().equalsIgnoreCase(test)))
          break;
        candidate = name + " " + suffix++;
      }
      SphereLab.buildPad(level, base, candidate);
      SphereLab.buildShip(level, base);
      // Keep the deck label out of the equipment row's interaction ray.
      level.setBlock(base.offset(15, 6, 20), Blocks.AIR.defaultBlockState(), 18);
      ShipGallery.label(
          level,
          base.offset(11, 6, 20),
          net.minecraft.world.item.DyeColor.BLUE,
          "DECK 1",
          "ENGINEERING",
          "Right-click equipment",
          "Arrows / Enter");
      level.setBlock(
          SphereLab.controller(base),
          ShipBlocks.HELM
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
      var devices =
          new Block[] {
            ShipBlocks.GENERATOR,
            ShipBlocks.BATTERY,
            ShipBlocks.AIR_SUPPLY,
            ShipBlocks.DOOR_CONTROL,
            ShipBlocks.SYSTEMS_CONTROLLER,
            ShipBlocks.MONITOR
          };
      for (int i = 0; i < devices.length; i++)
        level.setBlock(
            base.offset(14 + i, 6, 21),
            devices[i]
                .defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
            18);
      level.setBlock(base.offset(14, 6, 22), ShipBlocks.GENERATOR.defaultBlockState(), 18);
      level.setBlock(
          base.offset(16, 7, 21),
          ShipBlocks.CAMERA
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
      level.setBlock(
          base.offset(16, 18, 31),
          ShipBlocks.CAMERA
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH),
          18);
      level.setBlock(
          base.offset(18, 16, 22),
          ShipBlocks.SYSTEMS_CONTROLLER
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
      level.setBlock(
          base.offset(18, 16, 23),
          ShipBlocks.MONITOR
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
      level.setBlock(
          base.offset(plot == 23 ? 31 : 0, 16, 16),
          ShipBlocks.WEAPON
              .defaultBlockState()
              .setValue(
                  HorizontalDirectionalBlock.FACING, plot == 23 ? Direction.EAST : Direction.WEST),
          18);
      var ship = ShipTopology.resolve(level, SphereLab.controller(base));
      ShipSystems.upgrade(level, ship);
      ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[] {1, 2, 1, 3, 2, 0});
      ship.putDouble("simX", plot == 23 ? 0 : 180);
      ship.putDouble("simY", 0);
      ship.putDouble("simZ", 0);
      ShipService.inspect(level, SphereLab.controller(base));
      ShipGallery.label(
          level,
          base.offset(8, 1, -3),
          net.minecraft.world.item.DyeColor.GREEN,
          plot == 23 ? "23 SYSTEMS" : "24 TARGET",
          "Helm on deck 3",
          "Engineering deck 1",
          "Camera: Tab changes");
    }
    ShipStore.get(server).save();
    server.saveEverything(true, true, true);
    saved.putBoolean("complete", true);
    ShipStore.write(path, saved);
    return origin;
  }

  public static BlockPos base(BlockPos origin, int plot) {
    return origin.offset(plot == 23 ? 4 : 44, 0, 10);
  }

  private SystemsLab() {}
}
