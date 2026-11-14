package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Role;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Plans a ship as a small station laid on one straight spine, nose to tail: the bridge at the nose with the
 * docking door in its wall, a hall down the middle, rooms in mirrored pairs either side, engineering at the stern.
 * Every ship is held inside 32 blocks in each direction, two of them for the ramp up to the docking door.
 *
 * <ul>
 * <li>Small ships have one deck: two pairs of rooms, one large pair (12 deep) and one small pair (8 deep).</li>
 * <li>Medium and large ships stand on two to four decks joined by a stair hall behind the bridge. Each deck has a pair
 * of side rooms off its own hall, alternating large and small down the ship, a room at the nose above the bridge
 * and a room at the stern above engineering.</li>
 * </ul>
 */
public final class ShipPlanner
{
    /** The ramp to the docking door takes two blocks in front of the nose. */
    public static final int RAMP = 2, LIMIT = 32;
    private static final int HALL = 5, HUB = 11, NOSE = 7, PITCH = StationPlan.PITCH;

    /** The proportions of a size: the width of the bridge and engineering, how deep a large and a small side room are, and how long a deck's hall is. */
    private record Scale(int center, int largeDepth, int smallDepth, int hall, int lane)
    {
        /**
         * Rivet ships are plain spines. Halcyon ships have a wider bridge and engineering at the ends, and Bastion
         * ships a broader hall, so the makers' ships differ in outline as well as in materials.
         */
        static Scale of(ShipSpec.Size size, com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker maker)
        {
            int center = switch (size)
            {
                case SMALL -> 11;
                default -> 13;
            };
            int lane = maker == com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker.BASTION ? 7 : HALL;
            if (maker == com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker.HALCYON)
                center += 2;
            return switch (size)
            {
                case SMALL -> new Scale(center, 10, 7, 15, lane);
                case MEDIUM -> new Scale(center, 11, 8, 7, lane);
                case LARGE -> new Scale(center, 12, 8, 8, lane);
            };
        }
    }

    private record Pool(Use use, int weight)
    {}

    private static List<Pool> large(Role role)
    {
        return switch (role)
        {
            case FREIGHTER -> List.of(new Pool(Use.CARGO_HOLD, 6), new Pool(Use.WORKSHOP, 3), new Pool(Use.FOUNDRY, 3), new Pool(Use.RECEIVING_BAY, 2), new Pool(Use.COMMONS, 1));
            case EXPLORER -> List.of(new Pool(Use.LAB, 4), new Pool(Use.OBSERVATION, 3), new Pool(Use.GROWING_BAY, 3), new Pool(Use.ARCHIVE, 2), new Pool(Use.COMMONS, 2), new Pool(Use.WORKSHOP, 1));
            case SCOUT -> List.of(new Pool(Use.COMMONS, 3), new Pool(Use.OBSERVATION, 2), new Pool(Use.WORKSHOP, 2), new Pool(Use.BUNK_ROOM, 3), new Pool(Use.LAB, 1));
        };
    }

    private static List<Pool> small(Role role)
    {
        return switch (role)
        {
            case FREIGHTER -> List.of(new Pool(Use.STORES, 4), new Pool(Use.BUNK_ROOM, 3), new Pool(Use.KITCHEN, 2), new Pool(Use.MAINTENANCE, 2), new Pool(Use.EVA_PREP, 1), new Pool(Use.CABIN, 1));
            case EXPLORER -> List.of(new Pool(Use.CABIN, 3), new Pool(Use.BUNK_ROOM, 3), new Pool(Use.KITCHEN, 2), new Pool(Use.INFIRMARY, 2), new Pool(Use.STORES, 2), new Pool(Use.ARCHIVE, 1), new Pool(Use.GARDEN, 1), new Pool(Use.EVA_PREP, 1));
            case SCOUT -> List.of(new Pool(Use.BUNK_ROOM, 3), new Pool(Use.CABIN, 3), new Pool(Use.STORES, 3), new Pool(Use.KITCHEN, 2), new Pool(Use.EVA_PREP, 2), new Pool(Use.ARMORY, 1), new Pool(Use.LOOKOUT, 1));
        };
    }

    /** Draws rooms by weight, discouraging a use that is already on the ship while others remain. */
    private static final class Draw
    {
        private final SplittableRandom rng;
        private final EnumMap<Use, Integer> counts = new EnumMap<>(Use.class);

        Draw(long seed)
        {
            rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 0x5A1B);
        }

