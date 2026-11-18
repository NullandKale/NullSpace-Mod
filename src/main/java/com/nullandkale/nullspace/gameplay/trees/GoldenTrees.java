package com.nullandkale.nullspace.gameplay.trees;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The golden apple tree and the enchanted golden apple tree (chunk B3 of {@code docs/design/rewards-work-plan.md}). They work the same way:
 * a tree grows one apple per Minecraft day up to eight, counting the days it was out of sight ({@link TreeGrowth}); apples are picked from
 * the leaves they hang from by hand; breaking the trunk's core drops one sapling of the tree's own kind, which grows into a tree again. Only the
 * golden apple tree is ever generated (one stands in each hab's grove); an enchanted tree comes only from nine golden saplings crafted
 * into an enchanted sapling. A tree gives a hab air, as an Air Supply does, with no power ({@code AirSystem}).
 */
public final class GoldenTrees
{
    /** The two trees: what they are called in the game's ids, and what they grow. */
    public enum Kind
    {
        PLAIN("golden_apple", Items.GOLDEN_APPLE), ENCHANTED("enchanted_golden_apple", Items.ENCHANTED_GOLDEN_APPLE);

        public final String id;
        public final Item apple;

        Kind(String id, Item apple)
        {
            this.id = id;
            this.apple = apple;
        }
    }

    public static final Block TRUNK = trunk(Kind.PLAIN), ENCHANTED_TRUNK = trunk(Kind.ENCHANTED);

    public static final Block LEAVES = leaves(Kind.PLAIN), ENCHANTED_LEAVES = leaves(Kind.ENCHANTED);

    public static final Block SAPLING = sapling(Kind.PLAIN), ENCHANTED_SAPLING = sapling(Kind.ENCHANTED);

    /** An apple hanging from the bottom of a leaf. */
    public static final Block HANGING = hanging(Kind.PLAIN), ENCHANTED_HANGING = hanging(Kind.ENCHANTED);

    public static final BlockEntityType<TreeBlockEntity> TREE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:golden_tree"), new BlockEntityType<>(TreeBlockEntity::new, Set.of(TRUNK, ENCHANTED_TRUNK)));

    public static Block trunkOf(Kind kind)
    {
        return kind == Kind.PLAIN ? TRUNK : ENCHANTED_TRUNK;
    }

    public static Block leavesOf(Kind kind)
    {
        return kind == Kind.PLAIN ? LEAVES : ENCHANTED_LEAVES;
    }

    public static Block hangingOf(Kind kind)
    {
        return kind == Kind.PLAIN ? HANGING : ENCHANTED_HANGING;
    }

    public static Block saplingOf(Kind kind)
    {
        return kind == Kind.PLAIN ? SAPLING : ENCHANTED_SAPLING;
    }

    public static boolean isTrunk(Block block)
    {
        return block == TRUNK || block == ENCHANTED_TRUNK;
    }

    /** The kind of tree a trunk, leaf or sapling block belongs to, or null. */
    public static Kind kindOf(Block block)
    {
        return block instanceof Part part ? part.kind() : null;
    }

    /** A block that belongs to one of the trees. */
    interface Part
    {
        Kind kind();
    }

    private static Block trunk(Kind kind)
    {
        var id = Identifier.parse("nullspace:" + kind.id + "_trunk");
        return Registry.register(BuiltInRegistries.BLOCK, id, new Trunk(kind, BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD).ignitedByLava().setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    private static Block hanging(Kind kind)
    {
        var id = Identifier.parse("nullspace:" + kind.id + "_hanging");
        return Registry.register(BuiltInRegistries.BLOCK, id, new Hanging(kind, BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).noCollision().instabreak().sound(SoundType.SWEET_BERRY_BUSH).pushReaction(PushReaction.POPPED).setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    private static Block leaves(Kind kind)
    {
        var id = Identifier.parse("nullspace:" + kind.id + "_leaves");
        return Registry.register(BuiltInRegistries.BLOCK, id, new Leaves(kind, BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).strength(0.2F).sound(SoundType.GRASS).isValidSpawn((s, g, p, t) -> false).noLootTable().setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    private static Block sapling(Kind kind)
    {
        var id = Identifier.parse("nullspace:" + kind.id + "_sapling");
        var block = Registry.register(BuiltInRegistries.BLOCK, id, new Sapling(kind, BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.GRASS).pushReaction(PushReaction.POPPED).setId(ResourceKey.create(Registries.BLOCK, id))));
        var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix();
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, kind == Kind.ENCHANTED ? properties.rarity(Rarity.RARE) : properties));
        return block;
    }

    public static void initialize()
    {
    }

    /** The cells of a tree and the blocks that fill them, relative to its core: the core, the oak logs, and the crown of leaves. */
    public record Piece(TreeShape.Cell at, BlockState state)
    {}

    public static List<Piece> pieces(Kind kind)
    {
        var out = new ArrayList<Piece>();
        out.add(new Piece(new TreeShape.Cell(0, 0, 0), trunkOf(kind).defaultBlockState()));
        for (var cell : TreeShape.logs())
            out.add(new Piece(cell, Blocks.OAK_LOG.defaultBlockState()));
        for (var cell : TreeShape.crown())
            out.add(new Piece(cell, leavesOf(kind).defaultBlockState()));
        return out;
    }

    /** Grows a sapling into its tree if there is room: the cells above it must be air or leaves. Returns whether it grew. */
    public static boolean grow(ServerLevel level, BlockPos sapling, Kind kind)
    {
        var pieces = pieces(kind);
        for (var piece : pieces)
        {
            var at = sapling.offset(piece.at().dx(), piece.at().dy(), piece.at().dz());
            var state = level.getBlockState(at);
            if (!level.isInsideBuildHeight(at) || !(at.equals(sapling) || state.isAir() || state.canBeReplaced() || state.getBlock() instanceof Leaves))
                return false;
        }
        for (var piece : pieces)
            level.setBlock(sapling.offset(piece.at().dx(), piece.at().dy(), piece.at().dz()), piece.state(), 3);
        return true;
    }

    /** Hangs the first {@code apples} apples of the tree whose core is at {@code core} from its leaves, and takes the rest away. */
    public static void refreshFruit(ServerLevel level, BlockPos core, int apples)
    {
        var kind = kindOf(level.getBlockState(core).getBlock());
        if (kind == null)
            return;
        var fruit = TreeShape.fruit();
        for (int i = 0; i < fruit.size(); i++)
        {
            var at = core.offset(fruit.get(i).dx(), fruit.get(i).dy(), fruit.get(i).dz());
            var state = level.getBlockState(at);
            if (i < apples && state.isAir() && level.getBlockState(at.above()).is(leavesOf(kind)))
                level.setBlock(at, hangingOf(kind).defaultBlockState(), 2);
            else if (i >= apples && state.is(hangingOf(kind)))
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** How many apples hang on the tree whose core is at {@code core}. */
    public static int fruitCount(Level level, BlockPos core)
    {
        var kind = kindOf(level.getBlockState(core).getBlock());
        int n = 0;
        for (var cell : TreeShape.fruit())
            if (kind != null && level.getBlockState(core.offset(cell.dx(), cell.dy(), cell.dz())).is(hangingOf(kind)))
                n++;
        return n;
    }

    /** The core of the tree a leaf or an apple belongs to, found by looking where its trunk can be; null if the trunk is gone. */
    public static BlockPos coreOf(Level level, BlockPos leaf, Kind kind)
    {
        for (int dy = 3; dy <= 8; dy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                {
                    var at = leaf.offset(-dx, -dy, -dz);
                    if (level.getBlockState(at).is(trunkOf(kind)))
                        return at;
                }
        return null;
    }

    /**
     * The cell a tree's air comes out into, beside its trunk core at the foot: the first of its four sides that is not solid. Null when the
     * trunk is shut in on every side.
     */
    public static BlockPos outlet(Level level, BlockPos core)
    {
        for (var side : Direction.Plane.HORIZONTAL)
        {
            var at = core.relative(side);
            if (level.getBlockState(at).getCollisionShape(level, at).isEmpty())
                return at;
        }
        return null;
    }

    /** The trunk core: what a tree is. Breaking it drops one sapling of its kind. */
    static final class Trunk extends Block implements EntityBlock, Part
    {
        private final Kind kind;

        Trunk(Kind kind, Properties properties)
        {
            super(properties);
            this.kind = kind;
        }

        @Override
        public Kind kind()
        {
            return kind;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
        {
            return new TreeBlockEntity(pos, state);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type)
        {
            return level.isClientSide() || type != TREE ? null : (BlockEntityTicker<T>) (BlockEntityTicker<TreeBlockEntity>) TreeBlockEntity::tick;
        }
    }

    /** A leaf of a tree: it never decays. */
    static final class Leaves extends Block implements Part
    {
        private final Kind kind;

        Leaves(Kind kind, Properties properties)
        {
            super(properties);
            this.kind = kind;
        }

        @Override
        public Kind kind()
        {
            return kind;
        }
    }

    /**
     * An apple hanging from the bottom of a leaf. A hand picks it (or punches it off, and it drops the apple); it falls with the leaf it
     * hangs from.
     */
    static final class Hanging extends Block implements Part
    {
        private static final VoxelShape SHAPE = Block.box(4, 4, 4, 12, 16, 12);
        private final Kind kind;

        Hanging(Kind kind, Properties properties)
        {
            super(properties);
            this.kind = kind;
        }

        @Override
        public Kind kind()
        {
            return kind;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
        {
            return SHAPE;
        }

        @Override
        protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos)
        {
            return level.getBlockState(pos.above()).is(leavesOf(kind));
        }

        @Override
        protected BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos pos, Direction direction, BlockPos neighbourPos, BlockState neighbour, RandomSource random)
        {
            return direction == Direction.UP && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState() : super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
        {
            if (level instanceof ServerLevel server)
            {
                server.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                var core = coreOf(server, pos, kind);
                if (core != null && server.getBlockEntity(core) instanceof TreeBlockEntity tree)
                    tree.picked();
                var apple = new ItemStack(kind.apple);
                if (!player.getInventory().add(apple))
                    Block.popResource(server, pos, apple);
                server.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 1.0F);
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                    serverPlayer.sendOverlayMessage(Component.literal(kind == Kind.PLAIN ? "Golden apple picked" : "Enchanted golden apple picked"));
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** A sapling: it needs light and grows into its tree if there is room. */
    static final class Sapling extends VegetationBlock implements Part
    {
        private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 12.0);
        private final Kind kind;

        Sapling(Kind kind, Properties properties)
        {
            super(properties);
            this.kind = kind;
        }

        @Override
        public Kind kind()
        {
            return kind;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
        {
            return SHAPE;
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random)
        {
            if (level.getMaxLocalRawBrightness(pos.above()) >= 9 && random.nextInt(7) == 0)
                grow(level, pos, kind);
        }
    }

    private GoldenTrees()
    {
    }
}
