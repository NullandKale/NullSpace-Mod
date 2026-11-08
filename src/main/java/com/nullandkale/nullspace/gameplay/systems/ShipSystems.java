package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/**
 * Ship-owned state is included in the existing before/after transfer journal. A ship gets powered
 * systems and finite air once a generator and an Air Supply are connected.
 */
public final class ShipSystems
{
    private static final ServerContext.Key<Map<String, ShipRuntime>> KEY = new ServerContext.Key<>();

    static Map<String, ShipRuntime> live(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, HashMap::new);
    }

    public static void edited(MinecraftServer server, ShipRepository.Change change)
    {
        if (!change.fields().contains("systems") && !change.fields().contains("base") && !change.fields().contains("dimension"))
            return;
        var runtimes = ServerContext.get(server).existing(KEY);
        var r = runtimes == null ? null : runtimes.get(change.ship().value());
        if (r != null)
            r.source = null;
    }

    public static void replaced(MinecraftServer server)
    {
        var runtimes = ServerContext.get(server).existing(KEY);
        if (runtimes != null)
            for (var r : runtimes.values())
            {
                r.doors.clear();
                r.commander = null;
                r.commandController = null;
                r.dirty = true;
                r.power = null;
            }
    }

    /**
     * Power requests per system: controls, air (per Air Supply), doors, engines, cameras, weapons.
     */
    public static int[] defaultRequests()
    {
        return new int[]{1, SpatialAir.SUPPLY_POWER, 1, 3, 1, 0};
    }

    public static boolean enabled(CompoundTag ship)
    {
        return ship != null && ship.contains("systems");
    }

    public static int deviceEnergy(CompoundTag ship, BlockPos pos)
    {
        long local = pos.subtract(WorldStore.pos(ship, "base")).asLong();
        return state(ship).getListOrEmpty("devices").stream().map(t -> (CompoundTag) t).filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local).mapToInt(d -> d.getIntOr("energy", 0)).sum();
    }

    public static String deviceId(CompoundTag ship, BlockPos pos)
    {
        long local = pos.subtract(WorldStore.pos(ship, "base")).asLong();
        return state(ship).getListOrEmpty("devices").stream().map(t -> (CompoundTag) t).filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local).map(d -> d.getStringOr("id", "")).findFirst().orElse("");
    }

    public static void setEnabled(ServerLevel level, CompoundTag ship, BlockPos pos, boolean enabled)
    {
        for (var tag : state(ship).getListOrEmpty("devices"))
        {
            var d = (CompoundTag) tag;
            if (d.getLongOr("position", Long.MIN_VALUE) == pos.subtract(WorldStore.pos(ship, "base")).asLong())
                d.putBoolean("enabled", enabled);
        }
        var r = runtime(level, ship);
        r.power = null;
        r.revision++;
        WorldStore.get(level.getServer()).requestSave();
    }

    public static Set<BlockPos> connected(ServerLevel level, CompoundTag ship)
    {
        if (!enabled(ship))
            return ShipTopology.discover(level, ship);
        var r = runtime(level, ship);
        if (r.dirty)
            rebuild(level, ship, r, false);
        return r.blocks;
    }

    /** Re-read a vessel after its terrain has been loaded, preserving saved device and air state. */
    public static void loaded(ServerLevel level, CompoundTag ship)
    {
        var r = runtime(level, ship);
        r.dirty = true;
        r.power = null;
    }

    public static List<BlockPos> equipment(ServerLevel level, CompoundTag ship)
    {
        if (!enabled(ship))
            return connected(level, ship).stream().filter(p -> DeviceInventory.device(level.getBlockState(p).getBlock())).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        var r = runtime(level, ship);
        if (r.dirty)
            rebuild(level, ship, r, false);
        return r.equipment;
    }

    static ShipRuntime runtime(ServerLevel level, CompoundTag ship)
    {
        var runtimes = live(level.getServer());
        String id = ship.getStringOr("id", "");
        var r = runtimes.get(id);
        if (r == null || r.source != ship)
        {
            var replacement = new ShipRuntime();
            replacement.source = ship;
            if (r != null)
                replacement.revision = r.revision + 1;
            r = replacement;
            runtimes.put(id, r);
        }
        return r;
    }

    static CompoundTag state(CompoundTag ship)
    {
        return ship.getCompoundOrEmpty("systems");
    }

    public static int revision(ServerLevel level, CompoundTag ship)
    {
        var r = runtime(level, ship);
        if (enabled(ship) && r.dirty)
            rebuild(level, ship, r, false);
        return r.revision;
    }

    public static void cancel(ServerPlayer player, BlockPos controller)
    {
        for (var r : live(player.level().getServer()).values())
            if (r.commander == player && controller.equals(r.commandController) && !r.doors.isEmpty())
            {
                r.result = "Door batch CANCELLED: terminal closed";
                r.doorResults.add(r.result);
                r.doors.clear();
            }
    }

    public static void cancelTransit(ServerLevel level, CompoundTag ship)
    {
        var r = runtime(level, ship);
        if (!r.doors.isEmpty())
        {
            r.result = "Door batch CANCELLED: transfer";
            r.doorResults.add(r.result);
            r.doors.clear();
        }
        r.dirty = true;
    }

    public static Atmosphere.Grid grid(CompoundTag ship)
    {
        return StationAccess.station(ship) ? new Atmosphere.Grid(ship.getIntOr("width", 32), 32, ship.getIntOr("depth", 32)) : Atmosphere.SHIP;
    }

    static int local(CompoundTag ship, BlockPos p)
    {
        var d = p.subtract(WorldStore.pos(ship, "base"));
        return grid(ship).index(d.getX(), d.getY(), d.getZ());
    }

    public static void changed(ServerLevel level, BlockPos p, boolean replaced)
    {
        StationService.changed(level, p, replaced);
        for (Tag t : WorldStore.get(level.getServer()).ships())
        {
            var ship = (CompoundTag) t;
            if (!enabled(ship) || !ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) || !WorldStore.bounds(ship).contains(p))
                continue;
            var r = runtime(level, ship);
            r.dirty = true;
            // A destroyed/disconnected supply cannot authorize a shot or launch using last
            // tick's grant. Journal placement preserves the frozen transfer allocation.
            if (!TravelService.worldMutation())
                r.power = null;
            // Journal replay temporarily removes the same blocks before restoring them.
            // The journal owns device identities and energy during that mutation.
            if (replaced && !TravelService.worldMutation())
            {
                long pos = p.subtract(WorldStore.pos(ship, "base")).asLong();
                state(ship).getListOrEmpty("devices").removeIf(d -> ((CompoundTag) d).getLongOr("position", Long.MIN_VALUE) == pos);
            }
        }
    }

    static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r, boolean initialize)
    {
        DeviceInventory.rebuild(level, ship, r);
        AirSystem.rebuild(level, ship, r, initialize);
        r.dirty = false;
        r.revision++;
    }

    static boolean active(ShipRuntime r, BlockPos pos)
    {
        var d = r.devices.get(BlockPos.asLong(pos.getX() - r.base.getX(), pos.getY() - r.base.getY(), pos.getZ() - r.base.getZ()));
        return d == null || d.getBooleanOr("enabled", true);
    }

    public static void tick(ServerLevel level, CompoundTag ship)
    {
        if (!enabled(ship) || !StationAccess.ready(ship) || TransferLocks.locked(level, WorldStore.pos(ship, "base")))
            return;
        var r = runtime(level, ship);
        if (r.dirty)
            rebuild(level, ship, r, false);
        PowerSystem.tick(level, ship, r);
        DoorSystem.tick(level, r);
        AirSystem.tick(level, ship, r);
    }

    static int grant(ShipRuntime r, PowerBus.System system)
    {
        return r.power == null ? 0 : r.power.delivered().getOrDefault(system, 0);
    }

    public static int power(ServerLevel level, CompoundTag ship, PowerBus.System system)
    {
        if (!enabled(ship))
            return Integer.MAX_VALUE;
        var r = runtime(level, ship);
        if (r.dirty && !TravelService.worldMutation())
            rebuild(level, ship, r, false);
        if (r.power == null)
        {
            var a = PowerSystem.allocate(level, ship, r);
            var p = a.result();
            r.power = new PowerBus.Result(a.stored(), p.generated(), p.discharged(), p.consumed(), p.charged(), p.curtailed(), p.delivered());
        }
        return grant(r, system);
    }

    public static int[] powerStats(ServerLevel level, CompoundTag ship)
    {
        power(level, ship, PowerBus.System.CONTROLS);
        var r = runtime(level, ship);
        var p = r.power;
        return p == null ? new int[4] : new int[]{p.generated() - p.curtailed(), p.consumed(), p.charged(), p.energy()};
    }

    /** Read actual generation/use separately from demand, including unmet demand. */
    public static int[] powerDisplay(ServerLevel level, CompoundTag ship)
    {
        var actual = powerStats(level, ship);
        var allocation = PowerSystem.allocate(level, ship, runtime(level, ship));
        return new int[]{actual[0], actual[1], actual[2], actual[3], allocation.demand(), allocation.capacity()};
    }

    public static boolean active(CompoundTag ship, BlockPos pos)
    {
        long local = pos.subtract(WorldStore.pos(ship, "base")).asLong();
        return state(ship).getListOrEmpty("devices").stream().map(t -> (CompoundTag) t).filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local).allMatch(d -> d.getBooleanOr("enabled", true));
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.SYSTEMS, server ->
        {
            for (Tag t : WorldStore.get(server).ships())
            {
                var ship = (CompoundTag) t;
                if (!enabled(ship))
                    continue;
                var level = WorldStore.level(server, ship.getStringOr("dimension", ""));
                var bounds = WorldStore.bounds(ship);
                if (level.players().stream().noneMatch(p -> bounds.box().inflate(80).contains(p.position())) && !DockingService.engaged(ship))
                    continue;
                boolean loaded = true;
                for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
                    for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                        if (!level.hasChunk(x, z))
                            loaded = false;
                if (loaded)
                    tick(level, ship);
            }
            if (server.getTickCount() % 200 == 0)
                WorldStore.get(server).requestSave();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    }

    private ShipSystems()
    {
    }
}
