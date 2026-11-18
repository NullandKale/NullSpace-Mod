package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Pictures of the hab gallery (/nullspace shipyard habs): the surface pavilion of a hab from outside, the entry hall from inside, the foot of
 * the stair well on the deepest floor, and the first corridor off it. Taken in the first hab (Rivet, small), for review.
 */
public final class HabGalleryClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var hab = new HabGallery.Placed[1];
            var all = new java.util.List[1];
            world.getServer().runOnServer(server ->
            {
                var built = HabGallery.build(server);
                all[0] = built;
                hab[0] = built.getFirst();
            });
            var door = hab[0].door();
            var outside = hab[0].build().outside();
            var plan = hab[0].build().hab();
            var entry = plan.entry();
            var origin = hab[0].origin();
            shoot(c, world, door.relative(outside, 9), outside.getOpposite().toYRot(), 8, "nullspace-hab-surface-pavilion");
            shoot(c, world, door.relative(outside.getOpposite(), 2), outside.getOpposite().toYRot(), 12, "nullspace-hab-entry-hall");
            // The ladder shaft: stand in front of the foot of the ladder and look up it.
            var ladder = hab[0].build().plan().blocks().entrySet().stream().filter(e -> e.getValue().is(net.minecraft.world.level.block.Blocks.LADDER)).min(java.util.Comparator.comparingInt(e -> e.getKey().getY())).orElseThrow();
            var ladderFacing = ladder.getValue().getValue(net.minecraft.world.level.block.LadderBlock.FACING);
            shoot(c, world, origin.offset(ladder.getKey()).relative(ladderFacing, 4), ladderFacing.getOpposite().toYRot(), -28, "nullspace-hab-ladder-shaft");
            var i = entry.interior();
            var bottom = origin.offset((i.x0() + i.x1()) / 2, entry.standY(), (i.z0() + i.z1()) / 2);
            shoot(c, world, bottom, outside.toYRot(), -25, "nullspace-hab-atrium-from-below");
            // The first doorway off the hall on the deepest floor: stand in the hall's middle and look through it.
            var first = plan.plan().doors().stream().filter(d -> (d.a().equals(entry.id()) || d.b().equals(entry.id())) && d.at().getY() == 2).findFirst().orElseThrow();
            var through = first.a().equals(entry.id()) ? first.through() : first.through().getOpposite();
            shoot(c, world, bottom, through.toYRot(), 2, "nullspace-hab-deep-doorway");
            // A corridor of each wing, from its start toward its end, so the colour of the wing shows down the floor.
            for (var wing : com.nullandkale.nullspace.worldgen.stations.HabPlanner.Wing.values())
            {
                var corridor = plan.plan().rooms().stream().filter(r -> r.use() == com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.CORRIDOR && r.y() == 0 && com.nullandkale.nullspace.worldgen.stations.HabPlanner.wingOf(plan.plan(), r) == wing).findFirst();
                if (corridor.isEmpty())
                    continue;
                var ci = corridor.get().interior();
                boolean alongX = ci.x1() - ci.x0() >= ci.z1() - ci.z0();
                var start = alongX ? new BlockPos(ci.x0() + 1, 2, (ci.z0() + ci.z1()) / 2) : new BlockPos((ci.x0() + ci.x1()) / 2, 2, ci.z0() + 1);
                shoot(c, world, origin.offset(start), alongX ? 270 : 0, 12, "nullspace-hab-corridor-" + wing.name().toLowerCase());
            }
            // One room of each wing, from just inside its door.
            for (var wing : com.nullandkale.nullspace.worldgen.stations.HabPlanner.Wing.values())
            {
                var room = plan.plan().rooms().stream().filter(r -> com.nullandkale.nullspace.worldgen.stations.HabPlanner.Wing.of(r.use()) == wing && r.use() != com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.CORRIDOR && r.use() != com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.ARRIVAL && r.levels() == 1 && !r.id().equals(plan.grove())).findFirst();
                if (room.isEmpty())
                    continue;
                var roomDoor = plan.plan().doors().stream().filter(d -> d.a().equals(room.get().id()) || d.b().equals(room.get().id())).findFirst().orElseThrow();
                var inward = roomDoor.b().equals(room.get().id()) ? roomDoor.through() : roomDoor.through().getOpposite();
                shoot(c, world, origin.offset(roomDoor.at()).relative(inward, 2), inward.toYRot(), 12, "nullspace-hab-room-" + wing.name().toLowerCase() + "-" + room.get().use().name().toLowerCase());
            }
            // Each of the rooms only a hab has, in whichever of the three habs has one, from just inside its door; and the shaft of a mine head and the cave under it.
            var seen = new java.util.HashSet<com.nullandkale.nullspace.worldgen.stations.StationPlan.Use>();
            for (var any : (java.util.List<HabGallery.Placed>) all[0])
            {
                var habPlan = any.build().hab().plan();
                for (var room : habPlan.rooms())
                {
                    var use = room.use();
                    if (!java.util.Set.of(com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.CONTROLLER_ROOM, com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.MACHINE_HALL, com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.FAMILY_QUARTERS, com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.CHAPEL, com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.MINE_HEAD, com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.APIARY).contains(use) || !seen.add(use))
                        continue;
                    var roomDoor = habPlan.doors().stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).findFirst().orElseThrow();
                    var inward = roomDoor.b().equals(room.id()) ? roomDoor.through() : roomDoor.through().getOpposite();
                    shoot(c, world, any.origin().offset(roomDoor.at()).relative(inward, 2), inward.toYRot(), 14, "nullspace-hab-new-" + use.name().toLowerCase());
                    if (use == com.nullandkale.nullspace.worldgen.stations.StationPlan.Use.MINE_HEAD)
                        for (var point : any.build().plan().points())
                            if (point.name().equals("mineshaft") && point.argument().equals("open"))
                                shoot(c, world, any.origin().offset(point.pos()).relative(inward.getOpposite(), 0).above(), inward.toYRot(), 80, "nullspace-hab-new-mine-shaft-down");
                }
            }
            // The grove: stand just inside its door and look in, then back from the tree and look at it.
            var grove = plan.plan().doors().stream().filter(d -> d.a().equals(plan.grove()) || d.b().equals(plan.grove())).findFirst().orElseThrow();
            var into = grove.b().equals(plan.grove()) ? grove.through() : grove.through().getOpposite();
            shoot(c, world, origin.offset(grove.at()).relative(into, 2), into.toYRot(), -4, "nullspace-hab-grove-entering");
            var gi = plan.groveRoom().interior();
            var middle = origin.offset((gi.x0() + gi.x1()) / 2, plan.groveRoom().standY(), (gi.z0() + gi.z1()) / 2);
            // Let the tree have been left for a week: all eight apples.
            var core = origin.offset(hab[0].build().plan().points("tree").getFirst().pos());
            world.getServer().runOnServer(server ->
            {
                if (server.overworld().getBlockEntity(core) instanceof com.nullandkale.nullspace.gameplay.trees.TreeBlockEntity tree)
                {
                    tree.advanceNow(server.overworld());
                    tree.awayFor(com.nullandkale.nullspace.gameplay.trees.TreeGrowth.DAY * 8);
                    tree.advanceNow(server.overworld());
                }
            });
            shoot(c, world, middle.relative(into.getOpposite(), 8), into.toYRot(), -14, "nullspace-hab-grove-tree");
            shoot(c, world, middle.relative(into.getOpposite(), 3).above(6), into.toYRot(), 35, "nullspace-hab-grove-from-above");
        }
        ClientTestEvidence.passed(getClass());
    }

    private static void shoot(ClientGameTestContext c, net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world, BlockPos at, float yaw, float pitch, String name)
    {
        world.getServer().runOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            p.setNoGravity(true);
            p.teleportTo((ServerLevel) p.level(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), yaw, pitch, true);
        });
        c.waitTicks(160);
        world.getConnection().waitForChunksRender();
        c.takeScreenshot(name);
    }
}
