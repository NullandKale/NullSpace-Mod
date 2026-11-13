package com.nullandkale.nullspace.model.galaxy;

import java.util.Arrays;

/** Saved IDs are explicit: adding a signal must never reinterpret an existing journey. */
public enum EncounterKind
{
    GROWING_STATION(0, "Abandoned growing station", 5), DAMAGED_FREIGHTER(1, "Damaged freighter", 35), SALVAGE_SHIP(2, "Salvage ship", 35), DRIFTING_CARGO(3, "Drifting cargo", 20), ABANDONED_STATION(4, "Abandoned station", 5);

    public final int id;
    public final String label;
    private final int weight;

    EncounterKind(int id, String label, int weight)
    {
        this.id = id;
        this.label = label;
        this.weight = weight;
    }

    public static EncounterKind saved(int id)
    {
        return Arrays.stream(values()).filter(kind -> kind.id == id).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown encounter kind: " + id));
    }

    /** Stations together occupy one tenth of signals; this does not roll signal occurrence. */
    public static EncounterKind select(long seed)
    {
        int total = Arrays.stream(values()).mapToInt(kind -> kind.weight).sum();
        int roll = (int) Math.floorMod(seed >>> 16, total);
        for (var kind : values())
        {
            roll -= kind.weight;
            if (roll < 0)
                return kind;
        }
        throw new AssertionError("Encounter weights did not cover their roll");
    }

    public static boolean shipSalvage(int id)
    {
        return id == DAMAGED_FREIGHTER.id || id == SALVAGE_SHIP.id || id == DRIFTING_CARGO.id;
    }
}
