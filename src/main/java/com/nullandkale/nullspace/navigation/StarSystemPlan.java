package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.content.ContentCatalog;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** A complete, bounded system grammar; all orbital regions share these body identities. */
public final class StarSystemPlan {
  public static final int VERSION = 1;

  public record Region(
      LocalSpaces.Destination destination, Vec3 center, double radius, double atmosphere) {}

  public record System(
      String id,
      String name,
      long seed,
      String spectralClass,
      int color,
      double radius,
      List<Region> regions) {
    public System {
      regions = List.copyOf(regions);
    }
  }

  public static List<System> generate(long worldSeed) {
    var random = new Random(worldSeed ^ 0x46e7b1d32a9fc805L);
    var prefixes =
        new ArrayList<>(List.of("Al", "Cor", "El", "Ith", "Kel", "Mir", "Nar", "Or", "Tal", "Val"));
    Collections.shuffle(prefixes, random);
    var suffixes = List.of("ara", "ion", "eris", "ora", "ea", "una", "idia", "os");
    var result = new ArrayList<System>();
    int index = 0;
    for (var slot : ContentCatalog.SYSTEM_SLOTS) {
      long seed = random.nextLong();
      var rng = new Random(seed);
      String name = prefixes.get(index++) + suffixes.get(rng.nextInt(suffixes.size()));
      int type = rng.nextInt(3);
      String spectral = List.of("K", "G", "A").get(type);
      int color = new int[] {0xffffb477, 0xffffdca6, 0xffcadfff}[type];
      double starRadius = new double[] {1400, 1800, 2400}[type] + rng.nextInt(400);
      double angle = rng.nextDouble() * Math.PI * 2;
      var inner = orbit(angle, 90000 + rng.nextInt(15000));
      var outer = orbit(angle + 1.3 + rng.nextDouble(), 165000 + rng.nextInt(30000));
      double moonAngle = rng.nextDouble() * Math.PI * 2;
      var centers = List.of(inner, outer, outer.add(orbit(moonAngle, 34000)));
      var regions = new ArrayList<Region>();
      for (int i = 0; i < slot.regions().size(); i++) {
        var surface = slot.regions().get(i);
        boolean moon = surface.moon();
        String bodyName = name + (i == 0 ? " I" : i == 1 ? " II" : " II-a");
        int bodyColor = surface.color();
        // Keep appearance compatible with each allocated surface's terrain and atmosphere.
        int variation = rng.nextInt(17) - 8;
        int red = Math.clamp(((bodyColor >>> 16) & 255) + variation, 0, 255);
        int green = Math.clamp(((bodyColor >>> 8) & 255) + variation, 0, 255);
        int blue = Math.clamp((bodyColor & 255) + variation, 0, 255);
        var destination =
            new LocalSpaces.Destination(
                surface.id(),
                slot.id(),
                name,
                bodyName,
                rng.nextLong(),
                0xff000000 | red << 16 | green << 8 | blue,
                moon);
        regions.add(
            new Region(
                destination,
                centers.get(i),
                moon
                    ? 380 + rng.nextInt(180)
                    : i == 0 ? 1100 + rng.nextInt(400) : 1800 + rng.nextInt(400),
                surface.breathable() ? .04 : 0));
      }
      result.add(new System(slot.id(), name, seed, spectral, color, starRadius, regions));
    }
    return List.copyOf(result);
  }

  private static Vec3 orbit(double angle, double radius) {
    return new Vec3(
        Math.cos(angle) * radius, Math.sin(angle * 2) * radius * .015, Math.sin(angle) * radius);
  }

  private StarSystemPlan() {}
}
