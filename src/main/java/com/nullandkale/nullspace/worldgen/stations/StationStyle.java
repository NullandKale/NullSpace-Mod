package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.*;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a manufacturer's stations look: a small fixed palette used in repeating courses and patterns,
 * a window style, a floor pattern and a roof shape. Everything is a function of the maker, the
 * station seed and the cell, so one seed always gives the same blocks. Every block here is a full
 * cube, so shells stay sealed whichever variant a cell picks.
 */
public final class StationStyle
{
    /** How exterior walls are glazed. */
    public enum Windows
    {
        /** Continuous panoramic bands broken only by mullions. */
        BANDS,
        /** Rows of paired two-by-two windows between piers. */
        GRID,
        /** Tall narrow slits. */
        SLITS
    }

    /** How a room's floor is laid. */
    public enum Floors
    {
        /** Two-by-two plates in a check, with a stripe down the middle of corridors. */
        PLATES,
        /** Rings of inlay stepping in from the walls. */
        INLAY,
        /** A dark border with a lighter cross through the middle. */
        BORDER
    }

    /** How exposed rooftops are shaped. */
    public enum Roofs
    {
        /** Flat, with a low parapet and vents. */
        PARAPET,
        /** Stepped terraces rising to a glass skylight. */
        TERRACED,
        /** A crenellated parapet with corner turrets. */
        BATTLEMENT
    }

    private final Maker maker;
    private final long seed;
    public final Block frame, skirting, cornice, ceiling, floorA, floorB, accent, glass, roof, rail, wood, beam;
    public final Windows windows;
    public final Floors floors;
    public final Roofs roofs;
    /** Distance between piers along a wall, or 0 for none. */
    public final int pierSpacing;

    private StationStyle(Maker maker, long seed, Block frame, Block skirting, Block cornice, Block ceiling, Block floorA, Block floorB, Block accent, Block roof, Windows windows, Floors floors, Roofs roofs, int pierSpacing)
    {
        this.maker = maker;
        this.seed = seed;
        this.frame = frame;
        this.skirting = skirting;
        this.cornice = cornice;
        this.ceiling = ceiling;
        this.floorA = floorA;
        // Each palette carries one wood: planks for floor inlays and edges, a log for piers and ceiling beams.
        this.wood = switch (maker)
        {
            case RIVET -> Blocks.SPRUCE_PLANKS;
            case HALCYON -> Blocks.BIRCH_PLANKS;
            case BASTION -> Blocks.DARK_OAK_PLANKS;
        };
        this.beam = switch (maker)
        {
            case RIVET -> Blocks.SPRUCE_LOG;
            case HALCYON -> Blocks.BIRCH_LOG;
            case BASTION -> Blocks.DARK_OAK_LOG;
        };
        this.floorB = this.wood;
        this.accent = accent;
        this.glass = Blocks.GLASS;
        this.roof = roof;
        this.rail = maker == Maker.HALCYON ? Blocks.GLASS_PANE : Blocks.IRON_BARS;
        this.windows = windows;
        this.floors = floors;
        this.roofs = roofs;
        this.pierSpacing = pierSpacing;
    }

    public static StationStyle of(Maker maker, long seed)
    {
        return switch (maker)
        {
            case RIVET -> new StationStyle(maker, seed, Blocks.CHISELED_STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE, Blocks.CONCRETE.orange(), Blocks.SMOOTH_STONE, Windows.GRID, Floors.PLATES, Roofs.PARAPET, 6);
            case HALCYON -> new StationStyle(maker, seed, Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ, Blocks.CONCRETE.lightGray(), Blocks.CONCRETE.cyan(), Blocks.SMOOTH_QUARTZ, Windows.BANDS, Floors.INLAY, Roofs.TERRACED, 5);
            case BASTION -> new StationStyle(maker, seed, Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.POLISHED_DEEPSLATE, Blocks.CONCRETE.red(), Blocks.DEEPSLATE_BRICKS, Windows.SLITS, Floors.BORDER, Roofs.BATTLEMENT, 4);
        };
    }

    public Maker maker()
    {
        return maker;
    }

    /** A stable value in [0, 1) for a cell and a purpose. */
    public double noise(int x, int y, int z, int salt)
    {
        long h = seed * 0x9E3779B97F4A7C15L ^ (x * 0xBF58476D1CE4E5B9L) ^ (y * 0x94D049BB133111EBL) ^ (z * 0xD6E8FEB86659FD93L) ^ (salt * 0xA24BAED4963EE407L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * The wall block at a cell. Halcyon and Bastion lay regular courses (a band every few rows), so
     * their walls read as designed; only Rivet's old stonework has the odd cracked brick, sparsely.
     */
    public BlockState hull(int x, int y, int z)
    {
        return switch (maker)
        {
            case RIVET -> (noise(x, y, z, 1) < .08 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
            case HALCYON -> (Math.floorMod(y, 4) == 0 ? Blocks.QUARTZ_BRICKS : Blocks.QUARTZ_BLOCK).defaultBlockState();
            case BASTION -> (Math.floorMod(y, 3) == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_BRICKS).defaultBlockState();
        };
    }

    /** The floor block at a room's interior cell {@code (rx, rz)} counted from its corner, in a room of interior size {@code w} by {@code d}. */
    public BlockState floor(int rx, int rz, int w, int d, boolean corridor)
    {
        int edge = Math.min(Math.min(rx, rz), Math.min(w - 1 - rx, d - 1 - rz));
        Block block = switch (floors)
        {
            case PLATES ->
            {
                boolean stripe = corridor && (w >= d ? rz == d / 2 : rx == w / 2);
                yield stripe ? accent : ((rx / 2 + rz / 2) % 2 == 0 ? floorA : floorB);
            }
            case INLAY -> edge == 1 && Math.min(w, d) >= 5 ? accent : edge == 0 ? floorB : floorA;
            case BORDER -> edge == 0 ? floorB : (rx == w / 2 || rz == d / 2) && Math.min(w, d) >= 5 ? floorB : floorA;
        };
        return block.defaultBlockState();
    }
}
