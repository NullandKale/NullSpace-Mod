package com.nullandkale.nullspace.content.machine;

import com.nullandkale.nullspace.content.ModBlocks;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;

/** A machine's tier is the number of upgrades, capped by the best: far materials unlock 2 and 3. */
public final class UpgradeTierGameTest
{
    @GameTest
    public void upgradeTiersNeedAmethystThenNetheriteOrEchoShards(GameTestHelper ctx)
    {
        var at = ctx.absolutePos(new BlockPos(1, 1, 1));
        ctx.getLevel().setBlock(at, ModBlocks.ENGINE.defaultBlockState(), 3);
        var engine = (MachineBlockEntity) ctx.getLevel().getBlockEntity(at);
        var plain = new ItemStack(ModBlocks.SYSTEM_UPGRADE);
        var advanced = new ItemStack(ModBlocks.ADVANCED_UPGRADE);
        var exotic = new ItemStack(ModBlocks.EXOTIC_UPGRADE);
        record Case(List<ItemStack> items, int tier, String why)
        {}
        for (var c : List.of(new Case(List.of(plain, plain, plain), 1, "Three plain upgrades stay tier 1"), new Case(List.of(advanced), 1, "One upgrade of any kind is tier 1"), new Case(List.of(plain, advanced, plain), 2, "An advanced upgrade unlocks tier 2"), new Case(List.of(advanced, advanced, advanced), 2, "Tier 3 needs an exotic upgrade"), new Case(List.of(plain, plain, exotic), 3, "An exotic upgrade unlocks tier 3")))
        {
            for (int i = 0; i < 3; i++)
                engine.setItem(i, i < c.items().size() ? c.items().get(i).copy() : ItemStack.EMPTY);
            ctx.assertTrue(engine.tier() == c.tier(), c.why() + ", was " + engine.tier());
        }
        ctx.assertTrue(engine.canPlaceItem(0, exotic), "Upgrade slots accept every upgrade");
        ctx.assertTrue(!engine.canPlaceItem(0, new ItemStack(Items.AMETHYST_SHARD)), "Not raw materials");
        for (var id : List.of("advanced_system_upgrade", "exotic_system_upgrade", "exotic_system_upgrade_from_echo_shard"))
            ctx.assertTrue(ctx.getLevel().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse("nullspace:" + id))).isPresent(), "Recipe exists: " + id);
        ctx.succeed();
    }
}
