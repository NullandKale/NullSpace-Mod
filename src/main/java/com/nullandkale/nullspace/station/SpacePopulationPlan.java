package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.navigation.LocalSpaces;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** One seeded inventory of places per orbital region, independent of who arrives first. */
public final class SpacePopulationPlan {
  public static final int VERSION = 2;

  public record Site(String role, String id, String name, long seed, String legacyId) {
    public Vec3 candidate(int attempt) {
      double angle = new Random(seed).nextDouble() * Math.PI * 2 + attempt * Math.PI / 4;
      double radius =
          (role.equals("waypoint") ? 640 : role.equals("exchange") ? 1100 : 1600)
              + attempt / 8 * 384;
      return new Vec3(
          Math.cos(angle) * radius, 48 + Math.floorMod(seed, 96), Math.sin(angle) * radius);
    }
  }

  /** The full set: a Waypoint, an Exchange and one to three salvage ships. */
  public static List<Site> sites(long worldSeed, LocalSpaces.Destination region) {
    return sites(worldSeed, region, null);
  }

  /**
   * The places a region starts with. {@code population} comes from the galaxy chart (P9); null
   * means the full set. Site identities do not depend on which places are chosen.
   */
  public static List<Site> sites(
      long worldSeed,
      LocalSpaces.Destination region,
      com.nullandkale.nullspace.navigation.GalaxyPlan.Population population) {
    var random =
        new Random(worldSeed ^ region.seed() ^ region.id().hashCode() * 0x9e3779b97f4a7c15L);
    var sites = new ArrayList<Site>();
    long waypointSeed = random.nextLong(), exchangeSeed = random.nextLong();
    if (population == null || population.waypoint())
      sites.add(
          site(
              worldSeed,
              region,
              "waypoint",
              region.name() + " Waypoint Station",
              waypointSeed,
              "nullspace:waypoint:" + region.id()));
    if (population == null || population.exchange())
      sites.add(
          site(
              worldSeed,
              region,
              "exchange",
              region.name() + " Manufacturer Exchange",
              exchangeSeed,
              "nullspace:manufacturer-showcase:v1:" + region.id()));
    int ships = 1 + random.nextInt(3);
    if (population != null) ships = population.salvage();
    for (int i = 0; i < ships; i++) {
      long seed = random.nextLong();
      int kind = salvageKind(seed);
      String design = EncounterSites.design(kind, seed);
      sites.add(
          site(
              worldSeed,
              region,
              "salvage-" + i,
              com.nullandkale.nullspace.shipyard.ShipBlueprints.get(design).name()
                  + (kind == 1 ? " - abandoned freighter" : " - silent survey ship"),
              seed,
              "nullspace:salvage:" + region.id() + ":" + i));
    }
    return List.copyOf(sites);
  }

  public static int salvageKind(long seed) {
    return (int) Math.floorMod(seed >>> 12, 2) + 1;
  }

  private static Site site(
      long worldSeed,
      LocalSpaces.Destination region,
      String role,
      String name,
      long seed,
      String legacy) {
    return new Site(
        role,
        uuid("nullspace:population:v1:" + worldSeed + ":" + region.id() + ":" + role),
        name,
        seed,
        uuid(legacy));
  }

  private static String uuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private SpacePopulationPlan() {}
}
