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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;

/** Reproducible preview entry point; this batch uses the existing compact Waypoint builder. */
public final class StationPreview
{
    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("stations").then(Commands.literal("preview").then(Commands.argument("maker", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(Maker.values()), builder)).then(Commands.argument("purpose", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Purpose.values()), builder)).then(Commands.argument("size", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Size.values()), builder)).then(Commands.argument("condition", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(StationSpec.Condition.values()), builder)).executes(ctx -> run(ctx, java.util.concurrent.ThreadLocalRandom.current().nextLong())).then(Commands.argument("seed", LongArgumentType.longArg()).executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "seed")))))))))))));
    }

    private static List<String> ids(Enum<?>[] values)
    {
        return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, long seed) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var player = ctx.getSource().getPlayerOrException();
        try
        {
            var spec = StationSpec.parse(StringArgumentType.getString(ctx, "maker"), StringArgumentType.getString(ctx, "purpose"), StringArgumentType.getString(ctx, "size"), StringArgumentType.getString(ctx, "condition"), seed);
            var origin = build(player.level().getServer(), spec);
            player.teleportTo(player.level().getServer().overworld(), origin.getX() + 5.5, origin.getY() + 1, origin.getZ() + 20.5, Set.of(), -90, 0, true);
            player.sendSystemMessage(Component.literal("Existing Waypoint layout preview: " + spec.description() + ". Manufacturer layout methods and the damage pass are not part of this preview yet."));
            return 1;
        }
        catch (RuntimeException | java.io.IOException failure)
        {
            player.sendSystemMessage(Component.literal("Could not preview station: " + ShipMessages.failure(failure)));
            return 0;
        }
    }

    public static BlockPos build(MinecraftServer server, StationSpec spec) throws java.io.IOException
    {
        var plan = ExistingStationPlans.build(spec); // Validate unsupported briefs before reserving or placing anything.
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-preview.nbt");
        int count = Files.exists(marker) ? Math.addExact(WorldStore.read(marker).getIntOr("count", 0), 1) : 1;
        var level = server.overworld();
        int x = 3200, z = Math.addExact(2048, Math.multiplyExact(count - 1, 64));
        // Height queries on an unloaded region can return the minimum build height.
        for (int cx = x >> 4; cx <= (x + 47) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + 47) >> 4; cz++)
                level.getChunk(cx, cz);
        int y = level.getMinY();
        for (int dx = 0; dx < 48; dx++)
            for (int dz = 0; dz < 48; dz++)
                y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
        y += 4;
        if (y + 32 >= level.getMaxY())
            throw new IllegalArgumentException("No room above terrain for this station preview");
        var origin = new BlockPos(x, y, z);
        var area = new net.minecraft.world.phys.AABB(origin).expandTowards(47, 31, 47);
        if (!level.getWorldBorder().isWithinBounds(origin) || !level.getWorldBorder().isWithinBounds(origin.offset(47, 31, 47)) || !level.getEntities(null, area).isEmpty())
            throw new IllegalArgumentException("Station preview space is outside the border or occupied");
        for (var at : BlockPos.betweenClosed(origin, origin.offset(47, 31, 47)))
            if (!level.getBlockState(at).isAir())
                throw new IllegalArgumentException("Station preview space is occupied at " + at.toShortString());
        for (int dx = 0; dx < 48; dx++)
            for (int dz = 0; dz < 48; dz++)
                level.setBlock(origin.offset(dx, 0, dz), Blocks.CONCRETE.lightGray().defaultBlockState(), 18);
        var base = origin.offset(8, -1, 8);
        plan.blocks().forEach((at, state) -> level.setBlock(base.offset(at), state, 18));
        var data = new CompoundTag();
        data.putInt("count", count);
        data.put("stationSpec", spec.save());
        WorldStore.write(marker, data);
        return origin;
    }

    private StationPreview()
    {
    }
}
