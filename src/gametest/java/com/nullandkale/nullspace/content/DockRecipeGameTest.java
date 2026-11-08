package com.nullandkale.nullspace.content;

import com.nullandkale.nullspace.content.guide.GuideContent;
import com.nullandkale.nullspace.dev.RecipeLab;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.*;

public final class DockRecipeGameTest
{
    @GameTest(environment = "nullspace:hardware_retirement")
    public void dockRecipeAcceptsEveryWoodenDoorAndGuideListsOnlyCurrentHardware(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var recipe = RecipeLab.recipe(level.getServer(), "docking_base");
        int doors = 0;
        for (var holder : BuiltInRegistries.ITEM.listElements().toList())
            if (holder.is(ItemTags.WOODEN_DOORS))
            {
                doors++;
                var grid = new ArrayList<ItemStack>();
                for (int i = 0; i < 9; i++)
                    grid.add(new ItemStack(i == 4 ? holder.value() : i == 3 || i == 5 ? ModBlocks.CIRCUIT_BOARD : Items.IRON_INGOT));
                var input = CraftingInput.of(3, 3, grid);
                ctx.assertTrue(recipe.matches(input, level), "Accept wooden door " + holder.key());
                ctx.assertTrue(recipe.assemble(input).is(ModBlocks.DOCKING_BASE.asItem()), "Exact dock output");
                grid.set(4, new ItemStack(Items.IRON_DOOR));
                ctx.assertTrue(!recipe.matches(CraftingInput.of(3, 3, grid), level), "Metal door is not wood");
            }
        ctx.assertTrue(doors >= 12, "All vanilla wood families are present, including bamboo and Nether woods");
        var shown = GuideContent.recipes(level.getServer()).stream().filter(r -> r.name().equals("docking_base")).findFirst().orElseThrow();
        ctx.assertTrue(shown.cells().get(4).size() == doors, "Guide shows all accepted doors");
        ctx.succeed();
    }
}
