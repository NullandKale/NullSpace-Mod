package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The fixture world of the performance test: reference ships on registered pads, some of them launched into space.
 * Every ship is built and moved through the real code paths, so the store, the journals and the world hold what
 * a live server would.
 */
final class PerfWorld
{
    static final int PITCH = 64, COLUMNS = 8;
    private static final BlockPos ORIGIN = new BlockPos(4096, 96, 4096);

    /** One ship of the fixture and the actor who stands aboard it. */
    static final class Ship
    {
        final int index;
        final BlockPos base;
        final String pad;
        String id = "";
        ServerPlayer actor;
        ServerLevel home;
        BlockPos helm;

        Ship(int index)
        {
            this.index = index;
            this.base = ORIGIN.offset(PITCH * (index % COLUMNS), 0, PITCH * (index / COLUMNS));
            this.pad = "Perf " + index;
        }

        BlockPos padController()
        {
            return helm == null ? base.offset(6, 3, 3) : helm;
        }
    }

    /** One finished move, for the playback of the run. Coordinates are the ship's base after the move. */
    record Move(int ship, boolean launch, boolean completed, double ms, String dimension, long baseX, long baseZ)
    {}

    final List<Ship> ships = new ArrayList<>();
    final List<net.minecraft.nbt.CompoundTag> stations = new ArrayList<>();
    final List<ServerPlayer> crew = new ArrayList<>();
    int requestedCount, launchedCount;
    ServerPlayer builder;
    /**
     * Roof lights on every ship. Remote ships are drawn from the light stored at their blocks, and a ship in space
     * has none, so without these they are drawn at 15% brightness. Only the client test turns them on.
     */
    boolean beacons;
    /** Hulls of glass instead of stone, so a ship in view shows its rooms and machinery. Only the client test turns it on. */
    boolean glass;
    /** Told when a move starts, so the stage timings measure from the right moment. */
    java.util.function.LongConsumer stageClock = nanos ->
    {};
    java.util.function.Consumer<Move> listener = move ->
    {};
    private final ServerLevel planet;

    PerfWorld(ServerLevel planet)
    {
        this.planet = planet;
    }

