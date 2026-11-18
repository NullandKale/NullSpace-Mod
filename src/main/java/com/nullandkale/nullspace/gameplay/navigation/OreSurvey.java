package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.worldgen.OreRichness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ore finding with the Scanner (chunk A7 of {@code docs/design/rewards-work-plan.md}); sneak-use a powered Scanner on a surface. The
 * stock Scanner finds nothing. A tier I Scanner lists the kinds of ore within {@link #RADIUS}[1] blocks and how much of each; tier II
 * reaches {@link #RADIUS}[2] blocks and also gives the world's richness and the way to the nearest vein of each kind, and marks them;
 * tier III reaches {@link #RADIUS}[3] blocks and also finds ancient debris. It reads only chunks that are already generated.
 */
public final class OreSurvey
{
    /** How far the Scanner of each tier looks, in blocks (tier 4, the creative one, looks as far as tier III). */
    public static final int[] RADIUS = {0, 32, 48, 64};

    /** The kinds of ore, in the order reported. */
    private static final Map<String, Predicate<BlockState>> KINDS = new LinkedHashMap<>();

    static
    {
        KINDS.put("coal", s -> s.is(Blocks.COAL_ORE) || s.is(Blocks.DEEPSLATE_COAL_ORE));
        KINDS.put("iron", s -> s.is(BlockTags.IRON_ORES));
        KINDS.put("copper", s -> s.is(BlockTags.COPPER_ORES));
        KINDS.put("gold", s -> s.is(BlockTags.GOLD_ORES));
        KINDS.put("redstone", s -> s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE));
        KINDS.put("lapis", s -> s.is(Blocks.LAPIS_ORE) || s.is(Blocks.DEEPSLATE_LAPIS_ORE));
        KINDS.put("diamond", s -> s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE));
        KINDS.put("emerald", s -> s.is(Blocks.EMERALD_ORE) || s.is(Blocks.DEEPSLATE_EMERALD_ORE));
        KINDS.put("ancient debris", s -> s.is(Blocks.ANCIENT_DEBRIS));
    }

    /** What one scan found of one kind. */
    public record Find(String kind, int count, BlockPos nearest)
    {}

    /** The ore of each kind within {@code radius} of {@code center} in chunks that are loaded, nearest vein included. */
    public static List<Find> scan(ServerLevel level, BlockPos center, int radius, boolean debris)
    {
        var counts = new LinkedHashMap<String, Integer>();
        var nearest = new LinkedHashMap<String, BlockPos>();
        var cursor = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++)
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++)
            {
                if (!level.hasChunkAt(cursor.set(x, center.getY(), z)))
                    continue;
                for (int y = Math.max(level.getMinY(), center.getY() - radius); y <= Math.min(level.getMaxY(), center.getY() + radius); y++)
                {
                    var state = level.getBlockState(cursor.set(x, y, z));
                    if (state.isAir())
                        continue;
                    for (var kind : KINDS.entrySet())
                        if ((debris || !kind.getKey().equals("ancient debris")) && kind.getValue().test(state))
                        {
                            counts.merge(kind.getKey(), 1, Integer::sum);
                            var held = nearest.get(kind.getKey());
                            if (held == null || held.distSqr(center) > cursor.distSqr(center))
                                nearest.put(kind.getKey(), cursor.immutable());
                        }
                }
            }
        var out = new ArrayList<Find>();
        for (var kind : KINDS.keySet())
            if (counts.containsKey(kind))
                out.add(new Find(kind, counts.get(kind), nearest.get(kind)));
        return out;
    }

    /** The lines a Scanner of this tier reports for a surface of this richness band, or one line saying why not. */
    public static List<String> report(ServerLevel level, BlockPos center, int tier, int band)
    {
        int t = Math.min(tier, 3);
        if (t < 1)
            return List.of("This Scanner cannot find ore: install a System Upgrade to read the ground");
        int radius = RADIUS[t];
        var finds = scan(level, center, radius, t >= 3);
        var lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT, "Ore survey, tier %s, within %d blocks", roman(t), radius));
        if (t >= 2)
            lines.add(String.format(Locale.ROOT, "Richness %d: ore x%.1f, veins up to %d blocks nearer the surface", band, OreRichness.COUNT[Math.clamp(band, 0, 3)], OreRichness.LIFT[Math.clamp(band, 0, 3)]));
        if (finds.isEmpty())
            lines.add("No ore in the generated ground nearby");
        for (var find : finds)
        {
            if (t < 2)
                lines.add(String.format(Locale.ROOT, "%s: %d %s", find.kind(), find.count(), find.count() == 1 ? "block" : "blocks"));
            else
            {
                var d = find.nearest().subtract(center);
                boolean straight = Math.abs(d.getX()) < 2 && Math.abs(d.getZ()) < 2;
                lines.add(String.format(Locale.ROOT, "%s: %d %s, nearest %d blocks %s%s", find.kind(), find.count(), find.count() == 1 ? "block" : "blocks", (int) Math.round(Math.sqrt(find.nearest().distSqr(center))), heading(d), straight || d.getY() == 0 ? "" : d.getY() > 0 ? " and " + d.getY() + " up" : " and " + -d.getY() + " down"));
            }
        }
        return lines;
    }

    /** What sneak-using a Scanner does: the survey of the ground under it, if it has power and stands on a surface. */
    public static void use(ServerPlayer player, BlockPos scanner)
    {
        var level = player.level();
        var entity = level.getBlockEntity(scanner);
        if (!(entity instanceof MachineBlockEntity machine))
            return;
        if (PlanetSurfaces.address(level) == null || PlanetSurfaces.vacuum(level) && level.dimension().equals(com.nullandkale.nullspace.gameplay.travel.TravelService.SPACE))
        {
            player.sendSystemMessage(Component.literal("Survey: land on a planet and use the Scanner there"));
            return;
        }
        var ship = WorldStore.get(level.getServer()).at(level, scanner);
        if (ship != null && ShipSystems.enabled(ship) && ShipSystems.power(level, ship, PowerBus.System.CAMERAS) < 1)
        {
            player.sendSystemMessage(Component.literal("Survey: the Scanner has no power"));
            return;
        }
        int tier = machine.tier();
        for (var line : report(level, scanner, tier, PlanetDimensions.band(level)))
            player.sendSystemMessage(Component.literal(line));
        if (tier >= 2)
            for (var find : scan(level, scanner, RADIUS[Math.min(tier, 3)], tier >= 3))
                level.sendParticles(ParticleTypes.END_ROD, find.nearest().getX() + .5, find.nearest().getY() + .5, find.nearest().getZ() + .5, 24, .4, .4, .4, .02);
    }

    private static String roman(int tier)
    {
        return tier == 1 ? "I" : tier == 2 ? "II" : "III";
    }

    private static String heading(BlockPos d)
    {
        if (Math.abs(d.getX()) < 2 && Math.abs(d.getZ()) < 2)
            return d.getY() < 0 ? "below" : "above";
        return Math.abs(d.getX()) >= Math.abs(d.getZ()) ? d.getX() > 0 ? "east" : "west" : d.getZ() > 0 ? "south" : "north";
    }

    private OreSurvey()
    {
    }
}
