package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.docking.DockingTargets;
import com.nullandkale.nullspace.gameplay.docking.StationConstruction;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.ownership.Claims;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;

import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/**
 * Station identity lives independently of its core. Deployment is a saved, replayable block plan.
 */
public final class StationService
{
    static final ServerContext.Key<Map<String, Integer>> PROGRESS = new ServerContext.Key<>();
    static final ThreadLocal<Boolean> PLACING = ThreadLocal.withInitial(() -> false);

    public static CompoundTag at(ServerPlayer p, BlockPos at)
    {
        if (!p.isAlive() || !p.level().hasChunkAt(at) || p.distanceToSqr(Vec3.atCenterOf(at)) > 64 || !p.mayInteract(p.level(), at) || TransferLocks.busy(p))
            throw new IllegalArgumentException("Move within 8 blocks of the station controller");
        var s = WorldStore.get(p.level().getServer()).at(p.level(), at);
        if (s == null || !StationAccess.ready(s))
            throw new IllegalArgumentException("Controller is not on a ready, registered vessel");
        if (StationAccess.station(s) && p.level().getBlockState(at).is(ModBlocks.STATION_CORE) && StationAccess.builder(p, s) && !p.level().getBlockState(WorldStore.pos(s, "controller")).is(ModBlocks.STATION_CORE))
        {
            s.putLong("controller", at.asLong());
            ShipSystems.changed(p.level(), at, false);
            WorldStore.get(p.level().getServer()).requestSave();
        }
        return s;
    }

    public static String mode(ServerLevel level, BlockPos at)
    {
        var state = level.getBlockState(at);
        if (state.is(ModBlocks.STATION_CORE))
            return "station_core";
        if (state.is(ModBlocks.DOCKING_BASE))
            return "docking_port";
        return ModBlocks.navigation(state) ? "docks" : "";
    }

    public static void open(ServerPlayer p, BlockPos at, String notice)
    {
        var s = at(p, at);
        String mode = mode(p.level(), at);
        if (mode.isEmpty())
            throw new IllegalArgumentException("Station controller missing");
        String text = notice.isEmpty() ? status(p, s, at) : notice;
        ComputerTerminal.send(p, at, text, mode, "0");
    }

