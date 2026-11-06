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
  @GameTest(environment = "nullspace:hardware_retirement", maxTicks = 100)
  public void loadedRetiredHardwareBecomesIronWithoutChangingCurrentEquipmentOrLosingModules(
      GameTestHelper ctx) {
    var level = ctx.getLevel();
    var origin = ctx.absolutePos(new BlockPos(50000, 80, 50000));
    for (var at : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(12, -1, 6)))
      level.setBlock(at, Blocks.STONE.defaultBlockState(), 18);
    var retired =
        List.of(
            ShipBlocks.HELM,
            ShipBlocks.SYSTEMS_CONTROLLER,
            ShipBlocks.DOOR_CONTROL,
            ShipBlocks.LONG_RANGE_SCANNER,
            ShipBlocks.DOCKING_PORT,
            ShipBlocks.DOOR_CONNECTOR);
    var active =
        List.of(
            ShipBlocks.COMPUTER,
            ShipBlocks.GENERATOR,
            ShipBlocks.ENGINE,
            ShipBlocks.BATTERY,
            ShipBlocks.LOCAL_SCANNER,
            ShipBlocks.AIR_SUPPLY,
            ShipBlocks.CAMERA,
            ShipBlocks.MONITOR,
            ShipBlocks.DOCKING_BASE);
    var chunks = new HashSet<net.minecraft.world.level.chunk.LevelChunk>();
    for (int i = 0; i < retired.size(); i++) {
      var at = origin.offset(i, 0, 0);
      level.setBlock(at, retired.get(i).defaultBlockState(), 18);
      chunks.add(level.getChunkAt(at));
    }
    ((MachineEntity) level.getBlockEntity(origin.offset(3, 0, 0)))
        .setItem(0, new ItemStack(ShipBlocks.SYSTEM_UPGRADE));
    for (int i = 0; i < active.size(); i++) {
      var at = origin.offset(i, 1, 2);
      level.setBlock(at, active.get(i).defaultBlockState(), 18);
      chunks.add(level.getChunkAt(at));
    }
    // Exercise the real lifecycle subscription, including repeated loads and multiple sections.
    for (var chunk : chunks) {
      level.setChunkForced(chunk.getPos().x(), chunk.getPos().z(), true);
      ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, false);
    }
    ctx.runAfterDelay(
        12,
        () -> {
          for (int i = 0; i < retired.size(); i++)
            ctx.assertTrue(
                level.getBlockState(origin.offset(i, 0, 0)).is(Blocks.IRON_BLOCK),
                "Retired hardware loads as solid iron: " + retired.get(i));
          for (int i = 0; i < active.size(); i++)
            ctx.assertTrue(
                level.getBlockState(origin.offset(i, 1, 2)).is(active.get(i)),
                "Active hardware is preserved: " + active.get(i));
          for (var chunk : chunks)
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, false);
        });
    ctx.runAfterDelay(
        50,
        () -> {
          int modules =
              ShipEntities.inBounds(
                      level,
                      new ShipScan.Bounds(origin.offset(-16, -16, -16), origin.offset(16, 16, 16)))
                  .stream()
                  .filter(ItemEntity.class::isInstance)
                  .map(ItemEntity.class::cast)
                  .filter(e -> e.getItem().is(ShipBlocks.SYSTEM_UPGRADE))
                  .mapToInt(e -> e.getItem().getCount())
                  .sum();
          ctx.assertTrue(
              modules == 1,
              "Installed module drops exactly once across repeated loads; found " + modules);
          ctx.succeed();
        });
  }

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
