package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.dev.RecipeLab;
import com.nullandkale.nullspace.guide.GuideContent;
import com.nullandkale.nullspace.systems.MachineEntity;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.*;

public final class LegacyHardwareGameTest {
  @GameTest(environment = "nullspace:hardware_retirement")
  public void dockRecipeAcceptsEveryWoodenDoorAndGuideListsOnlyCurrentHardware(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var recipe = RecipeLab.recipe(level.getServer(), "docking_base");
    int doors = 0;
    for (var holder : BuiltInRegistries.ITEM.listElements().toList())
      if (holder.is(ItemTags.WOODEN_DOORS)) {
        doors++;
        var grid = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++)
          grid.add(
              new ItemStack(
                  i == 4
                      ? holder.value()
                      : i == 3 || i == 5 ? ShipBlocks.CIRCUIT_BOARD : Items.IRON_INGOT));
        var input = CraftingInput.of(3, 3, grid);
        ctx.assertTrue(recipe.matches(input, level), "Accept wooden door " + holder.key());
        ctx.assertTrue(
            recipe.assemble(input).is(ShipBlocks.DOCKING_BASE.asItem()), "Exact dock output");
        grid.set(4, new ItemStack(Items.IRON_DOOR));
        ctx.assertTrue(
            !recipe.matches(CraftingInput.of(3, 3, grid), level), "Metal door is not wood");
      }
    ctx.assertTrue(
        doors >= 12, "All vanilla wood families are present, including bamboo and Nether woods");
    for (String retired :
        List.of(
            "helm",
            "systems_controller",
            "door_control",
            "long_range_scanner",
            "door_connector",
            "docking_port")) {
      ctx.assertTrue(!GuideContent.RECIPES.contains(retired), "No retired guide recipe " + retired);
      var key =
          net.minecraft.resources.ResourceKey.create(
              net.minecraft.core.registries.Registries.RECIPE,
              net.minecraft.resources.Identifier.parse("nullspace:" + retired));
      ctx.assertTrue(
          level.getServer().getRecipeManager().byKey(key).isEmpty(), "No retired crafting recipe");
    }
    var shown =
        GuideContent.recipes(level.getServer()).stream()
            .filter(r -> r.name().equals("docking_base"))
            .findFirst()
            .orElseThrow();
    ctx.assertTrue(shown.cells().get(4).size() == doors, "Guide shows all accepted doors");
    ctx.succeed();
  }
}
