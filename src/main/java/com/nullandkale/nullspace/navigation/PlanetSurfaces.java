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

/** Explicit surface/region links; all terrain generators are data-driven flat worlds. */
public final class PlanetSurfaces {
  public static String address(Level level) {
    if (level.dimension().equals(Level.OVERWORLD)) return LocalSpaces.HOME;
    String id = level.dimension().identifier().toString();
    return LocalSpaces.DESTINATIONS.stream()
        .filter(
            d ->
                id.equals(
                    com.nullandkale.nullspace.content.ContentCatalog.planet(d.id()).dimension()))
        .map(LocalSpaces.Destination::id)
        .findFirst()
        .orElse(null);
  }

  public static ResourceKey<Level> dimension(String address) {
    LocalSpaces.get(address);
    return address.equals(LocalSpaces.HOME)
        ? Level.OVERWORLD
        : ResourceKey.create(
            Registries.DIMENSION,
            Identifier.parse(
                com.nullandkale.nullspace.content.ContentCatalog.planet(address).dimension()));
  }

  public static boolean breathable(Level level) {
    String region = address(level);
    return region == null
        ? !level.dimension().equals(TravelService.SPACE)
        : com.nullandkale.nullspace.content.ContentCatalog.planet(region).breathable();
  }

  public static boolean vacuum(Level level) {
    return !breathable(level);
  }

  public static String atmosphere(Level level) {
    return breathable(level) ? "BREATHABLE" : "VACUUM / SUIT REQUIRED";
  }

  public static void ensureLanding(MinecraftServer server, String address) {
    if (address.equals(LocalSpaces.HOME)) return;
    var key = dimension(address);
    var level = server.getLevel(key);
    if (level == null)
      throw new IllegalArgumentException(
          "Planet dimension is unavailable. Restart with the updated mod.");
    String padName = LocalSpaces.get(address).name() + " Landing";
    if (PadRegistry.read(server).stream()
        .anyMatch(p -> p.dimension().equals(key.identifier().toString()))) return;
    if (PadRegistry.read(server).stream().anyMatch(p -> p.name().equalsIgnoreCase(padName)))
      throw new IllegalArgumentException(
          "The starter pad name is already used on another world; rename that pad before"
              + " initializing this surface");
    var store = ShipStore.get(server);
    var sites = store.section("surfaceSites");
    if (sites.getBooleanOr(address, false)) return;
    // Choose a clear unused flat site. Never overwrite an obstruction or repair a removed user pad.
    int offset = 0;
    boolean found = false;
    for (int attempt = 0; attempt < 16; attempt++) {
      offset = attempt * 64;
      found = true;
      for (int x = offset - 1; x <= offset + 32 && found; x++)
        for (int z = -1; z <= 32 && found; z++)
          for (int y = 65; y <= 98; y++)
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
              found = false;
              break;
            }
      if (found) break;
    }
    if (!found)
      throw new IllegalArgumentException(
          "No clear starter landing site remains; register a surface pad manually");
    for (int x = -1; x <= 32; x++)
      for (int z = -1; z <= 32; z++)
        level.setBlock(
            new BlockPos(offset + x, 64, z),
            (x < 0 || x > 31 || z < 0 || z > 31 ? Blocks.COBBLESTONE : Blocks.DIRT)
                .defaultBlockState(),
            Block.UPDATE_CLIENTS);
    var controller = new BlockPos(offset - 1, 65, 16);
    level.setBlock(
        controller,
        PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.WEST),
        Block.UPDATE_CLIENTS);
    try {
      PadComputers.register(
          level, controller, new PadScanner.Pad(offset, offset + 31, 64, 0, 31), padName, false);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot register planetary landing site", e);
    }
    sites.putBoolean(address, true);
    store.save();
  }

  public static void initialize() {
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
                                              c.getSource().getServer().getLevel(dimension(region));
                                          p.teleportTo(
                                              level,
                                              16.5,
                                              66,
                                              16.5,
                                              java.util.Set.of(),
                                              0,
                                              0,
                                              true);
                                          return 1;
                                        })))));
  }

  private PlanetSurfaces() {}
}
