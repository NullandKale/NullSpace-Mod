package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.entity.Stowaways;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Every space mob on an airless planet drops salvage when a player kills it (and the humanoid ones wear the suit); elsewhere they are ordinary. */
public final class AirlessCrewsGameTest
{
    private static final List<EntityType<? extends Mob>> SPACE_MOBS = List.of(EntityTypes.ZOMBIE, EntityTypes.ZOMBIE_VILLAGER, EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON, Stowaways.GHOST_CREEPER, Stowaways.PALE_SPIDER, Stowaways.PALE_CAVE_SPIDER, Stowaways.PALE_SILVERFISH, Stowaways.VIOLET_SLIME, Stowaways.VIOLET_BLAZE, SiteThreats.REPAIR_BOT);

    /** Every chunk this class forces, so they can be released when a test ends. */
    private static final List<Object[]> FORCED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Releases the chunks in a range of chunk columns: the two tests run side by side, each on its own columns. */
    private static void release(int from, int to)
    {
        for (var forced : FORCED)
            if ((int) forced[1] >= from && (int) forced[1] <= to)
            {
                ((ServerLevel) forced[0]).setChunkForced((int) forced[1], 0, false);
                FORCED.remove(forced);
            }
    }

    private static Mob spawn(GameTestHelper ctx, ServerLevel level, EntityType<? extends Mob> type, int i)
    {
        TestWaits.hold(ctx, level, i >> 4, 0);
        FORCED.add(new Object[]{level, i >> 4});
        level.getChunk(i >> 4, 0);
        var mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.setPos(i + .5, 120, .5);
        mob.setNoGravity(true);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        return mob;
    }

    private static boolean suited(Mob mob)
    {
        var head = mob.getItemBySlot(EquipmentSlot.HEAD);
        return head.is(ModBlocks.HELMET) || head.is(ModBlocks.ARMORED_HELMET);
    }

    private static void kill(ServerLevel level, Mob mob, net.minecraft.server.level.ServerPlayer player)
    {
        mob.hurtServer(level, level.damageSources().playerAttack(player), 1000f);
    }

