package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

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
public final class SiteLootGameTest
{
    private static final List<String> TABLES = List.of("engineering", "galley", "cargo", "workshop", "crew", "garden", "treasure_0", "treasure_1", "treasure_2", "treasure_3", "treasure_singularity");

    @GameTest
    public void siteTablesLoadFillContainersAndTreasureFollowsDanger(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        for (var name : TABLES)
        {
            var key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("nullspace:site/" + name));
            ctx.assertTrue(server.reloadableRegistries().getLootTable(key) != LootTable.EMPTY, "Loot table loads: " + name);
        }
        var at = ctx.absolutePos(new BlockPos(1, 1, 1));
        ctx.getLevel().setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        for (long seed = 0; seed < 4; seed++)
        {
            ctx.assertTrue(SiteLoot.assign(ctx.getLevel(), at, "engineering", seed, "Engine room supplies"), "A chest takes a site loot table");
            var chest = (ChestBlockEntity) ctx.getLevel().getBlockEntity(at);
            chest.unpackLootTable(null);
            int fuel = 0;
            for (int i = 0; i < chest.getContainerSize(); i++)
                if (chest.getItem(i).is(Items.COAL) || chest.getItem(i).is(Items.CHARCOAL))
                    fuel += chest.getItem(i).getCount();
            ctx.assertTrue(fuel > 0, "Engine rooms always hold some fuel");
            ctx.assertTrue(chest.getCustomName() == null, "Generated labels do not become distinct dropped-container names");
            ctx.assertTrue(fuel <= 3, "Engineering supplies contain a small fuel reserve");
        }
        var drops = net.minecraft.world.level.block.Block.getDrops(ctx.getLevel().getBlockState(at), ctx.getLevel(), at, ctx.getLevel().getBlockEntity(at), null, new net.minecraft.world.item.ItemStack(Items.IRON_AXE));
        var droppedChest = drops.stream().filter(i -> i.is(Items.CHEST)).findFirst().orElseThrow();
        ctx.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(droppedChest, new net.minecraft.world.item.ItemStack(Items.CHEST)), "A dismantled generated chest stacks with a normal chest");
        var chest = (ChestBlockEntity) ctx.getLevel().getBlockEntity(at);
        chest.applyComponents(chest.collectComponents(), net.minecraft.core.component.DataComponentPatch.builder().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Player cargo")).build());
        SiteLoot.assign(ctx.getLevel(), at, "cargo", 10, "Default supplies");
        ctx.assertTrue(chest.getCustomName().getString().equals("Player cargo"), "Stocking leaves player-authored names intact");
        var galaxy = StarSystems.galaxy(server);
        ctx.assertTrue(SiteLoot.treasure(server, LocalSpaces.HOME).equals("treasure_0"), "Sol's derelicts hold everyday treasure");
        for (var f : galaxy.features())
        {
            String expected = f.type() == GalaxyPlan.FeatureType.BLACK_HOLE || f.type() == GalaxyPlan.FeatureType.PULSAR ? "treasure_singularity" : "treasure_" + GalaxyPlan.danger(galaxy, f.id());
            ctx.assertTrue(SiteLoot.treasure(server, f.id()).equals(expected), "Treasure follows danger at " + f.id());
        }
        ctx.succeed();
    }

    private static final List<String> ROOMS = List.of("bridge", "engineering", "stores_0", "stores_1", "stores_2", "maintenance", "commons", "kitchen", "observation", "archive", "workshop", "foundry", "cargo_hold", "receiving_bay", "growing_bay_0", "growing_bay_1", "garden_0", "garden_1", "lab", "cabin", "bunk_room", "operations", "armory", "lookout", "eva_prep", "infirmary", "brig");

    /** Every room's normal table and four derelict tiers load and fill a chest; a derelict room is always worth opening. */
    @GameTest(maxTicks = 200)
    public void roomTablesLoadAndEveryDerelictRoomHoldsSomethingWorthTaking(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var at = ctx.absolutePos(new BlockPos(1, 1, 1));
        ctx.getLevel().setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        var names = new ArrayList<String>();
        for (var room : ROOMS)
        {
            names.add("normal/" + room);
            for (int tier = 0; tier <= 3; tier++)
            {
                names.add("derelict/" + room + "_" + tier);
                if (List.of("bridge", "engineering", "maintenance", "operations").contains(room))
                    names.add("derelict/" + room + "_dead_" + tier);
            }
        }
        for (var name : names)
        {
            var key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("nullspace:site/" + name));
            ctx.assertTrue(server.reloadableRegistries().getLootTable(key) != LootTable.EMPTY, "Loot table loads: " + name);
            int most = 0;
            for (long seed = 0; seed < 6; seed++)
            {
                ctx.assertTrue(SiteLoot.assign(ctx.getLevel(), at, name, seed, "Test"), "A chest takes " + name);
                var chest = (ChestBlockEntity) ctx.getLevel().getBlockEntity(at);
                chest.unpackLootTable(null);
                int stacks = 0;
                for (int i = 0; i < chest.getContainerSize(); i++)
                    stacks += chest.getItem(i).isEmpty() ? 0 : 1;
                most = Math.max(most, stacks);
                if (name.startsWith("derelict/"))
                    ctx.assertTrue(stacks >= 2, name + " always holds something: " + stacks + " stacks, seed " + seed);
            }
            ctx.assertTrue(most >= 2, name + " gives a few stacks");
        }
        ctx.succeed();
    }
}
