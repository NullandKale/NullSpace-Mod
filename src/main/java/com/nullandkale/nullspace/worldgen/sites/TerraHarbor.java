package com.nullandkale.nullspace.worldgen.sites;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;

import java.util.*;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A public arrival hall with a ship pad beside it. The site keeps clear of registered landing pads,
 * ships and spawn protection; the hall shell cannot be broken by players, while its workstations
 * and garden are ordinary blocks anyone can use.
 */
public final class TerraHarbor
{
    /** Layout version; older harbors are relocated (if they overlap a pad) or rebuilt. */
    static final int VERSION = 2;

    private static final int WIDTH = 48, DEPTH = 40, HALL = 14;
    private static final BlockPos COMPUTER = new BlockPos(HALL, 1, 20);

    private static HullScan.Bounds area(BlockPos base)
    {
        return new HullScan.Bounds(base, base.offset(WIDTH - 1, 35, DEPTH - 1));
    }

    /** Registered pads (other than this harbor's own) or surface ships that the area would hit. */
    public static boolean conflicts(MinecraftServer server, BlockPos base, BlockPos ownComputer)
    {
        var level = server.overworld();
        String dimension = level.dimension().identifier().toString();
        AABB zone = area(base).box().inflate(16);
        for (var pad : PadRegistry.read(server))
        {
            if (!pad.dimension().equals(dimension) || pad.computer().equals(ownComputer))
                continue;
            var b = pad.bounds();
            if (zone.intersects(new AABB(b.minX(), b.y(), b.minZ(), b.maxX() + 1, b.y() + 33, b.maxZ() + 1)))
                return true;
        }
        for (var tag : WorldStore.get(server).ships())
        {
            var ship = (net.minecraft.nbt.CompoundTag) tag;
            if (ship.getStringOr("dimension", "").equals(dimension) && zone.intersects(WorldStore.bounds(ship).box()))
                return true;
        }
        var spawn = level.getRespawnData().pos();
        int protectedRadius = (server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated ? dedicated.spawnProtectionRadius() : 0) + 32;
        return Math.abs(base.getX() + WIDTH / 2 - spawn.getX()) < protectedRadius + WIDTH / 2 && Math.abs(base.getZ() + DEPTH / 2 - spawn.getZ()) < protectedRadius + DEPTH / 2;
    }

