package com.nullandkale.nullspace.model.space;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

/** Wire/save adapter; validation completes before any client GPU allocation. */
public final class SpaceDefinitionCodec {
  private static CompoundTag compound(Tag value) {
    if (!(value instanceof CompoundTag compound))
      throw new IllegalArgumentException("Scene contributors must be compounds");
    return compound;
  }

  private static Vec3 vec(CompoundTag t, String prefix) {
    return new Vec3(
        t.getDoubleOr(prefix + "x", 0),
        t.getDoubleOr(prefix + "y", 0),
        t.getDoubleOr(prefix + "z", 0));
  }

  private static void putVec(CompoundTag t, String prefix, Vec3 v) {
    t.putDouble(prefix + "x", v.x);
    t.putDouble(prefix + "y", v.y);
    t.putDouble(prefix + "z", v.z);
  }

  public static SpaceDefinition decode(CompoundTag t) {
    if (t.getIntOr("version", 0) != 2 || t.sizeInBytes() > 65536)
      throw new IllegalArgumentException("Unsupported or oversized space definition");
    var bodies = new ArrayList<SpaceDefinition.Body>();
    var volumes = new ArrayList<SpaceDefinition.Volume>();
    var fields = new ArrayList<SpaceDefinition.Field>();
    if (t.getListOrEmpty("bodies").size() > 64
        || t.getListOrEmpty("volumes").size() > 16
        || t.getListOrEmpty("fields").size() > 8)
      throw new IllegalArgumentException("Too many contributors");
    for (Tag tag : t.getListOrEmpty("bodies")) {
      var b = compound(tag);
      String kind = b.getStringOr("kind", "");
      int type =
          switch (kind) {
            case "sun" -> 0;
            case "planet" -> 1;
            case "moon" -> 2;
            case "asteroid" -> 3;
            case "comet" -> 4;
            default -> throw new IllegalArgumentException("Unknown body " + kind);
          };
      bodies.add(
          new SpaceDefinition.Body(
              b.getStringOr("id", ""),
              type,
              vec(b, ""),
              vec(b, "v"),
              b.getDoubleOr("radius", 0),
              b.getIntOr("color", -1),
              b.getDoubleOr("emission", b.getBooleanOr("emissive", false) ? 6 : 0),
              b.getDoubleOr("roughness", type >= 3 ? .15 : 0),
              b.getLongOr("seed", 1),
              b.getDoubleOr("atmosphere", 0),
              vec(b, "r"),
              b.getDoubleOr("spin", 0),
              b.contains("optics")
                  ? AtmosphereProfile.decode(b.getCompoundOrEmpty("optics"))
                  : null));
    }
    for (Tag tag : t.getListOrEmpty("volumes")) {
      var v = compound(tag);
      volumes.add(
          new SpaceDefinition.Volume(
              v.getStringOr("id", ""),
              vec(v, ""),
              vec(v, "e"),
              vec(v, "v"),
              v.getIntOr("color", -1),
              v.getDoubleOr("density", 0),
              v.getDoubleOr("extinction", .01),
              v.getDoubleOr("emission", 0),
              v.getDoubleOr("phase", 0),
              v.getLongOr("seed", 1),
              v.getIntOr("kind", 0)));
    }
    for (Tag tag : t.getListOrEmpty("fields")) {
      var f = compound(tag);
      fields.add(
          new SpaceDefinition.Field(
              f.getStringOr("id", ""),
              vec(f, ""),
              vec(f, "e"),
              f.getDoubleOr("cell", 0),
              f.getDoubleOr("density", 0),
              f.getDoubleOr("minRadius", 0),
              f.getDoubleOr("maxRadius", 0),
              f.getLongOr("seed", 1),
              f.getIntOr("color", -1)));
    }
    return new SpaceDefinition(
        t.getStringOr("id", ""),
        t.getLongOr("revision", -1),
        t.getLongOr("seed", 0),
        t.getIntOr("generator", 0),
        t.getDoubleOr("epoch", 0),
        bodies,
        volumes,
        fields);
  }

  public static CompoundTag encode(SpaceDefinition s) {
    var t = new CompoundTag();
    t.putInt("version", 2);
    t.putInt("generator", s.generator());
    t.putString("id", s.id());
    t.putLong("revision", s.revision());
    t.putLong("seed", s.seed());
    t.putDouble("epoch", s.epoch());
    var bodies = new ListTag();
    var volumes = new ListTag();
    var fields = new ListTag();
    for (var b : s.bodies()) {
      var v = new CompoundTag();
      v.putString("id", b.id());
      v.putString("kind", new String[] {"sun", "planet", "moon", "asteroid", "comet"}[b.kind()]);
      putVec(v, "", b.center());
      putVec(v, "v", b.velocity());
      v.putDouble("radius", b.radius());
      v.putInt("color", b.color());
      v.putBoolean("emissive", b.emission() > 0);
      v.putDouble("emission", b.emission());
      v.putDouble("roughness", b.roughness());
      v.putLong("seed", b.seed());
      v.putDouble("atmosphere", b.atmosphere());
      putVec(v, "r", b.rotationAxis());
      v.putDouble("spin", b.spin());
      if (b.optics() != null) v.put("optics", b.optics().encode());
      bodies.add(v);
    }
    for (var v : s.volumes()) {
      var a = new CompoundTag();
      a.putString("id", v.id());
      putVec(a, "", v.center());
      putVec(a, "e", v.extent());
      putVec(a, "v", v.velocity());
      a.putInt("color", v.color());
      a.putDouble("density", v.density());
      a.putDouble("extinction", v.extinction());
      a.putDouble("emission", v.emission());
      a.putDouble("phase", v.phase());
      a.putLong("seed", v.seed());
      a.putInt("kind", v.kind());
      volumes.add(a);
    }
    for (var f : s.fields()) {
      var a = new CompoundTag();
      a.putString("id", f.id());
      putVec(a, "", f.center());
      putVec(a, "e", f.extent());
      a.putDouble("cell", f.cell());
      a.putDouble("density", f.density());
      a.putDouble("minRadius", f.minRadius());
      a.putDouble("maxRadius", f.maxRadius());
      a.putLong("seed", f.seed());
      a.putInt("color", f.color());
      fields.add(a);
    }
    t.put("bodies", bodies);
    t.put("volumes", volumes);
    t.put("fields", fields);
    if (t.sizeInBytes() > 65536)
      throw new IllegalArgumentException("Space definition exceeds 64 KiB");
    return t;
  }

  /** Per-connection catalog. A rejected or old definition cannot replace the active scene. */
  public static final class Cache {
    private SpaceDefinition scene;
    private CompoundTag encoded;

    public boolean accept(CompoundTag t) {
      var next = decode(t);
      next.instances();
      if (scene != null && scene.id().equals(next.id()) && scene.revision() >= next.revision())
        return false;
      scene = next;
      encoded = t.copy();
      return true;
    }

    public SpaceDefinition scene() {
      return scene;
    }

    public CompoundTag encoded() {
      return encoded;
    }

    public void clear() {
      scene = null;
      encoded = null;
    }
  }

  private SpaceDefinitionCodec() {}
}
