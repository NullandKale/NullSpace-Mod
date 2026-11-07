package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.ship.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;

/**
 * Links between zones and surface dimensions. The world's galaxy chart assigns each landable body
 * a surface from the fixed pool (P9); other zones have no surface.
 */
public final class PlanetSurfaces {
  /** The zone whose surface this level is, or null for space and unassigned surfaces. */
  public static String address(Level level) {
    if (level.dimension().equals(Level.OVERWORLD)) return LocalSpaces.HOME;
    if (level.getServer() == null) return null;
    return StarSystems.zoneOfSurface(level.getServer(), level.dimension().identifier().toString());
  }

  /** The surface dimension of a zone, or null if you cannot land there. */
  public static ResourceKey<Level> dimension(MinecraftServer server, String address) {
    if (address.equals(LocalSpaces.HOME)) return Level.OVERWORLD;
    String surface = StarSystems.surface(server, address);
    return surface == null
        ? null
        : ResourceKey.create(Registries.DIMENSION, Identifier.parse(surface));
  }

  /**
   * True if pads in this dimension belong to the zone. End pads belong to Terra's orbit: ships
   * reach the End only from Sol / Terra, and only once someone has registered a pad there.
   */
  public static boolean surfaceOf(MinecraftServer server, String address, String dimension) {
    if (dimension.equals(Level.END.identifier().toString())) return address.equals(LocalSpaces.HOME);
    var key = dimension(server, address);
    return key != null && key.identifier().toString().equals(dimension);
  }

  public static boolean breathable(Level level) {
    if (level.dimension().equals(Level.OVERWORLD)) return true;
    return com.nullandkale.nullspace.content.ContentCatalog.surfaceOfDimension(
            level.dimension().identifier().toString())
        .map(com.nullandkale.nullspace.content.ContentCatalog.PlanetDefinition::breathable)
        .orElse(!level.dimension().equals(TravelService.SPACE));
  }

  public static boolean vacuum(Level level) {
    return !breathable(level);
  }

  public static String atmosphere(Level level) {
    return breathable(level) ? "BREATHABLE" : "VACUUM / SUIT REQUIRED";
  }

  public static void ensureLanding(MinecraftServer server, String address) {
    if (address.equals(LocalSpaces.HOME)) {
      TerraLandingSite.ensure(server.overworld());
      return;
    }
    var key = dimension(server, address);
    if (key == null) return; // Orbit-only zone: nothing to land on.
    var level = server.getLevel(key);
    if (level == null)
      throw new IllegalArgumentException(
          "Planet dimension is unavailable. Restart with the updated mod.");
    String padName = LocalSpaces.get(server, address).name() + " Landing";
    if (PadRegistry.read(server).stream()
        .anyMatch(p -> p.dimension().equals(key.identifier().toString()))) return;
    if (PadRegistry.read(server).stream().anyMatch(p -> p.name().equalsIgnoreCase(padName)))
      throw new IllegalArgumentException(
          "The starter pad name is already used on another world; rename that pad before"
              + " initializing this surface");
    var store = ShipStore.get(server);
    var sites = store.section("surfaceSites");
    if (sites.getBooleanOr(address, false)) return;
    // Real terrain (P9): pick the flattest dry site near the origin, then level a pad there with
    // clear sky above for the ship.
    var site = landingSite(level);
    int offset = site.getX(), originZ = site.getZ(), padY = site.getY();
    for (int x = -1; x <= 32; x++)
      for (int z = -1; z <= 32; z++) {
        var column = new BlockPos(offset + x, padY, originZ + z);
        for (int y = padY + 1; y <= padY + 40; y++)
          level.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = padY - 1; y >= padY - 24; y--) {
          var below = column.atY(y);
          var state = level.getBlockState(below);
          if (!state.isAir() && state.getFluidState().isEmpty()) break;
          level.setBlock(below, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(
            column,
            (x < 0 || x > 31 || z < 0 || z > 31 ? Blocks.COBBLESTONE : Blocks.DIRT)
                .defaultBlockState(),
            Block.UPDATE_CLIENTS);
      }
    var controller = new BlockPos(offset - 1, padY + 1, originZ + 16);
    level.setBlock(
        controller,
        PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.WEST),
        Block.UPDATE_CLIENTS);
    try {
      PadComputers.register(
          level,
          controller,
          new PadScanner.Pad(offset, offset + 31, padY, originZ, originZ + 31),
          padName,
          false);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot register planetary landing site", e);
    }
    sites.putBoolean(address, true);
    store.save();
  }

