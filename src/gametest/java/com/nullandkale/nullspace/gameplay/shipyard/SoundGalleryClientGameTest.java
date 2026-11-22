package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.render.sound.ClientSounds;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;

/**
 * Visual review of {@code /nullspace shipyard sounds}: the hall from the west end, the north row's stands up close, and the hearing row; and a lever and a
 * button really make sound on the client (the engine's loop and the clamp's thump and hiss).
 */
public final class SoundGalleryClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            ClientWaits.chunksRendered(world.getConnection());
            long[] origin = new long[3];
            world.getServer().runOnServer(server ->
            {
                var at = SoundGallery.build(server);
                origin[0] = at.getX();
                origin[1] = at.getY();
                origin[2] = at.getZ();
            });
            var cues = SoundGallery.cues();
            var northX = SoundGallery.columns(cues);
            int borrowed = 0;
            while (!cues.get(borrowed).group().equals("BORROWED"))
                borrowed++;
            var southX = SoundGallery.hearingColumns();
            double middle = (southX[0] + southX[southX.length - 1]) / 2.0;
            // Spectator views: the hall from the west end, the north row close up in three parts, the hearing row from across the aisle in two halves.
            String[] names = {"hall", "north-west", "north-middle", "north-borrowed", "hearing-east", "hearing-west"};
            double[][] views = {{1.5, 4.5, SoundGallery.DEPTH / 2.0, -90, 15}, {northX[4] + .5, 2.2, 7.5, 180, 15}, {northX[14] + .5, 2.2, 7.5, 180, 15}, {northX[borrowed + 4] + .5, 2.2, 7.5, 180, 15}, {middle + 13, 2.2, 5.5, 0, 15}, {middle - 13, 2.2, 5.5, 0, 15}};
            for (int i = 0; i < views.length; i++)
            {
                double[] view = views[i];
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(server.overworld(), origin[0] + view[0], origin[1] + view[1], origin[2] + view[2], Set.of(), (float) view[3], (float) view[4], true);
                });
                ClientWaits.chunksRendered(world.getConnection());
                c.waitTicks(30);
                c.takeScreenshot("nullspace-sound-gallery-" + names[i]);
            }
            // The full engine's lever, heard from the middle of the hall: the loop plays on the client.
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                world.getConnection().getServerPlayer().teleportTo(level, origin[0] + northX[8] + .5, origin[1] + 1.8, origin[2] + 7.5, Set.of(), 0, 10, true);
                var lever = new net.minecraft.core.BlockPos((int) origin[0] + northX[12], (int) origin[1] + 2, (int) origin[2] + SoundGallery.NORTH_Z);
                var state = level.getBlockState(lever);
                ((LeverBlock) state.getBlock()).pull(state, level, lever, null);
            });
            ClientWaits.waitFor(c, mc -> ClientSounds.heard("engine.hum") > .5, 400);
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                var lever = new net.minecraft.core.BlockPos((int) origin[0] + northX[12], (int) origin[1] + 2, (int) origin[2] + SoundGallery.NORTH_Z);
                var state = level.getBlockState(lever);
                ((LeverBlock) state.getBlock()).pull(state, level, lever, null);
                // The clamp's button: thump and hiss.
                var button = new net.minecraft.core.BlockPos((int) origin[0] + northX[13], (int) origin[1] + 2, (int) origin[2] + SoundGallery.NORTH_Z);
                var pressed = level.getBlockState(button);
                ((ButtonBlock) pressed.getBlock()).press(pressed, level, button, null);
            });
            ClientWaits.waitFor(c, mc -> ClientSounds.heard("dock.clamp") > .3, 200);
            ClientTestEvidence.passed(getClass());
        }
    }
}
