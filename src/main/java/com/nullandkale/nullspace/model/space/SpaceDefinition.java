package com.nullandkale.nullspace.model.space;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Immutable, bounded scene contract. Coordinates are simulation units, never storage chunks. */
public record SpaceDefinition(
    String id,
    long revision,
    long seed,
    int generator,
    double epoch,
    List<Body> bodies,
    List<Volume> volumes,
    List<Field> fields) {
  public static final int MAX_BODIES = 64, MAX_VOLUMES = 16, MAX_FIELDS = 8, MAX_INSTANCES = 2048;

  public record Body(
      String id,
      int kind,
      Vec3 center,
      Vec3 velocity,
      double radius,
      int color,
      double emission,
      double roughness,
      long seed,
      double atmosphere,
      Vec3 rotationAxis,
      double spin,
      AtmosphereProfile optics) {
    public Body(
        String id,
        int kind,
        Vec3 center,
        Vec3 velocity,
        double radius,
        int color,
        double emission,
        double roughness,
        long seed,
        double atmosphere,
        Vec3 rotationAxis,
        double spin) {
      this(
          id,
          kind,
          center,
          velocity,
          radius,
          color,
          emission,
          roughness,
          seed,
          atmosphere,
          rotationAxis,
          spin,
          null);
    }

    public Body(
        String id,
        int kind,
        Vec3 center,
        Vec3 velocity,
        double radius,
        int color,
        double emission,
        double roughness,
        long seed,
        double atmosphere) {
      this(
          id,
          kind,
          center,
          velocity,
          radius,
          color,
          emission,
          roughness,
          seed,
          atmosphere,
          new Vec3(0, 1, 0),
          0);
    }

    public Body {
      identifier(id);
      vector(center);
      vector(velocity);
      vector(rotationAxis);
      if (rotationAxis.lengthSqr() < 1e-12) rotationAxis = new Vec3(0, 1, 0);
      range(spin, -10, 10);
      positive(radius, 1e8);
      range(emission, 0, 100);
      range(roughness, 0, .3);
      range(atmosphere, 0, 1);
      if (optics != null
          && (atmosphere <= 0
              || Math.abs(optics.radius() - radius) > Math.max(.00001, radius * 1e-8)
              || Math.abs(optics.height() - radius * atmosphere) > Math.max(.00001, radius * 1e-8)))
        throw new IllegalArgumentException("Optical atmosphere must match its celestial body");
      if (kind < 0 || kind > 4) throw new IllegalArgumentException("Unknown body kind");
    }

    public Vec3 at(double time) {
      return center.add(velocity.scale(time));
    }

    public AtmosphereProfile atmosphereProfile() {
      return atmosphere <= 0
          ? null
          : optics != null ? optics : AtmosphereProfile.temperate(radius, atmosphere);
    }
  }

  public record Volume(
      String id,
      Vec3 center,
      Vec3 extent,
      Vec3 velocity,
      int color,
      double density,
      double extinction,
      double emission,
      double phase,
      long seed,
      int kind) {
    public Volume {
      identifier(id);
      vector(center);
      vector(extent);
      vector(velocity);
      positive(extent.x, 1e8);
      positive(extent.y, 1e8);
      positive(extent.z, 1e8);
      range(density, 0, 1);
      range(extinction, 0, 10);
      range(emission, 0, 100);
      range(phase, -.95, .95);
      if (kind < 0 || kind > 2) throw new IllegalArgumentException("Unknown volume kind");
    }
  }

  public record Field(
      String id,
      Vec3 center,
      Vec3 extent,
      double cell,
      double density,
      double minRadius,
      double maxRadius,
      long seed,
      int color) {
    public Field {
      identifier(id);
      vector(center);
      vector(extent);
      positive(extent.x, 1e8);
      positive(extent.y, 1e8);
      positive(extent.z, 1e8);
      positive(cell, 1e7);
      range(density, 0, 1);
      positive(minRadius, cell * .4);
      range(maxRadius, minRadius, cell * .4);
      double count =
          Math.ceil(extent.x * 2 / cell)
              * Math.ceil(extent.y * 2 / cell)
              * Math.ceil(extent.z * 2 / cell);
      if (count > 8192) throw new IllegalArgumentException("Field exceeds cell budget");
    }
  }

  public SpaceDefinition {
    identifier(id);
    if (revision < 0 || generator != 1 || !Double.isFinite(epoch))
      throw new IllegalArgumentException("Invalid scene version/time");
    bodies = List.copyOf(bodies);
    volumes = List.copyOf(volumes);
    fields = List.copyOf(fields);
    if (volumes.size()
                + bodies.stream().filter(b -> b.atmosphere > 0).count()
                + bodies.stream().filter(b -> b.kind == 4).count()
            > MAX_VOLUMES
        || bodies.size() > MAX_BODIES
        || volumes.size() > MAX_VOLUMES
        || fields.size() > MAX_FIELDS
        || bodies.stream().filter(b -> b.emission > 0).count() > 8)
      throw new IllegalArgumentException("Scene exceeds budget");
    var ids = new HashSet<String>();
    for (var b : bodies)
      if (!ids.add(b.id)) throw new IllegalArgumentException("Duplicate scene identity");
    for (var v : volumes)
      if (!ids.add(v.id)) throw new IllegalArgumentException("Duplicate scene identity");
    for (var f : fields)
      if (!ids.add(f.id)) throw new IllegalArgumentException("Duplicate scene identity");
  }

  private static void identifier(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,80}"))
      throw new IllegalArgumentException("Invalid scene identity");
  }

  private static void vector(Vec3 v) {
    if (v == null) throw new IllegalArgumentException("Missing vector");
    range(v.x, -1e12, 1e12);
    range(v.y, -1e12, 1e12);
    range(v.z, -1e12, 1e12);
  }

  private static void positive(double n, double max) {
    if (!Double.isFinite(n) || n <= 0 || n > max)
      throw new IllegalArgumentException("Invalid scene size");
  }

  private static void range(double n, double min, double max) {
    if (!Double.isFinite(n) || n < min || n > max)
      throw new IllegalArgumentException("Invalid scene parameter");
  }

  /**
   * Canonical cells, independent of camera and iteration order. Overflow is explicit, never
   * truncated.
   */
  public List<Body> instances() {
    var result = new ArrayList<Body>(bodies);
    for (var f : fields) {
      int nx = (int) Math.ceil(f.extent.x * 2 / f.cell),
          ny = (int) Math.ceil(f.extent.y * 2 / f.cell),
          nz = (int) Math.ceil(f.extent.z * 2 / f.cell);
      for (int z = 0; z < nz; z++)
        for (int y = 0; y < ny; y++)
          for (int x = 0; x < nx; x++) {
            long cellSeed =
                mix(f.seed ^ ((long) x * 73856093) ^ ((long) y * 19349663) ^ ((long) z * 83492791));
            var random = new SplittableRandom(cellSeed);
            if (random.nextDouble() >= f.density) continue;
            if (result.size() >= MAX_INSTANCES)
              throw new IllegalArgumentException("Asteroid field exceeds instance budget");
            var low = f.center.subtract(f.extent).add(x * f.cell, y * f.cell, z * f.cell);
            var fieldMax = f.center.add(f.extent);
            var size =
                new Vec3(
                    Math.min(f.cell, fieldMax.x - low.x),
                    Math.min(f.cell, fieldMax.y - low.y),
                    Math.min(f.cell, fieldMax.z - low.z));
            double allowed =
                Math.min(f.maxRadius, Math.min(size.x, Math.min(size.y, size.z)) / (2 * 1.16));
            if (allowed < f.minRadius) continue;
            double radius = random.nextDouble(f.minRadius, Math.nextUp(allowed)),
                outer = radius * 1.16;
            var center =
                low.add(
                    outer + random.nextDouble() * Math.max(0, size.x - 2 * outer),
                    outer + random.nextDouble() * Math.max(0, size.y - 2 * outer),
                    outer + random.nextDouble() * Math.max(0, size.z - 2 * outer));
            result.add(
                new Body(
                    f.id + ":" + x + ":" + y + ":" + z,
                    3,
                    center,
                    Vec3.ZERO,
                    radius,
                    f.color,
                    0,
                    .16,
                    cellSeed,
                    0,
                    new Vec3(
                        random.nextDouble(-1, 1),
                        random.nextDouble(-1, 1),
                        random.nextDouble(-1, 1)),
                    random.nextDouble(-.1, .1)));
          }
    }
    return List.copyOf(result);
  }

  public static long mix(long x) {
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }
}
