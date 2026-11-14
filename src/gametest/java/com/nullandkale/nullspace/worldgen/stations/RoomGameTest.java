package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Checks the room designs on the real block map: every room of every use, maker, variant and size is
 * furnished, its door has a plate on each side and a clear approach, a person can walk to the back
 * of it, every entity point refers to a real item and a free wall cell, and nothing is dark. Then the
 * gallery command itself runs and its frames, stands and animals appear.
 */
public final class RoomGameTest
{
    private static final int SMALL_W = 9, SMALL_D = 8, LARGE_W = 17, LARGE_D = 16;

    @GameTest(maxTicks = 1200)
    public void everyRoomIsFurnishedReachableAndLit(GameTestHelper ctx)
    {
        var problems = new ArrayList<String>();
        int checked = 0;
        for (var maker : Maker.values())
            for (var use : Use.values())
            {
                if (!StationRooms.furnishes(use))
                    continue;
                for (int variant = 0; variant < StationRooms.variants(use); variant++)
                    for (boolean large : new boolean[]{false, true})
                    {
                        String name = maker + " " + use + (StationRooms.variants(use) > 1 ? " " + StationRooms.variantName(use, variant) : "") + (large ? " large" : " small");
                        checked++;
                        try
                        {
                            check(name, maker, use, variant, large, problems);
                        }
                        catch (RuntimeException failure)
                        {
                            problems.add(name + ": " + failure);
                        }
                    }
            }
        ctx.assertTrue(problems.isEmpty(), checked + " rooms built; " + problems.size() + " problems, first: " + problems.stream().limit(30).toList());
        ctx.succeed();
    }

    private static void check(String name, Maker maker, Use use, int variant, boolean large, List<String> problems)
    {
        var spec = new StationSpec(maker, StationSpec.Purpose.HARBOR, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 1000L + use.ordinal() * 17L + variant);
        var plan = StationRooms.single(spec, use, large ? LARGE_W : SMALL_W, large ? LARGE_D : SMALL_D);
        var out = StationStructure.room(plan, variant);
        var blocks = out.blocks();
        var door = plan.doors().getFirst();
        var through = door.through();
        var inside = door.at().relative(through);
        // Plates on both sides of the door.
        for (var cell : List.of(door.at().relative(through), door.at().relative(through.getOpposite())))
            if (!(blocks.get(cell) != null && blocks.get(cell).getBlock() instanceof BasePressurePlateBlock))
                problems.add(name + ": no pressure plate beside the door at " + cell.toShortString());
        // The approach is clear: three wide, two deep, two high.
        for (int dv = 0; dv <= 1; dv++)
            for (int lateral = -1; lateral <= 1; lateral++)
                for (int row = 0; row <= 1; row++)
                {
                    var cell = inside.relative(through, dv).relative(through.getClockWise(), lateral).above(row);
                    var state = blocks.get(cell);
                    if (!StationStructureGameTest.open(blocks, cell) && !(state != null && state.getBlock() instanceof BasePressurePlateBlock))
                        problems.add(name + ": the approach is blocked at " + cell.toShortString());
                }
        var room = plan.room("room");
        var interior = room.interior();
        int furniture = 0;
        for (var e : blocks.entrySet())
        {
            var at = e.getKey();
            if (at.getX() >= interior.x0() && at.getX() <= interior.x1() && at.getZ() >= interior.z0() && at.getZ() <= interior.z1() && at.getY() >= interior.y0() && at.getY() <= interior.y1() && !e.getValue().isAir())
                furniture++;
        }
        if (furniture < 8)
            problems.add(name + ": only " + furniture + " blocks of furniture");
        // Containers sit on the floor in rows, never stacked; costly blocks are not scattered.
        int scanners = 0, brewers = 0, enchanters = 0;
        for (var e : blocks.entrySet())
        {
            var state = e.getValue();
            if ((state.is(Blocks.CHEST) || state.is(Blocks.BARREL)) && (blocks.get(e.getKey().above()) != null && (blocks.get(e.getKey().above()).is(Blocks.CHEST) || blocks.get(e.getKey().above()).is(Blocks.BARREL))))
                problems.add(name + ": containers are stacked at " + e.getKey().toShortString());
            if (state.is(com.nullandkale.nullspace.content.ModBlocks.LOCAL_SCANNER))
                scanners++;
            if (state.is(Blocks.BREWING_STAND))
                brewers++;
            if (state.is(Blocks.ENCHANTING_TABLE))
                enchanters++;
        }
        if (scanners > 1 || brewers > (large ? 2 : 1) || enchanters > 1)
            problems.add(name + ": too many costly blocks (" + scanners + " scanners, " + brewers + " brewing stands, " + enchanters + " enchanting tables)");
        // A person can walk to the back of the room.
        var reach = StationStructureGameTest.walk(blocks, inside);
        int deepest = 0;
        for (var cell : reach)
            if (cell.getX() >= interior.x0() && cell.getX() <= interior.x1() && cell.getZ() >= interior.z0() && cell.getZ() <= interior.z1())
                deepest = Math.max(deepest, (cell.getX() - inside.getX()) * through.getStepX() + (cell.getZ() - inside.getZ()) * through.getStepZ());
        int depth = interior.z1() - interior.z0() + 1;
        if (deepest < depth - 3)
            problems.add(name + ": the walkable floor reaches only " + deepest + " of " + depth + " deep");
        // Entity points: real items, facing a solid wall, standing in free space.
        for (var point : out.points())
        {
            var arguments = point.argument().split(" ");
            switch (point.name())
            {
                case "frame" ->
                {
                    var facing = Direction.byName(arguments[1]);
                    if (BuiltInRegistries.ITEM.getOptional(Identifier.parse(arguments[0])).isEmpty())
                        problems.add(name + ": unknown frame item " + arguments[0]);
                    else if (blocks.get(point.pos()) != null && !blocks.get(point.pos()).isAir())
                        problems.add(name + ": frame at " + point.pos().toShortString() + " shares its cell with " + blocks.get(point.pos()).getBlock());
                    else if (facing == null || !StationStructureGameTest.open(blocks, point.pos()) || blocks.get(point.pos().relative(facing.getOpposite())) == null || !blocks.get(point.pos().relative(facing.getOpposite())).isSolid())
                        problems.add(name + ": frame at " + point.pos().toShortString() + " has no free cell on a wall");
                }
                case "stand" ->
                {
                    if (!StationStructureGameTest.open(blocks, point.pos()) || !StationStructureGameTest.open(blocks, point.pos().above()))
                        problems.add(name + ": stand at " + point.pos().toShortString() + " is not in free space");
                }
                case "animal" ->
                {
                    if (!StationStructureGameTest.flat(blocks, point.pos()))
                        problems.add(name + ": animal at " + point.pos().toShortString() + " has nowhere to stand");
                }
                default ->
                    {
                    }
            }
        }
        var dark = StationStructureGameTest.darkCells(plan, blocks);
        if (!dark.isEmpty())
            problems.add(name + ": " + dark.size() + " standing cells below light " + StationLighting.MINIMUM + ", first " + dark.getFirst().toShortString());
    }

