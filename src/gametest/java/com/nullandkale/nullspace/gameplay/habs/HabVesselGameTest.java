package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.shipyard.HabGallery;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A hab in the gallery is a registered station, its Station Core is the real controller, and its systems see the hab and not the rock round it. */
public final class HabVesselGameTest
{
    @GameTest(environment = "nullspace:hab_vessel", maxTicks = 600000)
    public void aGalleryHabIsARegisteredStationAndItsStationCoreShowsItsPowerAirAndDoors(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        // The habs other tests left in this world stand where this one does; the one standing highest is the one a position is found in.
        WorldStore.get(server).ships().removeIf(t -> t instanceof net.minecraft.nbt.CompoundTag c && HabVessels.is(c));
        var job = HabGallery.start(server, StationSpec.Size.COMPACT, List.of(new HabGallery.Entry(Maker.RIVET, HabPlanner.Form.ARMS, StationSpec.Size.COMPACT, 2)), false);
        var tick = new Runnable[1];
        tick[0] = () ->
        {
            if (!job.step(HabGallery.PER_TICK))
            {
                ctx.runAfterDelay(1, () -> tick[0].run());
                return;
            }
            var placed = job.placed().getFirst();
            var plan = placed.build().hab().plan();
            var store = WorldStore.get(server);
            var vessel = store.repository().find(HabVessels.idAt(level, placed.origin()));
            ctx.assertTrue(vessel != null && StationAccess.station(vessel) && StationAccess.ready(vessel) && HabVessels.is(vessel), "The hab is a registered, ready station vessel");
            var core = WorldStore.pos(vessel, "controller");
            ctx.assertTrue(level.getBlockState(core).is(ModBlocks.STATION_CORE), "Its controller is a Station Core: " + level.getBlockState(core));
            var room = plan.rooms(Use.CONTROLLER_ROOM).getFirst().interior();
            var local = core.subtract(placed.origin());
            ctx.assertTrue(local.getX() >= room.x0() && local.getX() <= room.x1() && local.getZ() >= room.z0() && local.getZ() <= room.z1(), "The core stands in the controller room");
            ctx.assertTrue(store.at(level, core) == vessel, "The vessel is found from its core's position");
            // Its blocks are its own, not the rock: about as many as the build placed.
            long own = placed.build().plan().blocks().values().stream().filter(b -> !b.isAir()).count();
            var connected = ShipSystems.connected(level, vessel);
            ctx.assertTrue(connected.size() >= own * 0.99 && connected.size() <= own, "The vessel is the hab's blocks (" + own + ") and not the rock round it: " + connected.size());
            // The Station Core reports its power, air and doors, and does so in good time.
            var player = ctx.makeMockServerPlayerInLevel();
            long started = System.nanoTime();
            var text = StationService.status(player, vessel, core);
            long ms = (System.nanoTime() - started) / 1_000_000;
            com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB VESSEL status took {} ms: {}", ms, text.replace('\n', '|'));
            ctx.assertTrue(text.contains("Power:") && text.contains("Fuel:") && text.contains("Air:") && text.contains("Door nodes"), "The Station Core shows power, fuel, air and doors: " + text);
            ctx.assertTrue(ms < 5000, "The status is quick: " + ms + " ms");
            // A large vessel is saved from its own records: its air and seals are kept as runs, and neither is read back from the world.
            ShipSystems.flushLargeAir(server);
            var systems = vessel.getCompoundOrEmpty("systems");
            ctx.assertTrue(systems.getIntArray("airz").isPresent() && systems.getIntArray("sealz").isPresent() && systems.getIntArray("air").isEmpty(), "The air and the seals are saved as runs");
            ctx.assertTrue(systems.getIntArray("sealz").orElseThrow().length < 200_000, "The seals are a few runs, not a cell each");
            // A hab is given no free fuel; coal in a generator is fuel.
            ctx.assertTrue(FuelSystem.available(level, vessel) == 0, "No free fuel in a hab: " + FuelSystem.available(level, vessel));
            var generator = connected.stream().filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR)).findFirst().orElseThrow();
            ((MachineBlockEntity) level.getBlockEntity(generator)).setItem(3, new ItemStack(Items.COAL, 8));
            ctx.assertTrue(FuelSystem.available(level, vessel) > 0, "Coal in a generator is fuel for the hab");
            ctx.succeed();
        };
        ctx.runAfterDelay(1, tick[0]);
    }

    /** What a large hab costs the server tick while it runs, and what one block change costs: measured, since a player in one noticed lag. */
    @GameTest(environment = "nullspace:hab_vessel", maxTicks = 600000)
    public void aLargeHabCostsLittleOfTheServerTickWhileItRunsAndWhenABlockInItChanges(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        WorldStore.get(server).ships().removeIf(t -> t instanceof net.minecraft.nbt.CompoundTag c && HabVessels.is(c));
        var job = HabGallery.start(server, StationSpec.Size.LARGE, List.of(new HabGallery.Entry(Maker.BASTION, HabPlanner.Form.ARMS, StationSpec.Size.LARGE, 7)), false);
        var player = ctx.makeMockServerPlayerInLevel();
        var tick = new Runnable[1];
        var phase = new int[]{0, 0};
        var worst = new long[]{0, 0};
        var rebuilds = new long[]{0, 0};
        var total = new long[]{0, 0};
        var vessel = new net.minecraft.nbt.CompoundTag[1];
        var edit = new net.minecraft.core.BlockPos[1];
        tick[0] = () ->
        {
            if (phase[0] == 0)
            {
                if (!job.step(HabGallery.PER_TICK))
                {
                    ctx.runAfterDelay(1, () -> tick[0].run());
                    return;
                }
                var placed = job.placed().getFirst();
                vessel[0] = WorldStore.get(server).repository().find(HabVessels.idAt(level, placed.origin()));
                var core = WorldStore.pos(vessel[0], "controller");
                edit[0] = core.offset(2, 0, 2);
                phase[0] = 1;
            }
            // Always the store's current record: another test in this JVM may have replaced the store's records, and a runtime is kept per record.
            vessel[0] = WorldStore.get(server).repository().find(vessel[0].getStringOr("id", ""));
            long started = System.nanoTime();
            ShipSystems.tick(level, vessel[0]);
            long ms = (System.nanoTime() - started) / 1_000_000;
            int stage = phase[0] == 1 ? 0 : 1;
            worst[stage] = Math.max(worst[stage], ms);
            total[stage] += ms;
            phase[1]++;
            if (phase[0] == 1 && phase[1] == 200)
            {
                // A block next to a door's frame changes: stone where there was air, as a player placing a block would.
                level.setBlock(edit[0], net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(edit[0].above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                phase[0] = 2;
                phase[1] = 0;
                rebuilds[1] = ShipSystems.airRebuilds(level, vessel[0]);
            }
            if (phase[0] == 2 && phase[1] == 200)
            {
                rebuilds[0] = ShipSystems.airRebuilds(level, vessel[0]) - rebuilds[1];
                com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB VESSEL TICK steady: avg {} ms, worst {} ms over 200 ticks; after a block change: avg {} ms, worst {} ms over 200 ticks", total[0] / 200.0, worst[0], total[1] / 200.0, worst[1]);
                long flushed = System.nanoTime();
                ShipSystems.flushLargeAir(server);
                com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB VESSEL flush of the air and seals took {} ms", (System.nanoTime() - flushed) / 1_000_000);
                // What a block change must not do is work the vessel's air out in full on the server thread: its seals change a cell at a time and its air
                // is worked out on a worker. (The device list may be found again on the server thread: that reads a few thousand cells.) That is asserted
                // by counting full air passes, not milliseconds, which a slow machine would change.
                ctx.assertTrue(rebuilds[0] == 0, "A block change in a large hab works no air out on the server thread: " + rebuilds[0] + " full passes");
                ctx.succeed();
                return;
            }
            ctx.runAfterDelay(1, () -> tick[0].run());
        };
        ctx.runAfterDelay(1, tick[0]);
    }
}
