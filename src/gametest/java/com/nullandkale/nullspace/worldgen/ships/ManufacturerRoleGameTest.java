package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;

public final class ManufacturerRoleGameTest
{
    @GameTest(environment = "nullspace:manufacturer_roles", maxTicks = 300)
    public void roleFleetHasAccessibleSuppliedRoomsRealCargoAndConnectedStairs(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        int i = 0;
        var floors = new HashSet<Set<BlockPos>>();
        for (var preset : ShipBlueprints.PRESETS)
        {
            if (preset.role().equals("Scout") || preset.tiny())
                continue;
            var base = new BlockPos(1500000 + i++ * 4096, 80, 1500000);
            ShipYard.buildPad(level, base, 32, 32, "Role " + preset.id());
            var pad = new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
            var origin = ShipBlueprints.origin(preset, pad, 32);
            var plan = ShipBlueprints.blocks(preset);
            var floor = new HashSet<BlockPos>();
            for (var entry : plan.entrySet())
            {
                level.setBlock(origin.offset(entry.getKey()), entry.getValue(), 18);
                if (entry.getKey().getY() == 0)
                    floor.add(entry.getKey());
            }
            ctx.assertTrue(floors.add(new HashSet<>(plan.keySet())), "Each design has its own shape");
            var scan = HullScan.inspect(level, origin.offset(preset.helm()), pad, true);
            ctx.assertTrue(scan.blocks().size() == plan.size(), "All machinery, stairs and upper rooms are connected");
            var ship = ShipTopology.resolve(level, origin.offset(preset.helm()));
            for (var at : scan.blocks())
                FuelSystem.provision(level, at, 64);
            SystemsUpgrade.upgrade(level, ship);
            ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
            ShipSystems.tick(level, ship);
            ctx.assertTrue(ShipSensors.installed(level, ship, false) && ShipSystems.power(level, ship, PowerBus.System.CAMERAS) == 2, preset.name() + " powers its scanner and camera with stock generators and empty batteries");
            ship.putInt("thrustDemand", 3);
            ShipSystems.tick(level, ship);
            ctx.assertTrue(ShipSensors.installed(level, ship, false) && NavigationService.propulsion(level, ship) > 0, "Navigation sensors stay powered while propulsion uses the remaining output");
            ship.putInt("thrustDemand", 0);
            var port = DockingPorts.firstPort(level, ship);
            var unreachable = new ArrayList<String>();
            for (var room : ShipRooms.rooms(preset.id()))
            {
                var target = origin.offset(room.walk());
                ctx.assertTrue(scan.pressure().breathable(target.above()), preset.name() + " seals " + room.name());
                ctx.assertTrue(ShipAir.breathable(level, ship, target.above()), "Finite air is retained in " + room.name());
                if (!reachable(level, WorldStore.bounds(ship), port.anchors().getFirst(), target))
                    unreachable.add(room.name() + " at " + room.walk().toShortString() + " of " + preset.decks() + " decks");
            }
            ctx.assertTrue(unreachable.isEmpty(), preset.name() + " has a walking route from the dock to every room, but not to " + unreachable);
            var rooms = ShipRooms.rooms(preset.id());
            ctx.assertTrue(rooms.size() >= 5 && rooms.stream().map(ShipRooms.Room::use).distinct().count() >= 3, "Role layouts have distinct working and living spaces");
            for (var room : rooms)
            {
                if (room.supplies() == null)
                    continue;
                var container = origin.offset(room.supplies());
                ctx.assertTrue(level.getBlockEntity(container) instanceof net.minecraft.world.Container, preset.name() + " has real storage in " + room.name());
                ctx.assertTrue(Direction.Plane.HORIZONTAL.stream().anyMatch(direction -> reachable(level, WorldStore.bounds(ship), port.anchors().getFirst(), container.relative(direction))), preset.name() + " supplies can be reached in " + room.name());
            }
            int air = (int) plan.values().stream().filter(s -> s.is(ModBlocks.AIR_SUPPLY)).count();
            ctx.assertTrue(ShipSystems.power(level, ship, PowerBus.System.AIR) == air * SpatialAir.SUPPLY_POWER, "Every separated room's pump has normal power, not just the first two: " + ShipSystems.power(level, ship, PowerBus.System.AIR) + " for " + air);
            if (preset.role().equals("Freighter"))
                ctx.assertTrue(plan.values().stream().filter(s -> s.is(Blocks.BARREL)).count() >= 6 && ShipRooms.rooms(preset.id()).stream().anyMatch(r -> r.use() == ShipRooms.Use.CARGO), "Freighter role has a real hold of storage barrels: " + plan.values().stream().filter(s -> s.is(Blocks.BARREL)).count());
            if (preset.decks() > 1)
                ctx.assertTrue(plan.values().stream().anyMatch(s -> s.getBlock() instanceof StairBlock), preset.name() + " has a stairwell to its upper decks");
        }
        ctx.assertTrue(i == 6, "Three manufacturers each add a freighter and explorer");
        ctx.succeed();
    }

    private static boolean reachable(net.minecraft.server.level.ServerLevel level, HullScan.Bounds bounds, BlockPos start, BlockPos goal)
    {
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < 8192)
        {
            var at = queue.removeFirst();
            if (!seen.add(at))
                continue;
            if (at.equals(goal))
                return true;
            for (var d : Direction.Plane.HORIZONTAL)
                for (int dy = -1; dy <= 1; dy++)
                {
                    var n = at.relative(d).above(dy);
                    if (!bounds.contains(n))
                        continue;
                    var low = level.getBlockState(n);
                    var high = level.getBlockState(n.above());
                    var support = level.getBlockState(n.below());
                    // A garden path is a little lower than a full block, and still a floor.
                    boolean floor = !support.getCollisionShape(level, n.below()).isEmpty() && support.getCollisionShape(level, n.below()).max(net.minecraft.core.Direction.Axis.Y) >= .9 || support.getBlock() instanceof StairBlock;
                    boolean door = low.getBlock() instanceof DoorBlock && ShipDoors.framed(level, n);
                    boolean clearance = high.getCollisionShape(level, n.above()).isEmpty() && (low.getCollisionShape(level, n).isEmpty() || low.getBlock() instanceof StairBlock);
                    if ((floor && clearance || floor && door) && (dy <= 0 || level.getBlockState(at.above(2)).getCollisionShape(level, at.above(2)).isEmpty()))
                        queue.add(n);
                }
        }
        return false;
    }
}
