package com.nullandkale.nullspace.worldgen.shipgen;

import net.minecraft.core.*;

/** An opening in the wall between two rooms: {@code at} is the wall cell at floor row, {@code across} points from one room to the other. */
public record Link(BlockPos at, Direction across, int width, int height)
{}
