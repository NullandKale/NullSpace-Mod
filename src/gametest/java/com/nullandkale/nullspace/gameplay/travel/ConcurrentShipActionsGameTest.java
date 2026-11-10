package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorageGameTest;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.netty.channel.embedded.EmbeddedChannel;

/** Real handshake tokens, world transfers and independent release of per-vessel reservations. */
public final class ConcurrentShipActionsGameTest
{
    public record Client(ShipStorageGameTest.Fixture fixture, Connection connection, EmbeddedChannel channel)
    {
        public ServerPlayer player()
        {
            return fixture.player;
        }

        public void board()
        {
            var at = StationGameTest.boardingPoint(fixture.space, fixture.ship());
            player().teleportTo(fixture.space, at.x, at.y, at.z, Set.of(), 0, 0, true);
        }

        UUID land(String pad)
        {
            var controller = WorldStore.pos(fixture.ship(), "controller");
            return request(controller, "id:" + pad);
        }

        UUID request(BlockPos controller, String destination)
        {
            TravelService.request(player(), controller, destination, ShipService.preflight(player().level(), controller));
            connection.flushChannel();
            channel.runPendingTasks();
            TravelPackets.Progress latest = null;
            Object packet;
            while ((packet = channel.readOutbound()) != null)
            {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof TravelPackets.Progress progress && !progress.done())
                    latest = progress;
                io.netty.util.ReferenceCountUtil.release(packet);
            }
            if (latest == null)
                throw new AssertionError("Preparation did not issue an acknowledgement token");
            return latest.id();
        }

        String pad()
        {
            return PadRepository.get(fixture.surface.getServer()).dimension(fixture.surface.dimension().identifier().toString()).stream().filter(p -> p.computer().equals(fixture.computer)).findFirst().orElseThrow().id();
        }

        public void ready(UUID token, boolean arrived)
        {
            TravelService.ready(player(), new TravelPackets.Ready(token, arrived));
        }

