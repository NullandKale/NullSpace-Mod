package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class HabFurnishingsGameTest
{
    @GameTest(maxTicks = 400)
    public void theSurfaceLobbyHasLockersAndSuitsAndPlantsAndTheDoorAndLadderStayClear(GameTestHelper ctx)
    {
        for (var maker : Maker.values())
            for (var size : new StationSpec.Size[]{StationSpec.Size.COMPACT, StationSpec.Size.STANDARD})
            {
                var cached = BuiltHabs.get(maker, StationSpec.Purpose.RESEARCH, size, 3);
                var hab = cached.hab();
                var build = cached.build();
                var blocks = build.plan().blocks();
                var hall = hab.entry().interior();
                int stand = hab.groundY() + 1, barrels = 0, ferns = 0;
                for (int x = hall.x0(); x <= hall.x1(); x++)
                    for (int z = hall.z0(); z <= hall.z1(); z++)
                    {
                        var state = blocks.get(new BlockPos(x, stand, z));
                        if (state == null)
                            continue;
                        ctx.assertTrue(!state.is(Blocks.CARTOGRAPHY_TABLE) && !state.is(Blocks.CARPET.red()), maker + " " + size + ": no map table or carpet in the lobby");
                        if (state.is(Blocks.BARREL))
                            barrels++;
                        else if (state.is(Blocks.POTTED_FERN))
                            ferns++;
                    }
                long suits = build.plan().points().stream().filter(p -> p.name().equals("stand") && hall.x0() <= p.pos().getX() && p.pos().getX() <= hall.x1() && p.pos().getY() == stand).count();
                ctx.assertTrue(barrels >= 6 && ferns == 2 && suits >= 2, maker + " " + size + ": lockers " + barrels + ", ferns " + ferns + ", suits " + suits);
                var inside = build.entrance().relative(build.outside().getOpposite());
                ctx.assertTrue(blocks.get(inside) != null && blocks.get(inside).is(Blocks.STONE_PRESSURE_PLATE), maker + " " + size + ": the cell behind the door is its plate");
                var ladder = blocks.entrySet().stream().filter(e -> e.getValue().is(Blocks.LADDER)).map(java.util.Map.Entry::getKey).min(java.util.Comparator.comparingInt(BlockPos::getY)).orElseThrow();
                var front = ladder.atY(stand).relative(blocks.get(ladder).getValue(net.minecraft.world.level.block.LadderBlock.FACING));
                ctx.assertTrue(blocks.get(front) == null || blocks.get(front).isAir(), maker + " " + size + ": the cell in front of the ladder is clear: " + blocks.get(front));
            }
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aStoresRoomFollowsTheWingItOpensOnto(GameTestHelper ctx)
    {
        int works = 0, other = 0;
        for (long seed = 1; seed <= 10; seed++)
        {
            var hab = HabPlanner.generate(new StationSpec(Maker.RIVET, StationSpec.Purpose.INDUSTRY, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, seed));
            for (var room : hab.plan().rooms())
                if (room.use() == Use.STORES)
                {
                    int variant = HabFurnishings.variant(hab.plan(), room);
                    ctx.assertTrue(variant >= 0 && variant <= 2, "A stores variant is one of three: " + variant);
                    if (variant == 2)
                        works++;
                    else
                        other++;
                }
        }
        ctx.assertTrue(works > 0, "Stores in the works are spares: " + works + " spares, " + other + " others");
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void theHabRoomsAreBuiltTheMachineHallHasItsMachinesTheControllerADaisAndTheFamiliesTheirBeds(GameTestHelper ctx)
    {
        int homes = 0, shrines = 0, bees = 0;
        for (var maker : Maker.values())
            for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.INDUSTRY})
                for (long seed = 1; seed <= 2; seed++)
                {
                    var cached = BuiltHabs.get(maker, purpose, StationSpec.Size.STANDARD, seed);
                    var hab = cached.hab();
                    var made = cached.build().plan();
                    var blocks = made.blocks();
                    var points = made.points();
                    for (var room : hab.plan().rooms())
                    {
                        var i = room.interior();
                        int generators = 0, batteries = 0, terminals = 0, cores = 0, beds = 0, polished = 0, hives = 0, names = 0;
                        for (int x = i.x0(); x <= i.x1(); x++)
                            for (int y = i.y0(); y <= i.y1(); y++)
                                for (int z = i.z0(); z <= i.z1(); z++)
                                {
                                    var state = blocks.get(new BlockPos(x, y, z));
                                    if (state == null)
                                        continue;
                                    if (state.is(ModBlocks.GENERATOR))
                                        generators++;
                                    else if (state.is(ModBlocks.BATTERY))
                                        batteries++;
                                    else if (state.is(ModBlocks.TERMINAL))
                                        terminals++;
                                    else if (state.is(ModBlocks.STATION_CORE))
                                        cores++;
                                    else if (state.is(Blocks.POLISHED_BLACKSTONE))
                                        polished++;
                                    else if (state.is(Blocks.BEEHIVE))
                                        hives++;
                                    else if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                                        beds++;
                                }
                        for (var point : points)
                            if (point.name().equals("frame") && point.argument().startsWith("minecraft:name_tag") && i.x0() <= point.pos().getX() && point.pos().getX() <= i.x1() && i.z0() <= point.pos().getZ() && point.pos().getZ() <= i.z1() && i.y0() <= point.pos().getY() && point.pos().getY() <= i.y1())
                                names++;
                        var label = maker + " " + purpose + " " + seed + " " + room.id();
                        switch (room.use())
                        {
                            case MACHINE_HALL -> ctx.assertTrue(generators >= 4 && batteries == generators && terminals >= 1, label + ": generators " + generators + ", batteries " + batteries + ", consoles " + terminals);
                            case CONTROLLER_ROOM -> ctx.assertTrue(polished >= 9 && cores >= 1, label + ": a dais of " + polished + " with " + cores + " Station Cores");
                            case FAMILY_QUARTERS ->
                            {
                                ctx.assertTrue(beds >= 4, label + ": beds " + beds);
                                homes++;
                            }
                            case CHAPEL ->
                            {
                                ctx.assertTrue(names >= 4, label + ": a wall of " + names + " names");
                                shrines++;
                            }
                            case APIARY ->
                            {
                                ctx.assertTrue(hives >= 2, label + ": hives " + hives);
                                bees++;
                            }
                            default ->
                                {
                                }
                        }
                    }
                }
        ctx.assertTrue(homes > 0 && shrines > 0 && bees > 0, "Homes " + homes + ", shrines " + shrines + ", apiaries " + bees);
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void aMineHeadOnTheDeepestFloorOpensOnACaveWithALadderAndOreAndAnyOtherIsGrated(GameTestHelper ctx)
    {
        int open = 0, capped = 0;
        for (var maker : Maker.values())
            for (long seed = 1; seed <= 8; seed++)
            {
                var build = BuiltHabs.get(maker, StationSpec.Purpose.INDUSTRY, StationSpec.Size.STANDARD, seed).build();
                for (var point : build.plan().points())
                {
                    if (!point.name().equals("mineshaft"))
                        continue;
                    var shaft = point.pos();
                    if (point.argument().equals("open"))
                    {
                        open++;
                        ctx.assertTrue(build.carve().contains(shaft.below(3)), maker + " " + seed + ": the shaft is dug below the floor");
                        long chamber = build.carve().stream().filter(c -> c.getY() < shaft.getY() - 11).count();
                        ctx.assertTrue(chamber > 300, maker + " " + seed + ": a chamber of " + chamber + " cells under the shaft");
                        int ladders = 0;
                        for (int y = shaft.getY() - 10; y < shaft.getY(); y++)
                            if (build.plan().blocks().get(new BlockPos(shaft.getX() - 1, y, shaft.getZ())) != null && build.plan().blocks().get(new BlockPos(shaft.getX() - 1, y, shaft.getZ())).is(Blocks.LADDER))
                                ladders++;
                        ctx.assertTrue(ladders >= 8, maker + " " + seed + ": a ladder down the shaft: " + ladders);
                        long ore = build.plan().blocks().values().stream().filter(s -> s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE) || s.is(Blocks.COAL_ORE)).count();
                        ctx.assertTrue(ore > 0, maker + " " + seed + ": ore in the cave walls");
                    }
                    else
                    {
                        capped++;
                        // A capped shaft digs no cave: nothing is dug below the deepest floor.
                        ctx.assertTrue(build.carve().stream().noneMatch(c -> c.getY() < 0), maker + " " + seed + ": a capped shaft goes nowhere");
                    }
                }
            }
        ctx.assertTrue(open > 0, "Some mine heads are on the deepest floor: " + open + " open, " + capped + " capped");
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void theWayThroughAPassedThroughRoomIsClearOfFurniture(GameTestHelper ctx)
    {
        int through = 0;
        for (var maker : Maker.values())
            for (var size : new StationSpec.Size[]{StationSpec.Size.COMPACT, StationSpec.Size.STANDARD})
                for (long seed = 1; seed <= 8; seed++)
                {
                    var brief = new StationSpec(maker, StationSpec.Purpose.HABITATION, size, StationSpec.Condition.OPERATIONAL, seed);
                    if (HabPlanner.formFor(brief) != HabPlanner.Form.SUITES)
                        continue;
                    var cached = BuiltHabs.get(brief);
                    var hab = cached.hab();
                    var blocks = cached.build().plan().blocks();
                    for (var room : hab.plan().rooms())
                    {
                        if (room.use() == Use.ARRIVAL || !StationRooms.furnishes(room.use()))
                            continue;
                        for (var door : hab.plan().doors())
                            if (door.a().equals(room.id()) || door.b().equals(room.id()))
                            {
                                var into = door.a().equals(room.id()) ? door.through().getOpposite() : door.through();
                                var cell = door.at().relative(into);
                                var state = blocks.get(cell);
                                boolean clear = state == null || state.isAir() || state.is(Blocks.STONE_PRESSURE_PLATE) || state.is(com.nullandkale.nullspace.gameplay.habs.HabDoors.PANEL) || state.is(Blocks.LANTERN);
                                ctx.assertTrue(clear, maker + " " + size + " " + seed + " " + room.id() + ": the cell inside a door is clear: " + state);
                                through++;
                            }
                    }
                }
        ctx.assertTrue(through > 20, "Doors checked: " + through);
        ctx.succeed();
    }
}
