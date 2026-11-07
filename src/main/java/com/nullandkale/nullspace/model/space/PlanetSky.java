package com.nullandkale.nullspace.model.space;

import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** A flat surface is a tangent frame on its named body's rotating celestial reference. */
public final class PlanetSky {
  public static boolean decorate(CompoundTag scene, ServerLevel level, BlockPos origin) {
    String address = PlanetSurfaces.address(level);
    if (address == null) return false;
    var catalog = SceneCatalog.region(level.getServer(), address);
    String name = LocalSpaces.get(level.getServer(), address).name();
    var body =
        catalog.getListOrEmpty("bodies").stream()
            .map(t -> (CompoundTag) t)
            .filter(b -> b.getStringOr("name", "").equals(name))
            .findFirst()
            .orElse(null);
    if (body == null) return false;
    var sun =
        catalog.getListOrEmpty("bodies").stream()
            .map(t -> (CompoundTag) t)
            .filter(b -> b.getBooleanOr("emissive", false))
            .findFirst()
            .orElse(new CompoundTag());
    var center =
        new Vec3(body.getDoubleOr("x", 0), body.getDoubleOr("y", 0), body.getDoubleOr("z", 0));
    var toSun =
        new Vector3d(
                sun.getDoubleOr("x", -12000) - center.x,
                sun.getDoubleOr("y", 6000) - center.y,
                sun.getDoubleOr("z", -18000) - center.z)
            .normalize();
    double angle =
        Math.toRadians(
            level
                .environmentAttributes()
                .getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE));
    var rotation = orientation(angle, new Vec3(toSun.x, toSun.y, toSun.z));
    var radial = rotation.transform(new Vector3d(0, body.getDoubleOr("radius", 2200) + .05, 0));
    var surface = new ScenePose(center.add(radial.x, radial.y, radial.z), rotation);
    var position = surface.toUniverse(new Vec3(origin.getX(), origin.getY() - 65, origin.getZ()));
    var euler = rotation.getEulerAnglesYXZ(new Vector3d());
    for (var t : scene.getListOrEmpty("subjects")) {
      var subject = (CompoundTag) t;
      if (!subject.getStringOr("id", "").equals(scene.getStringOr("observer", ""))) continue;
      subject.putDouble("x", position.x);
      subject.putDouble("y", position.y);
      subject.putDouble("z", position.z);
      subject.putDouble("yaw", Math.toDegrees(euler.y));
      subject.putDouble("pitch", Math.toDegrees(euler.x));
      subject.putDouble("roll", Math.toDegrees(euler.z));
    }
    scene.putBoolean("universe", true);
    scene.putBoolean("planetSky", true);
    scene.putDouble("surfaceRadius", body.getDoubleOr("radius", 2200));
    scene.putDouble("surfaceCenterX", center.x);
    scene.putDouble("surfaceCenterY", center.y);
    scene.putDouble("surfaceCenterZ", center.z);
    double atmosphere = body.getDoubleOr("atmosphere", 0);
    scene.putBoolean("surfaceAtmosphere", atmosphere > 0);
    if (atmosphere > 0) {
      scene.put(
          "atmosphereProfile",
          body.contains("optics")
              ? AtmosphereProfile.decode(body.getCompoundOrEmpty("optics")).encode()
              : AtmosphereProfile.temperate(body.getDoubleOr("radius", 2200), atmosphere).encode());
      scene.putDouble("atmosphereX", center.x);
      scene.putDouble("atmosphereY", center.y);
      scene.putDouble("atmosphereZ", center.z);
    }
    scene.putString("region", address);
    scene.putString("surfaceBody", body.getStringOr("id", ""));
    return true;
  }

  public static Quaterniond orientation(double angle, Vec3 sunDirection) {
    if (!Double.isFinite(angle) || sunDirection.lengthSqr() < 1e-12)
      throw new IllegalArgumentException("Invalid surface frame");
    return new Quaterniond()
        .rotationTo(
            new Vector3d(0, 1, 0),
            new Vector3d(sunDirection.x, sunDirection.y, sunDirection.z).normalize())
        .rotateZ(-angle);
  }

  /** Flat terrain does not climb out of the atmosphere when the player walks east or north. */
  public record SurfaceView(
      Vec3 celestialEye,
      Vec3 center,
      Vec3 cloudOffset,
      Quaterniond cloudRotation,
      double distanceScale) {}

  public static SurfaceView surfaceView(
      CompoundTag scene, CompoundTag observer, Vec3 eye, AtmosphereProfile atmosphere) {
    var q =
        new Quaterniond()
            .rotationYXZ(
                Math.toRadians(observer.getDoubleOr("yaw", 0)),
                Math.toRadians(observer.getDoubleOr("pitch", 0)),
                Math.toRadians(observer.getDoubleOr("roll", 0)));
    var pose =
        new ScenePose(
            new Vec3(
                observer.getDoubleOr("x", 0),
                observer.getDoubleOr("y", 0),
                observer.getDoubleOr("z", 0)),
            q);
    var base = BlockPos.of(observer.getLongOr("base", 0));
    var nativeEye = pose.toLocal(eye).add(base.getX(), base.getY(), base.getZ());
    double scale = atmosphere == null ? 1 : atmosphere.height() / 512.;
    double radius =
        scene.getDoubleOr("surfaceRadius", atmosphere == null ? 2200 : atmosphere.radius());
    var radial =
        q.transform(new Vector3d(0, radius + .05 + Math.max(0, nativeEye.y - 65) * scale, 0));
    var center =
        new Vec3(
            scene.getDoubleOr("surfaceCenterX", 0),
            scene.getDoubleOr("surfaceCenterY", 0),
            scene.getDoubleOr("surfaceCenterZ", 0));
    var offset = new Vec3(radial.x, radial.y, radial.z);
    return new SurfaceView(
        center.add(offset),
        offset.scale(-1),
        new Vec3(nativeEye.x * scale, 0, nativeEye.z * scale),
        q.conjugate(),
        scale);
  }

  public static CompoundTag skyCatalog(CompoundTag catalog, String standingOn) {
    var copy = catalog.copy();
    copy.getListOrEmpty("bodies")
        .removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(standingOn));
    return copy;
  }

  private PlanetSky() {}
}
