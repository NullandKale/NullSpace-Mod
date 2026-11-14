package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import com.nullandkale.nullspace.worldgen.stations.StationPieces;

import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A gallery of every station piece for auditing, plus the Waypoint and the Manufacturer Exchange.
 * The pieces stand in two rows: few windows, then many. Each has a walk-in opening in its south
 * wall and a sign. Every visit builds a new gallery.
 */
public final class StationGallery
{
    public static final int PITCH = 22, MARGIN = 8;

    public static void initialize()
    {
        StationPreview.initialize();
        RoomGallery.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("stations").executes(ctx ->
        {
            var player = ctx.getSource().getPlayerOrException();
            try
            {
                var server = player.level().getServer();
                var origin = build(server);
                player.teleportTo(server.overworld(), origin.getX() + MARGIN + 8.5, origin.getY() + 1, origin.getZ() + MARGIN + 19.5, Set.of(), 0, 10, true);
                FieldGuide.supply(player);
                player.sendSystemMessage(Component.literal("Station gallery: the pieces in two rows (few windows, then many), each with a walk-in opening in its south wall, then the Waypoint and the Manufacturer Exchange behind them."));
                return 1;
            }
            catch (Exception error)
            {
                player.sendSystemMessage(Component.literal("Could not build the station gallery: " + ShipMessages.failure(error)));
                return 0;
            }
        })))));
    }

    /** The pieces in catalog order, as {@code (family, windows)}: row 0 few windows, row 1 many. */
    public static List<StationPieces.Definition> row(int windows)
    {
        return StationPieces.ALL.stream().filter(d -> d.windows() == windows).toList();
    }

    public static BlockPos build(MinecraftServer server) throws java.io.IOException
    {
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-gallery.nbt");
        int number = Files.exists(marker) ? WorldStore.read(marker).getIntOr("count", 0) + 1 : 1;
        var level = server.overworld();
        int columns = row(1).size();
        int width = MARGIN * 2 + columns * PITCH, depth = MARGIN * 2 + 4 * PITCH;
        int x = 1024 + 1200, z = 2048 + (number - 1) * (depth + 64);
        for (int cx = x >> 4; cx <= (x + width) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + depth) >> 4; cz++)
                level.getChunk(cx, cz);
        int y = level.getMinY();
        for (int dx = 0; dx < width; dx += 2)
            for (int dz = 0; dz < depth; dz += 2)
                y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
        y += 4;
        if (y + 40 >= level.getMaxY())
            throw new IllegalArgumentException("No room above the terrain for a gallery.");
        var data = new CompoundTag();
        data.putInt("count", number);
        WorldStore.write(marker, data);
        var origin = new BlockPos(x, y, z);
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                level.setBlock(origin.offset(dx, 0, dz), (dx % 8 == 0 && dz % 8 == 0 ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()).defaultBlockState(), 18);
        for (int windows = 1; windows <= 2; windows++)
        {
            var pieces = row(windows);
            for (int column = 0; column < pieces.size(); column++)
            {
                var piece = pieces.get(column);
                // Sunk one block, so the tile's lower floor is flush with the plate and its doorway is at ground level.
                var at = origin.offset(MARGIN + column * PITCH, -1, MARGIN + (windows - 1) * PITCH);
                var plan = StationBlueprint.build(new StationLayout.Layout(List.of(new StationLayout.Room("piece", piece.id(), 0, 0, 0)), List.of()));
                place(level, at, plan.blocks());
                // A walk-in opening in the south wall, the first doorway slot on the lower story.
                for (int up = 0; up < 2; up++)
                    level.setBlock(at.offset(slot0(), 2 + up, 15), Blocks.AIR.defaultBlockState(), 18);
                ShipGallery.label(level, at.offset(slot0(), 2, 17), DyeColor.GREEN, piece.name(), piece.windows() == 1 ? "few windows" : "many windows", piece.kit().name().toLowerCase(Locale.ROOT), piece.id());
            }
        }
        server.saveEverything(true, true, true);
        return origin;
    }

    private static int slot0()
    {
        return com.nullandkale.nullspace.worldgen.shipgen.StationGenerator.SLOTS[0];
    }

    private static void place(net.minecraft.server.level.ServerLevel level, BlockPos at, Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks)
    {
        blocks.forEach((pos, state) -> level.setBlock(at.offset(pos), state, 18));
    }

    private StationGallery()
    {
    }
}
