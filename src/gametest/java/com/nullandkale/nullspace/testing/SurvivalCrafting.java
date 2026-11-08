package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;

/** Crafts through native menus, paying for every ingredient from a finite survival inventory. */
public final class SurvivalCrafting
{
    private final ServerPlayer player;
    private final BlockPos table;

    public SurvivalCrafting(ServerPlayer player, BlockPos table)
    {
        this.player = player;
        this.table = table;
    }

    public void ensure(Item item, int count)
    {
        while (player.getInventory().countItem(item) < count)
        {
            var id = BuiltInRegistries.ITEM.getKey(item);
            var recipe = player.level().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, id)).orElseThrow(() -> new AssertionError("Insufficient raw material: " + id));
            if (!(recipe.value() instanceof ShapedRecipe shaped))
                throw new AssertionError("Insufficient raw material or unexpected recipe: " + id);
            Map<Item, Integer> needed = new LinkedHashMap<>();
            for (var ingredient : shaped.getIngredients())
                if (ingredient.isPresent())
                    needed.merge(representative(ingredient.get()), 1, Integer::sum);
            for (var entry : needed.entrySet())
                ensure(entry.getKey(), entry.getValue());
            var menu = new CraftingMenu(71, player.getInventory(), ContainerLevelAccess.create(player.level(), table));
            int before = player.getInventory().countItem(item);
            for (int y = 0; y < shaped.getHeight(); y++)
                for (int x = 0; x < shaped.getWidth(); x++)
                {
                    var ingredient = shaped.getIngredients().get(y * shaped.getWidth() + x);
                    if (ingredient.isPresent())
                        menu.getSlot(1 + y * 3 + x).set(take(player, representative(ingredient.get())));
                }
            menu.slotsChanged(menu.getInputGridSlots().getFirst().container);
            var output = menu.getResultSlot().getItem().copy();
            if (!output.is(item) || output.getCount() != shaped.assemble(CraftingInput.EMPTY).getCount())
                throw new AssertionError("Native crafting result missing: " + id);
            menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
            if (player.getInventory().countItem(item) != before + output.getCount())
                throw new AssertionError("Crafting output duplicated or lost: " + id);
            if (menu.getInputGridSlots().stream().anyMatch(slot -> !slot.getItem().isEmpty()) || !menu.getResultSlot().getItem().isEmpty())
                throw new AssertionError("Crafting must consume each ingredient exactly once: " + id);
            menu.removed(player);
        }
    }

    public static ItemStack take(ServerPlayer player, Item item)
    {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (player.getInventory().getItem(slot).is(item))
                return player.getInventory().removeItem(slot, 1);
        throw new AssertionError("Missing paid ingredient: " + BuiltInRegistries.ITEM.getKey(item));
    }

    private static Item representative(Ingredient ingredient)
    {
        for (var item : List.of(Items.OAK_PLANKS, Items.COBBLESTONE))
            if (ingredient.test(new ItemStack(item)))
                return item;
        return ingredient.items().findFirst().orElseThrow().value();
    }

    public static void starter(ServerPlayer player, BlockPos table)
    {
        player.getInventory().clearContent();
        Item[] items = {Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE, Items.GLASS, Items.COBBLESTONE, Items.OAK_PLANKS};
        int[] counts = {34, 50, 17, 8, 30, 21};
        for (int i = 0; i < items.length; i++)
            player.getInventory().add(new ItemStack(items[i], counts[i]));
        var crafting = new SurvivalCrafting(player, table);
        for (var item : List.of(PadComputers.BLOCK.asItem(), ModBlocks.COMPUTER.asItem(), ModBlocks.ENGINE.asItem(), ModBlocks.GENERATOR.asItem(), ModBlocks.BATTERY.asItem(), ModBlocks.AIR_SUPPLY.asItem()))
            crafting.ensure(item, 1);
        for (var item : items)
            if (player.getInventory().countItem(item) != 0)
                throw new AssertionError("Starter bill did not consume expected raw material: " + BuiltInRegistries.ITEM.getKey(item));
        if (player.getInventory().countItem(ModBlocks.CIRCUIT_BOARD) != 2 || player.getInventory().countItem(Items.STICK) != 3)
            throw new AssertionError("Starter batch leftovers must be two boards and three sticks");
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 19));
        player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 5));
        player.getInventory().add(new ItemStack(Items.GLASS));
        for (var item : List.of(ModBlocks.HELMET, ModBlocks.CHEST, ModBlocks.LEGS, ModBlocks.BOOTS))
            crafting.ensure(item, 1);
        for (var item : items)
            if (player.getInventory().countItem(item) != 0)
                throw new AssertionError("Suit bill did not consume expected raw material");
        if (player.getInventory().countItem(ModBlocks.CIRCUIT_BOARD) != 1)
            throw new AssertionError("Suit chestplate must use the spare board");
    }
}
