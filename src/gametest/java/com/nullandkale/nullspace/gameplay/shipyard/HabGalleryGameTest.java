package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationStyle;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;

public final class HabGalleryGameTest
{
    @GameTest(maxTicks = 1200)
    public void everySmallHabInTheGalleryHasASurfaceDoorBuriedWallsAndAWalkWayDownToItsGrove(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var placed = HabGallery.build(server);
        ctx.assertTrue(placed.size() == HabGallery.MAKERS.size(), "A hab for each maker: " + placed.size());
        for (var hab : placed)
            check(ctx, server.overworld(), hab);
        ctx.succeed();
    }

    @GameTest(maxTicks = 2400)
    public void everyMediumHabInTheGalleryHasASurfaceDoorBuriedWallsAndAWalkWayDownToItsGrove(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var placed = HabGallery.build(server, com.nullandkale.nullspace.worldgen.stations.StationSpec.Size.STANDARD);
        for (var hab : placed)
            check(ctx, server.overworld(), hab);
        ctx.succeed();
    }

    /** A large hab is up to 512 by 512: it is built a slice a tick, as the command does, and then checked. */
    @GameTest(maxTicks = 600000)
    public void everyLargeHabInTheGalleryIsBuiltInSlicesAndHasASurfaceDoorBuriedWallsAndAWalkWayDownToItsGrove(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var job = HabGallery.start(server, com.nullandkale.nullspace.worldgen.stations.StationSpec.Size.LARGE);
        var tick = new Runnable[1];
        tick[0] = () ->
        {
            try
            {
                long started = System.currentTimeMillis();
                boolean finished = job.step(HabGallery.PER_TICK);
                com.nullandkale.nullspace.NullSpace.LOGGER.info("LARGE GALLERY tick {}: {} after {} ms", ctx.getTick(), job.progress(), System.currentTimeMillis() - started);
                if (!finished)
                {
                    // A new Runnable each time: the framework forgets a task that schedules itself again.
                    ctx.runAfterDelay(2, () -> tick[0].run());
                    return;
                }
            }
            catch (RuntimeException error)
            {
                com.nullandkale.nullspace.NullSpace.LOGGER.error("The large hab gallery failed in the test", error);
                throw error;
            }
            for (var hab : job.placed())
            {
                ctx.assertTrue(hab.build().plan().blocks().keySet().stream().mapToInt(BlockPos::getX).max().orElse(0) < HabPlanner.MAX_WIDTH, hab.label() + " is inside 512 blocks across");
                check(ctx, server.overworld(), hab);
            }
            ctx.succeed();
        };
        ctx.runAfterDelay(1, tick[0]);
    }

    /**
     * The gallery the plain command builds: a grid of every size and layout form, with the crew in, built a slice a tick as the command builds it.
     * Every hab is checked, and the whole gallery is timed, so a slow build shows up as a slow test.
     */
    @GameTest(maxTicks = 600000)
    public void theWholeGridGalleryIsBuiltAsTheCommandBuildsItAndEveryHabInItIsChecked(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        long started = System.nanoTime();
        int[] index = {0};
        // Each hab is checked as soon as it is placed and then let go: thirteen of them are too many blocks to hold at once.
        var job = HabGallery.start(server, com.nullandkale.nullspace.worldgen.stations.StationSpec.Size.STANDARD, HabGallery.EVERYTHING, true).checking(hab ->
        {
            ctx.assertTrue(hab.build().hab().form() == HabGallery.EVERYTHING.get(index[0]).form(), hab.label() + " has the form its cell asks for: " + hab.build().hab().form());
            check(ctx, server.overworld(), hab);
            index[0]++;
        });
        var tick = new Runnable[1];
        tick[0] = () ->
        {
            if (!job.step(HabGallery.PER_TICK))
            {
                ctx.runAfterDelay(1, () -> tick[0].run());
                return;
            }
            com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB GALLERY grid of {} habs built in {} ms", job.placed().size(), (System.nanoTime() - started) / 1_000_000);
            ctx.assertTrue(index[0] == HabGallery.EVERYTHING.size(), "A hab for every cell of the grid was checked: " + index[0]);
            ctx.succeed();
        };
        ctx.runAfterDelay(1, tick[0]);
    }

