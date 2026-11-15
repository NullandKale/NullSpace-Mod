package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.model.galaxy.EncounterKind;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The loot budget a player lives with, checked through the game's own stocking decision and real rolled chests: how much of
 * the top end a found vessel holds, where it sits, and how many rooms give the high tier. The loot survey measures these;
 * this makes them fail when they drift.
 */
public final class LootBudgetGameTest
{
    private static final Set<String> TOP = Set.of("nullspace:system_upgrade", "nullspace:advanced_system_upgrade", "minecraft:totem_of_undying", "minecraft:netherite_scrap", "minecraft:echo_shard", "minecraft:enchanted_golden_apple", "minecraft:netherite_upgrade_smithing_template");
    private static final int VESSELS = 24;

    @GameTest(maxTicks = 6000)
    public void aFoundVesselKeepsTheTopEndInItsTreasureChestAndFollowsItsTierPlan(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var chestAt = ctx.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
        var random = new Random(0xB0D6E7L);
        int[] vessels = new int[4], advanced = new int[4], totems = new int[4], anyTop = new int[4];
        for (var kind : List.of(EncounterKind.DAMAGED_FREIGHTER, EncounterKind.SALVAGE_SHIP, EncounterKind.ABANDONED_STATION))
            for (int i = 0; i < VESSELS; i++)
            {
                long seed = random.nextLong();
                var plan = EncounterSites.plan(kind.id, seed);
                String design = EncounterSites.design(kind.id, seed);
                int chests = plan.points("roomloot").size();
                for (int danger = 0; danger <= 3; danger++)
                {
                    var assignments = EncounterSites.assignments(kind.id, design, BlockPos.ZERO, plan.points(), "treasure_" + danger, danger, seed, "Budget");
                    String label = kind.label + " seed " + seed + " danger " + danger;
                    ctx.assertTrue(assignments.size() == chests, label + ": one chest per room, " + assignments.size() + " for " + chests);
                    int top = 0, high = 0, rooms = 0;
                    var found = new HashSet<String>();
                    for (var a : assignments)
                    {
                        boolean room = a.table().startsWith("derelict/");
                        if (room)
                        {
                            rooms++;
                            high += a.table().endsWith("_3") ? 1 : 0;
                            ctx.assertTrue(a.table().endsWith("_" + Math.min(danger, 3)) || danger == 3 && (a.table().endsWith("_2") || a.table().endsWith("_3")), label + ": " + a.table() + " is not a tier of this danger");
                        }
                        var chest = (ChestBlockEntity) level.getBlockEntity(chestAt);
                        chest.clearContent();
                        SiteLoot.assign(level, chestAt, a.table(), a.seed(), a.name());
                        chest = (ChestBlockEntity) level.getBlockEntity(chestAt);
                        chest.unpackLootTable(null);
                        for (int slot = 0; slot < chest.getContainerSize(); slot++)
                        {
                            var stack = chest.getItem(slot);
                            if (stack.isEmpty())
                                continue;
                            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                            if (TOP.contains(id))
                            {
                                ctx.assertTrue(!room, label + ": the room chest " + a.table() + " holds the top-end item " + id);
                                top += stack.getCount();
                                found.add(id);
                            }
                        }
                    }
                    ctx.assertTrue(top <= 1, label + ": a vessel holds at most one top-end item, found " + top + " " + found);
                    if (danger == 0)
                        ctx.assertTrue(top == 0, label + ": a danger 0 vessel holds no top-end item, found " + found);
                    if (danger <= 2)
                    {
                        ctx.assertTrue(found.stream().allMatch(id -> id.equals("nullspace:system_upgrade") || id.equals("minecraft:shulker_shell")), label + ": below danger 3 the top end is only the basic upgrade, found " + found);
                        ctx.assertTrue(high == 0, label + ": only danger 3 has high-tier rooms");
                    }
                    else
                    {
                        // Four or five of the best rooms, fewer only where a small vessel has fewer than eight rooms besides the hoard.
                        ctx.assertTrue(high >= Math.min(4, rooms / 2) && high <= Math.min(5, rooms / 2), label + ": " + high + " high-tier rooms among " + rooms);
                    }
                    vessels[danger]++;
                    advanced[danger] += found.contains("nullspace:advanced_system_upgrade") ? 1 : 0;
                    totems[danger] += found.contains("minecraft:totem_of_undying") ? 1 : 0;
                    anyTop[danger] += top > 0 ? 1 : 0;
                }
            }
        // The shares the balance was set to, with room to spare: the advanced upgrade in about a quarter of danger 3 vessels, a totem in about one in twenty.
        ctx.assertTrue(advanced[3] * 100 <= vessels[3] * 45, "The advanced upgrade is in " + advanced[3] + " of " + vessels[3] + " danger 3 vessels");
        ctx.assertTrue(advanced[3] > 0, "The advanced upgrade can be found at danger 3");
        ctx.assertTrue(totems[3] * 100 <= vessels[3] * 20, "A totem is in " + totems[3] + " of " + vessels[3] + " danger 3 vessels");
        ctx.assertTrue(advanced[0] + advanced[1] + advanced[2] + totems[0] + totems[1] + totems[2] == 0, "Neither the advanced upgrade nor a totem appears below danger 3");
        ctx.succeed();
    }
}
