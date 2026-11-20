package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Registers the pad a test stands its fixture on. {@link PadComputers#register} refuses, and says why only in the text it returns, when another pad has the
 * name or overlaps the ring; every batch of tests is laid out over the same ground and registered pads outlive their tests, so a pad that an earlier test left
 * behind could make a later test's registration quietly fail and the test then fail somewhere else. Use this in a test, not {@code PadComputers.register}
 * (see {@code GameTestHygieneTest}).
 *
 * <p>A pad in the way is removed when the test that made it has finished. Which test that was is not known, but it was one of the tests running when the pad
 * first appeared: each pad is noted with those tests (by a tick hook, from the first time this class is used, and at once by a registration here), and once
 * all of them are done its owner is done. Batches run one after another, so a pad left by an earlier batch is always free to remove, even when that test left
 * its chunk forced and its computer standing. A pad is also free, as before, when its computer is gone or sits in a chunk that is not loaded (a pad that
 * appeared before this class was first used is noted late, with tests that may not include its owner). A pad of a test that is still running is never
 * removed: the registration is then refused and this throws, naming why.
 */
public final class TestPads
{
    /** The tests that were running when each pad first appeared, by dimension and computer position. */
    private static final Map<String, List<WeakReference<GameTestInfo>>> SEEN = new ConcurrentHashMap<>();

    static
    {
        ServerTickEvents.END_SERVER_TICK.register(TestPads::observe);
    }

    public static String register(ServerLevel level, BlockPos computer, PadScanner.Pad pad, String name) throws IOException
    {
        return register(level, computer, pad, name, true);
    }

    /** As {@link PadComputers#register(ServerLevel, BlockPos, PadScanner.Pad, String, boolean)}: {@code pruneMissing} false keeps registrations whose computer is gone. */
    public static String register(ServerLevel level, BlockPos computer, PadScanner.Pad pad, String name, boolean pruneMissing) throws IOException
    {
        var server = level.getServer();
        var repository = PadRepository.get(server);
        String dimension = level.dimension().identifier().toString();
        var kept = new ArrayList<>(repository.all());
        kept.removeIf(entry -> !entry.computer().equals(computer) && (entry.name().equalsIgnoreCase(name.strip()) || entry.dimension().equals(dimension) && pad.overlaps(entry.bounds())) && finished(server, entry));
        if (kept.size() != repository.all().size())
            repository.replace(kept);
        String result = PadComputers.register(level, computer, pad, name, pruneMissing);
        if (!result.startsWith("Registered"))
            throw new AssertionError("The pad " + name + " did not register: " + result);
        // Fresh owners even when a pad of an earlier batch had this computer position: later batches are laid out on the same ground.
        SEEN.put(key(dimension, computer), running());
        return result;
    }

    private static String key(String dimension, BlockPos computer)
    {
        return dimension + "|" + computer.asLong();
    }

    /** Notes every pad not yet seen with the tests running now, and forgets pads that are gone, so a pad made later at the same computer is noted afresh. */
    private static void observe(MinecraftServer server)
    {
        List<PadRegistry.Pad> pads;
        try
        {
            pads = PadRepository.get(server).all();
        }
        catch (RuntimeException unavailable)
        {
            return;
        }
        var present = new java.util.HashSet<String>();
        List<WeakReference<GameTestInfo>> running = null;
        for (var pad : pads)
        {
            String key = key(pad.dimension(), pad.computer());
            present.add(key);
            if (SEEN.containsKey(key))
                continue;
            if (running == null)
                running = running();
            SEEN.put(key, running);
        }
        SEEN.keySet().retainAll(present);
    }

    /** Whether the test that made this pad has finished. */
    private static boolean finished(MinecraftServer server, PadRegistry.Pad entry)
    {
        var owners = SEEN.get(key(entry.dimension(), entry.computer()));
        if (owners != null && !owners.isEmpty() && owners.stream().map(WeakReference::get).allMatch(info -> info == null || info.isDone()))
            return true;
        var level = WorldStore.level(server, entry.dimension());
        return level == null || !level.hasChunkAt(entry.computer()) || !level.getBlockState(entry.computer()).is(PadComputers.BLOCK);
    }

    @SuppressWarnings("unchecked")
    private static List<WeakReference<GameTestInfo>> running()
    {
        try
        {
            for (var field : GameTestTicker.class.getDeclaredFields())
                if (Collection.class.isAssignableFrom(field.getType()))
                {
                    field.setAccessible(true);
                    var out = new ArrayList<WeakReference<GameTestInfo>>();
                    for (var info : List.copyOf((Collection<GameTestInfo>) field.get(GameTestTicker.SINGLETON)))
                        out.add(new WeakReference<>(info));
                    return out;
                }
        }
        catch (ReflectiveOperationException | RuntimeException unreadable)
        {
            // Without the list of running tests a pad falls back to the world.
        }
        return List.of();
    }

    private TestPads()
    {
    }
}
