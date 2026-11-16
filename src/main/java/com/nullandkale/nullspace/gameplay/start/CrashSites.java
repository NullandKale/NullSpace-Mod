package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Where crashed ships come down. A crash site is a pad-shaped patch of ground: a 32 by 32 floor with a cobblestone ring
 * around it, a third of the ring blown away, a broken Pad Computer on a ring block, and craters around it. It is never
 * registered as a pad, so any number of crashes cannot fill the pad registry.
 *
 * <p>Sites sit on a grid of 40-block cells around the planet's landing spot (Terra's own pad on Terra), nearest first. A
 * cell is skipped if another crash holds it; the nearest few cells with gentle dry ground are preferred, but if none has
 * any, the nearest free cell is used anyway and made level, even over water or a steep slope: placing a crash never fails.
 */
public final class CrashSites
{
    /** The side of a grid cell: the pad and its ring (34) and a gap. */
    public static final int CELL = 40;
    /** How far a registered pad is kept from the blasts: sites are at least a cell plus this clear of its corner. */
    private static final int PAD_MARGIN = 16;
    /** The share of the pad ring that is blown away. */
    public static final double RING_BROKEN = .35;
    private static final int SIDE = 32;

    /** {@code minX} and {@code minZ} are the floor's corner; {@code y} is the floor; the ship's base is one above. */
    public record Site(String planet, int minX, int minZ, int y, boolean existing)
    {
        public BlockPos base()
        {
            return new BlockPos(minX, y + 1, minZ);
        }

        /** The ring block the broken Pad Computer stands on. */
        public BlockPos computer()
        {
            return new BlockPos(minX - 1, y + 1, minZ + 16);
        }

        public AABB box()
        {
            return new AABB(minX, y, minZ, minX + SIDE, y + 40, minZ + SIDE);
        }
    }

    private static ListTag sites(WorldStore store, String planet)
    {
        var all = store.section("crashSites");
        if (!all.contains(planet))
            all.put(planet, new ListTag());
        return all.getListOrEmpty(planet);
    }

    /** The saved site of a ship, or null. */
    public static Site of(WorldStore store, String planet, String shipId)
    {
        for (var tag : sites(store, planet))
        {
            var site = (CompoundTag) tag;
            if (site.getStringOr("ship", "").equals(shipId))
                return new Site(planet, site.getIntOr("x", 0), site.getIntOr("z", 0), site.getIntOr("y", 0), site.getBooleanOr("existing", false));
        }
        return null;
    }