    public static String status(ServerPlayer p, CompoundTag s, BlockPos at)
    {
        if (ModBlocks.navigation(p.level().getBlockState(at)))
            return DockingTargets.contacts(p, s);
        if (p.level().getBlockState(at).is(ModBlocks.DOCKING_BASE))
            return DockingTargets.status(p, s, at);
        var graph = StationGraph.inspect(p.level(), s);
        StringBuilder text = new StringBuilder(s.getStringOr("home", "Station"));
        var power = ShipSystems.powerStats(p.level(), s);
        var pressure = new HullScan.LivePressure(p.level(), WorldStore.bounds(s));
        int oxygen = 100, supplies = 0, leaks = 0;
        for (var pos : ShipSystems.connected(p.level(), s))
            if (p.level().getBlockState(pos).is(ModBlocks.AIR_SUPPLY) && ShipSystems.active(s, pos))
            {
                var outlet = pos.relative(p.level().getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING));
                var local = outlet.subtract(WorldStore.pos(s, "base"));
                supplies++;
                oxygen = Math.min(oxygen, WorldStore.bounds(s).contains(outlet) ? ShipAir.pressure(p.level(), s, local.getX(), local.getY(), local.getZ()) / 10 : 0);
                if (!pressure.breathable(outlet))
                    leaks++;
            }
        text.append("\nPower: ").append(power[0]).append(" / ").append(power[1]).append(" demand | Charging: ").append(power[2]);
        text.append("\nFuel: ").append(FuelSystem.display(FuelSystem.available(p.level(), s))).append(" | Battery: ").append(power[3]);
        text.append("\nAir: ").append(supplies == 0 ? "No active supply" : oxygen + "% at outlets").append(" | Leaks: ").append(leaks).append(" | Ports: ").append(graph.nodes().stream().filter(StationGraph.Node::dock).count());
        text.append("\nAccess: ").append(s.getStringOr("access", "private")).append(" | Door nodes: ").append(graph.nodes().size());
        for (var n : graph.nodes())
            text.append("\n").append(n.dock() ? "Dock " : "Door ").append(n.controller().subtract(WorldStore.pos(s, "base")).toShortString()).append(" | routes ").append(graph.routes().get(n.id()).size());
        text.append("\n").append(SystemsConsole.summary(p.level(), s));
        return text.substring(0, Math.min(2000, text.length()));
    }

    private static String showProblem(ServerPlayer p, CompoundTag station)
    {
        var level = p.level();
        var bounds = WorldStore.bounds(station);
        var pressure = new HullScan.LivePressure(level, bounds);
        for (var at : ShipSystems.connected(level, station))
        {
            var state = level.getBlockState(at);
            var target = at;
            String message = "";
            if (state.is(ModBlocks.AIR_SUPPLY) && ShipSystems.active(station, at))
            {
                target = at.relative(state.getValue(HorizontalDirectionalBlock.FACING));
                if (HullScan.seals(level, target))
                    message = "Clear the air outlet at " + target.toShortString();
                else if (!pressure.breathable(target))
                {
                    ShipDiagnostics.highlight(p, HullScan.leakTrace(level, bounds, ShipSystems.connected(level, station), target));
                    return "Leak path highlighted from air outlet " + target.toShortString();
                }
            }
            else if (state.is(ModBlocks.DOCKING_BASE) && !ShipDoors.framed(level, at.above()))
            {
                target = at.above();
                message = "Repair the framed dock door above the base at " + at.toShortString();
            }
            else if (state.is(ModBlocks.GENERATOR) && FuelSystem.available(level, station) == 0)
                message = "Load furnace fuel here; empty any blocked container returns at " + at.toShortString();
            if (!message.isEmpty())
            {
                ShipDiagnostics.highlight(p, List.of(target));
                return message;
            }
        }
        for (var port : DockingPorts.ports(level, station))
        {
            var obstruction = StationConstruction.doorwayObstruction(level, station, port);
            if (obstruction.isPresent())
            {
                ShipDiagnostics.highlight(p, List.of(obstruction.get()));
                return "Clear the docking doorway at " + obstruction.get().toShortString() + "; keep a two-block-high passage in front of the door.";
            }
        }
        return "Station fuel, air outlets and docking frames have no detected obstruction or leak";
    }

    public static void action(ServerPlayer p, BlockPos at, ComputerTerminal.Action action, String argument)
    {
        var s = at(p, at);
        var before = Set.of("STATION_INVITE", "STATION_REMOVE", "STATION_ACCESS").contains(action.name()) ? WorldStore.get(p.level().getServer()).snapshot() : null;
        String notice = "";
        switch (action.name())
        {
            case "STATION_CLAIM" ->
            {
                notice = Claims.claim(p, s);
                if (!StationAccess.station(s))
                {
                    // Claimed: the core is now a ship controller. Continue at the ship's controls.
                    ShipControl.open(p, WorldStore.pos(s, "controller"), notice);
                    return;
                }
            }
            case "STATION_REFRESH" ->
            {
                if (p.level().getBlockState(at).is(ModBlocks.STATION_CORE))
                    notice = showProblem(p, s);
            }
            case "STATION_SYSTEMS" ->
            {
                StationAccess.requireBuilder(p, s);
                SystemsConsole.action(p, at, ComputerTerminal.Action.SYSTEMS);
                return;
            }
            case "STATION_WAYPOINT" ->
            {
                notice = "Stations are discovered automatically. Choose Destination > Ports.";
            }
            case "STATION_START" ->
            {
                StationDeployment.start(p, s);
                notice = "Station deployed. Automatic construction docking starts when its platform is ready.";
            }
            case "STATION_MODULE", "STATION_GENERATE" ->
            {
                StationBuilds.buildRoom(p, s, argument, action.name().equals("STATION_GENERATE"));
                notice = "Module construction started.";
            }
            case "STATION_EXPAND" ->
            {
                StationBuilds.expand(p, s);
                notice = "Station build area expanded to 128 x 32 x 128. Existing blocks and systems are" + " preserved.";
            }
            case "STATION_RENAME" ->
            {
                StationAccess.requireBuilder(p, s);
                if (argument.isBlank())
                    throw new IllegalArgumentException("Enter a station name");
                com.nullandkale.nullspace.gameplay.travel.RecordJournal.run(p.level().getServer(), "rename-station", () ->
                {
                    s.putString("home", argument);
                    return null;
                });
            }
            case "STATION_INVITE" ->
            {
                if (!StationAccess.owner(p, s))
                    throw new IllegalArgumentException("Only the owner can invite builders");
                var target = p.level().getServer().getPlayerList().getPlayerByName(argument);
                if (target == null)
                    throw new IllegalArgumentException("That player must be online");
                var builders = s.getCompoundOrEmpty("builders");
                builders.putBoolean(target.getUUID().toString(), true);
                s.put("builders", builders);
                var names = s.getCompoundOrEmpty("builderNames");
                names.putString(target.getUUID().toString(), target.getPlainTextName());
                s.put("builderNames", names);
            }
            case "STATION_REMOVE" ->
            {
                if (!StationAccess.owner(p, s))
                    throw new IllegalArgumentException("Only the owner can remove builders");
                var builders = s.getCompoundOrEmpty("builders");
                builders.keySet().stream().filter(id -> id.replace("-", "").equals(argument)).findFirst().ifPresent(id ->
                {
                    builders.remove(id);
                    s.getCompoundOrEmpty("builderNames").remove(id);
                });
            }
            case "STATION_ACCESS" ->
            {
                if (!StationAccess.owner(p, s))
                    throw new IllegalArgumentException("Only the owner can change station access");
                s.putString("access", s.getStringOr("access", "private").equals("public") ? "private" : "public");
            }
            case "STATION_DOOR" ->
            {
                if (!StationAccess.allowed(p, s) && !StationAccess.returningVisitor(p, s))
                    throw new IllegalArgumentException("Station access is private");
                var door = at.below(2);
                if (DockingPorts.cycleOwnedInner(p, s, door))
                    break;
                if (DockingPorts.ownsDoor(p.level(), door))
                    throw new IllegalArgumentException("This door is owned by docking");
                if (ShipSystems.power(p.level(), s, PowerBus.System.DOORS) == 0)
                    throw new IllegalArgumentException("Doors have no power");
                if (!ShipDoors.framed(p.level(), door) || ShipDoors.powered(p.level(), door))
                    throw new IllegalArgumentException("Door is missing, unframed or overridden by redstone");
                ShipDoors.setOpen(p.level(), door, !ShipDoors.open(p.level().getBlockState(door)));
            }
            default ->
            {
                DockingService.action(p, s, at, action.name());
                return;
            }
        }
        if (before != null)
            com.nullandkale.nullspace.gameplay.travel.RecordJournal.commit(p.level().getServer(), "station-access", before);
        WorldStore.get(p.level().getServer()).requestSave();
        if (StationAccess.ready(s))
            open(p, at, notice);
    }

    public static void changed(ServerLevel level, BlockPos at, boolean replaced)
    {
        if (PLACING.get() || TravelService.worldMutation())
            return;
        var s = WorldStore.get(level.getServer()).at(level, at);
        if (s == null)
            return;
        s.putInt("stationRevision", s.getIntOr("stationRevision", 0) + 1);
        if (replaced)
            s.getCompoundOrEmpty("doorNodes").remove(Long.toString(at.subtract(WorldStore.pos(s, "base")).asLong()));
    }

    public static void initialize()
    {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
        {
            try
            {
                StationRegeneration.applyPending(server);
            }
            catch (Exception error)
            {
                NullSpace.LOGGER.error("Station regeneration was not applied; its request and backup are preserved", error);
            }
            var store = WorldStore.get(server);
            for (var tag : List.copyOf(store.ships()))
            {
                var s = (CompoundTag) tag;
                if (s.contains("stationBuild") && !s.getBooleanOr("buildPaid", true))
                {
                    if (!s.contains("systems"))
                        store.ships().remove(s);
                    else
                    {
                        s.remove("stationBuild");
                        s.put("roomPieces", s.getCompoundOrEmpty("piecesBefore"));
                        s.remove("piecesBefore");
                    }
                }
            }
        });
        ServerContext.tick(ServerContext.Phase.SESSIONS, server ->
        {
            var ledger = WorldStore.get(server).section("stationReceipts");
            boolean changed = false;
            for (var p : server.getPlayerList().getPlayers())
                for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                {
                    var stack = p.getInventory().getItem(i);
                    String token = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("stationReceipt", "");
                    if (!token.isEmpty() && ledger.contains(token))
                    {
                        stack.shrink(ledger.getIntOr(token, 1));
                        stack.remove(DataComponents.CUSTOM_DATA);
                        changed = true;
                    }
                }
            if (changed)
                server.getPlayerList().saveAll();
        });
        ServerContext.tick(ServerContext.Phase.TRAVEL, StationBuilds::tickBuilds);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(PROGRESS));
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
        {
            if (StationDeployment.stationStartGesture(player, world, hand) && StationDeployment.stationStartTarget(world, hit.getBlockPos()))
            {
                if (player instanceof ServerPlayer p && !StationAccess.mayUse(p, hit.getBlockPos()))
                    return InteractionResult.FAIL;
                return StationDeployment.startFromGesture(player, hand);
            }
            if (player instanceof ServerPlayer p && (!StationAccess.mayUse(p, hit.getBlockPos()) || p.getItemInHand(hand).getItem() instanceof BlockItem && !StationAccess.mayEdit(p, hit.getBlockPos().relative(hit.getDirection()))))
                return InteractionResult.FAIL;
            // A click fires once per hand when the client predicts no result, as it does for an iron door with an empty hand.
            // Toggle on the main hand only; the off-hand event on a door is consumed so it cannot toggle the door back.
            if (player instanceof ServerPlayer p && world.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && StationAccess.station(WorldStore.get(p.level().getServer()).at(p.level(), hit.getBlockPos())))
            {
                if (hand == InteractionHand.MAIN_HAND)
                    return StationAccess.useDoor(p, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) ->
        {
            if (StationDeployment.stationStartGesture(player, world, hand) && player.pick(player.blockInteractionRange(), 0, false).getType() == HitResult.Type.MISS)
                return StationDeployment.startFromGesture(player, hand);
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> !(player instanceof ServerPlayer p) || StationAccess.mayEdit(p, pos));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> player instanceof ServerPlayer p && !StationAccess.mayEdit(p, entity.blockPosition()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> player instanceof ServerPlayer p && !StationAccess.mayEdit(p, entity.blockPosition()) ? InteractionResult.FAIL : InteractionResult.PASS);
        GeneratedStations.initialize();
    }

    private StationService()
    {
    }

    static String idOf(CompoundTag s)
    {
        return s.getStringOr("id", "");
    }
}
