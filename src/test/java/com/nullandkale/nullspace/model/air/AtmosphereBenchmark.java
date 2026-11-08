package com.nullandkale.nullspace.model.air;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Opt-in repeatable microbenchmark. Timing is evidence, never a flaky build/test threshold. */
public final class AtmosphereBenchmark
{
    private static volatile long blackhole;

    private record Fixture(String name, boolean[] seals, int[] air)
    {}

    public static void main(String[] args) throws Exception
    {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        String label = args.length > 1 ? args[1] : "current";
        List<Fixture> fixtures = new ArrayList<>();
        boolean[] hull = new boolean[Atmosphere.CELLS];
        for (int y = 4; y <= 27; y++)
            for (int z = 4; z <= 27; z++)
                for (int x = 4; x <= 27; x++)
                    hull[Atmosphere.index(x, y, z)] = x == 4 || x == 27 || y == 4 || y == 27 || z == 4 || z == 27;
        int[] air = new int[Atmosphere.CELLS];
        Arrays.fill(air, -1);
        Atmosphere.rooms(hull, air, true);
        fixtures.add(new Fixture("sealed-cabin", hull.clone(), air.clone()));
        boolean[] decks = hull.clone();
        for (int y : new int[]{10, 17, 23})
            for (int z = 5; z < 27; z++)
                for (int x = 5; x < 27; x++)
                    if (x != 15 || z != 15)
                        decks[Atmosphere.index(x, y, z)] = true;
        int[] deckAir = air.clone();
        Atmosphere.rooms(decks, deckAir, false);
        fixtures.add(new Fixture("multi-deck", decks, deckAir));
        boolean[] breached = hull.clone();
        breached[Atmosphere.index(4, 16, 16)] = false;
        fixtures.add(new Fixture("breached-cabin", breached, air.clone()));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocations = bean.isThreadAllocatedMemorySupported();
        if (allocations && !bean.isThreadAllocatedMemoryEnabled())
            bean.setThreadAllocatedMemoryEnabled(true);
        List<String> records = new ArrayList<>();
        for (var fixture : fixtures)
        {
            for (int i = 0; i < 40; i++)
                rebuild(fixture);
            long[] times = new long[160];
            long allocated = 0;
            for (int i = 0; i < times.length; i++)
            {
                long beforeBytes = allocations ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0, start = System.nanoTime();
                rebuild(fixture);
                times[i] = System.nanoTime() - start;
                if (allocations)
                    allocated += bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes;
            }
            Arrays.sort(times);
            String record = String.format(Locale.ROOT, "{\"scenario\":\"%s\",\"median_ms\":%.4f,\"p95_ms\":%.4f,\"allocated_bytes_per_op\":%d}", fixture.name, times[times.length / 2] / 1e6, times[(int) (times.length * .95)] / 1e6, allocations ? allocated / times.length : -1);
            records.add(record);
            System.out.println(record);
        }
        Files.writeString(output.resolve("atmosphere-" + label + ".json"), "{\"label\":\"" + label + "\",\"java\":\"" + System.getProperty("java.version") + "\",\"measurements\":[" + String.join(",", records) + "]}\n");
    }

    private static void rebuild(Fixture fixture)
    {
        var air = fixture.air.clone();
        var rooms = Atmosphere.rooms(fixture.seals, air, false);
        blackhole += rooms.size();
    }

    private AtmosphereBenchmark()
    {
    }
}
