package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Role;

import java.util.Locale;

/**
 * Everything that decides a planned ship: the same spec always builds the same ship. Size is the
 * ship's scale (how many decks it stands on); every size has large rooms and small rooms.
 */
public record ShipSpec(Maker maker, Size size, Role role, long seed)
{
    public enum Size
    {
        SMALL(1), MEDIUM(2), LARGE(3);

        /** The decks a ship of this size stands on; a large ship may add a fourth. */
        public final int decks;

        Size(int decks)
        {
            this.decks = decks;
        }
    }

    /** The station brief the ship's rooms are designed under: the role is its purpose, the size its band. */
    StationSpec station()
    {
        var purpose = switch (role)
        {
            case FREIGHTER -> StationSpec.Purpose.CARGO;
            case EXPLORER -> StationSpec.Purpose.RESEARCH;
            case SCOUT -> StationSpec.Purpose.HARBOR;
        };
        var band = switch (size)
        {
            case SMALL -> StationSpec.Size.COMPACT;
            case MEDIUM -> StationSpec.Size.STANDARD;
            case LARGE -> StationSpec.Size.LARGE;
        };
        return new StationSpec(maker, purpose, band, StationSpec.Condition.OPERATIONAL, seed);
    }

    public String description()
    {
        return maker.label + " " + size.name().toLowerCase(Locale.ROOT) + " " + role.name().toLowerCase(Locale.ROOT) + " (seed " + seed + ")";
    }
}
