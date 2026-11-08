package com.nullandkale.nullspace.model.space;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpaceRayTest
{
    @Test
    void boxRejectsParallelMissAndKeepsNearFarOrdering()
    {
        assertNull(SpaceRay.box(new Vec3(-1, 16, 16), new Vec3(0, 0, 1), new Vec3(32, 32, 32), 100));
        assertArrayEquals(new double[]{10, 42}, SpaceRay.box(new Vec3(-10, 16, 16), new Vec3(1, 0, 0), new Vec3(32, 32, 32), 100), 1e-10);
    }

    @Test
    void solidBodyBlocksRayBeforeFarHull()
    {
        assertEquals(90, SpaceRay.sphere(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 100), 10), 1e-10);
        assertEquals(Double.POSITIVE_INFINITY, SpaceRay.sphere(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 100), 10));
    }
}
