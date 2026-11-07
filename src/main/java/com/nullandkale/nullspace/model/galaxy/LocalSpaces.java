package com.nullandkale.nullspace.model.galaxy;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** Stable navigation addresses. A region is a bounded encounter space, not a storage dimension. */
public final class LocalSpaces {
  public record Destination(
      String id,
      String system,
      String systemName,
      String name,
      long seed,
      int color,
      boolean moon) {}

  public static final String HOME = "sol_terra";
  public static final List<Destination> DESTINATIONS =
      ContentCatalog.PLANETS.stream()
          .map(
              d ->
                  new Destination(
                      d.id(), d.system(), d.systemName(), d.name(), d.seed(), d.color(), d.moon()))
          .toList();

  public static Destination get(String id) {
    return DESTINATIONS.stream()
        .filter(d -> d.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown local-space destination"));
  }

  public static List<Destination> destinations(net.minecraft.server.MinecraftServer server) {
    return StarSystems.destinations(server);
  }

  public static Destination get(net.minecraft.server.MinecraftServer server, String id) {
    var journey = JourneyService.region(server, id);
    if (journey != null) return journey;
    return destinations(server).stream()
        .filter(d -> d.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown local-space destination"));
  }

  /** True if the id has the shape of a galaxy zone (P9): Sol's worlds, system bodies, features. */
  public static boolean known(String id) {
    return id != null
        && (DESTINATIONS.stream().anyMatch(d -> d.id().equals(id))
            || id.matches("sol_[0-9]+(_[a-z])?|sys_[a-z]_[0-9]+(_[a-z])?|feature_[a-z]"));
  }

  public static String address(CompoundTag ship) {
    return ship == null ? HOME : ship.getStringOr("localSpace", HOME);
  }

  public static boolean together(CompoundTag a, CompoundTag b) {
    // A stored ship is out of play: it shares no space with anything but itself.
    if (a != b
        && (a != null && a.getBooleanOr("stored", false)
            || b != null && b.getBooleanOr("stored", false))) return false;
    return address(a).equals(address(b));
  }

  public static String label(CompoundTag ship) {
    var d = get(address(ship));
    return d.systemName + " / " + d.name;
  }

  public static String label(net.minecraft.server.MinecraftServer server, CompoundTag ship) {
    var d = get(server, address(ship));
    return d.systemName() + " / " + d.name();
  }

  private LocalSpaces() {}
}
