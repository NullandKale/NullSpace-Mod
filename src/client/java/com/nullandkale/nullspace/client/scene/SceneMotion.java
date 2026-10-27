package com.nullandkale.nullspace.client.scene;

import com.nullandkale.nullspace.navigation.FlightDynamics;
import java.util.*;
import net.minecraft.nbt.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Render-thread pose history shared across scene consumers; never retains hull arrays. */
public final class SceneMotion {
  private static final List<String> FIELDS = List.of("x", "y", "z", "yaw", "pitch", "roll");

  private record Sample(
      double time, long received, double duration, CompoundTag previous, CompoundTag target) {}

  private static final LinkedHashMap<String, Sample> SAMPLES = new LinkedHashMap<>(32, .75f, true);

  private static String key(CompoundTag scene, CompoundTag subject) {
    return scene.getStringOr("sceneId", "") + "/" + subject.getStringOr("id", "");
  }

  private static CompoundTag pose(CompoundTag source) {
    var pose = new CompoundTag();
    for (String field : FIELDS) pose.putDouble(field, source.getDoubleOr(field, 0));
    return pose;
  }

  private static boolean same(CompoundTag a, CompoundTag b) {
    for (String field : FIELDS)
      if (a.getDoubleOr(field, 0) != b.getDoubleOr(field, 0)) return false;
    return true;
  }

  public static void track(CompoundTag scene) {
    if (!scene.getBooleanOr("universe", false)) return;
    double time = scene.getDoubleOr("simulationTime", 0);
    long now = System.nanoTime();
    for (var tag : scene.getListOrEmpty("subjects")) {
      var t = (CompoundTag) tag;
      String key = key(scene, t);
      var old = SAMPLES.get(key);
      if (old != null && time <= old.time) continue;
      double duration = old == null ? .1 : Math.clamp(time - old.time, .05, 1);
      var target = pose(t);
      var previous = old == null ? target : old.target;
      double dx = target.getDoubleOr("x", 0) - previous.getDoubleOr("x", 0),
          dy = target.getDoubleOr("y", 0) - previous.getDoubleOr("y", 0),
          dz = target.getDoubleOr("z", 0) - previous.getDoubleOr("z", 0);
      if (dx * dx + dy * dy + dz * dz > 128 * 128 || same(previous, target)) previous = target;
      SAMPLES.put(key, new Sample(time, now, duration, previous, target));
    }
    while (SAMPLES.size() > 64) SAMPLES.remove(SAMPLES.keySet().iterator().next());
  }

  public static CompoundTag resolve(CompoundTag scene) {
    if (scene.getBooleanOr("motionResolved", false) || !scene.getBooleanOr("universe", false))
      return scene;
    long now = System.nanoTime();
    var subjects = new ListTag();
    boolean moving = false, changed = false;
    for (var tag : scene.getListOrEmpty("subjects")) {
      var original = (CompoundTag) tag;
      var sample = SAMPLES.get(key(scene, original));
      if (sample == null || sample.previous == sample.target && same(original, sample.target)) {
        subjects.add(original);
        continue;
      }
      double f = Math.clamp((now - sample.received) / (sample.duration * 1e9), 0, 1);
      if (f == 1 && same(original, sample.target)) {
        subjects.add(original);
        continue;
      }
      var data = SceneViewport.cameraView(original);
      changed = true;
      for (String axis : List.of("x", "y", "z"))
        data.putDouble(
            axis,
            sample.previous.getDoubleOr(axis, 0) * (1 - f)
                + sample.target.getDoubleOr(axis, 0) * f);
      var e =
          rotation(sample.previous)
              .slerp(rotation(sample.target), f)
              .getEulerAnglesYXZ(new Vector3d());
      data.putDouble("yaw", Math.toDegrees(e.y));
      data.putDouble("pitch", Math.toDegrees(e.x));
      data.putDouble("roll", Math.toDegrees(e.z));
      moving |= f < 1 && sample.previous != sample.target;
      subjects.add(data);
    }
    if (!changed) return scene;
    var result = SceneViewport.cameraView(scene);
    result.put("subjects", subjects);
    result.putBoolean("motionResolved", true);
    if (moving) result.putLong("motionFrame", now / 16_666_667);
    return result;
  }

  private static Quaterniond rotation(CompoundTag t) {
    return FlightDynamics.orientation(
        t.getDoubleOr("yaw", 0), t.getDoubleOr("pitch", 0), t.getDoubleOr("roll", 0));
  }

  public static void clear() {
    SAMPLES.clear();
  }

  private SceneMotion() {}
}
