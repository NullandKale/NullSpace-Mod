package com.nullandkale.nullspace.core.save;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import net.minecraft.nbt.CompoundTag;

/**
 * The ship store lives in two files, each carrying a save number that rises with every save. A save writes the
 * slot that does not hold the newest good copy, so that copy is never touched while a new one is written. On
 * start the highest readable save number wins. See docs/design/journals-and-save-recovery.md.
 */
public final class StoreSlots
{
    /** Root key that holds the save number inside a slot file. It is removed before the repository sees the store. */
    public static final String NUMBER = "saveNumber";

    public enum Slot
    {
        A("ships-a.nbt"), B("ships-b.nbt");

        final String fileName;

        Slot(String fileName)
        {
            this.fileName = fileName;
        }

        Slot other()
        {
            return this == A ? B : A;
        }
    }

    /** A slot file that could not be used, and where it was kept. */
    public record Damaged(Slot slot, Path keptAs, String reason)
    {}

    /**
     * The store as found on disk. {@code slot} is where the loaded copy lives, or null when the store came from the
     * old single file or is new. {@code olderNumber} is the other readable copy's number, or -1 when there is none.
     */
    public record Loaded(CompoundTag root, long saveNumber, Slot slot, long olderNumber, List<Damaged> damaged, boolean migrated)
    {}

    /** Every copy of the store exists but none can be read. The server must not start with an empty store. */
    public static final class Unreadable extends IOException
    {
        public Unreadable(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private record Attempt(Slot slot, Path file, CompoundTag root, long number, String problem)
    {
        boolean exists()
        {
            return problem == null || !problem.equals("missing");
        }

        boolean ok()
        {
            return problem == null;
        }
    }

    public static final String LEGACY = "ships.nbt";

    public static Path file(Path directory, Slot slot)
    {
        return directory.resolve(slot.fileName);
    }

    /** The slot the next save writes: the one that does not hold the newest copy. */
    public static Slot next(Slot current)
    {
        return current == null ? Slot.A : current.other();
    }

    private static Attempt attempt(Path directory, Slot slot)
    {
        var file = file(directory, slot);
        if (!Files.exists(file))
            return new Attempt(slot, file, null, -1, "missing");
        try
        {
            var root = WorldStore.read(file);
            if (!root.contains(NUMBER))
                return new Attempt(slot, file, null, -1, "not a store copy: no save number");
            long number = root.getLongOr(NUMBER, -1);
            if (number < 0)
                return new Attempt(slot, file, null, -1, "not a store copy: bad save number");
            root.remove(NUMBER);
            return new Attempt(slot, file, root, number, null);
        }
        catch (IOException | RuntimeException error)
        {
            return new Attempt(slot, file, null, -1, error.toString());
        }
    }

    /** Reads both copies and chooses one. A damaged copy is renamed, never deleted. */
    public static Loaded load(Path directory) throws IOException
    {
        Files.createDirectories(directory);
        var a = attempt(directory, Slot.A);
        var b = attempt(directory, Slot.B);
        Attempt best = null;
        for (var candidate : List.of(a, b))
            if (candidate.ok() && (best == null || candidate.number() > best.number()))
                best = candidate;
        if (best == null)
        {
            var broken = List.of(a, b).stream().filter(Attempt::exists).toList();
            if (!broken.isEmpty())
                throw new Unreadable("None of the ship store copies can be read: " + broken.stream().map(x -> x.file() + " (" + x.problem() + ")").toList() + ". Restore ships-a.nbt or ships-b.nbt from a backup of the world's nullspace directory. The server will not start with an empty ship store.", null);
            var legacy = directory.resolve(LEGACY);
            if (Files.exists(legacy))
            {
                try
                {
                    return new Loaded(WorldStore.read(legacy), 0, null, -1, List.of(), true);
                }
                catch (IOException | RuntimeException error)
                {
                    throw new Unreadable("The ship store " + legacy + " cannot be read and no newer copy exists. Restore it from a backup of the world's nullspace directory.", error);
                }
            }
            return new Loaded(new CompoundTag(), 0, null, -1, List.of(), false);
        }
        var damaged = new ArrayList<Damaged>();
        var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (var candidate : List.of(a, b))
            if (candidate != best && candidate.exists() && !candidate.ok())
            {
                var kept = candidate.file().resolveSibling(candidate.file().getFileName() + ".damaged-" + stamp);
                Files.move(candidate.file(), kept, StandardCopyOption.REPLACE_EXISTING);
                damaged.add(new Damaged(candidate.slot(), kept, candidate.problem()));
            }
        var other = best == a ? b : a;
        return new Loaded(best.root(), best.number(), best.slot(), other.ok() ? other.number() : -1, List.copyOf(damaged), false);
    }

    /** Writes the store as the given save number into the slot that does not hold the newest copy. */
    public static Slot write(Path directory, Slot current, CompoundTag root, long number)
    {
        var target = next(current);
        var copy = root.copy();
        copy.putLong(NUMBER, number);
        WorldStore.write(file(directory, target), copy);
        return target;
    }

    /** After the first save of a migrated store, the old single file is kept under another name. */
    public static void retireLegacy(Path directory) throws IOException
    {
        var legacy = directory.resolve(LEGACY);
        if (Files.exists(legacy))
            Files.move(legacy, directory.resolve(LEGACY + ".pre-journals"), StandardCopyOption.REPLACE_EXISTING);
    }

    private StoreSlots()
    {
    }
}
