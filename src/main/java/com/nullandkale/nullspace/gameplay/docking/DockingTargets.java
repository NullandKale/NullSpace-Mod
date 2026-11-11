package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/**
 * What a ship can dock with: the destination list, nearby contacts and the port panel a player
 * reads.
 */
public final class DockingTargets
{

    public record Choice(String vessel, BlockPos port, String label, String state)
    {}

    static List<Choice> choices(ServerPlayer p, CompoundTag own)
    {
        return destinations(p, own, false).stream().filter(c -> !c.state().equals("Occupied")).toList();
    }

    /** Registered station berths may be selected before jumping to their region. */
    public static List<Choice> destinations(ServerPlayer p, CompoundTag own, boolean remoteStations)
    {
        var out = new ArrayList<Choice>();
        var level = remoteStations ? p.level().getServer().getLevel(TravelService.SPACE) : p.level();
        if (level == null)
            return List.of();
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            if (out.size() >= (remoteStations ? 384 : 64))
                break;
            var s = (CompoundTag) tag;
            if (s == own || !s.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) || !LocalSpaces.together(s, own) && !(remoteStations && StationAccess.station(s)) || !StationAccess.ready(s) || !StationAccess.allowed(p, s) || s.contains("dock"))
                continue;
            // Contacts expose bounded registered ports, not arbitrary remote inventories or terrain.
            if (!StationAccess.station(s) && SceneService.pose(s).position().distanceTo(SceneService.pose(own).position()) > 4096)
                continue;
            if (remoteStations)
            {
                var bounds = WorldStore.bounds(s);
                boolean loaded = true;
                for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
                    for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                        loaded &= level.hasChunk(x, z);
                if (!loaded)
                {
                    // The destination picker reads registered devices without loading remote terrain.
                    // Request performs the authoritative block/airlock checks before reserving anything.
                    for (var tagDevice : s.getCompoundOrEmpty("systems").getListOrEmpty("devices"))
                    {
                        var device = (CompoundTag) tagDevice;
                        if (!device.getStringOr("type", "").equals("nullspace:docking_base"))
                            continue;
                        var local = BlockPos.of(device.getLongOr("position", 0));
                        var node = s.getCompoundOrEmpty("doorNodes").getStringOr(Long.toString(local.asLong()), "");
                        out.add(new Choice(DockingService.id(s), local, device.getStringOr("label", "").isBlank() ? portLabel(s, local) : s.getStringOr("home", "Ship") + " / " + device.getStringOr("label", ""), s.getCompoundOrEmpty("dockLinks").contains(node) ? "Occupied" : "Unverified"));
                    }
                    continue;
                }
            }
            else
                DockingService.chunks(level, s);
            for (var port : DockingPorts.ports(level, s))
            {
                var offset = port.controller().subtract(WorldStore.pos(s, "base"));
                String label = level.getBlockEntity(port.controller()) instanceof MachineBlockEntity machine && !machine.label.isBlank() ? s.getStringOr("home", "Ship") + " / " + machine.label : portLabel(s, offset);
                String status = s.getCompoundOrEmpty("dockLinks").contains(port.id()) ? "Occupied" : "Available";
                out.add(new Choice(DockingService.id(s), offset, label, status));
            }
        }
        out.sort(Comparator.comparing(Choice::label));
        return out.subList(0, Math.min(remoteStations ? 384 : 64, out.size()));
    }

    static String portLabel(CompoundTag ship, BlockPos local)
    {
        return ship.getStringOr("home", "Ship") + " / Port " + local.getX() + "," + local.getY() + "," + local.getZ();
    }

    public static String contacts(ServerPlayer p, CompoundTag own)
    {
        var choices = choices(p, own);
        if (!choices.isEmpty() && ShipDestination.selected(own) == null)
            selectDestination(p, own, choices.getFirst());
        var selected = selectedDestination(own);
        StringBuilder text = new StringBuilder("DOCKING | " + own.getCompoundOrEmpty("dock").getStringOr("state", "Available"));
        if (choices.isEmpty())
            text.append("\nNo available berths. Local station contacts update automatically.");
        else
            for (int i = 0; i < choices.size(); i++)
                text.append(sameChoice(selected, choices.get(i)) ? "\n> " : "\n  ").append(choices.get(i).label());
        text.append("\n").append(own.getStringOr("flightStatus", "Select a berth, then Dock automatically."));
        return text.substring(0, Math.min(2000, text.length()));
    }

    static boolean sameChoice(Choice a, Choice b)
    {
        return a != null && b != null && a.vessel().equals(b.vessel()) && a.port().equals(b.port());
    }

    static Choice selectedDestination(CompoundTag own)
    {
        var destination = ShipDestination.selected(own);
        return destination != null && destination.kind() == ShipDestination.Kind.PORT ? new Choice(destination.reference(), destination.port(), destination.name(), destination.state()) : null;
    }

    static void selectDestination(ServerPlayer p, CompoundTag own, Choice choice)
    {
        var target = DockingService.find(p.level().getServer(), choice.vessel());
        if (target != null)
            ShipDestination.select(p, own, new ShipDestination(ShipDestination.Kind.PORT, choice.vessel(), choice.port(), LocalSpaces.address(target), choice.label(), choice.state()));
    }

    public static CompoundTag panel(MinecraftServer server, CompoundTag s, BlockPos at)
    {
        var result = new CompoundTag();
        var d = DockingService.link(server, s, at);
        String state = d == null ? "UNCONNECTED" : d.getStringOr("state", "");
        result.putString("dockState", state);
        boolean source = d != null && d.getStringOr("source", "").equals(DockingService.id(s));
        result.putBoolean("incoming", d != null && !source && state.equals("WAITING_CONSENT"));
        result.putBoolean("maneuver", d != null && source && !state.equals("CLAMPED"));
        result.putBoolean("clamped", state.equals("CLAMPED"));
        var partner = d == null ? null : DockingService.find(server, d.getStringOr(source ? "target" : "source", ""));
        result.putString("partner", partner == null ? "None" : partner.getStringOr("home", "Vessel"));
        return result;
    }

    public static String status(ServerPlayer p, CompoundTag s, BlockPos at)
    {
        var port = DockingPorts.readPort(p.level(), s, at, false);
        var d = DockingService.link(p.level().getServer(), s, at);
        String connection = d == null ? "No connection" : d.getStringOr("state", "");
        int controls = ShipSystems.power(p.level(), s, PowerBus.System.CONTROLS);
        int doors = ShipSystems.power(p.level(), s, PowerBus.System.DOORS);
        int air = ShipSystems.power(p.level(), s, PowerBus.System.AIR);
        return "Door + docking base ready\n" + connection + "\nPower controls/doors/air: " + controls + "/" + doors + "/" + air;
    }

    private DockingTargets()
    {
    }
}
