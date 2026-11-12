package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import static com.nullandkale.nullspace.worldgen.shipgen.RoomUse.*;

/** Decides which rooms a ship gets: the role sets the wish list, the seed the order, and the room sizes what fits. */
final class Program
{
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

    private static List<RoomUse> wishes(Spec.Role role)
    {
        return switch (role)
        {
            case FREIGHTER -> List.of(CARGO, STORAGE);
            case EXPLORER -> List.of(CREW, LAB);
            default -> List.of(CREW, STORAGE);
        };
    }

    /** One bag for the whole vessel, including its fixed rooms and every deck. */
    static final class Bag
    {
        private final List<RoomUse> order = new ArrayList<>();
        private final EnumMap<RoomUse, Integer> used = new EnumMap<>(RoomUse.class);

        Bag(Spec spec)
        {
            var random = new Random(spec.seed() * 31 + 17);
            var preferred = wishes(spec.role());
            order.addAll(preferred);
            var rest = new ArrayList<>(List.of(CARGO, STORAGE, CREW, CABIN, CRAFTING, SMELTING, GALLEY, VIEWING, ENCHANTING, BREWING, HYDROPONICS, LOUNGE, LAB, MINING));
            rest.removeAll(order);
            Collections.shuffle(rest, random);
            order.addAll(rest);
        }

        void reserve(RoomUse use)
        {
            used.merge(use, 1, Integer::sum);
        }

        RoomUse next(int length, int depth)
        {
            RoomUse choice = order.stream().filter(use -> fits(use, length, depth)).min(Comparator.comparingInt(use -> used.getOrDefault(use, 0))).orElseThrow(() -> new IllegalArgumentException("No room fits " + length + " x " + depth));
            reserve(choice);
            return choice;
        }
    }

    private Program()
    {
    }
}
