package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import static com.nullandkale.nullspace.worldgen.shipgen.RoomUse.*;

/** Decides which rooms a ship gets: the role sets the wish list, the seed the order, and the room sizes what fits. */
final class Program
{
    /** Floor channels share their slab with the ceiling below, so wet rooms belong downstairs. */
    static void groundWaterRooms(Layout layout)
    {
        for (int i = 0; i < layout.spaces.size(); i++)
        {
            var wet = layout.spaces.get(i);
            if (wet.level == 0 || wet.use != HYDROPONICS)
                continue;
            int swap = -1;
            for (int j = 0; j < layout.spaces.size(); j++)
            {
                var dry = layout.spaces.get(j);
                if (dry.level == 0 && dry.use != HYDROPONICS && dry.use != BRIDGE && dry.use != ENTRY && dry.use != ENGINEERING && dry.use != HUB && dry.use != CORRIDOR && dry.use != STAIRS && fits(wet.use, dry.w, dry.d) && fits(dry.use, wet.w, wet.d))
                {
                    swap = j;
                    break;
                }
            }
            if (swap < 0)
                throw new IllegalStateException("No compatible first-floor room for " + wet.id);
            var dry = layout.spaces.get(swap);
            layout.spaces.set(swap, dry.withUse(wet.use));
            layout.spaces.set(i, wet.withUse(dry.use));
        }
    }

    /** How many windows a room's walls get: 0 none, 1 a few, 2 many. */
    static int windows(RoomUse use)
    {
        return switch (use)
        {
            case BRIDGE, VIEWING, LOUNGE, HUB, HYDROPONICS -> 2;
            case CABIN, CREW, GALLEY, LAB, ENTRY, ENCHANTING -> 1;
            default -> 0;
        };
    }

    /** True if a room this size can hold the use. {@code length} runs along the ship, {@code depth} away from the corridor. */
    static boolean fits(RoomUse use, int length, int depth)
    {
        return switch (use)
        {
            case CRAFTING -> length >= 7 && depth >= 5;
            case ENCHANTING -> length >= 5 && depth >= 7;
            case GALLEY, CREW, CABIN, LAB, BREWING, HYDROPONICS, LOUNGE -> depth >= 5 && length >= 5;
            default -> depth >= 4 && length >= 5;
        };
    }

    /** Relative draw weights, normalized over fitting rooms used the fewest times. */
    static int weight(Spec.Role role, RoomUse use)
    {
        return switch (role)
        {
            case FREIGHTER -> switch (use)
            {
                case CARGO, STORAGE -> 8;
                case CRAFTING, SMELTING, MINING -> 4;
                case CREW, GALLEY -> 3;
                default -> 1;
            };
            case EXPLORER -> switch (use)
            {
                case LAB, HYDROPONICS, VIEWING, CABIN, ENCHANTING -> 4;
                case CREW, BREWING, MINING -> 2;
                default -> 1;
            };
            case SCOUT -> switch (use)
            {
                case CREW, CABIN, STORAGE -> 4;
                case CRAFTING, GALLEY, LAB -> 2;
                default -> 1;
            };
        };
    }

    /** One bag for the whole vessel, including its fixed rooms and every deck. */
    static final class Bag
    {
        private static final List<RoomUse> ROOMS = List.of(CARGO, STORAGE, CREW, CABIN, CRAFTING, SMELTING, GALLEY, VIEWING, ENCHANTING, BREWING, HYDROPONICS, LOUNGE, LAB, MINING);
        private final EnumMap<RoomUse, Integer> used = new EnumMap<>(RoomUse.class);
        private final Spec.Role role;
        private final Random random;

        Bag(Spec spec)
        {
            random = new Random(spec.seed() * 31 + 17);
            role = spec.role();
        }

        void reserve(RoomUse use)
        {
            used.merge(use, 1, Integer::sum);
        }

        RoomUse next(int length, int depth)
        {
            var fitting = ROOMS.stream().filter(use -> fits(use, length, depth)).toList();
            int leastUsed = fitting.stream().mapToInt(use -> used.getOrDefault(use, 0)).min().orElseThrow(() -> new IllegalArgumentException("No room fits " + length + " x " + depth));
            var candidates = fitting.stream().filter(use -> used.getOrDefault(use, 0) == leastUsed).toList();
            int roll = random.nextInt(candidates.stream().mapToInt(use -> weight(role, use)).sum());
            for (var choice : candidates)
            {
                roll -= weight(role, choice);
                if (roll < 0)
                {
                    reserve(choice);
                    return choice;
                }
            }
            throw new IllegalStateException("Room draw exceeded its weight total");
        }
    }

    private Program()
    {
    }
}
