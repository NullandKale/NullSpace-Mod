package com.nullandkale.nullspace.generation;

import com.nullandkale.nullspace.navigation.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/** Site loot tables load, fill containers on first opening, and scale with danger. */
public final class SiteLootGameTest {
  private static final List<String> TABLES =
      List.of(
          "engineering", "galley", "cargo", "workshop", "crew", "garden", "treasure_0",
          "treasure_1", "treasure_2", "treasure_3", "treasure_singularity");

  @GameTest
  public void siteTablesLoadFillContainersAndTreasureFollowsDanger(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    for (var name : TABLES) {
      var key =
          ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("nullspace:site/" + name));
      ctx.assertTrue(
          server.reloadableRegistries().getLootTable(key) != LootTable.EMPTY,
          "Loot table loads: " + name);
    }
    var at = ctx.absolutePos(new BlockPos(1, 1, 1));
    ctx.getLevel().setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
    for (long seed = 0; seed < 4; seed++) {
      ctx.assertTrue(
          SiteLoot.assign(ctx.getLevel(), at, "engineering", seed, "Engine room supplies"),
          "A chest takes a site loot table");
      var chest = (ChestBlockEntity) ctx.getLevel().getBlockEntity(at);
      chest.unpackLootTable(null);
      int fuel = 0;
      for (int i = 0; i < chest.getContainerSize(); i++)
        if (chest.getItem(i).is(Items.COAL) || chest.getItem(i).is(Items.CHARCOAL))
          fuel += chest.getItem(i).getCount();
      ctx.assertTrue(fuel > 0, "Engine rooms always hold some fuel");
      ctx.assertTrue(
          chest.getCustomName() != null
              && chest.getCustomName().getString().equals("Engine room supplies"),
          "The container is named for its room");
    }
    var galaxy = StarSystems.galaxy(server);
    ctx.assertTrue(
        SiteLoot.treasure(server, LocalSpaces.HOME).equals("treasure_0"),
        "Sol's derelicts hold everyday treasure");
    for (var f : galaxy.features()) {
      String expected =
          f.type() == GalaxyPlan.FeatureType.BLACK_HOLE || f.type() == GalaxyPlan.FeatureType.PULSAR
              ? "treasure_singularity"
              : "treasure_" + GalaxyPlan.danger(galaxy, f.id());
      ctx.assertTrue(
          SiteLoot.treasure(server, f.id()).equals(expected), "Treasure follows danger at " + f.id());
    }
    ctx.succeed();
  }
}
