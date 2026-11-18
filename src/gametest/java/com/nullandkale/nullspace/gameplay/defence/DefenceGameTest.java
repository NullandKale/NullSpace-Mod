package com.nullandkale.nullspace.gameplay.defence;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.systems.Turrets;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.worldgen.encounters.SiteDefences;

import java.util.Set;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/** The salvage turret, the repair bay and the air an explosion takes, on a real ship with real fuel. */
public final class DefenceGameTest
{
    private static void until(GameTestHelper ctx, BooleanSupplier done, int waited, int most, Runnable then, java.util.function.Supplier<String> failure)
    {
        if (done.getAsBoolean())
            then.run();
        else if (waited >= most)
            ctx.fail(failure.get());
        else
            ctx.runAfterDelay(4, () -> until(ctx, done, waited + 4, most, then, failure));
    }

    /**
     * A survival-mode player. It is the test's own mock player and does not join through the player list, because joining runs
     * the world's start (a crash start or a space start would carry a new player away from the ship under test). Being there is
     * also what makes the mobs around the ship tick, so their damage cooldowns run.
     */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer survivalPlayer(GameTestHelper ctx)
    {
        var player = ctx.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    private static void leave(ServerLevel level, net.minecraft.server.level.ServerPlayer player)
    {
        if (level.getServer().getPlayerList().getPlayers().contains(player))
            level.getServer().getPlayerList().remove(player);
        else
            player.discard();
    }

    /** A real survival player who has joined, because the mock player is always in creative mode and so never a target. */
    private static net.minecraft.server.level.ServerPlayer joinedPlayer(GameTestHelper ctx)
    {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "defence-test"), false);
        var player = new net.minecraft.server.level.ServerPlayer(ctx.getLevel().getServer(), ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    /** Mobs only tick in chunks that tick entities, which a remote test ship's chunks do not do on their own. */
    private static void force(ServerLevel level, BlockPos base, boolean on)
    {
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++)
                level.setChunkForced((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz, on);
    }

    /** A powered, fuelled reference ship with its pad registered. */
    private static CompoundTag ship(ServerLevel level, BlockPos base, String name) throws java.io.IOException
    {
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), name);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        return ship;
    }

    private static Mob spawn(ServerLevel level, EntityType<? extends Mob> type, Vec3 at)
    {
        var mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.setPos(at);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        return mob;
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 500)
    public void aTurretShootsOnlyHostileMobsKeepsTheirDropsAndExperienceAndSpendsFuel(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(100, 6, 100));
        var at = base.offset(3, 3, 2);
        force(level, base, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Turret test");
        level.setBlock(at, DefenceBlocks.SALVAGE_TURRET.defaultBlockState(), 18);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        var turret = (TurretBlockEntity) level.getBlockEntity(at);
        ctx.assertTrue(turret != null, "The turret block has its block entity");
        int fuel = FuelSystem.available(level, ship);
        var bystander = survivalPlayer(ctx);
        bystander.teleportTo(level, at.getX() + 2.5, at.getY(), at.getZ() + 3.5, Set.of(), 0, 0, true);
        var zombie = spawn(level, EntityTypes.ZOMBIE, Vec3.atCenterOf(at.offset(0, 0, 3)));
        var villager = spawn(level, EntityTypes.VILLAGER, Vec3.atCenterOf(at.offset(1, 0, 3)));
        var item = new ItemEntity(level, at.getX() + .5, at.getY() + 1.2, at.getZ() + .5, new ItemStack(Items.IRON_INGOT, 3));
        level.addFreshEntity(item);
        ExperienceOrb.award(level, Vec3.atCenterOf(at.above()), 7);
        until(ctx, () -> !zombie.isAlive() && turret.xp() >= 7, 0, 400, () ->
        {
            ctx.assertTrue(villager.isAlive() && villager.getHealth() == villager.getMaxHealth(), "A villager is not a target");
            ctx.assertTrue(turret.countItem(Items.IRON_INGOT) == 3, "The turret keeps what lies in reach: " + turret.countItem(Items.IRON_INGOT));
            ctx.assertTrue(FuelSystem.available(level, ship) < fuel, "Shooting costs fuel: " + fuel + " -> " + FuelSystem.available(level, ship));
            ctx.assertTrue(FuelSystem.available(level, ship) > fuel - 20 * Turrets.SHOT_FUEL - 20000, "A kill costs a few shots, not the tank");
            int points = turret.takeExperience();
            ctx.assertTrue(points >= 7 && turret.xp() == 0, "The experience is handed over once: " + points);
            ctx.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "A friendly turret never shoots a player");
            villager.discard();
            leave(level, bystander);
            force(level, base, false);
            WorldStore.get(level.getServer()).ships().remove(ship);
            ctx.succeed();
        }, () -> "Zombie alive=" + zombie.isAlive() + " health=" + zombie.getHealth() + "/" + zombie.getMaxHealth() + " ticks=" + zombie.tickCount + " at " + zombie.blockPosition() + " cooldown=" + zombie.damageCooldownTime + " target=" + Turrets.target(level, turret, at, WorldStore.bounds(ship)) + " lastShot=" + turret.lastShot() + " now=" + level.getGameTime() + " xp=" + turret.xp() + " items=" + turret.countItem(Items.IRON_INGOT) + " fuel=" + FuelSystem.available(level, ship) + " of " + fuel + " bystander ticks=" + bystander.tickCount);
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 400)
    public void aSecurityTurretShootsPlayersAndNotMobsUntilItIsSwitchedOver(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(200, 6, 200));
        var at = base.offset(3, 3, 2);
        force(level, base, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Security turret test");
        level.setBlock(at, DefenceBlocks.SALVAGE_TURRET.defaultBlockState(), 18);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        var turret = (TurretBlockEntity) level.getBlockEntity(at);
        turret.hostile(true);
        var player = joinedPlayer(ctx);
        var zombie = spawn(level, EntityTypes.ZOMBIE, Vec3.atCenterOf(at.offset(1, 0, 1)));
        player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + 2.5, Set.of(), 0, 0, true);
        until(ctx, () -> player.getHealth() < player.getMaxHealth(), 0, 300, () ->
        {
            ctx.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "A security turret leaves the mobs alone");
            turret.hostile(false);
            float health = player.getHealth();
            ctx.runAfterDelay(80, () ->
            {
                ctx.assertTrue(player.getHealth() >= health, "Once switched over it no longer shoots the player");
                leave(level, player);
                force(level, base, false);
                WorldStore.get(level.getServer()).ships().remove(ship);
                ctx.succeed();
            });
        }, () -> "Player health " + player.getHealth() + " of " + player.getMaxHealth() + " at " + player.blockPosition() + " creative=" + player.isCreative() + " target=" + Turrets.target(level, turret, at, WorldStore.bounds(ship)) + " lastShot=" + turret.lastShot() + " now=" + level.getGameTime() + " enabled=" + turret.enabled() + " hostile=" + turret.hostile() + " inBounds=" + WorldStore.bounds(ship).contains(player.blockPosition()) + " clip=" + level.clip(new net.minecraft.world.level.ClipContext(Vec3.atCenterOf(at).add(0, 0.7, 0), player.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() + " turretAt=" + at);
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 800)
    public void aRepairBayMendsATreasuredToolWithItsEnchantmentsForFuelAndStopsWithoutIt(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(300, 6, 300));
        var at = base.offset(3, 3, 2);
        force(level, base, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Repair bay test");
        level.setBlock(at, DefenceBlocks.REPAIR_BAY.defaultBlockState(), 18);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        var bay = (RepairBayBlockEntity) level.getBlockEntity(at);
        // A vessel's systems only run while a player is within eighty blocks of it ("unattended ships pause").
        var attendant = survivalPlayer(ctx);
        attendant.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + 2.5, Set.of(), 0, 0, true);
        var pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
        pick.setDamageValue(100);
        bay.setItem(0, pick);
        ctx.assertTrue(bay.canPlaceItem(1, new ItemStack(Items.DIAMOND_PICKAXE)) && !bay.canPlaceItem(1, new ItemStack(Items.STICK)), "The bay takes tools and nothing else");
        int fuel = FuelSystem.available(level, ship);
        until(ctx, () -> !bay.getItem(0).isDamaged(), 0, 720, () ->
        {
            var mended = bay.getItem(0);
            ctx.assertTrue(mended.isEnchanted(), "The enchantment survives the repair");
            int spent = fuel - FuelSystem.available(level, ship);
            int cost = RepairCost.unitsFor(100);
            ctx.assertTrue(spent >= cost && spent < cost + 50000, "The job costs its fuel: spent " + spent + ", the job is " + cost);
            leave(level, attendant);
            force(level, base, false);
            WorldStore.get(level.getServer()).ships().remove(ship);
            ctx.succeed();
        }, () -> "Damage " + bay.getItem(0).getDamageValue() + ", status " + bay.status() + ", fuel " + FuelSystem.available(level, ship) + " of " + fuel + ", same block entity " + (level.getBlockEntity(at) == bay) + ", world has " + (level.getBlockEntity(at) instanceof RepairBayBlockEntity w ? w.getItem(0) + " status " + w.status() : "none") + ", state " + level.getBlockState(at) + ", forced " + level.getForceLoadedChunks().size());
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 300)
    public void anExplosionInASealedRoomUsesUpTheAirAndACreeperOnlyDentsIt(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var tnt = fullShip(ctx, level, ctx.absolutePos(new BlockPos(400, 6, 400)), "Blast test");
        var creeper = fullShip(ctx, level, ctx.absolutePos(new BlockPos(460, 6, 460)), "Creeper test");
        var inside = room(level, tnt);
        int before = ShipAir.oxygen(level, tnt, inside);
        ctx.assertTrue(before >= 95, "The room starts full: " + before);
        ShipAir.blast(level, Vec3.atCenterOf(inside), 4.0F);
        ShipSystems.tick(level, tnt);
        int afterTnt = ShipAir.oxygen(level, tnt, inside);
        ctx.assertTrue(afterTnt < 25, "A TNT blast takes the room below the breathable threshold: " + afterTnt);
        ctx.assertTrue(!ShipAir.breathable(level, tnt, inside), "The room is not breathable after TNT");
        var spot = room(level, creeper);
        ShipAir.blast(level, Vec3.atCenterOf(spot), 3.0F);
        ShipSystems.tick(level, creeper);
        int afterCreeper = ShipAir.oxygen(level, creeper, spot);
        ctx.assertTrue(afterCreeper < before && afterCreeper > afterTnt, "A creeper takes a smaller bite than TNT: " + afterCreeper + " vs " + afterTnt);
        ShipAir.blast(level, Vec3.atCenterOf(spot).add(500, 0, 500), 4.0F);
        ShipAir.blast(level, Vec3.atCenterOf(spot), 0.0F);
        ctx.assertTrue(ShipAir.oxygen(level, creeper, spot) == afterCreeper, "A blast far away, or one with no power, takes nothing");
        WorldStore.get(level.getServer()).ships().remove(tnt);
        WorldStore.get(level.getServer()).ships().remove(creeper);
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 500)
    public void aDefendedSiteHasSecurityTurretsLockedDoorsAndPoolsAndUsingTheCoreSecuresIt(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(520, 6, 520));
        force(level, base, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Defended site");
        ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ShipTopology.resolve(level, helm));
        var ship = WorldStore.get(level.getServer()).at(level, helm);
        ctx.assertTrue(ship != null, "The ship is in the store");
        var door = base.offset(3, 3, 0);
        ctx.assertTrue(level.getBlockState(door).is(net.minecraft.world.level.block.Blocks.OAK_DOOR), "The reference ship has a wooden door");
        SiteDefences.apply(level, ship, 3, 77L);
        ctx.assertTrue(ship.getBooleanOr(SiteDefences.DEFENDED, false), "The site is marked defended");
        var turrets = new java.util.ArrayList<BlockPos>();
        int fluid = 0;
        for (var p : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
        {
            if (level.getBlockState(p).is(DefenceBlocks.SALVAGE_TURRET))
                turrets.add(p.immutable());
            if (level.getFluidState(p).getType() == com.nullandkale.nullspace.content.SpaceMaterials.GREEN_FLUID)
                fluid++;
        }
        ctx.assertTrue(!turrets.isEmpty() && turrets.stream().allMatch(p -> level.getBlockEntity(p) instanceof TurretBlockEntity t && t.hostile()), "Its doorway has hostile security turrets: " + turrets);
        ctx.assertTrue(level.getBlockState(door).is(net.minecraft.world.level.block.Blocks.IRON_DOOR), "Its door is locked: " + level.getBlockState(door));
        ctx.assertTrue(fluid >= 1, "It has a pool of the green fluid: " + fluid);
        int before = turrets.size();
        SiteDefences.apply(level, ship, 3, 77L);
        int again = 0;
        for (var p : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
            if (level.getBlockState(p).is(DefenceBlocks.SALVAGE_TURRET))
                again++;
        ctx.assertTrue(again == before, "Applying twice adds nothing: " + before + " then " + again);
        var turretAt = turrets.getFirst();
        var player = joinedPlayer(ctx);
        player.teleportTo(level, turretAt.getX() + .5, turretAt.getY(), turretAt.getZ() + 2.5, Set.of(), 0, 0, true);
        until(ctx, () -> player.getHealth() < player.getMaxHealth(), 0, 300, () ->
        {
            var message = SiteDefences.secure(player, helm);
            ctx.assertTrue(message != null && message.isEmpty(), "Using the core with power secures the site: " + message);
            ctx.assertTrue(ship.getBooleanOr(SiteDefences.SECURED, false), "The site is secured");
            ctx.assertTrue(level.getBlockState(door).is(net.minecraft.world.level.block.Blocks.OAK_DOOR), "Its door unlocks: " + level.getBlockState(door));
            ctx.assertTrue(SiteDefences.secure(player, helm) == null, "Securing is done once");
            player.setHealth(player.getMaxHealth());
            ctx.runAfterDelay(100, () ->
            {
                ctx.assertTrue(player.getHealth() == player.getMaxHealth(), "A secured site's turrets no longer shoot the player: " + player.getHealth());
                leave(level, player);
                force(level, base, false);
                WorldStore.get(level.getServer()).ships().remove(ship);
                ctx.succeed();
            });
        }, () -> "Player health " + player.getHealth() + " turrets " + turrets + " target=" + Turrets.target(level, true, turretAt, WorldStore.bounds(ship)));
    }

    @GameTest(environment = "nullspace:defence", maxTicks = 300)
    public void aDeepSiteWhoseSeedAllowsItHasOneRepairBayStandingAgainstAWall(GameTestHelper ctx) throws java.io.IOException
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(620, 6, 620));
        force(level, base, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Deep site");
        ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ShipTopology.resolve(level, helm));
        var ship = WorldStore.get(level.getServer()).at(level, helm);
        ctx.assertTrue(ship != null, "The ship is in the store");
        long seed = 1;
        while (!com.nullandkale.nullspace.worldgen.encounters.SiteMachines.hasBay(3, seed))
            seed++;
        com.nullandkale.nullspace.worldgen.encounters.SiteMachines.apply(level, ship, 3, seed);
        var bays = new java.util.ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
            if (level.getBlockState(p).is(DefenceBlocks.REPAIR_BAY))
                bays.add(p.immutable());
        ctx.assertTrue(bays.size() == 1, "A site that allows it has one repair bay: " + bays);
        var bay = bays.getFirst();
        ctx.assertTrue(java.util.stream.Stream.of(net.minecraft.core.Direction.Plane.HORIZONTAL.stream().toArray(net.minecraft.core.Direction[]::new)).anyMatch(d -> level.getBlockState(bay.relative(d)).isFaceSturdy(level, bay.relative(d), d.getOpposite())), "It stands against a wall");
        com.nullandkale.nullspace.worldgen.encounters.SiteMachines.apply(level, ship, 3, seed);
        int again = 0;
        for (var p : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
            if (level.getBlockState(p).is(DefenceBlocks.REPAIR_BAY))
                again++;
        ctx.assertTrue(again == 1, "Applying twice adds nothing: " + again);
        force(level, base, false);
        WorldStore.get(level.getServer()).ships().remove(ship);
        ctx.succeed();
    }

    /** A reference ship whose rooms start full of air. */
    private static CompoundTag fullShip(GameTestHelper ctx, ServerLevel level, BlockPos base, String name) throws java.io.IOException
    {
        var original = ship(level, base, name);
        var copy = original.copy();
        var air = copy.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
        for (int i = 0; i < air.length; i++)
            if (air[i] >= 0)
                air[i] = 1000;
        copy.getCompoundOrEmpty("systems").putIntArray("air", air);
        WorldStore.get(level.getServer()).ships().remove(original);
        WorldStore.get(level.getServer()).ships().add(copy);
        return copy;
    }

    /** The middle of the reference ship's one sealed room: its hull is a 7 by 7 box and the room is x 1 to 5, y 3 to 5, z 1 to 5. */
    private static BlockPos room(ServerLevel level, CompoundTag ship)
    {
        var inside = WorldStore.pos(ship, "base").offset(3, 4, 3);
        if (!level.getBlockState(inside).isAir() || ShipAir.roomAt(level, ship, inside) == null)
            throw new IllegalStateException("No room at " + inside + ": " + ShipAir.rooms(level, ship).stream().map(ShipAir.RoomReading::description).toList());
        return inside;
    }
}
