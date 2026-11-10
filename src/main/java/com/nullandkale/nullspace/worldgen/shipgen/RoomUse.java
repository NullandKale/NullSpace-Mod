package com.nullandkale.nullspace.worldgen.shipgen;

/** What a room is for. Every room is one complete task: it holds everything that task needs. */
public enum RoomUse
{
    BRIDGE("Bridge", "Helm, scanner, terminals and the forward view"), //
    ENTRY("Airlock", "The docking door, EVA lockers and a map table"), //
    ENGINEERING("Engineering", "Engines, generators, batteries, fuel and air"), //
    CARGO("Cargo hold", "Racks of barrels and crates"), //
    STORAGE("Storage", "Sorted chests along every wall"), //
    CREW("Crew quarters", "Bunks with lockers"), //
    CABIN("Captain's cabin", "A bed, a desk, bookshelves and a map wall"), //
    CRAFTING("Workshop", "Every workbench: crafting, smithing, stonecutting, looms and an anvil"), //
    SMELTING("Smelting bay", "A bank of furnaces, blast furnaces and smokers"), //
    GALLEY("Galley", "Smokers, food stores and a mess table with seats"), //
    VIEWING("Viewing room", "A giant monitor wall with seating"), //
    ENCHANTING("Enchanting library", "An enchanting table inside a full ring of bookshelves, with anvils"), //
    BREWING("Brewing lab", "Brewing stands, cauldrons and ingredient barrels"), //
    HYDROPONICS("Hydroponics", "Planters of crops with composters"), //
    LOUNGE("Lounge", "Seating, bookshelves and plants"), //
    LAB("Science lab", "Scanners, a monitor and sample storage"), //
    MINING("Mining bay", "Ore barrels, blast furnaces and a grindstone"), //
    HUB("Commons", "A round meeting hall under a glass dome"), //
    CORRIDOR("Corridor", "A straight walkway"), //
    STAIRS("Stairwell", "Stairs between decks");

    public final String label, description;

    RoomUse(String label, String description)
    {
        this.label = label;
        this.description = description;
    }

    /** Rooms people stay in and use as a destination; walkways and stairs are not. */
    public boolean destination()
    {
        return this != CORRIDOR && this != STAIRS;
    }
}
