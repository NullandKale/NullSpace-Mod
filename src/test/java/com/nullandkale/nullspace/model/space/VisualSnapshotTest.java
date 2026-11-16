package com.nullandkale.nullspace.model.space;

import java.util.Random;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A snapshot the server builds must always pass the check the client makes on it, however full a volume is. */
class VisualSnapshotTest
{
    private static CompoundTag visual(int cell, int padding)
    {
        var data = new CompoundTag();
        data.putString("id", "minecraft:sign");
        data.putString("text", "x".repeat(padding));
        var visual = new CompoundTag();
        visual.putInt("cell", cell);
        visual.put("data", data);
        return visual;
    }

    @Test
    void theOldSumUnderCountsEachListElementByItsOverhead()
    {
        var list = new ListTag();
        list.add(visual(0, 50));
        var next = visual(1, 50);
        int predicted = list.sizeInBytes() + next.sizeInBytes();
        list.add(next);
        assertEquals(predicted + 4, list.sizeInBytes(), "A list charges every element four bytes the old sum left out, so a visual accepted at the limit could push the list over it");
    }

    @Test
    void aFullVolumeStaysWithinTheBudgetTheClientEnforces()
    {
        for (long seed = 0; seed < 400; seed++)
        {
            var random = new Random(seed);
            var collector = new VisualSnapshot.Collector();
            for (int cell = 0; cell < 4000; cell++)
                collector.accept(visual(cell, random.nextInt(900)));
            assertDoesNotThrow(() -> VisualSnapshot.validate(collector.entities(), 32768), "seed " + seed + " built " + collector.entities().size() + " visuals, " + collector.entities().sizeInBytes() + " bytes");
            assertTrue(collector.entities().sizeInBytes() <= VisualSnapshot.MAX_BYTES && collector.entities().size() <= VisualSnapshot.MAX_ENTITIES);
        }
    }

    @Test
    void anOversizedVisualIsSkippedAndSmallerOnesStillFit()
    {
        var collector = new VisualSnapshot.Collector();
        assertFalse(collector.accept(visual(0, VisualSnapshot.MAX_BYTES)));
        assertTrue(collector.accept(visual(1, 10)));
        assertEquals(1, collector.entities().size());
    }

    @Test
    void whenTheBudgetRunsOutFurnitureGoesBeforeMachinesAndDecorations()
    {
        var collector = new VisualSnapshot.Collector();
        // Far more furniture than fits, at the lowest cells, then a few machines and decorations at higher cells.
        for (int cell = 0; cell < 300; cell++)
            collector.offer(VisualSnapshot.priority("minecraft:chest"), visual(cell, 100));
        for (int i = 0; i < 5; i++)
        {
            collector.offer(VisualSnapshot.priority("nullspace:monitor"), visual(1000 + i, 100));
            collector.offer(VisualSnapshot.priority("minecraft:banner"), visual(2000 + i, 100));
        }
        var kept = collector.entities();
        int machines = 0, banners = 0, chests = 0;
        for (var tag : kept)
        {
            int cell = ((CompoundTag) tag).getIntOr("cell", 0);
            machines += cell >= 1000 && cell < 2000 ? 1 : 0;
            banners += cell >= 2000 ? 1 : 0;
            chests += cell < 1000 ? 1 : 0;
        }
        assertEquals(5, machines, "every machine is kept");
        assertEquals(5, banners, "every decoration is kept");
        assertTrue(chests > 0 && chests < 300, "chests fill what is left: " + chests);
        assertDoesNotThrow(() -> VisualSnapshot.validate(kept, 32768));
        assertEquals(kept, collector.entities(), "reading it again gives the same snapshot");
    }

    @Test
    void theOrderOfPrioritiesIsMachinesThenDecorationsThenFurniture()
    {
        assertTrue(VisualSnapshot.priority("nullspace:monitor") < VisualSnapshot.priority("minecraft:banner"));
        assertTrue(VisualSnapshot.priority("minecraft:banner") < VisualSnapshot.priority("minecraft:bed"));
        for (String furniture : new String[]{"minecraft:bed", "minecraft:sign", "minecraft:hanging_sign", "minecraft:chest", "minecraft:barrel"})
            assertEquals(2, VisualSnapshot.priority(furniture), furniture);
    }
}
