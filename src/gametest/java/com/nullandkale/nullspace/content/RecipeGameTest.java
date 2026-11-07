package com.nullandkale.nullspace.content;

import com.nullandkale.nullspace.dev.RecipeLab;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.SurvivalCrafting;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class RecipeGameTest {
  private static final Map<String, String> PATTERNS =
      Map.ofEntries(
          Map.entry("field_guide", "Y../C../..."),
          Map.entry("ship_beacon", "GTG/CKC/SHS"),
          Map.entry("machine_casing", "ICI/C.C/ICI"),
          Map.entry("circuit_board", "CTC/RWR/CTC"),
          Map.entry("computer_base", "GGG/BHB/CTC"),
          Map.entry("pad_computer", "WWW/CKC/STS"),
          Map.entry("computer_terminal", "GGG/BKB/CTC"),
          Map.entry("local_scanner", "IGI/RKR/CTC"),
          Map.entry("ship_computer", "WGW/CKC/WTW"),
          Map.entry("camera", "IGI/CBC/.H."),
          Map.entry("monitor", "GGG/GBG/CHC"),
          Map.entry("engine", "IPI/CHC/SFS"),
          Map.entry("generator", "ITI/CFC/SHS"),
          Map.entry("battery", "CRC/CHC/IRI"),
          Map.entry("air_supply", "IGI/CPC/BHB"),
          Map.entry("weapon", "III/PHP/CDC"),
          Map.entry("space_helmet", "III/IGI/.C."),
          Map.entry("space_chestplate", "I.I/ICI/IBI"),
          Map.entry("space_leggings", "ICI/I.I/I.I"),
          Map.entry("space_boots", "I.I/C.C/..."));

  private static List<ItemStack> grid(String name, boolean mirror, boolean mixed) {
    var symbols =
        Map.ofEntries(
            Map.entry('Y', Items.BOOK),
            Map.entry('I', Items.IRON_INGOT),
            Map.entry('C', Items.COPPER_INGOT),
            Map.entry('G', Items.GLASS),
            Map.entry('W', Items.OAK_PLANKS),
            Map.entry('S', Items.COBBLESTONE),
            Map.entry('R', Items.REDSTONE),
            Map.entry('T', Items.REDSTONE_TORCH),
            Map.entry('B', ModBlocks.CIRCUIT_BOARD),
            Map.entry('H', ModBlocks.MACHINE_CASING),
            Map.entry('K', ModBlocks.COMPUTER_BASE.asItem()),
            Map.entry('P', Items.PISTON),
            Map.entry('F', Items.FURNACE),
            Map.entry('L', Items.LEVER),
            Map.entry('D', Items.DISPENSER),
            Map.entry('X', ModBlocks.LOCAL_SCANNER.asItem()));
    var rows = PATTERNS.get(name).split("/");
    var result = new ArrayList<ItemStack>();
    for (int y = 0; y < 3; y++)
      for (int x = 0; x < 3; x++) {
        char c = rows[y].charAt(mirror ? 2 - x : x);
        var item = symbols.get(c);
        if (mixed && c == 'W' && (x + y) % 2 == 0) item = Items.BIRCH_PLANKS;
        if (mixed && c == 'S' && (x + y) % 2 == 0) item = Items.COBBLED_DEEPSLATE;
        result.add(c == '.' ? ItemStack.EMPTY : new ItemStack(item));
      }
    return result;
  }

  @GameTest
  public void shapedRecipesUnlockConsumeAndSurviveMining(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var server = level.getServer();
    var player = ctx.makeMockServerPlayerInLevel();
    for (var item :
        List.of(
            Items.BOOK,
            Items.IRON_INGOT,
            Items.REDSTONE,
            ModBlocks.CIRCUIT_BOARD,
            ModBlocks.MACHINE_CASING,
            ModBlocks.COMPUTER_BASE.asItem())) {
      player.getInventory().add(new ItemStack(item));
      CriteriaTriggers.INVENTORY_CHANGED.trigger(
          player, player.getInventory(), new ItemStack(item));
    }
    for (var entry : PATTERNS.entrySet()) {
      String name = entry.getKey();
      if (ContentCatalog.equipment(name).map(d -> d.has("retired")).orElse(false)) continue;
      var recipe = RecipeLab.recipe(server, name);
      var key =
          ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("nullspace", name));
      ctx.assertTrue(player.getRecipeBook().contains(key), "Recipe book must unlock " + name);
      for (boolean mirror : new boolean[] {false, true})
        for (boolean mixed : new boolean[] {false, true}) {
          var input = CraftingInput.of(3, 3, grid(name, mirror, mixed));
          ctx.assertTrue(recipe.matches(input, level), "Expected shaped/tagged input for " + name);
          var matches =
              server.getRecipeManager().getRecipes().stream()
                  .filter(
                      r ->
                          r.value() instanceof CraftingRecipe crafting
                              && crafting.matches(input, level))
                  .toList();
          ctx.assertTrue(
              matches.size() == 1 && matches.getFirst().id().equals(key),
              "Recipe must not collide with native recipes: " + name);
          var output = recipe.assemble(input);
          ctx.assertTrue(
              BuiltInRegistries.ITEM.getKey(output.getItem()).equals(key.identifier())
                  && output.getCount() == (name.equals("circuit_board") ? 4 : 1),
              "Exact output for " + name);
        }
      var wrong = grid(name, false, false);
      int occupied = 0;
      while (wrong.get(occupied).isEmpty()) occupied++;
      wrong.set(occupied, new ItemStack(Items.DIRT));
      ctx.assertTrue(
          !recipe.matches(CraftingInput.of(3, 3, wrong), level),
          "Wrong material must fail " + name);
      var holes = grid(name, false, false);
      for (int i = 0; i < holes.size(); i++)
        if (holes.get(i).isEmpty()) {
          holes.set(i, new ItemStack(Items.DIRT));
          ctx.assertTrue(
              !recipe.matches(CraftingInput.of(3, 3, holes), level),
              "Pattern holes must remain empty: " + name);
          break;
        }
      var result = recipe.assemble(CraftingInput.EMPTY);
      if (result.getItem() instanceof BlockItem blockItem) {
        var drops =
            Block.getDrops(
                blockItem.getBlock().defaultBlockState(),
                level,
                ctx.absolutePos(BlockPos.ZERO),
                null,
                player,
                new ItemStack(Items.IRON_PICKAXE));
        ctx.assertTrue(
            drops.size() == 1
                && drops.getFirst().is(result.getItem())
                && drops.getFirst().getCount() == 1,
            "Equipment must be recoverable when mined: " + name);
      }
    }
    var at = ctx.absolutePos(new BlockPos(14000, 64, 14000));
    level.setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), 18);
    player.teleportTo(at.getX(), at.getY() + 1, at.getZ());
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    SurvivalCrafting.starter(player, at);
    var base = at.offset(8, 0, 0);
    var controller = ShipTestFixture.build(level, base);
    level.setBlock(base.offset(1, 3, 6), ModBlocks.COMPUTER_BASE.defaultBlockState(), 18);
    HullScan.inspect(
        level,
        controller,
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7),
        true);
    ctx.assertTrue(
        !ModBlocks.navigation(ModBlocks.COMPUTER_BASE.defaultBlockState()),
        "Base must remain unconfigured");
    ctx.succeed();
  }

  @GameTest
  public void recipeChestsFollowLoadedDataAndPreserveEdits(GameTestHelper ctx)
      throws java.io.IOException {
    var server = ctx.getLevel().getServer();
    var gallery = RecipeLab.ensure(server);
    var level = server.overworld();
    for (int i = 0; i < RecipeLab.RECIPES.size(); i++) {
      var name = RecipeLab.RECIPES.get(i);
      var at = gallery.chest(i);
      var chest = (ChestBlockEntity) level.getBlockEntity(at);
      var input = new ArrayList<ItemStack>();
      for (int slot : RecipeLab.GRID_SLOTS) input.add(chest.getItem(slot).copy());
      var recipe = RecipeLab.recipe(server, name);
      ctx.assertTrue(
          recipe.matches(CraftingInput.of(3, 3, input), level), "Chest pattern must craft " + name);
      ctx.assertTrue(
          ItemStack.matches(
              recipe.assemble(CraftingInput.EMPTY), chest.getItem(RecipeLab.OUTPUT_SLOT)),
          "Chest must show exact result and batch count: " + name);
      ctx.assertTrue(
          chest.getCustomName() != null
              && level.getBlockState(at.offset(2, 0, 0)).is(Blocks.CRAFTING_TABLE)
              && level.getBlockState(at.above()).isAir(),
          "Displays must be named, openable and have a crafting table");
    }
    var first = (ChestBlockEntity) level.getBlockEntity(gallery.chest(0));
    var output = first.removeItemNoUpdate(RecipeLab.OUTPUT_SLOT);
    var second = gallery.chest(1).offset(2, 0, 0);
    var state = level.getBlockState(second);
    try {
      level.removeBlockEntity(second);
      level.setBlock(second, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
      ctx.assertTrue(
          RecipeLab.ensure(server).equals(gallery), "Repeat visits must reuse the saved gallery");
      ctx.assertTrue(
          first.getItem(RecipeLab.OUTPUT_SLOT).isEmpty()
              && level.getBlockState(second).is(Blocks.GOLD_BLOCK),
          "Repeat visits must not refill or regenerate displays");
    } finally {
      first.setItem(RecipeLab.OUTPUT_SLOT, output);
      level.setBlock(second, state, 18);
    }
    ctx.succeed();
  }
}
