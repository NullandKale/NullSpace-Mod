package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.model.computer.ScriptLanguage;
import com.nullandkale.nullspace.model.engineering.EngineeringCommands;
import com.nullandkale.nullspace.model.engineering.ShipCommand;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/** The ship systems console: its summary text, readings and commands (power, doors, automation). */
public final class SystemsConsole
{
    static final String UPGRADE_QUERY = "upgrade readiness";

    /**
     * Closes every ship door now (Engage, playtest 3). Doors owned by docking or disabled are left
     * alone; returns the doors that could not close, for example ones held open by redstone.
     */
    public static List<BlockPos> closeDoorsNow(ServerLevel level, CompoundTag ship)
    {
        if (!ShipSystems.enabled(ship))
            return List.of();
        var r = ShipSystems.runtime(level, ship);
        if (r.dirty)
            ShipSystems.rebuild(level, ship, r, false);
        var stuck = new ArrayList<BlockPos>();
        for (var p : List.copyOf(r.blocks))
        {
            var state = level.getBlockState(p);
            if (!ShipDoors.root(state) || !ShipDoors.isDoor(state) || !ShipDoors.framed(level, p))
                continue;
            if (DockingPorts.ownsDoor(level, p) || !ShipSystems.active(r, p))
                continue;
            if (!state.getValue(DoorBlock.OPEN))
                continue;
            if (!ShipDoors.powered(level, p))
                ShipDoors.setOpen(level, p, false);
            if (level.getBlockState(p).getValue(DoorBlock.OPEN))
                stuck.add(p.immutable());
        }
        r.revision++;
        return stuck;
    }

    public static String summary(ServerLevel level, CompoundTag ship)
    {
        if (!ShipSystems.enabled(ship))
            return "Systems not set up. Connect a generator and an Air Supply to the hull, then set" + " up systems at the ship controller to get power and finite air.";
        var r = ShipSystems.runtime(level, ship);
        ShipSystems.power(level, ship, PowerBus.System.CONTROLS);
        var p = r.power;
        String priority = ShipSystems.state(ship).contains("order") ? Arrays.toString(ShipSystems.state(ship).getIntArray("order").orElse(new int[0])) : ShipSystems.state(ship).getBooleanOr("airFirst", false) ? "Air first" : "Controls first";
        String text = "SYSTEMS " + r.revision + " | Profile " + ShipSystems.state(ship).getStringOr("profile", "custom") + " | Priority " + priority + " | " + (p == null ? "Starting" : ("Generation " + p.generated() + " Draw " + p.consumed() + " Battery " + p.energy())) + " | ";
        int[] req = ShipSystems.state(ship).getIntArray("requests").orElse(new int[6]);
        for (var system : PowerBus.System.values())
            text += system + " " + ShipSystems.grant(r, system) + "/" + req[system.ordinal()] + "; ";
        int room = 0;
        for (var a : r.rooms)
        {
            long q = 0;
            for (int i : a.cells())
                q += Math.max(0, r.air[i]);
            text += "Room " + (++room) + ": " + q * 100 / (a.cells().length * 1000L) + "%" + (a.leaks() > 0 ? " LEAK " : " sealed ");
            if (text.length() > 1600)
                break;
        }
        return text + " | " + r.result + " | " + String.join("; ", r.doorResults.subList(Math.max(0, r.doorResults.size() - 6), r.doorResults.size()));
    }

    public static String reading(ServerPlayer player, BlockPos controller, String query)
    {
        // Physical edits can invalidate the controller's topology as well as its selected page.
        // Both are status messages while a terminal is watched, not fatal server tick errors.
        try
        {
            var level = player.level();
            var ship = ShipTopology.resolve(level, controller);
            if (query.equals(UPGRADE_QUERY))
                return SystemsUpgrade.upgradeCheck(level, ship, controller).message();
            if (query.isEmpty() || !ShipSystems.enabled(ship))
                return summary(level, ship);
            if (!EngineeringCommands.readOnly(query))
                throw new IllegalArgumentException("This is not a status query");
            var r = ShipSystems.runtime(level, ship);
            if (r.dirty)
                ShipSystems.rebuild(level, ship, r, false);
            return configure(player, level, ship, r, query);
        }
        catch (IllegalArgumentException e)
        {
            return ShipMessages.failure(e);
        }
    }

    public static void action(ServerPlayer player, BlockPos controller, ComputerTerminal.Action action)
    {
        action(player, controller, action, "");
    }

    public static void action(ServerPlayer player, BlockPos controller, ComputerTerminal.Action action, String argument)
    {
        action(player, controller, action, argument, true);
    }

    public static String closeDoors(ServerPlayer player, BlockPos controller)
    {
        action(player, controller, ComputerTerminal.Action.CLOSE_DOORS, "", false);
        var ship = WorldStore.get(player.level().getServer()).at(player.level(), controller);
        return ShipSystems.runtime(player.level(), ship).result;
    }

