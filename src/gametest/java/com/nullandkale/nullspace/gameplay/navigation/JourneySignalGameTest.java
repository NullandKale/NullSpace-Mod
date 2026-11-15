package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.entity.RepairBot;
import com.nullandkale.nullspace.content.entity.Stowaways;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.JourneyPlan;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.encounters.SiteThreats;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Step 6: journey signals are random, fade afterwards, and derelicts fight back by danger. */
public final class JourneySignalGameTest
{
    @GameTest
    public void signalsAreOptionalAndARecentlyAnnouncedSignalStartsACooldown(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var ship = record(TravelService.SPACE.identifier().toString(), "sol_terra");
        ship.putLong("nextSignalNonce", 0);
        ctx.assertTrue(JourneyService.plan(server, ship, "sol_luna", 2000).getIntOr("contactAt", 0) < Integer.MAX_VALUE, "A signal-bearing trip can announce a contact");
        ship.putLong("lastSignalAt", server.overworld().getGameTime());
        ship.putLong("nextSignalNonce", 0);
        ctx.assertTrue(JourneyService.plan(server, ship, "sol_luna", 2000).getIntOr("contactAt", 0) == Integer.MAX_VALUE, "A fresh trip does not bypass the vessel's five-minute cooldown");
        ship.putLong("lastSignalAt", server.overworld().getGameTime() - 6000);
        ship.putLong("nextSignalNonce", 0);
        ctx.assertTrue(JourneyService.plan(server, ship, "sol_luna", 2000).getIntOr("contactAt", 0) < Integer.MAX_VALUE, "Signals return when the cooldown expires");
        ship.putLong("nextSignalNonce", 1);
        ctx.assertTrue(JourneyService.plan(server, ship, "sol_luna", 2000).getIntOr("contactAt", 0) == Integer.MAX_VALUE, "A quiet roll has no contact even after the cooldown");
        ctx.succeed();
    }

    private static final String SPACE = TravelService.SPACE.identifier().toString();

