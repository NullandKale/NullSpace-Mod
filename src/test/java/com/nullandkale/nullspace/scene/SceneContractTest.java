package com.nullandkale.nullspace.scene;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class SceneContractTest {
  private static SpaceDefinition scene(String id, long revision) {
    return new SpaceDefinition(
        id,
        revision,
        91,
        1,
        42.5,
        List.of(
            new SpaceDefinition.Body(
                "planet",
                1,
                new Vec3(1e11, 2e11, -3e11),
                new Vec3(3, -2, 1),
                2000,
                0xff112233,
                0,
                .1,
                77,
                .2,
                new Vec3(1, 2, 3),
                .05)),
        List.of(
            new SpaceDefinition.Volume(
                "gas",
                Vec3.ZERO,
                new Vec3(10, 20, 30),
                new Vec3(1, 0, 0),
                0xff445566,
                .3,
                .01,
                .02,
                -.5,
                123,
                2)),
        List.of(
            new SpaceDefinition.Field(
                "belt", new Vec3(500, 0, 0), new Vec3(32, 16, 32), 16, .5, 1, 3, 456, 0xff778899)));
  }

  @Test
  void fullSceneRoundTripPreservesMotionVolumesAndGeneratedInstances() {
    var original = scene("test:scene", 1);
    var decoded = SpaceDefinitionCodec.decode(SpaceDefinitionCodec.encode(original));
    assertEquals(original, decoded);
    assertEquals(original.instances(), decoded.instances());
  }

  @Test
  void cacheRejectsOldAndInvalidUpdatesWithoutLosingTheActiveScene() {
    var c = new SpaceDefinitionCodec.Cache();
    var first = scene("test:a", 10);
    assertTrue(c.accept(SpaceDefinitionCodec.encode(first)));
    assertFalse(c.accept(SpaceDefinitionCodec.encode(scene("test:a", 9))));
    assertFalse(c.accept(SpaceDefinitionCodec.encode(first)));
    var bad = SpaceDefinitionCodec.encode(scene("test:a", 11));
    bad.putInt("version", 999);
    assertThrows(IllegalArgumentException.class, () -> c.accept(bad));
    assertEquals(first, c.scene());
    assertTrue(
        c.accept(SpaceDefinitionCodec.encode(scene("test:b", 0))),
        "A new local-space address starts its own revision sequence");
    c.clear();
    assertNull(c.scene());
    assertNull(c.encoded());
  }

  @Test
  void callerCannotMutateAnAcceptedWireSnapshot() {
    var c = new SpaceDefinitionCodec.Cache();
    var tag = SpaceDefinitionCodec.encode(scene("test:a", 1));
    c.accept(tag);
    tag.putString("id", "mutated");
    assertEquals("test:a", c.scene().id());
    assertEquals("test:a", c.encoded().getStringOr("id", ""));
  }

  @TestFactory
  Stream<DynamicTest> nonFiniteSceneInputsAreRejectedBeforeRendering() {
    return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        .flatMap(
            value ->
                Stream.of("center", "velocity", "radius", "density", "epoch")
                    .map(
                        field ->
                            DynamicTest.dynamicTest(
                                field + "=" + value,
                                () -> {
                                  var tag = SpaceDefinitionCodec.encode(scene("test:a", 1));
                                  var body = (CompoundTag) tag.getListOrEmpty("bodies").getFirst();
                                  switch (field) {
                                    case "center" -> body.putDouble("x", value);
                                    case "velocity" -> body.putDouble("vx", value);
                                    case "radius" -> body.putDouble("radius", value);
                                    case "density" ->
                                        ((CompoundTag) tag.getListOrEmpty("volumes").getFirst())
                                            .putDouble("density", value);
                                    case "epoch" -> tag.putDouble("epoch", value);
                                  }
                                  assertThrows(
                                      IllegalArgumentException.class,
                                      () -> SpaceDefinitionCodec.decode(tag));
                                })));
  }

  @Test
  void contributorTypeAndCrossKindIdentityCollisionsAreRejected() {
    var malformed = SpaceDefinitionCodec.encode(scene("test:a", 1));
    var bad = new ListTag();
    bad.add(StringTag.valueOf("not a body"));
    malformed.put("bodies", bad);
    assertThrows(IllegalArgumentException.class, () -> SpaceDefinitionCodec.decode(malformed));
    var tag = SpaceDefinitionCodec.encode(scene("test:a", 1));
    ((CompoundTag) tag.getListOrEmpty("volumes").getFirst()).putString("id", "planet");
    final var duplicate = tag;
    assertThrows(IllegalArgumentException.class, () -> SpaceDefinitionCodec.decode(duplicate));
  }
}
