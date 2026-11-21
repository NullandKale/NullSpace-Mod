package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.synth.SynthBlockEntity;
import com.nullandkale.nullspace.gameplay.sound.Hearing;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.gameplay.systems.RoomAcoustics;
import com.nullandkale.nullspace.model.sound.SoundCues;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;

/** The sound gallery: every cue the game plays on a stand, a lever for each loop and a button for the rest, and pulling and pressing them plays the cue. */
public final class SoundGalleryGameTest
{
    @GameTest(environment = "nullspace:sound_gallery", maxTicks = 900)
    public void everyCueHasAStandAndItsLeverOrButtonPlaysItAsTheGameDoes(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var origin = SoundGallery.build(server);
        var north = SoundGallery.cues();
        var south = SoundGallery.hearing();
        var northX = SoundGallery.columns(north);
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + northX[northX.length - 1] + 8) >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + SoundGallery.DEPTH) >> 4; cz++)
                TestWaits.hold(ctx, level, cx, cz);
        var names = new HashSet<String>();
        for (int i = 0; i < north.size(); i++)
        {
            var at = origin.offset(northX[i], 1, SoundGallery.NORTH_Z);
            var stand = north.get(i);
            ctx.assertTrue(level.getBlockEntity(at) instanceof SynthBlockEntity synth && stand.cue().equals(synth.cue()), "Stand " + stand.name() + " " + stand.detail() + " plays its cue at " + at);
            ctx.assertTrue(level.getBlockState(at.above()).is(stand.loop() ? Blocks.LEVER : Blocks.STONE_BUTTON), "A loop has a lever and a one-shot a button: " + stand.name());
            ctx.assertTrue(level.getBlockState(at.above(2)).is(Blocks.OAK_WALL_SIGN), "Its sign hangs on the wall above it, clear of its front: " + stand.name());
            names.add(stand.cue().name());
        }
        var hearingX = SoundGallery.hearingColumns();
        ctx.assertTrue(hearingX[0] > hearingX[1] && hearingX[3] > hearingX[4] + SoundGallery.PITCH, "The hearing row runs east to west, so it reads from clear to a wall for someone facing it, with a gap between its groups");
        ctx.assertTrue(names.containsAll(SoundCues.names()), "Every cue has a stand; missing: " + SoundCues.names().stream().filter(n -> !names.contains(n)).toList());
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "gallery-listener"), false);
        var listener = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, listener, cookie);
        listener.teleportTo(level, origin.getX() + northX[12] + .5, origin.getY() + 1, origin.getZ() + SoundGallery.DEPTH / 2.0, Set.of(), 0, 0, true);
        // The full engine: its lever starts the loop with the hum the game gives full thrust at speed.
        int engine = 12;
        ctx.assertTrue(north.get(engine).detail().equals("full, at speed"), "The thirteenth stand is the full engine");
        var engineAt = origin.offset(northX[engine], 1, SoundGallery.NORTH_Z);
        var lever = level.getBlockState(engineAt.above());
        ((LeverBlock) lever.getBlock()).pull(lever, level, engineAt.above(), null);
        String key = "synth:" + level.dimension().identifier() + ":" + engineAt.asLong();
        TestWaits.until(ctx, () -> ShipSounds.playing(listener).contains(key), () ->
        {
            ctx.assertTrue(ShipSounds.recent(server).contains("gallery-listener engine.hum muffle 0.0"), "The lever plays the engine cue itself: " + ShipSounds.recent(server));
            var pulled = level.getBlockState(engineAt.above());
            ((LeverBlock) pulled.getBlock()).pull(pulled, level, engineAt.above(), null);
            ctx.assertTrue(!((SynthBlockEntity) level.getBlockEntity(engineAt)).playing(), "The lever stops it again");
            // The clamp's button plays the whole cue, thump and hiss.
            int clamp = 13;
            ctx.assertTrue(north.get(clamp).cue().name().equals("dock.clamp"), "The fourteenth stand is the clamp");
            var clampAt = origin.offset(northX[clamp], 1, SoundGallery.NORTH_Z);
            // In front of each stand pressed: a sound in the world carries 40 blocks, less than the hall's length.
            listener.teleportTo(level, clampAt.getX() + .5, origin.getY() + 1, origin.getZ() + SoundGallery.DEPTH / 2.0, Set.of(), 0, 0, true);
            var button = level.getBlockState(clampAt.above());
            ((ButtonBlock) button.getBlock()).press(button, level, clampAt.above(), null);
            ctx.assertTrue(ShipSounds.recent(server).getLast().equals("gallery-listener dock.clamp muffle 0.0"), "The button plays the clamp: " + ShipSounds.recent(server));
            // The hearing row's last stand: a hit on the hull as through a wall.
            var southX = SoundGallery.columns(south);
            var wall = south.size() - 1;
            BlockPos wallAt = null;
            for (int dx = 0; dx < northX[northX.length - 1] + 8 && wallAt == null; dx++)
            {
                var at = origin.offset(dx, 1, SoundGallery.SOUTH_Z);
                if (level.getBlockEntity(at) instanceof SynthBlockEntity synth && south.get(wall).cue().equals(synth.cue()))
                    wallAt = at;
            }
            ctx.assertTrue(wallAt != null, "The hearing row has its hull hit through a wall (" + southX.length + " stands)");
            listener.teleportTo(level, wallAt.getX() + .5, origin.getY() + 1, origin.getZ() + SoundGallery.DEPTH / 2.0, Set.of(), 0, 0, true);
            var wallButton = level.getBlockState(wallAt.above());
            ((ButtonBlock) wallButton.getBlock()).press(wallButton, level, wallAt.above(), null);
            ctx.assertTrue(ShipSounds.recent(server).getLast().equals("gallery-listener hull.impact muffle " + Hearing.rooms(RoomAcoustics.WALL)), "It is heard as through a wall: " + ShipSounds.recent(server));
            // A second visit builds the hall again in the same place.
            ctx.assertTrue(SoundGallery.build(server).equals(origin) && level.getBlockEntity(engineAt) instanceof SynthBlockEntity again && !again.playing(), "A second build replaces the first");
            server.getPlayerList().remove(listener);
            ctx.succeed();
        });
    }
}
