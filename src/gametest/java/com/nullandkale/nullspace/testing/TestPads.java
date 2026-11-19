package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;

import java.io.IOException;
import java.util.ArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Registers the pad a test stands its fixture on. {@link PadComputers#register} refuses, and says why only in the text it returns, when another pad has the
 * name or overlaps the ring; every batch of tests is laid out over the same ground and registered pads outlive their tests, so a pad that an earlier test left
 * behind could make a later test's registration quietly fail and the test then fail somewhere else. Use this in a test, not {@code PadComputers.register}
 * (see {@code GameTestHygieneTest}).
 *
 * <p>A pad whose computer is gone, or sits in a chunk that is not loaded, belongs to a test that has finished and is removed if it is in the way. A pad
 * whose computer still stands belongs to a test that is running, and is never removed: the registration is then refused and this throws, naming why.
 */
public final class TestPads
{
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
        kept.removeIf(entry -> abandoned(server, entry) && !entry.computer().equals(computer) && (entry.name().equalsIgnoreCase(name.strip()) || entry.dimension().equals(dimension) && pad.overlaps(entry.bounds())));
        if (kept.size() != repository.all().size())
            repository.replace(kept);
        String result = PadComputers.register(level, computer, pad, name, pruneMissing);
        if (!result.startsWith("Registered"))
            throw new AssertionError("The pad " + name + " did not register: " + result);
        return result;
    }

    private static boolean abandoned(net.minecraft.server.MinecraftServer server, PadRegistry.Pad entry)
    {
        var level = WorldStore.level(server, entry.dimension());
        return level == null || !level.hasChunkAt(entry.computer()) || !level.getBlockState(entry.computer()).is(PadComputers.BLOCK);
    }

    private TestPads()
    {
    }
}