    /** Each form of layout stands in the gallery too: the small gallery in the stacked form (the others need room a small hab has not), checked like any other. */
    @GameTest(maxTicks = 600000)
    public void theSmallGalleryInTheStackedFormHasItsDoorsGroveAndWalkway(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var job = HabGallery.start(server, com.nullandkale.nullspace.worldgen.stations.StationSpec.Size.COMPACT, HabPlanner.Form.STACKED, false);
        while (!job.step(Integer.MAX_VALUE / 2))
        {
        }
        for (var hab : job.placed())
        {
            ctx.assertTrue(HabPlanner.formFor(hab.build().hab().plan().spec()) == HabPlanner.Form.STACKED, hab.label() + " is stacked");
            check(ctx, server.overworld(), hab);
        }
        ctx.succeed();
    }

    /** The medium gallery as a ring, with the crew in it: every hab is checked, the crew stand in its rooms and none are in the lobby. */
    @GameTest(maxTicks = 600000)
    public void theMediumGalleryInTheRingFormWithItsCrewHasItsDoorsGroveAndWalkway(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var job = HabGallery.start(server, com.nullandkale.nullspace.worldgen.stations.StationSpec.Size.STANDARD, HabPlanner.Form.RING, true);
        while (!job.step(Integer.MAX_VALUE / 2))
        {
        }
        for (var hab : job.placed())
        {
            var plan = hab.build().hab();
            ctx.assertTrue(HabPlanner.formFor(plan.plan().spec()) == HabPlanner.Form.RING, hab.label() + " is a ring");
            check(ctx, server.overworld(), hab);
            var box = new net.minecraft.world.phys.AABB(hab.origin().getX(), hab.origin().getY(), hab.origin().getZ(), hab.origin().getX() + plan.plan().width(), hab.origin().getY() + plan.plan().height(), hab.origin().getZ() + plan.plan().depth());
            var crew = server.overworld().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box, m -> m.isPersistenceRequired() && (m instanceof net.minecraft.world.entity.monster.Enemy || m instanceof com.nullandkale.nullspace.content.entity.RepairBot));
            ctx.assertTrue(!crew.isEmpty(), hab.label() + " has its crew in it: " + crew.size());
            int lobbyTop = hab.origin().getY() + plan.groundY();
            ctx.assertTrue(crew.stream().noneMatch(m -> m.getY() >= lobbyTop), hab.label() + " has no crew in the lobby");
        }
        ctx.succeed();
    }

    private static void check(GameTestHelper ctx, ServerLevel level, HabGallery.Placed hab)
    {
        var build = hab.build();
        var plan = hab.build().hab();
        var door = hab.door();
        var outside = build.outside();
        ctx.assertTrue(level.getBlockState(door).is(Blocks.IRON_DOOR) && level.getBlockState(door.above()).getBlock() instanceof DoorBlock, hab.label() + " has an iron door at the surface");
        ctx.assertTrue(level.getBlockState(door.below()).is(Blocks.STONE) || level.getBlockState(door.below()).is(Blocks.GRASS_BLOCK) || level.getBlockState(door.below()).is(Blocks.DIRT) || !level.getBlockState(door.below()).getCollisionShape(level, door.below()).isEmpty(), hab.label() + " stands level with the ground at its door");
        var inside = door.relative(outside.getOpposite());
        // Nothing is glazed under the ground: the glass of the maker's style is only in the surface story.
        var glass = StationStyle.of(plan.plan().spec().maker(), plan.plan().spec().seed()).glass;
        int buried = 0;
        for (var entry : build.plan().blocks().entrySet())
            if (entry.getValue().is(glass) && entry.getKey().getY() < plan.groundY())
                buried++;
        ctx.assertTrue(buried == 0, hab.label() + " has " + buried + " windows under the ground");
        // The tree's spot is on the deepest floor, in a garden.
        var tree = build.plan().points("tree");
        ctx.assertTrue(tree.size() == 1 && tree.getFirst().pos().getY() < 4, hab.label() + " has one tree spot on the deepest floor: " + tree);
        ctx.assertTrue(level.getBlockState(hab.origin().offset(tree.getFirst().pos())).is(com.nullandkale.nullspace.gameplay.trees.GoldenTrees.TRUNK) && level.getBlockState(hab.origin().offset(tree.getFirst().pos()).above(5)).is(Blocks.OAK_LOG) && level.getBlockState(hab.origin().offset(tree.getFirst().pos()).above(6)).is(com.nullandkale.nullspace.gameplay.trees.GoldenTrees.LEAVES), hab.label() + " has a golden apple tree standing at the centre of its grove");
        // The tree has one to three apples on it, put there when the hab was built.
        var treeCore = hab.origin().offset(tree.getFirst().pos());
        ctx.assertTrue(level.getBlockEntity(treeCore) instanceof com.nullandkale.nullspace.gameplay.trees.TreeBlockEntity apples && apples.apples() >= 1 && apples.apples() <= 3 && com.nullandkale.nullspace.gameplay.trees.GoldenTrees.fruitCount(level, treeCore) == apples.apples(), hab.label() + "'s tree has one to three apples on it");
        // One ladder climbs from the lowest floor to the top, in one column, with no gap.
        // (The ladder down a mine head's shaft is another, under the hab.)
        var shafts = build.plan().points("mineshaft").stream().map(p -> p.pos().west()).toList();
        var ladders = build.plan().blocks().entrySet().stream().filter(e -> e.getValue().is(Blocks.LADDER) && shafts.stream().noneMatch(s -> s.getX() == e.getKey().getX() && s.getZ() == e.getKey().getZ())).map(java.util.Map.Entry::getKey).sorted(java.util.Comparator.comparingInt(BlockPos::getY)).toList();
        int rungs = (plan.floors() - 1) * com.nullandkale.nullspace.worldgen.stations.StationPlan.PITCH + 1;
        ctx.assertTrue(ladders.size() == rungs && ladders.stream().allMatch(l -> l.getX() == ladders.getFirst().getX() && l.getZ() == ladders.getFirst().getZ()) && ladders.getLast().getY() - ladders.getFirst().getY() == rungs - 1, hab.label() + " has one ladder of " + rungs + " rungs through every floor: " + ladders.size());
        ctx.assertTrue(level.getBlockState(hab.origin().offset(ladders.getFirst())).is(Blocks.LADDER) && level.getBlockState(hab.origin().offset(ladders.getLast())).is(Blocks.LADDER), hab.label() + "'s ladder is in the world");
        // Cameras look out of the surface hall on all four sides, and one looks over the tree.
        int cameras = 0;
        for (var entry : build.plan().blocks().entrySet())
            if (entry.getValue().is(com.nullandkale.nullspace.content.ModBlocks.CAMERA))
            {
                cameras++;
                ctx.assertTrue(level.getBlockState(hab.origin().offset(entry.getKey())).is(com.nullandkale.nullspace.content.ModBlocks.CAMERA), hab.label() + "'s camera is in the world");
            }
        ctx.assertTrue(cameras >= 5, hab.label() + " has four cameras on its surface hall, one in the grove and more in its machine hall, controller room and plazas: " + cameras + " " + build.plan().blocks().entrySet().stream().filter(e -> e.getValue().is(com.nullandkale.nullspace.content.ModBlocks.CAMERA)).map(java.util.Map.Entry::getKey).toList());
        var groveRoom = plan.groveRoom();
        ctx.assertTrue(groveRoom.levels() == 2 && groveRoom.w() >= 19 && groveRoom.d() >= 19, hab.label() + " has a big two level grove: " + groveRoom.w() + " by " + groveRoom.d());
        // A person can walk from the door to the grove.
        var grove = plan.groveRoom().interior();
        int groveStand = hab.origin().getY() + plan.groveRoom().standY();
        ctx.assertTrue(hab.origin().getY() >= level.getMinY(), hab.label() + " is entirely above the bottom of the world");
        var reached = reach(level, inside, hab.origin().offset(grove.x0(), 0, grove.z0()), hab.origin().offset(grove.x1(), 0, grove.z1()), groveStand);
        ctx.assertTrue(reached, hab.label() + ": no walkway from the surface door to the grove");
    }

    @GameTest(maxTicks = 1500)
    public void theRoomsOfAHabAreLitByTheRealLightEngineLikeThoseOfAStation(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var hab = HabGallery.build(server).getFirst();
        var origin = hab.origin();
        var plan = hab.build().hab().plan();
        // Keep the hab's chunks loaded while the light engine settles.
        var chunks = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + plan.width()) >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + plan.depth()) >> 4; cz++)
                chunks.add(new net.minecraft.world.level.ChunkPos(cx, cz));
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        java.util.function.Supplier<int[]> measure = () ->
        {
            int standing = 0, lit = 0;
            for (var room : plan.rooms())
            {
                var i = room.interior();
                for (int story = 0; story < room.levels(); story++)
                {
                    int y = room.y() + story * com.nullandkale.nullspace.worldgen.stations.StationPlan.PITCH + 2;
                    for (int x = i.x0(); x <= i.x1(); x++)
                        for (int z = i.z0(); z <= i.z1(); z++)
                        {
                            var at = origin.offset(x, y, z);
                            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir() || level.getBlockState(at.below()).isAir())
                                continue;
                            standing++;
                            if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) >= 8)
                                lit++;
                        }
                }
            }
            return new int[]{lit, standing};
        };
        ctx.runAfterDelay(600, () ->
        {
            var m = measure.get();
            chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
            ctx.assertTrue(m[1] > 100 && m[0] >= m[1] * 0.95, "At least 95 percent of the standing cells have block light 8 or more: " + m[0] + " of " + m[1]);
            ctx.succeed();
        });
    }

    @GameTest(maxTicks = 2000)
    public void aHabKeepsItsLightWhenItsChunksUnloadAfterTheBuildAndLoadAgain(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var hab = HabGallery.build(server).getFirst();
        var origin = hab.origin();
        var plan = hab.build().hab().plan();
        // Nothing holds the chunks: after the build they fall out of memory and are saved, as when a player is far away.
        ctx.runAfterDelay(400, () ->
        {
            for (int cx = origin.getX() >> 4; cx <= (origin.getX() + plan.width()) >> 4; cx++)
                for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + plan.depth()) >> 4; cz++)
                    level.getChunk(cx, cz);
            ctx.runAfterDelay(100, () ->
            {
                int standing = 0, lit = 0;
                for (var room : plan.rooms())
                {
                    var i = room.interior();
                    for (int story = 0; story < room.levels(); story++)
                    {
                        int y = room.y() + story * com.nullandkale.nullspace.worldgen.stations.StationPlan.PITCH + 2;
                        for (int x = i.x0(); x <= i.x1(); x++)
                            for (int z = i.z0(); z <= i.z1(); z++)
                            {
                                var at = origin.offset(x, y, z);
                                if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir() || level.getBlockState(at.below()).isAir())
                                    continue;
                                standing++;
                                if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) >= 8)
                                    lit++;
                            }
                    }
                }
                ctx.assertTrue(standing > 100 && lit >= standing * 0.95, "After unloading and loading again, at least 95 percent of the standing cells have block light 8 or more: " + lit + " of " + standing);
                ctx.succeed();
            });
        });
    }

    /** True if standing cells lead from {@code start} to a standing cell inside the box on the row {@code stand}, stepping up or down one block at a time. */
    private static boolean reach(ServerLevel level, BlockPos start, BlockPos lo, BlockPos hi, int stand)
    {
        var seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var queue = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        int limit = 8_000_000;
        while (!queue.isEmpty() && limit-- > 0)
        {
            var at = BlockPos.of(queue.dequeueLong());
            if (at.getY() == stand && at.getX() >= lo.getX() && at.getX() <= hi.getX() && at.getZ() >= lo.getZ() && at.getZ() <= hi.getZ())
                return true;
            if (ladder(level, at))
                for (int step : new int[]{-1, 1})
                {
                    var next = at.above(step);
                    if (ladder(level, next) && seen.add(next.asLong()))
                        queue.enqueue(next.asLong());
                }
            for (var side : Direction.Plane.HORIZONTAL)
                for (int step = -1; step <= 1; step++)
                {
                    var next = at.relative(side).above(step);
                    if (seen.contains(next.asLong()) || !standable(level, next))
                        continue;
                    // Stepping up needs the head room over the starting cell as well.
                    if (step == 1 && !passable(level, at.above(2)))
                        continue;
                    seen.add(next.asLong());
                    queue.enqueue(next.asLong());
                }
        }
        return false;
    }

    private static boolean passable(ServerLevel level, BlockPos pos)
    {
        var state = level.getBlockState(pos);
        return state.isAir() || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock || state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean ladder(ServerLevel level, BlockPos pos)
    {
        return level.getBlockState(pos).is(Blocks.LADDER);
    }

    private static boolean standable(ServerLevel level, BlockPos pos)
    {
        if (ladder(level, pos))
            return true;
        var below = level.getBlockState(pos.below());
        boolean floor = !below.getCollisionShape(level, pos.below()).isEmpty() || below.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
        return passable(level, pos) && passable(level, pos.above()) && floor;
    }
}
