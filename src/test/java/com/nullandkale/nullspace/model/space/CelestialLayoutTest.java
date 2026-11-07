package com.nullandkale.nullspace.model.space;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CelestialLayoutTest {
  @Test
  void moonStaysSmallFromEverySurfaceDirection() {
    var planet = new Vec3(0, -2500, 5000);
    var oldMoon = new Vec3(2600, -1000, 4300);
    var moon = CelestialLayout.companionPosition(planet, 2200, oldMoon, 600);
    assertTrue(moon.distanceTo(planet) > 35000);
    assertEquals(
        1, moon.subtract(planet).normalize().dot(oldMoon.subtract(planet).normalize()), 1e-12);
    for (int latitude = -90; latitude <= 90; latitude += 5)
      for (int longitude = 0; longitude < 360; longitude += 5) {
        double p = Math.toRadians(latitude), a = Math.toRadians(longitude);
        var surface =
            planet.add(
                Math.cos(p) * Math.cos(a) * 2200,
                Math.sin(p) * 2200,
                Math.cos(p) * Math.sin(a) * 2200);
        assertTrue(
            2 * Math.asin(600 / moon.distanceTo(surface)) <= CelestialLayout.MOON_DIAMETER + 1e-10);
      }
  }

  @Test
  void distantCompanionsAndRepeatedLayoutStayPut() {
    var far = new Vec3(100000, 50, 30);
    assertEquals(far, CelestialLayout.companionPosition(Vec3.ZERO, 2200, far, 600));
    var next = CelestialLayout.companionPosition(Vec3.ZERO, 2200, new Vec3(2000, 10, 30), 600);
    assertTrue(
        next.distanceTo(CelestialLayout.companionPosition(Vec3.ZERO, 2200, next, 600)) < 1e-10);
  }
}
