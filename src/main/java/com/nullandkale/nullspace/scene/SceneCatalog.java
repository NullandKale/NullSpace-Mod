package com.nullandkale.nullspace.scene;

import com.nullandkale.nullspace.ship.ShipStore;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;

/**
 * Server-authored celestial identities and representations. Viewing never loads planetary terrain.
 */
public final class SceneCatalog {
  public static CompoundTag get(MinecraftServer server) {
    var store = ShipStore.get(server);
    if (store.section("scene").isEmpty()) {
      var data = new CompoundTag();
      data.putInt("version", 1);
      data.putLong("seed", 79419843L);
      var bodies = new ListTag();
      bodies.add(body("sun", "Helios", -12000, 6000, -18000, 1800, 0xffffd07a, true));
      bodies.add(body("planet", "Terra", 0, -2500, 5000, 2200, 0xff2161aa, false));
      bodies.add(body("moon", "Luna", 2600, -1000, 4300, 600, 0xffaaaaad, false));
      bodies.add(body("asteroid", "Rock 1", -80, 12, 180, 12, 0xff76695a, false));
      data.put("bodies", bodies);
      // Moons get their spacing once, when the scene is first created.
      CelestialLayout.arrange(data, "Terra");
      store.section("scene").merge(data);
      store.requestSave();
    }
    var data = store.section("scene");
    if (data.getIntOr("version", 1) == 1) {
      data.putInt("version", 2);
      data.putInt("generator", 1);
      data.putString("id", "nullspace:local-space");
      data.putLong("revision", 1);
      data.putDouble("epoch", 0);
      for (var tag : data.getListOrEmpty("bodies")) {
        var body = (CompoundTag) tag;
        body.putLong("seed", body.getStringOr("id", "").hashCode());
        if (body.getStringOr("kind", "").equals("planet")) body.putDouble("atmosphere", .04);
      }
      var comet = body("comet", "Voyager", 260, 70, 460, 10, 0xffa5aabb, false);
      comet.putDouble("vx", .3);
      comet.putDouble("vz", -.1);
      comet.putDouble("roughness", .14);
      data.getListOrEmpty("bodies").add(comet);
      var volumes = new ListTag();
      var gas = new CompoundTag();
      gas.putString("id", "nullspace:nebula");
      gas.putDouble("x", 120);
      gas.putDouble("y", 90);
      gas.putDouble("z", 500);
      gas.putDouble("ex", 280);
      gas.putDouble("ey", 140);
      gas.putDouble("ez", 200);
      gas.putInt("color", 0xff7350b5);
      gas.putDouble("density", .12);
      gas.putDouble("extinction", .009);
      gas.putDouble("emission", .16);
      gas.putDouble("phase", .25);
      gas.putLong("seed", 971);
      volumes.add(gas);
      data.put("volumes", volumes);
      var fields = new ListTag();
      var field = new CompoundTag();
      field.putString("id", "nullspace:belt");
      field.putDouble("x", -220);
      field.putDouble("z", 320);
      field.putDouble("ex", 160);
      field.putDouble("ey", 64);
      field.putDouble("ez", 160);
      field.putDouble("cell", 32);
      field.putDouble("density", .15);
      field.putDouble("minRadius", 1.5);
      field.putDouble("maxRadius", 6);
      field.putLong("seed", 8241);
      field.putInt("color", 0xff817267);
      fields.add(field);
      data.put("fields", fields);
      SpaceDefinitionCodec.decode(data);
      store.requestSave();
    }
    return data;
  }

  public static CompoundTag forShip(MinecraftServer server, CompoundTag ship) {
    return region(server, com.nullandkale.nullspace.navigation.LocalSpaces.address(ship));
  }

