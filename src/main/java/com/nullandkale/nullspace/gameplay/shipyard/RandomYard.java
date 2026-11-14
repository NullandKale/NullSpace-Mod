package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.shipgen.Spec;
import com.nullandkale.nullspace.worldgen.stations.ShipSpec;
import com.nullandkale.nullspace.worldgen.stations.ShipStructure;

import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.brigadier.arguments.LongArgumentType;

/**
 * A big walk-through gallery of randomly generated ships: one row per size, one column
 * per role, plus a wild card. Every ship stands on its own registered pad (the ship's own ramp climbs to its
 * docking door). Each visit builds a new gallery at a new site, so earlier ones are never touched.
 */
public final class RandomYard
{
    public static final int COLUMNS = 4, PITCH = 40, MARGIN = 6;
    public static final int ROWS = ShipSpec.Size.values().length;

    public record Gallery(BlockPos origin, int number)
    {
        public BlockPos pad(int index)
        {
            return origin.offset(MARGIN + index % COLUMNS * PITCH, 0, MARGIN + index / COLUMNS * PITCH);
        }

        public BlockPos spawn()
        {
            return origin.offset(MARGIN - 3, 1, MARGIN - 3);
        }
    }

    public static void initialize()
    {
        var seeded = Commands.argument("seed", LongArgumentType.longArg()).executes(ctx -> visit(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "seed")));
        var random = Commands.literal("random").executes(ctx -> visit(ctx.getSource().getPlayerOrException(), System.nanoTime())).then(seeded);
        var shipyard = Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(random);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(shipyard)));
    }

    private static int visit(net.minecraft.server.level.ServerPlayer player, long seed)
    {
        if (TransferLocks.busy(player))
            throw new IllegalArgumentException("Finish travelling before visiting the gallery.");
        try
        {
            var server = player.level().getServer();
            var gallery = build(server, seed);
            var at = gallery.spawn();
            player.teleportTo(server.overworld(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), -45, 10, true);
            FieldGuide.supply(player);
            player.sendSystemMessage(Component.literal("""
                Random ship gallery %d: %d generated ships, one row per size (small, medium, large), \
                one column per role (scout, freighter, explorer), plus a wild card. Seed %d. Walk up each ramp to the docking \
                door. Run the command again for a new gallery.""".formatted(gallery.number(), ROWS * COLUMNS, seed)));
            return 1;
        }
        catch (Exception error)
        {
            player.sendSystemMessage(Component.literal("Could not build the gallery: " + ShipMessages.failure(error)));
            return 0;
        }
    }

    /** The spec for a gallery slot: the row picks the size, the column the role; the rest is random. */
    public static ShipSpec spec(int index, Random random)
    {
        int row = index / COLUMNS, column = index % COLUMNS;
        var size = ShipSpec.Size.values()[row];
        var roles = Spec.Role.values();
        var role = column < roles.length ? roles[column] : roles[random.nextInt(roles.length)];
        var maker = Spec.Maker.values()[random.nextInt(Spec.Maker.values().length)];
        return new ShipSpec(maker, size, role, random.nextInt(1_000_000));
    }

    public static Gallery build(MinecraftServer server, long seed) throws java.io.IOException
    {
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-random-gallery.nbt");
        int number = Files.exists(marker) ? WorldStore.read(marker).getIntOr("count", 0) + 1 : 1;
        var level = server.overworld();
        int width = MARGIN * 2 + COLUMNS * PITCH, depth = MARGIN * 2 + ROWS * PITCH;
        int x = 1024, z = 2048 + (number - 1) * (depth + 64);
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
        // Reserve the site before building: an interrupted build is never rebuilt over.
        WorldStore.write(marker, data);
        var gallery = new Gallery(new BlockPos(x, y, z), number);
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                level.setBlock(gallery.origin().offset(dx, 0, dz), (dx % 8 == 0 && dz % 8 == 0 ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()).defaultBlockState(), 18);
        var random = new Random(seed);
        for (int index = 0; index < ROWS * COLUMNS; index++)
            place(level, gallery, index, spec(index, random));
        server.saveEverything(true, true, true);
        return gallery;
    }

    private static void place(ServerLevel level, Gallery gallery, int index, ShipSpec spec) throws java.io.IOException
    {
        var base = gallery.pad(index);
        var ship = ShipStructure.build(spec);
        String title = spec.size().name().charAt(0) + spec.size().name().substring(1).toLowerCase(Locale.ROOT);
        String padName = "Gal" + gallery.number() + " " + (index + 1) + " " + title;
        ShipYard.buildPad(level, base, 32, 32, padName.length() > 32 ? padName.substring(0, 32).strip() : padName);
        var pad = new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
        var at = new BlockPos(pad.minX() + (32 - ship.width()) / 2, pad.y() + 1, pad.minZ() + (32 - ship.depth()) / 2);
        var blocks = new LinkedHashMap<>(ship.blueprint().blocks());
        BlueprintOrientation.fix(blocks);
        blocks.forEach((pos, state) -> level.setBlock(at.offset(pos), state, 18));
        RoomEntities.spawn(level, at, ship.blueprint().points());
        blocks.forEach((pos, state) ->
        {
            if (state.is(ModBlocks.GENERATOR))
                FuelSystem.provision(level, at.offset(pos), 64);
        });
        var tag = ShipTopology.resolve(level, at.offset(ship.helm()));
        tag.putString("name", title + " " + spec.role().name().toLowerCase(Locale.ROOT));
        // Every room has its own Air Supply, so the doors are served before the air.
        tag.putBoolean("generatedRooms", true);
        SystemsUpgrade.upgrade(level, tag);
        tag.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, com.nullandkale.nullspace.model.air.SpatialAir.SUPPLY_POWER, 1, 3, 2, 0});
        ShipGallery.label(level, base.offset(1, 1, -3), DyeColor.GREEN, "%02d %s".formatted(index + 1, title), spec.role().name() + " / " + spec.maker().label, ship.width() + " x " + ship.height() + " x " + ship.depth(), "seed " + spec.seed());
    }

    private RandomYard()
    {
    }
}
