package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.computer.ComputerImageCodec;
import com.nullandkale.nullspace.model.computer.ScriptLanguage;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.PlanetSky;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.TestZones;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;

public final class ExpansionGameTest
{
    private record Fixture(BlockPos base, BlockPos helm, BlockPos terminal, CompoundTag ship)
    {}

    private static Fixture fixture(GameTestHelper ctx, int offset)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(offset, 64, offset));
        var helm = ShipTestFixture.build(level, base);
        level.setBlock(base.offset(1, 3, 6), ModBlocks.GENERATOR.defaultBlockState(), 18);
        level.setBlock(base.offset(2, 3, 6), ModBlocks.BATTERY.defaultBlockState(), 18);
        var terminal = base.offset(3, 3, 6);
        level.setBlock(terminal, ModBlocks.TERMINAL.defaultBlockState(), 18);
        level.setBlock(base.offset(4, 3, 6), ModBlocks.LOCAL_SCANNER.defaultBlockState(), 18);
        // A Local Scanner with an upgrade reaches other places.
        level.setBlock(base.offset(5, 3, 6), ModBlocks.LOCAL_SCANNER.defaultBlockState(), 18);
        ((MachineBlockEntity) level.getBlockEntity(base.offset(5, 3, 6))).setItem(0, new net.minecraft.world.item.ItemStack(ModBlocks.SYSTEM_UPGRADE));
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("dimension", level.dimension().identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", helm.asLong());
        ship.putInt("width", 8);
        ship.putInt("depth", 8);
        ship.put("crew", new ListTag());
        WorldStore.get(level.getServer()).ships().add(ship);
        SystemsUpgrade.upgrade(level, ship);
        SystemsConsole.automation(level, ship, terminal, "power engines 0");
        SystemsConsole.automation(level, ship, terminal, "power cameras 2");
        return new Fixture(base, helm, terminal, ship);
    }

    @GameTest(maxTicks = 200)
    public void flatSurfacesExistAndStarterPadsAreIdempotent(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        for (var d : LocalSpaces.DESTINATIONS)
        {
            var level = server.getLevel(PlanetSurfaces.dimension(server, d.id()));
            ctx.assertTrue(level != null, "Surface dimension must exist: " + d.id());
            if (d.id().equals(LocalSpaces.HOME))
                continue;
            // P9: surfaces are real terrain, so there is ground somewhere above the bottom of the world.
            ctx.assertTrue(level.getChunk(3, 3).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) > level.getMinY() + 8, "Surface has terrain");
            PlanetSurfaces.ensureLanding(server, d.id());
            var pad = PadRegistry.read(server).stream().filter(p -> p.dimension().equals(level.dimension().identifier().toString())).findFirst().orElseThrow();
            PadRegistry.validate(level, pad);
            var at = pad.bounds();
            var marker = new BlockPos(at.minX() + 2, at.y() + 1, at.minZ() + 2);
            level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            PlanetSurfaces.ensureLanding(server, d.id());
            ctx.assertTrue(level.getBlockState(marker).is(Blocks.GOLD_BLOCK), "Returning to a site must preserve construction");
            level.setBlock(marker, Blocks.AIR.defaultBlockState(), 18);
            ctx.assertTrue(PlanetSurfaces.breathable(level) == d.id().equals(LocalSpaces.HOME), "Surface breathing must follow metadata");
        }
        ctx.succeed();
    }

    @GameTest
    public void sensorsUseTheConnectedBusAndExposeDistinctReadings(GameTestHelper ctx)
    {
        var f = fixture(ctx, 48000);
        var level = ctx.getLevel();
        ctx.assertTrue(ShipSensors.scan(level, f.ship, false).contains("LOCAL SPACE") && ShipSensors.scan(level, f.ship, true).contains(TestZones.otherSystem(level.getServer())), "Local and long-range scanners expose their own domains");
        SystemsConsole.automation(level, f.ship, f.terminal, "power cameras 0");
        boolean rejected = false;
        try
        {
            ShipSensors.scan(level, f.ship, true);
        }
        catch (IllegalArgumentException expected)
        {
            rejected = true;
        }
        ctx.assertTrue(rejected, "No sensor power must prevent fresh scans");
        ctx.succeed();
    }

    @GameTest
    public void computerSessionsAndDiskAreServerOwned(GameTestHelper ctx)
    {
        var f = fixture(ctx, 49000);
        var level = ctx.getLevel();
        var p = ctx.makeMockServerPlayerInLevel();
        var stranger = ctx.makeMockServerPlayerInLevel();
        p.teleportTo(f.base.getX() + 3.5, f.base.getY() + 3, f.base.getZ() + 4.5);
        stranger.teleportTo(p.getX(), p.getY(), p.getZ());
        ComputerService.open(p, f.terminal);
        var id = ComputerService.session(p);
        ctx.assertTrue(!id.equals(new UUID(0, 0)), "Opening a computer must issue a real session");
        ComputerService.input(p, new ComputerService.Input(id, "echo original > flightlog\r", false));
        ComputerService.flush(level.getServer());
        String before = f.ship.getCompoundOrEmpty("computers").toString();
        ComputerService.input(stranger, new ComputerService.Input(id, "rm flightlog\r", false));
        ComputerService.flush(level.getServer());
        ctx.assertTrue(before.equals(f.ship.getCompoundOrEmpty("computers").toString()), "A second player cannot use another viewer's keyboard session");
        var saved = f.ship.getCompoundOrEmpty("computers");
        boolean persisted = false;
        for (String key : saved.keySet())
            persisted |= ComputerImageCodec.decode(saved.getCompoundOrEmpty(key).get("image")).contains("original");
        ctx.assertTrue(persisted, "Guest file must be persisted with the ship");
        level.getServer().getPlayerList().remove(p);
        level.getServer().getPlayerList().remove(stranger);
        ctx.succeed();
    }

    @GameTest
    public void surfaceSkyHasAnExplicitFrameAndOmitsItsOwnBody(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().getLevel(PlanetSurfaces.dimension(ctx.getLevel().getServer(), "sol_luna"));
        var scene = new CompoundTag();
        var subjects = new ListTag();
        var item = new CompoundTag();
        item.putString("id", "surface");
        subjects.add(item);
        scene.putString("observer", "surface");
        scene.put("subjects", subjects);
        ctx.assertTrue(PlanetSky.decorate(scene, level, new BlockPos(0, 65, 0)), "Known surface gets a celestial frame");
        String body = scene.getStringOr("surfaceBody", "");
        var original = SceneCatalog.region(level.getServer(), "sol_luna");
        var copy = PlanetSky.skyCatalog(original, body);
        ctx.assertTrue(!body.isEmpty() && copy.getListOrEmpty("bodies").size() == original.getListOrEmpty("bodies").size() - 1, "Only the standing body's representation is omitted");
        ctx.assertTrue(!scene.getBooleanOr("surfaceAtmosphere", true), "Luna has no breathable atmospheric sky");
        ctx.succeed();
    }

    @GameTest
    public void planetSkyCarriesOpticalProfileEvenWhenItsBodyIsOmitted(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().overworld();
        var scene = new CompoundTag();
        var subjects = new ListTag();
        var observer = new CompoundTag();
        observer.putString("id", "surface");
        subjects.add(observer);
        scene.putString("observer", "surface");
        scene.put("subjects", subjects);
        ctx.assertTrue(PlanetSky.decorate(scene, level, new BlockPos(0, 65, 0)), "Terra has a sky");
        var profile = AtmosphereProfile.decode(scene.getCompoundOrEmpty("atmosphereProfile"));
        var catalog = SceneCatalog.region(level.getServer(), PlanetSurfaces.address(level));
        var original = catalog.copy();
        var body = catalog.getListOrEmpty("bodies").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("surfaceBody", ""))).findFirst().orElseThrow();
        ctx.assertTrue(scene.getBooleanOr("surfaceAtmosphere", false), "Optical atmosphere is independent of ship air simulation");
        ctx.assertTrue(Math.abs(profile.radius() - body.getDoubleOr("radius", 0)) < .001, "Optical geometry uses the same radius as the celestial body");
        ctx.assertTrue(scene.getDoubleOr("atmosphereY", Double.NaN) == body.getDoubleOr("y", 0), "Planet center survives removal from the sky's opaque body catalog");
        ctx.assertTrue(scene.getDoubleOr("surfaceRadius", 0) == profile.radius(), "Flat-world celestial frame retains the standing body's radius");
        for (var axis : new String[]{"X", "Y", "Z"})
            ctx.assertTrue(scene.getDoubleOr("surfaceCenter" + axis, Double.NaN) == body.getDoubleOr(axis.toLowerCase(java.util.Locale.ROOT), 0), "Flat-world celestial frame retains the standing body's center on " + axis);
        var sky = PlanetSky.skyCatalog(catalog, scene.getStringOr("surfaceBody", ""));
        ctx.assertTrue(catalog.equals(original), "Building a sky catalog cannot mutate the shared definition");
        ctx.assertTrue(sky.getListOrEmpty("bodies").size() == original.getListOrEmpty("bodies").size() - 1, "The optical profile does not reintroduce an opaque planet below native terrain");
        ctx.succeed();
    }

    @GameTest(maxTicks = 80)
    public void scriptFlightExpiresAndStopsWhenTheComputerIsRemoved(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
        var base = ctx.absolutePos(new BlockPos(62000, 90, 62000));
        var helm = ShipTestFixture.build(level, base);
        var terminal = base.offset(4, 3, 6);
        level.setBlock(terminal, ModBlocks.TERMINAL.defaultBlockState(), 18);
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", helm.asLong());
        ship.putInt("width", 8);
        ship.putInt("depth", 8);
        ship.putDouble("simX", -2500);
        ship.put("crew", new ListTag());
        WorldStore.get(level.getServer()).ships().add(ship);
        for (int x = -1; x <= 8; x++)
            for (int z = -1; z <= 8; z++)
            {
                var pos = base.offset(x, 0, z);
                level.getChunkSource().addTicketWithRadius(SceneSnapshots.CAMERA_TICKET, new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), 0);
            }
        String shipId = ship.getStringOr("id", "");
        java.util.function.Supplier<CompoundTag> current = () -> WorldStore.get(level.getServer()).ships().stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(shipId)).findFirst().orElseThrow();
        ctx.runAfterDelay(5, () ->
        {
            int started = level.getServer().getTickCount();
            Piloting.automate(level, current.get(), terminal, "native-script-test", FlightDynamics.FORWARD, 4);
            ctx.runAfterDelay(2, () -> ctx.assertTrue(SceneService.pose(current.get()).position().x > -2499.99, "Script thrust must use real flight dynamics"));
            ctx.runAfterDelay(6, () ->
            {
                ctx.assertTrue(current.get().getDoubleOr("simVX", 1) == 0, "Expired commands must stop motion (server elapsed=" + (level.getServer().getTickCount() - started) + ", velocity=" + current.get().getDoubleOr("simVX", 1) + ")");
                Piloting.automate(level, current.get(), terminal, "native-script-test", FlightDynamics.FORWARD, 20);
                level.setBlock(terminal, Blocks.AIR.defaultBlockState(), 18);
            });
            ctx.runAfterDelay(9, () ->
            {
                ctx.assertTrue(current.get().getDoubleOr("simVX", 1) == 0, "Removing a computer must revoke its flight command");
                WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(shipId));
                ctx.succeed();
            });
        });
    }

    @GameTest
    public void longScannerMenuFitsItsWireProtocol(GameTestHelper ctx)
    {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), ctx.getLevel().registryAccess());
        try
        {
            var packet = new TerminalPackets.Open(BlockPos.ZERO, "sensor status", "long_range_scanner", "0");
            TerminalPackets.Open.CODEC.encode(buffer, packet);
            var result = TerminalPackets.Open.CODEC.decode(buffer);
            ctx.assertTrue(result.mode().equals("long_range_scanner"), "Every registered equipment mode must survive the actual terminal codec");
            ctx.succeed();
        }
        finally
        {
            buffer.release();
        }
    }

    @GameTest
    public void partialHullAvailabilityNeverLoadsTheMissingChunks(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
        var base = ctx.absolutePos(new BlockPos(7200000, 90, 7200000));
        var terminal = base.offset(2, 2, 2);
        var missing = base.offset(31, 0, 31);
        level.setBlock(terminal, ModBlocks.TERMINAL.defaultBlockState(), 18);
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.asLong());
        ship.putLong("controller", terminal.asLong());
        ship.putInt("width", 32);
        ship.putInt("depth", 32);
        ctx.assertTrue(!level.hasChunkAt(missing), "Cold test needs an unloaded hull corner");
        WorldStore.get(level.getServer()).ships().add(ship);
        ctx.assertTrue(!ShipTopology.loaded(level, ship), "A loaded terminal alone is insufficient for world actions");
        boolean waiting = false;
        try
        {
            Piloting.automate(level, ship, terminal, "cold-computer", FlightDynamics.FORWARD, 10);
        }
        catch (ScriptLanguage.Unavailable expected)
        {
            waiting = true;
        }
        ctx.assertTrue(waiting, "Flight must yield before reading disconnected hull hardware");
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(level, terminal.getX() + .5, terminal.getY(), terminal.getZ() + .5, Set.of(), 0, 0, true);
        ComputerService.open(player, terminal);
        ctx.assertTrue(ComputerService.session(player).equals(new UUID(0, 0)), "Partial hulls must defer equipment access");
        ctx.assertTrue(!level.hasChunkAt(missing), "Availability queries and computer validation must never load the missing hull corner");
        level.getServer().getPlayerList().remove(player);
        WorldStore.get(level.getServer()).ships().remove(ship);
        ctx.succeed();
    }

    @GameTest
    public void automationCannotOperateAcrossDisconnectedHardware(GameTestHelper ctx)
    {
        var f = fixture(ctx, 50000);
        var level = ctx.getLevel();
        SystemsConsole.automation(level, f.ship, f.terminal, "power air 0");
        ctx.assertTrue(ShipSystems.power(level, f.ship, PowerBus.System.AIR) == 0, "Native commands update actual grants");
        level.setBlock(f.terminal, Blocks.AIR.defaultBlockState(), 18);
        boolean rejected = false;
        try
        {
            SystemsConsole.automation(level, f.ship, f.terminal, "power air 2");
        }
        catch (IllegalArgumentException expected)
        {
            rejected = true;
        }
        ctx.assertTrue(rejected, "Removed hardware must lose its capability immediately");
        ctx.succeed();
    }
}
