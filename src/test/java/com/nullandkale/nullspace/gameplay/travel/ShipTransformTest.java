package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipTransformTest
{
    @Test
    void blockCentresAndContinuousPositionsAgreeForAllQuarterTurns()
    {
        var source = new BlockPos(100, 60, 200);
        var target = new BlockPos(-300, 80, -500);
        for (int turns = 0; turns < 4; turns++)
        {
            var transform = new ShipTransform(source, target, 8, 11, turns, new BlockPos(2, 0, 3));
            for (int x = 0; x < 8; x++)
                for (int z = 0; z < 11; z++)
                {
                    var block = source.offset(x, 2, z);
                    assertEquals(Vec3.atCenterOf(transform.apply(block)), transform.apply(Vec3.atCenterOf(block)));
                }
        }
    }

    @Test
    void fourRectangularQuarterTurnsDoNotDrift()
    {
        BlockPos block = new BlockPos(2, 3, 7);
        Vec3 point = new Vec3(2.25, 3.1, 7.8), initial = point;
        int width = 8, depth = 11;
        for (int i = 0; i < 4; i++)
        {
            var transform = new ShipTransform(BlockPos.ZERO, BlockPos.ZERO, width, depth, 1);
            block = transform.apply(block);
            point = transform.apply(point);
            int swap = width;
            width = depth;
            depth = swap;
        }
        assertEquals(new BlockPos(2, 3, 7), block);
        assertEquals(initial.x, point.x, 1e-10);
        assertEquals(initial.z, point.z, 1e-10);
    }

    @Test
    void journalRoundTripAndLegacyTranslationKeepExactDestinationBounds()
    {
        var journal = new CompoundTag();
        var min = new BlockPos(100, 60, 200);
        var max = min.offset(7, 5, 10);
        journal.putLong("min", min.asLong());
        journal.putLong("max", max.asLong());
        journal.putLong("delta", new BlockPos(400, 20, -300).asLong());
        assertEquals(min.offset(400, 20, -300), ShipTransform.destinationBounds(journal).min());
        var transform = new ShipTransform(min, new BlockPos(500, 80, -100), 8, 11, 1, new BlockPos(2, 0, 1));
        transform.save(journal, new HullScan.Bounds(min, max));
        assertEquals(transform, ShipTransform.fromJournal(journal));
        assertEquals(new BlockPos(502, 80, -99), ShipTransform.destinationBounds(journal).min());
        assertEquals(new BlockPos(512, 85, -92), ShipTransform.destinationBounds(journal).max());
    }

    @Test
    void unpressurizedUnknownCellsRemainUnknownWhenTheHullIsRecentered()
    {
        var ship = new CompoundTag();
        var systems = new CompoundTag();
        int[] air = new int[32768];
        java.util.Arrays.fill(air, -1);
        air[3 + 3 * 32 + 2 * 1024] = 600;
        systems.putIntArray("air", air);
        ship.put("systems", systems);
        RotatedShipState.ship(ship, new ShipTransform(BlockPos.ZERO, BlockPos.ZERO, 32, 32, 3, new BlockPos(0, 0, -25)));
        var result = systems.getIntArray("air").orElseThrow();
        assertEquals(-1, result[31 + 31 * 32 + 31 * 1024]);
        assertEquals(600, java.util.Arrays.stream(result).filter(i -> i > 0).sum());
        assertEquals(1, java.util.Arrays.stream(result).filter(i -> i >= 0).count());
    }

    @Test
    void savedAirDevicesPortsCrewAndBedIdentityFollowTheHull()
    {
        var transform = new ShipTransform(new BlockPos(100, 60, 200), new BlockPos(500, 80, -100), 32, 32, 1);
        var local = new BlockPos(5, 2, 7);
        var expected = new BlockPos(24, 2, 5);
        var ship = new CompoundTag();
        var systems = new CompoundTag();
        int[] air = new int[32768];
        air[5 + 7 * 32 + 2 * 1024] = 777;
        systems.putIntArray("air", air);
        var device = new CompoundTag();
        device.putLong("position", local.asLong());
        device.putInt("energy", 37);
        var devices = new ListTag();
        devices.add(device);
        systems.put("devices", devices);
        ship.put("systems", systems);
        var nodes = new CompoundTag();
        nodes.putString(Long.toString(local.asLong()), "door-id");
        ship.put("doorNodes", nodes);
        RotatedShipState.ship(ship, transform);
        assertEquals(37, device.getIntOr("energy", -1));
        assertEquals(expected.asLong(), device.getLongOr("position", 0));
        assertEquals("door-id", ship.getCompoundOrEmpty("doorNodes").getStringOr(Long.toString(expected.asLong()), ""));
        int[] moved = systems.getIntArray("air").orElseThrow();
        assertEquals(777, moved[24 + 5 * 32 + 2 * 1024]);
        assertEquals(777, java.util.Arrays.stream(moved).sum());
        var member = new CompoundTag();
        member.putDouble("x", 5.5);
        member.putDouble("y", 2);
        member.putDouble("z", 7.5);
        member.putFloat("yaw", 15);
        RotatedShipState.crew(member, transform);
        assertEquals(24.5, member.getDoubleOr("x", 0));
        assertEquals(5.5, member.getDoubleOr("z", 0));
        assertEquals(105, member.getFloatOr("yaw", 0));
        var root = new CompoundTag();
        var placements = new CompoundTag();
        placements.putString("ship@" + local.asLong(), "placement-token");
        root.put("bedPlacements", placements);
        var bindings = new CompoundTag();
        var binding = new CompoundTag();
        binding.putString("ship", "ship");
        binding.putLong("bed", local.asLong());
        binding.putString("placement", "placement-token");
        bindings.put("player", binding);
        root.put("vesselBeds", bindings);
        RotatedShipState.beds(root, "ship", transform);
        assertEquals("placement-token", placements.getStringOr("ship@" + expected.asLong(), ""));
        assertEquals(expected.asLong(), binding.getLongOr("bed", 0));
        assertEquals("placement-token", binding.getStringOr("placement", ""));
    }
}
