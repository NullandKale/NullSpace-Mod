package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.SpaceMaterials;
import com.nullandkale.nullspace.gameplay.boss.SpaceBoss;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;
import com.nullandkale.nullspace.gameplay.trees.GoldenTrees;
import com.nullandkale.nullspace.worldgen.encounters.SiteLoot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What the endgame testbed ship's chests hold (see {@code docs/design/endgame-testbed.md}). The ship's rooms mark each chest as a {@code stock}
 * point with a kind, and the room entities pass fills it when the ship is delivered: netherite gear and totems, the late game's magic,
 * materials, food, this mod's own space items, seeds and fuel, and a chest of treasure from the vessel loot tables.
 */
public final class TestbedStock
{
    /** The kinds of chest the ship has. */
    public static final List<String> KINDS = List.of("gear", "magic", "materials", "food", "space", "seeds", "fuel", "treasure");

    private static void add(List<ItemStack> out, Item item, int count)
    {
        var stack = new ItemStack(item);
        stack.setCount(Math.min(count, stack.getMaxStackSize()));
        out.add(stack);
    }

    /** The stacks of a kind of chest, at most 27 (a chest's slots); empty for treasure, which is a loot table, and for a kind that does not exist. */
    public static List<ItemStack> items(String kind)
    {
        var out = new ArrayList<ItemStack>();
        switch (kind)
        {
            case "gear" ->
            {
                for (var item : List.of(Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Items.ELYTRA, Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.MACE, Items.FISHING_ROD, Items.SHEARS, Items.FLINT_AND_STEEL, Items.SPYGLASS))
                    add(out, item, 1);
                add(out, Items.TOTEM_OF_UNDYING, 3);
                add(out, Items.ARROW, 64);
                add(out, Items.ARROW, 64);
                add(out, Items.FIREWORK_ROCKET, 64);
            }
            case "magic" ->
            {
                add(out, Items.ENDER_PEARL, 16);
                add(out, Items.ENDER_EYE, 12);
                add(out, Items.BLAZE_ROD, 16);
                add(out, Items.BLAZE_POWDER, 32);
                add(out, Items.NETHER_STAR, 2);
                add(out, Items.EXPERIENCE_BOTTLE, 64);
                add(out, Items.EXPERIENCE_BOTTLE, 64);
                add(out, Items.LAPIS_LAZULI, 64);
                add(out, Items.LAPIS_LAZULI, 64);
                add(out, Items.BOOK, 64);
                add(out, Items.ENCHANTED_GOLDEN_APPLE, 4);
                add(out, Items.GOLDEN_APPLE, 16);
                add(out, Items.GHAST_TEAR, 8);
                add(out, Items.NETHER_WART, 64);
                add(out, Items.GLISTERING_MELON_SLICE, 16);
                add(out, Items.SPIDER_EYE, 16);
                add(out, Items.GLASS_BOTTLE, 32);
                add(out, Items.ECHO_SHARD, 4);
                add(out, Items.AMETHYST_SHARD, 32);
                add(out, Items.NETHERITE_SCRAP, 16);
                add(out, Items.ANVIL, 2);
            }
            case "materials" ->
            {
                add(out, Items.DIAMOND, 64);
                add(out, Items.EMERALD, 64);
                add(out, Items.NETHERITE_INGOT, 16);
                add(out, Items.DIAMOND_BLOCK, 8);
                add(out, Items.GOLD_BLOCK, 8);
                add(out, Items.IRON_BLOCK, 16);
                add(out, Items.IRON_INGOT, 64);
                add(out, Items.COPPER_INGOT, 64);
                add(out, Items.REDSTONE, 64);
                add(out, Items.REDSTONE_BLOCK, 16);
                add(out, Items.OBSIDIAN, 64);
                add(out, Items.GLASS, 64);
                add(out, Items.QUARTZ, 64);
                add(out, Items.COBBLESTONE, 64);
                add(out, Items.OAK_PLANKS, 64);
                add(out, Items.STONE_BRICKS, 64);
                add(out, Items.SOUL_SAND, 64);
                add(out, Items.HOPPER, 8);
                add(out, Items.DISPENSER, 4);
                add(out, Items.ENCHANTING_TABLE, 1);
            }
            case "food" ->
            {
                add(out, Items.COOKED_BEEF, 64);
                add(out, Items.COOKED_PORKCHOP, 64);
                add(out, Items.COOKED_CHICKEN, 64);
                add(out, Items.BREAD, 64);
                add(out, Items.GOLDEN_CARROT, 64);
                add(out, Items.HONEY_BOTTLE, 16);
                add(out, Items.APPLE, 32);
            }
            case "space" ->
            {
                add(out, ModBlocks.SYSTEM_UPGRADE, 16);
                add(out, ModBlocks.ADVANCED_UPGRADE, 16);
                add(out, ModBlocks.EXOTIC_UPGRADE, 16);
                add(out, ModBlocks.CREATIVE_UPGRADE, 1);
                add(out, ModBlocks.AIR_CANISTER, 16);
                add(out, ModBlocks.ARMORED_HELMET, 1);
                add(out, ModBlocks.ARMORED_CHEST, 1);
                add(out, ModBlocks.ARMORED_LEGS, 1);
                add(out, ModBlocks.ARMORED_BOOTS, 1);
                add(out, ModBlocks.MACHINE_CASING, 32);
                add(out, ModBlocks.CIRCUIT_BOARD, 32);
                add(out, ModBlocks.GENERATOR.asItem(), 4);
                add(out, ModBlocks.BATTERY.asItem(), 4);
                add(out, ModBlocks.AIR_SUPPLY.asItem(), 4);
                add(out, ModBlocks.ENGINE.asItem(), 2);
                add(out, ModBlocks.LOCAL_SCANNER.asItem(), 2);
                add(out, DefenceBlocks.SALVAGE_TURRET.asItem(), 4);
                add(out, DefenceBlocks.REPAIR_BAY.asItem(), 2);
                add(out, SpaceMaterials.GREEN_GEM, 64);
                add(out, SpaceMaterials.GREEN_GEM_BLOCK.asItem(), 8);
                add(out, SpaceBoss.BLACK_HOLE, 1);
                add(out, SpaceBoss.BEACON_HELMET, 1);
                add(out, GoldenTrees.SAPLING.asItem(), 9);
                add(out, GoldenTrees.ENCHANTED_SAPLING.asItem(), 1);
            }
            case "seeds" ->
            {
                add(out, Items.WHEAT_SEEDS, 64);
                add(out, Items.COCOA_BEANS, 64);
                add(out, Items.SUGAR_CANE, 32);
                add(out, Items.CARROT, 32);
                add(out, Items.POTATO, 32);
                add(out, Items.BEETROOT_SEEDS, 32);
                add(out, Items.MELON_SEEDS, 32);
                add(out, Items.PUMPKIN_SEEDS, 32);
                add(out, Items.NETHER_WART, 32);
                add(out, Items.BONE_MEAL, 64);
                add(out, Items.WATER_BUCKET, 4);
            }
            case "fuel" ->
            {
                add(out, Items.LAVA_BUCKET, 4);
                add(out, Items.BUCKET, 8);
                add(out, Items.COAL_BLOCK, 64);
                add(out, Items.COAL_BLOCK, 64);
                add(out, Items.COAL, 64);
            }
            default ->
                {
                }
        }
        return out;
    }

    /** Fills the chest at {@code at} with a kind of stock, or gives it the vessel treasure table; false when there is no chest there. */
    public static boolean fill(ServerLevel level, BlockPos at, String kind)
    {
        if (kind.equals("treasure"))
            return SiteLoot.assign(level, at, "treasure_3", at.asLong(), "Testbed treasure");
        if (!(level.getBlockEntity(at) instanceof Container chest))
            return false;
        var stacks = items(kind);
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            chest.setItem(slot, slot < stacks.size() ? stacks.get(slot) : ItemStack.EMPTY);
        chest.setChanged();
        return !stacks.isEmpty();
    }

    private TestbedStock()
    {
    }
}
