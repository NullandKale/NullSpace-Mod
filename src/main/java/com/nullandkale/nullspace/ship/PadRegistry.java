package com.nullandkale.nullspace.ship;

import com.google.gson.*;
import com.nullandkale.nullspace.pad.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class PadRegistry {
  public record Pad(
      String id, String name, String dimension, BlockPos computer, PadScanner.Pad bounds) {}

  public enum State {
    READY,
    OCCUPIED,
    UNAVAILABLE,
    UNKNOWN
  }

  private static final class UnloadedTerrain extends RuntimeException {}

  public record Status(Pad pad, State state, String detail) {}

  /**
   * Prefer the nearest registered floor below a controller; never choose a lower stacked pad by
   * file order.
   */
  public static java.util.Optional<Pad> below(ServerLevel level, BlockPos at) {
    PadRefreshService.near(level, at);
    return PadRepository.get(level.getServer())
        .dimension(level.dimension().identifier().toString())
        .stream()
        .filter(
            p -> {
              var b = p.bounds();
              return p.dimension().equals(level.dimension().identifier().toString())
                  && at.getX() >= b.minX()
                  && at.getX() <= b.maxX()
                  && at.getZ() >= b.minZ()
                  && at.getZ() <= b.maxZ()
                  && at.getY() > b.y()
                  && at.getY() <= b.y() + 32;
            })
        .max(java.util.Comparator.comparingInt(p -> p.bounds().y()));
  }

  public static int buildHeight(
      ServerLevel level, com.nullandkale.nullspace.pad.PadScanner.Pad pad) {
    int ceiling = pad.y() + 33;
    for (var other :
        PadRepository.get(level.getServer()).dimension(level.dimension().identifier().toString())) {
      var b = other.bounds();
      if (other.dimension().equals(level.dimension().identifier().toString())
          && b.y() > pad.y()
          && b.minX() <= pad.maxX()
          && b.maxX() >= pad.minX()
          && b.minZ() <= pad.maxZ()
          && b.maxZ() >= pad.minZ()) ceiling = Math.min(ceiling, b.y());
    }
    return Math.clamp(ceiling - pad.y() - 1, 0, 32);
  }

  /** Registration changes resize ground allocations while retaining their world-space state. */
  public static void reconcileHeights(ServerLevel level) {
    var store = ShipStore.get(level.getServer());
    var pads = read(level.getServer());
    for (var tag : store.ships()) {
      var ship = (net.minecraft.nbt.CompoundTag) tag;
      if (!ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
          || level.dimension().equals(TravelService.SPACE)) continue;
      if (TravelService.locked(level, ShipStore.pos(ship, "controller"))) continue;
      var pad =
          pads.stream().filter(p -> p.id().equals(ship.getStringOr("homeId", ""))).findFirst();
      if (pad.isEmpty()) continue;
      var b = pad.get().bounds();
      int height = buildHeight(level, b);
      var nextBase = new BlockPos(b.minX(), b.y() + 1, b.minZ());
      var delta = ShipStore.pos(ship, "base").subtract(nextBase);
      if (ship.getIntOr("height", 32) != height
          || ship.getIntOr("width", 32) != b.width()
          || ship.getIntOr("depth", 32) != b.depth()
          || !delta.equals(BlockPos.ZERO)) {
        com.nullandkale.nullspace.systems.ShipSystems.cancelTransit(level, ship);
        store
            .repository()
            .mutate(
                new ShipRepository.ShipId(ship.getStringOr("id", "")),
                ShipRepository.Mutation.CONFIGURATION,
                candidate -> ShipAllocation.resize(candidate, b, height));
        com.nullandkale.nullspace.computer.ComputerService.rebase(
            level.getServer(), ship.getStringOr("id", ""), delta);
      }
    }
  }

  public static List<Status> states(MinecraftServer server) {
    return read(server).stream().map(p -> state(server, p)).toList();
  }

  /** Listing never loads remote terrain. UNKNOWN is distinct from an inspected unusable pad. */
  public static Status state(MinecraftServer server, Pad pad) {
    return com.nullandkale.nullspace.pad.PadStatusService.status(server, pad);
  }

  public static Status inspectState(MinecraftServer server, Pad pad) {
    ServerLevel level;
    try {
      level = ShipStore.level(server, pad.dimension());
    } catch (IllegalArgumentException unavailable) {
      return new Status(pad, State.UNAVAILABLE, "Dimension missing");
    }
    var b = pad.bounds();
    for (int x = b.minX() - 1; x <= b.maxX() + 1; x++)
      for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++)
        if (!level.hasChunkAt(new BlockPos(x, b.y(), z)))
          return new Status(pad, State.UNKNOWN, "Terrain not loaded");
    if (!level.hasChunkAt(pad.computer()))
      return new Status(pad, State.UNKNOWN, "Computer not loaded");
    try {
      validate(level, pad, false);
    } catch (UnloadedTerrain unloaded) {
      return new Status(pad, State.UNKNOWN, "Terrain not loaded");
    } catch (IllegalArgumentException invalid) {
      return new Status(pad, State.UNAVAILABLE, invalid.getMessage());
    }
    for (int x = b.minX(); x <= b.maxX(); x++)
      for (int z = b.minZ(); z <= b.maxZ(); z++)
        for (int y = b.y() + 1; y <= level.getMaxY(); y++)
          if (!level.getBlockState(new BlockPos(x, y, z)).isAir())
            return new Status(
                pad,
                State.OCCUPIED,
                "Launch column contains "
                    + ShipMessages.block(level, new BlockPos(x, y, z))
                    + " at "
                    + x
                    + ", "
                    + y
                    + ", "
                    + z);
    return new Status(pad, State.READY, "Surface, border and sky clear");
  }

  public static List<Pad> read(MinecraftServer server) {
    return com.nullandkale.nullspace.pad.PadRepository.get(server).all();
  }

  public static void reload(MinecraftServer server) {
    com.nullandkale.nullspace.pad.PadRepository.get(server).reload();
  }

  public static void validate(ServerLevel level, Pad pad) {
    validate(level, pad, true);
  }

  private static void validate(ServerLevel level, Pad pad, boolean load) {
    var p = pad.computer();
    if (load) level.getChunk(p);
    else if (!level.hasChunkAt(p)) throw new UnloadedTerrain();
    if (!level.getBlockState(p).is(PadComputers.BLOCK))
      throw new IllegalArgumentException("Pad computer is missing: " + pad.name());
    var state = level.getBlockState(p);
    var facing = state.getValue(PadComputerBlock.FACING);
    var result =
        PadScanner.scan(
            (x, y, z) -> {
              var pos = new BlockPos(x, y, z);
              if (!load && !level.hasChunkAt(pos)) throw new UnloadedTerrain();
              var s = level.getBlockState(pos);
              if (s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK))
                return PadScanner.Cell.GRASS;
              if (s.is(net.minecraft.world.level.block.Blocks.DIRT)) return PadScanner.Cell.DIRT;
              if (s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE))
                return PadScanner.Cell.BORDER;
              return PadScanner.Cell.OTHER;
            },
            p.getX(),
            p.getY(),
            p.getZ(),
            facing.getStepX(),
            facing.getStepZ());
    if (!result.valid() || !result.pad().equals(pad.bounds()))
      throw new IllegalArgumentException("Pad changed: " + result.message());
  }

  private PadRegistry() {}
}
