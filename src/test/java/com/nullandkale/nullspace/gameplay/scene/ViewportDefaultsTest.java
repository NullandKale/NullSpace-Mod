package com.nullandkale.nullspace.gameplay.scene;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewportDefaultsTest
{
    @Test
    void aMonitorShowsTheCameraNearestToIt()
    {
        var cameras = List.of(new BlockPos(0, 0, 0), new BlockPos(40, 0, 0), new BlockPos(80, 0, 0));
        assertEquals(0, ViewportDefaults.nearest(cameras, new BlockPos(3, 2, 1)));
        assertEquals(1, ViewportDefaults.nearest(cameras, new BlockPos(30, 2, 5)));
        assertEquals(2, ViewportDefaults.nearest(cameras, new BlockPos(70, 0, 0)));
    }

    @Test
    void equalDistancesTakeTheFirstAndNoCamerasGiveZero()
    {
        var cameras = List.of(new BlockPos(0, 0, 0), new BlockPos(10, 0, 0));
        assertEquals(0, ViewportDefaults.nearest(cameras, new BlockPos(5, 0, 0)));
        assertEquals(0, ViewportDefaults.nearest(List.of(), new BlockPos(5, 0, 0)));
    }
}