    static void action(ServerPlayer player, BlockPos controller, ComputerTerminal.Action action, String argument, boolean openScreen)
    {
        var level = player.level();
        if (TransferLocks.busy(player) || TransferLocks.locked(level, controller))
            throw new IllegalArgumentException("Wait for boarding to finish");
        var ship = ShipTopology.resolve(level, controller);
        var r = ShipSystems.runtime(level, ship);
        boolean readOnly = action == ComputerTerminal.Action.SYSTEMS || action == ComputerTerminal.Action.CONFIGURE && EngineeringCommands.readOnly(argument);
        String response = null;
        var connected = ShipSystems.connected(level, ship);
        if (action != ComputerTerminal.Action.UPGRADE && !connected.contains(controller))
            throw new IllegalArgumentException("This controller is disconnected from the ship");
        if (action == ComputerTerminal.Action.UPGRADE)
        {
            var check = SystemsUpgrade.upgradeCheck(level, ship, controller);
            if (check.ready())
                SystemsUpgrade.upgrade(level, ship);
            else
                response = check.message();
        }
        else if (ShipSystems.enabled(ship))
        {
            if (!readOnly && ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0)
                throw new IllegalArgumentException("Controls have no power; reset local supply hardware");
            if (action == ComputerTerminal.Action.CLOSE_DOORS || action == ComputerTerminal.Action.OPEN_DOORS)
            {
                if (ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0)
                    throw new IllegalArgumentException("Controls have no power; use local generator/battery reset");
                r.doors.clear();
                r.open = action == ComputerTerminal.Action.OPEN_DOORS;
                r.doorResults.clear();
                r.revision++;
                r.commander = player;
                r.commandController = controller;
                r.blocks.stream().filter(p -> ShipDoors.root(level.getBlockState(p))).sorted(Comparator.comparingLong(BlockPos::asLong)).forEach(r.doors::addLast);
                r.result = "Queued " + r.doors.size() + " doors to " + (r.open ? "open" : "close");
            }
            else if (action == ComputerTerminal.Action.POWER_AIR || action == ComputerTerminal.Action.POWER_ENGINES || action == ComputerTerminal.Action.POWER_WEAPONS)
            {
                var req = ShipSystems.state(ship).getIntArray("requests").orElse(ShipSystems.defaultRequests());
                int i = action == ComputerTerminal.Action.POWER_AIR ? 1 : action == ComputerTerminal.Action.POWER_ENGINES ? 3 : 5;
                req[i] = (req[i] + 1) % 9;
                ShipSystems.state(ship).putIntArray("requests", req);
                ShipSystems.state(ship).putString("profile", "custom");
                r.power = null;
                r.revision++;
            }
            else if (action == ComputerTerminal.Action.PRIORITY)
            {
                ShipSystems.state(ship).remove("order");
                ShipSystems.state(ship).putBoolean("airFirst", !ShipSystems.state(ship).getBooleanOr("airFirst", false));
                ShipSystems.state(ship).putString("profile", "custom");
                r.power = null;
                r.revision++;
            }
            else if (action == ComputerTerminal.Action.CONFIGURE)
            {
                var result = configure(player, level, ship, r, argument);
                if (readOnly)
                    response = result;
                else
                {
                    r.result = result;
                    r.commander = player;
                    r.commandController = controller;
                    r.power = null;
                    r.revision++;
                }
            }
        }
        else if (action != ComputerTerminal.Action.SYSTEMS)
            throw new IllegalArgumentException("Upgrade systems first");
        if (!readOnly)
            WorldStore.get(level.getServer()).requestSave();
        if (!openScreen)
            return;
        ComputerTerminal.send(player, controller, response != null ? response : action == ComputerTerminal.Action.CONFIGURE ? r.result : summary(level, ship), "systems", Integer.toString(r.revision));
        ComputerTerminal.follow(player, controller, action == ComputerTerminal.Action.UPGRADE && response != null ? UPGRADE_QUERY : readOnly && action == ComputerTerminal.Action.CONFIGURE ? argument : "");
    }

    static List<CompoundTag> doors(CompoundTag ship, ShipRuntime r)
    {
        var base = WorldStore.pos(ship, "base");
        return ShipSystems.state(ship).getListOrEmpty("devices").stream().map(t -> (CompoundTag) t).filter(d -> (d.getStringOr("type", "").endsWith("_door") || d.getStringOr("type", "").endsWith("_trapdoor")) && r.blocks.contains(base.offset(BlockPos.of(d.getLongOr("position", 0))))).sorted(Comparator.comparing(d -> d.getStringOr("id", ""))).toList();
    }

    static String configure(ServerPlayer player, ServerLevel level, CompoundTag ship, ShipRuntime r, String input)
    {
        return EngineeringExecutor.execute(player, level, ship, r, ShipCommand.parse(input));
    }

    public static String automation(ServerLevel level, CompoundTag ship, BlockPos controller, String input)
    {
        if (input.length() > 128)
            throw new IllegalArgumentException("Device command is too long");
        if (TransferLocks.locked(level, controller))
            throw new ScriptLanguage.Unavailable("Ship is in transit");
        var r = ShipSystems.runtime(level, ship);
        if (r.dirty)
            ShipSystems.rebuild(level, ship, r, false);
        if (!r.blocks.contains(controller) || !ShipSystems.active(r, controller))
            throw new IllegalArgumentException("Computer is disconnected or disabled");
        if (!ShipSystems.enabled(ship))
            throw new IllegalArgumentException("Upgrade ship systems first");
        boolean read = EngineeringCommands.readOnly(input);
        if (!read && ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0)
            throw new IllegalArgumentException("Controls have no power");
        if (input.equals("room here"))
            throw new IllegalArgumentException("Use rooms from an unattended computer");
        String result = configure(null, level, ship, r, input);
        if (!read)
        {
            r.commander = null;
            r.commandController = controller;
            r.power = null;
            r.revision++;
        }
        return result;
    }

    private SystemsConsole()
    {
    }
}
