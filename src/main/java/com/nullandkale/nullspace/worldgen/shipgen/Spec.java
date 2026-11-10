package com.nullandkale.nullspace.worldgen.shipgen;

/** Everything that decides a generated ship: the same spec always builds the same ship. */
public record Spec(Maker maker, Shape shape, Tier tier, Role role, long seed)
{
    /** The silhouette family: how rooms are arranged. */
    public enum Shape
    {
        /** One straight corridor, nose to tail, with rooms on both sides. */
        SPINE,
        /** A round commons under a glass dome, with rooms around it. */
        DOME,
        /** Two hulls with a bridge between them, joined by a gantry. */
        TWIN
    }

    /** How big the pieces are: the room size, not the ship count. */
    public enum Tier
    {
        SMALL(5, 5, 3, 1), MEDIUM(7, 7, 4, 2), LARGE(9, 7, 4, 3);

        /** Depth of a side room (away from its corridor) and its length along the ship. */
        public final int cell, length;
        public final int height, decks;

        Tier(int cell, int length, int height, int decks)
        {
            this.cell = cell;
            this.length = length;
            this.height = height;
            this.decks = decks;
        }
    }

    /** What the ship is for, which decides the rooms it gets. */
    public enum Role
    {
        SCOUT, FREIGHTER, EXPLORER
    }

    /** The makers, each with its own materials. */
    public enum Maker
    {
        RIVET("Rivet Works"), HALCYON("Halcyon Aerospace"), BASTION("Bastion Shipbuilding");

        public final String label;

        Maker(String label)
        {
            this.label = label;
        }
    }
}
