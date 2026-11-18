package com.nullandkale.nullspace.gameplay.shipyard;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Writes plain blocks straight into chunk sections, the way the chunk generator does, instead of one {@code setBlock} at a time. A {@code setBlock}
 * updates four heightmaps, the skylight sources and the light engine for every block, and tells the chunk and its watchers; for the millions of
 * cells of solid ground a gallery fills, that is almost all of its time. This writes the palette only, and {@link #flush} then does what is needed
 * once for each chunk touched: primes the heightmaps, rebuilds the skylight sources, tells the light engine which sections are empty and marks the
 * chunk to be saved. It is for plain blocks (stone, dirt, grass, air): anything with a block entity or that reacts to being placed is placed with
 * {@code setBlock}. An old block entity under a cell that is written over is removed, so a gallery rebuilt in the same place leaves none behind.
 */
final class FastBlocks
{
    private final ServerLevel level;
    private final Set<ChunkPos> touched = new LinkedHashSet<>();
    private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;

    FastBlocks(ServerLevel level)
    {
        this.level = level;
    }

    /** Writes one block into its chunk section. */
    void set(int x, int y, int z, BlockState state)
    {
        if (level.isOutsideBuildHeight(y))
            return;
        var chunk = level.getChunk(x >> 4, z >> 4);
        var section = chunk.getSection(chunk.getSectionIndex(y));
        if (!chunk.getBlockEntities().isEmpty())
            chunk.removeBlockEntity(new BlockPos(x, y, z));
        section.setBlockState(x & 15, y & 15, z & 15, state, false);
        if ((x >> 4) != lastX || (z >> 4) != lastZ)
        {
            lastX = x >> 4;
            lastZ = z >> 4;
            touched.add(chunk.getPos());
        }
    }

    /** What has to be done once for each chunk written to: heightmaps, skylight sources, which sections are empty, and the save. */
    void flush()
    {
        var engine = level.getChunkSource().getLightEngine();
        for (var pos : touched)
        {
            var chunk = level.getChunk(pos.x(), pos.z());
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
            chunk.initializeLightSources();
            for (int index = 0; index < chunk.getSectionsCount(); index++)
                engine.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(index)), chunk.getSection(index).hasOnlyAir());
            chunk.markUnsaved();
        }
        resend(level, touched);
        touched.clear();
        lastX = Integer.MIN_VALUE;
        lastZ = Integer.MIN_VALUE;
    }

    /**
     * Sends each chunk to the players who have it loaded. Blocks written to a section never go through {@code setBlock}, which is what tells the players
     * watching a chunk that a block in it has changed, so a player who was there before the write would see the old chunk until they left and came back.
     */
    static void resend(ServerLevel level, Collection<ChunkPos> chunks)
    {
        var map = level.getChunkSource().chunkMap;
        for (var pos : chunks)
        {
            var watchers = map.getPlayers(pos, false);
            if (watchers.isEmpty())
                continue;
            var chunk = level.getChunk(pos.x(), pos.z());
            var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            for (var player : watchers)
                player.connection.send(packet);
        }
    }

    /**
     * Makes every chunk give its light sources out again (the torches and lamps in it), so that the rooms placed in it are lit whatever order the
     * blocks went in. The light engine only ever adds light from these, so it is safe to run on a chunk that is already lit.
     */
    static void relight(ServerLevel level, Collection<ChunkPos> chunks)
    {
        var engine = level.getChunkSource().getLightEngine();
        for (var pos : chunks)
            engine.propagateLightSources(pos);
    }
}
