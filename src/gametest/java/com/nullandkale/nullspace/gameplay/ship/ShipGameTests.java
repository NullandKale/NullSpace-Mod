package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipLab;
import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.scene.SceneSnapshots;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.Occupants;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TransferRecovery;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;

public final class ShipGameTests
{
    @GameTest
    public void stairsReachElevatedDoorAndHeadroomMatters(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(640, 64, 640));
        ShipTestFixture.build(level, base);
        for (int x = 0; x <= 6; x++)
            for (int z = 0; z <= 6; z++)
                for (int y = 2; y <= 8; y++)
                {
                    var pos = base.offset(x, y, z);
                    if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)
                        c.clearContent();
                    level.setBlock(pos, (x == 0 || x == 6 || z == 0 || z == 6 || y == 2 || y == 8 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 18);
                }
        var controller = base.offset(6, 3, 3);
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        level.setBlock(base.offset(0, 3, 1), ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        for (int x = 1; x <= 5; x++)
            for (int y = 3; y <= 7; y++)
                for (int z : new int[]{2, 4})
                    level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
        var stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.WEST);
        level.setBlock(base.offset(4, 3, 3), stair, 18);
        level.setBlock(base.offset(3, 3, 3), Blocks.STONE.defaultBlockState(), 18);
        level.setBlock(base.offset(2, 4, 3), stair, 18);
        level.setBlock(base.offset(1, 4, 3), Blocks.STONE.defaultBlockState(), 18);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        level.setBlock(base.offset(0, 5, 3), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 18);
        level.setBlock(base.offset(0, 6, 3), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 18);
        var pad = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        ctx.assertTrue(HullScan.inspect(level, controller, pad, true).pressure().breathable(base.offset(5, 4, 3)), "Elevated exit via stairs must pass");
        level.setBlock(base.offset(4, 5, 3), Blocks.STONE.defaultBlockState(), 18);
        boolean blocked = false;
        try
        {
            HullScan.inspect(level, controller, pad, true);
        }
        catch (HullScan.Failure failure)
        {
            blocked = failure.getMessage().contains("path");
        }
        ctx.assertTrue(blocked, "Insufficient jump/step headroom must fail");
        ctx.succeed();
    }

    @GameTest
    public void sessionsBindPlayerBlockDimensionAndPadIdentity(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(768, 64, 768));
        var controller = ShipTestFixture.build(level, base);
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        var stranger = ctx.makeMockServerPlayerInLevel();
        stranger.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 4.5);
        var bounds = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        String name = "Session " + java.util.UUID.randomUUID().toString().substring(0, 8);
        PadComputers.register(level, base.offset(-1, 1, 3), bounds, name);
        var registry = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("nullspace-pads.json");
        String original = java.nio.file.Files.readString(registry);
        var crowded = new com.google.gson.JsonArray();
        for (int n = 0; n < 130; n++)
        {
            var pad = new com.google.gson.JsonObject();
            pad.addProperty("id", java.util.UUID.randomUUID().toString());
            pad.addProperty("name", "Catalog " + n);
            pad.addProperty("dimension", "nullspace:missing_catalog_test");
            pad.addProperty("computer", new BlockPos(n * 64, 65, 0).asLong());
            pad.addProperty("minX", 0);
            pad.addProperty("maxX", 7);
            pad.addProperty("y", 64);
            pad.addProperty("minZ", 0);
            pad.addProperty("maxZ", 7);
            crowded.add(pad);
        }
        for (var pad : com.google.gson.JsonParser.parseString(original).getAsJsonArray())
            crowded.add(pad);
        TerminalPackets.Open open;
        try
        {
            java.nio.file.Files.writeString(registry, crowded.toString());
            PadRegistry.reload(level.getServer());
            open = ComputerTerminal.issue(player, controller, "", "ship", name);
        }
        finally
        {
            java.nio.file.Files.writeString(registry, original);
            PadRegistry.reload(level.getServer());
        }
        ctx.assertTrue(!open.session().equals(ComputerTerminal.NONE) && open.destinations().size() == 128, "A valid session must retain its bounded catalog");
        var selected = open.destinations().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
        var request = new TerminalPackets.Request(open.session(), ComputerTerminal.Action.LAND, name, selected.id());
        boolean stolen = false;
        try
        {
            ComputerTerminal.authorize(stranger, request);
        }
        catch (IllegalArgumentException expected)
        {
            stolen = true;
        }
        ctx.assertTrue(stolen, "Another player must not use a stolen session");
        PadComputers.register(level, base.offset(-1, 1, 3), bounds, name + " renamed");
        ctx.assertTrue(ComputerTerminal.authorize(player, request).argument().equals("id:" + selected.id()), "Rename must not redirect the selected pad");
        ComputerTerminal.close(stranger, open.session());
        player.teleportTo(base.getX() + 30, base.getY() + 3, base.getZ() + 3);
        boolean distant = false;
        try
        {
            ComputerTerminal.authorize(player, request);
        }
        catch (IllegalArgumentException expected)
        {
            distant = true;
        }
        ctx.assertTrue(distant, "Moving out of range must invalidate the session");
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        var replacement = ComputerTerminal.issue(player, controller, "", "ship", "");
        ctx.assertTrue(!replacement.session().equals(open.session()), "Expired session must not revive on returning");
        level.setBlock(controller, Blocks.STONE.defaultBlockState(), 18);
        boolean removed = false;
        try
        {
            ComputerTerminal.authorize(player, new TerminalPackets.Request(replacement.session(), ComputerTerminal.Action.INSPECT, "", ""));
        }
        catch (IllegalArgumentException expected)
        {
            removed = true;
        }
        ctx.assertTrue(removed, "Replacing a computer must invalidate its session");
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState(), 18);
        var changedDimension = ComputerTerminal.issue(player, controller, "", "ship", "");
        player.teleportTo(level.getServer().getLevel(TravelService.SPACE), player.getX(), player.getY(), player.getZ(), java.util.Set.of(), 0, 0, true);
        boolean dimension = false;
        try
        {
            ComputerTerminal.authorize(player, new TerminalPackets.Request(changedDimension.session(), ComputerTerminal.Action.INSPECT, "", ""));
        }
        catch (IllegalArgumentException expected)
        {
            dimension = true;
        }
        ctx.assertTrue(dimension, "Same coordinates in another dimension must not authorize a session");
        serverRemove(player);
        serverRemove(stranger);
        ctx.succeed();
    }

    private static void serverRemove(net.minecraft.server.level.ServerPlayer player)
    {
        player.level().getServer().getPlayerList().remove(player);
    }

    private static void keepFixtureLoaded(net.minecraft.server.level.ServerLevel level, BlockPos base)
    {
        for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
            for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
                level.setChunkForced(x, z, true);
    }

    @GameTest(maxTicks = 600)
    public void readinessTimeoutsPreserveSourceAndQuarantineArrival(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(1024, 64, 1024));
        var controller = ShipTestFixture.build(level, base);
        keepFixtureLoaded(level, base);
        var space = server.getLevel(TravelService.SPACE);
        int free = 0;
        for (int slot = 0; free < 3; slot++)
        {
            final int x = slot * TravelService.SPACE_SPACING;
            if (TransferLocks.spaceSlotFree(server, slot))
            {
                space.setChunkForced(x >> 4, 0, true);
                free++;
            }
        }
        var registered = PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Readiness " + java.util.UUID.randomUUID().toString().substring(0, 8));
        ctx.assertTrue(registered.startsWith("Registered "), "Readiness fixture must register: " + registered);
        // Allow the fixture chunks and their initial entity data to finish loading.
        ctx.runAfterDelay(40, () ->
        {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "readiness-test"), false);
            var player = new net.minecraft.server.level.ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
            // Ships take off only with systems set up.
            SystemsUpgrade.upgrade(level, ShipTopology.resolve(level, controller));
            TravelService.request(player, controller, "", ShipService.preflight(level, controller));
            TravelService.ready(player, new TravelPackets.Ready(java.util.UUID.randomUUID(), false));
            ctx.runAfterDelay(205, () ->
            {
                ctx.assertTrue(!TransferLocks.busy(player) && level.getBlockState(controller).is(ModBlocks.COMPUTER), "Missing/wrong preparation ACK must time out without moving the source");
                TravelService.request(player, controller, "", ShipService.preflight(level, controller));
                // Server listeners batch writes until the end of the tick. Flush the new token,
                // otherwise this embedded observer sees only the previous expired request.
                connection.flushChannel();
                channel.runPendingTasks();
                TravelPackets.Progress latest = null;
                Object packet;
                while ((packet = channel.readOutbound()) != null)
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom && custom.payload() instanceof TravelPackets.Progress progress && !progress.done())
                        latest = progress;
                ctx.assertTrue(latest != null, "Preparation must send an acknowledgment token");
                TravelService.ready(player, new TravelPackets.Ready(latest.id(), false));
                ctx.runAfterDelay(40, () ->
                {
                    ctx.assertTrue(player.level().dimension().equals(TravelService.SPACE) && TransferLocks.busy(player), "Arrival must remain quarantined while its ACK is missing (dimension=" + player.level().dimension() + ", busy=" + TransferLocks.busy(player) + ")");
                    TravelService.ready(player, new TravelPackets.Ready(java.util.UUID.randomUUID(), true));
                    ctx.runAfterDelay(205, () ->
                    {
                        ctx.assertTrue(!TransferLocks.busy(player) && !connection.isConnected(), "Missing/wrong arrival ACK must disconnect the client and release" + " travel");
                        ctx.assertTrue(level.getBlockState(controller).isAir(), "Arrival timeout must keep the committed destination" + " authoritative");
                        serverRemove(player);
                        ctx.succeed();
                    });
                });
            });
        });
    }

    @GameTest
    public void sensorsValidateCargoAndTraceLeaks(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(1152, 64, 1152));
        var controller = ShipTestFixture.build(level, base);
        keepFixtureLoaded(level, base);
        var pad = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        var scan = HullScan.inspect(level, controller, pad, true);
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 3.5);
        level.addFreshEntity(stand);
        ctx.runAfterDelay(10, () ->
        {
            ctx.assertTrue(Occupants.inspect(level, scan).cargo().contains(stand), "Decorations such as armor stands are accepted as cargo");
            stand.discard();
            var hole = base.offset(3, 4, 6);
            level.setBlock(hole, Blocks.AIR.defaultBlockState(), 18);
            try
            {
                HullScan.inspect(level, controller, pad, true);
                ctx.fail("Hull leak must fail inspection");
            }
            catch (HullScan.Failure expected)
            {
                ctx.assertTrue(expected.trace.contains(hole) && !expected.trace.isEmpty(), "Leak diagnostics must trace from the controller through the real breach");
            }
            ctx.succeed();
        });
    }

    @GameTest
    public void padStatesSeparateUnknownInvalidAndOccupied(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(137000, 64, 137000));
        ShipTestFixture.build(level, base);
        // The scanner probes one block beyond the controller, including outside the ring.
        // Load that fixture halo explicitly; passive pad listings must never generate it.
        for (int x = -2; x <= 9; x++)
            for (int z = -2; z <= 9; z++)
            {
                var at = base.offset(x, 0, z);
                level.getChunk(at);
                level.getChunkSource().addTicketWithRadius(SceneSnapshots.CAMERA_TICKET, new net.minecraft.world.level.ChunkPos(at.getX() >> 4, at.getZ() >> 4), 0);
            }
        // Fresh distant chunks become visible to passive queries between server ticks.
        ctx.runAfterDelay(5, () ->
        {
            var pad = new PadRegistry.Pad(java.util.UUID.randomUUID().toString(), "State pad", level.dimension().identifier().toString(), base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7));
            ctx.assertTrue(PadRegistry.inspectState(level.getServer(), pad).state() == PadRegistry.State.OCCUPIED, "Ship must show occupied: " + PadRegistry.inspectState(level.getServer(), pad));
            var scan = HullScan.inspect(level, base.offset(6, 3, 3), pad.bounds(), true);
            for (var p : scan.blocks())
            {
                if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c)
                    c.clearContent();
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
            }
            ctx.assertTrue(PadRegistry.inspectState(level.getServer(), pad).state() == PadRegistry.State.READY, "Empty clear pad must show ready");
            level.setBlock(pad.computer(), Blocks.AIR.defaultBlockState(), 18);
            ctx.assertTrue(PadRegistry.inspectState(level.getServer(), pad).state() == PadRegistry.State.UNAVAILABLE, "Missing computer must show unavailable");
            var far = base.offset(100000, 0, 100000);
            var unknown = new PadRegistry.Pad("unknown", "Unloaded", pad.dimension(), far, new PadScanner.Pad(far.getX(), far.getX() + 7, far.getY(), far.getZ(), far.getZ() + 7));
            ctx.assertTrue(PadRegistry.inspectState(level.getServer(), unknown).state() == PadRegistry.State.UNKNOWN && !level.hasChunkAt(far), "Listing must not generate remote terrain");
            ctx.succeed();
        });
    }

    @GameTest
    public void padRegistrationRenameAndConflicts(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(512, 64, 512));
        ShipTestFixture.build(level, base);
        var bounds = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        var computer = base.offset(-1, 1, 3);
        String unique = "Pad " + java.util.UUID.randomUUID().toString().substring(0, 8);
        ctx.assertTrue(PadComputers.register(level, computer, bounds, "").contains("1-32"), "Blank names must fail explicitly");
        ctx.assertTrue(PadComputers.register(level, computer, bounds, unique).startsWith("Registered "), "Valid pad must register");
        String id = PadRegistry.read(level.getServer()).stream().filter(p -> p.computer().equals(computer)).findFirst().orElseThrow().id();
        ctx.assertTrue(PadComputers.register(level, computer, bounds, unique + " renamed").startsWith("Registered "), "Pad must rename");
        ctx.assertTrue(PadRegistry.read(level.getServer()).stream().filter(p -> p.computer().equals(computer)).findFirst().orElseThrow().id().equals(id), "Rename must preserve identity");
        var second = base.offset(3, 1, -1);
        level.setBlock(second, PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.SOUTH), 18);
        ctx.assertTrue(PadComputers.register(level, second, bounds, unique + " renamed").contains("already"), "Duplicate names must explain rejection");
        ctx.assertTrue(PadComputers.register(level, second, bounds, unique + " other").contains("overlaps"), "Overlapping pads must explain rejection");
        ctx.succeed();
    }

    @GameTest
    public void generatedLabIsReadyAndPreservesEdits(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var base = ShipLab.ensure(server);
        var controller = base.offset(6, 3, 3);
        ctx.assertTrue(ShipService.inspect(server.overworld(), controller).scan().blocks().size() > 0, "Generated lab must pass the real ship inspector");
        var window = base.offset(3, 4, 6);
        server.overworld().setBlock(window, Blocks.IRON_BLOCK.defaultBlockState(), 18);
        ctx.assertTrue(ShipLab.ensure(server).equals(base), "Repeated generation must reuse the lab");
        ctx.assertTrue(server.overworld().getBlockState(window).is(Blocks.IRON_BLOCK), "Repeated generation must preserve player edits");
        ctx.succeed();
    }

    @GameTest
    public void maximumShipVolumeIsBounded(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(384, 64, 384));
        for (int x = -1; x <= 32; x++)
            for (int z = -1; z <= 32; z++)
                level.setBlock(base.offset(x, 0, z), (x == -1 || x == 32 || z == -1 || z == 32 ? Blocks.COBBLESTONE : Blocks.GRASS_BLOCK).defaultBlockState(), 18);
        for (int x = 0; x < 32; x++)
            for (int z = 0; z < 32; z++)
                for (int y = 2; y <= 32; y++)
                    if (x == 0 || x == 31 || z == 0 || z == 31 || y == 2 || y == 32)
                        level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
        var controller = base.offset(31, 3, 16);
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        level.setBlock(base.offset(0, 3, 16), ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(base.offset(16, 3, 0), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 18);
        level.setBlock(base.offset(16, 4, 0), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 18);
        long start = System.nanoTime();
        var scan = HullScan.inspect(level, controller, new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31), true);
        NullSpace.LOGGER.info("Maximum ship scan: {} ms, {} hull blocks", (System.nanoTime() - start) / 1_000_000, scan.blocks().size());
        ctx.assertTrue(scan.bounds().max().getY() == base.getY() + 32 && scan.bounds().max().getX() - scan.bounds().min().getX() + 1 == 32, "Maximum footprint and height must remain valid");
        ctx.succeed();
    }

    @GameTest(maxTicks = 2000)
    public void survivalSuitVacuumAndTether(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(1000000, 96, 0);
        var controller = ShipTestFixture.build(space, base);
        var window = base.offset(3, 4, 6);
        space.setBlock(window, Blocks.AIR.defaultBlockState(), 18);
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "vacuum-test"), false);
        var player = new net.minecraft.server.level.ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(space, base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, java.util.Set.of(), 0, 0, true);
        player.hasChangedDimension();
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        player.setAirSupply(0);
        // The oxygen meter, not vanilla air, decides suffocation: start this player out of oxygen.
        WorldStore.get(server).section("oxygen").putInt(player.getUUID().toString(), 0);
        player.setHealth(20);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WATER_BREATHING, 1000));
        var store = WorldStore.get(server);
        var ship = new net.minecraft.nbt.CompoundTag();
        ship.putString("id", java.util.UUID.randomUUID().toString());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", controller.asLong());
        var members = new net.minecraft.nbt.ListTag();
        var member = new net.minecraft.nbt.CompoundTag();
        member.putString("id", player.getUUID().toString());
        members.add(member);
        ship.put("crew", members);
        ship.putInt("width", 7);
        ship.putInt("depth", 7);
        ship.putInt("height", 6);
        store.ships().add(ship);
        // Air comes only from working systems: the reference ship's generator and Air Supply.
        SystemsUpgrade.upgrade(space, ship);
        ctx.runAfterDelay(25, () ->
        {
            ctx.assertTrue(player.getHealth() < 20, "Vacuum must damage a fed player despite Water Breathing");
            space.setBlock(window, Blocks.GLASS.defaultBlockState(), 18);
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(ModBlocks.HELMET));
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(ModBlocks.CHEST));
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(ModBlocks.LEGS));
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(ModBlocks.BOOTS));
            int[] waited = {0};
            Runnable[] refilled = {null};
            refilled[0] = () ->
            {
                // The Air Supply refills the sealed cabin over a few seconds.
                // Breathable cabin air (not just the suit tether) also turns suit flight off.
                boolean cabinAir = player.getAirSupply() > 0 && !player.getAbilities().mayfly;
                if (!cabinAir && (waited[0] += 10) < 400)
                {
                    // A new Runnable each time: the helper keys scheduled work by the Runnable.
                    ctx.runAfterDelay(10, () -> refilled[0].run());
                    return;
                }
                ctx.assertTrue(player.getAirSupply() > 0, "Closing leak must restore breathable air within 20 seconds");
                NullSpace.LOGGER.info("Vacuum test: cabin air restored after {} ticks", waited[0]);
                ctx.assertTrue(LifeSupport.suited(player) && !player.getAbilities().mayfly && !player.getAbilities().flying, "Sealed cabin must retain normal gravity");
                player.teleportTo(base.getX() + 10.5, base.getY() + 3, base.getZ() + 3.5);
                int suppliedAir = player.getAirSupply();
                ctx.runAfterDelay(10, () ->
                {
                    // Playtest 2 spec: a suited, tethered player outside the cabin is held at the
                    // 20 floor rather than refilled.
                    ctx.assertTrue(player.getAirSupply() >= player.getMaxAirSupply() * LifeSupport.SUIT_TETHER_FLOOR / 100, "The tether keeps an EVA player at the oxygen floor (was " + suppliedAir + ", now " + player.getAirSupply() + ")");
                    ctx.assertTrue(player.getAbilities().mayfly && player.getAbilities().flying, "Leaving the cabin must automatically activate suit flight");
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
                    int bareAir = player.getAirSupply();
                    ctx.runAfterDelay(5, () ->
                    {
                        ctx.assertTrue(player.getAirSupply() < bareAir && !player.getAbilities().mayfly && !player.getAbilities().flying, "Missing suit piece must remove oxygen and flight");
                        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(ModBlocks.HELMET));
                        space.setBlock(window, Blocks.AIR.defaultBlockState(), 18);
                        int ventedAir = player.getAirSupply();
                        int[] drained = {0};
                        Runnable[] vented = {null};
                        vented[0] = () ->
                        {
                            // The room drains through the open window, then the tether
                            // has no breathable outlet to draw from.
                            if (player.getAirSupply() >= ventedAir && (drained[0] += 10) < 400)
                            {
                                ctx.runAfterDelay(10, () -> vented[0].run());
                                return;
                            }
                            ctx.assertTrue(player.getAirSupply() < ventedAir, "Vented Air Supply room must stop tether air");
                            ctx.assertTrue(player.getAbilities().flying, "Restoring the complete suit outside must reactivate flight");
                            space.setBlock(window, Blocks.GLASS.defaultBlockState(), 18);
                            player.teleportTo(base.getX() + 200, base.getY() + 3, base.getZ() + 3.5);
                            ctx.runAfterDelay(2, () ->
                            {
                                ctx.assertTrue(player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(controller)) <= 72 * 72, "Server must enforce the tether boundary");
                                player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
                                int[] refill = {0};
                                Runnable[] reentered = {null};
                                reentered[0] = () ->
                                {
                                    // The vented cabin refills before it has gravity.
                                    if (player.getAbilities().mayfly && (refill[0] += 10) < 500)
                                    {
                                        ctx.runAfterDelay(10, () -> reentered[0].run());
                                        return;
                                    }
                                    ctx.assertTrue(!player.getAbilities().flying && !player.getAbilities().mayfly, "Reentering a sealed cabin must restore normal" + " gravity");
                                    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
                                    player.teleportTo(base.getX() + 10.5, base.getY() + 3, base.getZ() + 3.5);
                                    ctx.runAfterDelay(2, () ->
                                    {
                                        ctx.assertTrue(player.getAbilities().flying, "Creative lab players must also enter" + " flight automatically");
                                        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
                                        ctx.runAfterDelay(2, () ->
                                        {
                                            ctx.assertTrue(!player.getAbilities().flying && player.getAbilities().mayfly, "Creative cabin gravity must not" + " revoke creative flight" + " permission");
                                            server.getPlayerList().remove(player);
                                            ctx.succeed();
                                        });
                                    });
                                };
                                ctx.runAfterDelay(2, () -> reentered[0].run());
                            });
                        };
                        ctx.runAfterDelay(10, () -> vented[0].run());
                    });
                });
            };
            ctx.runAfterDelay(10, refilled[0]);
        });
    }

    /**
     * Picks the first free orbit slot, holds its chunk and launches ten ticks later, so it runs in its own environment, alone:
     * another test launching at the same time would take the slot, and the next one has not loaded its entities.
     */
    @GameTest(environment = "nullspace:ship_round_trip")
    public void transferRoundTripAndInterruptedTransfers(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(128, 64, 128));
        var controller = ShipTestFixture.build(level, base);
        var bounds = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        // This recovery fixture keeps its north entrance aligned; LandingRotationGameTest covers turns.
        level.setBlock(base.offset(3, 1, -1), com.nullandkale.nullspace.gameplay.pads.PadComputers.BLOCK.defaultBlockState(), 18);
        var pad = new PadRegistry.Pad(java.util.UUID.randomUUID().toString(), "Roundtrip", level.dimension().identifier().toString(), base.offset(3, 1, -1), bounds);
        var file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("nullspace-pads.json");
        com.google.gson.JsonArray records;
        try
        {
            records = java.nio.file.Files.exists(file) ? com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonArray() : new com.google.gson.JsonArray();
        }
        catch (java.io.IOException error)
        {
            throw new java.io.UncheckedIOException(error);
        }
        records.asList().removeIf(t -> t.getAsJsonObject().get("name").getAsString().equals("Roundtrip"));
        var entry = new com.google.gson.JsonObject();
        entry.addProperty("id", pad.id());
        entry.addProperty("name", pad.name());
        entry.addProperty("dimension", pad.dimension());
        entry.addProperty("computer", pad.computer().asLong());
        entry.addProperty("minX", bounds.minX());
        entry.addProperty("maxX", bounds.maxX());
        entry.addProperty("minZ", bounds.minZ());
        entry.addProperty("maxZ", bounds.maxZ());
        entry.addProperty("y", bounds.y());
        records.add(entry);
        try
        {
            java.nio.file.Files.writeString(file, records.toString());
            PadRegistry.reload(level.getServer());
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
        var furnacePositions = java.util.List.of(base.offset(1, 3, 6), base.offset(2, 3, 6), base.offset(4, 3, 6));
        var furnaceBlocks = java.util.List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
        for (int i = 0; i < 3; i++)
        {
            var at = furnacePositions.get(i);
            level.setBlock(at, furnaceBlocks.get(i).defaultBlockState(), 18);
            var furnace = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) level.getBlockEntity(at);
            furnace.setItem(0, new net.minecraft.world.item.ItemStack(i == 2 ? net.minecraft.world.item.Items.BEEF : net.minecraft.world.item.Items.IRON_ORE, 32));
            furnace.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 4));
        }
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        var passenger = ctx.makeMockServerPlayerInLevel();
        passenger.teleportTo(base.getX() + 3.5, base.getY() + 3, base.getZ() + 3.5);
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 11));
        var creeper = net.minecraft.world.entity.EntityTypes.CREEPER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        creeper.setPos(base.getX() + 1.5, base.getY() + 3, base.getZ() + 3.5);
        creeper.setHealth(13);
        level.addFreshEntity(creeper);
        var cow = net.minecraft.world.entity.EntityTypes.COW.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        cow.setPos(base.getX() + 4.5, base.getY() + 3, base.getZ() + 4.5);
        level.addFreshEntity(cow);
        var item = new net.minecraft.world.entity.item.ItemEntity(level, base.getX() + 2.5, base.getY() + 3, base.getZ() + 4.5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 3));
        level.addFreshEntity(item);
        var ids = java.util.List.of(creeper.getUUID(), cow.getUUID(), item.getUUID());
        for (int x = (base.getX() >> 4); x <= ((base.getX() + 7) >> 4); x++)
            for (int z = (base.getZ() >> 4); z <= ((base.getZ() + 7) >> 4); z++)
                level.setChunkForced(x, z, true);
        int slot = 0;
        while (true)
        {
            final int candidate = slot * TravelService.SPACE_SPACING;
            if (TransferLocks.spaceSlotFree(server, slot))
                break;
            slot++;
        }
        server.getLevel(TravelService.SPACE).setChunkForced((slot * TravelService.SPACE_SPACING) >> 4, 0, true);
        ctx.runAfterDelay(10, () ->
        {
            for (var entity : java.util.List.of(creeper, cow, item))
                ctx.assertTrue(level.getEntity(entity.getUUID()) != null, "Fixture entities must be loaded before transfer");
            creeper.setNoAi(true);
            creeper.ignite();
            for (int tick = 0; tick < 7; tick++)
                creeper.tick();
            float fuse = creeper.getSwelling(1);
            var furnaceSnapshots = furnacePositions.stream().map(at -> level.getBlockEntity(at).saveWithoutMetadata(level.registryAccess())).toList();
            var selectedDestination = ShipDestination.catalog(player, ShipTopology.resolve(level, controller)).stream().filter(d -> d.name().equals("Roundtrip")).findFirst().orElseThrow();
            ShipDestination.select(player, ShipTopology.resolve(level, controller), selectedDestination);
            for (String stage : java.util.List.of("PREPARED", "DESTINATION_STAGED", "SOURCE_CLEARED", "PASSENGERS_MOVED"))
            {
                TravelService.failureInjector = point ->
                {
                    if (point.equals(stage))
                        throw new TravelService.SimulatedCrash();
                };
                boolean interrupted = false;
                String outcome = "";
                try
                {
                    var result = TravelService.transfer(player, controller, "", ShipService.inspect(level, controller));
                    outcome = result.outcome() + " " + result.message();
                }
                catch (TravelService.SimulatedCrash expected)
                {
                    interrupted = true;
                }
                finally
                {
                    TravelService.failureInjector = point ->
                    {};
                }
                ctx.assertTrue(interrupted, "Failure hook must interrupt " + stage + ", but the transfer returned: " + outcome);
                TransferRecovery.recover(server);
                TransferRecovery.recover(server);
                ctx.assertTrue(player.level() == level && passenger.level() == level, "Recovery must restore both passengers");
                ctx.assertTrue(level.getBlockState(controller).is(ModBlocks.COMPUTER), "Recovery must restore source at " + stage);
                assertChest(ctx, level, base.offset(2, 3, 2), 7);
                for (int i = 0; i < 3; i++)
                    ctx.assertTrue(furnaceSnapshots.get(i).equals(level.getBlockEntity(furnacePositions.get(i)).saveWithoutMetadata(level.registryAccess())), "Furnace inventory, fuel and cooking state must roll back exactly once at " + stage);
                for (var id : ids)
                {
                    ctx.assertTrue(level.getEntity(id) != null, "Recovery must preserve entity UUID at " + stage);
                    ctx.assertTrue(server.getLevel(TravelService.SPACE).getEntity(id) == null, "Recovery must remove duplicate entity at " + stage);
                }
            }
            TravelService.transfer(player, controller, "", ShipService.inspect(level, controller));
            var space = server.getLevel(TravelService.SPACE);
            var ship = WorldStore.get(server).at(space, player.blockPosition());
            var spaceBase = WorldStore.pos(ship, "base");
            ctx.assertTrue(ShipDestination.selected(ship).id().equals(selectedDestination.id()), "Launch and recovery preserve the selected final destination");
            var delta = spaceBase.subtract(base.above());
            var spaceController = controller.offset(delta);
            ctx.assertTrue(player.level() == space, "Passenger must arrive in space");
            ctx.assertTrue(passenger.level() == space, "Second passenger must arrive in space");
            ctx.assertTrue(level.getBlockState(controller).isAir(), "Source ship must be removed");
            ctx.assertTrue(level.getBlockState(base).is(Blocks.GRASS_BLOCK), "Pad must stay on planet");
            // Natural grass decay must not invalidate the registered home pad or prevent the return
            // trip.
            for (int x = 0; x < 8; x++)
                for (int z = 0; z < 8; z++)
                    level.setBlock(base.offset(x, 0, z), Blocks.DIRT.defaultBlockState(), 18);
            assertChest(ctx, space, base.offset(2, 3, 2).offset(delta), 7);
            for (int i = 0; i < 3; i++)
                ctx.assertTrue(furnaceSnapshots.get(i).equals(space.getBlockEntity(furnacePositions.get(i).offset(delta)).saveWithoutMetadata(space.registryAccess())), "All furnace state must survive launch");
            var movedEntities = HullEntities.inBounds(space, WorldStore.bounds(ship));
            for (var id : ids)
                ctx.assertTrue(movedEntities.stream().filter(e -> e.getUUID().equals(id)).count() == 1 && level.getEntity(id) == null, "Cargo entity must have one authoritative UUID");
            ctx.assertTrue(((net.minecraft.world.entity.LivingEntity) movedEntities.stream().filter(e -> e.getUUID().equals(ids.get(0))).findFirst().orElseThrow()).getHealth() == 13, "Creeper health must survive transfer");
            ctx.assertTrue(((net.minecraft.world.entity.monster.Creeper) movedEntities.stream().filter(e -> e.getUUID().equals(ids.get(0))).findFirst().orElseThrow()).getSwelling(1) == fuse, "Elapsed creeper fuse must survive recovery and transfer");
            ((net.minecraft.world.level.block.entity.ChestBlockEntity) space.getBlockEntity(base.offset(2, 3, 2).offset(delta))).setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 9));
            space.setBlock(base.offset(3, 4, 6).offset(delta), Blocks.IRON_BLOCK.defaultBlockState(), 18);
            level.setBlock(base.offset(1, 40, 1), Blocks.GLASS.defaultBlockState(), 18);
            var blocked = TravelService.transfer(player, spaceController, "Roundtrip", ShipService.inspect(space, spaceController));
            ctx.assertTrue(blocked.outcome() == TravelService.Outcome.REJECTED && blocked.message().contains("obstructed") && blocked.message().contains("Location:") && player.level() == space, "Blocked landing returns a diagnostic result and leaves the ship in space");
            level.setBlock(base.offset(1, 40, 1), Blocks.AIR.defaultBlockState(), 18);
            var destinationPad = PadRegistry.read(server).stream().filter(candidatePad -> candidatePad.name().equals("Roundtrip")).findFirst().orElseThrow();
            var padComputer = destinationPad.computer();
            var computerState = level.getBlockState(padComputer);
            var originalInjector = TravelService.failureInjector;
            try
            {
                level.setBlock(padComputer, Blocks.AIR.defaultBlockState(), 18);
                TravelService.failureInjector = stage ->
                {
                    throw new AssertionError("A rejected landing must not prepare a transfer journal");
                };
                var missing = TravelService.transfer(player, spaceController, "Roundtrip", ShipService.inspect(space, spaceController));
                ctx.assertTrue(missing.outcome() == TravelService.Outcome.REJECTED && missing.message().equals("Pad computer is missing: Roundtrip") && player.level() == space && space.getBlockState(spaceController).is(ModBlocks.COMPUTER), "Missing pad computer is an ordinary rejection without journal work or ship" + " movement");
                var unknown = TravelService.transfer(player, spaceController, "No such pad", ShipService.inspect(space, spaceController));
                ctx.assertTrue(unknown.outcome() == TravelService.Outcome.REJECTED && unknown.message().contains("Unknown landing pad"), "Unknown destinations also return a rejection without exceptions");
            }
            finally
            {
                TravelService.failureInjector = originalInjector;
                level.setBlock(padComputer, computerState, 18);
            }
            server.getPlayerList().remove(passenger);
            var landed = TravelService.transfer(player, spaceController, "Roundtrip", ShipService.inspect(space, spaceController));
            ctx.assertTrue(landed.outcome() == TravelService.Outcome.COMPLETED, "Repairing the computer allows the next landing attempt to complete");
            ctx.assertTrue(player.level() == level, "Passenger must return to planet");
            ctx.assertTrue(ShipDestination.selected(WorldStore.get(server).at(level, controller)).id().equals(selectedDestination.id()), "Landing preserves the destination alongside the ship identity");
            ctx.assertTrue(level.getBlockState(base).is(Blocks.DIRT), "Landing must preserve the dirt pad");
            ctx.assertTrue(level.getBlockState(base.offset(3, 4, 6)).is(Blocks.IRON_BLOCK), "Return must preserve edits");
            assertChest(ctx, level, base.offset(2, 3, 2), 9);
            for (int i = 0; i < 3; i++)
                ctx.assertTrue(furnaceSnapshots.get(i).equals(level.getBlockEntity(furnacePositions.get(i)).saveWithoutMetadata(level.registryAccess())), "All furnace state must survive landing");
            for (var id : ids)
                ctx.assertTrue(level.getEntity(id) != null && space.getEntity(id) == null, "Return must preserve unique entities");
            ctx.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) == 11, "Player inventory must remain unchanged");
            ctx.assertTrue(WorldStore.get(server).list("targets").stream().anyMatch(t -> ((net.minecraft.nbt.CompoundTag) t).getStringOr("id", "").equals(passenger.getUUID().toString()) && ((net.minecraft.nbt.CompoundTag) t).getStringOr("dimension", "").equals(level.dimension().identifier().toString())), "Offline passenger must have a pending landing arrival");
            TravelService.failureInjector = point ->
            {
                if (point.equals("COMMITTED"))
                    throw new TravelService.SimulatedCrash();
            };
            try
            {
                TravelService.transfer(player, controller, "", ShipService.inspect(level, controller));
            }
            catch (TravelService.SimulatedCrash expected)
            {
            }
            finally
            {
                TravelService.failureInjector = point ->
                {};
            }
            TransferRecovery.recover(server);
            ctx.assertTrue(player.level() == space && level.getBlockState(controller).isAir(), "Committed transfer must not roll back");
            ctx.succeed();
        });
    }

    private static void assertChest(GameTestHelper ctx, net.minecraft.server.level.ServerLevel level, BlockPos pos, int count)
    {
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
        ctx.assertTrue(chest != null && chest.getItem(0).is(net.minecraft.world.item.Items.DIAMOND) && chest.getItem(0).getCount() == count, "Container contents must survive exactly once");
    }

    @GameTest
    public void validatesHullDoorAndBreach(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(64, 64, 64));
        var controller = ShipTestFixture.build(level, base);
        var pad = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        var scan = HullScan.inspect(level, controller, pad, true);
        ctx.assertTrue(scan.pressure().breathable(base.offset(4, 4, 3)), "Enclosed cabin must be breathable");
        var low = base.offset(3, 3, 0);
        var high = low.above();
        level.setBlock(low, level.getBlockState(low).setValue(DoorBlock.OPEN, true), 18);
        level.setBlock(high, level.getBlockState(high).setValue(DoorBlock.OPEN, true), 18);
        ctx.assertTrue(!HullScan.pressure(level, scan.bounds(), null).breathable(base.offset(4, 4, 3)), "Open door must depressurize cabin");
        level.setBlock(low, level.getBlockState(low).setValue(DoorBlock.OPEN, false), 18);
        level.setBlock(high, level.getBlockState(high).setValue(DoorBlock.OPEN, false), 18);
        ctx.assertTrue(HullScan.pressure(level, scan.bounds(), null).breathable(base.offset(4, 4, 3)), "Closing door must restore binary pressure");
        level.setBlock(base.offset(3, 4, 6), Blocks.GLASS_PANE.defaultBlockState(), 18);
        ctx.assertTrue(!HullScan.pressure(level, scan.bounds(), null).breathable(base.offset(4, 4, 3)), "Pane must not seal the hull");
        level.setBlock(base.offset(3, 4, 6), Blocks.GLASS.defaultBlockState(), 18);
        level.setBlock(base.offset(2, 40, 2), Blocks.GLASS.defaultBlockState(), 18);
        boolean rejected = false;
        try
        {
            HullScan.inspect(level, controller, pad, true);
        }
        catch (IllegalArgumentException expected)
        {
            rejected = expected.getMessage().contains("obstruction");
        }
        ctx.assertTrue(rejected, "Transparent obstruction above height limit must fail");
        level.setBlock(base.offset(2, 40, 2), Blocks.AIR.defaultBlockState(), 18);
        ctx.succeed();
    }

    @GameTest
    public void airlockSeparatesCabinFromVacuum(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(256, 64, 256));
        var controller = ShipTestFixture.build(level, base);
        for (int x = 1; x <= 5; x++)
            for (int y = 3; y <= 5; y++)
                level.setBlock(base.offset(x, y, 2), Blocks.STONE.defaultBlockState(), 18);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, false);
        level.setBlock(base.offset(3, 3, 2), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 18);
        level.setBlock(base.offset(3, 4, 2), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 18);
        var bounds = new HullScan.Bounds(base.above(), base.offset(7, 32, 7));
        for (int y = 3; y <= 4; y++)
        {
            var p = base.offset(3, y, 0);
            level.setBlock(p, level.getBlockState(p).setValue(DoorBlock.OPEN, true), 18);
        }
        var rooms = new HullScan.LivePressure(level, bounds);
        ctx.assertTrue(rooms.breathable(base.offset(4, 4, 3)), "Closed inner door must protect main cabin");
        ctx.assertTrue(!rooms.breathable(base.offset(3, 4, 1)), "Airlock with open exterior door must vent");
        for (int y = 3; y <= 4; y++)
        {
            var p = base.offset(3, y, 2);
            level.setBlock(p, level.getBlockState(p).setValue(DoorBlock.OPEN, true), 18);
        }
        ctx.assertTrue(!new HullScan.LivePressure(level, bounds).breathable(base.offset(4, 4, 3)), "Opening both doors must vent main cabin");
        ctx.succeed();
    }

    @GameTest
    public void spaceDimensionIsAvailable(GameTestHelper ctx)
    {
        ctx.assertTrue(ctx.getLevel().getServer().getLevel(TravelService.SPACE) != null, "Space dimension must load");
        ctx.succeed();
    }

    @GameTest(maxTicks = 60)
    public void underwaterAirCarriesIntoVacuumWithoutRefill(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var location = ctx.absolutePos(new BlockPos(2048, 0, 2048));
        var base = new BlockPos(location.getX(), 96, location.getZ());
        var controller = ShipTestFixture.build(space, base);
        keepFixtureLoaded(space, base);
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "water-air-test"), false);
        var player = new net.minecraft.server.level.ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(space, base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, java.util.Set.of(), 0, 0, true);
        player.hasChangedDimension();
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        player.setNoGravity(true);
        player.setAirSupply(80);
        // Embedded connections are not in the real network listener's tick list.
        // Drive vanilla's player tick too, so fluid detection and drowning really run.
        ctx.onEachTick(() ->
        {
            if (!player.isRemoved())
            {
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.doTick();
            }
        });
        var ship = new net.minecraft.nbt.CompoundTag();
        ship.putString("id", java.util.UUID.randomUUID().toString());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", controller.asLong());
        WorldStore.get(server).ships().add(ship);
        ctx.runAfterDelay(2, () ->
        {
            int dryAir = player.getAirSupply();
            for (int x = 3; x <= 5; x++)
                for (int y = 3; y <= 5; y++)
                    for (int z = 2; z <= 4; z++)
                        space.setBlock(base.offset(x, y, z), Blocks.WATER.defaultBlockState(), 18);
            ctx.runAfterDelay(10, () ->
            {
                int wetAir = player.getAirSupply();
                ctx.assertTrue(player.isUnderWater() && wetAir < dryAir, "Sealed cabin must not suppress vanilla underwater air loss");
                // Remove flowed water too: clearing only the source cube allows an old
                // neighboring cell to flow back over the player during the assertion.
                for (int x = 0; x <= 7; x++)
                    for (int y = 1; y <= 10; y++)
                        for (int z = 0; z <= 7; z++)
                        {
                            var at = base.offset(x, y, z);
                            var state = space.getBlockState(at);
                            if (state.is(Blocks.WATER))
                                space.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
                            else if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
                                space.setBlock(at, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false), 18);
                        }
                space.setBlock(base.offset(3, 4, 6), Blocks.AIR.defaultBlockState(), 18);
                // Sample after enough drain ticks to distinguish the old reserve from
                // vanilla's temporary +4 recovery before our end-of-tick vacuum update.
                ctx.runAfterDelay(10, () ->
                {
                    ctx.assertTrue(!player.isUnderWater() && player.getAirSupply() < wetAir, "Entering vacuum from water must not restore a stale air reserve (wet=" + wetAir + ", current=" + player.getAirSupply() + ", underwater=" + player.isUnderWater() + ", dimension=" + player.level().dimension() + ", position=" + player.position() + ")");
                    serverRemove(player);
                    ctx.succeed();
                });
            });
        });
    }
}