    /** A small sealed stone-brick room registered as a ready salvage site. */
    private static CompoundTag site(GameTestHelper ctx, String address, BlockPos base)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("name", "Test derelict");
        ship.putString("dimension", SPACE);
        ship.putString("localSpace", address);
        ship.putLong("base", base.asLong());
        ship.putInt("width", 8);
        ship.putInt("depth", 8);
        ship.putInt("height", 6);
        ship.putBoolean("salvage", true);
        WorldStore.get(server).ships().add(ship);
        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 6; y++)
                for (int z = 0; z < 8; z++)
                    if (x == 0 || x == 7 || y == 0 || y == 5 || z == 0 || z == 7)
                        space.setBlock(base.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
        return ship;
    }

    private static CompoundTag record(String dimension, String address)
    {
        var ship = new CompoundTag();
        ship.putString("id", UUID.randomUUID().toString());
        ship.putString("dimension", dimension);
        ship.putString("localSpace", address);
        ship.putLong("base", new BlockPos(1_990_000, 96, 1_990_000).asLong());
        ship.putInt("width", 1);
        ship.putInt("depth", 1);
        ship.putInt("height", 1);
        return ship;
    }

    private static void clear(GameTestHelper ctx, BlockPos base)
    {
        var space = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
        for (var at : BlockPos.betweenClosed(base, base.offset(7, 5, 7)))
            space.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
    }

    @GameTest(maxTicks = 40)
    public void signalsFadeOnceNoJourneyOrShipNeedsThemButClaimedOnesStay(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var store = WorldStore.get(server);
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String faded = "e_fade_" + tag, followed = "e_follow_" + tag, visited = "e_visit_" + tag, claimed = "e_claim_" + tag, oldCorridor = "j_old_" + tag, liveCorridor = "j_live_" + tag;
        var bases = new ArrayList<BlockPos>();
        var vessels = new HashMap<String, CompoundTag>();
        int i = 0;
        for (var id : List.of(faded, followed, visited, claimed))
        {
            var base = new BlockPos(1_950_000 + i++ * 64, 96, 1_950_000);
            bases.add(base);
            var vessel = site(ctx, id, base);
            vessels.put(id, vessel);
            var entry = new CompoundTag();
            entry.putString("state", "placed");
            entry.putString("vessel", vessel.getStringOr("id", ""));
            store.section("encounters").put(id, entry);
            store.section("journeyRegions").put(id, new CompoundTag());
        }
        vessels.get(claimed).putString("owner", UUID.randomUUID().toString());
        store.section("journeyRegions").put(oldCorridor, new CompoundTag());
        store.section("journeyRegions").put(liveCorridor, new CompoundTag());
        // A ship still on the journey that found one signal, and a ship parked at another.
        var traveller = record("minecraft:overworld", "");
        var journey = new CompoundTag();
        journey.putString("encounter", followed);
        journey.putString("corridor", liveCorridor);
        traveller.put("journey", journey);
        var visitor = record(SPACE, visited);
        store.ships().add(traveller);
        store.ships().add(visitor);
        try
        {
            EncounterSites.vanish(server);
            var fadedVessel = vessels.get(faded);
            ctx.assertTrue(!store.section("encounters").contains(faded) && !store.section("journeyRegions").contains(faded) && store.repository().find(fadedVessel.getStringOr("id", "")) == null && space.getBlockState(bases.get(0)).isAir(), "A signal nobody's journey refers to fades: its site, record and region are gone");
            ctx.assertTrue(store.section("encounters").contains(followed) && space.getBlockState(bases.get(1)).is(Blocks.STONE_BRICKS), "A signal stays while the journey that found it is underway");
            ctx.assertTrue(store.section("encounters").contains(visited) && space.getBlockState(bases.get(2)).is(Blocks.STONE_BRICKS), "A signal stays while another ship is still there");
            ctx.assertTrue(store.repository().find(vessels.get(claimed).getStringOr("id", "")) != null && space.getBlockState(bases.get(3)).is(Blocks.STONE_BRICKS), "A claimed site is a player's ship and never fades");
            ctx.assertTrue(!store.section("journeyRegions").contains(oldCorridor) && store.section("journeyRegions").contains(liveCorridor), "Corridors of finished journeys are forgotten; live ones stay");
        }
        finally
        {
            store.ships().removeIf(t -> vessels.containsValue(t) || t == traveller || t == visitor);
            for (var id : List.of(faded, followed, visited, claimed, oldCorridor, liveCorridor))
            {
                store.section("encounters").remove(id);
                store.section("journeyRegions").remove(id);
            }
            bases.forEach(b -> clear(ctx, b));
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void derelictsGetLostCrewAndRepairBotsByDangerOnce(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var store = WorldStore.get(server);
        var base = new BlockPos(1_960_000, 96, 1_960_000);
        var safeBase = new BlockPos(1_960_064, 96, 1_960_000);
        for (var b : List.of(base, safeBase))
            space.setChunkForced(b.getX() >> 4, b.getZ() >> 4, true);
        var far = site(ctx, "e_threat_far", base);
        far.putInt("encounterDanger", 3);
        var home = site(ctx, "e_threat_home", safeBase);
        home.putInt("encounterDanger", 0);
        var spawned = SiteThreats.populate(space, far);
        var none = SiteThreats.populate(space, home);
        try
        {
            long zombies = spawned.stream().filter(m -> m instanceof Zombie).count();
            long skeletons = spawned.stream().filter(m -> m instanceof AbstractSkeleton).count();
            long bots = spawned.stream().filter(m -> m instanceof RepairBot).count();
            ctx.assertTrue(zombies == 3 && skeletons == 2 && bots == 2, "Danger 3: three lost crew zombies, two skeletons and two repair bots, got " + zombies + "/" + skeletons + "/" + bots);
            ctx.assertTrue(none.isEmpty(), "A danger 0 site has no threats");
            ctx.assertTrue(far.getBooleanOr("threatsPlaced", false) && home.getBooleanOr("threatsPlaced", false), "Threats are placed once per build");
            var bounds = WorldStore.bounds(far).box();
            for (Mob mob : spawned)
            {
                ctx.assertTrue(bounds.contains(mob.position()), "Threats start inside the site");
                ctx.assertTrue(mob.isPersistenceRequired(), "Threats do not despawn");
                if (mob instanceof Zombie || mob instanceof AbstractSkeleton)
                    ctx.assertTrue((mob.getItemBySlot(EquipmentSlot.HEAD).is(ModBlocks.HELMET) || mob.getItemBySlot(EquipmentSlot.HEAD).is(ModBlocks.ARMORED_HELMET)) && (mob.getItemBySlot(EquipmentSlot.FEET).is(ModBlocks.BOOTS) || mob.getItemBySlot(EquipmentSlot.FEET).is(ModBlocks.ARMORED_BOOTS)) && mob.getCustomName() == null, "Lost crew wear space suits and carry no name tag");
            }
            ctx.assertTrue(spawned.stream().filter(m -> m instanceof AbstractSkeleton && !(m instanceof WitherSkeleton)).allMatch(m -> m.getMainHandItem().is(Items.BOW)), "Lost crew skeletons carry bows");
            ctx.assertTrue(spawned.stream().anyMatch(m -> m instanceof ZombieVillager), "Danger 3 has the hard zombie rank");
            var bot = (RepairBot) spawned.stream().filter(m -> m instanceof RepairBot).findFirst().get();
            ctx.assertTrue(bot.isNoGravity(), "Repair bots hover");
            var patient = spawned.stream().filter(m -> m instanceof Zombie).findFirst().get();
            patient.teleportTo(bot.getX(), bot.getY(), bot.getZ());
            patient.setHealth(4);
            ctx.runAfterDelay(90, () ->
            {
                ctx.assertTrue(!patient.isAlive() || patient.getHealth() > 4, "A repair bot patches up wounded crew near it");
                finish(ctx, store, far, home, base, safeBase, spawned);
                ctx.succeed();
            });
        }
        catch (RuntimeException | AssertionError e)
        {
            finish(ctx, store, far, home, base, safeBase, spawned);
            throw e;
        }
    }

    @GameTest(maxTicks = 200)
    public void crewGetVanillasSpawnUpgradesInTheSpaceSuitAndHigherDangerGetsMore(GameTestHelper ctx)
    {
        var space = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
        int[] armored = new int[2], enchanted = new int[2];
        var dangers = new int[]{1, 3};
        for (int d = 0; d < 2; d++)
            for (int i = 0; i < 150; i++)
            {
                var mob = SiteThreats.crew(space, i % 2 == 0 ? net.minecraft.world.entity.EntityTypes.ZOMBIE : net.minecraft.world.entity.EntityTypes.SKELETON, SiteThreats.difficulty(dangers[d], d == 1 ? SiteThreats.FULL_HEAT : 0));
                ctx.assertTrue(mob.getCustomName() == null && !mob.isBaby() && !mob.isPassenger() && !mob.isVehicle(), "Crew carry no tag and are never a baby or a jockey");
                if (mob instanceof Zombie zombie)
                    ctx.assertTrue(!zombie.canBreakDoors(), "Crew never break doors, which would vent the rooms");
                for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                {
                    var piece = mob.getItemBySlot(slot);
                    ctx.assertTrue(!piece.isEmpty() && !piece.is(Items.IRON_HELMET) && !piece.is(Items.DIAMOND_HELMET), "Every piece is a space suit piece");
                    armored[d] += piece.is(ModBlocks.ARMORED_HELMET) || piece.is(ModBlocks.ARMORED_CHEST) || piece.is(ModBlocks.ARMORED_LEGS) || piece.is(ModBlocks.ARMORED_BOOTS) ? 1 : 0;
                    enchanted[d] += piece.isEnchanted() ? 1 : 0;
                }
            }
        // 150 crew with four pieces each: about 20 percent armored at danger 1, about 75 at danger 3.
        ctx.assertTrue(armored[1] > armored[0] * 2 && armored[1] > 300 && armored[0] < 300, "Danger 3 crew wear far more of the armored suit: " + armored[0] + " against " + armored[1]);
        ctx.assertTrue(enchanted[1] > enchanted[0] && enchanted[1] > 0, "Danger 3 suits carry enchantments: " + enchanted[0] + " against " + enchanted[1]);
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void pestsJoinTheCrewByDangerAndTheCreeperOnlyFromDangerTwo(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var store = WorldStore.get(server);
        var base = new BlockPos(1_960_192, 96, 1_960_000);
        space.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        var ship = site(ctx, "e_threat_pests", base);
        ship.putInt("encounterDanger", 3);
        var spawned = new ArrayList<>(SiteThreats.populate(space, ship));
        try
        {
            var pests = spawned.stream().filter(m -> !(m instanceof Zombie) && !(m instanceof AbstractSkeleton) && !(m instanceof RepairBot)).toList();
            ctx.assertTrue(pests.size() == SiteThreats.pests(3) && SiteThreats.pests(3) == 3 && SiteThreats.pests(1) == 1 && SiteThreats.pests(0) == 0, "Danger 3 has three pests: " + pests.size());
            var allowed = Set.of(Stowaways.PALE_SPIDER, Stowaways.PALE_CAVE_SPIDER, Stowaways.PALE_SILVERFISH, Stowaways.GHOST_CREEPER, Stowaways.VIOLET_SLIME, Stowaways.VIOLET_BLAZE);
            ctx.assertTrue(pests.stream().allMatch(m -> allowed.contains(m.getType())), "Pests are only the stowaways");
            var random = net.minecraft.util.RandomSource.create(7);
            var seen = new HashSet<net.minecraft.world.entity.EntityType<?>>();
            for (int i = 0; i < 300; i++)
            {
                ctx.assertTrue(SiteThreats.pest(1, random) != Stowaways.GHOST_CREEPER && SiteThreats.pest(2, random) != Stowaways.VIOLET_SLIME && SiteThreats.pest(2, random) != Stowaways.VIOLET_BLAZE, "No creeper below danger 2, and the violet pair only at danger 3");
                seen.add(SiteThreats.pest(3, random));
            }
            ctx.assertTrue(seen.equals(allowed), "Danger 3 draws all six stowaways: " + seen);
        }
        finally
        {
            spawned.forEach(Mob::discard);
            store.ships().remove(ship);
            clear(ctx, base);
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void timeAboardBringsReinforcementsOutOfSightUpToTwoPerDanger(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var store = WorldStore.get(server);
        var base = new BlockPos(1_960_128, 96, 1_960_000);
        space.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        var ship = site(ctx, "e_threat_wait", base);
        ship.putInt("encounterDanger", 2);
        var spawned = new ArrayList<>(SiteThreats.populate(space, ship));
        try
        {
            var box = WorldStore.bounds(ship).box();
            ctx.assertTrue(SiteThreats.reinforce(space, ship) == null && ship.getIntOr("reinforcements", 0) == 0, "Nobody comes before the time is up");
            int arrived = 0;
            for (int round = 1; round <= 5; round++)
            {
                ship.putInt("siteHeat", round * SiteThreats.REINFORCE_TICKS - 20);
                var mob = SiteThreats.reinforce(space, ship);
                if (mob != null)
                {
                    arrived++;
                    spawned.add(mob);
                    ctx.assertTrue(box.contains(mob.position()), "A reinforcement turns up inside the site");
                }
            }
            ctx.assertTrue(arrived == 4 && ship.getIntOr("reinforcements", 0) == 4, "Danger 2 gets two reinforcements per danger level and no more: " + arrived);
        }
        finally
        {
            spawned.forEach(Mob::discard);
            store.ships().remove(ship);
            clear(ctx, base);
        }
        ctx.succeed();
    }

    private static void finish(GameTestHelper ctx, WorldStore store, CompoundTag far, CompoundTag home, BlockPos base, BlockPos safeBase, List<Mob> spawned)
    {
        spawned.forEach(Mob::discard);
        store.ships().remove(far);
        store.ships().remove(home);
        clear(ctx, base);
        clear(ctx, safeBase);
    }

    @GameTest
    public void driftingCargoIsASmallDockableSiteWithCrates(GameTestHelper ctx)
    {
        for (long seed = 0; seed < 3; seed++)
        {
            var plan = EncounterSites.plan(3, seed);
            ctx.assertTrue(plan.docks().size() == 1, "The cargo pod has one dock");
            ctx.assertTrue(plan.blocks().get(plan.core()).is(ModBlocks.STATION_CORE), "The cargo pod has a core");
            for (var crate : EncounterSites.POD_CRATES)
                ctx.assertTrue(plan.blocks().get(crate).is(Blocks.BARREL), "Each crate is a barrel");
        }
        ctx.assertTrue(JourneyPlan.name(3).equals("Drifting cargo") && EncounterSites.design(3, 1).isEmpty(), "Drifting cargo is its own kind, not a shipyard design");
        ctx.succeed();
    }
}
