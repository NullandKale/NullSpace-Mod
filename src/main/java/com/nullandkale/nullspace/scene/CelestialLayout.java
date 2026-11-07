package com.nullandkale.nullspace.scene;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Default companion spacing, shared by orbital views, surface skies and server geometry. */
public final class CelestialLayout {
  public static final double MOON_DIAMETER = Math.toRadians(2);

  public static Vec3 companionPosition(
      Vec3 primary, double primaryRadius, Vec3 companion, double radius) {
    var offset = companion.subtract(primary);
    double minimum = primaryRadius + radius / Math.sin(MOON_DIAMETER / 2);
    return offset.length() >= minimum
        ? companion
        : primary.add(
            offset.lengthSqr() < 1e-12
                ? new Vec3(minimum, 0, 0)
                : offset.normalize().scale(minimum));
  }

  /** New generated definitions inherit material/identity, then receive their own spacing. */
  public static void arrange(CompoundTag catalog, String primaryName) {
    var primary = primary(catalog, primaryName);
    if (primary != null)
      for (var tag : catalog.getListOrEmpty("bodies")) {
        var body = (CompoundTag) tag;
        if (body != primary && body.getStringOr("kind", "").equals("moon")) move(primary, body);
      }
    catalog.putInt("layoutVersion", 1);
  }

  private static CompoundTag primary(CompoundTag catalog, String name) {
    return catalog.getListOrEmpty("bodies").stream()
        .map(t -> (CompoundTag) t)
        .filter(t -> t.getStringOr("name", "").equals(name))
        .findFirst()
        .orElse(null);
  }

  private static Vec3 position(CompoundTag body) {
    return new Vec3(body.getDoubleOr("x", 0), body.getDoubleOr("y", 0), body.getDoubleOr("z", 0));
  }

  private static void move(CompoundTag primary, CompoundTag body) {
    var next =
        companionPosition(
            position(primary),
            primary.getDoubleOr("radius", 0),
            position(body),
            body.getDoubleOr("radius", 0));
    body.putDouble("x", next.x);
    body.putDouble("y", next.y);
    body.putDouble("z", next.z);
  }

  private CelestialLayout() {}
}
