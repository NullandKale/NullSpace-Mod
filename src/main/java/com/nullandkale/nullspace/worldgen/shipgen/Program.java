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

    private static List<RoomUse> wishes(Spec.Role role, int level, int decks)
    {
        if (decks > 1)
            return level == 0 ? switch (role)
            {
                case FREIGHTER -> List.of(CARGO, STORAGE, CARGO, SMELTING, CRAFTING);
                case EXPLORER -> List.of(CRAFTING, STORAGE, SMELTING, MINING, BREWING);
                default -> List.of(STORAGE, CRAFTING, SMELTING, BREWING);
            } : switch (role)
            {
                case FREIGHTER -> List.of(CREW, GALLEY, CREW, LOUNGE);
                case EXPLORER -> List.of(CREW, LAB, ENCHANTING, HYDROPONICS, CABIN, LOUNGE, GALLEY);
                default -> List.of(CREW, CABIN, LAB, LOUNGE, GALLEY);
            };
        return switch (role)
        {
            case FREIGHTER -> List.of(CARGO, CREW, CARGO, STORAGE, SMELTING, CARGO, GALLEY);
            case EXPLORER -> List.of(CREW, LAB, CRAFTING, HYDROPONICS, GALLEY, ENCHANTING, SMELTING, LOUNGE, MINING);
            default -> List.of(CREW, STORAGE, LAB, CRAFTING, BREWING, CABIN, SMELTING);
        };
    }

    /** The uses for {@code count} side-room pairs on one deck. */
    static List<RoomUse> sides(Spec spec, int level, int count, int length, int depth)
    {
        var random = new Random(spec.seed() * 31 + level * 7919L + 17);
        var wish = new ArrayList<>(wishes(spec.role(), level, spec.tier().decks));
        // Keep the first two as the role's identity; the rest vary with the seed.
        var tail = new ArrayList<>(wish.subList(Math.min(2, wish.size()), wish.size()));
        Collections.shuffle(tail, random);
        var ordered = new ArrayList<>(wish.subList(0, Math.min(2, wish.size())));
        ordered.addAll(tail);
        var out = new ArrayList<RoomUse>();
        for (var use : ordered)
            if (out.size() < count && fits(use, length, depth) && (!out.contains(use) || use == CARGO))
                out.add(use);
        for (var use : List.of(STORAGE, CREW, LOUNGE, CARGO))
            while (out.size() < count)
                out.add(use);
        return out;
    }

    private Program()
    {
    }
}
