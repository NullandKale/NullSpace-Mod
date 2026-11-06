package com.nullandkale.nullspace.systems;

import static com.nullandkale.nullspace.systems.ShipSystems.*;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class DoorSystem {
  static void tick(ServerLevel level, ShipRuntime r) {
    if (!r.doors.isEmpty()) {
      if (r.commander != null
          && (!r.commander.isAlive()
              || r.commander.level() != level
              || r.commander.distanceToSqr(
                      net.minecraft.world.phys.Vec3.atCenterOf(r.commandController))
                  > 64
              || !r.blocks.contains(r.commandController))) {
        r.result = "Door batch CANCELLED: controller unavailable";
        r.doorResults.add(r.result);
        r.doors.clear();
      } else if (grant(r, PowerBus.System.DOORS) == 0) {
        r.result = "Door batch cancelled: NO POWER";
        r.doorResults.add(r.result);
        r.doors.clear();
      } else {
        var p = r.doors.removeFirst();
        var b = level.getBlockState(p);
        if (!ShipDoors.isDoor(b) || !r.blocks.contains(p) || !ShipDoors.framed(level, p))
          r.result = "Door MISSING/INVALID at " + p.toShortString();
        else if (com.nullandkale.nullspace.station.DockingService.ownsDoor(level, p))
          r.result = "DOCKING owns door at " + p.toShortString();
        else if (!active(r, p)) r.result = "DISABLED at " + p.toShortString();
        else if (!r.open && ShipDoors.powered(level, p))
          r.result = "REDSTONE OVERRIDE at " + p.toShortString();
        else {
          boolean already = b.getValue(DoorBlock.OPEN) == r.open;
          ShipDoors.setOpen(level, p, r.open);
          r.result =
              (level.getBlockState(p).getValue(DoorBlock.OPEN) == r.open
                      ? (already ? "ALREADY " : "") + (r.open ? "OPEN " : "CLOSED ")
                      : "OVERRIDDEN ")
                  + p.toShortString();
        }
        r.doorResults.add(r.result);
      }
    }
  }
}
