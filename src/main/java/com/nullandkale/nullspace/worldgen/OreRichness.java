package com.nullandkale.nullspace.worldgen;

import com.mojang.serialization.MapCodec;
import com.nullandkale.nullspace.gameplay.navigation.PlanetDimensions;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * The farther a world is from Terra, the more ore it has and the nearer the surface it lies. Added to the planets' copies of the
 * vanilla ore features ({@code data/nullspace/worldgen/placed_feature/ore_*.json}, written by
 * {@code scripts/art/generate_planet_surfaces.py}), it multiplies each attempt by the world's band and lifts the attempt toward the
 * surface. The band is the zone's danger (0 at Sol, 1 within 35 light-years, 2 within 65, 3 beyond; see {@link PlanetDimensions#band}).
 * Everything that is not a created planet is band 0, so Terra and Luna keep vanilla amounts.
 */
public record OreRichness() implements PlacementModifier
{
    public static final MapCodec<OreRichness> CODEC = MapCodec.unit(new OreRichness());

    /** What each attempt is multiplied by, per band. */
    public static final double[] COUNT = {1.0, 1.5, 2.0, 3.0};

    /** The most blocks an attempt may be lifted toward the surface, per band. */
    public static final int[] LIFT = {0, 16, 32, 48};

    /** Blocks kept between a lifted vein and the open sky. */
    private static final int COVER = 3;

    public static void initialize()
    {
        Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, Identifier.parse("nullspace:richness"), CODEC);
    }

    /** How many attempts one attempt becomes in a band: the whole part, plus one more with the chance of the fraction. */
    public static int attempts(int band, RandomSource random)
    {
        double multiplier = COUNT[Math.clamp(band, 0, 3)];
        int whole = (int) Math.floor(multiplier);
        return whole + (random.nextDouble() < multiplier - whole ? 1 : 0);
    }

    /** Where an attempt at height {@code y} moves to in a band, given the height of the surface above it. */
    public static int lifted(int band, int y, int surface, RandomSource random)
    {
        int lift = LIFT[Math.clamp(band, 0, 3)];
        if (lift == 0)
            return y;
        int ceiling = surface - COVER;
        return ceiling <= y ? y : Math.min(y + random.nextInt(lift + 1), ceiling);
    }

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output)
    {
        int band = PlanetDimensions.band(context.getLevel().getLevel());
        int surface = band == 0 ? origin.getY() : context.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ());
        for (int i = attempts(band, random); i > 0; i--)
            output.accept(new BlockPos(origin.getX(), lifted(band, origin.getY(), surface, random), origin.getZ()));
    }

    @Override
    public MapCodec<OreRichness> codec()
    {
        return CODEC;
    }
}
