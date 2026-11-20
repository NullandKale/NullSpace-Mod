package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.synth.SynthBlockEntity;
import com.nullandkale.nullspace.content.synth.Synths;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.RoomAcoustics;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.TestPads;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/** Who hears what: rooms muffle by their doors, a hull muffles on a world with air, and nothing carries outside a hull in vacuum; and the Synth block. */
public final class SoundGameTest
{
    /** The reference ship with a wall across its cabin and a closed door in it: a front room (z 1 to 3) and a back room (z 5). */
    private static CompoundTag twoRooms(ServerLevel level, BlockPos base, String pad) throws java.io.IOException
    {
        var helm = ShipTestFixture.build(level, base);
        TestPads.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), pad);
        for (var at : BlockPos.betweenClosed(base.offset(1, 3, 4), base.offset(5, 5, 4)))
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 18);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, false);
        level.setBlock(base.offset(3, 3, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 18);
        level.setBlock(base.offset(3, 4, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        return ship;
    }

    /** A survival player who has joined, so the server's sound loop counts them as a listener. */
    private static ServerPlayer listener(GameTestHelper ctx, String name)
    {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(ctx.getLevel().getServer(), ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    @GameTest(environment = "nullspace:sound", maxTicks = 600)
    public void roomsMuffleByTheirDoorsAHullMufflesOnAWorldWithAirAndNothingCarriesInVacuum(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(15000, 80, 15000));
        var ship = twoRooms(level, base, "Hearing test");
        var front = Vec3.atCenterOf(base.offset(3, 4, 2));
        var back = Vec3.atCenterOf(base.offset(3, 4, 5));
        var source = Vec3.atCenterOf(base.offset(4, 3, 2));
        var outside = Vec3.atCenterOf(base.offset(3, 4, -4));
        TestWaits.until(ctx, () -> Hearing.muffle(level, back, source) == Hearing.rooms(RoomAcoustics.DOOR), () ->
        {
            ctx.assertTrue(Hearing.muffle(level, front, source) == 0, "A sound in the listener's room is clear");
            ctx.assertTrue(Hearing.muffle(level, outside, source) == Hearing.rooms(RoomAcoustics.WALL) && Hearing.muffle(level, front, outside) == Hearing.rooms(RoomAcoustics.WALL), "On a world with air a hull muffles what passes through it");
            ctx.assertTrue(Hearing.muffle(level, outside, outside.add(4, 0, 0)) == 0, "In a planet's open air a sound is clear");
            var space = server.getLevel(TravelService.SPACE);
            ctx.assertTrue(Hearing.muffle(space, new Vec3(-123456, 100, -500000), new Vec3(-123450, 100, -500000)) == Hearing.SILENT, "Nothing carries outside a hull in vacuum");
            ShipDoors.setOpen(level, base.offset(3, 3, 4), true);
            TestWaits.until(ctx, () -> Hearing.muffle(level, back, source) == 0, () ->
            {
                WorldStore.get(server).ships().remove(ship);
                ctx.succeed();
            });
        });
    }

    @GameTest(environment = "nullspace:sound", maxTicks = 900)
    public void aSynthIsProgrammedFromATerminalPlaysItsLoopThroughTheDoorAndRedstonePlaysIt(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(15200, 80, 15000));
        var ship = twoRooms(level, base, "Synth test");
        // After the ship: building the reference ship clears the space it stands in.
        var synthAt = base.offset(4, 3, 2);
        level.setBlock(synthAt, ModBlocks.SYNTH.defaultBlockState(), 3);
        var helm = WorldStore.pos(ship, "controller");
        for (int i = 0; i < 20; i++)
            ShipSystems.tick(level, ship);
        var listener = listener(ctx, "synth-listener");
        listener.teleportTo(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 5.5, Set.of(), 0, 0, true);
        var local = synthAt.subtract(WorldStore.pos(ship, "base"));
        ctx.assertTrue(Synths.command(level, ship, helm, List.of()).startsWith("1: at " + local.getX() + " " + local.getY() + " " + local.getZ()), "The terminal lists the ship's Synth where it stands in the ship: " + Synths.command(level, ship, helm, List.of()));
        Synths.command(level, ship, helm, List.of("preset", "alarm.lowair"));
        Synths.command(level, ship, helm, List.of("from", "1000"));
        var synth = (SynthBlockEntity) level.getBlockEntity(synthAt);
        ctx.assertTrue(synth.patch().loop() && synth.patch().toneFrom() == 1000 && Synths.command(level, ship, helm, List.of("dump")).startsWith("tone square 1000"), "A preset and a setting change the block's patch: " + synth.patch().text());
        ctx.assertTrue(Synths.silence(level, synthAt).isEmpty(), "The ship powers its Synth: " + Synths.silence(level, synthAt));
        ctx.assertTrue(Synths.command(level, ship, helm, List.of("play")).equals("Playing (loop)"), "A loop starts");
        String key = "synth:" + level.dimension().identifier() + ":" + synthAt.asLong();
        TestWaits.until(ctx, () -> ShipSounds.playing(listener).contains(key), () ->
        {
            Synths.command(level, ship, helm, List.of("stop"));
            TestWaits.until(ctx, () -> !ShipSounds.playing(listener).contains(key), () ->
            {
                // A one-shot plays on a redstone pulse, and the listener in the back room hears it through the closed door.
                Synths.command(level, ship, helm, List.of("loop", "off"));
                level.setBlock(synthAt.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                var heard = ShipSounds.recent(server);
                ctx.assertTrue(heard.contains("synth-listener patch muffle " + Hearing.rooms(RoomAcoustics.DOOR)), "The pulse plays the patch, muffled by the door: " + heard);
                level.setBlock(synthAt.above(), Blocks.AIR.defaultBlockState(), 3);
                // A loop plays while the signal is on.
                Synths.command(level, ship, helm, List.of("loop", "on"));
                level.setBlock(synthAt.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                ctx.assertTrue(synth.playing(), "A loop plays while the signal is on");
                level.setBlock(synthAt.above(), Blocks.AIR.defaultBlockState(), 3);
                ctx.assertTrue(!synth.playing(), "and stops when it goes off");
                server.getPlayerList().remove(listener);
                WorldStore.get(server).ships().remove(ship);
                ctx.succeed();
            });
        });
    }
}
