package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.ShipBlocks;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The room designs. Each room that has one door and one job is furnished by a design for its use,
 * which scales with the room's size: a small room is one unit of the task, a large one repeats its
 * bays. Designs write blocks, and the entities a room shows (item frames, armor stands, paintings,
 * animals) as marked points. They place nothing in containers and never block the door's approach.
 */
public final class StationRooms
{
    /** True for uses that have a design: rooms with one door and one job. */
    public static boolean furnishes(Use use)
    {
        return switch (use)
        {
            case ARRIVAL, DOCK, CORRIDOR -> false;
            default -> true;
        };
    }

    /** How many variants a use has: stores come as general, seed and spares; growing bays and gardens as two; everything else is one design. */
    public static int variants(Use use)
    {
        return use == Use.STORES ? 3 : use == Use.GROWING_BAY || use == Use.GARDEN ? 2 : 1;
    }

    /**
     * Which variant of a room a generated station gets: stores follow the station's purpose (a seed
     * store for agriculture, spares for industry), and growing bays and gardens follow the seed.
     */
    public static int variantFor(StationSpec spec, Room room)
    {
        return switch (room.use())
        {
            case STORES -> spec.purpose() == StationSpec.Purpose.AGRICULTURE ? 1 : spec.purpose() == StationSpec.Purpose.INDUSTRY ? 2 : 0;
            case GROWING_BAY, GARDEN -> Math.floorMod(spec.seed() + room.id().hashCode(), 2);
            default -> 0;
        };
    }

    public static String variantName(Use use, int variant)
    {
        return switch (use)
        {
            case STORES -> new String[]{"general", "seed", "spares"}[Math.floorMod(variant, 3)];
            case GROWING_BAY -> new String[]{"crops", "orchard"}[Math.floorMod(variant, 2)];
            case GARDEN -> new String[]{"animal pen", "grove"}[Math.floorMod(variant, 2)];
            default -> "";
        };
    }

    /**
     * Furnishes {@code room}, entered through {@code door}, into {@code out}; entity points go to
     * {@code points}. Returns the cells its frames, stands, paintings and animals take, which lighting must leave alone.
     */
    public static Set<BlockPos> furnish(StationStyle style, ShipBlocks.Palette palette, Map<BlockPos, BlockState> out, List<Piece.Point> points, Room room, Door door, int variant, long seed)
    {
        var rng = new SplittableRandom(seed * 31 + room.id().hashCode());
        var kit = new RoomKit(style, palette, out, points, room, door, rng, variant);
        switch (room.use())
        {
            case BRIDGE -> RoomsCommand.bridge(kit);
            case ENGINEERING -> RoomsWork.engineering(kit);
            case WORKSHOP -> RoomsWork.workshop(kit);
            case FOUNDRY -> RoomsWork.foundry(kit);
            case CARGO_HOLD -> RoomsWork.cargoHold(kit);
            case RECEIVING_BAY -> RoomsWork.receivingBay(kit);
            case STORES -> RoomsWork.stores(kit);
            case MAINTENANCE -> RoomsWork.maintenance(kit);
            case COMMONS -> RoomsLiving.commons(kit);
            case KITCHEN -> RoomsLiving.kitchen(kit);
            case CABIN -> RoomsLiving.cabin(kit);
            case BUNK_ROOM -> RoomsLiving.bunkRoom(kit);
            case GROWING_BAY -> RoomsLiving.growingBay(kit);
            case GARDEN -> RoomsLiving.garden(kit);
            case INFIRMARY -> RoomsLiving.infirmary(kit);
            case LAB -> RoomsCommand.lab(kit);
            case ARCHIVE -> RoomsCommand.archive(kit);
            case OBSERVATION -> RoomsCommand.observation(kit);
            case LOOKOUT -> RoomsCommand.lookout(kit);
            case OPERATIONS -> RoomsCommand.operations(kit);
            case ARMORY -> RoomsCommand.armory(kit);
            case EVA_PREP -> RoomsCommand.evaPrep(kit);
            case BRIG -> RoomsCommand.brig(kit);
            default ->
                {
                }
        }
        return kit.entityCells;
    }

    /** A review brief: one room of {@code use} at the given outer size, behind a small lobby. */
    public static StationPlan single(StationSpec spec, Use use, int outerWidth, int outerDepth)
    {
        var lobby = new Room("lobby", Use.CORRIDOR, (outerWidth - 5) / 2, 0, 0, 5, 5, 1, null);
        var room = new Room("room", use, 0, 0, 4, outerWidth, outerDepth, 1, null);
        var door = StationBuilder.doorBetween(lobby, room);
        return new StationPlan(spec, outerWidth, 4 + outerDepth, StationPlan.PITCH + 2, List.of(lobby, room), List.of(door), List.of(), "lobby");
    }

    private StationRooms()
    {
    }
}