    /** What lay on the ground between two x positions once the kills had a tick to settle: iron, copper, redstone and glass, in that order. */
    private static int[] collect(ServerLevel level, double from, double to)
    {
        int[] found = new int[4];
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(from, 60, -4, to, 140, 5)))
        {
            var stack = item.getItem();
            if (stack.is(Items.IRON_INGOT))
                found[0] += stack.getCount();
            else if (stack.is(Items.COPPER_INGOT))
                found[1] += stack.getCount();
            else if (stack.is(Items.REDSTONE))
                found[2] += stack.getCount();
            else if (stack.is(Items.GLASS))
                found[3] += stack.getCount();
            item.discard();
        }
        return found;
    }

    /**
     * A mob is dressed and tagged when its chunk starts ticking it, which comes after the chunk loads on other threads: on a slow machine that is many ticks after
     * it was added. Waits until every chunk this test holds has its entities and every crew member is tagged; a crew left untagged would die an ordinary mob.
     */
    private static void whenLoaded(GameTestHelper ctx, java.util.Map<EntityType<? extends Mob>, List<Mob>> crews, Runnable then)
    {
        TestWaits.afterLoaded(ctx, 1, () -> TestWaits.until(ctx, () -> crews.values().stream().allMatch(list -> list.stream().allMatch(m -> m.entityTags().contains("nullspace_airless_crew"))), then));
    }

    @GameTest(environment = "nullspace:airless_crews", maxTicks = 400)
    public void everySpaceMobOnAnAirlessPlanetDropsSalvageAndTheCrewsWearTheSuitButOnTerraTheyDoNot(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var ares = server.getLevel(PlanetSurfaces.dimension(server, "sol_ares"));
        var terra = server.overworld();
        ctx.assertTrue(AirlessCrews.airless(ares) && !AirlessCrews.airless(terra), "Ares has no air and Terra has");
        var player = ctx.makeMockServerPlayerInLevel();
        // A mob is dressed when it finishes entering the level, a tick or so after it is added: spawn them all, wait, then look.
        int at = 0;
        var crews = new java.util.LinkedHashMap<EntityType<? extends Mob>, List<Mob>>();
        for (var type : SPACE_MOBS)
        {
            ctx.assertTrue(AirlessCrews.spaceMob(type), type + " counts as a space mob");
            var list = new java.util.ArrayList<Mob>();
            for (int n = 0; n < 12; n++)
                list.add(spawn(ctx, ares, type, at++));
            crews.put(type, list);
        }
        var ordinary = new java.util.ArrayList<Mob>();
        for (int n = 0; n < 40; n++)
            ordinary.add(spawn(ctx, terra, n % 2 == 0 ? EntityTypes.ZOMBIE : Stowaways.PALE_SPIDER, 400 + n));
        whenLoaded(ctx, crews, () ->
        {
            var before = new java.util.HashMap<EntityType<? extends Mob>, String>();
            for (var entry : crews.entrySet())
                before.put(entry.getKey(), entry.getValue().stream().filter(m -> m.isAlive()).count() + " alive, tagged " + entry.getValue().stream().filter(m -> m.entityTags().contains("nullspace_airless_crew")).count() + ", first " + entry.getValue().get(0).getHealth() + " hp " + entry.getValue().get(0).getRemovalReason());
            for (var entry : crews.entrySet())
            {
                for (var mob : entry.getValue())
                {
                    ctx.assertTrue(!AirlessCrews.humanoid(entry.getKey()) || suited(mob), "A humanoid crew member on an airless planet wears the suit: " + entry.getKey());
                    kill(ares, mob, player);
                }
            }
            for (var mob : ordinary)
            {
                ctx.assertTrue(!suited(mob), "A mob on Terra is an ordinary one");
                kill(terra, mob, player);
            }
            // The drops need a tick before a query sees them.
            ctx.runAfterDelay(4, () ->
            {
                int[] all = new int[4];
                int index = 0;
                for (var entry : crews.entrySet())
                {
                    var found = collect(ares, index * 12, index * 12 + 12);
                    index++;
                    ctx.assertTrue(found[1] > 0, entry.getKey() + " drops copper over a dozen kills: " + java.util.Arrays.toString(found) + " before=" + before.get(entry.getKey()) + " alive=" + entry.getValue().stream().filter(m -> m.isAlive()).count() + " removal=" + entry.getValue().get(0).getRemovalReason() + " health=" + entry.getValue().get(0).getHealth());
                    for (int i = 0; i < 4; i++)
                        all[i] += found[i];
                }
                ctx.assertTrue(all[0] > 0 && all[1] > 0 && all[2] > 0 && all[3] > 0, "Together they drop iron, copper, redstone and glass: " + java.util.Arrays.toString(all));
                var terraFound = collect(terra, 398, 442);
                ctx.assertTrue(terraFound[1] + terraFound[2] + terraFound[3] == 0, "Ordinary mobs carry no salvage: " + java.util.Arrays.toString(terraFound));
                release(0, 30);
                ctx.succeed();
            });
        });
    }

    @GameTest(environment = "nullspace:airless_crews", maxTicks = 400)
    public void aCrewMemberOnlyDropsSalvageWhenAPlayerKillsIt(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var ares = server.getLevel(PlanetSurfaces.dimension(server, "sol_ares"));
        var mobs = new java.util.ArrayList<Mob>();
        for (int i = 600; i < 640; i++)
            mobs.add(spawn(ctx, ares, EntityTypes.ZOMBIE, i));
        whenLoaded(ctx, java.util.Map.of(EntityTypes.ZOMBIE, mobs), () ->
        {
            for (var mob : mobs)
                mob.hurtServer(ares, ares.damageSources().generic(), 1000f);
            ctx.runAfterDelay(4, () ->
            {
                var found = collect(ares, 598, 642);
                ctx.assertTrue(found[1] + found[2] + found[3] == 0, "Falling or burning does not drop the salvage: " + java.util.Arrays.toString(found));
                release(36, 42);
                ctx.succeed();
            });
        });
    }
}
