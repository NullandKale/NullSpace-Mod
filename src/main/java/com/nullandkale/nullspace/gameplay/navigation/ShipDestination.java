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
import net.minecraft.server.MinecraftServer;
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
        // A region's places are fixed by the world seed before anyone goes there, but are built only after the first visit: list them as charted, so the
        // scanner shows which places have a station. Choosing one sets a course to its region; once it is built, its own entry offers its ports. They come
        // last, so a long list that a screen cuts short loses them before any place that can be reached.
        var server = player.level().getServer();
        for (var region : LocalSpaces.destinations(server))
            for (var site : com.nullandkale.nullspace.worldgen.encounters.SpacePopulation.unbuilt(server, region.id()))
                result.add(new ShipDestination(Kind.VESSEL, PLANNED + site.id(), BlockPos.ZERO, region.id(), site.name(), (site.role().equals("orbital") ? "Station" : "Ship") + ": charted"));
        // So are the pads that are built on first need: a planet's landing pad (made when someone first jumps there) and the Terra Harbor (made for the first
        // rescue). Choosing one builds it then, so a course can end by landing on it.
        var pads = PadRegistry.read(server);
        var surfaces = WorldStore.get(server).section("surfaceSites");
        for (var region : LocalSpaces.destinations(server))
        {
            var surface = region.id().equals(LocalSpaces.HOME) ? null : PlanetSurfaces.dimension(server, region.id());
            if (surface == null || surfaces.getBooleanOr(region.id(), false) || pads.stream().anyMatch(p -> p.dimension().equals(surface.identifier().toString())))
                continue;
            result.add(new ShipDestination(Kind.PAD, PLANNED_LANDING + region.id(), BlockPos.ZERO, region.id(), region.name() + " Landing", "Charted"));
        }
        if (!WorldStore.get(server).section("harbor").getBooleanOr("built", false) && pads.stream().noneMatch(p -> p.name().equals(HARBOR)))
            result.add(new ShipDestination(Kind.PAD, PLANNED_HARBOR, BlockPos.ZERO, LocalSpaces.HOME, HARBOR, "Charted"));
        return List.copyOf(result);
    }

    /** The reference of a place that is planned but not built yet (see {@link #navigationCatalog}). */
    static final String PLANNED = "planned:";
    static final String PLANNED_LANDING = PLANNED + "landing:", PLANNED_HARBOR = PLANNED + "harbor", HARBOR = "Terra Harbor";

    /**
     * The destination a choice stands for. A charted station or ship that is not built has no ports, so the course is to its region; a charted pad is built
     * now, and the course is to the pad itself.
     */
    static ShipDestination charted(MinecraftServer server, ShipDestination chosen)
    {
        if (!chosen.reference().startsWith(PLANNED))
            return chosen;
        if (chosen.kind() != Kind.PAD)
            return region(server, chosen.region());
        java.util.function.Predicate<PadRegistry.Pad> built;
        if (chosen.reference().equals(PLANNED_HARBOR))
        {
            com.nullandkale.nullspace.worldgen.sites.TerraHarbor.ensure(server);
            built = pad -> pad.name().equals(HARBOR);
        }
        else
        {
            PlanetSurfaces.ensureLanding(server, chosen.region());
            String surface = PlanetSurfaces.dimension(server, chosen.region()).identifier().toString();
            built = pad -> pad.dimension().equals(surface);
        }
        var pad = PadRegistry.read(server).stream().filter(built).findFirst().orElseThrow(() -> new IllegalArgumentException(chosen.name() + " could not be made; choose the planet instead"));
        return new ShipDestination(Kind.PAD, pad.id(), BlockPos.ZERO, chosen.region(), pad.name(), PadRegistry.state(server, pad).state().name());
    }

    public static void select(ServerPlayer p, CompoundTag ship, ShipDestination chosen)
    {
        if (JourneyService.cruising(ship))
            throw new IllegalArgumentException("Journey underway: investigate a signal or arrive before changing destination");
        var destination = charted(p.level().getServer(), chosen);
        WorldStore.get(p.level().getServer()).repository().mutate(new ShipRepository.ShipId(ship.getStringOr("id", "")), ShipRepository.Mutation.CONFIGURATION, candidate -> candidate.put("destination", destination.save()));
        WorldStore.get(p.level().getServer()).requestSave();
    }
}
