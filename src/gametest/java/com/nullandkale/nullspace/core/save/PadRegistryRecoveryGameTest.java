package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;

/** The pad registry keeps its previous generation, and falls back to it when the file cannot be read. */
public final class PadRegistryRecoveryGameTest
{
    private static PadRegistry.Pad pad(String id, int x)
    {
        return new PadRegistry.Pad(id, "Recovery " + id, "minecraft:overworld", new BlockPos(x, 64, 700000), new PadScanner.Pad(x, x + 7, 63, 700000, 700007));
    }

    @GameTest(environment = "nullspace:pad_registry_recovery")
    public void aDamagedRegistryFallsBackToThePreviousCopyAndIsKept(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var repository = PadRepository.get(server);
        var root = server.getWorldPath(LevelResource.ROOT);
        var file = root.resolve("nullspace-pads.json");
        var previous = root.resolve("nullspace-pads.previous.json");
        var original = List.copyOf(repository.all());
        try
        {
            var first = new ArrayList<>(original);
            first.add(pad("a", 700000));
            repository.replace(first);
            var second = new ArrayList<>(first);
            second.add(pad("b", 700100));
            repository.replace(second);
            ctx.assertTrue(Files.exists(previous), "Replacing the registry keeps the one it replaced");
            ctx.assertTrue(repository.all().size() == second.size(), "The newest registry is in use");

            Files.writeString(file, "{ this is not a registry");
            boolean refused = false;
            try
            {
                repository.reload();
            }
            catch (IllegalStateException expected)
            {
                refused = true;
            }
            ctx.assertTrue(refused && repository.all().size() == second.size(), "A reload of a registry that cannot be read is an error and keeps the pads the server is running with");
            ctx.assertTrue(PadRegistryReader.ids(previous).equals(first.stream().map(PadRegistry.Pad::id).toList()), "and the good previous copy is not replaced by it");
            // A server start has no pads in memory, so it falls back to the previous copy.
            var started = PadRepository.reopen(server);
            ctx.assertTrue(started.all().stream().map(PadRegistry.Pad::id).toList().equals(first.stream().map(PadRegistry.Pad::id).toList()), "A start with a registry that cannot be read loads the previous copy, one registration behind");
            try (var kept = Files.list(root))
            {
                ctx.assertTrue(kept.anyMatch(p -> p.getFileName().toString().startsWith("nullspace-pads.json.damaged-")), "The damaged file is kept under another name");
            }
            ctx.assertTrue(PadRegistryReader.ids(file).equals(first.stream().map(PadRegistry.Pad::id).toList()), "The registry on disk is whole again");

            // A registry that was lost mid-write, with its previous copy still there.
            Files.delete(file);
            var restarted = PadRepository.reopen(server);
            ctx.assertTrue(Files.exists(file) && restarted.all().size() == first.size(), "A start with the registry missing restores it from the previous copy");
        }
        finally
        {
            repository.replace(new ArrayList<>(original));
            try (var leftovers = Files.list(root))
            {
                for (var leftover : leftovers.filter(p -> p.getFileName().toString().startsWith("nullspace-pads.json.damaged-")).toList())
                    Files.deleteIfExists(leftover);
            }
        }
        ctx.succeed();
    }

    /** Reads the ids in a registry file without going through the repository. */
    private static final class PadRegistryReader
    {
        static List<String> ids(Path file) throws java.io.IOException
        {
            var ids = new ArrayList<String>();
            for (var element : com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonArray())
                ids.add(element.getAsJsonObject().get("id").getAsString());
            return ids;
        }
    }
}
