package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;

/**
 * Reproducible review commands for generated stations. They place the real walkable structure from
 * the same generator the journeys, fixed sites and construction will use, on reserved clear space with
 * each berth's full approach box above the terrain. They add no population entries and no loot.
 *
 * <pre>
 * /nullspace shipyard stations preview  &lt;maker&gt; &lt;purpose&gt; &lt;size&gt; &lt;condition&gt; [seed]
 * /nullspace shipyard stations compare  &lt;purpose&gt; &lt;size&gt; &lt;condition&gt; [seed]       the three makers, one seed
 * /nullspace shipyard stations variants &lt;maker&gt; &lt;purpose&gt; &lt;size&gt; &lt;condition&gt; [seed]  four seeds of one brief
 * </pre>
 */
public final class StationPreview
{
    /** Space kept clear around every station for berth approaches, and between neighbours. */
    private static final int MARGIN = 34 + 4, ROW = 320;

    private interface Specs
    {
        List<StationSpec> specs(CommandContext<CommandSourceStack> ctx);
    }

    public static void initialize()
    {
        Specs one = ctx -> List.of(spec(ctx, StringArgumentType.getString(ctx, "maker"), seed(ctx)));
        Specs three = ctx ->
        {
            long seed = seed(ctx);
            var out = new ArrayList<StationSpec>();
            for (var maker : Maker.values())
                out.add(spec(ctx, maker.name().toLowerCase(Locale.ROOT), seed));
            return out;
        };
        Specs four = ctx ->
        {
            long seed = seed(ctx);
            var out = new ArrayList<StationSpec>();
            for (int k = 0; k < 4; k++)
                out.add(spec(ctx, StringArgumentType.getString(ctx, "maker"), seed + k * 1_000_003L));
            return out;
        };
        var preview = Commands.literal("preview").then(makerArgument().then(brief(one)));
        var compare = Commands.literal("compare").then(brief(three));
        var variants = Commands.literal("variants").then(makerArgument().then(brief(four)));
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("stations").then(preview).then(compare).then(variants)))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> makerArgument()
    {
        return Commands.argument("maker", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(Maker.values()), builder));
    }

    /** purpose, size, condition and an optional seed, running {@code specs}. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> brief(Specs specs)
    {
        var seeded = Commands.argument("seed", LongArgumentType.longArg()).executes(ctx -> run(ctx, specs));
        var condition = Commands.argument("condition", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Condition.values()), builder)).executes(ctx -> run(ctx, specs)).then(seeded);
        var size = Commands.argument("size", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Size.values()), builder)).then(condition);
        return Commands.argument("purpose", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Purpose.values()), builder)).then(size);
    }

    private static long seed(CommandContext<CommandSourceStack> ctx)
    {
        try
        {
            return LongArgumentType.getLong(ctx, "seed");
        }
        catch (IllegalArgumentException none)
        {
            return ctx.getSource().getServer().overworld().getRandom().nextLong();
        }
    }

    private static StationSpec spec(CommandContext<CommandSourceStack> ctx, String maker, long seed)
    {
        return StationSpec.parse(maker, StringArgumentType.getString(ctx, "purpose"), StringArgumentType.getString(ctx, "size"), StringArgumentType.getString(ctx, "condition"), seed);
    }

    private static List<String> ids(Enum<?>[] values)
    {
        return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Specs specs) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var player = ctx.getSource().getPlayerOrException();
        try
        {
            var all = specs.specs(ctx);
            var placed = build(player.level().getServer(), all);
            var first = placed.getFirst();
            var arrival = first.plan().points().stream().filter(p -> p.name().equals("room") && p.argument().startsWith(first.arrival() + ":")).findFirst().orElseThrow().pos();
            var at = first.origin().offset(arrival);
            player.teleportTo(player.level().getServer().overworld(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), -90, 0, true);
            for (int i = 0; i < placed.size(); i++)
            {
                var p = placed.get(i);
                player.sendSystemMessage(Component.literal((i + 1) + ". " + p.spec().description() + ": " + p.describe() + " at " + p.origin().toShortString()));
            }
            return 1;
        }
        catch (RuntimeException | java.io.IOException failure)
        {
            player.sendSystemMessage(Component.literal("Could not preview station: " + ShipMessages.failure(failure)));
            return 0;
        }
    }

    /** A station placed in the world: where, and the plan it was built from. */
    public record Placed(StationSpec spec, StationPlan planned, StationBlueprint.Plan plan, BlockPos origin)
    {
        public String arrival()
        {
            return planned.arrival();
        }

        public String describe()
        {
            return planned.rooms().size() + " rooms, " + planned.levels() + " levels, " + planned.berths().size() + " berths, " + planned.width() + " x " + planned.height() + " x " + planned.depth();
        }
    }

    /** Generates and places each station in a clear row; every plan is generated before any space is reserved or any block placed. */
    public static List<Placed> build(MinecraftServer server, List<StationSpec> specs) throws java.io.IOException
    {
        var plans = new ArrayList<StationPlan>();
        var blueprints = new ArrayList<StationBlueprint.Plan>();
        for (var spec : specs)
        {
            var planned = StationPlanner.generate(spec);
            plans.add(planned);
            blueprints.add(StationStructure.build(planned));
        }
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-preview.nbt");
        int count = Files.exists(marker) ? Math.addExact(WorldStore.read(marker).getIntOr("count", 0), 1) : 1;
        var level = server.overworld();
        int z = Math.addExact(2048, Math.multiplyExact(count - 1, ROW)), x = 3200 + MARGIN;
        var origins = new ArrayList<BlockPos>();
        for (int i = 0; i < plans.size(); i++)
        {
            var planned = plans.get(i);
            int w = planned.width(), d = planned.depth();
            // Roofs rise above the planned rooms: reserve what the blocks actually occupy.
            int top = blueprints.get(i).blocks().keySet().stream().mapToInt(at -> at.getY()).max().orElse(planned.height()) + 1;
            // Height queries on an unloaded region can return the minimum build height.
            for (int cx = (x - MARGIN) >> 4; cx <= (x + w + MARGIN) >> 4; cx++)
                for (int cz = (z - MARGIN) >> 4; cz <= (z + d + MARGIN) >> 4; cz++)
                    level.getChunk(cx, cz);
            int surface = level.getMinY();
            for (int dx = -MARGIN; dx < w + MARGIN; dx += 2)
                for (int dz = -MARGIN; dz < d + MARGIN; dz += 2)
                    surface = Math.max(surface, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
            // Berth boxes reach 17 blocks below a port: keep them clear of the ground.
            int y = surface + 20;
            if (y + top + 20 >= level.getMaxY())
                throw new IllegalArgumentException("No room above terrain for this station preview");
            var origin = new BlockPos(x, y, z);
            var corner = origin.offset(w - 1, top - 1, d - 1);
            if (!level.getWorldBorder().isWithinBounds(origin) || !level.getWorldBorder().isWithinBounds(corner) || !level.getEntities(null, new net.minecraft.world.phys.AABB(origin).expandTowards(w - 1, top - 1, d - 1)).isEmpty())
                throw new IllegalArgumentException("Station preview space is outside the border or occupied");
            for (var at : BlockPos.betweenClosed(origin, corner))
                if (!level.getBlockState(at).isAir())
                    throw new IllegalArgumentException("Station preview space is occupied at " + at.toShortString());
            origins.add(origin);
            x += w + 2 * MARGIN;
        }
        var placed = new ArrayList<Placed>();
        var list = new ListTag();
        for (int i = 0; i < plans.size(); i++)
        {
            var origin = origins.get(i);
            var base = origin;
            blueprints.get(i).blocks().forEach((at, state) -> level.setBlock(base.offset(at), state, 18));
            // Review markers only: red posts at the vertical edges of each berth's reserved approach box.
            for (var berth : plans.get(i).berths())
            {
                var box = berth.approach();
                for (int y = box.y0(); y <= box.y1(); y++)
                    for (int px : new int[]{box.x0(), box.x1()})
                        for (int pz : new int[]{box.z0(), box.z1()})
                            level.setBlock(base.offset(px, y, pz), net.minecraft.world.level.block.Blocks.CONCRETE.red().defaultBlockState(), 18);
            }
            placed.add(new Placed(specs.get(i), plans.get(i), blueprints.get(i), origin));
            var entry = specs.get(i).save();
            entry.putLong("origin", origin.asLong());
            list.add(entry);
        }
        var data = new CompoundTag();
        data.putInt("count", count);
        data.put("stationSpec", specs.getFirst().save());
        data.putLong("origin", origins.getFirst().asLong());
        data.put("stations", list);
        WorldStore.write(marker, data);
        return placed;
    }

    private StationPreview()
    {
    }
}