    /** Builds one ship on its own registered pad. */
    Ship build(int index) throws java.io.IOException
    {
        var ship = new Ship(index);
        if (requestedCount > 0)
            return representative(ship);
        int minChunkX = ship.base.getX() >> 4, minChunkZ = ship.base.getZ() >> 4;
        for (int x = minChunkX; x <= minChunkX + 2; x++)
            for (int z = minChunkZ; z <= minChunkZ + 2; z++)
                planet.setChunkForced(x, z, true);
        ShipTestFixture.build(planet, ship.base);
        var pad = new PadScanner.Pad(ship.base.getX(), ship.base.getX() + 7, ship.base.getY(), ship.base.getZ(), ship.base.getZ() + 7);
        PadComputers.register(planet, ship.base.offset(-1, 1, 3), pad, ship.pad, false);
        for (int x = 2; x <= 5; x++)
            planet.setBlock(ship.base.offset(x, 3, 5), new Block[]{ModBlocks.GENERATOR, ModBlocks.BATTERY, ModBlocks.AIR_SUPPLY, ModBlocks.LOCAL_SCANNER}[x - 2].defaultBlockState(), 18);
        if (glass)
            for (int x = 0; x <= 6; x++)
                for (int z = 0; z <= 6; z++)
                    for (int y = 2; y <= 6; y++)
                        if (planet.getBlockState(ship.base.offset(x, y, z)).is(Blocks.STONE))
                            planet.setBlock(ship.base.offset(x, y, z), Blocks.GLASS.defaultBlockState(), 18);
        if (beacons)
            for (var at : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}, {3, 3}})
                planet.setBlock(ship.base.offset(at[0], 7, at[1]), Blocks.SEA_LANTERN.defaultBlockState(), 18);
        SystemsUpgrade.upgrade(planet, ShipTopology.resolve(planet, ship.padController()));
        ship.id = WorldStore.get(planet.getServer()).at(planet, ship.padController()).getStringOr("id", "");
        ships.add(ship);
        return ship;
    }

    List<Ship> movers()
    {
        return requestedCount == 0 ? ships : ships.subList(0, Math.min(8, ships.size()));
    }

    void refresh()
    {
        stations.replaceAll(station -> java.util.Objects.requireNonNull(WorldStore.get(server()).repository().find(station.getStringOr("id", "")), "Fixture station disappeared"));
    }

    private Ship representative(Ship ship) throws java.io.IOException
    {
        var level = ship.index >= launchedCount && (ship.index - launchedCount) % 2 == 1 ? WorldStore.level(server(), "nullspace:sol_ares") : planet;
        ship.home = level;
        var preset = com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.PRESETS.get(ship.index % com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.PRESETS.size());
        for (int x = (ship.base.getX() - 1) >> 4; x <= (ship.base.getX() + 32) >> 4; x++)
            for (int z = (ship.base.getZ() - 1) >> 4; z <= (ship.base.getZ() + 32) >> 4; z++)
            {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        // Planet terrain can rise above the fixed pad elevation. Clear its entire launch
        // column, including terrain above the 32-block build volume, before placing cargo.
        for (int x = ship.base.getX() - 1; x <= ship.base.getX() + 32; x++)
            for (int z = ship.base.getZ() - 1; z <= ship.base.getZ() + 32; z++)
                for (int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z); y > ship.base.getY(); y--)
                {
                    var at = new BlockPos(x, y, z);
                    if (!level.getBlockState(at).isAir())
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
                }
        com.nullandkale.nullspace.gameplay.shipyard.ShipYard.buildPad(level, ship.base, 32, 32, ship.pad);
        var pad = new PadScanner.Pad(ship.base.getX(), ship.base.getX() + 31, ship.base.getY(), ship.base.getZ(), ship.base.getZ() + 31);
        var origin = com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.origin(preset, pad, 32);
        for (var entry : com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.blocks(preset).entrySet())
        {
            var at = origin.offset(entry.getKey());
            level.setBlock(at, entry.getValue(), 18);
            if (entry.getValue().is(ModBlocks.GENERATOR))
                com.nullandkale.nullspace.gameplay.systems.FuelSystem.provision(level, at, 64);
        }
        ship.helm = origin.offset(preset.helm());
        var record = ShipTopology.resolve(level, ship.helm);
        ship.id = record.getStringOr("id", "");
        String address = com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces.address(level);
        if (address != null)
            record.putString("localSpace", address);
        record.putString("name", preset.name());
        record.putString("blueprint", preset.id());
        record.putString("owner", crew.get(ship.index % crew.size()).getUUID().toString());
        SystemsUpgrade.upgrade(level, record);
        record.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
        var shulker = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        shulker.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.Collections.nCopies(27, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64))));
        for (var at : BlockPos.betweenClosed(WorldStore.bounds(record).min(), WorldStore.bounds(record).max()))
            if ((level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity || level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) && level.getBlockEntity(at) instanceof net.minecraft.world.Container container)
            {
                for (int slot = 0; slot < container.getContainerSize(); slot++)
                    container.setItem(slot, slot % 3 == 0 ? shulker.copy() : new net.minecraft.world.item.ItemStack(slot % 3 == 1 ? net.minecraft.world.item.Items.COAL : net.minecraft.world.item.Items.BREAD, 64));
                container.setChanged();
            }
        var scan = ShipService.inspect(level, ship.helm).scan();
        var boarding = scan.boarding();
        for (int i = 0; i < 2; i++)
        {
            var pig = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            pig.setNoAi(true);
            pig.setPos(boarding.getX() + .5, boarding.getY(), boarding.getZ() + .5);
            level.addFreshEntity(pig);
        }
        var terminal = scan.blocks().stream().filter(at -> level.getBlockState(at).is(ModBlocks.TERMINAL)).findFirst();
        if (terminal.isEmpty())
        {
            terminal = BlockPos.betweenClosedStream(scan.bounds().min(), scan.bounds().max()).map(BlockPos::immutable).filter(at -> !at.equals(boarding) && !at.equals(boarding.above()) && level.getBlockState(at).isAir() && scan.pressure().breathable(at) && level.getBlockState(at.below()).isCollisionShapeFullBlock(level, at.below())).findFirst();
            if (terminal.isEmpty())
                throw new IllegalStateException("No cargo computer position in " + preset.id());
            level.setBlock(terminal.get(), ModBlocks.TERMINAL.defaultBlockState(), 18);
        }
        if (terminal.isPresent())
        {
            var computer = new com.nullandkale.nullspace.model.computer.VirtualComputer((name, args) -> 0);
            computer.files.write("/home/crew/cargo", "cargo manifest\n".repeat(256));
            var data = new net.minecraft.nbt.CompoundTag();
            data.putString("id", UUID.nameUUIDFromBytes(("perf-computer-" + ship.index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            data.putString("owner", record.getStringOr("owner", ""));
            data.putBoolean("powered", true);
            data.put("image", com.nullandkale.nullspace.model.computer.ComputerImageCodec.encode(computer.save()));
            var computers = new net.minecraft.nbt.CompoundTag();
            computers.put(Long.toString(terminal.get().subtract(WorldStore.pos(record, "base")).asLong()), data);
            record.put("computers", computers);
        }
        ships.add(ship);
        return ship;
    }

    void stationFixture(int count)
    {
        for (int index = 0; index < count; index++)
        {
            var metadata = new net.minecraft.nbt.CompoundTag();
            metadata.putBoolean("worldGenerated", true);
            var station = com.nullandkale.nullspace.gameplay.station.StationBuilds.queueAmbient(server(), UUID.nameUUIDFromBytes(("perf-station-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "Perf Station " + index, com.nullandkale.nullspace.model.galaxy.LocalSpaces.HOME, new net.minecraft.world.phys.Vec3(2000 + index * 300, 0, 2000), com.nullandkale.nullspace.worldgen.stations.StationBlueprint.build(com.nullandkale.nullspace.worldgen.stations.StationLayout.waypoint()), metadata);
            stations.add(station);
        }
    }

    boolean parkCrew()
    {
        var boardings = new ArrayList<BlockPos>();
        for (var ship : movers())
        {
            var record = record(ship);
            var level = WorldStore.level(server(), record.getStringOr("dimension", ""));
            var bounds = WorldStore.bounds(record);
            for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
                for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                    level.setChunkForced(x, z, true);
            try
            {
                boardings.add(ShipService.preflight(level, WorldStore.pos(record, "controller")).scan().boarding());
            }
            catch (IllegalArgumentException loading)
            {
                if (loading.getMessage().contains("loading"))
                    return false;
                throw loading;
            }
        }
        var ids = crew.stream().map(p -> p.getUUID().toString()).collect(java.util.stream.Collectors.toSet());
        for (var ship : ships)
            record(ship).getListOrEmpty("crew").removeIf(t -> ids.contains(((net.minecraft.nbt.CompoundTag) t).getStringOr("id", "")));
        for (int index = 0; index < movers().size(); index++)
        {
            var ship = movers().get(index);
            ship.actor = crew.get(index);
            var record = record(ship);
            var level = WorldStore.level(server(), record.getStringOr("dimension", ""));
            var boarding = boardings.get(index);
            ship.actor.teleportTo(level, boarding.getX() + .5, boarding.getY(), boarding.getZ() + .5, Set.of(), 0, 0, true);
        }
        return true;
    }

    net.minecraft.server.MinecraftServer server()
    {
        return planet.getServer();
    }

    /** The record of the ship, wherever it is now. */
    net.minecraft.nbt.CompoundTag record(Ship ship)
    {
        return WorldStore.get(planet.getServer()).repository().find(ship.id);
    }

    boolean inSpace(Ship ship)
    {
        var record = record(ship);
        return record != null && record.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString());
    }

    /** Launches or lands the ship with its own actor. Returns the outcome message when the move did not complete. */
    String move(Ship ship)
    {
        var server = planet.getServer();
        var record = record(ship);
        var level = WorldStore.level(server, record.getStringOr("dimension", ""));
        var controller = WorldStore.pos(record, "controller");
        var base = WorldStore.pos(record, "base");
        var actor = ship.actor;
        ShipService.Inspection inspection;
        try
        {
            inspection = ShipService.preflight(level, controller);
        }
        catch (IllegalArgumentException loading)
        {
            return loading.getMessage();
        }
        var boarding = inspection.scan().boarding();
        actor.teleportTo(level, boarding.getX() + .5, boarding.getY(), boarding.getZ() + .5, Set.of(), 0, 0, true);
        boolean launch = !inSpace(ship);
        long started = System.nanoTime();
        stageClock.accept(started);
        String problem;
        try
        {
            var result = TravelService.transfer(actor, controller, launch ? "" : ship.pad, inspection);
            problem = result.outcome() == TravelService.Outcome.COMPLETED ? "" : result.message();
        }
        catch (IllegalArgumentException refused)
        {
            // Expected refusals, such as chunk entities still loading, arrive as exceptions.
            problem = refused.getMessage();
        }
        double ms = (System.nanoTime() - started) / 1e6;
        var after = record(ship);
        var at = WorldStore.pos(after, "base");
        listener.accept(new Move(ship.index, launch, problem.isEmpty(), ms, after.getStringOr("dimension", ""), at.getX(), at.getZ()));
        return problem;
    }
}
