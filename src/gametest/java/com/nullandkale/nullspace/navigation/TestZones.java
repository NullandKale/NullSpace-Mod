package com.nullandkale.nullspace.navigation;

import net.minecraft.server.MinecraftServer;

/** Zones from the test world's random galaxy chart (P9), for tests that need "somewhere else". */
public final class TestZones {
  /** A zone in another chart place that the starting engine can reach from Sol in one jump. */
  public static String elsewhere(MinecraftServer server) {
    var galaxy = StarSystems.galaxy(server);
    var sol = galaxy.place(GalaxyPlan.HOME);
    var next =
        galaxy.places().stream()
            .filter(p -> p != sol && GalaxyPlan.distance(sol, p) <= GalaxyPlan.STARTING_RANGE)
            .findFirst()
            .orElseThrow();
    return firstZone(galaxy, next);
  }

  /** A zone in a different chart place than Sol, the first in chart order. */
  public static String otherSystem(MinecraftServer server) {
    var galaxy = StarSystems.galaxy(server);
    return galaxy.systems().get(1).bodies().getFirst().id();
  }

  /** A landable zone outside Sol, for surface tests. */
  public static String landable(MinecraftServer server) {
    var galaxy = StarSystems.galaxy(server);
    for (var s : galaxy.systems().subList(1, galaxy.systems().size()))
      for (var b : s.bodies()) if (b.landable()) return b.id();
    throw new IllegalStateException("Every non-home system has a landable body");
  }

  private static String firstZone(GalaxyPlan.Galaxy galaxy, GalaxyPlan.Place place) {
    if (place instanceof GalaxyPlan.StarSystem s) return s.bodies().getFirst().id();
    return place.id();
  }

  private TestZones() {}
}