    @GameTest(maxTicks = 1200)
    public void theGalleryCommandPlacesRoomsAndTheirDisplays(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var player = ctx.makeMockServerPlayerInLevel();
        var source = server.createCommandSourceStack().withEntity(player);
        var dispatcher = server.getCommands().getDispatcher();
        var rooms = dispatcher.getRoot().getChild("nullspace").getChild("shipyard").getChild("rooms");
        ctx.assertTrue(rooms != null && rooms.getChild("station") != null && rooms.getChild("ship") != null, "The rooms command has station and ship parts");
        ctx.assertTrue(dispatcher.execute("nullspace shipyard rooms station bogus", source) == 0, "An unknown maker is refused");
        ctx.assertTrue(dispatcher.execute("nullspace shipyard rooms station rivet", source) == 1, "The station room gallery is placed");
        var receipt = WorldStore.read(server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-room-gallery.nbt"));
        var origin = BlockPos.of(receipt.getLongOr("origin", 0));
        // The command loads its chunks without a ticket, so hold them while the entities are counted.
        var level = server.overworld();
        var forced = new ArrayList<long[]>();
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + 180) >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + 260) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
                forced.add(new long[]{cx, cz});
            }
        ctx.runAfterDelay(100, () ->
        {
            var area = new AABB(origin).expandTowards(200, 40, 280);
            int frames = level.getEntitiesOfClass(ItemFrame.class, area).size();
            int stands = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, area).size();
            int animals = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, area).size();
            for (var chunk : forced)
                level.setChunkForced((int) chunk[0], (int) chunk[1], false);
            ctx.assertTrue(frames >= 20, "The gallery hung item frames: " + frames);
            ctx.assertTrue(stands >= 4, "The gallery stood suits on armor stands: " + stands);
            ctx.assertTrue(animals >= 2, "The gallery put animals in its pens: " + animals);
            ctx.succeed();
        });
    }
}
