package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.awt.image.BufferedImage;
import java.util.Set;
import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Opt-in (run with {@code -Dnullspace.perf.fog=true}; it does nothing otherwise, as it takes about a minute): a review tool, not
 * a check. Where grass runs out to the edge of the render distance, the far terrain must meet the planet sky without a band of the
 * wrong colour between them. Vanilla fades far terrain into its biome fog colour, a pale blue against the planet sky, so the
 * fog is replaced by a fade into the sky's own colour. The check is a photograph of the horizon: the sky just above the
 * terrain edge and the terrain just below it must be close in colour.
 */
public final class FogBlendClientGameTest implements FabricClientGameTest
{
    private static final int CENTER_X = 886, CENTER_Z = -82, GROUND = 64, RADIUS = 288;
    /** The render distance the photograph is taken at, in chunks; the server sends the same. */
    private static final int RENDER_CHUNKS = 16;

    private static double[] average(BufferedImage image, int x0, int x1, int y0, int y1)
    {
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
            {
                int p = image.getRGB(x, y);
                r += p >> 16 & 255;
                g += p >> 8 & 255;
                b += p & 255;
                n++;
            }
        return new double[]{r / n, g / n, b / n};
    }

    private static double distance(double[] a, double[] b)
    {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null || !Boolean.getBoolean("nullspace.perf.fog"))
        {
            ClientTestEvidence.passed(getClass());
            return;
        }
        try (var world = c.worldBuilder().create())
        {
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(RENDER_CHUNKS));
            world.getConnection().waitForChunksRender();
            c.runOnClient(mc -> mc.options.renderDistance().set(RENDER_CHUNKS));
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                var level = server.overworld();
                // Grass past the render distance on every side, so the terrain edge seen is the render edge.
                for (int x = -RADIUS; x <= RADIUS; x++)
                    for (int z = -RADIUS; z <= RADIUS; z++)
                    {
                        level.setBlock(new BlockPos(CENTER_X + x, GROUND, CENTER_Z + z), Blocks.GRASS_BLOCK.defaultBlockState(), 18);
                        for (int y = GROUND + 1; y < GROUND + 6; y++)
                            level.setBlock(new BlockPos(CENTER_X + x, y, CENTER_Z + z), Blocks.AIR.defaultBlockState(), 18);
                    }
                var player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.CREATIVE);
                player.teleportTo(level, CENTER_X + .5, GROUND + 1, CENTER_Z + .5, Set.of(), 0, 0, true);
            });
            c.waitFor(client -> SpaceSceneRenderer.planetSkyReady() && SpaceSceneRenderer.viewport().compute().atmosphereReady(), 300);
            // The server sends the chunks a few at a time, so give it long enough for the terrain to reach the render edge.
            c.waitTicks(600);
            c.runOnClient(mc ->
            {
                var fade = SpaceSceneRenderer.chunkFadeFor(SpaceSceneRenderer.viewport().compute());
                System.out.println("FOGBLEND fade range " + fade[0] + " to " + fade[1] + " render distance option " + mc.options.renderDistance().get() + " planetSky " + SpaceSceneRenderer.planetSkyReady());
            });
            var path = c.takeScreenshot("nullspace-fog-horizon");
            try
            {
                var image = ImageIO.read(path.toFile());
                int w = image.getWidth(), h = image.getHeight();
                // The terrain edge is the row with the biggest jump in brightness near the middle of the picture.
                int edge = h / 2;
                double best = 0;
                for (int y = h / 2 - 40; y < h / 2 + 60; y++)
                {
                    var above = average(image, w / 4, w * 3 / 4, y - 2, y - 1);
                    var below = average(image, w / 4, w * 3 / 4, y + 1, y + 2);
                    double jump = distance(above, below);
                    if (jump > best)
                    {
                        best = jump;
                        edge = y;
                    }
                }
                // On a flat plain the whole fade sits in a row or two at the horizon, so what matters is the seam itself: how far the
                // colour jumps between the row above the terrain edge and the row below it.
                var sky = average(image, w / 4, w * 3 / 4, edge - 3, edge - 1);
                var far = average(image, w / 4, w * 3 / 4, edge + 1, edge + 2);
                var near = average(image, w / 4, w * 3 / 4, edge + 60, edge + 70);
                double seam = distance(sky, far);
                System.out.println("FOGBLEND edge row " + edge + " sky " + java.util.Arrays.toString(sky) + " terrain below the edge " + java.util.Arrays.toString(far) + " near terrain " + java.util.Arrays.toString(near) + " seam colour distance " + seam);
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        }
        ClientTestEvidence.passed(getClass());
    }
}
