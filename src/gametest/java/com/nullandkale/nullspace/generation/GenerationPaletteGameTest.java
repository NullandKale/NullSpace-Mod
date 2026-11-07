package com.nullandkale.nullspace.generation;

import com.nullandkale.nullspace.shipyard.ShipBlueprints;
import com.nullandkale.nullspace.station.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;

/** Generated builds never pay out valuable blocks when torn apart. */
public final class GenerationPaletteGameTest {
  private static void cheap(GameTestHelper ctx, String what, Map<BlockPos, BlockState> blocks) {
    var bad = GenerationPalette.violations(blocks);
    ctx.assertTrue(
        bad.isEmpty(),
        what
            + " uses valuable blocks: "
            + bad.stream()
                .limit(5)
                .map(p -> BuiltInRegistries.BLOCK.getKey(blocks.get(p).getBlock()) + " at " + p)
                .toList());
  }

  private static BlockState block(GameTestHelper ctx, String id) {
    var key = net.minecraft.resources.Identifier.parse("minecraft:" + id);
    ctx.assertTrue(BuiltInRegistries.BLOCK.containsKey(key), "No block " + id);
    return BuiltInRegistries.BLOCK.getValue(key).defaultBlockState();
  }

  @GameTest
  public void valuableStorageBlocksAreBannedAndEveryGeneratorIsCheap(GameTestHelper ctx) {
    for (var id :
        List.of(
            "iron_block",
            "gold_block",
            "diamond_block",
            "copper_block",
            "cut_copper",
            "waxed_oxidized_copper",
            "chiseled_copper",
            "coal_block"))
      ctx.assertTrue(!GenerationPalette.allowed(block(ctx, id)), "Banned: " + id);
    for (var id :
        List.of(
            "stone_bricks",
            "smooth_stone",
            "light_gray_concrete",
            "glass",
            "iron_door",
            "iron_trapdoor",
            "copper_bulb",
            "copper_door",
            "quartz_block"))
      ctx.assertTrue(GenerationPalette.allowed(block(ctx, id)), "Allowed: " + id);
    for (var preset : ShipBlueprints.PRESETS)
      cheap(ctx, preset.id(), ShipBlueprints.blocks(preset));
    cheap(ctx, "Manufacturer Exchange", ManufacturerStation.build().blocks());
    cheap(ctx, "Station foundation", StationBlueprint.foundation().blocks());
    for (int kind = 0; kind < com.nullandkale.nullspace.navigation.JourneyPlan.KINDS; kind++)
      for (long seed = 0; seed < 8; seed++)
        cheap(ctx, "Encounter " + kind + "/" + seed, EncounterSites.plan(kind, seed).blocks());
    ctx.succeed();
  }
}