        Use next(List<Pool> pool, Use first)
        {
            if (first != null && counts.getOrDefault(first, 0) == 0)
            {
                counts.merge(first, 1, Integer::sum);
                return first;
            }
            double total = 0;
            var weights = new double[pool.size()];
            for (int i = 0; i < weights.length; i++)
            {
                weights[i] = pool.get(i).weight() / (1 + 2.5 * counts.getOrDefault(pool.get(i).use(), 0));
                total += weights[i];
            }
            double roll = rng.nextDouble() * total;
            int pick = 0;
            for (; pick < weights.length - 1 && roll >= weights[pick]; pick++)
                roll -= weights[pick];
            var use = pool.get(pick).use();
            counts.merge(use, 1, Integer::sum);
            return use;
        }
    }

    public static StationPlan generate(ShipSpec ship)
    {
        var spec = ship.station();
        var draw = new Draw(ship.seed());
        var rng = new SplittableRandom(ship.seed() ^ 0x7E57L);
        int decks = ship.size().decks + (ship.size() == ShipSpec.Size.LARGE && rng.nextBoolean() ? 1 : 0);
        var builder = new StationBuilder(spec, new Random(ship.seed()), decks, LIMIT, LIMIT - RAMP);
        // The signature room of the role goes in the first large slot.
        Use signature = switch (ship.role())
        {
            case FREIGHTER -> Use.CARGO_HOLD;
            case EXPLORER -> Use.LAB;
            case SCOUT -> Use.COMMONS;
        };
        var scale = Scale.of(ship.size(), ship.maker());
        int x = (LIMIT - scale.center()) / 2;
        var bridge = builder.place(Use.BRIDGE, x, 0, 0, scale.center(), NOSE, 1);
        if (bridge == null)
            throw new IllegalStateException("The bridge does not fit");
        builder.arrival = bridge.id();
        if (decks == 1)
            small(ship, scale, builder, draw, bridge, signature, rng.nextBoolean());
        else
            tall(ship, scale, builder, draw, bridge, decks, signature, rng.nextBoolean());
        var plan = builder.finish();
        if (plan == null)
            throw new IllegalStateException("Could not plan " + ship.description() + ": the plan failed validation");
        if (plan.width() > LIMIT || plan.depth() + RAMP > LIMIT || plan.height() > LIMIT)
            throw new IllegalStateException("Could not plan " + ship.description() + ": it is " + plan.width() + " x " + plan.height() + " x " + plan.depth() + " with its ramp");
        return plan;
    }

    private static void small(ShipSpec ship, Scale scale, StationBuilder b, Draw draw, Room bridge, Use signature, boolean largeFirst)
    {
        var hall = b.attachCentered(bridge, Direction.SOUTH, Use.CORRIDOR, scale.lane(), scale.hall(), 1, 0, null);
        require(hall, "hall");
        // Two pairs of rooms in mirrored places, one large and one small; the pairs share a wall.
        int alongA = largeFirst ? 9 : scale.hall() - 9 + 1, alongB = scale.hall() - alongA + 1;
        pair(b, scale, draw, hall, ship.role(), largeFirst, hall.z(), alongA, 0, signature);
        pair(b, scale, draw, hall, ship.role(), !largeFirst, hall.z() + alongA - 1, alongB, 0, null);
        require(b.attachCentered(hall, Direction.SOUTH, Use.ENGINEERING, scale.center(), NOSE, 1, 0, null), "engineering");
        b.berths.add(berth(bridge));
    }

    private static void tall(ShipSpec ship, Scale scale, StationBuilder b, Draw draw, Room bridge, int decks, Use signature, boolean largeFirst)
    {
        var band = b.rng.nextBoolean() ? Direction.WEST : Direction.EAST;
        var hub = b.attachCentered(bridge, Direction.SOUTH, Use.ARRIVAL, HUB, HUB, decks, 0, band.getCounterClockWise());
        require(hub, "stair hall");
        for (int level = 0; level < decks; level++)
        {
            int y = level * PITCH;
            var hall = b.attachCentered(hub, Direction.SOUTH, Use.CORRIDOR, scale.lane(), scale.hall(), 1, y, null);
            require(hall, "hall on deck " + level);
            // The ground deck's pair is large; the decks above alternate, so every ship has both sizes.
            boolean large = (level % 2 == 0) == largeFirst;
            pair(b, scale, draw, hall, ship.role(), large, hall.z(), scale.hall(), y, level == 0 ? signature : null);
            if (level == 0)
                require(b.attachCentered(hall, Direction.SOUTH, Use.ENGINEERING, scale.center(), NOSE, 1, 0, null), "engineering");
            else
            {
                // A room above the bridge at the nose, and one above engineering at the stern.
                require(b.attachCentered(hub, Direction.NORTH, draw.next(small(ship.role()), null), HUB, NOSE, 1, y, null), "nose room on deck " + level);
                require(b.attachCentered(hall, Direction.SOUTH, draw.next(small(ship.role()), null), scale.center(), NOSE, 1, y, null), "stern room on deck " + level);
            }
        }
        b.berths.add(berth(bridge));
    }

    /** A mirrored pair of rooms against a hall: the same size either side, each its own use. */
    private static void pair(StationBuilder b, Scale scale, Draw draw, Room hall, Role role, boolean large, int z, int along, int y, Use first)
    {
        int depth = large ? scale.largeDepth() : scale.smallDepth();
        var pool = large ? large(role) : small(role);
        for (var side : List.of(Direction.EAST, Direction.WEST))
            require(b.attachAt(hall, side, draw.next(pool, side == Direction.EAST ? first : null), along, depth, 1, y, null, z), (large ? "large" : "small") + " room " + side);
    }

    /** The docking door is in the middle of the bridge's nose wall; its approach is the single cell in front, since ships dock to stations, not the reverse. */
    private static Berth berth(Room bridge)
    {
        int cx = bridge.x() + bridge.w() / 2;
        var port = new BlockPos(cx, bridge.y() + 2, bridge.z());
        var front = port.north();
        return new Berth(bridge.id(), port, Direction.NORTH, new Box(front.getX(), front.getY(), front.getZ(), front.getX(), front.getY(), front.getZ()));
    }

    private static void require(Room room, String what)
    {
        if (room == null)
            throw new IllegalStateException("The ship's " + what + " does not fit");
    }

    private ShipPlanner()
    {
    }
}
