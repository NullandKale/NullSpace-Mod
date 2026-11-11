package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingTargets;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** One final destination, independent of the intermediate launch/jump/arrival operations. */
public record ShipDestination(Kind kind, String reference, BlockPos port, String region, String name, String state)
{
    public enum Kind
    {
        REGION, PAD, PORT, VESSEL
    }

    public String id()
    {
        return UUID.nameUUIDFromBytes((kind + ":" + reference + ":" + port.asLong()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static ShipDestination region(net.minecraft.server.MinecraftServer server, String address)
    {
        var region = LocalSpaces.get(server, address);
        return new ShipDestination(Kind.REGION, address, BlockPos.ZERO, address, region.systemName() + " / " + region.name(), "Orbit");
    }

    public static List<ShipDestination> catalog(ServerPlayer p, CompoundTag own)
    {
        var out = new ArrayList<ShipDestination>();
        for (var region : LocalSpaces.destinations(p.level().getServer()))
            out.add(new ShipDestination(Kind.REGION, region.id(), BlockPos.ZERO, region.id(), region.systemName() + " / " + region.name(), StarSystems.describe(p.level().getServer(), region.id())));
        for (var pad : PadRegistry.read(p.level().getServer()))
        {
            var region = LocalSpaces.destinations(p.level().getServer()).stream().filter(r -> PlanetSurfaces.surfaceOf(p.level().getServer(), r.id(), pad.dimension())).findFirst();
            if (region.isEmpty())
                continue;
            var status = PadRegistry.state(p.level().getServer(), pad);
            out.add(new ShipDestination(Kind.PAD, pad.id(), BlockPos.ZERO, region.get().id(), pad.name(), status.state().name()));
        }
        for (var berth : DockingTargets.destinations(p, own, true))
        {
            var target = WorldStore.get(p.level().getServer()).repository().find(berth.vessel());
            if (target != null && (!JourneyService.special(p.level().getServer(), LocalSpaces.address(target)) || LocalSpaces.together(own, target)))
                out.add(new ShipDestination(Kind.PORT, berth.vessel(), berth.port(), LocalSpaces.address(target), berth.label(), berth.state()));
        }
        return List.copyOf(out);
    }

    public CompoundTag save()
    {
        var tag = new CompoundTag();
        tag.putString("kind", kind.name());
        tag.putString("reference", reference);
        tag.putLong("port", port.asLong());
        tag.putString("region", region);
        tag.putString("name", name);
        return tag;
    }

    public static ShipDestination selected(CompoundTag ship)
    {
        return read(ship.getCompoundOrEmpty("destination"));
    }

    public static ShipDestination read(CompoundTag tag)
    {
        var kind = Arrays.stream(Kind.values()).filter(k -> k.name().equals(tag.getStringOr("kind", ""))).findFirst();
        var region = tag.getStringOr("region", "");
        if (kind.isEmpty() || !(LocalSpaces.known(region) || region.matches("e_[a-f0-9]{24}")))
            return null;
        return new ShipDestination(kind.get(), tag.getStringOr("reference", ""), BlockPos.of(tag.getLongOr("port", 0)), region, tag.getStringOr("name", "Destination"), "Unavailable");
    }

    /** One stable destination per vessel; physical berths are resolved when approaching. */
    public static List<ShipDestination> navigationCatalog(ServerPlayer player, CompoundTag own)
    {
        var raw = catalog(player, own);
        var vessels = new LinkedHashMap<String, List<ShipDestination>>();
        for (var entry : raw)
            if (entry.kind() == Kind.PORT)
                vessels.computeIfAbsent(entry.reference(), k -> new ArrayList<>()).add(entry);
        var result = new ArrayList<ShipDestination>();
        for (var ports : vessels.values())
        {
            var first = ports.getFirst();
            var vessel = WorldStore.get(player.level().getServer()).repository().find(first.reference());
            if (vessel == null)
                continue;
            long free = ports.stream().filter(p -> !p.state().equalsIgnoreCase("Occupied")).count();
            result.add(new ShipDestination(Kind.VESSEL, first.reference(), BlockPos.ZERO, first.region(), vessel.getStringOr("home", "Vessel"), (StationAccess.station(vessel) ? "Station" : "Ship") + ": " + free + "/" + ports.size() + " free"));
        }
        result.addAll(raw);
        return List.copyOf(result);
    }

    public static void select(ServerPlayer p, CompoundTag ship, ShipDestination destination)
    {
        if (JourneyService.cruising(ship))
            throw new IllegalArgumentException("Journey underway: investigate a signal or arrive before changing destination");
        WorldStore.get(p.level().getServer()).repository().mutate(new ShipRepository.ShipId(ship.getStringOr("id", "")), ShipRepository.Mutation.CONFIGURATION, candidate -> candidate.put("destination", destination.save()));
        WorldStore.get(p.level().getServer()).requestSave();
    }
}
