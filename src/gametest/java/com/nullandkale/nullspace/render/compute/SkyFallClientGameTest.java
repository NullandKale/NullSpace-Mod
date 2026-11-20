package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Opt-in (run with {@code -Dnullspace.video.skyfall=true}; it does nothing otherwise): a player falls from far above a real
 * overworld (a normal generated world, not the test framework's flat default), over the nearest village. It starts weightless looking up at the sun among the stars, then lowers to the planet's curved
 * edge, then gravity comes back, the fall carries it through the cloud layer, and gravity is turned down again near the
 * ground so the landscape is seen for longer before the landing. The camera turns slowly, stays level with the horizon until the ground is close, and ends ten
 * degrees from straight down. One screenshot every two ticks, so {@code scripts/test/make-video.py} joins them at real speed
 * (and {@code --every N} plays it faster). {@code -Dnullspace.video.seed=<n>} picks the world.
 */
public final class SkyFallClientGameTest implements FabricClientGameTest
{
    private static final int START_HEIGHT = 1700, CHUNKS = 20;
    /** Ticks of weightlessness at the start, then the ticks gravity takes to come back. */
    private static final int FLOAT_TICKS = 220, RETURN_TICKS = 140;

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null || !Boolean.getBoolean("nullspace.video.skyfall"))
        {
            ClientTestEvidence.passed(getClass());
            return;
        }
        long seed = Long.getLong("nullspace.video.seed", 20261115L);
        System.out.println("SKYFALL seed " + seed);
        try (var world = c.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> settings.setSeed(Long.toString(seed))).create())
        {
            ClientWaits.chunksRendered(world.getConnection());
            double[] site = new double[3];
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                // The nearest village, for something to land beside; the spawn point if there is none.
                BlockPos at = level.getRespawnData().pos();
                var village = level.findNearestMapStructure(StructureTags.VILLAGE, at, 100, false);
                if (village != null)
                    at = village;
                level.getChunk(at.getX() >> 4, at.getZ() >> 4);
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
                site[0] = at.getX() + .5;
                site[1] = ground;
                site[2] = at.getZ() + .5;
                System.out.println("SKYFALL site " + at.getX() + " " + ground + " " + at.getZ() + (village != null ? " (village)" : " (spawn)"));
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 2000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule advance_time false");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                server.getPlayerList().setViewDistance(CHUNKS);
                var player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                player.teleportTo(level, site[0], ground + 2, site[2], Set.of(), 0, 0, true);
            });
            c.runOnClient(mc ->
            {
                mc.options.renderDistance().set(CHUNKS);
                if (!mc.gui.hud.isHidden())
                    mc.gui.hud.toggle();
            });
            // Let the landscape around the landing site generate and arrive before the fall starts.
            c.waitTicks(500);
            world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().teleportTo(server.overworld(), site[0], START_HEIGHT, site[2], Set.of(), -90, -28, true));
            c.waitTicks(10);
            ClientWaits.waitFor(c, client -> SpaceSceneRenderer.planetSkyReady() && SpaceSceneRenderer.viewport().compute().atmosphereReady(), 600);
            c.waitTicks(10);
            int landedFrames = 0;
            for (int tick = 0; tick < 3200 && landedFrames < 4; tick += 2)
            {
                int now = tick;
                boolean[] onGround = new boolean[1];
                c.runOnClient(mc ->
                {
                    double altitude = Math.max(0, mc.player.getY() - site[1]);
                    // Weightless at first, gravity coming back, then slower and slower toward the ground.
                    double fromTime = now < FLOAT_TICKS ? .004 : now < FLOAT_TICKS + RETURN_TICKS ? .004 + .076 * (now - FLOAT_TICKS) / RETURN_TICKS : .08;
                    double fromHeight = altitude > 1200 ? .08 : altitude > 300 ? .02 + .06 * (altitude - 300) / 900 : .01 + .01 * altitude / 300;
                    mc.player.getAttribute(Attributes.GRAVITY).setBaseValue(Math.min(fromTime, fromHeight));
                    // Level with the horizon until the ground is close, so it fills the view when the camera finally tips down, then
                    // eased down to ten degrees from straight down.
                    double turn = Math.clamp((500 - altitude) / 380, 0, 1);
                    double up = Math.clamp(1 - now / 160., 0, 1);
                    mc.player.setXRot((float) (4 + 51 * turn * turn * (3 - 2 * turn) - 32 * up * up * (3 - 2 * up)));
                    mc.player.setYRot(-90 + now * .18f);
                    onGround[0] = mc.player.onGround();
                });
                if (onGround[0])
                    landedFrames++;
                c.waitTicks(2);
                c.takeScreenshot("nullspace-skyfall-frame");
            }
            c.runOnClient(mc ->
            {
                mc.player.getAttribute(Attributes.GRAVITY).setBaseValue(.08);
                if (mc.gui.hud.isHidden())
                    mc.gui.hud.toggle();
            });
        }
        ClientTestEvidence.passed(getClass());
    }
}