    private static BlockPos findSite(MinecraftServer server)
    {
        var level = server.overworld();
        var spawn = level.getRespawnData().pos();
        for (int attempt = 0; attempt < 128; attempt++)
        {
            int x = spawn.getX() + 96 + (attempt % 16) * 64;
            int z = spawn.getZ() + 96 + (attempt / 16) * 64;
            int high = level.getMinY();
            for (int dx = 0; dx < WIDTH; dx += 8)
                for (int dz = 0; dz < DEPTH; dz += 8)
                {
                    level.getChunk(new BlockPos(x + dx, 0, z + dz));
                    high = Math.max(high, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
                }
            if (high == level.getMinY())
                high = 64;
            if (high + 36 >= level.getMaxY())
                continue;
            var candidate = new BlockPos(x, high, z);
            if (conflicts(server, candidate, null))
                continue;
            boolean clear = true;
            for (var at : BlockPos.betweenClosed(area(candidate).min(), area(candidate).max()))
                if (!level.getBlockState(at).isAir())
                {
                    clear = false;
                    break;
                }
            if (clear && HullEntities.inBounds(level, area(candidate)).isEmpty())
                return candidate;
        }
        throw new IllegalStateException("No clear site for Terra Harbor");
    }

    /** The harbor as a block map; facing and pane connections are settled before placement. */
    static Map<BlockPos, BlockState> plan()
    {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = 0; x < WIDTH; x++)
            for (int z = 0; z < DEPTH; z++)
                blocks.put(new BlockPos(x, 0, z), (x < HALL ? ((x + z) % 7 == 0 ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS) : Blocks.SMOOTH_STONE).defaultBlockState());
        for (int x = 0; x < HALL; x++)
            for (int z = 0; z < DEPTH; z++)
                for (int y = 1; y <= 4; y++)
                    if ((x == 0 || z == 0 || z == DEPTH - 1) && !(x == 0 && z >= 17 && z <= 21))
                        blocks.put(new BlockPos(x, y, z), (y == 2 ? Blocks.GLASS_PANE : Blocks.STONE_BRICKS).defaultBlockState());
        // Workstations stand against the west wall; the settle pass turns their fronts to the hall.
        blocks.put(new BlockPos(1, 1, 4), Blocks.CRAFTING_TABLE.defaultBlockState());
        blocks.put(new BlockPos(1, 1, 5), Blocks.FURNACE.defaultBlockState());
        blocks.put(new BlockPos(1, 1, 6), Blocks.COMPOSTER.defaultBlockState());
        // A renewable food source that follows normal world rules; arriving does not refill anything.
        for (int z = 26; z < 36; z++)
        {
            blocks.put(new BlockPos(3, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
            blocks.put(new BlockPos(3, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            blocks.put(new BlockPos(4, 0, z), Blocks.WATER.defaultBlockState());
        }
        blocks.put(COMPUTER, PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.WEST));
        BlueprintOrientation.fix(blocks);
        return blocks;
    }

    public static BlockPos ensure(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        var site = store.section("harbor");
        var level = server.overworld();
        if (site.contains("base") && site.getIntOr("version", 1) < VERSION)
        {
            var old = WorldStore.pos(site, "base");
            if (conflicts(server, old, old.offset(COMPUTER)))
            {
                // The first layout could land on a registered pad. Retire its pad and pick a new site;
                // the old blocks are left for an operator to clear rather than guessing at terrain.
                try
                {
                    var keep = PadRegistry.read(server).stream().filter(p -> !p.computer().equals(old.offset(COMPUTER))).toList();
                    PadRepository.get(server).replace(keep);
                }
                catch (java.io.IOException error)
                {
                    throw new IllegalStateException("Cannot retire the old Terra Harbor pad", error);
                }
                NullSpace.LOGGER.warn("Terra Harbor at {} overlapped a landing pad; relocating. Old blocks remain.", old);
                site.remove("base");
            }
            site.putBoolean("built", false);
        }
        if (!site.contains("base"))
        {
            site.putLong("base", findSite(server).asLong());
            store.save(); // Reserve before building, so interrupted construction resumes at the same site.
        }
        var base = WorldStore.pos(site, "base");
        for (int x = 0; x < WIDTH; x += 16)
            for (int z = 0; z < DEPTH; z += 16)
                level.getChunk(base.offset(x, 0, z));
        if (!site.getBooleanOr("built", false))
        {
            com.nullandkale.nullspace.gameplay.travel.BuildJournal.run(level, "terra-harbor", List.of(), plan().keySet().stream().map(base::offset).toList(), () ->
            {
                for (var e : plan().entrySet())
                    level.setBlock(base.offset(e.getKey()), e.getValue(), 3);
                var computer = base.offset(COMPUTER);
                try
                {
                    PadComputers.register(level, computer, new PadScanner.Pad(base.getX() + 15, base.getX() + 46, base.getY(), base.getZ() + 4, base.getZ() + 35), "Terra Harbor", false);
                }
                catch (java.io.IOException error)
                {
                    throw new IllegalStateException("Cannot register Terra Harbor", error);
                }
                server.saveEverything(false, true, true);
                site.putBoolean("built", true);
                site.putInt("version", VERSION);
                store.save();
                return null;
            });
        }
        return base;
    }

    public static Vec3 arrival(MinecraftServer server)
    {
        var base = ensure(server);
        var level = server.overworld();
        for (int z = 8; z <= 24; z += 2)
            for (int x = 6; x <= 11; x += 2)
            {
                var feet = base.offset(x, 1, z);
                var box = new AABB(feet).setMaxY(feet.getY() + 1.8).deflate(.05);
                if (level.getBlockState(feet.below()).isSolidRender() && level.noCollision(box) && level.getEntities(null, box).isEmpty())
                    return Vec3.atBottomCenterOf(feet);
            }
        throw new IllegalStateException("Terra Harbor arrival area is occupied; rescue will retry");
    }

    /** The hall's floor, walls and windows; workstations, garden and the pad are not included. */
    public static boolean protectedAt(ServerLevel level, BlockPos at)
    {
        if (level != level.getServer().overworld())
            return false;
        var site = WorldStore.get(level.getServer()).section("harbor");
        if (!site.getBooleanOr("built", false))
            return false;
        var local = at.subtract(WorldStore.pos(site, "base"));
        int x = local.getX(), y = local.getY(), z = local.getZ();
        if (x < 0 || x >= HALL || z < 0 || z >= DEPTH)
            return false;
        if (y == 0)
            return !(x == 3 || x == 4) || z < 26 || z >= 36; // garden soil and water stay open
        boolean wall = (x == 0 || z == 0 || z == DEPTH - 1) && !(x == 0 && z >= 17 && z <= 21);
        return wall && y >= 1 && y <= 4;
    }

    public static void initialize()
    {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> !(world instanceof ServerLevel level) || !protectedAt(level, pos) || player instanceof ServerPlayer p && p.isCreative() && p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    private TerraHarbor()
    {
    }
}
