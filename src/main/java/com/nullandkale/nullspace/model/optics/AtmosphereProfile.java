package com.nullandkale.nullspace.model.optics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Optical coefficients are per scene unit; one atmosphere height represents 100 km. */
public record AtmosphereProfile(
    double radius,
    double height,
    Vec3 rayleigh,
    double mie,
    Vec3 absorption,
    double rayleighHeight,
    double mieHeight,
    double albedo,
    double clouds) {
  public AtmosphereProfile {
    if (!Double.isFinite(radius)
        || radius < 1
        || radius > 1e8
        || !Double.isFinite(height)
        || height < .001
        || height > radius
        || !positive(rayleigh)
        || !positive(absorption)
        || !Double.isFinite(mie)
        || mie < 0
        || mie > 1e8
        || !Double.isFinite(rayleighHeight)
        || rayleighHeight <= 0
        || rayleighHeight > height
        || !Double.isFinite(mieHeight)
        || mieHeight <= 0
        || mieHeight > height
        || !Double.isFinite(albedo)
        || albedo < 0
        || albedo > .95
        || !Double.isFinite(clouds)
        || clouds < 0
        || clouds > 1) throw new IllegalArgumentException("Invalid planetary atmosphere");
  }

  private static boolean positive(Vec3 v) {
    return v != null
        && Double.isFinite(v.x)
        && Double.isFinite(v.y)
        && Double.isFinite(v.z)
        && v.x >= 0
        && v.y >= 0
        && v.z >= 0
        && v.x <= 1e8
        && v.y <= 1e8
        && v.z <= 1e8;
  }

  public static AtmosphereProfile temperate(double radius, double fraction) {
    double h = Math.max(.001, radius * Math.clamp(fraction, .00001, 1));
    double km = 100 / h;
    return new AtmosphereProfile(
        radius,
        h,
        new Vec3(.005802, .013558, .033100).scale(km),
        .003996 * km,
        new Vec3(.000650, .001881, .000085).scale(km),
        h * .08,
        h * .012,
        .18,
        .38);
  }

  public CompoundTag encode() {
    var t = new CompoundTag();
    t.putDouble("radius", radius);
    t.putDouble("height", height);
    put(t, "rayleigh", rayleigh);
    put(t, "absorption", absorption);
    t.putDouble("mie", mie);
    t.putDouble("rayleighHeight", rayleighHeight);
    t.putDouble("mieHeight", mieHeight);
    t.putDouble("albedo", albedo);
    t.putDouble("clouds", clouds);
    return t;
  }

  private static void put(CompoundTag t, String key, Vec3 v) {
    t.putDouble(key + "R", v.x);
    t.putDouble(key + "G", v.y);
    t.putDouble(key + "B", v.z);
  }

  private static Vec3 get(CompoundTag t, String key) {
    return new Vec3(
        t.getDoubleOr(key + "R", Double.NaN),
        t.getDoubleOr(key + "G", Double.NaN),
        t.getDoubleOr(key + "B", Double.NaN));
  }

  public static AtmosphereProfile decode(CompoundTag t) {
    return new AtmosphereProfile(
        t.getDoubleOr("radius", 0),
        t.getDoubleOr("height", 0),
        get(t, "rayleigh"),
        t.getDoubleOr("mie", Double.NaN),
        get(t, "absorption"),
        t.getDoubleOr("rayleighHeight", 0),
        t.getDoubleOr("mieHeight", 0),
        t.getDoubleOr("albedo", Double.NaN),
        t.getDoubleOr("clouds", Double.NaN));
  }

  public Vec3 extinction(double altitude) {
    double h = Math.max(0, altitude);
    if (h >= height) return Vec3.ZERO;
    double ozone = Math.max(0, 1 - Math.abs(h - height * .25) / (height * .15));
    return rayleigh
        .scale(Math.exp(-h / rayleighHeight))
        .add(new Vec3(1, 1, 1).scale(mie / .9 * Math.exp(-h / mieHeight)))
        .add(absorption.scale(ozone));
  }

  /** Independent double-precision reference for GPU transmittance-table regressions. */
  public Vec3 transmittance(double altitude, double cosine, int samples) {
    if (!Double.isFinite(altitude)
        || altitude < 0
        || altitude > height
        || !Double.isFinite(cosine)
        || cosine < -1
        || cosine > 1
        || samples < 1
        || samples > 65536) throw new IllegalArgumentException("Invalid optical ray");
    double r = radius + altitude;
    double ground = r * r * (cosine * cosine - 1) + radius * radius;
    if (cosine < 0 && ground > 0) return Vec3.ZERO;
    double top = radius + height;
    double distance = -r * cosine + Math.sqrt(r * r * (cosine * cosine - 1) + top * top);
    double ds = distance / samples;
    var optical = Vec3.ZERO;
    for (int i = 0; i < samples; i++) {
      double t = (i + .5) * ds;
      optical =
          optical.add(extinction(Math.sqrt(r * r + t * t + 2 * r * t * cosine) - radius).scale(ds));
    }
    return new Vec3(Math.exp(-optical.x), Math.exp(-optical.y), Math.exp(-optical.z));
  }
}