    /** Reserves a site for the ship, nearest free cell to the planet's landing spot, and saves it. A repeat call returns the same site. */
    public static Site allocate(MinecraftServer server, ServerLevel level, String planet, String shipId)
    {
        var store = WorldStore.get(server);
        var saved = of(store, planet, shipId);
        if (saved != null)
            return saved;
        int cx, cz, cy;
        boolean terraPad = false;
        var terra = planet.equals(LocalSpaces.HOME) ? PadRegistry.read(server).stream().filter(p -> p.dimension().equals(Level.OVERWORLD.identifier().toString())).findFirst().orElse(null) : null;
        if (terra != null)
        {
            cx = terra.bounds().minX();
            cz = terra.bounds().minZ();
            cy = terra.bounds().y();
            terraPad = true;
        }
        else
        {
            var corner = PlanetSurfaces.landingSite(level);
            cx = corner.getX();
            cz = corner.getZ();
            cy = corner.getY();
        }
        var taken = new ArrayList<int[]>();
        for (var tag : sites(store, planet))
            taken.add(new int[]{((CompoundTag) tag).getIntOr("x", 0), ((CompoundTag) tag).getIntOr("z", 0), 0});
        // A registered pad is never built over (Terra's own is the first site, below).
        for (var pad : PadRegistry.read(server))
            if (pad.dimension().equals(level.dimension().identifier().toString()) && !(terraPad && pad.bounds().minX() == cx && pad.bounds().minZ() == cz))
                taken.add(new int[]{pad.bounds().minX(), pad.bounds().minZ(), PAD_MARGIN});
        var cells = new ArrayList<int[]>();
        for (int i = -12; i <= 12; i++)
            for (int j = -12; j <= 12; j++)
                cells.add(new int[]{i, j});
        cells.sort(Comparator.<int[]>comparingInt(c -> c[0] * c[0] + c[1] * c[1]).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        Site best = null;
        int examined = 0;
        for (var cell : cells)
        {
            int x = cx + cell[0] * CELL, z = cz + cell[1] * CELL;
            if (taken.stream().anyMatch(t -> Math.abs(t[0] - x) < CELL + t[2] && Math.abs(t[1] - z) < CELL + t[2]))
                continue;
            boolean origin = cell[0] == 0 && cell[1] == 0;
            int y = origin ? cy : level.getMinY();
            boolean gentle = true;
            if (!origin)
            {
                int[] terrain = terrain(level, x, z);
                y = terrain[0];
                gentle = terrain[1] <= 8 && terrain[2] <= 4;
            }
            var site = new Site(planet, x, z, y, origin && terraPad);
            if (best == null)
                best = site;
            // The nearest free cell is the fallback; a gentle dry one among the nearest few is preferred.
            if (gentle)
            {
                best = site;
                break;
            }
            if (++examined >= 12)
                break;
        }
        var chosen = best != null ? best : new Site(planet, cx + 13 * CELL, cz, cy, false);
        var record = new CompoundTag();
        record.putInt("x", chosen.minX());
        record.putInt("z", chosen.minZ());
        record.putInt("y", chosen.y());
        record.putBoolean("existing", chosen.existing());
        record.putString("ship", shipId);
        sites(store, planet).add(record);
        store.requestSave();
        return chosen;
    }

    /** {height to build at, height spread, wet columns} estimated from the terrain noise, without loading chunks. */
    private static int[] terrain(ServerLevel level, int x, int z)
    {
        var chunks = level.getChunkSource();
        var heights = new ArrayList<Integer>();
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE, wet = 0;
        for (int dx = 0; dx <= 33; dx += 8)
            for (int dz = 0; dz <= 33; dz += 8)
            {
                int surface = chunks.getGenerator().getBaseHeight(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG, level, chunks.randomState()) - 1;
                int floor = chunks.getGenerator().getBaseHeight(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, chunks.randomState()) - 1;
                if (surface > floor)
                    wet++;
                heights.add(surface);
                low = Math.min(low, surface);
                high = Math.max(high, surface);
            }
        Collections.sort(heights);
        int median = heights.get(heights.size() / 2);
        int y = Math.clamp(Math.max(median, level.getSeaLevel() + 1), level.getMinY() + 8, level.getMaxY() - 44);
        return new int[]{y, high - low, wet};
    }

    /** Levels the ground (unless this is Terra's own pad), lays the ring, breaks a third of it, and stands the broken computer on it. */
    public static void prepare(ServerLevel level, Site site, long seed)
    {
        for (int cx = (site.minX() - 2) >> 4; cx <= (site.minX() + SIDE + 1) >> 4; cx++)
            for (int cz = (site.minZ() - 2) >> 4; cz <= (site.minZ() + SIDE + 1) >> 4; cz++)
                level.getChunk(cx, cz);
        var random = new Random(seed ^ site.minX() * 31L ^ site.minZ() * 17L);
        if (!site.existing())
            for (int x = -1; x <= SIDE; x++)
                for (int z = -1; z <= SIDE; z++)
                {
                    var column = new BlockPos(site.minX() + x, site.y(), site.minZ() + z);
                    for (int y = site.y() + 1; y <= site.y() + 40; y++)
                        level.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    // Fill down to solid ground, so a site over water or a drop stands on stone.
                    for (int y = site.y() - 1; y >= level.getMinY(); y--)
                    {
                        var below = column.atY(y);
                        var state = level.getBlockState(below);
                        if (!state.isAir() && state.getFluidState().isEmpty())
                            break;
                        level.setBlock(below, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    boolean ring = x < 0 || x > SIDE - 1 || z < 0 || z > SIDE - 1;
                    level.setBlock(column, (ring ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
                }
        // A third of the ring is gone. The block under the computer stays.
        for (int x = -1; x <= SIDE; x++)
            for (int z = -1; z <= SIDE; z++)
            {
                boolean ring = x < 0 || x > SIDE - 1 || z < 0 || z > SIDE - 1;
                if (!ring || x == -1 && z == 16)
                    continue;
                if (random.nextDouble() < RING_BROKEN)
                    level.setBlock(new BlockPos(site.minX() + x, site.y(), site.minZ() + z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        var computer = site.computer();
        if (site.existing())
        {
            var terra = PadRegistry.read(level.getServer()).stream().filter(p -> p.dimension().equals(Level.OVERWORLD.identifier().toString())).findFirst();
            if (terra.isPresent())
                computer = terra.get().computer();
        }
        level.removeBlockEntity(computer);
        level.setBlock(computer, BrokenMachines.PAD_COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), Block.UPDATE_CLIENTS);
    }

    /**
     * Explosions around the outside of the pad: craters in the ground and more of the ring gone. The ship's own blocks, and the
     * air above its footprint, are immune, so the cabin and machines are only ever hurt by {@link CrashDamage}.
     */
    public static void explode(ServerLevel level, Site site, long seed)
    {
        for (int i = 0; i < BLASTS; i++)
            blast(level, site, i, seed);
        // The blasts throw loose blocks as items: sweep them up, so the site is a scar and not a litter of drops.
        var area = new AABB(site.minX() - 1, site.y(), site.minZ() - 1, site.minX() + SIDE + 1, site.y() + 40, site.minZ() + SIDE + 1).inflate(14, 10, 14);
        for (var item : level.getEntitiesOfClass(ItemEntity.class, area))
            item.discard();
    }

    /** The number of blasts around a site. */
    public static final int BLASTS = 6;

    /** One of the blasts, alone, so a video can set them off one at a time. Items it throws are left where they fall. */
    public static void blast(ServerLevel level, Site site, int index, long seed)
    {
        var random = new Random(seed ^ 0xC2A5L ^ site.minX() ^ index * 0x9E3779B9L);
        var footprint = new AABB(site.minX() - 1, site.y(), site.minZ() - 1, site.minX() + SIDE + 1, site.y() + 40, site.minZ() + SIDE + 1);
        var shield = new ExplosionDamageCalculator()
        {
            @Override
            public Optional<Float> getBlockExplosionResistance(net.minecraft.world.level.Explosion explosion, net.minecraft.world.level.BlockGetter getter, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.material.FluidState fluid)
            {
                return footprint.contains(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) ? Optional.of(100000f) : super.getBlockExplosionResistance(explosion, getter, pos, state, fluid);
            }
        };
        double angle = index * Math.PI / 3 + random.nextDouble() * .8;
        double reach = 22 + random.nextDouble() * 5;
        double x = site.minX() + 16 + Math.cos(angle) * reach, z = site.minZ() + 16 + Math.sin(angle) * reach;
        level.explode(null, null, shield, x, site.y() + 1.5, z, 3.5f, false, Level.ExplosionInteraction.BLOCK);
    }

    private CrashSites()
    {
    }
}
