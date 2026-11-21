package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.synth.SynthBlockEntity;
import com.nullandkale.nullspace.content.synth.Synths;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.EngineSounds;
import com.nullandkale.nullspace.gameplay.systems.RoomAcoustics;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.sound.SynthVoice;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.TestPads;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Who hears what: rooms muffle by their doors, a hull muffles on a world with air, and nothing carries outside a hull in vacuum; Minecraft's own sounds follow
 * the same rules; the engines hum while they thrust and a blow on the hull reaches its crew; a hull in space hums for its crew; and the Synth block.
 */
public final class SoundGameTest
{
    /** The reference ship with a wall across its cabin and a closed door in it: a front room (z 1 to 3) and a back room (z 5). */
    private static CompoundTag twoRooms(GameTestHelper ctx, BlockPos base, String pad) throws java.io.IOException
    {
        var level = ctx.getLevel();
        // Held, because a Synth plays only while its chunk is loaded, and nothing else keeps a test ship's chunks loaded until the listener's arrive.
        for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
            for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
                TestWaits.hold(ctx, level, x, z);
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

    /** A survival player who has joined, so the server's sound loop counts them as a listener, and the channel their packets go out on. */
    private record Listener(ServerPlayer player, Connection connection, EmbeddedChannel channel)
    {
        /** The packets sent to this player since the last call. */
        List<Object> sent()
        {
            connection.flushChannel();
            channel.runPendingTasks();
            var out = new ArrayList<Object>();
            Object packet;
            while ((packet = channel.readOutbound()) != null)
                out.add(packet);
            return out;
        }

        /** The vanilla sounds of one kind sent to this player since the last call. */
        List<ClientboundSoundPacket> sounds(net.minecraft.sounds.SoundEvent kind)
        {
            return sent().stream().filter(p -> p instanceof ClientboundSoundPacket s && s.getSound().value() == kind).map(p -> (ClientboundSoundPacket) p).toList();
        }
    }

    private static Listener join(GameTestHelper ctx, String name)
    {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(ctx.getLevel().getServer(), ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return new Listener(player, connection, channel);
    }

    /** Waits for {@code done} for at most {@code ticks}, then fails with the state the wait was about. */
    private static void until(GameTestHelper ctx, java.util.function.BooleanSupplier done, int ticks, java.util.function.Supplier<String> state, Runnable then)
    {
        if (done.getAsBoolean())
            then.run();
        else if (ticks <= 0)
            ctx.fail(state.get());
        else
            ctx.runAfterDelay(1, () -> until(ctx, done, ticks - 1, state, then));
    }

    private static ServerPlayer listener(GameTestHelper ctx, String name)
    {
        return join(ctx, name).player();
    }

    @GameTest(environment = "nullspace:sound", maxTicks = 600)
    public void roomsMuffleByTheirDoorsAHullMufflesOnAWorldWithAirAndNothingCarriesInVacuum(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(15000, 80, 15000));
        var ship = twoRooms(ctx, base, "Hearing test");
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
        var ship = twoRooms(ctx, base, "Synth test");
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
        java.util.function.Supplier<String> state = () -> "synth playing=" + synth.playing() + " silence='" + Synths.silence(level, synthAt) + "' listener at " + listener.blockPosition() + " in " + listener.level().dimension().identifier() + " muffle=" + Hearing.muffle(listener.level(), listener.getEyePosition(), Vec3.atCenterOf(synthAt)) + " sent=" + ShipSounds.playing(listener) + " recent=" + ShipSounds.recent(server);
        until(ctx, () -> ShipSounds.playing(listener).contains(key), 300, state, () ->
        {
            Synths.command(level, ship, helm, List.of("stop"));
            until(ctx, () -> !ShipSounds.playing(listener).contains(key), 300, state, () ->
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

    @GameTest(environment = "nullspace:sound", maxTicks = 600)
    public void minecraftSoundsAreQuieterThroughADoorAndAnExplosionInVacuumMakesNoSound(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(15400, 80, 15000));
        var ship = twoRooms(ctx, base, "Vanilla hearing test");
        var listener = join(ctx, "vanilla-listener");
        var p = listener.player();
        p.teleportTo(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 5.5, Set.of(), 0, 0, true);
        var source = base.offset(4, 3, 2);
        float door = Hearing.rooms(RoomAcoustics.DOOR);
        // Once the rooms are worked out (a vanilla sound never works them out itself, see RoomAcoustics).
        TestWaits.until(ctx, () -> Hearing.muffle(level, p.getEyePosition(), Vec3.atCenterOf(source)) == door, () ->
        {
            listener.sent();
            level.playSound(null, source, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1, 1);
            var through = listener.sounds(SoundEvents.ANVIL_LAND);
            ctx.assertTrue(through.size() == 1 && Math.abs(through.getFirst().getVolume() - SynthVoice.muffledGain(door)) < 1e-3, "Through the closed door the sound is sent quieter: " + through.stream().map(ClientboundSoundPacket::getVolume).toList());
            p.teleportTo(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 2.5, Set.of(), 0, 0, true);
            listener.sent();
            level.playSound(null, source, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1, 1);
            var clear = listener.sounds(SoundEvents.ANVIL_LAND);
            ctx.assertTrue(clear.size() == 1 && clear.getFirst().getVolume() == 1, "In the same room it is sent as it is: " + clear.stream().map(ClientboundSoundPacket::getVolume).toList());
            var blast = new ClientboundExplodePacket(Vec3.atCenterOf(source), 4, 0, Optional.empty(), ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, WeightedList.<ExplosionParticleInfo>of(), true);
            ctx.assertTrue(VanillaHearing.explosion(level, p, blast) == blast, "An explosion in the listener's room sounds as it is");
            p.teleportTo(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 5.5, Set.of(), 0, 0, true);
            var muffled = (ClientboundExplodePacket) VanillaHearing.explosion(level, p, blast);
            ctx.assertTrue(muffled.playSound() && muffled.explosionSound() == VanillaHearing.MUFFLED_EXPLOSION && muffled.playerKnockback().equals(blast.playerKnockback()), "Through a door an explosion is heard muffled");
            var vacuum = (ClientboundExplodePacket) VanillaHearing.explosion(server.getLevel(TravelService.SPACE), p, blast);
            ctx.assertTrue(!vacuum.playSound() && vacuum.radius() == blast.radius(), "In vacuum outside a hull an explosion makes no sound, but still bursts");
            server.getPlayerList().remove(p);
            WorldStore.get(server).ships().remove(ship);
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:sound", maxTicks = 600)
    public void aHullInSpaceHumsForEveryoneInsideItAndNotForSomeoneOnEva(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        // Out of the way of the space slots, near the vacuum point the first test checks.
        var base = new BlockPos(-130000, 100, -500000);
        for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
            for (int z = (base.getZ() - 8) >> 4; z <= (base.getZ() + 7) >> 4; z++)
                TestWaits.hold(ctx, space, x, z);
        // A ship reaches space only by launching, so the test records the hull there itself, as the combat tests do.
        var helm = ShipTestFixture.build(space, base);
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.asLong());
        ship.putLong("controller", helm.asLong());
        ship.putInt("width", 8);
        ship.putInt("depth", 8);
        ship.putInt("height", 8);
        WorldStore.get(server).ships().add(ship);
        var aboard = listener(ctx, "hum-aboard");
        var outside = listener(ctx, "hum-outside");
        aboard.teleportTo(space, base.getX() + 3.5, base.getY() + 3, base.getZ() + 2.5, Set.of(), 0, 0, true);
        outside.teleportTo(space, base.getX() + 3.5, base.getY() + 3, base.getZ() - 6.5, Set.of(), 0, 0, true);
        String key = "ship.hum:" + ship.getStringOr("id", "");
        TestWaits.until(ctx, () -> ShipSounds.playing(aboard).contains(key), () ->
        {
            ctx.assertTrue(ShipSounds.recent(server).contains("hum-aboard ship.hum muffle 0.0"), "The crew hear the hum clear, wherever they stand: " + ShipSounds.recent(server));
            ctx.assertTrue(!ShipSounds.playing(outside).contains(key), "Someone outside on EVA hears nothing: " + ShipSounds.playing(outside));
            server.getPlayerList().remove(aboard);
            server.getPlayerList().remove(outside);
            WorldStore.get(server).ships().remove(ship);
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:sound", maxTicks = 600)
    public void theEnginesHumThroughTheDoorWhileTheyThrustAndABlowOnTheHullReachesItsCrew(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(15600, 80, 15000));
        var ship = twoRooms(ctx, base, "Engine sound test");
        for (int i = 0; i < 20; i++)
            ShipSystems.tick(level, ship);
        var listener = listener(ctx, "engine-listener");
        listener.teleportTo(level, base.getX() + 3.5, base.getY() + 3, base.getZ() + 5.5, Set.of(), 0, 0, true);
        ctx.assertTrue(EngineSounds.hum(level, ship) == null, "Idle engines are quiet");
        // As the pilot's thrust sets it (NavigationService.fly); the engine stands in the front room.
        ship.putInt("thrustDemand", 4);
        ship.putDouble("thrustShare", .6);
        String key = "engine.hum:" + ship.getStringOr("id", "");
        TestWaits.until(ctx, () ->
        {
            // The thrust asks the engines for power on the systems' next step.
            ShipSystems.tick(level, ship);
            return ShipSounds.playing(listener).contains(key);
        }, () ->
        {
            var hum = EngineSounds.hum(level, ship);
            ctx.assertTrue(hum != null && hum.at().distanceTo(Vec3.atCenterOf(base.offset(0, 3, 3))) < .01 && hum.volume() > .7 && hum.pitch() > 1, "The hum comes from the engine and follows its thrust: " + hum);
            ctx.assertTrue(ShipSounds.recent(server).contains("engine-listener engine.hum muffle " + Hearing.rooms(RoomAcoustics.DOOR)), "The back room hears it through the door: " + ShipSounds.recent(server));
            ctx.assertTrue(ShipSounds.playing(listener).stream().noneMatch(k -> k.startsWith("ship.hum:")), "A hull on a world does not hum: " + ShipSounds.playing(listener));
            ship.putInt("thrustDemand", 0);
            TestWaits.until(ctx, () -> !ShipSounds.playing(listener).contains(key), () ->
            {
                // A blow outside the hull, which on a world with air would otherwise come through it as through a wall.
                ShipSounds.hull(level, ship, Vec3.atCenterOf(base.offset(3, 4, -1)), "hull.impact");
                ctx.assertTrue(ShipSounds.recent(server).getLast().equals("engine-listener hull.impact muffle " + Hearing.rooms(RoomAcoustics.WALL)), "The crew hear the blow through the hull: " + ShipSounds.recent(server));
                server.getPlayerList().remove(listener);
                WorldStore.get(server).ships().remove(ship);
                ctx.succeed();
            });
        });
    }
}
