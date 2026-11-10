package com.nullandkale.nullspace.worldgen.shipgen;

/**
 * What a generated ship cell is made of, before a maker's materials are chosen. The geometry code
 * only uses these, so layouts can be tested without the game's block registries.
 */
public enum Kind
{
    // Structure
    HULL, FRAME, WALL, FLOOR, CEILING, TRIM, ACCENT, GLASS, LIGHT, DOOR_LOWER, DOOR_UPPER, DOCK_BASE, STAIR, MOSS, WATER, VENT,
    // Furniture and fixtures
    BARREL, CHEST, CHEST_LEFT, CHEST_RIGHT, CRAFTING_TABLE, FURNACE, BLAST_FURNACE, SMOKER, ANVIL, ENCHANTING_TABLE, BOOKSHELF, BED, BED_FOOT, LECTERN, BREWING_STAND, CAULDRON, SMITHING_TABLE, STONECUTTER, LOOM, CARTOGRAPHY_TABLE, GRINDSTONE, FLETCHING_TABLE, COMPOSTER, FARMLAND, CROP, CROP2, CROP3, SEAT, TABLE, CARPET, PLANT,
    // The mod's machines
    COMPUTER, TERMINAL, SCANNER, MONITOR, CAMERA, GENERATOR, BATTERY, ENGINE, AIR_SUPPLY, STATION_CORE;

    /** Cells a player walks through never hold these, so they do not count as furniture. */
    public boolean solid()
    {
        return this != CARPET && this != WATER && this != CROP && this != CROP2 && this != CROP3 && this != SEAT && this != DOOR_LOWER && this != DOOR_UPPER;
    }

    /** True for the machines and fixtures that fill a room, as opposed to the shell. */
    public boolean fixture()
    {
        return ordinal() >= BARREL.ordinal();
    }
}
