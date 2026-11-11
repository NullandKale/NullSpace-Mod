package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.core.save.*;
import com.nullandkale.nullspace.gameplay.ownership.*;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.RecordJournal;

import java.nio.file.Path;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Opt-in only. Ordinary verification returns immediately without creating a fixture or changing pacing. */
public final class PerformanceGameTest
{
    @GameTest(maxTicks = 1000000)
    public void run(GameTestHelper ctx) throws java.io.IOException
    {
        String scenario = System.getProperty("nullspace.perf.scenario", "");
        if (scenario.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var output = Path.of(System.getProperty("nullspace.perf.out"));
        if (scenario.equals("startup-seed") || scenario.equals("startup"))
        {
            PerfStartup.run(ctx, scenario, output);
            return;
        }
        int count = Integer.getInteger("nullspace.perf.ships", 40);
        if (count < 8 || count > 300)
            throw new IllegalArgumentException("Performance fixtures need 8..300 ships and stations");
        int stationCount = Math.max(1, count * 3 / 20);
        int mobileCount = count - stationCount;
        var server = ctx.getLevel().getServer();
        var world = new PerfWorld(server.overworld());
        world.requestedCount = count;
        world.launchedCount = Math.min(mobileCount, Integer.getInteger("nullspace.perf.launched", count * 3 / 5));
        for (int i = 0; i < Math.min(8, mobileCount); i++)
        {
            var crew = ctx.makeMockServerPlayerInLevel();
            crew.setItemSlot(EquipmentSlot.HEAD, new ItemStack(com.nullandkale.nullspace.content.ModBlocks.HELMET));
            crew.setItemSlot(EquipmentSlot.CHEST, new ItemStack(com.nullandkale.nullspace.content.ModBlocks.CHEST));
            crew.setItemSlot(EquipmentSlot.LEGS, new ItemStack(com.nullandkale.nullspace.content.ModBlocks.LEGS));
            crew.setItemSlot(EquipmentSlot.FEET, new ItemStack(com.nullandkale.nullspace.content.ModBlocks.BOOTS));
            world.crew.add(crew);
        }
        world.builder = ctx.makeMockServerPlayerInLevel();
        world.builder.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        int[] built = {0}, launched = {0};
        boolean[] stations = {false}, started = {false};
        PerfBuilds[] builds = {null};
        int[] quietTicks = {0};
        long setupStarted = System.nanoTime();
        String[] lastProgress = {""};
        ctx.onEachTick(() ->
        {
            if (started[0])
                return;
            try
            {
                world.refresh();
                String progress = "ships " + built[0] + "/" + mobileCount + ", launched " + launched[0] + "/" + world.launchedCount + ", stations ready " + world.stations.stream().filter(StationAccess::ready).count() + "/" + stationCount;
                if (!progress.equals(lastProgress[0]))
                {
                    System.getLogger(PerformanceGameTest.class.getName()).log(System.Logger.Level.INFO, "Performance setup: " + progress);
                    lastProgress[0] = progress;
                }
                if (System.nanoTime() - setupStarted > 600_000_000_000L)
                    throw new IllegalStateException("Performance fixture did not finish within ten minutes: " + progress);
                if (built[0] < mobileCount)
                {
                    var ship = world.build(built[0]++);
                    ship.actor = world.crew.get(ship.index % world.crew.size());
                    return;
                }
                if (!stations[0])
                {
                    world.stationFixture(stationCount);
                    if (scenario.equals("build"))
                        builds[0] = new PerfBuilds(world);
                    stations[0] = true;
                    return;
                }
                if (launched[0] < world.launchedCount)
                {
                    if (world.inSpace(world.ships.get(launched[0])))
                    {
                        launched[0]++;
                        return;
                    }
                    String problem = world.move(world.ships.get(launched[0]));
                    if (problem.isEmpty())
                        launched[0]++;
                    else
                        ctx.assertTrue(problem.contains("loading"), "Fixture launch must complete: " + problem);
                    return;
                }
                if (world.stations.stream().anyMatch(s -> !StationAccess.ready(s)) || builds[0] != null && !builds[0].prepare())
                    return;
                if (StationAccess.building(server))
                {
                    quietTicks[0] = 0;
                    return;
                }
                if (++quietTicks[0] < 40)
                    return;
                if (!world.parkCrew())
                    return;
                started[0] = true;
                server.saveEverything(true, true, true);
                if (scenario.equals("startup-fixture"))
                {
                    PerfStartup.fixture(ctx, world, output);
                    return;
                }
                begin(ctx, scenario, world, builds[0], output);
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    private static void begin(GameTestHelper ctx, String scenario, PerfWorld world, PerfBuilds builds, Path output)
    {
        var server = ctx.getLevel().getServer();
        PerfRun.Operation operation = null;
        double[] ramp = PerfRun.RAMP;
        int flat = 600;
        var store = WorldStore.get(server);
        switch (scenario)
        {
            case "idle" ->
                {
                }
            case "store-save" -> operation = count ->
            {
                var ship = world.ships.get(count % world.ships.size());
                store.repository().mutate(new ShipRepository.ShipId(ship.id), ShipRepository.Mutation.SIMULATION, candidate -> candidate.putDouble("simZ", candidate.getDoubleOr("simZ", 0) + 1));
                store.save();
                return "";
            };
            case "world-save" ->
            {
                operation = count ->
                {
                    server.saveEverything(true, true, true);
                    return "";
                };
                ramp = new double[]{0.1, 0.25, 0.5, 1};
                flat = 10;
            }
            case "move" -> operation = moves(world, 1);
            case "move-burst" ->
            {
                operation = moves(world, 4);
                ramp = new double[]{0.1, 0.25};
                flat = 5;
            }
            case "build" -> operation = builds;
            case "record" -> operation = count ->
            {
                var ship = world.record(world.ships.get(count % world.ships.size()));
                var owner = world.crew.stream().filter(p -> p.getUUID().toString().equals(ship.getStringOr("owner", ""))).findFirst().orElseThrow();
                var next = world.crew.get((world.crew.indexOf(owner) + 1) % world.crew.size());
                return RecordJournal.run(server, "perf-title-handover", () ->
                {
                    var title = ShipTitles.issue(ship);
                    ShipTitles.use(owner, title);
                    String result = ShipTitles.use(next, title);
                    if (!ShipTitles.owner(next, ship))
                        return result;
                    CrewRelease.authorize(ship, next);
                    return "";
                });
            };
            default -> throw new IllegalArgumentException("Unknown performance scenario: " + scenario);
        }
        new PerfRun(server, world, scenario, operation, ramp, flat, output, () ->
        {
            ctx.succeed();
            return true;
        }).start();
    }

    private static PerfRun.Operation moves(PerfWorld world, int size)
    {
        return new PerfRun.Operation()
        {
            private final List<PerfWorld.Ship> selected = new ArrayList<>();
            private final Set<Integer> finished = new HashSet<>();
            private final Map<Integer, Boolean> origin = new HashMap<>();

            @Override
            public String run(int ordinal)
            {
                if (selected.isEmpty())
                    for (int i = 0; i < size; i++)
                    {
                        var ship = world.movers().get((ordinal * size + i) % world.movers().size());
                        selected.add(ship);
                        origin.put(ship.index, world.inSpace(ship));
                    }
                for (var ship : selected)
                {
                    if (finished.contains(ship.index))
                        continue;
                    if (world.inSpace(ship) != origin.get(ship.index))
                    {
                        finished.add(ship.index);
                        continue;
                    }
                    String result = world.move(ship);
                    if (result.isEmpty())
                        finished.add(ship.index);
                    else if (!result.contains("loading"))
                        throw new IllegalStateException("Performance move failed: " + result);
                }
                if (finished.size() < selected.size())
                    return PerfRun.PENDING;
                selected.clear();
                finished.clear();
                origin.clear();
                return "";
            }
        };
    }
}
