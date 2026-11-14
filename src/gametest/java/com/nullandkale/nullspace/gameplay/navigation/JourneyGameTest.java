package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.Boarding;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.travel.ConcurrentShipActionsGameTest;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.model.galaxy.JourneyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class JourneyGameTest
{
    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 800)
    public void investigateStartsDockingWithoutEngageAndDisengageKeepsItCancelled(GameTestHelper ctx) throws Exception
    {
        var c = ConcurrentShipActionsGameTest.client(ctx, 48);
        c.board();
        var server = ctx.getLevel().getServer();
        var own = c.fixture().ship();
        var target = StationGameTest.fixture(c.fixture().space, new BlockPos(750000, 96, 750000), false);
        var journey = JourneyService.plan(server, own, "sol_luna", 2000);
        journey.putString("encounter", "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        journey.putInt("contactAt", 0);
        JourneyService.register(server, journey);
        own.put("journey", journey);
        own.putString("localSpace", journey.getStringOr("corridor", ""));
        String siteId = journey.getStringOr("encounter", "");
        target.putString("localSpace", siteId);
        target.putDouble("simX", 0);
        target.putDouble("simZ", 200);
        var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(siteId);
        site.putString("vessel", target.getStringOr("id", ""));
        site.putString("state", "placed");
        int[] cancelledAt = {-1}, stoppedAt = {-1};
        ctx.onEachTick(() ->
        {
            acknowledge(c);
            var ship = c.fixture().ship();
            if (ship.getCompoundOrEmpty("course").getStringOr("state", "").equals("PAUSED"))
                throw new AssertionError(CourseService.summary(ship));
            if (cancelledAt[0] < 0 && ship.contains("dock"))
            {
                ctx.assertTrue(!NavigationService.pending(server, c.fixture().id) && LocalSpaces.address(ship).equals(siteId), "Docking starts after the arrival handshake");
                ctx.assertTrue(CourseService.active(ship) && ship.getCompoundOrEmpty("dock").getStringOr("target", "").equals(target.getStringOr("id", "")), "Investigate approaches the actual encounter without another Engage or Dock input");
                CourseService.disengage(server, ship);
                cancelledAt[0] = server.getTickCount();
            }
            else if (cancelledAt[0] >= 0 && !ship.contains("dock") && !CourseService.active(ship))
            {
                if (stoppedAt[0] < 0)
                    stoppedAt[0] = server.getTickCount();
                if (server.getTickCount() - stoppedAt[0] < 20)
                    return;
                try
                {
                    ctx.assertTrue(!JourneyService.cruising(ship), "Cancelling docking does not resume the paid journey");
                    ctx.assertTrue(ship.getCompoundOrEmpty("course").getStringOr("state", "").equals("STOPPED"), "Automatic docking stays cancelled");
                    ctx.succeed();
                }
                finally
                {
                    WorldStore.get(server).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(c.fixture().id) || ((CompoundTag) t).getStringOr("id", "").equals(target.getStringOr("id", "")));
                    WorldStore.get(server).section("encounters").remove(siteId);
                    c.close();
                }
            }
        });
        Jumps.investigate(c.player(), WorldStore.pos(own, "controller"));
    }

    @GameTest(environment = "nullspace:encounter_retry", maxTicks = 300)
    public void encounterSaveFailureKeepsThePlanAndBacksOffWithoutCrashingTheServer(GameTestHelper ctx) throws Exception
    {
        var c = ConcurrentShipActionsGameTest.client(ctx, 44);
        var server = ctx.getLevel().getServer();
        var j = JourneyService.plan(server, c.fixture().ship(), "sol_luna", 2000);
        j.putString("encounter", "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        j.putInt("contactAt", 0);
        JourneyService.register(server, j);
        c.fixture().ship().put("journey", j);
        boolean[] done = {false};
        ctx.onEachTick(() ->
        {
            if (done[0] || server.getTickCount() % 20 != 0 || StationAccess.building(server))
                return;
            done[0] = true;
            int[] calls = {0};
            var previous = WorldStore.writeObserver;
            try
            {
                WorldStore.writeObserver = progress ->
                {
                    calls[0]++;
                    throw new IllegalStateException("Injected encounter save failure");
                };
                EncounterSites.tick(server);
                ctx.assertTrue(calls[0] > 0, "Encounter construction reached the durable registration write");
                var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(j.getStringOr("encounter", ""));
                ctx.assertTrue(site.getStringOr("state", "").equals("planned"), "Failed registration retains an unconsumed encounter plan");
                int failures = calls[0];
                EncounterSites.tick(server);
                ctx.assertTrue(calls[0] == failures, "A failed site does not retry every tick");
            }
            finally
            {
                WorldStore.writeObserver = previous;
                removeVessels(server, List.of(c.fixture().id));
                WorldStore.get(server).section("encounters").remove(j.getStringOr("encounter", ""));
                c.close();
            }
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:journey_offline_reload", maxTicks = 300)
    public void twoCrewsMeetTheirOwnSignalsAndKeepIndependentSavedProgress(GameTestHelper ctx) throws Exception
    {
        var a = ConcurrentShipActionsGameTest.client(ctx, 41);
        var b = ConcurrentShipActionsGameTest.client(ctx, 42);
        a.board();
        b.board();
        var server = ctx.getLevel().getServer();
        boolean[] offline = {false}, done = {false};
        int[] started = {-1}, elapsed = {-1};
        ctx.onEachTick(() ->
        {
            if (done[0])
                return;
            if (!offline[0])
                acknowledge(a);
            acknowledge(b);
            if (!offline[0] && JourneyService.cruising(a.fixture().ship()) && JourneyService.cruising(b.fixture().ship()) && !NavigationService.pending(server))
            {
                var ja = a.fixture().ship().getCompoundOrEmpty("journey");
                var jb = b.fixture().ship().getCompoundOrEmpty("journey");
                ctx.assertTrue(!ja.getStringOr("encounter", "a").equals(jb.getStringOr("encounter", "a")), "Each journey meets its own random signal, even on the same route");
                ctx.assertTrue(!LocalSpaces.together(a.fixture().ship(), b.fixture().ship()), "Independent FTL corridors do not expose unrelated ships to each other");
                elapsed[0] = ja.getIntOr("elapsed", 0);
                a.close();
                offline[0] = true;
                started[0] = server.getTickCount();
            }
            else if (offline[0] && server.getTickCount() - started[0] >= 80)
            {
                ctx.assertTrue(a.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", -1) == elapsed[0], "Empty ships pause while their crew is offline");
                ctx.assertTrue(b.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", 0) > elapsed[0], "The other crew keeps travelling");
                WorldStore.get(server).save();
                WorldStore.forget(server);
                ctx.assertTrue(a.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", -1) == elapsed[0], "Disk reload retains the offline crew's exact checkpoint");
                removeVessels(server, List.of(a.fixture().id, b.fixture().id));
                b.close();
                done[0] = true;
                ctx.succeed();
            }
        });
        ctx.runAfterDelay(20, () ->
        {
            Jumps.jump(a.player(), WorldStore.pos(a.fixture().ship(), "controller"), "sol_luna");
            Jumps.jump(b.player(), WorldStore.pos(b.fixture().ship(), "controller"), "sol_luna");
        });
    }

    @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 4800)
    public void allEncounterTypesHaveSafeDocksAccessibleFiniteSuppliesAndRealCrops(GameTestHelper ctx) throws Exception
    {
        var c = ConcurrentShipActionsGameTest.client(ctx, 43);
        var server = ctx.getLevel().getServer();
        var level = c.fixture().space;
        var vessels = new ArrayList<CompoundTag>();
        for (int kind = 0; kind < JourneyPlan.KINDS; kind++)
        {
            var vessel = StationBuilds.queue(server, UUID.randomUUID().toString(), "Encounter fixture " + kind, LocalSpaces.HOME, new net.minecraft.world.phys.Vec3(0, 0, 2000 + kind * 300), "", EncounterSites.plan(kind, kind), false);
            vessel.putBoolean("salvage", true);
            vessel.putInt("encounterKind", kind);
            vessel.putLong("encounterSeed", kind);
            vessels.add(vessel);
        }
        Runnable[] poll = {null};
        boolean[] done = {false};
        poll[0] = () ->
        {
            // The store may replace its records (a save retry reloads them), so follow each vessel by id.
            for (int i = 0; i < vessels.size(); i++)
            {
                var current = WorldStore.get(server).repository().find(vessels.get(i).getStringOr("id", ""));
                if (current != null)
                    vessels.set(i, current);
            }
            // Builds run one at a time, so the last encounter can take a while on large ships.
            if (vessels.stream().anyMatch(s -> !StationAccess.ready(s) || com.nullandkale.nullspace.gameplay.travel.BuildJournal.pending(server, s.getStringOr("id", ""))))
                return;
            done[0] = true;
            for (var vessel : vessels)
            {
                var port = DockingPorts.ports(level, vessel).getFirst();
                var pressure = new HullScan.LivePressure(level, WorldStore.bounds(vessel));
                ctx.assertTrue(port.anchors().stream().anyMatch(p -> pressure.breathable(p.above())), "Every encounter preserves a sealed docking entrance");
                int food = 0, fuel = 0, upgrades = 0, crops = 0;
                for (var at : BlockPos.betweenClosed(WorldStore.bounds(vessel).min(), WorldStore.bounds(vessel).max()))
                {
                    if (level.getBlockState(at).is(Blocks.WHEAT))
                        crops++;
                    // Found vessels keep one loot chest per room; barrels and the other chests stay empty.
                    if (!(level.getBlockEntity(at) instanceof Container chest))
                        continue;
                    // Loot tables roll when a player first opens the container.
                    if (chest instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r)
                        r.unpackLootTable(c.player());
                    ctx.assertTrue(StationAccess.mayUse(c.player(), at) && StationAccess.mayEdit(c.player(), at), "Salvage supplies and hardware are accessible in survival");
                    for (int i = 0; i < chest.getContainerSize(); i++)
                    {
                        var stack = chest.getItem(i);
                        if (stack.is(Items.BREAD))
                            food += stack.getCount();
                        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL))
                            fuel += stack.getCount();
                        if (stack.is(ModBlocks.SYSTEM_UPGRADE))
                            upgrades += stack.getCount();
                    }
                }
                int kind = vessel.getIntOr("encounterKind", -1);
                ctx.assertTrue(fuel > 0 && (kind != 0 || crops == 48), "Encounter rewards are real loot and growing blocks for role " + kind);
                ctx.assertTrue(fuel <= 64, "An encounter's total generator and container fuel stays within one stack: " + fuel);
                if (kind == 1 || kind == 2)
                {
                    var rooms = ShipRooms.rooms(EncounterSites.design(kind, vessel.getLongOr("encounterSeed", 1)));
                    int stocked = 0;
                    for (var room : rooms)
                    {
                        if (room.supplies() == null)
                            continue;
                        var supplies = (Container) level.getBlockEntity(WorldStore.pos(vessel, "base").offset(room.supplies()));
                        ctx.assertTrue(supplies != null, "Each resource room has its own physical container");
                        if (!supplies.isEmpty())
                            stocked++;
                        boolean hasFuel = false, hasFood = false;
                        for (int i = 0; i < supplies.getContainerSize(); i++)
                        {
                            var stack = supplies.getItem(i);
                            hasFuel |= stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
                            hasFood |= stack.has(net.minecraft.core.component.DataComponents.FOOD);
                        }
                        if (room.use() == ShipRooms.Use.ENGINEERING)
                            ctx.assertTrue(hasFuel, "Fuel belongs beside engineering");
                        if (room.use() == ShipRooms.Use.GALLEY)
                        {
                            var held = new java.util.ArrayList<String>();
                            for (int i = 0; i < supplies.getContainerSize(); i++)
                                held.add(supplies.getItem(i).toString());
                            ctx.assertTrue(hasFood, "Food belongs in the galley, " + room.name() + " holds " + held);
                        }
                        supplies.clearContent();
                    }
                    ctx.assertTrue(stocked >= 3, "Exploration rewards are spread across useful rooms");
                    EncounterSites.stock(level, vessel);
                    for (var room : rooms)
                        if (room.supplies() != null)
                            ctx.assertTrue(((Container) level.getBlockEntity(WorldStore.pos(vessel, "base").offset(room.supplies()))).isEmpty(), "A second stocking call cannot refill looted rooms");
                }
            }
            var ids = new ArrayList<>(vessels.stream().map(v -> v.getStringOr("id", "")).toList());
            ids.add(c.fixture().id);
            removeVessels(server, ids);
            c.close();
            ctx.succeed();
        };
        ctx.onEachTick(() ->
        {
            if (!done[0] && ctx.getTick() >= 20)
                poll[0].run();
        });
    }

    private static int count(Container container)
    {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++)
            total += container.getItem(i).getCount();
        return total;
    }

    private static void removeVessels(net.minecraft.server.MinecraftServer server, List<String> ids)
    {
        var store = WorldStore.get(server);
        for (String id : ids)
        {
            var vessel = store.repository().find(id);
            if (vessel == null)
                continue;
            var level = WorldStore.level(server, vessel.getStringOr("dimension", ""));
            var bounds = WorldStore.bounds(vessel);
            for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            {
                if (level.getBlockState(at).isAir())
                    continue;
                level.removeBlockEntity(at);
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
            }
        }
        store.ships().removeIf(t -> ids.contains(((CompoundTag) t).getStringOr("id", "")));
        store.requestSave();
    }

    private static void acknowledge(ConcurrentShipActionsGameTest.Client c)
    {
        c.connection().flushChannel();
        c.channel().runPendingTasks();
        Object packet;
        while ((packet = c.channel().readOutbound()) != null)
        {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof TravelPackets.Progress progress && !progress.done())
                NavigationService.ready(c.player(), new TravelPackets.Ready(progress.id(), !progress.dimension().isEmpty()));
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        for (var tag : WorldStore.get(c.player().level().getServer()).list("stationBoarding"))
        {
            var board = (CompoundTag) tag;
            if (board.getStringOr("player", "").equals(c.player().getUUID().toString()))
                Boarding.ready(c.player(), new TravelPackets.Ready(UUID.fromString(board.getStringOr("token", "")), board.getBooleanOr("committed", false)));
        }
    }

    @GameTest(environment = "nullspace:journey_restart_flow", maxTicks = 1600)
    public void savedJourneyCanStopDockCollectFuelAndResumeWithoutPayingAgain(GameTestHelper ctx) throws Exception
    {
        journeyFlow(ctx, 1, 40);
    }

    @GameTest(environment = "nullspace:journey_station_flow", maxTicks = 1600)
    public void existingStationSignalCanBeBoardedAndResumedAfterReload(GameTestHelper ctx) throws Exception
    {
        journeyFlow(ctx, com.nullandkale.nullspace.model.galaxy.EncounterKind.ABANDONED_STATION.id, 44);
    }

    private static void journeyFlow(GameTestHelper ctx, int encounterKind, int fixture) throws Exception
    {
        var c = ConcurrentShipActionsGameTest.client(ctx, fixture);
        var server = ctx.getLevel().getServer();
        var level = c.fixture().space;
        c.board();
        int[] phase = {-1}, paused = {0}, fuel = {0};
        String[] siteId = {""}, vesselId = {""};
        BlockPos[] cargo = {null};
        int[] remaining = {0};
        int[] previous = {-2}, ticks = {0};
        ctx.onEachTick(() ->
        {
            acknowledge(c);
            var ship = c.fixture().ship();
            if (previous[0] != phase[0])
            {
                NullSpace.LOGGER.info("Journey test phase {}", phase[0]);
                previous[0] = phase[0];
            }
            if (++ticks[0] == 1590)
                ctx.assertTrue(false, "Journey stuck at phase " + phase[0] + " dock=" + ship.getCompoundOrEmpty("dock") + " journey=" + ship.getCompoundOrEmpty("journey"));
            if (phase[0] == 0 && JourneyService.cruising(ship) && !NavigationService.pending(server, c.fixture().id))
            {
                ctx.assertTrue(!TransferLocks.locked(level, WorldStore.pos(ship, "controller")), "Cruise releases physical block interaction locks");
                ctx.assertTrue(Math.abs(ship.getDoubleOr("simYaw", 0) - 37) < 1e-8, "Entering FTL preserves the ship's heading rather than snapping it to zero");
                var j = ship.getCompoundOrEmpty("journey");
                j.putInt("kind", encounterKind);
                siteId[0] = j.getStringOr("encounter", "");
                var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(siteId[0]);
                site.putInt("kind", encounterKind);
                j.putInt("contactAt", 0);
                j.putInt("duration", 4000);
                j.remove("selection"); // Compatibility with the earliest saved journey records.
                WorldStore.get(server).save();
                WorldStore.forget(server);
                ctx.assertTrue(JourneyService.cruising(c.fixture().ship()), "Disk reload retains cruise state and paid route");
                phase[0] = 1;
            }
            else if (phase[0] == 1)
            {
                var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(siteId[0]);
                var vessel = WorldStore.get(server).repository().find(site.getStringOr("vessel", ""));
                if (vessel == null || !StationAccess.ready(vessel) || com.nullandkale.nullspace.gameplay.travel.BuildJournal.pending(server, vessel.getStringOr("id", "")))
                    return;
                vesselId[0] = vessel.getStringOr("id", "");
                if (encounterKind == com.nullandkale.nullspace.model.galaxy.EncounterKind.ABANDONED_STATION.id)
                {
                    var spec = com.nullandkale.nullspace.worldgen.stations.StationSpec.read(vessel.getCompoundOrEmpty("stationSpec"));
                    ctx.assertTrue(spec.equals(com.nullandkale.nullspace.worldgen.stations.StationSpec.read(site.getCompoundOrEmpty("stationSpec"))), "Reload preserves the exact planned station brief");
                    ctx.assertTrue(StationAccess.station(vessel) && !com.nullandkale.nullspace.gameplay.ownership.Claims.claimable(vessel), "The station cannot be converted through salvage-ship claiming");
                }
                ctx.assertTrue(JourneyService.stopReason(server, ship).isEmpty(), "Built signal is available to investigate");
                Jumps.investigate(c.player(), WorldStore.pos(ship, "controller"));
                phase[0] = 2;
            }
            else if (phase[0] == 2 && !NavigationService.pending(server, c.fixture().id))
            {
                // Keep this departure/loot regression on its original clear approach fixture rather
                // than making its success depend on the randomized encounter arrival position.
                ship.putDouble("simX", 0);
                ship.putDouble("simY", 0);
                ship.putDouble("simZ", 460);
                ctx.assertTrue(!JourneyService.cruising(ship) && LocalSpaces.address(ship).equals(siteId[0]), "Dropout enters the saved encounter and pauses travel");
                var selectedDock = ShipDestination.selected(ship);
                ctx.assertTrue(selectedDock != null && selectedDock.kind() == ShipDestination.Kind.PORT && selectedDock.reference().equals(vesselId[0]), "Investigate selects the encountered vessel's dock without another destination" + " search");
                var journey = ship.getCompoundOrEmpty("journey");
                journey.remove("encounterDockSelected");
                ship.put("destination", ShipDestination.region(server, "sol_luna").save());
                ctx.assertTrue(JourneyService.selectEncounterDock(c.player(), ship) && ShipDestination.selected(ship).reference().equals(vesselId[0]), "An older paused journey can select its dock without resetting the world");
                ship.put("destination", ShipDestination.region(server, "sol_luna").save());
                ctx.assertTrue(!JourneyService.selectEncounterDock(c.player(), ship) && ShipDestination.selected(ship).region().equals("sol_luna"), "The one-time selection respects later deliberate destination changes");
                ship.put("destination", selectedDock.save());
                paused[0] = ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -1);
                ctx.assertTrue(CourseService.active(ship), "The encounter course automatically continues into docking");
                phase[0] = 3;
            }
            else if (phase[0] == 3 && ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED") && !TransferLocks.inTransit(server, ship))
            {
                var port = DockingPorts.firstPort(level, ship);
                var at = port.anchors().getFirst();
                c.player().teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                DockingService.perform(c.player(), ship, port.controller(), "DOCK_BOARD");
                phase[0] = 4;
            }
            else if (phase[0] == 4 && !Boarding.busy(c.player()) && !TransferLocks.inTransit(server, ship) && !TransferLocks.inTransit(server, WorldStore.get(server).repository().find(vesselId[0])) && SceneService.observer(c.player()).getStringOr("id", "").equals(vesselId[0]))
            {
                var vessel = WorldStore.get(server).repository().find(vesselId[0]);
                if (encounterKind == 1)
                {
                    var rubble = WorldStore.pos(vessel, "base").offset(12, 1, 10);
                    ctx.assertTrue(StationAccess.mayEdit(c.player(), rubble), "Unclaimed freighter permits cargo access repairs");
                    level.setBlock(rubble, Blocks.AIR.defaultBlockState(), 18);
                    level.setBlock(rubble.above(), Blocks.AIR.defaultBlockState(), 18);
                }
                BlockPos suppliesAt;
                if (encounterKind == com.nullandkale.nullspace.model.galaxy.EncounterKind.ABANDONED_STATION.id)
                    suppliesAt = com.nullandkale.nullspace.worldgen.encounters.DerelictLoot.spots(com.nullandkale.nullspace.worldgen.encounters.SitePoints.read(vessel)).stream().filter(spot -> spot.use().equals("engineering")).findFirst().orElseThrow().pos();
                else
                    suppliesAt = ShipRooms.rooms(EncounterSites.design(vessel.getIntOr("encounterKind", 1), vessel.getLongOr("encounterSeed", 1))).stream().filter(r -> r.use() == ShipRooms.Use.ENGINEERING).findFirst().orElseThrow().supplies();
                var fuelLocker = WorldStore.pos(vessel, "base").offset(suppliesAt);
                var supplies = (Container) level.getBlockEntity(fuelLocker);
                if (supplies instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r)
                    r.unpackLootTable(c.player());
                int slot = -1;
                for (int i = 0; supplies != null && i < supplies.getContainerSize(); i++)
                    if (supplies.getItem(i).is(Items.COAL) || supplies.getItem(i).is(Items.CHARCOAL))
                    {
                        slot = i;
                        break;
                    }
                ctx.assertTrue(StationAccess.mayUse(c.player(), fuelLocker), "A visitor can use the engineering locker");
                ctx.assertTrue(slot >= 0, "Engineering's fuel locker contains fuel: design=" + vessel.getStringOr("encounterDesign", "") + ", seed=" + vessel.getLongOr("encounterSeed", 0) + ", stocked=" + vessel.getBooleanOr("encounterStocked", false) + ", block=" + level.getBlockState(fuelLocker) + ", items=" + (supplies == null ? "missing" : java.util.stream.IntStream.range(0, supplies.getContainerSize()).mapToObj(i -> supplies.getItem(i).toString()).toList()));
                var fuelItem = supplies.getItem(slot).getItem();
                c.player().getInventory().add(supplies.removeItem(slot, 1));
                int left = 0;
                for (int i = 0; i < supplies.getContainerSize(); i++)
                    left += supplies.getItem(i).getCount();
                remaining[0] = left;
                supplies.setChanged();
                cargo[0] = fuelLocker;
                ctx.assertTrue(cargo[0] != null && c.player().getInventory().contains(new net.minecraft.world.item.ItemStack(fuelItem)), "Actual coal transfers from cargo into player inventory");
                WorldStore.get(server).save();
                ctx.assertTrue(ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -2) == paused[0], "Docking and looting do not consume journey progress");
                phase[0] = 41;
            }
            else if (phase[0] == 41 && !Boarding.busy(c.player()) && !TransferLocks.inTransit(server, ship) && !TransferLocks.inTransit(server, WorldStore.get(server).repository().find(vesselId[0])))
            {
                var vessel = WorldStore.get(server).repository().find(vesselId[0]);
                var connected = WorldStore.pos(vessel, "base").offset(WorldStore.pos(ship.getCompoundOrEmpty("dock"), "targetPort"));
                var port = DockingPorts.ports(level, vessel).stream().filter(p -> p.controller().equals(connected)).findFirst().orElseThrow(() -> new AssertionError("The ship's connected return berth must remain present"));
                var at = port.anchors().getFirst();
                c.player().teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                DockingService.perform(c.player(), vessel, port.controller(), "DOCK_BOARD");
                phase[0] = 5;
            }
            else if (phase[0] == 5 && !Boarding.busy(c.player()) && SceneService.observer(c.player()) == ship)
            {
                c.board();
                var view = ShipControl.open(c.player(), WorldStore.pos(ship, "controller"), "Ready");
                ctx.assertTrue(view.location().contains("Luna") && view.details().contains("Undock"), "The docked controller previews the original destination and automatic departure");
                ctx.assertTrue(view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.RESUME && b.enabled()), "Continue journey is available while docked at an encounter: " + view.buttons().stream().filter(b -> b.action() == ShipControl.Action.RESUME).findFirst().orElseThrow().reason());
                var result = ShipControl.input(c.player(), new ControllerPackets.Input(view.session(), ShipControl.Action.RESUME, ""));
                ctx.assertTrue(result.startsWith("Course engaged"), "One Continue journey accepts undocking and the paid return course: " + result);
                phase[0] = 6;
            }
            else if (phase[0] == 6 && !ship.contains("dock"))
            {
                fuel[0] = FuelSystem.available(level, ship);
                phase[0] = 7;
            }
            else if (phase[0] == 7 && !NavigationService.pending(server, c.fixture().id) && JourneyService.cruising(ship))
            {
                ctx.assertTrue(fuel[0] - FuelSystem.available(level, ship) < 500, "Resume charges no second 2000-unit jump fee");
                ctx.assertTrue(ShipDestination.selected(ship).region().equals("sol_luna"), "Resume restores the paid destination even for an older journey without a saved" + " selection");
                ctx.assertTrue(ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -2) >= paused[0] && !JourneyService.contact(ship), "Progress survives the stop and the contact is consumed");
                ctx.assertTrue(count((Container) level.getBlockEntity(cargo[0])) == remaining[0], "Collected supplies never restock on resume");
                var j = ship.getCompoundOrEmpty("journey");
                j.putInt("elapsed", j.getIntOr("duration", 0));
                phase[0] = 8;
            }
            else if (phase[0] == 8 && !JourneyService.active(ship) && !NavigationService.pending(server, c.fixture().id))
            {
                ctx.assertTrue(LocalSpaces.address(ship).equals("sol_luna"), "Automatic arrival completes the original destination");
                // The signal fades once the journey that found it is over.
                if (WorldStore.get(server).section("encounters").contains(siteId[0]))
                    return;
                ctx.assertTrue(WorldStore.get(server).repository().find(vesselId[0]) == null && level.getBlockEntity(cargo[0]) == null, "After arrival the visited signal fades: its site is gone");
                phase[0] = 9;
                removeVessels(server, List.of(c.fixture().id, vesselId[0]));
                c.close();
                ctx.succeed();
            }
        });
        ctx.runAfterDelay(20, () ->
        {
            c.fixture().ship().putDouble("simYaw", 37);
            long nonce = 0;
            while (true)
            {
                var planned = JourneyPlan.between(server.overworld().getSeed(), "sol_terra", "sol_luna", 0, 0, nonce);
                if (planned.kind() == encounterKind && planned.hasSignal())
                    break;
                nonce += 0x9e3779b97f4a7c15L;
            }
            c.fixture().ship().putLong("nextSignalNonce", nonce);
            Jumps.jump(c.player(), WorldStore.pos(c.fixture().ship(), "controller"), "sol_luna");
            phase[0] = 0;
        });
    }
}
