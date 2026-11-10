package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenu;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.gameplay.docking.Boarding;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.docking.StationConstruction;
import com.nullandkale.nullspace.gameplay.navigation.ControllerChecks;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.ownership.Claims;
import com.nullandkale.nullspace.gameplay.ownership.ShipTitles;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

public final class StationGameTest
{
    @GameTest(environment = "nullspace:stations")
    public void stationCoreStartsFromSneakBlockOrAirAndConsumesTheUsedHandOnly(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var ship = fixture(level, new BlockPos(760000, 96, 760000), true);
        var player = survivalPlayer(ctx);
        var at = boardingPoint(level, ship);
        player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModBlocks.STATION_CORE, 2));
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(ModBlocks.STATION_CORE));
        var floor = player.blockPosition().below();
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false);
        var existing = WorldStore.get(server).ships().stream().map(t -> ((CompoundTag) t).getStringOr("id", "")).collect(java.util.stream.Collectors.toSet());
        try
        {
            ctx.assertTrue(StationDeployment.stationStartTarget(level, floor), "Ordinary hull/floor blocks can start a station");
            ctx.assertTrue(!StationDeployment.stationStartTarget(level, WorldStore.pos(ship, "controller")), "Interactive controllers are excluded");
            var callback = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker();
            callback.interact(player, level, net.minecraft.world.InteractionHand.OFF_HAND, hit);
            // Count only this player's stations: other tests add ships to the shared store meanwhile.
            String owner = player.getUUID().toString();
            java.util.function.LongSupplier started = () -> WorldStore.get(server).ships().stream().map(t -> (CompoundTag) t).filter(t -> !existing.contains(t.getStringOr("id", "")) && t.getStringOr("owner", "").equals(owner)).count();
            ctx.assertTrue(started.getAsLong() == 0, "Ordinary right click does not start a station");
            player.setShiftKeyDown(true);
            callback.interact(player, level, net.minecraft.world.InteractionHand.OFF_HAND, hit);
            ctx.assertTrue(started.getAsLong() == 1, "Shift-right-click on a non-interactive block starts construction");
            ctx.assertTrue(player.getOffhandItem().isEmpty() && player.getMainHandItem().getCount() == 2, "Consume the core in the hand actually used, not another stack");
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(.1);
            player.setXRot(0);
            ctx.assertTrue(player.pick(player.blockInteractionRange(), 0, false).getType() == net.minecraft.world.phys.HitResult.Type.MISS, "Air interaction fixture targets empty space within reach");
            net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker().interact(player, level, net.minecraft.world.InteractionHand.MAIN_HAND);
            ctx.assertTrue(started.getAsLong() == 2 && player.getMainHandItem().getCount() == 1, "Shift-right-click in empty space starts construction and consumes one core");
            ctx.succeed();
        }
        finally
        {
            server.getPlayerList().remove(player);
            WorldStore.get(server).ships().removeIf(t -> !existing.contains(((CompoundTag) t).getStringOr("id", "")) && ((CompoundTag) t).getStringOr("owner", "").equals(player.getUUID().toString()) || t == ship);
        }
    }

    private static net.minecraft.server.level.ServerPlayer survivalPlayer(GameTestHelper ctx)
    {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "station-builder"), false);
        var player = new net.minecraft.server.level.ServerPlayer(ctx.getLevel().getServer(), ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    private static void cleanup(ServerLevel level, CompoundTag s)
    {
        // A docked ship's boarding collar stands outside both vessels' bounds: take it down first, or it outlives them.
        if (s.contains("dock"))
            com.nullandkale.nullspace.gameplay.docking.DockingTunnel.retract(level.getServer(), s.getCompoundOrEmpty("dock"));
        WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(s.getStringOr("id", "")));
        for (var pos : BlockPos.betweenClosed(WorldStore.bounds(s).min(), WorldStore.bounds(s).max()))
            if (!level.getBlockState(pos).isAir())
            {
                level.removeBlockEntity(pos);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
            }
    }

    public static CompoundTag fixture(ServerLevel level, BlockPos base, boolean ship)
    {
        StationBlueprint.Plan plan;
        if (ship)
        {
            var preset = ShipBlueprints.get("rivet_bolt");
            plan = new StationBlueprint.Plan(ShipBlueprints.blocks(preset), preset.helm(), List.of());
        }
        else
            plan = StationBlueprint.build(StationLayout.waypoint());
        var s = new CompoundTag();
        s.putString("id", UUID.randomUUID().toString());
        s.putString("dimension", level.dimension().identifier().toString());
        s.putLong("base", base.asLong());
        s.putLong("controller", base.offset(plan.core()).asLong());
        s.putString("home", ship ? "Test ship" : "Test station");
        s.putString("localSpace", "sol_terra");
        s.putString("kind", ship ? "ship" : "station");
        s.putString("access", "public");
        s.put("crew", new ListTag());
        s.putDouble("simX", ship ? -2800 : -3000);
        s.putDouble("simZ", -2800);
        WorldStore.get(level.getServer()).ships().add(s);
        for (var e : plan.blocks().entrySet())
            level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
        for (var e : plan.blocks().entrySet())
            if (e.getValue().is(ModBlocks.GENERATOR))
                FuelSystem.provision(level, base.offset(e.getKey()), 64);
        SystemsUpgrade.upgrade(level, s);
        s.getCompoundOrEmpty("systems").putIntArray("requests", ship ? new int[]{1, 4, 1, 3, 2, 0} : new int[]{1, 4, 1, 0, 2, 0});
        ShipSystems.tick(level, s);
        return s;
    }

    public static Vec3 boardingPoint(ServerLevel level, CompoundTag s)
    {
        var helm = WorldStore.pos(s, "controller");
        var at = helm.relative(level.getBlockState(helm).getValue(HorizontalDirectionalBlock.FACING));
        return new Vec3(at.getX() + .5, at.getY(), at.getZ() + .5);
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 60)
    public void unsuitedCrewMayOpenAnyDoorButShipsWithoutSystemsCannotTakeOff(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var ship = fixture(level, new BlockPos(284000, 96, 280000), true);
        var player = survivalPlayer(ctx);
        var at = boardingPoint(level, ship);
        player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
        try
        {
            ctx.assertFalse(LifeSupport.suited(player), "The player wears no suit");
            var port = DockingPorts.ports(level, ship).getFirst();
            DockingPorts.useCompactDoor(player, port.outer());
            ctx.assertTrue(ShipDoors.open(level.getBlockState(port.outer())), "Without a suit you can still open an exterior door to vacuum");
            ShipDoors.setOpen(level, port.outer(), false);
            player.teleportTo(ctx.getLevel(), at.x, 120, at.z, Set.of(), 0, 0, true);
            var systems = ship.getCompoundOrEmpty("systems").copy();
            ship.remove("systems");
            ctx.assertTrue(ControllerChecks.reason(player, ship, null, ShipControl.Action.LAUNCH).equals(ControllerChecks.NO_SYSTEMS), "A ship without set-up systems has no air and cannot take off");
            ship.put("systems", systems);
            ctx.assertFalse(ControllerChecks.reason(player, ship, null, ShipControl.Action.LAUNCH).equals(ControllerChecks.NO_SYSTEMS), "Once systems are set up, the systems rule no longer blocks take-off");
            ctx.succeed();
        }
        finally
        {
            server.getPlayerList().remove(player);
            cleanup(level, ship);
        }
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 100)
    public void dockingDistinguishesMissingSystemsLowCabinAirAndOutsideCrew(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(280000, 96, 280000);
        var ship = fixture(level, base, true);
        var player = survivalPlayer(ctx);
        var at = boardingPoint(level, ship);
        player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
        try
        {
            var systems = ship.getCompoundOrEmpty("systems").copy();
            ship.remove("systems");
            var legacy = DockingManeuver.readiness(player, ship);
            ctx.assertTrue(!legacy.ready() && legacy.message().contains("systems record") && !legacy.message().contains("Recall"), "Missing saved systems does not falsely accuse recalled crew of being outside");
            ship.put("systems", systems);
            var replacement = ship.copy();
            int[] air = replacement.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
            var eye = BlockPos.containing(player.getEyePosition()).subtract(base);
            air[Atmosphere.index(eye.getX(), eye.getY(), eye.getZ())] = 100;
            replacement.getCompoundOrEmpty("systems").putIntArray("air", air);
            // Publish a new authoritative record, just as recovery does, so no cached air can mask the
            // check.
            WorldStore.get(server).ships().remove(ship);
            WorldStore.get(server).ships().add(replacement);
            var low = DockingManeuver.readiness(player, replacement);
            ctx.assertTrue(!low.ready() && low.message().contains("is aboard") && low.message().contains("10%") && low.message().contains(player.getPlainTextName()), "An aboard player gets an exact oxygen diagnostic");
            air[Atmosphere.index(eye.getX(), eye.getY(), eye.getZ())] = Atmosphere.FULL;
            ctx.assertTrue(DockingManeuver.readiness(player, replacement).ready(), "Recalled crew in a breathable cabin can dock");
            player.teleportTo(base.getX() - 3, base.getY() + 1, base.getZ() + 4);
            var outside = DockingManeuver.readiness(player, replacement);
            ctx.assertTrue(!outside.ready() && outside.message().contains("outside a sealed cabin") && outside.message().contains(player.getPlainTextName()), "Actual EVA crew are named and remain blocked");
            ctx.succeed();
        }
        finally
        {
            server.getPlayerList().remove(player);
            cleanup(level, ship);
        }
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 100)
    public void waypointHasPoweredAirlocksAndConnectedDoorGraph(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(61000, 64, 61000));
        var s = fixture(level, base, false);
        try
        {
            var ports = DockingPorts.ports(level, s);
            ctx.assertTrue(ports.size() == 2, "Completed station has two valid berths");
            for (var port : ports)
            {
                DockingPorts.readPort(level, s, port.controller(), true);
                ctx.assertTrue(ShipAir.breathable(level, s, port.anchors().getFirst().above()), "Docking chamber starts with finite air");
            }
            var graph = StationGraph.inspect(level, s);
            ctx.assertTrue(graph.nodes().size() == 18, "Sixteen ordinary internal doors (four joints, two slots on two stories) and two compact docking doors form graph nodes: " + graph.nodes().size());
            ctx.assertTrue(!graph.route(ports.getFirst().id(), ports.getLast().id()).isEmpty(), "Door graph reaches both docks");
            var saved = s.copy();
            ctx.assertTrue(StationGraph.inspect(level, s).nodes().stream().map(StationGraph.Node::id).toList().equals(graph.nodes().stream().map(StationGraph.Node::id).toList()), "Inspection preserves node identities");
            var broken = ports.getFirst().inner().above(2);
            level.setBlock(broken, Blocks.AIR.defaultBlockState(), 18);
            boolean rejected = false;
            try
            {
                DockingPorts.readPort(level, s, ports.getFirst().controller(), true);
            }
            catch (IllegalArgumentException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected, "Broken door frame prevents boarding, irrespective of saved graph");
            ctx.succeed();
        }
        finally
        {
            cleanup(level, s);
        }
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 100)
    public void stationPermissionsAndTerminalActionsAreServerOwned(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(65000, 64, 65000));
        var s = fixture(level, base, false);
        var p = ctx.makeMockServerPlayerInLevel();
        var other = ctx.makeMockServerPlayerInLevel();
        try
        {
            s.putString("owner", p.getUUID().toString());
            p.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 5.5);
            other.teleportTo(p.getX(), p.getY(), p.getZ());
            ctx.assertTrue(StationAccess.mayEdit(p, base.offset(4, 3, 4)) && !StationAccess.mayEdit(other, base.offset(4, 3, 4)), "Public docking does not grant edit rights");
            var open = ComputerTerminal.issue(p, WorldStore.pos(s, "controller"), "", "station_core", "");
            boolean denied = false;
            try
            {
                ComputerTerminal.authorize(other, new TerminalPackets.Request(open.session(), ComputerTerminal.Action.STATION_GENERATE, "", ""));
            }
            catch (IllegalArgumentException expected)
            {
                denied = true;
            }
            ctx.assertTrue(denied, "Another player cannot reuse a station terminal session");
            denied = false;
            try
            {
                ComputerTerminal.authorize(p, new TerminalPackets.Request(open.session(), ComputerTerminal.Action.LAUNCH, "", ""));
            }
            catch (IllegalArgumentException expected)
            {
                denied = true;
            }
            ctx.assertTrue(denied, "Station terminals never authorize launch");
            var ports = DockingPorts.ports(level, s);
            var port = ports.getFirst();
            ctx.assertTrue(StationAccess.mayUse(other, port.controller()) && StationAccess.mayUse(other, port.outer()), "Public visitors can inspect and operate compact ports");
            var bulkhead = StationGraph.inspect(level, s).nodes().stream().filter(n -> !n.dock()).findFirst().orElseThrow().door();
            ctx.assertTrue(StationAccess.useDoor(other, bulkhead) && ShipDoors.open(level.getBlockState(bulkhead)), "Ordinary station iron doors operate directly without buttons");
            ShipDoors.setOpen(level, bulkhead, false);
            var visitor = new CompoundTag();
            visitor.putString("id", other.getUUID().toString());
            visitor.putString("returnShip", "visitors-home-ship");
            s.getListOrEmpty("crew").add(visitor);
            s.putString("access", "private");
            ctx.assertTrue(!StationAccess.allowed(other, s) && StationAccess.mayUse(other, port.controller()) && StationAccess.mayUse(other, port.outer()), "Revoking public access preserves a visitor's return door");
            DockingPorts.useCompactDoor(other, port.outer());
            ctx.assertTrue(ShipDoors.open(level.getBlockState(port.outer())), "Returning visitors can still open the compact dock door");
            var menu = new MachineMenu(1, other.getInventory(), (MachineBlockEntity) level.getBlockEntity(port.controller()));
            menu.broadcastChanges();
            ctx.assertTrue(!menu.reading.getBooleanOr("canConfigure", true), "Visitor port panels are read-only");
            menu.edit(other, 0, "");
            ctx.assertTrue(ShipSystems.active(s, port.controller()), "Visitors cannot disable station docking equipment");
            ctx.succeed();
        }
        finally
        {
            level.getServer().getPlayerList().remove(p);
            level.getServer().getPlayerList().remove(other);
            cleanup(level, s);
        }
    }

    /** A derelict for claim tests: a waypoint layout recorded as unclaimed salvage. */
    private static CompoundTag derelict(ServerLevel level, BlockPos base)
    {
        var s = fixture(level, base, false);
        s.putBoolean("salvage", true);
        s.putInt("encounterKind", 1);
        s.putString("owner", "");
        return s;
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 2200)
    public void claimingADerelictYourShipIsDockedToThenUndockingSeparatesYourShip(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var origin = ctx.absolutePos(new BlockPos(75000, 96, 75000));
        var a = new BlockPos(origin.getX(), 96, origin.getZ());
        var b = a.offset(512, 0, 0);
        var ship = fixture(level, a, true);
        var wreck = derelict(level, b);
        // Away from the other docking tests' scene positions, which run at the same time.
        for (var s : List.of(ship, wreck))
            s.putDouble("simY", s.getDoubleOr("simY", 0) + 3000);
        var wreckBase = WorldStore.pos(wreck, "base");
        var p = ctx.makeMockServerPlayerInLevel();
        var start = boardingPoint(level, ship);
        p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
        var target = DockingPorts.ports(level, wreck).stream().filter(port -> port.facing() == Direction.EAST).findFirst().orElseThrow();
        DockingManeuver.request(p, ship, wreck.getStringOr("id", ""), target.controller().subtract(b));
        ctx.runAfterDelay(1000, () ->
        {
            var own = WorldStore.get(server).repository().find(ship.getStringOr("id", ""));
            var found = WorldStore.get(server).repository().find(wreck.getStringOr("id", ""));
            ctx.assertTrue(own.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"), "Your ship is docked to the derelict: " + own.getStringOr("flightStatus", "") + " / " + own.getCompoundOrEmpty("dock").getStringOr("state", "none"));
            var center = WorldStore.bounds(found).box().getCenter();
            p.teleportTo(level, center.x, center.y, center.z, Set.of(), 0, 0, true);
            String claimed = Claims.claim(p, found);
            ctx.assertTrue(claimed.startsWith("You claimed"), "Claim while docked: " + claimed);
            p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
            DockingService.action(p, own, WorldStore.pos(own, "controller"), "DOCK_UNDOCK");
            ctx.runAfterDelay(400, () ->
            {
                try
                {
                    var after = WorldStore.get(server).repository().find(ship.getStringOr("id", ""));
                    var prize = WorldStore.get(server).repository().find(wreck.getStringOr("id", ""));
                    ctx.assertTrue(!after.contains("dock") && prize.getCompoundOrEmpty("dockLinks").isEmpty(), "Undocking releases the berth");
                    ctx.assertTrue(WorldStore.pos(prize, "base").equals(wreckBase) && prize.getStringOr("owner", "").equals(p.getUUID().toString()), "Your own ship backs away; the claimed ship stays where it was, still yours");
                    ctx.succeed();
                }
                finally
                {
                    server.getPlayerList().remove(p);
                    cleanup(level, ship);
                    cleanup(level, wreck);
                }
            });
        });
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 100)
    public void aDerelictWithADestroyedControllerIsClaimedThroughAPlacedShipController(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var wreck = derelict(level, new BlockPos(288000, 96, 280000));
        var p = survivalPlayer(ctx);
        try
        {
            var core = WorldStore.pos(wreck, "controller");
            var facing = level.getBlockState(core).getValue(HorizontalDirectionalBlock.FACING);
            level.setBlock(core, Blocks.AIR.defaultBlockState(), 3);
            ctx.assertTrue(Claims.damaged(level, wreck), "Without its core the derelict is damaged");
            var center = Vec3.atBottomCenterOf(core.relative(facing));
            p.teleportTo(level, center.x, center.y, center.z, Set.of(), 0, 0, true);
            ctx.assertTrue(Claims.claim(p, wreck).contains("Place a Ship Controller aboard"), "Claiming a damaged derelict explains how to claim the wreck");
            level.setBlock(core, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing), 3);
            ShipControl.open(p, core, "");
            ctx.assertTrue(wreck.getStringOr("owner", "").equals(p.getUUID().toString()) && wreck.getStringOr("kind", "").equals("ship") && ShipTitles.holds(p, wreck), "Opening a Ship Controller placed aboard claims the wreck");
            ctx.succeed();
        }
        finally
        {
            server.getPlayerList().remove(p);
            cleanup(level, wreck);
        }
    }

    @GameTest(environment = "nullspace:stations", maxTicks = 2200)
    public void automaticDockBoardReturnAndUndock(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var origin = ctx.absolutePos(new BlockPos(71000, 96, 71000));
        var a = new BlockPos(origin.getX(), 96, origin.getZ());
        var b = a.offset(512, 0, 0);
        var ship = fixture(level, a, true);
        var station = fixture(level, b, false);
        var p = ctx.makeMockServerPlayerInLevel();
        var start = boardingPoint(level, ship);
        p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
        p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
        var source = DockingPorts.firstPort(level, ship);
        var target = DockingPorts.ports(level, station).stream().filter(port -> port.facing() == Direction.EAST).findFirst().orElseThrow();
        DockingManeuver.request(p, ship, station.getStringOr("id", ""), target.controller().subtract(b));
        ctx.assertTrue(DockingService.motionLocked(ship), "Autopilot immediately owns all movement");
        ctx.runAfterDelay(1000, () ->
        {
            var current = WorldStore.get(server).repository().find(ship.getStringOr("id", ""));
            ctx.assertTrue(current != null && current.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"), "Autopilot must capture without any manual input: " + (current == null ? "record missing" : current.getStringOr("flightStatus", "") + " " + current.getCompoundOrEmpty("dock").getStringOr("state", "") + " pose " + current.getDoubleOr("simX", 0)));
            var anchor = source.anchors().getFirst();
            p.teleportTo(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5);
            p.setYRot(source.facing().toYRot() + 17);
            p.setXRot(-23);
            var outboundLook = FlightDynamics.rotate(SceneService.pose(current).orientation(), p.getLookAngle());
            DockingService.action(p, current, source.controller(), "DOCK_BOARD");
            var intent = (CompoundTag) WorldStore.get(server).list("stationBoarding").getFirst();
            Boarding.ready(p, new TravelPackets.Ready(UUID.fromString(intent.getStringOr("token", "")), false));
            ctx.runAfterDelay(10, () ->
            {
                ctx.assertTrue(WorldStore.bounds(station).contains(p.blockPosition()), "Passenger boards actual station blocks");
                ctx.assertTrue(FlightDynamics.rotate(SceneService.pose(station).orientation(), p.getLookAngle()).distanceTo(outboundLook) < 3e-4, "Boarding keeps the passenger looking through the dock, including their head" + " tilt");
                Boarding.ready(p, new TravelPackets.Ready(UUID.fromString(intent.getStringOr("token", "")), true));
                ctx.runAfterDelay(10, () ->
                {
                    ctx.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 7, "Boarding preserves inventory exactly once");
                    var dest = target.anchors().getFirst();
                    p.teleportTo(dest.getX() + .5, dest.getY(), dest.getZ() + .5);
                    p.setYRot(target.facing().toYRot() - 11);
                    p.setXRot(18);
                    var returnLook = FlightDynamics.rotate(SceneService.pose(station).orientation(), p.getLookAngle());
                    DockingService.action(p, WorldStore.get(server).repository().find(station.getStringOr("id", "")), target.controller(), "DOCK_BOARD");
                    var back = (CompoundTag) WorldStore.get(server).list("stationBoarding").getFirst();
                    Boarding.ready(p, new TravelPackets.Ready(UUID.fromString(back.getStringOr("token", "")), false));
                    ctx.runAfterDelay(10, () ->
                    {
                        ctx.assertTrue(WorldStore.bounds(ship).contains(p.blockPosition()), "Return boards the original ship");
                        ctx.assertTrue(FlightDynamics.rotate(SceneService.pose(current).orientation(), p.getLookAngle()).distanceTo(returnLook) < 3e-4, "Returning preserves the view in the original ship's frame too");
                        Boarding.ready(p, new TravelPackets.Ready(UUID.fromString(back.getStringOr("token", "")), true));
                        ctx.runAfterDelay(10, () ->
                        {
                            var unpoweredTarget = WorldStore.get(server).repository().find(station.getStringOr("id", ""));
                            unpoweredTarget.getCompoundOrEmpty("systems").putIntArray("requests", new int[6]);
                            ShipSystems.tick(level, unpoweredTarget);
                            ctx.assertTrue(ShipSystems.power(level, unpoweredTarget, PowerBus.System.CONTROLS) == 0, "The target is unpowered before departure");
                            DockingService.action(p, WorldStore.get(server).repository().find(ship.getStringOr("id", "")), WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
                            ctx.runAfterDelay(400, () ->
                            {
                                try
                                {
                                    ctx.assertTrue(!WorldStore.get(server).repository().find(ship.getStringOr("id", "")).contains("dock") && WorldStore.get(server).repository().find(station.getStringOr("id", "")).getCompoundOrEmpty("dockLinks").isEmpty(), "Automatic undocking releases the berth after backing" + " away");
                                    ctx.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 7, "Return never clones cargo");
                                    ctx.succeed();
                                }
                                finally
                                {
                                    server.getPlayerList().remove(p);
                                    cleanup(level, ship);
                                    cleanup(level, station);
                                }
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(environment = "nullspace:station_building", maxTicks = 160)
    public void stationDeploymentIsBoundedIdempotentAndPreservesEdits(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        String id = UUID.randomUUID().toString();
        var plan = StationBlueprint.build(StationLayout.waypoint());
        var station = StationBuilds.queue(server, id, "Saved station", "sol_luna", new Vec3(-2000, 0, -2000), "", plan, false);
        ctx.assertTrue(StationBuilds.queue(server, id, "Saved station", "sol_luna", new Vec3(0, 0, 0), "", plan, false) == station, "A repeated deployment reuses its reserved identity and section");
        ctx.runAfterDelay(55, () ->
        {
            try
            {
                ctx.assertTrue(StationAccess.ready(station), "Bounded placement publishes a completed station");
                var level = server.getLevel(TravelService.SPACE);
                var base = WorldStore.pos(station, "base");
                ctx.assertTrue(DockingPorts.ports(level, station).size() == 2, "Saved deployment commissions both ports");
                var edited = base.offset(1, 7, 1);
                level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                StationBuilds.queue(server, id, "Saved station", "sol_luna", Vec3.ZERO, "", plan, false);
                ctx.assertTrue(level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK), "Revisiting never repairs over station edits");
                ctx.succeed();
            }
            finally
            {
                cleanup(server.getLevel(TravelService.SPACE), station);
            }
        });
    }

    @GameTest(environment = "nullspace:station_cancel", maxTicks = 1800)
    public void cancelledFinalApproachCanAutomaticallyRetry(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(245000, 96, 245000);
        var ship = fixture(level, base, true);
        var station = fixture(level, base.offset(512, 0, 0), false);
        var p = ctx.makeMockServerPlayerInLevel();
        var point = boardingPoint(level, ship);
        p.teleportTo(level, point.x, point.y, point.z, Set.of(), 0, 0, true);
        var target = DockingPorts.ports(level, station).stream().filter(port -> port.facing() == Direction.EAST).findFirst().orElseThrow();
        var port = target.controller().subtract(WorldStore.pos(station, "base"));
        DockingManeuver.request(p, ship, station.getStringOr("id", ""), port);
        boolean[] cancelled = {false};
        int[] polls = {0};
        Runnable[] poll = new Runnable[1];
        poll[0] = () ->
        {
            var phase = ship.getCompoundOrEmpty("dock").getStringOr("state", "");
            double distance = SceneService.pose(ship).toUniverse(new Vec3(16, 16, 16)).distanceTo(SceneService.pose(station).toUniverse(new Vec3(16, 16, 16)));
            if (++polls[0] > 300 || phase.isEmpty() || phase.equals("CLAMPED") && !cancelled[0])
            {
                String error = "Cancel/retry failed: cancelled=" + cancelled[0] + " phase=" + phase + " distance=" + distance + " status=" + ship.getStringOr("flightStatus", "");
                server.getPlayerList().remove(p);
                cleanup(level, ship);
                cleanup(level, station);
                ctx.assertTrue(false, error);
                return;
            }
            if (!cancelled[0] && phase.equals("CONNECTING") && distance < 50)
            {
                DockingService.action(p, ship, WorldStore.pos(ship, "controller"), "DOCK_CANCEL");
                cancelled[0] = true;
                DockingManeuver.request(p, ship, station.getStringOr("id", ""), port);
            }
            if (cancelled[0] && phase.equals("CLAMPED"))
            {
                server.getPlayerList().remove(p);
                cleanup(level, ship);
                cleanup(level, station);
                ctx.succeed();
            }
            else
                ctx.runAfterDelay(5, () -> poll[0].run());
        };
        ctx.runAfterDelay(5, poll[0]);
    }

    @GameTest(environment = "nullspace:station_consent", maxTicks = 120)
    public void mobileShipDockingRequiresConsentAndRejectsReservationRaces(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(265000, 96, 265000);
        var a = fixture(level, base, true);
        var b = fixture(level, base.offset(512, 0, 0), true);
        var c = fixture(level, base.offset(1024, 0, 0), true);
        b.putDouble("simX", -3000);
        c.putDouble("simX", -2600);
        var pa = ctx.makeMockServerPlayerInLevel();
        var pb = ctx.makeMockServerPlayerInLevel();
        var pc = ctx.makeMockServerPlayerInLevel();
        var players = List.of(pa, pb, pc);
        var ships = List.of(a, b, c);
        try
        {
            for (int i = 0; i < 3; i++)
            {
                var point = boardingPoint(level, ships.get(i));
                players.get(i).teleportTo(level, point.x, point.y, point.z, Set.of(), 0, 0, true);
            }
            var port = DockingPorts.firstPort(level, b).controller().subtract(WorldStore.pos(b, "base"));
            DockingManeuver.request(pa, a, b.getStringOr("id", ""), port);
            ctx.assertTrue(a.getCompoundOrEmpty("dock").getStringOr("state", "").equals("WAITING_CONSENT"), "Approach never starts before another vessel accepts");
            boolean rejected = false;
            try
            {
                DockingManeuver.request(pc, c, b.getStringOr("id", ""), port);
            }
            catch (IllegalArgumentException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected, "One berth cannot be reserved by two vessels");
            var helm = WorldStore.pos(b, "controller");
            var session = ComputerTerminal.issue(pb, helm, "Incoming request", "docks", "0");
            ComputerTerminal.authorize(pb, new TerminalPackets.Request(session.session(), ComputerTerminal.Action.DOCK_ACCEPT, "", ""));
            DockingService.action(pb, b, helm, "DOCK_ACCEPT");
            ctx.assertTrue(a.getCompoundOrEmpty("dock").getStringOr("state", "").equals("APPROACHING"), "Target crew can accept from the helm without entering the locked airlock");
            DockingService.action(pa, a, WorldStore.pos(a, "controller"), "DOCK_CANCEL");
            ctx.assertTrue(!a.contains("dock") && !b.contains("dock") && b.getCompoundOrEmpty("dockLinks").isEmpty(), "Cancellation releases both movement and berth reservations");
            ctx.succeed();
        }
        finally
        {
            players.forEach(server.getPlayerList()::remove);
            ships.forEach(s -> cleanup(level, s));
        }
    }

    @GameTest(environment = "nullspace:station_construction", maxTicks = 1800)
    public void survivalConstructionStartsInSpaceAndBuildsConnectedModules(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(190000, 96, 190000);
        var ship = fixture(level, base, true);
        var p = survivalPlayer(ctx);
        var start = boardingPoint(level, ship);
        p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.getInventory().add(new ItemStack(ModBlocks.STATION_CORE));
        p.getInventory().add(new ItemStack(ModBlocks.STATION_MODULE, 4));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(ModBlocks.HELMET));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(ModBlocks.CHEST));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(ModBlocks.LEGS));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(ModBlocks.BOOTS));
        var station = StationDeployment.start(p, ship);
        var source = DockingPorts.firstPort(level, ship);
        ctx.runAfterDelay(1100, () ->
        {
            ctx.assertTrue(ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"), "Construction berth captures automatically: " + ship.getStringOr("flightStatus", ""));
            var anchor = source.anchors().getFirst();
            p.teleportTo(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5);
            DockingService.action(p, ship, source.controller(), "DOCK_CONSTRUCTION");
            var intent = (CompoundTag) WorldStore.get(server).list("stationBoarding").getFirst();
            var token = UUID.fromString(intent.getStringOr("token", ""));
            Boarding.ready(p, new TravelPackets.Ready(token, false));
            ctx.runAfterDelay(10, () ->
            {
                ctx.assertTrue(WorldStore.bounds(station).contains(p.blockPosition()) && StationConstruction.constructionAir(p, station), "Suited builder reaches the real platform with finite ship oxygen");
                Boarding.ready(p, new TravelPackets.Ready(token, true));
                ctx.runAfterDelay(10, () ->
                {
                    StationBuilds.buildRoom(p, station, "arrival 0 0", false);
                    ctx.runAfterDelay(35, () ->
                    {
                        ctx.assertTrue(StationAccess.ready(station), "Arrival module finishes without losing the construction return" + " route");
                        StationBuilds.buildRoom(p, station, "engineering 1 0", false);
                        ctx.runAfterDelay(35, () -> StationBuilds.buildRoom(p, station, "tunnel_straight 0 1", false));
                        ctx.runAfterDelay(70, () -> StationBuilds.buildRoom(p, station, "dome_garden 1 1", false));
                        ctx.runAfterDelay(105, () ->
                        {
                            try
                            {
                                var rooms = station.getCompoundOrEmpty("roomPieces");
                                ctx.assertTrue(rooms.getStringOr("0_1", "").equals("tunnel_straight") && rooms.getStringOr("1_1", "").equals("dome_garden"), "A tunnel and a dome are placed by doorway: " + rooms);
                                for (int x : new int[]{3, 12})
                                    for (int y : new int[]{2, 9})
                                        ctx.assertTrue(level.getBlockState(WorldStore.pos(station, "base").offset(x, y, 15)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && level.getBlockState(WorldStore.pos(station, "base").offset(x, y, 16)).isAir(), "The tunnel opens into the entrance through a door at x " + x + ", height " + y);
                                ctx.assertTrue(StationAccess.ready(station) && DockingPorts.ports(level, station).size() == 2, "Engineering joins the existing module through a valid" + " bulkhead");
                                ctx.assertTrue(ShipSystems.power(level, station, PowerBus.System.CONTROLS) > 0, "Station systems run without a helm or engines");
                                ctx.assertTrue(p.getInventory().countItem(ModBlocks.STATION_CORE.asItem()) == 0 && p.getInventory().countItem(ModBlocks.STATION_MODULE) == 0, "Core and module kits are consumed exactly once: core=" + p.getInventory().countItem(ModBlocks.STATION_CORE.asItem()) + " kits=" + p.getInventory().countItem(ModBlocks.STATION_MODULE) + " creative=" + p.isCreative());
                                var graph = StationGraph.inspect(level, station);
                                ctx.assertTrue(graph.nodes().size() >= 3, "Player-assembled rooms have real doorway nodes");
                                ctx.succeed();
                            }
                            finally
                            {
                                server.getPlayerList().remove(p);
                                cleanup(level, ship);
                                cleanup(level, station);
                            }
                        });
                    });
                });
            });
        });
    }
}
