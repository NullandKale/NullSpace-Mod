package com.nullandkale.nullspace.scene;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class ScenePoseTest {
  @Test
  void rotatedLargePositionRoundTripPreservesLocalCoordinates() {
    var p = new ScenePose(new Vec3(1e9, -2e9, 3e9), new Quaterniond().rotationYXZ(.73, .22, -.4));
    var local = new Vec3(15.5, 24.2, -61.1);
    assertTrue(p.toLocal(p.toUniverse(local)).distanceTo(local) < 1e-5);
  }

  @Test
  void poseDoesNotExposeMutableQuaternion() {
    var q = new Quaterniond();
    var p = new ScenePose(Vec3.ZERO, q);
    q.rotateX(2);
    p.orientation().rotateY(3);
    assertEquals(new Vec3(0, 0, 1), p.toUniverse(new Vec3(0, 0, 1)));
  }

  @Test
  void hitMappingIgnoresPhysicalStorageDistance() {
    var pose = new ScenePose(new Vec3(10, 0, 0), new Quaterniond().rotateY(Math.PI / 2));
    var storage = new Vec3(1000000, 96, 0);
    var hit = pose.toLocal(new Vec3(15, 0, 0)).add(storage);
    assertTrue(hit.distanceTo(new Vec3(1000000, 96, 5)) < 1e-8);
  }
}
