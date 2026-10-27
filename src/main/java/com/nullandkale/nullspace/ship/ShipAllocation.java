package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.pad.PadScanner;
import com.nullandkale.nullspace.systems.Atmosphere;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** A ground-pad edit changes the allocation frame, never physical block or passenger positions. */
public final class ShipAllocation {
  public static void resize(CompoundTag ship, PadScanner.Pad pad, int height) {
    var nextBase = new BlockPos(pad.minX(), pad.y() + 1, pad.minZ());
    var delta = ShipStore.pos(ship, "base").subtract(nextBase);
    if (!delta.equals(BlockPos.ZERO)) {
      var systems = ship.getCompoundOrEmpty("systems");
      for (var tag : systems.getListOrEmpty("devices")) {
        var device = (CompoundTag) tag;
        device.putLong(
            "position", BlockPos.of(device.getLongOr("position", 0)).offset(delta).asLong());
      }
      var air = systems.getIntArray("air").orElse(null);
      if (air != null) {
        if (air.length != Atmosphere.CELLS)
          throw new IllegalStateException("Invalid saved atmosphere");
        var remapped = new int[Atmosphere.CELLS];
        Arrays.fill(remapped, -1);
        long vented = 0;
        for (int i = 0; i < air.length; i++) {
          int x = i % 32 + delta.getX(),
              y = i / 1024 + delta.getY(),
              z = i / 32 % 32 + delta.getZ();
          if (x >= 0 && x < 32 && y >= 0 && y < 32 && z >= 0 && z < 32)
            remapped[Atmosphere.index(x, y, z)] = air[i];
          else vented += Math.max(0, air[i]);
        }
        systems.putIntArray("air", remapped);
        if (vented > 0)
          systems.putLong(
              "constructionVented", systems.getLongOr("constructionVented", 0) + vented);
      }
      for (String field : new String[] {"computers", "monitorChannels"}) {
        if (!ship.contains(field)) continue;
        var old = ship.getCompoundOrEmpty(field);
        var remapped = new CompoundTag();
        for (var key : old.keySet())
          remapped.put(
              Long.toString(BlockPos.of(Long.parseLong(key)).offset(delta).asLong()),
              old.get(key).copy());
        ship.put(field, remapped);
      }
      for (var tag : ship.getListOrEmpty("crew")) {
        var member = (CompoundTag) tag;
        member.putDouble("x", member.getDoubleOr("x", 0) + delta.getX());
        member.putDouble("y", member.getDoubleOr("y", 0) + delta.getY());
        member.putDouble("z", member.getDoubleOr("z", 0) + delta.getZ());
      }
    }
    ship.putLong("base", nextBase.asLong());
    ship.putInt("width", pad.width());
    ship.putInt("depth", pad.depth());
    ship.putInt("height", height);
  }

  private ShipAllocation() {}
}
