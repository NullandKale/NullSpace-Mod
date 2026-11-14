package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

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

/** Opt-in showroom and working delivery pads. Reserved once; visits never rebuild player edits. */
public final class ShipYard
{
    public static final int WIDTH = 118, DEPTH = 44 + ((ShipBlueprints.PRESETS.size() + 2) / 3) * 38;

    public record Yard(BlockPos origin, String prefix)
    {
        public BlockPos pad(int index)
        {
            if (index < 0 || index >= ShipBlueprints.PRESETS.size())
                throw new IllegalArgumentException("Unknown display bay");
            return origin.offset(3 + index % 3 * 38, 0, 43 + index / 3 * 38);
        }

        public BlockPos deliveryBeacon(int index)
        {
            return delivery(index).offset(-1, 1, 1);
        }

        public BlockPos delivery(int index)
        {
            if (index < 0 || index >= 3)
                throw new IllegalArgumentException("Choose delivery bay 0-2");
            return origin.offset(3 + index * 38, 0, 5);
        }

        public BlockPos spawn()
        {
            return origin.offset(1, 1, 1);
        }
    }

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).executes(ctx ->
        {
            var player = ctx.getSource().getPlayerOrException();
            if (TransferLocks.busy(player))
                throw new IllegalArgumentException("Finish travelling before visiting the shipyard.");
            try
            {
                var yard = ensure(player.level().getServer());
                var at = yard.spawn();
                player.teleportTo(player.level().getServer().overworld(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), 0, 0, true);
                FieldGuide.supply(player);
                player.sendSystemMessage(Component.literal("Shipyard: " + ShipBlueprints.PRESETS.size() + " display ships behind the delivery" + " pads. Right-click a copper Ship Beacon, select" + " a design, then Deliver ship. Purchases are" + " FREE; keep delivery pads clear."));
                return 1;
            }
            catch (Exception error)
            {
                player.sendSystemMessage(Component.literal("Could not prepare the shipyard: " + ShipMessages.failure(error)));
                return 0;
            }
        }))));
    }

    public static Yard ensure(MinecraftServer server) throws java.io.IOException
    {
        // A new reserved gallery leaves the earlier galleries and all player edits intact.
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-shipyard-v4.nbt");
        if (Files.exists(marker))
        {
            var saved = WorldStore.read(marker);
            if (saved.getIntOr("version", 0) != 1 || !saved.getBooleanOr("complete", false))
                throw new IllegalArgumentException("Shipyard generation was interrupted. Its reserved site is preserved; existing blocks" + " will not be rebuilt.");
            return new Yard(WorldStore.pos(saved, "origin"), saved.getStringOr("prefix", "Yard"));
        }
        var level = server.overworld();
        Yard yard = null;
        // Start outside the existing labs, and choose height above every existing block/fluid.
        for (int attempt = 0; attempt < 8 && yard == null; attempt++)
        {
            int x = 2400 + attempt * 160, z = 512;
            var proposed = new PadScanner.Pad(x, x + WIDTH - 1, 0, z, z + DEPTH - 1);
            if (PadRegistry.read(server).stream().anyMatch(p -> p.dimension().equals(level.dimension().identifier().toString()) && proposed.minX() - 1 <= p.bounds().maxX() + 1 && proposed.maxX() + 1 >= p.bounds().minX() - 1 && proposed.minZ() - 1 <= p.bounds().maxZ() + 1 && proposed.maxZ() + 1 >= p.bounds().minZ() - 1))
                continue;
            for (int cx = x >> 4; cx <= (x + WIDTH - 1) >> 4; cx++)
                for (int cz = z >> 4; cz <= (z + DEPTH - 1) >> 4; cz++)
                    level.getChunk(cx, cz);
            int y = level.getMinY();
            for (int dx = 0; dx < WIDTH; dx++)
                for (int dz = 0; dz < DEPTH; dz++)
                    y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
            y += 4;
            if (y + 33 >= level.getMaxY())
                continue;
            if (TransferLocks.areaBusy(level, new HullScan.Bounds(new BlockPos(x, y, z), new BlockPos(x + WIDTH - 1, y + 33, z + DEPTH - 1))))
                continue;
            if (!level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(x, y, z, x + WIDTH, y + 34, z + DEPTH), e -> e.isAlive()).isEmpty())
                continue;
            String prefix = "Yard";
            for (int suffix = 2; conflicts(server, prefix); suffix++)
                prefix = "Yard" + suffix;
            yard = new Yard(new BlockPos(x, y, z), prefix);
        }
        if (yard == null)
            throw new IllegalArgumentException("No empty shipyard site was found in the bounded search area.");
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putLong("origin", yard.origin().asLong());
        saved.putString("prefix", yard.prefix());
        saved.putBoolean("complete", false);
        WorldStore.write(marker, saved);
        for (int x = 0; x < WIDTH; x++)
            for (int z = 0; z < DEPTH; z++)
                level.setBlock(yard.origin().offset(x, 0, z), (x % 8 == 0 && z % 8 == 0 ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()).defaultBlockState(), 18);
        // Three empty delivery pads face the individually registered catalog ships.
        for (int index = 0; index < 3; index++)
        {
            var base = yard.delivery(index);
            buildPad(level, base, 32, 32, yard.prefix() + " Delivery " + (index + 1));
            level.setBlock(yard.deliveryBeacon(index), ModBlocks.SHIP_BEACON.defaultBlockState(), 18);
            ShipGallery.label(level, base.offset(1, 1, -3), DyeColor.CYAN, "DELIVERY " + (index + 1), "Choose at beacon", "ALL SHIPS FREE", "Keep pad clear");
        }
        for (int index = 0; index < ShipBlueprints.PRESETS.size(); index++)
        {
            var preset = ShipBlueprints.PRESETS.get(index);
            var base = yard.pad(index);
            buildPad(level, base, 32, 32, yard.prefix() + " " + preset.name());
            var pad = new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
            var at = ShipBlueprints.origin(preset, pad, 32);
            for (var entry : ShipBlueprints.blocks(preset).entrySet())
                level.setBlock(at.offset(entry.getKey()), entry.getValue(), 18);
            for (var entry : ShipBlueprints.blocks(preset).entrySet())
                if (entry.getValue().is(ModBlocks.GENERATOR))
                    FuelSystem.provision(level, at.offset(entry.getKey()), 64);
            var ship = ShipTopology.resolve(level, at.offset(preset.helm()));
            ship.putString("blueprint", preset.id());
            ship.putString("name", preset.name());
            if (preset.planned())
            {
                ship.putBoolean("generatedRooms", true);
                RoomEntities.spawn(level, at, ShipBlueprints.points(preset));
            }
            SystemsUpgrade.upgrade(level, ship);
            ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
            ShipGallery.label(level, base.offset(1, 1, -3), DyeColor.GREEN, preset.name(), preset.size(), preset.decks() + " DECKS", "FREE at beacon");
        }
        server.saveEverything(true, true, true);
        saved.putBoolean("complete", true);
        WorldStore.write(marker, saved);
        return yard;
    }

    private static boolean conflicts(MinecraftServer server, String prefix)
    {
        return PadRegistry.read(server).stream().anyMatch(p -> p.name().startsWith(prefix + " "));
    }

    public static void buildPad(ServerLevel level, BlockPos base, int width, int depth, String name) throws java.io.IOException
    {
        for (int x = -1; x <= width; x++)
            for (int z = -1; z <= depth; z++)
                level.setBlock(base.offset(x, 0, z), (x < 0 || z < 0 || x == width || z == depth ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 18);
        var computer = base.offset(-1, 1, depth / 2);
        level.setBlock(computer, PadComputers.BLOCK.defaultBlockState(), 18);
        var result = PadComputers.register(level, computer, new PadScanner.Pad(base.getX(), base.getX() + width - 1, base.getY(), base.getZ(), base.getZ() + depth - 1), name, false);
        if (!result.startsWith("Registered "))
            throw new IllegalArgumentException(result);
    }

    private ShipYard()
    {
    }
}
