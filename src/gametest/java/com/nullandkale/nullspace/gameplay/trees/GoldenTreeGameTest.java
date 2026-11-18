package com.nullandkale.nullspace.gameplay.trees;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.trees.GoldenTrees.Kind;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The golden apple tree and the enchanted golden apple tree: growing from a sapling, the apples a day brings, picking, the sapling a trunk drops, the craft, and the air a tree gives. */
public final class GoldenTreeGameTest
{
    /** Dirt with a sapling of {@code kind} on it, at a spot of its own, far from the others. */
    private static BlockPos plant(GameTestHelper ctx, Kind kind, int index)
    {
        var level = ctx.getLevel();
        var at = ctx.absolutePos(new BlockPos(40 + index * 30, 6, 40));
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
            {
                level.setBlock(at.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), 2);
                for (int y = 0; y <= 12; y++)
                    level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        level.setBlock(at, GoldenTrees.saplingOf(kind).defaultBlockState(), 2);
        return at;
    }

    /** The apples hanging on a tree. */
    private static int fruit(ServerLevel level, BlockPos core, Kind kind)
    {
        return GoldenTrees.fruitCount(level, core);
    }

    @GameTest(maxTicks = 100)
    public void aSaplingOfEitherKindGrowsIntoItsTreeIfThereIsRoomAndNotIfThereIsNot(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        for (var kind : Kind.values())
        {
            var at = plant(ctx, kind, kind.ordinal());
            ctx.assertTrue(GoldenTrees.grow(level, at, kind), kind + " grows");
            ctx.assertTrue(level.getBlockState(at).is(GoldenTrees.trunkOf(kind)) && level.getBlockEntity(at) instanceof TreeBlockEntity, kind + " has its core, with its memory");
            for (var cell : TreeShape.logs())
                ctx.assertTrue(level.getBlockState(at.offset(cell.dx(), cell.dy(), cell.dz())).is(Blocks.OAK_LOG), kind + " has its logs");
            int leaves = 0;
            for (var cell : TreeShape.crown())
                if (level.getBlockState(at.offset(cell.dx(), cell.dy(), cell.dz())).is(GoldenTrees.leavesOf(kind)))
                    leaves++;
            ctx.assertTrue(leaves == TreeShape.crown().size(), kind + " has its crown: " + leaves);
        }
        var blocked = plant(ctx, Kind.PLAIN, 2);
        level.setBlock(blocked.above(7), Blocks.STONE.defaultBlockState(), 2);
        ctx.assertTrue(!GoldenTrees.grow(level, blocked, Kind.PLAIN), "A tree with a block in its crown does not grow");
        ctx.assertTrue(level.getBlockState(blocked).is(GoldenTrees.SAPLING), "and the sapling stays a sapling");
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aTreeGrowsOneAppleADayAndAnyDaysItWasAwayUpToEightAndHangsThemUnderItsLeaves(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var at = plant(ctx, Kind.PLAIN, 0);
        GoldenTrees.grow(level, at, Kind.PLAIN);
        var tree = (TreeBlockEntity) level.getBlockEntity(at);
        tree.advanceNow(level);
        ctx.assertTrue(tree.apples() == 0 && fruit(level, at, Kind.PLAIN) == 0, "A new tree has no apples: " + tree.apples());
        tree.awayFor(TreeGrowth.DAY * 3 + 500);
        tree.advanceNow(level);
        ctx.assertTrue(tree.apples() == 3 && fruit(level, at, Kind.PLAIN) == 3, "Three days away is three apples, hanging: " + tree.apples() + " and " + fruit(level, at, Kind.PLAIN));
        tree.awayFor(TreeGrowth.DAY * 100);
        tree.advanceNow(level);
        ctx.assertTrue(tree.apples() == 8 && fruit(level, at, Kind.PLAIN) == 8, "A long time away is the most, eight: " + tree.apples() + " and " + fruit(level, at, Kind.PLAIN));
        tree.awayFor(TreeGrowth.DAY * 5);
        tree.advanceNow(level);
        ctx.assertTrue(tree.apples() == 8, "A full tree stays at eight");
        ctx.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(maxTicks = 100)
    public void anAppleHangsFromTheBottomOfALeafIsPickedByHandAndEachTreeGivesItsOwnApple(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        for (var kind : Kind.values())
        {
            var at = plant(ctx, kind, kind.ordinal());
            GoldenTrees.grow(level, at, kind);
            var tree = (TreeBlockEntity) level.getBlockEntity(at);
            tree.advanceNow(level);
            tree.awayFor(TreeGrowth.DAY * 8);
            tree.advanceNow(level);
            for (var cell : TreeShape.fruit())
            {
                var hangs = at.offset(cell.dx(), cell.dy(), cell.dz());
                ctx.assertTrue(level.getBlockState(hangs).is(GoldenTrees.hangingOf(kind)) && level.getBlockState(hangs.above()).is(GoldenTrees.leavesOf(kind)), kind + ": an apple hangs at " + cell + " under a leaf");
            }
            var player = ctx.makeMockServerPlayerInLevel();
            var cell = TreeShape.fruit().getFirst();
            var apple = at.offset(cell.dx(), cell.dy(), cell.dz());
            var hit = new BlockHitResult(Vec3.atCenterOf(apple), Direction.UP, apple, false);
            level.getBlockState(apple).useWithoutItem(level, player, hit);
            int picked = player.getInventory().countItem(kind.apple);
            ctx.assertTrue(picked == 1, kind + " gives its own apple: " + picked);
            ctx.assertTrue(level.getBlockState(apple).isAir() && tree.apples() == 7, kind + " apple is gone and the tree has seven: " + tree.apples());
            var leaf = at.offset(TreeShape.crown().getFirst().dx(), TreeShape.crown().getFirst().dy(), TreeShape.crown().getFirst().dz());
            level.getBlockState(leaf).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(leaf), Direction.UP, leaf, false));
            ctx.assertTrue(player.getInventory().countItem(kind.apple) == 1, "A leaf gives nothing");
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void anApplePunchedOffDropsItAndLeavesTheCountAndOneThatLosesItsLeafFalls(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var at = plant(ctx, Kind.PLAIN, 0);
        GoldenTrees.grow(level, at, Kind.PLAIN);
        var tree = (TreeBlockEntity) level.getBlockEntity(at);
        tree.advanceNow(level);
        tree.awayFor(TreeGrowth.DAY * 8);
        tree.advanceNow(level);
        var first = at.offset(TreeShape.fruit().getFirst().dx(), TreeShape.fruit().getFirst().dy(), TreeShape.fruit().getFirst().dz());
        var drops = Block.getDrops(level.getBlockState(first), level, first, null, null, ItemStack.EMPTY);
        ctx.assertTrue(drops.size() == 1 && drops.getFirst().is(Items.GOLDEN_APPLE), "A punched apple drops a golden apple: " + drops);
        level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
        tree.advanceNow(level);
        ctx.assertTrue(tree.apples() == 7 && fruit(level, at, Kind.PLAIN) == 7, "The tree counts seven: " + tree.apples());
        var second = at.offset(TreeShape.fruit().get(1).dx(), TreeShape.fruit().get(1).dy(), TreeShape.fruit().get(1).dz());
        level.setBlock(second.above(), Blocks.AIR.defaultBlockState(), 3);
        ctx.assertTrue(level.getBlockState(second).isAir(), "An apple falls when its leaf goes");
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void breakingTheTrunkDropsOneSaplingOfItsOwnKindAndLeavesDropNothing(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        for (var kind : Kind.values())
        {
            var at = plant(ctx, kind, kind.ordinal());
            GoldenTrees.grow(level, at, kind);
            var drops = Block.getDrops(level.getBlockState(at), level, at, level.getBlockEntity(at), null, new ItemStack(Items.IRON_AXE));
            Item sapling = GoldenTrees.saplingOf(kind).asItem();
            ctx.assertTrue(drops.size() == 1 && drops.getFirst().is(sapling) && drops.getFirst().getCount() == 1, kind + " drops one sapling of its own kind: " + drops);
            var crown = TreeShape.crown().getFirst();
            var leaf = at.offset(crown.dx(), crown.dy(), crown.dz());
            ctx.assertTrue(Block.getDrops(level.getBlockState(leaf), level, leaf, null, null, new ItemStack(Items.IRON_AXE)).isEmpty(), kind + " leaves drop nothing");
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void anEnchantedSaplingIsMadeOnlyFromNineGoldenSaplingsInOneCraft(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var recipes = level.getServer().getRecipeManager();
        var saplings = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++)
            saplings.add(new ItemStack(GoldenTrees.SAPLING));
        var nine = recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, saplings), level);
        ctx.assertTrue(nine.isPresent() && nine.get().value().assemble(CraftingInput.of(3, 3, saplings)).is(GoldenTrees.ENCHANTED_SAPLING.asItem()), "Nine golden apple saplings make an enchanted one");
        var eight = new java.util.ArrayList<>(saplings);
        eight.set(4, ItemStack.EMPTY);
        ctx.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, eight), level).isEmpty(), "Eight make nothing");
        var mixed = new java.util.ArrayList<>(saplings);
        mixed.set(4, new ItemStack(GoldenTrees.ENCHANTED_SAPLING));
        ctx.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, mixed), level).isEmpty(), "Enchanted saplings do not multiply");
        ctx.succeed();
    }

    /** A powered reference ship whose own Air Supply is switched off, with its room emptied of air. */
    private static net.minecraft.nbt.CompoundTag dryShip(GameTestHelper ctx, ServerLevel level, BlockPos base) throws java.io.IOException
    {
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Tree test");
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        for (var p : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
            if (level.getBlockState(p).is(ModBlocks.AIR_SUPPLY))
                ShipSystems.setEnabled(level, ship, p.immutable(), false);
        var air = ship.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
        java.util.Arrays.fill(air, 0);
        ship.getCompoundOrEmpty("systems").putIntArray("air", air);
        ShipSystems.loaded(level, ship);
        return ship;
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 1200)
    public void aTreeGivesAShutRoomAirWithNoAirSupplyOn(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(700, 6, 700));
        var ship = dryShip(ctx, level, base);
        var inside = base.offset(3, 4, 3);
        ShipSystems.tick(level, ship);
        ctx.assertTrue(ShipAir.oxygen(level, ship, inside) < 5, "The room starts without air: " + ShipAir.oxygen(level, ship, inside));
        level.setBlock(base.offset(3, 3, 3), GoldenTrees.TRUNK.defaultBlockState(), 3);
        ShipSystems.loaded(level, ship);
        var finish = new Runnable[1];
        finish[0] = () ->
        {
            ShipSystems.tick(level, ship);
            if (ShipAir.oxygen(level, ship, inside) >= 25)
            {
                WorldStore.get(level.getServer()).ships().remove(ship);
                ctx.succeed();
            }
            else if (ctx.getTick() > 1000)
                ctx.fail("A tree should fill the room with air: " + ShipAir.oxygen(level, ship, inside));
            else
                ctx.runAfterDelay(1, () -> finish[0].run());
        };
        ctx.runAfterDelay(1, () -> finish[0].run());
    }
}
