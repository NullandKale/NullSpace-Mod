package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.EngineEffects;
import com.nullandkale.nullspace.gameplay.systems.EngineSounds;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.testing.TestWaits;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

import org.joml.Vector3d;

public final class DockingPresetGameTest
{
    @GameTest(environment = "nullspace:docking_presets")
    public void capturedTunnelKeepsBlackoutBoardingSafeAndPreservesExistingBlocks(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var ship = StationGameTest.fixture(level, new BlockPos(2213000, 96, 2200000), true);
        var station = StationGameTest.fixture(level, new BlockPos(2214000, 96, 2200000), false);
        var player = ctx.makeMockServerPlayerInLevel();
        try
        {
            var source = DockingPorts.firstPort(level, ship);
            var target = DockingPorts.ports(level, station).getFirst();
            var at = source.anchors().getFirst();
            player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), 0, 0, true);
            DockingManeuver.request(player, ship, station.getStringOr("id", ""), target.controller().subtract(WorldStore.pos(station, "base")));
            var dock = ship.getCompoundOrEmpty("dock");
            // Collars and door boarding: an older dock, or one whose hulls could not be joined.
            dock.putBoolean("join", false);
            dock.putDouble("gap", 4.05);
            pose(ship, DockingManeuver.matingPose(ship, source, station, target, 4.05));
            dock.putString("state", "CLAMPED");
            var existing = source.outer().below().relative(source.facing());
            level.setBlock(existing, net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            DockingManeuver.tickMotion(server, ship);
            ctx.assertTrue(dock.contains("tunnel") && DockingTunnel.sections(level, ship) == 1 && DockingTunnel.sections(level, station) == 1, "Both storage frames have persistent, rendered boarding collars");
            com.nullandkale.nullspace.gameplay.travel.BuildJournal.flushPending(server);
            var cells = dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty("source").getListOrEmpty("blocks");
            var floor = cells.stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("kind", "").equals("floor")).findFirst().orElseThrow();
            var step = WorldStore.pos(floor, "at").above();
            ctx.assertTrue(!ShipService.inspect(level, WorldStore.pos(ship, "controller")).scan().blocks().contains(WorldStore.pos(floor, "at")), "A docked ship can still be inspected; temporary collars are not captured as hull or" + " loose cargo");
            player.teleportTo(step.getX() + .5, step.getY(), step.getZ() + .5);
            boolean blocked = false;
            try
            {
                DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
            }
            catch (IllegalArgumentException occupied)
            {
                blocked = occupied.getMessage().contains("inside a cabin");
            }
            ctx.assertTrue(blocked, "A tunnel cannot retract under a passenger");
            player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            for (var vessel : List.of(ship, station))
            {
                vessel.getCompoundOrEmpty("systems").putIntArray("requests", new int[6]);
                ShipSystems.tick(level, vessel);
                ctx.assertTrue(ShipSystems.power(level, vessel, PowerBus.System.CONTROLS) == 0, "Fixture is fully unpowered");
            }
            DockingManeuver.tickMotion(server, ship);
            boolean noPropulsion = false;
            try
            {
                DockingService.perform(player, ship, source.controller(), "DOCK_UNDOCK");
            }
            catch (IllegalArgumentException expected)
            {
                noPropulsion = expected.getMessage().contains("Your ship needs");
            }
            ctx.assertTrue(noPropulsion && dock.getStringOr("state", "").equals("CLAMPED") && dock.contains("tunnel"), "An unpowered source cannot retract the safe connection and begin departure");
            DockingService.perform(player, ship, source.controller(), "DOCK_BOARD");
            ctx.assertTrue(Boarding.busy(player), "A captured connection permits boarding without either ship's power");
            WorldStore.get(server).list("stationBoarding").removeIf(t -> ((CompoundTag) t).getStringOr("player", "").equals(player.getUUID().toString()));
            // A delayed movement packet can reach the collar's end stop between server ticks.
            for (var vessel : List.of(ship, station))
            {
                var port = vessel == ship ? source : target;
                var end = net.minecraft.world.phys.Vec3.atBottomCenterOf(port.outer()).add(DockingMotion.normal(port.facing()).scale(3.2));
                ShipDoors.setOpen(level, port.outer(), false);
                player.teleportTo(end.x, end.y, end.z);
                DockingPorts.walkThrough(server);
                ctx.assertTrue(!Boarding.busy(player), "A closed door still prevents boarding");
                ShipDoors.setOpen(level, port.outer(), true);
                var side = DockingMotion.normal(port.facing().getClockWise()).scale(2);
                player.teleportTo(end.x + side.x, end.y, end.z + side.z);
                DockingPorts.walkThrough(server);
                ctx.assertTrue(!Boarding.busy(player), "Passengers beside the collar cannot board");
                player.teleportTo(end.x, end.y, end.z);
                DockingPorts.walkThrough(server);
                ctx.assertTrue(Boarding.busy(player), "A passenger at either collar end stop boards during a blackout after delayed" + " movement");
                WorldStore.get(server).list("stationBoarding").removeIf(t -> ((CompoundTag) t).getStringOr("player", "").equals(player.getUUID().toString()));
                ShipDoors.setOpen(level, port.outer(), false);
            }
            player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            // Cancel an interrupted departure to exercise durable collar removal.
            dock.putString("state", "UNDOCKING");
            DockingService.perform(player, ship, source.controller(), "DOCK_CANCEL");
            com.nullandkale.nullspace.gameplay.travel.BuildJournal.flushPending(server);
            ctx.assertTrue(level.getBlockState(WorldStore.pos(floor, "at")).isAir() && level.getBlockState(existing).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK), "Retraction removes only temporary collar blocks, retaining the original hull");
        }
        finally
        {
            server.getPlayerList().remove(player);
            WorldStore.get(server).ships().remove(ship);
            WorldStore.get(server).ships().remove(station);
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:docking_presets")
    public void compactDockAcceptsADoorPlacedFacingIntoTheCabin(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var ship = StationGameTest.fixture(level, new BlockPos(2210000, 96, 2200000), true);
        var station = StationGameTest.fixture(level, new BlockPos(2211000, 96, 2200000), false);
        var player = ctx.makeMockServerPlayerInLevel();
        try
        {
            var original = DockingPorts.firstPort(level, ship);
            for (var door : List.of(original.outer(), original.outer().above()))
                level.setBlock(door, level.getBlockState(door).setValue(net.minecraft.world.level.block.DoorBlock.FACING, original.facing().getOpposite()), 18);
            var inwardDoor = DockingPorts.firstPort(level, ship);
            ctx.assertTrue(inwardDoor.facing() == original.facing() && inwardDoor.anchors().equals(original.anchors()), "The Docking Base defines the exterior and cabin even when the door faces inward");
            var at = StationGameTest.boardingPoint(level, ship);
            player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
            var target = DockingPorts.ports(level, station).getFirst();
            var requested = DockingManeuver.request(player, ship, station.getStringOr("id", ""), target.controller().subtract(WorldStore.pos(station, "base")));
            ctx.assertTrue(requested.ready() && ship.contains("dock"), "An inward-facing ordinary door can start docking");
            WorldStore.get(server).ships().remove(station);
            DockingManeuver.tickMotion(server, ship);
            ctx.assertTrue(!ship.contains("dock") && ship.getStringOr("flightStatus", "").equals("Target unavailable; maneuver cancelled") && ship.getIntOr("thrustDemand", -1) == 0, "A failure after starting stops safely and retains the logged reason without an open" + " controller");
        }
        finally
        {
            server.getPlayerList().remove(player);
            WorldStore.get(server).ships().remove(ship);
            WorldStore.get(server).ships().remove(station);
        }
        ctx.succeed();
    }

    private static void pose(CompoundTag ship, ScenePose pose)
    {
        ship.putDouble("simX", pose.position().x);
        ship.putDouble("simY", pose.position().y);
        ship.putDouble("simZ", pose.position().z);
        var angles = pose.orientation().getEulerAnglesYXZ(new Vector3d());
        ship.putDouble("simYaw", Math.toDegrees(angles.y));
        ship.putDouble("simPitch", Math.toDegrees(angles.x));
        ship.putDouble("simRoll", Math.toDegrees(angles.z));
    }

    private static CompoundTag find(net.minecraft.server.MinecraftServer server, String id)
    {
        return WorldStore.get(server).repository().find(id);
    }

    /**
     * Every delivered hull docks for real at a station berth: it burns its engines and jets on the way in, clamps, and its blocks join the berth, turned to face
     * it and standing just outside the station's area, with the crew moved along. The docking arm is open to walk, walking across makes the player the other
     * vessel's crew (so the visitor cannot leave them behind), and undocking (or an emergency departure, every other hull) moves the hull to an area of its own
     * before it backs away.
     */
    /** The last docking sound the player was sent, the clamp or the release, or empty. */
    private static String lastDockSound(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerPlayer player)
    {
        String prefix = player.getPlainTextName() + " ";
        var log = ShipSounds.recent(server);
        for (int i = log.size() - 1; i >= 0; i--)
            if (log.get(i).startsWith(prefix + "dock."))
                return log.get(i).substring(prefix.length()).split(" ")[0];
        return "";
    }

    @GameTest(environment = "nullspace:docking_join", maxTicks = 6000)
    public void allDeliveredHullsClampJoinTheBerthAndBackAway(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var station = StationGameTest.fixture(level, new BlockPos(2200000, 96, 2200000), false);
        station.putDouble("simX", 3000);
        station.putDouble("simY", 1000);
        station.putDouble("simZ", -3000);
        station.putDouble("simYaw", 31);
        var target = DockingPorts.ports(level, station).getFirst();
        var player = ctx.makeMockServerPlayerInLevel();
        visit(ctx, level, station.getStringOr("id", ""), target, player, 0, () ->
        {
            StationGameTest.cleanup(level, find(server, station.getStringOr("id", "")));
            server.getPlayerList().remove(player);
            ctx.succeed();
        });
    }

    private static void visit(GameTestHelper ctx, net.minecraft.server.level.ServerLevel level, String stationId, DockingPorts.Port target, net.minecraft.server.level.ServerPlayer player, int i, Runnable done)
    {
        var server = level.getServer();
        if (i >= ShipBlueprints.PRESETS.size())
        {
            done.run();
            return;
        }
        var preset = ShipBlueprints.PRESETS.get(i);
        var station = find(server, stationId);
        var base = new BlockPos(2201024 + i * 128, 96, 2200000);
        var ship = new CompoundTag();
        String id = UUID.randomUUID().toString();
        ship.putString("id", id);
        ship.putString("home", preset.name());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putString("localSpace", "sol_terra");
        ship.putLong("base", base.asLong());
        ship.putLong("controller", base.offset(preset.helm()).asLong());
        ship.putInt("width", 32);
        ship.putInt("depth", 32);
        ship.putInt("height", 32);
        // The player flies it: they are its crew.
        var crew = new ListTag();
        var member = new CompoundTag();
        member.putString("id", player.getUUID().toString());
        crew.add(member);
        ship.put("crew", crew);
        WorldStore.get(server).ships().add(ship);
        for (var e : ShipBlueprints.blocks(preset).entrySet())
            level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
        for (var p : ShipBlueprints.blocks(preset).keySet())
            FuelSystem.provision(level, base.offset(p), 64);
        SystemsUpgrade.upgrade(level, ship);
        ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
        ShipSystems.tick(level, ship);
        var helm = WorldStore.pos(ship, "controller");
        var front = helm.relative(level.getBlockState(helm).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING));
        player.teleportTo(level, front.getX() + .5, front.getY(), front.getZ() + .5, Set.of(), 0, 0, true);
        var ready = DockingManeuver.request(player, ship, stationId, target.controller().subtract(WorldStore.pos(station, "base")));
        ctx.assertTrue(ready.ready(), preset.name() + ": " + ready.message());
        var source = DockingPorts.firstPort(level, ship);
        var d = ship.getCompoundOrEmpty("dock");
        int gap = (int) Math.round(d.getDoubleOr("gap", -1));
        ctx.assertTrue(d.getBooleanOr("join", false) && gap >= 0 && gap == d.getDoubleOr("gap", -1), preset.name() + " plans a joined dock with a whole-block arm: " + d);
        var goal = DockingManeuver.matingPose(ship, source, station, target, gap);
        var normal = SceneService.pose(station).toUniverse(DockingMotion.normal(target.facing())).subtract(SceneService.pose(station).position());
        pose(ship, new ScenePose(goal.position().add(normal.scale(2)), goal.orientation()));
        d.putString("state", "CONNECTING");
        // The first step in: the engines burn (their plume and hum) and a maneuvering jet puffs, heard through the hull.
        DockingManeuver.tickMotion(server, ship);
        ctx.assertTrue(ship.getIntOr("thrustDemand", 0) > 0 && !EngineEffects.exhausts(level, ship).isEmpty() && EngineSounds.hum(level, ship) != null, preset.name() + " burns its engines as it closes in: demand " + ship.getIntOr("thrustDemand", 0) + ", " + ship.getStringOr("flightStatus", ""));
        ctx.assertTrue(ShipSounds.recent(server).getLast().contains("rcs.puff"), preset.name() + " fires a maneuvering jet: " + ShipSounds.recent(server));
        for (int step = 0; step < 10 && !ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"); step++)
            DockingManeuver.tickMotion(server, ship);
        ctx.assertTrue(ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"), preset.name() + " clamps: " + ship.getStringOr("flightStatus", ""));
        ctx.assertTrue(ship.getIntOr("thrustDemand", 0) == 0, preset.name() + " shuts its engines once clamped");
        // The clamp waits for the hull to join the berth: played now, the move behind the loading screen would leave it behind.
        ctx.assertTrue(ship.getCompoundOrEmpty("dock").getBooleanOr("clampSound", false) && !lastDockSound(server, player).equals("dock.clamp"), preset.name() + " holds its clamp sound until it has joined: " + ShipSounds.recent(server));
        // Clamped, it asks to join; the mock player has no client to show the fade, so the move goes ahead at once.
        DockingManeuver.tickMotion(server, ship);
        TestWaits.until(ctx, () -> find(server, id) != null && find(server, id).getCompoundOrEmpty("dock").getBooleanOr("joined", false) && !TransferLocks.busy(server, find(server, id)) || find(server, id) != null && !find(server, id).getCompoundOrEmpty("dock").getBooleanOr("join", true), () ->
        {
            var visitor = find(server, id);
            var host = find(server, stationId);
            var dock = visitor.getCompoundOrEmpty("dock");
            ctx.assertTrue(dock.getBooleanOr("joined", false), preset.name() + " joins the berth: " + visitor.getStringOr("flightStatus", ""));
            DockingManeuver.tickMotion(server, visitor);
            ctx.assertTrue(!visitor.getCompoundOrEmpty("dock").contains("clampSound") && lastDockSound(server, player).equals("dock.clamp"), preset.name() + "'s crew hear the clamp beside the berth: " + ShipSounds.recent(server));
            var area = WorldStore.bounds(visitor).box();
            var hostArea = WorldStore.bounds(host).box();
            ctx.assertTrue(!area.intersects(hostArea) && area.inflate(.5).intersects(hostArea), preset.name() + " stands just outside the station's area: " + area + " beside " + hostArea);
            ctx.assertTrue(visitor.getIntOr("width", 32) <= 32 && visitor.getIntOr("depth", 32) <= 32, preset.name() + "'s docked area fits its hull");
            var hostPose = SceneService.pose(host);
            var visitorPose = SceneService.pose(visitor);
            ctx.assertTrue(Math.abs(visitorPose.orientation().dot(hostPose.orientation())) > .999999, preset.name() + " shares the station's orientation, so its blocks sit as the simulation shows them");
            ctx.assertTrue(hostPose.toUniverse(net.minecraft.world.phys.Vec3.atLowerCornerOf(WorldStore.pos(visitor, "base").subtract(WorldStore.pos(host, "base")))).distanceTo(visitorPose.position()) < 1e-6, preset.name() + "'s simulated place is where its blocks are");
            ctx.assertTrue(area.contains(player.position()), preset.name() + " carries its crew to the berth: " + player.position());
            var berth = DockingPorts.readPort(level, host, target.controller(), false);
            var port = DockingPorts.readPort(level, visitor, WorldStore.pos(visitor, "base").offset(WorldStore.pos(dock, "sourcePort")), false);
            ctx.assertTrue(port.facing() == berth.facing().getOpposite() && port.outer().equals(berth.outer().relative(berth.facing(), gap + 1)), preset.name() + "'s door faces the berth's door across the arm: " + port.outer() + " and " + berth.outer());
            DockingManeuver.tickMotion(server, visitor);
            com.nullandkale.nullspace.gameplay.travel.BuildJournal.flushPending(server);
            for (int k = 1; k <= gap; k++)
            {
                var cell = berth.outer().relative(berth.facing(), k);
                ctx.assertTrue(level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir() && !level.getBlockState(cell.below()).isAir() && DockingTunnel.walkway(level, cell), preset.name() + "'s docking arm is open and floored at " + cell);
            }
            ShipDoors.setOpen(level, port.outer(), true);
            ShipDoors.setOpen(level, berth.outer(), true);
            var inside = berth.anchors().getFirst();
            player.teleportTo(inside.getX() + .5, inside.getY(), inside.getZ() + .5);
            TestWaits.afterTicks(ctx, 10, () ->
            {
                var nowHost = find(server, stationId);
                ctx.assertTrue(nowHost.getListOrEmpty("crew").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("id", "").equals(player.getUUID().toString()) && t.getStringOr("returnShip", "").equals(id)), preset.name() + ": walking into the station makes the player its crew, remembering their ship");
                boolean held = false;
                try
                {
                    DockingService.perform(player, find(server, id), WorldStore.pos(find(server, id), "controller"), "DOCK_UNDOCK");
                }
                catch (IllegalArgumentException expected)
                {
                    held = expected.getMessage().contains("Recall crew");
                }
                ctx.assertTrue(held, preset.name() + " cannot undock and leave its crew on the station");
                var home = port.anchors().getFirst();
                player.teleportTo(home.getX() + .5, home.getY(), home.getZ() + .5);
                TestWaits.afterTicks(ctx, 10, () ->
                {
                    var current = find(server, id);
                    ctx.assertTrue(current.getListOrEmpty("crew").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString())), preset.name() + ": walking back makes the player its crew again");
                    boolean emergency = i % 2 == 1;
                    DockingService.perform(player, current, WorldStore.pos(current, "controller"), emergency ? "DOCK_EMERGENCY" : "DOCK_UNDOCK");
                    ctx.assertTrue(current.getCompoundOrEmpty("dock").getStringOr("state", "").equals("SEPARATING"), preset.name() + " separates from the berth before backing away");
                    var joinedAt = SceneService.pose(current).position();
                    TestWaits.until(ctx, () -> !find(server, id).getCompoundOrEmpty("dock").getBooleanOr("joined", true) && !TransferLocks.busy(server, find(server, id)) || find(server, id).getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"), () ->
                    {
                        var apart = find(server, id);
                        var gone = apart.getCompoundOrEmpty("dock");
                        DockingManeuver.tickMotion(server, apart);
                        ctx.assertTrue(gone.getStringOr("state", "").equals(emergency ? "EMERGENCY_BACKOFF" : "UNDOCKING"), preset.name() + " leaves the berth: " + gone + " " + apart.getStringOr("flightStatus", ""));
                        ctx.assertTrue(!gone.contains("releaseSound") && lastDockSound(server, player).equals("dock.release"), preset.name() + "'s crew hear the release once they are back in their own area: " + ShipSounds.recent(server));
                        var own = WorldStore.pos(apart, "base");
                        ctx.assertTrue(apart.getIntOr("width", 0) == 32 && own.getX() % TravelService.SPACE_SPACING == 0 && own.getZ() == 0 && own.getY() == 96, preset.name() + " is back in an area of its own: " + own);
                        ctx.assertTrue(WorldStore.bounds(apart).box().contains(player.position()), preset.name() + " carries its crew away: " + player.position());
                        ctx.assertTrue(!DockingTunnel.walkway(level, berth.outer().relative(berth.facing())), preset.name() + "'s docking arm is retracted");
                        for (int step = 0; step < 8; step++)
                            DockingManeuver.tickMotion(server, apart);
                        ctx.assertTrue(SceneService.pose(apart).position().distanceTo(joinedAt) > 1, preset.name() + " backs away without collision");
                        ctx.assertTrue(apart.getIntOr("thrustDemand", 0) > 0, preset.name() + " burns its engines to back away");
                        DockingService.perform(player, apart, WorldStore.pos(apart, "controller"), "DOCK_CANCEL");
                        StationGameTest.cleanup(level, apart);
                        visit(ctx, level, stationId, target, player, i + 1, done);
                    });
                });
            });
        });
    }
}
