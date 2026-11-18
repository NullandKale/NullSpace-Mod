package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.shipyard.TestbedStock;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** The endgame testbed ship: one deck, its farm, pen, lava farm and vault, and the chests the shipyard stocks. */
public final class EndgameTestbedGameTest
{
    private static long count(ShipBlueprints.Preset preset, Predicate<BlockState> what)
    {
        return ShipBlueprints.blocks(preset).values().stream().filter(what).count();
    }

    @GameTest(maxTicks = 100)
    public void theTestbedIsOneDeckWithAFarmAPenALavaFarmAndAVaultAndItsAnimalsAndChestsAreMarked(GameTestHelper ctx)
    {
        var preset = ShipBlueprints.get("endgame_testbed");
        ctx.assertTrue(preset.decks() == 1 && preset.width() <= 32 && preset.height() <= 32 && preset.depth() <= 32, "One deck inside the ship envelope: " + preset.size());
        ctx.assertTrue(preset.planned(), "The testbed is a planned ship, so its rooms' entities and stock are placed");
        ctx.assertTrue(count(preset, s -> s.is(Blocks.WHEAT)) >= 6 && count(preset, s -> s.is(Blocks.CARROTS)) >= 6, "Wheat and carrots grow");
        ctx.assertTrue(count(preset, s -> s.is(Blocks.COCOA)) >= 8, "Cocoa pods grow on jungle logs: " + count(preset, s -> s.is(Blocks.COCOA)));
        ctx.assertTrue(count(preset, s -> s.is(Blocks.SUGAR_CANE)) >= 6, "Sugar cane grows");
        ctx.assertTrue(count(preset, s -> s.is(Blocks.LAVA)) >= 3 && count(preset, s -> s.is(Blocks.POINTED_DRIPSTONE)) >= 3 && count(preset, s -> s.is(Blocks.LAVA_CAULDRON)) >= 3, "A lava farm: lava over dripstone over a cauldron, three times");
        ctx.assertTrue(count(preset, s -> s.is(Blocks.ENCHANTING_TABLE)) == 1 && count(preset, s -> s.is(Blocks.BOOKSHELF)) >= 15, "An enchanting table with fifteen bookshelves: " + count(preset, s -> s.is(Blocks.BOOKSHELF)));
        ctx.assertTrue(count(preset, s -> s.is(Blocks.BREWING_STAND)) >= 2 && count(preset, s -> s.is(Blocks.ANVIL)) >= 1 && count(preset, s -> s.is(Blocks.SMITHING_TABLE)) == 1, "Brewing, an anvil and a smithing table");
        ctx.assertTrue(count(preset, s -> s.is(ModBlocks.GENERATOR)) >= 1 && count(preset, s -> s.is(ModBlocks.AIR_SUPPLY)) >= 4 && count(preset, s -> s.is(ModBlocks.COMPUTER)) == 1, "Power, air and a helm");
        var points = ShipBlueprints.points(preset);
        long chickens = points.stream().filter(p -> p.name().equals("animal") && p.argument().equals("chicken")).count();
        long others = points.stream().filter(p -> p.name().equals("animal") && !p.argument().equals("chicken")).count();
        ctx.assertTrue(chickens >= 6 && others >= 6, "Chickens and the other animals are marked: " + chickens + " and " + others);
        for (var kind : TestbedStock.KINDS)
            ctx.assertTrue(points.stream().anyMatch(p -> p.name().equals("stock") && p.argument().equals(kind)), "A chest of " + kind + " is marked");
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void everyKindOfStockFitsAChestAndFillsOne(GameTestHelper ctx)
    {
        for (var kind : TestbedStock.KINDS)
            if (!kind.equals("treasure"))
                ctx.assertTrue(!TestbedStock.items(kind).isEmpty() && TestbedStock.items(kind).size() <= 27, kind + " is between one and 27 stacks: " + TestbedStock.items(kind).size());
        var level = ctx.getLevel();
        var at = new BlockPos(1, 2, 1);
        ctx.setBlock(at, Blocks.CHEST);
        var abs = ctx.absolutePos(at);
        ctx.assertTrue(TestbedStock.fill(level, abs, "gear") && contains((Container) level.getBlockEntity(abs), Items.NETHERITE_SWORD) && contains((Container) level.getBlockEntity(abs), Items.ELYTRA) && contains((Container) level.getBlockEntity(abs), Items.TOTEM_OF_UNDYING), "The gear chest holds netherite, an elytra and totems");
        ctx.assertTrue(TestbedStock.fill(level, abs, "space") && contains((Container) level.getBlockEntity(abs), com.nullandkale.nullspace.content.SpaceMaterials.GREEN_GEM) && contains((Container) level.getBlockEntity(abs), com.nullandkale.nullspace.gameplay.boss.SpaceBoss.BLACK_HOLE), "The space chest holds green gems and a black hole");
        ctx.assertTrue(TestbedStock.fill(level, abs, "fuel") && contains((Container) level.getBlockEntity(abs), Items.LAVA_BUCKET) && contains((Container) level.getBlockEntity(abs), Items.COAL_BLOCK), "The fuel chest holds lava and coal");
        ctx.assertTrue(TestbedStock.fill(level, abs, "treasure"), "The treasure chest takes the vessel treasure table");
        ctx.succeed();
    }

    private static boolean contains(Container chest, net.minecraft.world.item.Item item)
    {
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            if (chest.getItem(slot).is(item))
                return true;
        return false;
    }
}
