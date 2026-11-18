package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.SpaceMaterials;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.boss.SpaceBoss;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;

import java.util.Set;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The space materials and what they make, in a lit hall for review: a green geode cut open behind glass, a basin of the green fluid
 * beside gem blocks and clusters, the salvage turret and repair bay on a bench under frames of the new items, and a white space wither
 * standing still. {@code /nullspace shipyard materials} builds it, one section after another going east, rebuilt in the same place
 * each visit.
 */
public final class MaterialsGallery
{
    public static final int PITCH = 22, SECTIONS = 5, DEPTH = 30, HEIGHT = 20, AISLE = 26;

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("materials").executes(ctx ->
        {
            var player = ctx.getSource().getPlayerOrException();
            try
            {
                var server = player.level().getServer();
                var origin = build(server);
                player.teleportTo(server.overworld(), origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + AISLE + .5, Set.of(), -90, 5, true);
                player.sendSystemMessage(Component.literal("Materials gallery, going east: the green geode cut open, the green fluid with gem blocks and clusters, the salvage turret and repair bay with the new items on frames, and the space wither."));
                return 1;
            }
            catch (Exception error)
            {
                com.nullandkale.nullspace.NullSpace.LOGGER.error("The materials gallery failed", error);
                player.sendSystemMessage(Component.literal("Could not build the materials gallery: " + ShipMessages.failure(error)));
                return 0;
            }
        })))));
    }

    /** Builds the hall, replacing any earlier one; returns its south-west corner. */
    public static BlockPos build(MinecraftServer server)
    {
        var level = server.overworld();
        int width = SECTIONS * PITCH;
        int x = 1024 + 3200, z = 2048;
        for (int cx = x >> 4; cx <= (x + width) >> 4; cx++)
            for (int cz = z >> 4; cz <= (z + DEPTH) >> 4; cz++)
                level.getChunk(cx, cz);
        int y = level.getMinY();
        for (int dx = 0; dx < width; dx += 2)
            for (int dz = 0; dz < DEPTH; dz += 2)
                y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz));
        y += 4;
        if (y + HEIGHT + 4 >= level.getMaxY())
            throw new IllegalArgumentException("No room above the terrain for a gallery.");
        var origin = new BlockPos(x, y, z);
        level.getEntitiesOfClass(Mob.class, new AABB(x - 2, y - 1, z - 2, x + width + 2, y + HEIGHT + 2, z + DEPTH + 2)).forEach(Mob::discard);
        level.getEntitiesOfClass(ItemFrame.class, new AABB(x - 2, y - 1, z - 2, x + width + 2, y + HEIGHT + 2, z + DEPTH + 2)).forEach(ItemFrame::discard);
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < DEPTH; dz++)
                for (int dy = 0; dy <= HEIGHT; dy++)
                {
                    boolean edge = dx == 0 || dz == 0 || dx == width - 1 || dz == DEPTH - 1;
                    boolean lamp = dx % 4 == 2 && dz % 5 == 2;
                    var block = dy == 0 ? (lamp ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray()) : dy == HEIGHT ? Blocks.GLASS : edge ? Blocks.STONE_BRICKS : Blocks.AIR;
                    level.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), 18);
                }
        // The roof is too far up to light the floor, so lamps line both long walls at two heights.
        for (int dx = 2; dx < width; dx += 4)
            for (int dy : new int[]{4, 10})
            {
                level.setBlock(origin.offset(dx, dy, 1), Blocks.SEA_LANTERN.defaultBlockState(), 2);
                level.setBlock(origin.offset(dx, dy, DEPTH - 2), Blocks.SEA_LANTERN.defaultBlockState(), 2);
            }
        // The roof is glass, so daylight lights the hall in its natural colour (block light alone tints everything tan).
        // Invisible light blocks in a grid at three heights keep the whole hall bright.
        for (int dx = 3; dx < width; dx += 6)
            for (int dz = 4; dz < DEPTH; dz += 6)
                for (int dy : new int[]{3, 9, 16})
                    if (level.getBlockState(origin.offset(dx, dy, dz)).isAir())
                        level.setBlock(origin.offset(dx, dy, dz), Blocks.LIGHT.defaultBlockState(), 2);
        geode(level, origin.offset(11, 9, 12));
        pool(level, origin.offset(PITCH + 11, 0, 14));
        machines(level, origin.offset(PITCH * 2 + 11, 0, 10));
        wither(level, origin.offset(PITCH * 3 + 11, 1, 15));
        trees(level, origin.offset(PITCH * 4 + 11, 1, 15), origin.offset(PITCH * 4 + 11, 3, 1));
        String[][] labels = {{"Green geode", "cut open"}, {"Green fluid", "gem block", "gem cluster"}, {"Salvage turret", "Repair bay", "new items"}, {"Space wither"}, {"Golden apple tree", "and the enchanted", "golden apple tree"}};
        for (int i = 0; i < SECTIONS; i++)
            ShipGallery.label(level, origin.offset(PITCH * i + 11, 1, DEPTH - 2), DyeColor.GREEN, labels[i]);
        return origin;
    }

    /** A green geode in a solid block of stone (it will not form beside air), its front half taken away and a pane of glass over the cut, so the gems and the fluid show. */
    private static void geode(ServerLevel level, BlockPos at)
    {
        for (int x = -9; x <= 9; x++)
            for (int y = -8; y <= 8; y++)
                for (int z = -9; z <= 9; z++)
                    level.setBlock(at.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(Identifier.parse("nullspace:green_geode"));
        boolean placed = false;
        for (int seed = 0; seed < 16 && !placed; seed++)
            placed = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), at);
        if (!placed)
            throw new IllegalStateException("The green geode did not place");
        for (int x = -9; x <= 9; x++)
            for (int y = -8; y <= 8; y++)
                for (int z = 1; z <= 9; z++)
                    level.setBlock(at.offset(x, y, z), z == 1 && Math.abs(x) == 7 && Math.abs(y) == 7 ? Blocks.SEA_LANTERN.defaultBlockState() : z == 1 ? (x * x + y * y <= 20 ? Blocks.GLASS : Blocks.STONE).defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
    }

    /** A basin of the green fluid beside a pile of gem blocks and clusters. */
    private static void pool(ServerLevel level, BlockPos at)
    {
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
            {
                boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                level.setBlock(at.offset(x, 0, z), (edge ? Blocks.STONE_BRICKS : Blocks.STONE).defaultBlockState(), 2);
                level.setBlock(at.offset(x, 1, z), (edge ? Blocks.STONE_BRICKS.defaultBlockState() : SpaceMaterials.GREEN_FLUID_BLOCK.defaultBlockState()), 2);
            }
        level.setBlock(at.offset(6, 1, 0), SpaceMaterials.GREEN_GEM_BLOCK.defaultBlockState(), 2);
        level.setBlock(at.offset(6, 2, 0), SpaceMaterials.GREEN_GEM_BLOCK.defaultBlockState(), 2);
        level.setBlock(at.offset(7, 1, 0), SpaceMaterials.GREEN_GEM_BLOCK.defaultBlockState(), 2);
        level.setBlock(at.offset(6, 1, 2), SpaceMaterials.GREEN_GEM_CLUSTER.defaultBlockState(), 2);
        level.setBlock(at.offset(7, 1, 2), SpaceMaterials.GREEN_GEM_CLUSTER.defaultBlockState(), 2);
        level.setBlock(at.offset(6, 1, -2), SpaceMaterials.GREEN_GEM_CLUSTER.defaultBlockState(), 2);
    }

    /** The two machines on a bench with the new items hung on the wall behind them. */
    private static void machines(ServerLevel level, BlockPos at)
    {
        for (int x = -4; x <= 4; x++)
            for (int y = 1; y <= 5; y++)
                level.setBlock(at.offset(x, y, -2), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(at.offset(-2, 1, 0), DefenceBlocks.SALVAGE_TURRET.defaultBlockState(), 2);
        // The turret looks out over the aisle, a little up, so its head and gun show turned.
        if (level.getBlockEntity(at.offset(-2, 1, 0)) instanceof com.nullandkale.nullspace.gameplay.defence.TurretBlockEntity turret)
            turret.aim(net.minecraft.world.phys.Vec3.atCenterOf(at.offset(-2, 1, 0)).add(0, 0.7, 0), net.minecraft.world.phys.Vec3.atCenterOf(at.offset(2, 3, 12)));
        level.setBlock(at.offset(0, 1, 0), DefenceBlocks.REPAIR_BAY.defaultBlockState(), 2);
        level.setBlock(at.offset(2, 1, 0), SpaceMaterials.GREEN_GEM_BLOCK.defaultBlockState(), 2);
        var items = new ItemStack[]{new ItemStack(SpaceMaterials.GREEN_GEM), new ItemStack(SpaceMaterials.GREEN_FLUID_BUCKET), new ItemStack(SpaceBoss.BLACK_HOLE), new ItemStack(SpaceBoss.BEACON_HELMET)};
        for (int i = 0; i < items.length; i++)
        {
            var frame = new ItemFrame(level, at.offset(-3 + i * 2, 3, -1), Direction.SOUTH);
            frame.setItem(items[i], false);
            level.addFreshEntity(frame);
        }
    }

    /**
     * The two trees side by side, each in a bed of podzol with every one of its eight apples grown, and a frame of each sapling on the wall
     * behind: the golden apple tree on the left, the enchanted golden apple tree on the right.
     */
    private static void trees(ServerLevel level, BlockPos at, BlockPos wall)
    {
        for (var kind : com.nullandkale.nullspace.gameplay.trees.GoldenTrees.Kind.values())
        {
            int side = kind == com.nullandkale.nullspace.gameplay.trees.GoldenTrees.Kind.PLAIN ? -5 : 5;
            var core = at.offset(side, 0, 0);
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    level.setBlock(core.offset(x, -1, z), Blocks.PODZOL.defaultBlockState(), 2);
            level.setBlock(core, com.nullandkale.nullspace.gameplay.trees.GoldenTrees.saplingOf(kind).defaultBlockState(), 2);
            if (com.nullandkale.nullspace.gameplay.trees.GoldenTrees.grow(level, core, kind) && level.getBlockEntity(core) instanceof com.nullandkale.nullspace.gameplay.trees.TreeBlockEntity tree)
            {
                tree.advanceNow(level);
                tree.awayFor(com.nullandkale.nullspace.gameplay.trees.TreeGrowth.DAY * 8);
                tree.advanceNow(level);
            }
            var frame = new ItemFrame(level, wall.offset(side, 0, 0), Direction.SOUTH);
            frame.setItem(new ItemStack(com.nullandkale.nullspace.gameplay.trees.GoldenTrees.saplingOf(kind)), false);
            level.addFreshEntity(frame);
        }
    }

    /** A space wither standing still, facing the aisle. */
    private static void wither(ServerLevel level, BlockPos at)
    {
        var wither = SpaceBoss.SPACE_WITHER.create(level, EntitySpawnReason.COMMAND);
        wither.snapTo(at.getX() + .5, at.getY() + 2, at.getZ() + .5, 180, 0);
        wither.setNoAi(true);
        wither.setPermanentlyInvulnerable(true);
        wither.setSilent(true);
        wither.setPersistenceRequired();
        level.addFreshEntity(wither);
    }

    private MaterialsGallery()
    {
    }
}
