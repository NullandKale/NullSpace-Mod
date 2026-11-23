package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ControllerChecks;
import com.nullandkale.nullspace.gameplay.navigation.PlanetApproach;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Landing flies first: the ship turns to the planet and flies close, burning fuel, before the landing transfer. With no fuel there is an emergency
 * landing, which crash-lands on the pad and breaks machines but leaves the bridge its air.
 */
public final class PlanetApproachGameTest
{
    private static ShipDestination pad(ConcurrentShipActionsGameTest.Client client)
    {
        return new ShipDestination(ShipDestination.Kind.PAD, client.pad(), BlockPos.ZERO, LocalSpaces.address(client.fixture().ship()), "Approach pad", "READY");
    }

    /** The token of the landing transfer the approach handed over to, as a client learns it from the progress packet. */
    private static UUID token(ConcurrentShipActionsGameTest.Client client)
    {
        client.connection().flushChannel();
        client.channel().runPendingTasks();
        UUID latest = null;
        Object packet;
        while ((packet = client.channel().readOutbound()) != null)
        {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof TravelPackets.Progress progress && !progress.done())
                latest = progress.id();
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return latest;
    }

    private static void waitUntil(GameTestHelper ctx, BooleanSupplier condition, int ticks, String what, Runnable then)
    {
        waitUntil(ctx, condition, ticks, what, () -> "", then);
    }

    /** As above, saying what the state was when it timed out. */
    private static void waitUntil(GameTestHelper ctx, BooleanSupplier condition, int ticks, String what, java.util.function.Supplier<String> state, Runnable then)
    {
        if (condition.getAsBoolean())
            then.run();
        else if (ticks <= 0)
            ctx.fail("Timed out waiting for " + what + ": " + state.get());
        else
            TestWaits.afterLoaded(ctx, 2, () -> waitUntil(ctx, condition, ticks - 2, what, state, then));
    }

    /** Hands the waiting landing transfer its two client acknowledgements, then runs {@code then} once the ship stands on the pad. */
    private static void arrive(GameTestHelper ctx, ConcurrentShipActionsGameTest.Client client, String id, Runnable then)
    {
        var server = ctx.getLevel().getServer();
        UUID[] token = {null};
        waitUntil(ctx, () -> (token[0] = token[0] != null ? token[0] : token(client)) != null, 200, "the landing transfer to start", () ->
        {
            client.ready(token[0], false);
            waitUntil(ctx, () -> client.player().level() == client.fixture().surface, 300, "the hull to move to the pad", () ->
            {
                client.ready(token[0], true);
                waitUntil(ctx, () -> !TransferLocks.physicalBusy(client.player()) && WorldStore.get(server).repository().find(id).getStringOr("dimension", "").equals(client.fixture().surface.dimension().identifier().toString()), 200, "the landing to finish", then);
            });
        });
    }

    private static long count(ServerLevel level, CompoundTag ship, Block block)
    {
        return BlockPos.betweenClosedStream(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()).filter(p -> level.getBlockState(p).is(block)).count();
    }

    @GameTest(environment = "nullspace:planet_approach", maxTicks = 1600)
    public void landingFliesCloseToThePlanetBurningFuelThenLandsOnThePad(GameTestHelper ctx) throws Exception
    {
        var client = ConcurrentShipActionsGameTest.client(ctx, 62);
        client.board();
        var server = ctx.getLevel().getServer();
        var start = client.fixture().ship();
        String id = client.fixture().id;
        // Over Terra, a few thousand blocks out, as a ship arriving in orbit is.
        start.putDouble("simX", 0);
        start.putDouble("simY", 0);
        start.putDouble("simZ", 0);
        var space = client.fixture().space;
        // Another ship's commit can replace the store's records, so read the ship afresh each time.
        java.util.function.Supplier<CompoundTag> now = () -> WorldStore.get(server).repository().find(id);
        TestWaits.afterLoaded(ctx, 20, () ->
        {
            var ship = now.get();
            double before = distance(server, ship);
            int fuel = FuelSystem.available(space, ship);
            ctx.assertTrue(before > 1000, "The fixture starts well away from the planet: " + before);
            ctx.assertTrue(ControllerChecks.reason(client.player(), ship, pad(client), ShipControl.Action.LAND).isEmpty(), "Land is available with fuel");
            ctx.assertTrue(!ControllerChecks.reason(client.player(), ship, pad(client), ShipControl.Action.EMERGENCY_LAND).isEmpty(), "No emergency landing while there is fuel to land");
            PlanetApproach.begin(client.player(), ship, pad(client), false);
            ctx.assertTrue(PlanetApproach.active(ship) && !TransferLocks.physicalBusy(client.player()), "The approach flies first; nothing is transferred yet");
            TestWaits.afterLoaded(ctx, 60, () ->
            {
                var later = now.get();
                ctx.assertTrue(PlanetApproach.active(later), "Still approaching after three seconds");
                ctx.assertTrue(distance(server, later) < before - 100, "The ship flies toward the planet: " + before + " -> " + distance(server, later));
                ctx.assertTrue(FuelSystem.available(space, later) < fuel, "The approach burns fuel");
                waitUntil(ctx, () -> !PlanetApproach.active(now.get()), 900, "the approach to reach the planet", () -> "distance " + distance(server, now.get()) + ", approach " + now.get().getCompoundOrEmpty("planetApproach") + ", busy " + TransferLocks.busy(server, now.get()) + ", pose " + SceneService.pose(now.get()), () ->
                {
                    var there = now.get();
                    ctx.assertTrue(distance(server, there) < 2, "The approach ends close to the planet: " + distance(server, there));
                    arrive(ctx, client, id, () ->
                    {
                        var landed = WorldStore.get(server).repository().find(id);
                        ctx.assertTrue(count(client.fixture().surface, landed, BrokenMachines.ENGINE) == 0, "An ordinary landing breaks nothing");
                        client.close();
                        ctx.succeed();
                    });
                });
            });
        });
    }

