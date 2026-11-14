package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.shipgen.*;

import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

/**
 * The room galleries. {@code /nullspace shipyard rooms} builds both: the station rooms ({@link StationRoomGallery}), then this gallery of the ship room kits.
 * The ship gallery is every room kit for auditing: one row per use, one column per tier (the piece size)
 * and window level. Each room is built on its own behind a short corridor with an open doorway, so
 * you can walk in and judge the room by itself. Every visit builds a new gallery.
 */
public final class RoomGallery
{
    public static final int PITCH_X = 24, PITCH_Z = 34, MARGIN = 8;

    /** Uses shown: the destination rooms. The round commons and the stairwell are built into the shapes, not as side rooms. */
    public static final List<RoomUse> USES = Arrays.stream(RoomUse.values()).filter(u -> u.destination() && u != RoomUse.HUB).toList();

    public static final List<int[]> COLUMNS = new ArrayList<>();

    static
    {
        for (var tier : Spec.Tier.values())
            for (int windows : new int[]{1, 2})
                COLUMNS.add(new int[]{tier.ordinal(), windows});
    }

    public static void initialize()
    {
        var station = Commands.literal("station").executes(ctx -> run(ctx, true, false, null)).then(Commands.argument("maker", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Spec.Maker.values()).map(m -> m.name().toLowerCase(Locale.ROOT)).toList(), builder)).executes(ctx -> run(ctx, true, false, StringArgumentType.getString(ctx, "maker"))));
        var ship = Commands.literal("ship").executes(ctx -> run(ctx, false, true, null));
        var rooms = Commands.literal("rooms").executes(ctx -> run(ctx, true, true, null)).then(station).then(ship);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(rooms))));
    }

    /**
     * Builds the station room gallery, the ship room gallery or both, and stands the player at the
     * first. {@code maker} narrows the station rooms to one maker.
     */
    private static int run(CommandContext<CommandSourceStack> ctx, boolean stations, boolean ships, String maker) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var player = ctx.getSource().getPlayerOrException();
        try
        {
            var server = player.level().getServer();
            var makers = new ArrayList<Spec.Maker>();
            for (var m : Spec.Maker.values())
                if (maker == null || maker.equalsIgnoreCase("all") || m.name().equalsIgnoreCase(maker))
                    makers.add(m);
            if (stations && makers.isEmpty())
                throw new IllegalArgumentException("Unknown maker: " + maker);
            BlockPos stand = null;
            if (stations)
            {
                var placed = StationRoomGallery.build(server, makers);
                stand = placed.start();
                player.sendSystemMessage(Component.literal("Station room gallery: %d rooms (small and large, one row of six per three uses, a section per maker) with %d frames, stands, paintings and animals, starting at %s. Each room's sign is by its door, and every door opens on a pressure plate.".formatted(StationRoomGallery.rooms(makers), placed.entities(), stand.toShortString())));
            }
            if (ships)
            {
                var origin = build(server);
                if (stand == null)
                    stand = origin.offset(MARGIN + 8, 1, 1);
                player.sendSystemMessage(Component.literal("""
                    Ship room gallery: %d rooms, one row per use going south from %s. Columns going east: small, medium and                     large ship pieces, each with few and then many windows. A blank cell means the room does not fit that size.""".formatted(USES.size(), origin.toShortString())));
                FieldGuide.supply(player);
            }
            player.teleportTo(server.overworld(), stand.getX() + .5, stand.getY(), stand.getZ() + .5, Set.of(), 0, 10, true);
            return 1;
        }
        catch (Exception error)
        {
            player.sendSystemMessage(Component.literal("Could not build the room gallery: " + ShipMessages.failure(error)));
            return 0;
        }
    }

    public static BlockPos build(MinecraftServer server) throws java.io.IOException
    {
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-room-gallery.nbt");
        int number = Files.exists(marker) ? WorldStore.read(marker).getIntOr("count", 0) + 1 : 1;
        var level = server.overworld();
        int width = MARGIN * 2 + COLUMNS.size() * PITCH_X, depth = MARGIN * 2 + USES.size() * PITCH_Z;
        int x = 1024 + 400, z = 2048 + (number - 1) * (depth + 64);
        for (int cx = x >> 4; cx <= (x + width) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + depth) >> 4; cz++)
                level.getChunk(cx, cz);
        int y = level.getMinY();
        for (int dx = 0; dx < width; dx += 2)
            for (int dz = 0; dz < depth; dz += 2)
                y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
        y += 4;
        if (y + 20 >= level.getMaxY())
            throw new IllegalArgumentException("No room above the terrain for a gallery.");
        var data = new CompoundTag();
        data.putInt("count", number);
        WorldStore.write(marker, data);
        var origin = new BlockPos(x, y, z);
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                level.setBlock(origin.offset(dx, 0, dz), (dx % 8 == 0 && dz % 8 == 0 ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()).defaultBlockState(), 18);
        for (int row = 0; row < USES.size(); row++)
            for (int column = 0; column < COLUMNS.size(); column++)
            {
                var use = USES.get(row);
                var tier = Spec.Tier.values()[COLUMNS.get(column)[0]];
                int windows = COLUMNS.get(column)[1];
                var ship = ShipGenerator.room(use, tier, windows);
                if (ship == null)
                    continue;
                var at = origin.offset(MARGIN + column * PITCH_X, 1, MARGIN + row * PITCH_Z);
                var blocks = new LinkedHashMap<>(ShipBlocks.blocks(ship));
                BlueprintOrientation.fix(blocks);
                blocks.forEach((pos, state) -> level.setBlock(at.offset(pos), state, 18));
                // The doorway is on the north face, at the room's centre line.
                ShipGallery.label(level, at.offset(ship.width() / 2 + 3, 0, -1), DyeColor.GREEN, use.label, tier.name().charAt(0) + tier.name().substring(1).toLowerCase(Locale.ROOT) + ", " + (windows == 1 ? "few windows" : "many windows"), tier.length + " x " + tier.cell + " x " + tier.height, use.description.length() > 30 ? use.description.substring(0, 30) : use.description);
            }
        server.saveEverything(true, true, true);
        return origin;
    }

    private RoomGallery()
    {
    }
}
