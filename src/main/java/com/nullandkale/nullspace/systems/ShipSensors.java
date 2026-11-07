package com.nullandkale.nullspace.systems;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public final class ShipSensors {
  public static boolean powered(ServerLevel level, CompoundTag ship, BlockPos scanner) {
    var blocks = ShipSystems.connected(level, ship);
    if (!blocks.contains(scanner) || !ShipSystems.active(ship, scanner)) return false;
    long cameras =
        blocks.stream()
            .filter(
                p -> level.getBlockState(p).is(ShipBlocks.CAMERA) && ShipSystems.active(ship, p))
            .count();
    var sensors =
        blocks.stream()
            .filter(
                p ->
                    (level.getBlockState(p).is(ShipBlocks.LOCAL_SCANNER))
                        && ShipSystems.active(ship, p))
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .toList();
    int index = sensors.indexOf(scanner);
    return index >= 0 && ShipSystems.power(level, ship, PowerBus.System.CAMERAS) > cameras + index;
  }

  public static int range(ServerLevel level, CompoundTag ship) {
    int tier =
        ShipSystems.connected(level, ship).stream()
            .filter(
                p ->
                    (level.getBlockState(p).is(ShipBlocks.LOCAL_SCANNER))
                        && ShipSystems.active(ship, p))
            .filter(p -> powered(level, ship, p))
            .mapToInt(p -> FuelSystem.tier(level, p))
            .max()
            .orElse(0);
    return 1024 << tier;
  }

  public static boolean installed(ServerLevel level, CompoundTag ship, boolean far) {
    var blocks = ShipSystems.connected(level, ship);
    long cameras =
        blocks.stream()
            .filter(
                p -> level.getBlockState(p).is(ShipBlocks.CAMERA) && ShipSystems.active(ship, p))
            .count();
    var sensors =
        blocks.stream()
            .filter(
                p ->
                    (level.getBlockState(p).is(ShipBlocks.LOCAL_SCANNER))
                        && ShipSystems.active(ship, p))
            .sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
            .toList();
    int granted = ShipSystems.power(level, ship, PowerBus.System.CAMERAS);
    for (int i = 0; i < sensors.size(); i++)
      if (granted > cameras + i
          && level.getBlockState(sensors.get(i)).is(ShipBlocks.LOCAL_SCANNER)
          && (!far || FuelSystem.tier(level, sensors.get(i)) > 0)) return true;
    return false;
  }

  public static String scan(ServerLevel level, CompoundTag ship, boolean far) {
    if (!installed(level, ship, far))
      throw new IllegalArgumentException(
          "Install, enable and power a "
              + (far ? "long-range" : "local")
              + " scanner on the cameras/sensors bus");
    if (far)
      return "JUMP TARGETS\n"
          + String.join(
              "\n",
              LocalSpaces.destinations(level.getServer()).stream()
                  .map(
                      d ->
                          d.id()
                              + " | "
                              + d.systemName()
                              + " / "
                              + d.name()
                              + (d.id().equals(LocalSpaces.address(ship))
                                  ? " | CURRENT"
                                  : " | AVAILABLE"))
                  .toList());
    var lines = new ArrayList<String>();
    String address =
        level.dimension().equals(TravelService.SPACE)
            ? LocalSpaces.address(ship)
            : PlanetSurfaces.address(level);
    if (address == null) address = LocalSpaces.HOME;
    lines.add("LOCAL SPACE: " + LocalSpaces.get(level.getServer(), address).name());
    lines.add("Environment: " + PlanetSurfaces.atmosphere(level));
    var own = SceneService.pose(ship);
    lines.add(
        String.format(
            Locale.ROOT,
            "Position %.1f %.1f %.1f",
            own.position().x,
            own.position().y,
            own.position().z));
    var definition = SpaceDefinitionCodec.decode(SceneCatalog.region(level.getServer(), address));
    for (var body : definition.bodies())
      lines.add(
          String.format(
              Locale.ROOT,
              "%s | range %.0f | radius %.0f",
              body.id(),
              body.center().distanceTo(own.position()),
              body.radius()));
    int contacts = 0;
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var other = (CompoundTag) tag;
      if (other == ship
          || !other.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
          || !LocalSpaces.together(ship, other)) continue;
      double distance = SceneService.pose(other).position().distanceTo(own.position());
      if (distance > range(level, ship)) continue;
      lines.add(
          String.format(
              Locale.ROOT,
              "CONTACT %s | %.1f blocks",
              other.getStringOr("home", "Ship"),
              distance));
      if (++contacts >= 16) break;
    }
    lines.add("Contacts: " + contacts + " | local range " + range(level, ship));
    return String.join("\n", lines);
  }

  public static List<Map<String, Object>> targets(ServerLevel level, CompoundTag ship) {
    if (!installed(level, ship, true))
      throw new IllegalArgumentException("Power a long-range scanner first");
    return LocalSpaces.destinations(level.getServer()).stream()
        .map(
            d ->
                Map.<String, Object>of(
                    "id",
                    d.id(),
                    "name",
                    d.name(),
                    "system",
                    d.system(),
                    "current",
                    d.id().equals(LocalSpaces.address(ship))))
        .toList();
  }

  public static List<Map<String, Object>> contacts(ServerLevel level, CompoundTag ship) {
    if (!installed(level, ship, false))
      throw new IllegalArgumentException("Power a local scanner first");
    var own = SceneService.pose(ship);
    var result = new ArrayList<Map<String, Object>>();
    for (var t : ShipStore.get(level.getServer()).ships()) {
      var other = (CompoundTag) t;
      if (other == ship
          || !other.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
          || !LocalSpaces.together(ship, other)) continue;
      var position = SceneService.pose(other).position();
      double distance = position.distanceTo(own.position());
      if (distance <= range(level, ship) && result.size() < 16)
        result.add(
            Map.of(
                "id",
                other.getStringOr("id", ""),
                "name",
                other.getStringOr("home", "Ship"),
                "range",
                distance,
                "x",
                position.x,
                "y",
                position.y,
                "z",
                position.z));
    }
    return List.copyOf(result);
  }

  public static String equipment(ServerLevel level, BlockPos pos) {
    return scan(
        level,
        ShipTopology.resolve(level, pos),
        FuelSystem.tier(level, pos) > 0);
  }

  private ShipSensors() {}
}
