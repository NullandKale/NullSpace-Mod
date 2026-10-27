package com.nullandkale.nullspace.scene;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpaceDefinitionTest {
  @Test
  void optionalPlanetOpticsRoundTripAndOldDefinitionsKeepTheirDefaults() {
    var profile = AtmosphereProfile.temperate(2200, .04);
    var planet =
        new SpaceDefinition.Body(
            "planet",
            1,
            Vec3.ZERO,
            Vec3.ZERO,
            2200,
            -1,
            0,
            0,
            4,
            .04,
            new Vec3(0, 1, 0),
            0,
            profile);
    var definition = scene(List.of(planet));
    assertEquals(definition, SpaceDefinitionCodec.decode(SpaceDefinitionCodec.encode(definition)));
    assertEquals(profile, planet.atmosphereProfile());
    var old = SpaceDefinitionCodec.encode(definition);
    old.getListOrEmpty("bodies").getCompound(0).orElseThrow().remove("optics");
    assertEquals(profile, SpaceDefinitionCodec.decode(old).bodies().getFirst().atmosphereProfile());
    assertNull(body("moon", Vec3.ZERO, 10).atmosphereProfile());
  }

  @Test
  void mismatchedOrMalformedOpticsCannotCrossTheWire() {
    var planet =
        new SpaceDefinition.Body("planet", 1, Vec3.ZERO, Vec3.ZERO, 2200, -1, 0, 0, 4, .04);
    var encoded = SpaceDefinitionCodec.encode(scene(List.of(planet)));
    var body = encoded.getListOrEmpty("bodies").getCompound(0).orElseThrow();
    body.put("optics", AtmosphereProfile.temperate(1100, .04).encode());
    assertThrows(IllegalArgumentException.class, () -> SpaceDefinitionCodec.decode(encoded));
    body.put("optics", new net.minecraft.nbt.CompoundTag());
    assertThrows(IllegalArgumentException.class, () -> SpaceDefinitionCodec.decode(encoded));
  }

  static SpaceDefinition.Body body(String id, Vec3 center, double radius) {
    return new SpaceDefinition.Body(id, 2, center, Vec3.ZERO, radius, 0xff888888, 0, 0, 4, 0);
  }

  static SpaceDefinition scene(List<SpaceDefinition.Body> bodies) {
    return new SpaceDefinition("test:space", 1, 42, 1, 0, bodies, List.of(), List.of());
  }

  @Test
  void validatesVersionCountsIdsAndFiniteNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> scene(List.of(body("same", Vec3.ZERO, 1), body("same", Vec3.ZERO, 2))));
    assertThrows(IllegalArgumentException.class, () -> body("bad", new Vec3(Double.NaN, 0, 0), 1));
    assertThrows(IllegalArgumentException.class, () -> body("bad", Vec3.ZERO, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpaceDefinition("test:space", 0, 0, 2, 0, List.of(), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scene(
                java.util.stream.IntStream.range(0, 65)
                    .mapToObj(i -> body("b" + i, Vec3.ZERO, 1))
                    .toList()));
  }

  @Test
  void definitionsAndInstancesAreImmutable() {
    var list = new ArrayList<SpaceDefinition.Body>();
    list.add(body("a", Vec3.ZERO, 1));
    var s = scene(list);
    list.clear();
    assertEquals(1, s.bodies().size());
    assertThrows(UnsupportedOperationException.class, () -> s.bodies().clear());
    assertThrows(UnsupportedOperationException.class, () -> s.instances().clear());
  }

  @Test
  void seededFieldsAreStableAcrossViewsAndConstruction() {
    var f =
        new SpaceDefinition.Field(
            "test:belt", new Vec3(100, 0, 100), new Vec3(64, 64, 64), 32, .5, 1, 4, 23, 0xff777777);
    var a = new SpaceDefinition("test:space", 1, 0, 1, 0, List.of(), List.of(), List.of(f));
    var b = new SpaceDefinition("test:space", 1, 0, 1, 0, List.of(), List.of(), List.of(f));
    assertEquals(a.instances(), b.instances());
    assertTrue(a.instances().size() > 10);
    assertEquals(
        a.instances().size(),
        a.instances().stream().map(SpaceDefinition.Body::id).distinct().count());
  }

  @Test
  void largeFieldsFailExplicitlyInsteadOfDroppingOccluders() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpaceDefinition.Field(
                "bad", Vec3.ZERO, new Vec3(1000, 1000, 1000), 1, 1, .1, .2, 0, 0));
    var f =
        new SpaceDefinition.Field("full", Vec3.ZERO, new Vec3(256, 128, 128), 16, 1, 1, 2, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpaceDefinition("s", 0, 0, 1, 0, List.of(), List.of(), List.of(f)).instances());
  }

  @Test
  void std430BvhLayoutAndRelativePrecision() {
    var center = new Vec3(1e11, 1e11, 1e11);
    var bvh = new SpaceBvh(List.of(body("a", center.add(10, 0, 0), 2)), 0);
    var bytes = bvh.pack(center);
    assertEquals(32, bytes.remaining());
    assertEquals(8, bytes.getFloat(0));
    assertEquals(-2, bytes.getFloat(4));
    assertEquals(-1, bytes.getInt(12));
    assertEquals(12, bytes.getFloat(16));
    assertEquals(-1, bytes.getInt(28));
  }

  @Test
  void analyticInsideAndGrazingRays() {
    assertEquals(2, SpaceBvh.sphere(Vec3.ZERO, new Vec3(1, 0, 0), Vec3.ZERO, 2), 1e-9);
    assertEquals(5, SpaceBvh.sphere(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(1, 0, 5), 1), 1e-9);
    assertEquals(
        Double.POSITIVE_INFINITY,
        SpaceBvh.sphere(Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(0, 0, 5), 1));
    assertTrue(
        SpaceBvh.box(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(-1, -1, -1), new Vec3(1, 1, 1), 10));
    assertFalse(
        SpaceBvh.box(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(2, -1, -1), new Vec3(3, 1, 1), 10));
  }

  @Test
  void hierarchyMatchesBruteForceForThousandsOfRandomRays() {
    var r = new Random(6781);
    var bodies = new ArrayList<SpaceDefinition.Body>();
    for (int i = 0; i < 200; i++)
      bodies.add(
          body(
              "b" + i,
              new Vec3(
                  r.nextDouble() * 200 - 100,
                  r.nextDouble() * 200 - 100,
                  r.nextDouble() * 200 - 100),
              1 + r.nextDouble() * 8));
    var bvh = new SpaceBvh(bodies, 0);
    for (int i = 0; i < 3000; i++) {
      var origin =
          new Vec3(r.nextDouble() * 80 - 40, r.nextDouble() * 80 - 40, r.nextDouble() * 80 - 40);
      var dir =
          new Vec3(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1)
              .normalize();
      double expected = Double.POSITIVE_INFINITY;
      int hit = -1;
      for (int n = 0; n < bodies.size(); n++) {
        var b = bodies.get(n);
        double d = SpaceBvh.sphere(origin, dir, b.center(), b.radius());
        if (d < expected) {
          expected = d;
          hit = n;
        }
      }
      var actual = bvh.intersect(origin, dir);
      assertEquals(hit, actual.index());
      assertEquals(expected, actual.distance());
    }
  }

  @Test
  void refitTimeUsesMotionAndConservativeRoughnessBounds() {
    var b = new SpaceDefinition.Body("moving", 3, Vec3.ZERO, new Vec3(1, 0, 0), 2, 0, 0, .2, 0, 0);
    var bvh = new SpaceBvh(List.of(b), 10);
    assertEquals(7.6, bvh.nodes().getFirst().min().x, 1e-8);
    assertEquals(8, bvh.intersect(Vec3.ZERO, new Vec3(1, 0, 0)).distance(), 1e-8);
  }

  @Test
  void opticalIntegrationIsOrderIndependentForOneUniformMediumAndConservesTransmission() {
    var a = new SpaceRenderMath.Optical(0, 1).then(.2, .3, 2, 10);
    var b = new SpaceRenderMath.Optical(0, 1).then(.2, .3, 2, 3).then(.2, .3, 2, 7);
    assertEquals(a.radiance(), b.radiance(), 1e-12);
    assertEquals(Math.exp(-.6), a.transmittance(), 1e-12);
    assertEquals(2 * (1 - a.transmittance()), a.radiance(), 1e-12);
    assertEquals(
        new SpaceRenderMath.Optical(0, 1), new SpaceRenderMath.Optical(0, 1).then(0, 1, 2, 10));
    assertThrows(IllegalArgumentException.class, () -> a.then(1, 1, 1, -1));
  }

  @Test
  void logarithmicVolumeSlicesRoundTrip() {
    for (int i = 1; i < 32; i++)
      assertEquals(
          i, SpaceRenderMath.sliceCoordinate(SpaceRenderMath.sliceDistance(i, 32), 32), 1e-8);
    assertEquals(0, SpaceRenderMath.sliceDistance(0, 32));
    assertEquals(1e6, SpaceRenderMath.sliceDistance(31, 32), 1e-5);
  }

  @Test
  void historyRejectsCutsProjectionSourceAndSceneChanges() {
    assertTrue(SpaceRenderMath.history(1, .99, true, true, true));
    assertFalse(SpaceRenderMath.history(20, 1, true, true, true));
    assertFalse(SpaceRenderMath.history(0, .5, true, true, true));
    assertFalse(SpaceRenderMath.history(0, 1, false, true, true));
    assertFalse(SpaceRenderMath.history(0, 1, true, false, true));
    assertFalse(SpaceRenderMath.history(0, 1, true, true, false));
  }

  @Test
  void proceduralRotationsStayInsideConservativeBounds() {
    var body =
        new SpaceDefinition.Body(
            "rock",
            3,
            new Vec3(0, 0, 20),
            Vec3.ZERO,
            3,
            0xff777777,
            0,
            .2,
            51,
            0,
            new Vec3(1, 2, 3),
            .4);
    var direction = new Vec3(0, 0, 1);
    for (int i = 0; i < 100; i++) {
      double hit = SpaceShapes.intersect(Vec3.ZERO, direction, body, i * .1, 100);
      assertTrue(hit >= 16.4 && hit <= 23.6, "Procedural hit escaped its enclosing sphere");
      assertTrue(Math.abs(SpaceShapes.distance(direction.scale(hit), body, i * .1)) < .002);
    }
  }

  @Test
  void rotationAndMotionAreIndependentOfStorageCoordinates() {
    var axis = new Vec3(1, 2, 3);
    var point = new Vec3(3, 4, 5);
    assertEquals(point.length(), SpaceShapes.rotate(point, axis, .7).length(), 1e-10);
    var body =
        new SpaceDefinition.Body(
            "moving", 3, new Vec3(0, 0, 20), new Vec3(0, 0, 1), 2, 0, 0, .1, 9, 0, axis, .2);
    double hit = SpaceShapes.intersect(Vec3.ZERO, new Vec3(0, 0, 1), body, 10, 100);
    assertTrue(hit > 27 && hit < 33);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpaceDefinition.Body(
                "bad", 3, Vec3.ZERO, Vec3.ZERO, 1, 0, 0, 0, 0, 0, axis, Double.NaN));
  }

  @Test
  void partialFieldCellsKeepWholeShapesInsideTheirBounds() {
    var extent = new Vec3(100, 20, 37);
    var field = new SpaceDefinition.Field("partial", Vec3.ZERO, extent, 16, 1, 1, 4, 9, 0);
    var s = new SpaceDefinition("test:space", 0, 0, 1, 0, List.of(), List.of(), List.of(field));
    for (var b : s.instances()) {
      double r = b.radius() * (1 + b.roughness());
      assertTrue(Math.abs(b.center().x) + r <= extent.x + 1e-9);
      assertTrue(Math.abs(b.center().y) + r <= extent.y + 1e-9);
      assertTrue(Math.abs(b.center().z) + r <= extent.z + 1e-9);
    }
  }

  @Test
  void smallNonzeroRotationAxesRetainTheirDirection() {
    var point = new Vec3(3, 4, 5);
    var axis = new Vec3(1, 2, 3);
    var expected = SpaceShapes.rotate(point, axis, .7);
    for (double scale : new double[] {1e-5, 1, 1e8}) {
      var actual = SpaceShapes.rotate(point, axis.scale(scale), .7);
      assertEquals(expected.x, actual.x, 1e-10);
      assertEquals(expected.y, actual.y, 1e-10);
      assertEquals(expected.z, actual.z, 1e-10);
    }
  }

  @Test
  void maximumHierarchyFitsTheGpuTraversalStack() {
    var bodies =
        java.util.stream.IntStream.range(0, SpaceDefinition.MAX_INSTANCES)
            .mapToObj(i -> body("b" + i, new Vec3(i, 0, 0), .4))
            .toList();
    var nodes = new SpaceBvh(bodies, 0).nodes();
    var stack = new java.util.ArrayDeque<int[]>();
    stack.push(new int[] {0, 1});
    int max = 0;
    while (!stack.isEmpty()) {
      var entry = stack.pop();
      max = Math.max(max, entry[1]);
      var node = nodes.get(entry[0]);
      if (node.left() >= 0) {
        stack.push(new int[] {node.left(), entry[1] + 1});
        stack.push(new int[] {node.right(), entry[1] + 1});
      }
    }
    assertTrue(max < 14, "Balanced maximum-size BVH must fit the 16-entry GPU DFS stack");
  }

  @Test
  void sharedHierarchyBoundsCoverTheEntireMotionTick() {
    var b =
        new SpaceDefinition.Body("moving", 2, Vec3.ZERO, new Vec3(100, -20, 10), 1, 0, 0, 0, 0, 0);
    var root = new SpaceBvh(List.of(b), 10, .05).nodes().getFirst();
    assertEquals(999, root.min().x, 1e-7);
    assertEquals(1006, root.max().x, 1e-7);
    assertTrue(root.min().y <= -202 && root.max().y >= -199);
  }
}
