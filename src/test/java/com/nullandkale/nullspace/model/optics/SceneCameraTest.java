package com.nullandkale.nullspace.model.optics;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SceneCameraTest {
  private static Matrix4f projection(boolean zeroOne) {
    return new Matrix4f().perspective((float) Math.toRadians(73), 713f / 413, 1024, .05f, zeroOne);
  }

  private static Vector3f ray(Matrix4f projection) {
    var inverse = new Matrix4f(projection).invert();
    var near = inverse.transformProject(new Vector3f(0, 0, 1));
    var far = inverse.transformProject(new Vector3f(0, 0, .5f));
    return far.sub(near).normalize();
  }

  @Test
  void walkingBobMovesEyeWithoutTurningIntoAnArtificialRotation() {
    var p = projection(true);
    var view = new Matrix4f().rotationY((float) Math.PI);
    var world = new Matrix4f(p).translate(.18f, -.12f, 0);
    var oldProjection = new Matrix4f(p).mul(new Matrix4f(p).invert().mul(world)).mul(view);
    var oldDirection =
        new Matrix4f(oldProjection).invert().transformProject(new Vector3f(0, 0, 1)).normalize();
    var expected = ray(new Matrix4f(world).mul(view));
    assertTrue(
        oldDirection.dot(expected) < .5f, "Fixture must reproduce the severe near-plane bob error");
    var aligned = SceneCamera.compose(p, world, view, new Quaterniond(), true);
    var direction =
        new Matrix4f(aligned.projection())
            .invert()
            .transformProject(new Vector3f(0, 0, 1))
            .normalize();
    assertTrue(direction.distance(expected) < 1e-5);
    assertEquals(.18f, aligned.eyeOffset().x, 1e-5);
    assertEquals(.12f, aligned.eyeOffset().y, 1e-5);
  }

  @Test
  void sceneAndHullStayAlignedThroughBobHurtPortalEffectsAndShipRotation() {
    for (boolean zeroOne : new boolean[] {false, true})
      for (int phase = 0; phase < 48; phase++) {
        float a = phase * .17f;
        var p = projection(zeroOne).m20(.035f).m21(-.02f);
        var effects =
            new Matrix4f()
                .translate((float) Math.sin(a) * .2f, -Math.abs((float) Math.cos(a)) * .15f, 0)
                .rotateZ((float) Math.sin(a) * .1f)
                .rotateX(.06f)
                .rotate(a, new Vector3f(0, 1, 1).normalize())
                .scale(1.06f, 1, 1)
                .rotate(-a, new Vector3f(0, 1, 1).normalize());
        var view = new Matrix4f().rotationYXZ(a, .3f, -.1f);
        var ship = new Quaterniond().rotationYXZ(-.8, .4, .6);
        var world = new Matrix4f(p).mul(effects);
        var aligned = SceneCamera.compose(p, world, view, ship, zeroOne);
        var localPoint = new Matrix4f(view).invert().transformPosition(new Vector3f(4, 2, -90));
        var expected = new Matrix4f(world).mul(view).transformProject(new Vector3f(localPoint));
        var relative = new Vector3f(localPoint).sub(aligned.eyeOffset());
        new Quaternionf(ship).transform(relative);
        var nativePoint = aligned.nativeProjection().transformProject(new Vector3f(relative));
        var distantPoint = aligned.projection().transformProject(new Vector3f(relative));
        assertTrue(
            expected.distance(nativePoint) < .0002,
            "Native depth must use the shifted eye too: phase="
                + phase
                + " zeroOne="
                + zeroOne
                + " expected="
                + expected
                + " actual="
                + nativePoint);
        assertEquals(expected.x, distantPoint.x, .0002);
        assertEquals(expected.y, distantPoint.y, .0002);
        var expectedDirection = ray(new Matrix4f(world).mul(view));
        new Quaternionf(ship).transform(expectedDirection);
        var actualDirection =
            new Matrix4f(aligned.projection())
                .invert()
                .transformProject(new Vector3f(0, 0, 1))
                .normalize();
        assertTrue(
            expectedDirection.distance(actualDirection) < .0001,
            "Compute rays must match native camera rays");
      }
  }

  @Test
  void noBobPreservesEyeAndInputsAreNeverMutated() {
    var p = projection(true);
    var before = new Matrix4f(p);
    var view = new Matrix4f().rotationYXZ(.7f, -.3f, 0);
    var viewBefore = new Matrix4f(view);
    var ship = new Quaterniond().rotationYXZ(.3, .2, .1);
    var shipBefore = new Quaterniond(ship);
    var aligned = SceneCamera.compose(p, p, view, ship, true);
    assertTrue(aligned.eyeOffset().length() < 1e-6);
    assertEquals(before, p);
    assertEquals(viewBefore, view);
    assertEquals(shipBefore, ship);
  }
}
