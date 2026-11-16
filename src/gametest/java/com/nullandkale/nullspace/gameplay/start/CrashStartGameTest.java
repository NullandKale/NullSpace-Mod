package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * The crash-landing start on a planet with no air (Ares, the desert world), so Terra's shared pad is never touched. Own test
 * environment: it builds on a planet's landing site and writes world-level start records.
 */
public final class CrashStartGameTest
{
    private static final WorldStartSettings SINGLE = new WorldStartSettings(true, "rivet_bolt", true, false, "desert");
    private static final WorldStartSettings MULTI = new WorldStartSettings(true, "rivet_bolt", true, true, "desert");
    private static final String PLANET = "sol_ares";

    private static long count(ServerLevel level, CompoundTag ship, Block block)
    {
        long n = 0;
        var bounds = WorldStore.bounds(ship);
        for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (level.getBlockState(at).is(block))
                n++;
        return n;
    }

    private static ServerLevel level(MinecraftServer server)
    {
        return server.getLevel(PlanetSurfaces.dimension(server, PLANET));
    }

    /** Leaves the world as found: the ships, the saved sites and the world-level crash record. */
    private static void clean(MinecraftServer server, ServerLevel level, Set<CompoundTag> ships)
    {
        var store = WorldStore.get(server);
        for (var ship : ships)
        {
            // The frames and dropped items stay behind when the blocks go; the next test's ship stands on the same site.
            for (var left : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, WorldStore.bounds(ship).box().inflate(2), e -> !(e instanceof net.minecraft.world.entity.player.Player)))
                left.discard();
            StationGameTest.cleanup(level, ship);
        }
        var world = store.section("crashWorld");
        for (var key : Set.copyOf(world.keySet()))
            world.remove(key);
        store.section("crashSites").remove(PLANET);
    }

    @GameTest(environment = "nullspace:crash_single", maxTicks = 600)
    public void aSingleCrashWrecksOneShipWithABrokenPadComputerAndLaterPlayersWakeInIt(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        // The planet's starter pad exists first, as in a real world, so the crash sites keep clear of it and leave it as found.
        PlanetSurfaces.ensureLanding(server, PLANET);
        var first = ctx.makeMockServerPlayerInLevel();
        var second = ctx.makeMockServerPlayerInLevel();
        int pads = PadRegistry.read(server).size();
        var preset = ShipBlueprints.get("rivet_bolt");
        var plan = ShipBlueprints.blocks(preset);
        long engines = plan.values().stream().filter(s -> s.is(ModBlocks.ENGINE)).count(), generators = plan.values().stream().filter(s -> s.is(ModBlocks.GENERATOR)).count(), airs = plan.values().stream().filter(s -> s.is(ModBlocks.AIR_SUPPLY)).count();
        var ship = SpaceStart.start(first, SINGLE);
        var level = level(server);
        ctx.assertTrue(ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && first.level() == level, "The ship and the first player are on the chosen planet, with no stop in orbit");
        ctx.assertTrue(ship.getStringOr("localSpace", "").equals(PLANET) && ship.getStringOr("blueprint", "").equals("rivet_bolt"), "The ship's record says where it is and what it is");
        ctx.assertTrue(!PlanetSurfaces.breathable(level), "The fixture planet has no air");
        var site = CrashSites.of(WorldStore.get(server), PLANET, ship.getStringOr("id", ""));
        ctx.assertTrue(site != null, "The crash site is saved");
        ctx.assertTrue(level.getBlockState(site.computer()).is(BrokenMachines.PAD_COMPUTER), "The Pad Computer is broken");
        ctx.assertTrue(PadRegistry.read(server).size() == pads, "A crash never registers a pad");
        int ring = 0, gone = 0;
        for (int x = -1; x <= 32; x++)
            for (int z = -1; z <= 32; z++)
                if (x < 0 || x > 31 || z < 0 || z > 31)
                {
                    ring++;
                    if (level.getBlockState(new BlockPos(site.minX() + x, site.y(), site.minZ() + z)).isAir())
                        gone++;
                }
        ctx.assertTrue(ring == 132 && gone > ring * .2 && gone < ring * .6, "About a third of the ring is blown away (" + gone + " of " + ring + ")");
        // With no air outside, the cabin must keep its air.
        var controller = WorldStore.pos(ship, "controller");
        var at = controller.relative(level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING));
        ctx.assertTrue(ShipAir.breathable(level, ship, at) && ShipAir.breathable(level, ship, at.above()), "The cabin is breathable after the crash");
        long workingGenerators = count(level, ship, ModBlocks.GENERATOR), workingAirs = count(level, ship, ModBlocks.AIR_SUPPLY);
        ctx.assertTrue(workingGenerators >= 1 && workingAirs >= 1, "A generator and an air supply survive, so the cabin keeps its air");
        ctx.assertTrue(count(level, ship, ModBlocks.ENGINE) + count(level, ship, BrokenMachines.ENGINE) == engines && count(level, ship, BrokenMachines.ENGINE) >= 1, "At least one engine is broken, none lost");
        ctx.assertTrue(count(level, ship, BrokenMachines.GENERATOR) + workingGenerators == generators && count(level, ship, BrokenMachines.AIR_SUPPLY) + workingAirs == airs, "Machines are broken in place, never lost");
        ctx.assertTrue(first.getInventory().countItem(ModBlocks.AIR_CANISTER) == 1, "One air canister where there is no air");
        ctx.assertTrue(first.getInventory().countItem(Items.BREAD) == 16, "The food is kept");
        ctx.assertTrue(first.getInventory().countItem(Items.COAL) == 0 && first.getInventory().countItem(Items.IRON_PICKAXE) == 0 && first.getInventory().countItem(Items.IRON_AXE) == 0 && first.getInventory().countItem(Items.COBBLESTONE) == 0, "No fuel, tools or stone: a survivor starts with food and the suit");
        ctx.assertTrue(com.nullandkale.nullspace.gameplay.crew.LifeSupport.suited(first) && first.getInventory().countItem(com.nullandkale.nullspace.content.guide.FieldGuide.ITEM) == 1, "The suit and the field guide");
        ctx.assertTrue(first.getRecipeBook().contains(ResourceKey.create(Registries.RECIPE, Identifier.parse("nullspace:generator"))), "The recipes are unlocked");
        var frames = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, WorldStore.bounds(ship).box().inflate(1)).stream().filter(f -> f.entityTags().contains(CrashStart.KIT_FRAME)).toList();
        var framed = frames.stream().map(f -> f.getItem().getItem()).collect(java.util.stream.Collectors.toSet());
        ctx.assertTrue(framed.containsAll(Set.of(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.OAK_SAPLING, Items.DIRT)), "A water bucket, a lava bucket, a sapling and dirt hang in item frames in the cabin: stone and wood on every planet: " + framed);
        ctx.assertTrue(frames.stream().filter(f -> Set.of(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.OAK_SAPLING, Items.DIRT).contains(f.getItem().getItem())).allMatch(f -> level.getBlockState(f.blockPosition()).isAir() && ShipAir.breathable(level, ship, f.blockPosition())), "The frames are inside the sealed cabin: " + frames.stream().map(f -> f.getItem().getItem() + "@" + f.blockPosition() + " air=" + level.getBlockState(f.blockPosition()).isAir() + " state=" + level.getBlockState(f.blockPosition()) + " breathable=" + ShipAir.breathable(level, ship, f.blockPosition())).toList());
        var drops = new java.util.HashSet<net.minecraft.world.item.Item>();
        var wreck = BrokenMachines.ENGINE.defaultBlockState();
        for (int i = 0; i < 200; i++)
            Block.getDrops(wreck, level, controller, null).forEach(stack -> drops.add(stack.getItem()));
        ctx.assertTrue(Set.of(Items.IRON_INGOT, Items.COPPER_INGOT, Items.COBBLESTONE, Items.FURNACE, Items.PISTON).containsAll(drops) && drops.contains(Items.IRON_INGOT), "A wreck drops some of the materials its machine is made from, and nothing else: " + drops);
        // The second player does not crash again: they wake in the same ship.
        var again = SpaceStart.start(second, SINGLE);
        ctx.assertTrue(again.getStringOr("id", "").equals(ship.getStringOr("id", "")), "A later player wakes in the same ship");
        ctx.assertTrue(second.level() == level && WorldStore.bounds(again).contains(second.blockPosition()), "They wake inside it");
        ctx.assertTrue(ShipAir.breathable(level, again, second.blockPosition().above()), "In breathable air");
        ctx.assertTrue(WorldStore.get(server).ships().stream().filter(t -> Set.of(first.getUUID().toString(), second.getUUID().toString()).contains(((CompoundTag) t).getStringOr("owner", ""))).count() == 1, "Only one ship crashed for the two players");
        ctx.assertTrue(second.getInventory().countItem(Items.BREAD) == 16 && second.getInventory().countItem(ModBlocks.AIR_CANISTER) == 1, "Each player has their own supplies and canister");
        ctx.assertTrue(SpaceStart.start(first, SINGLE) == WorldStore.get(server).repository().find(ship.getStringOr("id", "")), "Rejoining keeps the ship");
        clean(server, level, Set.of(ship));
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:crash_multi", maxTicks = 600)
    public void everyPlayerInAMultiCrashCrashesOnTheirOwnSiteNearTheSpawnSpotWithoutNewPads(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        // The planet's starter pad exists first, as in a real world, so the crash sites keep clear of it and leave it as found.
        PlanetSurfaces.ensureLanding(server, PLANET);
        var players = new ServerPlayer[]{ctx.makeMockServerPlayerInLevel(), ctx.makeMockServerPlayerInLevel(), ctx.makeMockServerPlayerInLevel()};
        int pads = PadRegistry.read(server).size();
        var ships = new java.util.HashSet<CompoundTag>();
        var sites = new java.util.ArrayList<CrashSites.Site>();
        for (var player : players)
        {
            var ship = SpaceStart.start(player, MULTI);
            ships.add(ship);
            sites.add(CrashSites.of(WorldStore.get(server), PLANET, ship.getStringOr("id", "")));
            ctx.assertTrue(ship.getStringOr("owner", "").equals(player.getUUID().toString()), "Each player owns their own ship");
            ctx.assertTrue(player.level() == level(server) && WorldStore.bounds(ship).contains(player.blockPosition()), "Each player is in their own ship");
        }
        ctx.assertTrue(ships.size() == 3, "Three players, three ships");
        for (int i = 0; i < sites.size(); i++)
            for (int j = i + 1; j < sites.size(); j++)
                ctx.assertTrue(Math.abs(sites.get(i).minX() - sites.get(j).minX()) >= CrashSites.CELL || Math.abs(sites.get(i).minZ() - sites.get(j).minZ()) >= CrashSites.CELL, "No two crash sites overlap");
        var first = sites.getFirst();
        for (var site : sites)
            ctx.assertTrue(Math.hypot(site.minX() - first.minX(), site.minZ() - first.minZ()) <= CrashSites.CELL * 3.0, "Later crashes land close to the first, on the nearest free sites");
        ctx.assertTrue(PadRegistry.read(server).size() == pads, "Three crashes create and register no pad");
        for (var site : sites)
            ctx.assertTrue(level(server).getBlockState(site.computer()).is(BrokenMachines.PAD_COMPUTER), "Every site has its broken Pad Computer");
        clean(server, level(server), ships);
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:crash_large", maxTicks = 600)
    public void aLargeShipBreaksMoreLosesFurnishingsAndStillKeepsItsCabinAir(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        // The planet's starter pad exists first, as in a real world, so the crash sites keep clear of it and leave it as found.
        PlanetSurfaces.ensureLanding(server, PLANET);
        var player = ctx.makeMockServerPlayerInLevel();
        var settings = new WorldStartSettings(true, "halcyon_horizon", true, false, "desert");
        var preset = ShipBlueprints.get("halcyon_horizon");
        var plan = ShipBlueprints.blocks(preset);
        long engines = plan.values().stream().filter(s -> s.is(ModBlocks.ENGINE)).count(), generators = plan.values().stream().filter(s -> s.is(ModBlocks.GENERATOR)).count(), airs = plan.values().stream().filter(s -> s.is(ModBlocks.AIR_SUPPLY)).count();
        long furnishings = plan.values().stream().filter(s -> s.hasBlockEntity() && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals("nullspace") && !(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && !(s.getBlock() instanceof net.minecraft.world.level.block.BedBlock)).count();
        var ship = SpaceStart.start(player, settings);
        var level = level(server);
        ctx.assertTrue(StartDifficulty.shipClass(preset.decks()) == 2, "The fixture is a large ship");
        ctx.assertTrue(CrashDamage.broken((int) engines, 2) > CrashDamage.broken((int) engines, 0) || engines <= 4, "A large ship may break more of a kind than a small one");
        long brokenEngines = count(level, ship, BrokenMachines.ENGINE), brokenGenerators = count(level, ship, BrokenMachines.GENERATOR), brokenAirs = count(level, ship, BrokenMachines.AIR_SUPPLY);
        long workingGenerators = count(level, ship, ModBlocks.GENERATOR), workingAirs = count(level, ship, ModBlocks.AIR_SUPPLY);
        ctx.assertTrue(brokenEngines <= 4 && brokenGenerators <= 4 && brokenAirs <= 4, "No more than four of a kind are broken");
        ctx.assertTrue(brokenEngines == CrashDamage.broken((int) engines, 2) && brokenGenerators + workingGenerators == generators && brokenAirs + workingAirs == airs, "Machines are broken by the large-ship rule and none is lost");
        ctx.assertTrue(workingGenerators >= 1 && workingAirs >= 1, "With no air outside, a generator and an air supply survive");
        var controller = WorldStore.pos(ship, "controller");
        var at = controller.relative(level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING));
        ctx.assertTrue(ShipAir.breathable(level, ship, at) && ShipAir.breathable(level, ship, at.above()), "The cabin keeps its air");
        long left = 0;
        var bounds = WorldStore.bounds(ship);
        for (var pos : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            var state = level.getBlockState(pos);
            if (state.hasBlockEntity() && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace") && !(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && !(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock))
                left++;
        }
        ctx.assertTrue(furnishings > 0 && left < furnishings, "Some furnishings are destroyed: " + left + " of " + furnishings + " remain");
        ctx.assertTrue(player.getInventory().countItem(Items.BREAD) == 16 && player.getInventory().countItem(Items.COAL) == 0, "The same small kit on a big ship");
        clean(server, level, Set.of(ship));
        ctx.succeed();
    }
}
