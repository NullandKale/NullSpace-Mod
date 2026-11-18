package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.gameplay.shipyard.HabGallery;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.BuiltHabs;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.PressurePlateBlock;

/**
 * The systems of a hab beyond its rooms: doors that only power opens, the depth ramp of crew and stock, the written logs, and the new rooms and
 * layout forms as built.
 */
public final class HabSystemsGameTest
{
    @GameTest(maxTicks = 200)
    public void aDoorPanelSignalsOnlyWhileAGeneratorOfTheHabHasFuelAndFuelIsBurnedToOpenIt(GameTestHelper ctx)
    {
        for (int x = 0; x < 6; x++)
            for (int z = 0; z < 4; z++)
                ctx.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        var generator = new BlockPos(1, 2, 1);
        var panel = new BlockPos(4, 2, 1);
        ctx.setBlock(generator, ModBlocks.GENERATOR);
        ctx.setBlock(panel, HabDoors.PANEL);
        var level = ctx.getLevel();
        var machine = (MachineBlockEntity) level.getBlockEntity(ctx.absolutePos(generator));
        ctx.assertTrue(machine != null && machine.generator(), "A generator stands in the test");
        ctx.assertTrue(HabDoors.link(level, ctx.absolutePos(panel), List.of(ctx.absolutePos(generator))), "The panel has a block entity to link");
        ctx.assertTrue(!HabDoors.powered(level, ctx.absolutePos(panel)), "A hab with no fuel in its generators has no power");
        ctx.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(4, 2, 1));
        ctx.runAfterDelay(15, () ->
        {
            ctx.assertTrue(!level.getBlockState(ctx.absolutePos(panel)).getValue(PressurePlateBlock.POWERED), "Somebody standing on a panel does nothing without power");
            machine.setItem(3, new ItemStack(Items.COAL, 4));
            ctx.assertTrue(HabDoors.powered(level, ctx.absolutePos(panel)), "Coal in a generator gives the hab power");
            int before = machine.available();
            ctx.runAfterDelay(15, () ->
            {
                ctx.assertTrue(level.getBlockState(ctx.absolutePos(panel)).getValue(PressurePlateBlock.POWERED), "With power the panel signals under somebody's feet");
                ctx.assertTrue(machine.available() < before, "Opening a door burned some fuel: " + before + " then " + machine.available());
                ctx.succeed();
            });
        });
    }

    @GameTest(maxTicks = 600)
    public void everyPoweredDoorHasAPanelEachSideAndTheOthersAStonePlateAndTheMachineHallIsNeverBehindOne(GameTestHelper ctx)
    {
        int forms = 0;
        for (var form : HabPlanner.Form.values())
            for (var maker : Maker.values())
            {
                var size = form == HabPlanner.Form.RING || form == HabPlanner.Form.BEADS ? StationSpec.Size.STANDARD : StationSpec.Size.COMPACT;
                var cached = BuiltHabs.get(HabGallery.spec(maker, size, form));
                var hab = cached.hab();
                // (The seed asked for gives the form unless that form failed for it and the hab fell back to the arms: that one is not this form's to check.)
                if (hab.form() != form)
                    continue;
                var blocks = cached.build().plan().blocks();
                var label = form + " " + maker;
                ctx.assertTrue(HabPlanner.problems(hab).isEmpty(), label + ": " + HabPlanner.problems(hab));
                boolean controllerDoor = false;
                for (var door : hab.plan().doors())
                {
                    var sideA = blocks.get(door.at().relative(door.through()));
                    var sideB = blocks.get(door.at().relative(door.through().getOpposite()));
                    boolean powered = hab.powered().contains(door);
                    // Two open rooms are one room: their door is an opening, with no plate.
                    if (hab.open().contains(door))
                    {
                        ctx.assertTrue(sideA == null || !sideA.is(Blocks.STONE_PRESSURE_PLATE), label + ": an opening between open rooms has no plate");
                        continue;
                    }
                    if (powered)
                    {
                        ctx.assertTrue(sideA != null && sideA.is(HabDoors.PANEL) && sideB != null && sideB.is(HabDoors.PANEL), label + ": a powered door has a panel on each side");
                        controllerDoor |= hab.plan().room(door.a()).use() == Use.CONTROLLER_ROOM || hab.plan().room(door.b()).use() == Use.CONTROLLER_ROOM;
                    }
                    else
                        ctx.assertTrue(sideA != null && sideA.is(Blocks.STONE_PRESSURE_PLATE) && sideB != null && sideB.is(Blocks.STONE_PRESSURE_PLATE), label + ": an ordinary door has a stone plate on each side: " + sideA + " " + sideB);
                }
                ctx.assertTrue(controllerDoor, label + ": the controller room's door is powered");
                var open = HabPlanner.reachableUnpowered(hab);
                for (var room : hab.plan().rooms(Use.MACHINE_HALL))
                    ctx.assertTrue(open.contains(room.id()), label + ": the machine hall can be reached without power");
                if (form == HabPlanner.Form.RING)
                    ctx.assertTrue(hab.powered().size() >= 3, label + ": a ring is closed by a powered door on each level, besides the controller's");
                forms++;
            }
        ctx.assertTrue(forms >= HabPlanner.Form.values().length * 2, "Most forms of most makers were checked: " + forms);
        // (Each maker can make each form, so the brief the gallery searches out has the form asked for.)
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void theDepthRampPutsMoreCrewAndBetterStockOnTheDeeperFloorsAndNoneInTheLobby(GameTestHelper ctx)
    {
        int[] rooms = new int[4], crew = new int[4];
        for (var maker : Maker.values())
            for (long seed = 1; seed <= 4; seed++)
            {
                var cached = BuiltHabs.get(maker, StationSpec.Purpose.RESEARCH, StationSpec.Size.STANDARD, seed);
                var hab = cached.hab();
                for (var room : hab.plan().rooms())
                    if (room.use() != Use.CORRIDOR && room.use() != Use.ARRIVAL)
                        rooms[HabDepth.dangerOf(hab, room)]++;
                var spawns = HabDepth.plan(hab, BlockPos.ZERO, seed);
                for (var spawn : spawns)
                {
                    ctx.assertTrue(spawn.danger() >= 1 && spawn.danger() == HabDepth.dangerOf(hab, hab.plan().room(spawn.room())), "Crew stand only on floors below the lobby, at their floor's danger");
                    crew[spawn.danger()]++;
                }
                for (var controller : hab.plan().rooms(Use.CONTROLLER_ROOM))
                {
                    long guards = spawns.stream().filter(s -> s.room().equals(controller.id())).count();
                    ctx.assertTrue(guards == HabDepth.dangerOf(hab, controller), "The controller room has a guard for each danger level: " + guards + " at " + HabDepth.dangerOf(hab, controller));
                }
                for (var room : hab.plan().rooms(Use.BUNK_ROOM))
                    ctx.assertTrue(spawns.stream().filter(s -> s.room().equals(room.id()) && s.kind() == 0).count() <= 2, "A dormitory holds one or two of the crew at most, never a row");
                // The stock: every chest takes a table that exists, at the tier of its floor, and the controller room's chest is the treasure.
                var points = cached.build().plan().points();
                for (var assignment : HabDepth.assignments(hab, BlockPos.ZERO, points, seed, "test"))
                    ctx.assertTrue(HabSystemsGameTest.class.getResource("/data/nullspace/loot_table/site/" + assignment.table() + ".json") != null, "The loot table " + assignment.table() + " exists");
                for (var spot : com.nullandkale.nullspace.worldgen.encounters.DerelictLoot.spots(points))
                    if (spot.use().equals("controller_room"))
                        ctx.assertTrue(HabDepth.assignments(hab, BlockPos.ZERO, points, seed, "test").stream().anyMatch(a -> a.table().startsWith("treasure_")), "The controller room holds the treasure");
            }
        ctx.assertTrue(rooms[1] > 0 && rooms[2] > 0 && rooms[3] > 0 && rooms[0] == 0, "Rooms by danger: " + java.util.Arrays.toString(rooms));
        double one = crew[1] / (double) rooms[1], two = crew[2] / (double) rooms[2], three = crew[3] / (double) rooms[3];
        ctx.assertTrue(one < two && two < three, "Crew per room rises with depth: " + one + ", " + two + ", " + three);
        ctx.succeed();
    }

    @GameTest(maxTicks = 300)
    public void theLogsAreWrittenAndTheLecternsThatHoldThemAreInTheRooms(GameTestHelper ctx)
    {
        for (var kind : List.of("family 7", "chapel 3", "machine 12", "controller 5"))
        {
            var book = HabLogs.book(kind);
            var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
            ctx.assertTrue(content != null && content.pages().size() >= 2, kind + " is a written book of at least two pages");
        }
        int lecterns = 0;
        for (var maker : Maker.values())
        {
            var made = BuiltHabs.get(maker, StationSpec.Purpose.HABITATION, StationSpec.Size.STANDARD, 2).build().plan();
            for (var log : made.points("log"))
            {
                ctx.assertTrue(made.blocks().get(log.pos()) != null && made.blocks().get(log.pos()).is(Blocks.LECTERN), "A log is marked on a lectern: " + made.blocks().get(log.pos()));
                lecterns++;
            }
        }
        ctx.assertTrue(lecterns >= 6, "Logs lie in the hab's rooms: " + lecterns);
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void theCameraWallTheGalleryTheQuarantineWardAndTheOreStoreAreBuiltAndTheKeyRoomsHaveACamera(GameTestHelper ctx)
    {
        int walls = 0, galleries = 0, wards = 0, stores = 0;
        for (var maker : Maker.values())
            for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.HABITATION})
                for (long seed = 1; seed <= 2; seed++)
                {
                    var cached = BuiltHabs.get(maker, purpose, StationSpec.Size.STANDARD, seed);
                    var hab = cached.hab();
                    var blocks = cached.build().plan().blocks();
                    for (var room : hab.plan().rooms())
                    {
                        var i = room.interior();
                        int monitors = 0, bars = 0, ore = 0, cameras = 0;
                        for (int x = i.x0(); x <= i.x1(); x++)
                            for (int y = i.y0(); y <= i.y1(); y++)
                                for (int z = i.z0(); z <= i.z1(); z++)
                                {
                                    var state = blocks.get(new BlockPos(x, y, z));
                                    if (state == null)
                                        continue;
                                    if (state.is(ModBlocks.MONITOR))
                                        monitors++;
                                    else if (state.is(Blocks.IRON_BARS))
                                        bars++;
                                    else if (state.is(Blocks.IRON_ORE) || state.is(Blocks.COPPER_ORE) || state.is(Blocks.COAL_ORE))
                                        ore++;
                                    else if (state.is(ModBlocks.CAMERA))
                                        cameras++;
                                }
                        var label = maker + " " + purpose + " " + seed + " " + room.id();
                        switch (room.use())
                        {
                            case OPERATIONS ->
                            {
                                ctx.assertTrue(monitors >= 6, label + ": a camera wall of screens: " + monitors);
                                walls++;
                            }
                            case OBSERVATION ->
                            {
                                ctx.assertTrue(monitors >= 6, label + ": a gallery wall of screens: " + monitors);
                                galleries++;
                            }
                            case INFIRMARY ->
                            {
                                if (bars > 0)
                                    wards++;
                            }
                            case CARGO_HOLD ->
                            {
                                ctx.assertTrue(ore >= 4, label + ": piles of ore: " + ore);
                                stores++;
                            }
                            case MACHINE_HALL, CONTROLLER_ROOM -> ctx.assertTrue(cameras >= 1, label + ": a camera: " + cameras);
                            default ->
                                {
                                }
                        }
                    }
                }
        ctx.assertTrue(walls > 0 && galleries > 0 && wards > 0 && stores > 0, "Camera walls " + walls + ", galleries " + galleries + ", wards " + wards + ", ore stores " + stores);
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aPlazaAndASuiteDoNotNeedALongFirstPassage(GameTestHelper ctx)
    {
        // Beads: on every arm there is a level whose first passage off the hall is short (the others clear the crossing arm's plaza with a long one).
        int arms = 0;
        for (var maker : Maker.values())
        {
            var hab = BuiltHabs.get(HabGallery.spec(maker, StationSpec.Size.STANDARD, HabPlanner.Form.BEADS)).hab();
            if (hab.form() != HabPlanner.Form.BEADS)
                continue;
            var hub = hab.entry();
            var shortest = new java.util.EnumMap<net.minecraft.core.Direction, Integer>(net.minecraft.core.Direction.class);
            for (var door : hab.plan().doors())
            {
                boolean first = door.a().equals(hub.id());
                if (!first && !door.b().equals(hub.id()))
                    continue;
                var other = hab.plan().room(first ? door.b() : door.a());
                if (other.use() != Use.CORRIDOR)
                    continue;
                var arm = first ? door.through() : door.through().getOpposite();
                shortest.merge(arm, Math.max(other.w(), other.d()), Math::min);
            }
            for (var entry : shortest.entrySet())
            {
                ctx.assertTrue(entry.getValue() <= 12, maker + ": an arm to the " + entry.getKey() + " has a short first passage: " + entry.getValue());
                arms++;
            }
        }
        ctx.assertTrue(arms >= 6, "Arms checked: " + arms);
        ctx.succeed();
    }
}