    @GameTest(environment = "nullspace:planet_approach", maxTicks = 1600)
    public void withNoFuelAnEmergencyLandingCrashesOnThePadAndTheBridgeKeepsItsAirMachines(GameTestHelper ctx) throws Exception
    {
        var client = ConcurrentShipActionsGameTest.client(ctx, 63);
        client.board();
        var server = ctx.getLevel().getServer();
        var start = client.fixture().ship();
        String id = client.fixture().id;
        java.util.function.Supplier<CompoundTag> now = () -> WorldStore.get(server).repository().find(id);
        start.putDouble("simX", 900); // Apart from the other test's ship, which flies from the same orbit at the same time.
        start.putDouble("simY", 0);
        start.putDouble("simZ", 0);
        var space = client.fixture().space;
        TestWaits.afterLoaded(ctx, 20, () ->
        {
            var ship = now.get();
            FuelSystem.spend(space, ship, FuelSystem.available(space, ship));
            ctx.assertTrue(FuelSystem.available(space, ship) == 0, "The generators are empty");
            ctx.assertTrue(!ControllerChecks.reason(client.player(), ship, pad(client), ShipControl.Action.ENGAGE).isEmpty(), "Engage is blocked without fuel");
            String emergency = ControllerChecks.reason(client.player(), ship, pad(client), ShipControl.Action.EMERGENCY_LAND);
            ctx.assertTrue(emergency.isEmpty(), "An emergency landing is offered without fuel: " + emergency);
            long engines = count(space, ship, ModBlocks.ENGINE);
            double before = distance(server, ship);
            ctx.assertTrue(before > 1000, "The fixture starts well away from the planet: " + before);
            PlanetApproach.begin(client.player(), ship, pad(client), true);
            ctx.assertTrue(PlanetApproach.emergency(ship) && !ControllerChecks.reason(client.player(), ship, pad(client), ShipControl.Action.DISENGAGE).isEmpty(), "A falling ship cannot stop");
            TestWaits.afterLoaded(ctx, 40, () -> ctx.assertTrue(PlanetApproach.active(now.get()) && distance(server, now.get()) < before - 100, "The ship falls toward the planet before it lands: " + before + " -> " + distance(server, now.get())));
            waitUntil(ctx, () -> !PlanetApproach.active(now.get()), 900, "the fall to reach the planet", () -> arrive(ctx, client, id, () -> TestWaits.afterLoaded(ctx, 6, () ->
            {
                var level = client.fixture().surface;
                var landed = WorldStore.get(server).repository().find(id);
                ctx.assertTrue(!landed.getBooleanOr("crashLanding", false), "The crash is applied once");
                ctx.assertTrue(engines == 0 || count(level, landed, BrokenMachines.ENGINE) > 0, "The crash breaks engines");
                var controller = WorldStore.pos(landed, "controller");
                var generators = BlockPos.betweenClosedStream(WorldStore.bounds(landed).min(), WorldStore.bounds(landed).max()).map(BlockPos::immutable).filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR) || level.getBlockState(p).is(BrokenMachines.GENERATOR)).sorted(java.util.Comparator.comparingDouble(p -> p.distSqr(controller))).toList();
                ctx.assertTrue(generators.isEmpty() || level.getBlockState(generators.getFirst()).is(ModBlocks.GENERATOR), "The generator nearest the helm survives the crash");
                ctx.assertTrue(count(level, landed, ModBlocks.AIR_SUPPLY) > 0 || count(level, landed, BrokenMachines.AIR_SUPPLY) == 0, "An air supply survives for the bridge");
                ctx.assertTrue(SceneService.observer(client.player()) != null, "The crew is still aboard their ship");
                client.close();
                ctx.succeed();
            })));
        });
    }

    private static double distance(net.minecraft.server.MinecraftServer server, CompoundTag ship)
    {
        return PlanetApproach.distance(server, ship);
    }
}
