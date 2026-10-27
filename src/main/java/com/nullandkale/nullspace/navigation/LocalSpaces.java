package com.nullandkale.nullspace.navigation;

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
      com.nullandkale.nullspace.content.ContentCatalog.PLANETS.stream()
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

  public static String address(CompoundTag ship) {
    return ship == null ? HOME : ship.getStringOr("localSpace", HOME);
  }

  public static boolean together(CompoundTag a, CompoundTag b) {
    return address(a).equals(address(b));
  }

  public static String label(CompoundTag ship) {
    var d = get(address(ship));
    return d.systemName + " / " + d.name;
  }

  private LocalSpaces() {}
}