  public static CompoundTag region(MinecraftServer server, String address) {
    var destination = com.nullandkale.nullspace.navigation.LocalSpaces.get(server, address);
    if (com.nullandkale.nullspace.navigation.JourneyService.special(server, address)) {
      var space = new CompoundTag();
      space.putInt("version", 2);
      space.putInt("generator", 1);
      space.putString("id", "nullspace:" + address);
      space.putLong("seed", destination.seed());
      space.putLong("revision", 1);
      space.putDouble("epoch", 0);
      space.put("bodies", new ListTag());
      space.put("volumes", new ListTag());
      space.put("fields", new ListTag());
      return space;
    }
    if (address.equals(com.nullandkale.nullspace.navigation.LocalSpaces.HOME)) return get(server);
    var store = ShipStore.get(server);
    var regions = store.section("localScenes");
    if (regions.contains(address)) {
      var cached = regions.getCompoundOrEmpty(address);
      // Generated views from an older layout are rebuilt; hand-made ones are kept.
      if (!cached.contains("systemGenerator")
          || cached.getIntOr("layoutVersion", 1)
              >= com.nullandkale.nullspace.navigation.StarSystems.SCENE_LAYOUT) return cached;
    }
    var generated = com.nullandkale.nullspace.navigation.StarSystems.scene(server, address);
    if (generated.isPresent()) {
      regions.put(address, generated.get());
      store.requestSave();
      return generated.get();
    }
    var template = get(server).copy();
    template.putString("id", "nullspace:" + address);
    template.putLong("seed", destination.seed());
    template.putLong("revision", 1);
    template.putDouble("epoch", server.overworld().getGameTime() / 20.0);
    int index = 0;
    for (var tag : template.getListOrEmpty("bodies")) {
      var b = (CompoundTag) tag;
      b.putString("id", "nullspace:" + address + ":" + index++);
      b.putLong("seed", destination.seed() + index * 971);
      String kind = b.getStringOr("kind", "");
      if (kind.equals("sun")) {
        b.putString("name", destination.systemName().equals("Sol") ? "Helios" : "Vega");
        b.putInt("color", destination.systemName().equals("Sol") ? 0xffffd07a : 0xffaaccff);
      }
      if (kind.equals("planet")) {
        b.putString("name", destination.name());
        b.putInt("color", destination.color());
        if (destination.moon()) {
          b.putString("kind", "moon");
          b.putDouble("radius", 1100);
          b.putDouble("atmosphere", 0);
          b.putDouble("y", -1200);
          b.putDouble("z", 3200);
        }
      }
      if (kind.equals("moon")) {
        b.putString("name", "Companion");
        b.putInt("color", destination.moon() ? 0xff2161aa : 0xffaaa299);
      }
    }
    for (var tag : template.getListOrEmpty("volumes")) {
      var v = (CompoundTag) tag;
      v.putString("id", "nullspace:" + address + ":gas");
      v.putLong("seed", destination.seed());
      v.putInt("color", destination.system().equals("sol") ? 0xff935678 : 0xff326bba);
    }
    for (var tag : template.getListOrEmpty("fields")) {
      var f = (CompoundTag) tag;
      f.putString("id", "nullspace:" + address + ":belt");
      f.putLong("seed", destination.seed());
    }
    CelestialLayout.arrange(template, destination.name());
    SpaceDefinitionCodec.decode(template).instances();
    regions.put(address, template);
    store.requestSave();
    return template;
  }

  private static CompoundTag body(
      String kind,
      String name,
      double x,
      double y,
      double z,
      double radius,
      int color,
      boolean emission) {
    var b = new CompoundTag();
    b.putString(
        "id",
        java.util
            .UUID
            .nameUUIDFromBytes(
                ("nullspace:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString());
    b.putString("kind", kind);
    b.putString("name", name);
    b.putDouble("x", x);
    b.putDouble("y", y);
    b.putDouble("z", z);
    b.putDouble("radius", radius);
    b.putInt("color", color);
    b.putBoolean("emissive", emission);
    return b;
  }

  private SceneCatalog() {}
}