        public void close()
        {
            fixture.close();
            channel.finishAndReleaseAll();
        }
    }

    public static Client client(GameTestHelper ctx, int index) throws Exception
    {
        var f = new ShipStorageGameTest.Fixture(ctx, index);
        var id = f.player.getUUID();
        f.close();
        var cookie = CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(id, "parallel-" + index), false);
        f.player = new ServerPlayer(ctx.getLevel().getServer(), ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, f.player, cookie);
        f.player.setGameMode(GameType.SURVIVAL);
        f.home();
        return new Client(f, connection, channel);
    }

    private static net.minecraft.nbt.CompoundTag ground(Client client)
    {
        var f = client.fixture;
        var preset = ShipBlueprints.get("rivet_bolt");
        var origin = f.padBase.above();
        for (var e : ShipBlueprints.blocks(preset).entrySet())
        {
            f.surface.setBlock(origin.offset(e.getKey()), e.getValue(), 18);
            if (e.getValue().is(ModBlocks.GENERATOR))
                FuelSystem.provision(f.surface, origin.offset(e.getKey()), 64);
        }
        var controller = origin.offset(preset.helm());
        var ship = ShipTopology.resolve(f.surface, controller);
        SystemsUpgrade.upgrade(f.surface, ship);
        ShipSystems.tick(f.surface, ship);
        var at = StationGameTest.boardingPoint(f.surface, ship);
        client.player().teleportTo(f.surface, at.x, at.y, at.z, Set.of(), 0, 0, true);
        return ship;
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 160)
    public void simultaneousLaunchesReserveDistinctSpaceSlotsBeforeEitherShipMoves(GameTestHelper ctx) throws Exception
    {
        var a = client(ctx, 30);
        var b = client(ctx, 31);
        var server = ctx.getLevel().getServer();
        var groundA = ground(a);
        var groundB = ground(b);
        var controllerA = WorldStore.pos(groundA, "controller");
        var controllerB = WorldStore.pos(groundB, "controller");
        ctx.runAfterDelay(20, () ->
        {
            var free = new ArrayList<Integer>();
            for (int slot = 0; slot <= 7000 && free.size() < 2; slot++)
                if (TransferLocks.spaceSlotFree(server, slot))
                    free.add(slot);
            ctx.assertTrue(free.size() == 2, "Two physical regions are available");
            var tokenA = a.request(controllerA, "");
            var tokenB = b.request(controllerB, "");
            ctx.assertTrue(!TransferLocks.spaceSlotFree(server, free.get(0)) && !TransferLocks.spaceSlotFree(server, free.get(1)), "Queued launches reserve separate destinations before either client acknowledges");
            boolean refused = false;
            try
            {
                a.request(controllerA, "");
            }
            catch (IllegalArgumentException alreadyTravelling)
            {
                refused = alreadyTravelling.getMessage().contains("already");
            }
            ctx.assertTrue(refused, "The same ship cannot queue another transfer");
            a.ready(tokenA, false);
            b.ready(tokenB, false);
            ctx.runAfterDelay(35, () ->
            {
                ctx.assertTrue(a.player().level() == a.fixture.space && b.player().level() == b.fixture.space, "Both ships launch independently");
                var store = WorldStore.get(server);
                var movedA = store.at(a.player().level(), a.player().blockPosition());
                var movedB = store.at(b.player().level(), b.player().blockPosition());
                ctx.assertTrue(WorldStore.pos(movedA, "base").getX() == free.get(0) * TravelService.SPACE_SPACING && WorldStore.pos(movedB, "base").getX() == free.get(1) * TravelService.SPACE_SPACING, "Each commit uses its own original reservation");
                ctx.assertTrue(!a.fixture.surface.getBlockState(controllerA).is(ModBlocks.COMPUTER) && !b.fixture.surface.getBlockState(controllerB).is(ModBlocks.COMPUTER), "Both authoritative source copies are removed");
                a.ready(tokenA, true);
                b.ready(tokenB, true);
                ctx.runAfterDelay(5, () ->
                {
                    a.close();
                    b.close();
                    ctx.succeed();
                });
            });
        });
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 160)
    public void recoveryOfALaterTransferKeepsAnEarlierShipCommit(GameTestHelper ctx) throws Exception
    {
        var a = client(ctx, 34);
        var b = client(ctx, 35);
        ctx.runAfterDelay(20, () ->
        {
            var first = ShipStorage.move(a.player(), a.fixture.computer, a.fixture.ship());
            ctx.assertTrue(first.outcome() == TravelService.Outcome.COMPLETED, first.message());
            var firstBase = WorldStore.pos(a.fixture.ship(), "base");
            TravelService.failureInjector = stage ->
            {
                if (stage.equals("SOURCE_CLEARED"))
                    throw new TravelService.SimulatedCrash();
            };
            boolean crashed = false;
            try
            {
                ShipStorage.move(b.player(), b.fixture.computer, b.fixture.ship());
            }
            catch (TravelService.SimulatedCrash expected)
            {
                crashed = true;
            }
            finally
            {
                TravelService.failureInjector = stage ->
                {};
            }
            ctx.assertTrue(crashed, "The second transfer reaches its physical mutation checkpoint");
            TransferRecovery.recover(ctx.getLevel().getServer());
            ctx.assertTrue(a.fixture.ship().getStringOr("dimension", "").equals("minecraft:overworld") && WorldStore.pos(a.fixture.ship(), "base").equals(firstBase), "Rollback preserves the earlier committed ship metadata");
            ctx.assertTrue(b.fixture.ship().getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) && b.fixture.space.getBlockState(WorldStore.pos(b.fixture.ship(), "controller")).is(ModBlocks.COMPUTER), "Only the failed ship returns to its original authoritative source");
            a.close();
            b.close();
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 200)
    public void separateLandingsKeepTheirTokensLocksAndMetadataWhileSharingNoServerBarrier(GameTestHelper ctx) throws Exception
    {
        var a = client(ctx, 20);
        var b = client(ctx, 21);
        var c = client(ctx, 22);
        a.board();
        b.board();
        c.board();
        var server = ctx.getLevel().getServer();
        ctx.runAfterDelay(20, () ->
        {
            var tokenA = a.land(a.pad());
            boolean denied = false;
            try
            {
                b.land(a.pad());
            }
            catch (IllegalArgumentException conflict)
            {
                denied = conflict.getMessage().contains("pad");
            }
            ctx.assertTrue(denied && !TransferLocks.physicalBusy(b.player()), "Only the shared destination pad is rejected; no second request is left behind");
            var station = StationBuilds.queue(server, UUID.randomUUID().toString(), "Independent construction", LocalSpaces.HOME, new Vec3(8000, 200, 8000), c.player().getUUID().toString(), StationBlueprint.build(StationLayout.waypoint()), false);
            ctx.assertTrue(station != null && station.contains("stationBuild"), "Unrelated station construction can start while a ship prepares to land");
            var tokenB = b.land(b.pad());
            double previousX = c.fixture.ship().getDoubleOr("simX", 0);
            double previousZ = c.fixture.ship().getDoubleOr("simZ", 0);
            c.fixture.ship().putDouble("simX", -2800);
            c.fixture.ship().putDouble("simZ", -2800);
            ctx.assertTrue(DockingManeuver.readiness(c.player(), c.fixture.ship()).ready(), "A third ship's docking controls remain available during two landings and station" + " construction");
            // This is only a local-flight readiness probe. Keep later tests' docking area clear,
            // including when another test explicitly saves and reloads the vessel repository.
            c.fixture.ship().putDouble("simX", previousX);
            c.fixture.ship().putDouble("simZ", previousZ);
            WorldStore.get(server).ships().remove(station); // Cancel the unpaid test plan before any blocks are built.
            ctx.assertTrue(!tokenA.equals(tokenB) && TransferLocks.physicalBusy(a.player()) && TransferLocks.physicalBusy(b.player()), "Both ships hold independent preparation jobs");
            ctx.assertTrue(TransferLocks.locked(a.fixture.space, WorldStore.pos(a.fixture.ship(), "controller")) && TransferLocks.locked(b.fixture.surface, b.fixture.padBase.above()), "Source hull and destination pad are reserved throughout preparation");
            a.ready(tokenA, false);
            b.ready(tokenA, false); // Another ship's token cannot acknowledge B.
            ctx.runAfterDelay(35, () ->
            {
                ctx.assertTrue(a.player().level() == a.fixture.surface && b.player().level() == b.fixture.space, "A completes its physical move while B still awaits its own preparation" + " acknowledgement");
                a.ready(tokenA, true);
                ctx.runAfterDelay(5, () ->
                {
                    ctx.assertTrue(!TransferLocks.physicalBusy(a.player()) && TransferLocks.physicalBusy(b.player()), "Finishing A never clears B's job");
                    b.ready(tokenB, false);
                    ctx.runAfterDelay(35, () ->
                    {
                        ctx.assertTrue(b.player().level() == b.fixture.surface && a.fixture.ship().getStringOr("dimension", "").equals("minecraft:overworld"), "B's commit preserves A's already committed ship metadata");
                        b.ready(tokenB, true);
                        ctx.runAfterDelay(5, () ->
                        {
                            ctx.assertTrue(!TransferLocks.physicalBusy(b.player()), "B releases its own arrival reservation");
                            a.close();
                            b.close();
                            c.close();
                            ctx.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 200)
    public void independentJumpsAndPhysicalTravelDoNotBlockOrAcknowledgeEachOther(GameTestHelper ctx) throws Exception
    {
        var a = client(ctx, 23);
        var b = client(ctx, 24);
        var c = client(ctx, 25);
        a.board();
        b.board();
        c.board();
        var server = ctx.getLevel().getServer();
        ctx.runAfterDelay(20, () ->
        {
            var jumpA = Jumps.jump(a.player(), WorldStore.pos(a.fixture.ship(), "controller"), "sol_luna");
            var jumpB = Jumps.jump(b.player(), WorldStore.pos(b.fixture.ship(), "controller"), "sol_ares");
            var land = c.land(c.pad());
            ctx.assertTrue(ShipDestination.selected(a.fixture.ship()).region().equals("sol_luna") && ShipDestination.selected(b.fixture.ship()).region().equals("sol_ares"), "Direct jump calls retain the actual paid destination in each controller");
            ctx.assertTrue(!jumpA.equals(jumpB) && NavigationService.pending(server, a.fixture.id) && NavigationService.pending(server, b.fixture.id), "Two ships can prepare distinct jumps alongside physical travel");
            NavigationService.ready(a.player(), new TravelPackets.Ready(jumpA, false));
            NavigationService.ready(b.player(), new TravelPackets.Ready(jumpA, false));
            c.ready(land, false);
            ctx.runAfterDelay(40, () ->
            {
                ctx.assertTrue(JourneyService.cruising(a.fixture.ship()) && a.fixture.ship().getCompoundOrEmpty("journey").getStringOr("destination", "").equals("sol_luna") && LocalSpaces.address(b.fixture.ship()).equals(LocalSpaces.HOME), "Only A's matching acknowledgement commits its jump");
                ctx.assertTrue(c.player().level() == c.fixture.surface, "An unrelated physical transfer proceeds while both jumps are pending");
                NavigationService.ready(a.player(), new TravelPackets.Ready(jumpA, true));
                c.ready(land, true);
                ctx.runAfterDelay(5, () ->
                {
                    ctx.assertTrue(!NavigationService.pending(server, a.fixture.id) && NavigationService.pending(server, b.fixture.id), "Finishing A leaves B's jump intact");
                    NavigationService.ready(b.player(), new TravelPackets.Ready(jumpB, false));
                    ctx.runAfterDelay(30, () ->
                    {
                        ctx.assertTrue(JourneyService.cruising(b.fixture.ship()) && b.fixture.ship().getCompoundOrEmpty("journey").getStringOr("destination", "").equals("sol_ares") && JourneyService.cruising(a.fixture.ship()) && a.fixture.ship().getCompoundOrEmpty("journey").getStringOr("destination", "").equals("sol_luna"), "Each jump preserves the other ship's destination");
                        NavigationService.ready(b.player(), new TravelPackets.Ready(jumpB, true));
                        ctx.runAfterDelay(5, () ->
                        {
                            a.close();
                            b.close();
                            c.close();
                            ctx.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 200)
    public void separateRecallsCanBeCancelledAndCompletedAlongsideAnotherShipsTravel(GameTestHelper ctx) throws Exception
    {
        var a = client(ctx, 26);
        var b = client(ctx, 27);
        var c = client(ctx, 28);
        var server = ctx.getLevel().getServer();
        int[] phase = {-1};
        UUID[] token = {null};
        // Register the poller before the test begins ticking: onEachTick installs
        // callbacks for the full remaining range and must not rehash the live tick queue.
        ctx.onEachTick(() ->
        {
            if (phase[0] == 0 && !ShipStorage.active(a.player()) && !ShipStorage.active(b.player()))
            {
                ctx.assertTrue(a.fixture.ship().getStringOr("dimension", "").equals("minecraft:overworld") && b.fixture.ship().getStringOr("dimension", "").equals("minecraft:overworld"), "Both recalls commit without overwriting each other's metadata");
                ctx.assertTrue(TransferLocks.physicalBusy(c.player()), "Unrelated recall completion never clears the third ship's travel job");
                c.ready(token[0], false);
                phase[0] = 1;
            }
            else if (phase[0] == 1 && c.player().level() == c.fixture.surface)
            {
                c.ready(token[0], true);
                phase[0] = 2;
            }
            else if (phase[0] == 2 && !TransferLocks.physicalBusy(c.player()))
            {
                phase[0] = 3;
                a.close();
                b.close();
                c.close();
                ctx.succeed();
            }
        });
        ctx.runAfterDelay(20, () ->
        {
            var menu = ComputerTerminal.issue(a.player(), a.fixture.computer, "Ready", "recall", "");
            String first = ShipStorage.request(a.player(), a.fixture.computer, a.fixture.selection(), menu.session());
            String second = ShipStorage.request(b.player(), b.fixture.computer, b.fixture.selection(), null);
            ctx.assertTrue(first.startsWith("Recall requested") && second.startsWith("Recall requested"), "Independent recalls are both accepted: " + first + "; " + second);
            ctx.assertTrue(ComputerTerminal.current(a.player(), menu.session()), "Recall controls remain usable so the owner can cancel while waiting");
            ShipStorage.cancel(a.player());
            ctx.assertTrue(!ShipStorage.active(a.player()) && ShipStorage.active(b.player()), "Cancelling one player's recall preserves the other player's recall");
            ctx.assertTrue(ShipStorage.request(a.player(), a.fixture.computer, a.fixture.selection(), null).startsWith("Recall requested"), "Cancelled reservations are immediately reusable");
            c.board();
            token[0] = c.land(c.pad());
            phase[0] = 0;
        });
    }
}
