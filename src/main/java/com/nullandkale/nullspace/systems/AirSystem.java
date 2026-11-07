package com.nullandkale.nullspace.systems;

import static com.nullandkale.nullspace.systems.ShipSystems.*;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class AirSystem {
  static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r, boolean initialize) {
    var s = state(ship);
    var grid = grid(ship);
    var base = ShipStore.pos(ship, "base");
    if (r.air == null) {
      r.air =
          s.getIntArray("air")
              .orElseGet(
                  () -> {
                    int[] a = new int[grid.cells()];
                    Arrays.fill(a, -1);
                    return a;
                  });
      if (r.air.length != grid.cells()) throw new IllegalStateException("Invalid saved atmosphere");
    }
    boolean[] seals = new boolean[grid.cells()];
    for (BlockPos p :
        BlockPos.betweenClosed(
            base, base.offset(grid.width() - 1, grid.height() - 1, grid.depth() - 1)))
      seals[local(ship, p)] = ShipStore.bounds(ship).contains(p) && ShipScan.seals(level, p);
    r.seals = seals;
    r.atmosphere = Atmosphere.rebuild(seals, r.air, initialize, grid);
    r.rooms = r.atmosphere.rooms();
    if (r.atmosphere.vented() > 0)
      s.putLong("constructionVented", s.getLongOr("constructionVented", 0) + r.atmosphere.vented());
    s.putIntArray("air", r.air);
  }

  static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var s = state(ship);
    if (level.getServer().getTickCount() % 5 == 0) {
      if (r.dirty) rebuild(level, ship, r, false);
      var supplies = new HashMap<Integer, Integer>();
      var outlets = new ArrayList<BlockPos>();
      for (var p : r.equipment)
        if (level.getBlockState(p).is(ShipBlocks.AIR_SUPPLY) && active(r, p)) {
          var front =
              p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
          if (ShipStore.bounds(ship).contains(front)) outlets.add(front);
        }
      // The air bus is shared evenly: that gets the most air from it, since power past
      // normal output (4) buys only half as much (SpatialAir.output).
      int power = grant(r, PowerBus.System.AIR);
      for (int i = 0; i < outlets.size(); i++) {
        int share = power / outlets.size() + (i < power % outlets.size() ? 1 : 0);
        supplies.merge(local(ship, outlets.get(i)), SpatialAir.output(share), Integer::sum);
      }
      var occupants = new HashMap<Integer, Integer>();
      for (var p : level.players())
        if (ShipStore.bounds(ship).contains(BlockPos.containing(p.getEyePosition())))
          // Breathing is a small, steady draw; leaks and supplies dominate the balance.
          occupants.merge(local(ship, BlockPos.containing(p.getEyePosition())), 1, Integer::sum);
      boolean vacuum = com.nullandkale.nullspace.navigation.PlanetSurfaces.vacuum(level);
      var flow =
          SpatialAir.step(
              r.air, r.atmosphere, r.seals, supplies, occupants, 5, vacuum ? 0 : Atmosphere.FULL);
      AirEffects.send(level, ShipStore.pos(ship, "base"), flow.flows(), grid(ship));
      s.putIntArray("air", r.air);
    }
  }
}