  /**
   * The corner and height of a 32x32 starter pad: the candidate with the least height variation,
   * above any sea, among a few spots near the origin.
   */
  static BlockPos landingSite(net.minecraft.server.level.ServerLevel level) {
    int sea = level.getSeaLevel();
    BlockPos best = null;
    int bestScore = Integer.MAX_VALUE;
    for (int attempt = 0; attempt < 9; attempt++) {
      int cx = (attempt % 3 - 1) * 96, cz = (attempt / 3 - 1) * 96;
      int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0;
      var heights = new java.util.ArrayList<Integer>();
      for (int x = 0; x <= 32; x += 8)
        for (int z = 0; z <= 32; z += 8) {
          // Estimate from the terrain noise instead of generating chunks: candidates that are not
          // chosen never cost a chunk. Water lies above the ocean floor.
          var chunks = level.getChunkSource();
          int y =
              chunks
                      .getGenerator()
                      .getBaseHeight(
                          cx + x,
                          cz + z,
                          net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                          level,
                          chunks.randomState())
                  - 1;
          int floor =
              chunks
                      .getGenerator()
                      .getBaseHeight(
                          cx + x,
                          cz + z,
                          net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                          level,
                          chunks.randomState())
                  - 1;
          if (y > floor) wet++;
          heights.add(y);
          min = Math.min(min, y);
          max = Math.max(max, y);
        }
      java.util.Collections.sort(heights);
      int median = heights.get(heights.size() / 2);
      int score = (max - min) + wet * 12;
      if (score < bestScore) {
        bestScore = score;
        best = new BlockPos(cx, Math.max(median, sea + 1), cz);
      }
    }
    return best;
  }

  public static void initialize() {
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
        server -> ensureLanding(server, LocalSpaces.HOME));
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("surface")
                            .requires(
                                s ->
                                    s.permissions()
                                        .hasPermission(
                                            net.minecraft.server.permissions.Permissions
                                                .COMMANDS_GAMEMASTER))
                            .then(
                                Commands.argument(
                                        "planet",
                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(
                                        c -> {
                                          String region =
                                              com.mojang.brigadier.arguments.StringArgumentType
                                                  .getString(c, "planet");
                                          ensureLanding(c.getSource().getServer(), region);
                                          var p = c.getSource().getPlayerOrException();
                                          var level =
                                              c.getSource()
                                                  .getServer()
                                                  .getLevel(
                                                      dimension(c.getSource().getServer(), region));
                                          var landing =
                                              PadRegistry.read(c.getSource().getServer()).stream()
                                                  .filter(
                                                      pad ->
                                                          pad.dimension()
                                                              .equals(
                                                                  dimension(
                                                                          c.getSource().getServer(),
                                                                          region)
                                                                      .identifier()
                                                                      .toString()))
                                                  .findFirst()
                                                  .orElseThrow(
                                                      () ->
                                                          new IllegalArgumentException(
                                                              "No landing site is available on this"
                                                                  + " surface"));
                                          var bounds = landing.bounds();
                                          p.teleportTo(
                                              level,
                                              (bounds.minX() + bounds.maxX() + 1) / 2.0,
                                              bounds.y() + 2,
                                              (bounds.minZ() + bounds.maxZ() + 1) / 2.0,
                                              java.util.Set.of(),
                                              0,
                                              0,
                                              true);
                                          return 1;
                                        })))));
  }

  private PlanetSurfaces() {}
}
