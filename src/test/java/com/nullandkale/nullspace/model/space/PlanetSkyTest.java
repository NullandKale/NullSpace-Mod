package com.nullandkale.nullspace.model.space;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetSkyTest {
  private static net.minecraft.nbt.CompoundTag flatFrame() {
    var scene = new net.minecraft.nbt.CompoundTag();
    scene.putDouble("surfaceRadius", 2200);
    scene.putDouble("surfaceCenterY", -2200.05);
    return scene;
  }

  @Test
  void walkingAcrossFlatTerrainDoesNotClimbIntoSpace() {
    var observer = new net.minecraft.nbt.CompoundTag();
    observer.putLong("base", new net.minecraft.core.BlockPos(0, 65, 0).asLong());
    var profile = AtmosphereProfile.temperate(2200, .04);
    var origin = PlanetSky.surfaceView(flatFrame(), observer, new Vec3(0, 1.62, 0), profile);
    for (var eye :
        java.util.List.of(
            new Vec3(886.242943, 1.62, -81.684514),
            new Vec3(30000000, 1.62, -30000000),
            new Vec3(-4000, 1.62, 8000))) {
      var remote = PlanetSky.surfaceView(flatFrame(), observer, eye, profile);
      assertTrue(remote.center().distanceTo(origin.center()) < 1e-8);
      assertTrue(remote.celestialEye().distanceTo(origin.celestialEye()) < 1e-8);
      assertTrue(remote.center().length() < profile.radius() + profile.height());
      assertTrue(remote.cloudOffset().distanceTo(origin.cloudOffset()) > 0);
    }
  }

  @Test
  void surfaceHeightAndNativeDistanceUseTheSameOpticalScale() {
    var observer = new net.minecraft.nbt.CompoundTag();
    observer.putLong("base", new net.minecraft.core.BlockPos(0, 65, 0).asLong());
    var profile = AtmosphereProfile.temperate(2200, .04);
    var low = PlanetSky.surfaceView(flatFrame(), observer, Vec3.ZERO, profile);
    var high = PlanetSky.surfaceView(flatFrame(), observer, new Vec3(886, 255, -82), profile);
    assertEquals(88. / 512, low.distanceScale(), 1e-12);
    assertEquals(255 * low.distanceScale(), high.center().length() - low.center().length(), 1e-8);
    assertTrue(high.center().length() < profile.radius() + profile.height() * .5);
  }

  @Test
  void cameraBlockOriginsDoNotChangeTheViewAndCloudsFollowTheGroundFrame() {
    var scene = flatFrame();
    var profile = AtmosphereProfile.temperate(2200, .04);
    var q = new org.joml.Quaterniond().rotationYXZ(.6, .3, -.2);
    var observer = new net.minecraft.nbt.CompoundTag();
    var base = new net.minecraft.core.BlockPos(886, 90, -82);
    observer.putLong("base", base.asLong());
    observer.putDouble("yaw", Math.toDegrees(.6));
    observer.putDouble("pitch", Math.toDegrees(.3));
    observer.putDouble("roll", Math.toDegrees(-.2));
    var pose = new ScenePose(new Vec3(100, 300, 700), q);
    observer.putDouble("x", 100);
    observer.putDouble("y", 300);
    observer.putDouble("z", 700);
    var view =
        PlanetSky.surfaceView(scene, observer, pose.toUniverse(new Vec3(.5, 1.62, .5)), profile);
    var cloud = view.cloudRotation().transform(q.transform(new Vector3d(3, 4, 5)));
    assertTrue(cloud.distance(new Vector3d(3, 4, 5)) < 1e-9);
    assertEquals((886.5) * view.distanceScale(), view.cloudOffset().x, 1e-8);
    assertEquals(
        profile.radius() + .05 + (91.62 - 65) * view.distanceScale(), view.center().length(), 1e-8);
  }

  @Test
  void nativeSunDirectionMatchesCelestialSunThroughoutTheDay() {
    var sun = new Vec3(-12000, 6000, -18000).normalize();
    for (int i = 0; i < 96; i++) {
      double angle = i * Math.PI / 48;
      var q = PlanetSky.orientation(angle, sun);
      var localSun = new Vector3d(-Math.sin(angle), Math.cos(angle), 0);
      q.transform(localSun);
      assertTrue(localSun.distance(new Vector3d(sun.x, sun.y, sun.z)) < 1e-10);
    }
  }

  @Test
  void SurfaceNormalRotatesFromDayToNight() {
    var sun = new Vec3(1, 0, 0);
    var noon = PlanetSky.orientation(0, sun).transform(new Vector3d(0, 1, 0));
    var midnight = PlanetSky.orientation(Math.PI, sun).transform(new Vector3d(0, 1, 0));
    assertEquals(-1, noon.dot(midnight), 1e-10);
  }
}
