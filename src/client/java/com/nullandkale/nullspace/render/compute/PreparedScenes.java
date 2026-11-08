package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.model.space.SpaceBvh;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;

/** A small cache of decoded scene definitions and their expanded bodies, shared by every view. */
public final class PreparedScenes {

  static final class Prepared {
    final CompoundTag source;
    final SpaceDefinition definition;
    final List<SpaceDefinition.Body> bodies;
    double tick = Double.NaN;
    SpaceBvh hierarchy;

    Prepared(CompoundTag source, SpaceDefinition definition, List<SpaceDefinition.Body> bodies) {
      this.source = source;
      this.definition = definition;
      this.bodies = bodies;
    }

    SpaceBvh bvh(double time) {
      double next = Math.floor(time * 20) / 20;
      if (next != tick) {
        tick = next;
        hierarchy = new SpaceBvh(bodies, next, .05);
      }
      return hierarchy;
    }
  }

  static final LinkedHashMap<String, Prepared> DEFINITIONS = new LinkedHashMap<>(8, .75f, true);

  static Prepared prepared(CompoundTag catalog) {
    String key = catalog.getStringOr("id", "") + ":" + catalog.getLongOr("revision", 0);
    var old = DEFINITIONS.get(key);
    if (old != null && old.source.equals(catalog)) return old;
    var definition = SpaceDefinitionCodec.decode(catalog);
    var next = new Prepared(catalog.copy(), definition, definition.instances());
    DEFINITIONS.put(key, next);
    while (DEFINITIONS.size() > 4) DEFINITIONS.remove(DEFINITIONS.keySet().iterator().next());
    return next;
  }

  private PreparedScenes() {}
}
