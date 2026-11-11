package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.NullSpace;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.*;

/**
 * Files of the transfer journal directory. A journal records one operation: anything that creates, removes or moves a
 * ship, or edits blocks together with store records. The file name carries what recovery and pruning need to know
 * without opening the file: {@code 00000012-<id>.nbt} is unfinished, and {@code 00000012-<id>.s34.nbt} is finished
 * and included in every store copy numbered 34 or higher. See docs/design/journals-and-save-recovery.md.
 */
public final class Journals
{
    public static final int VERSION = 2;

    public enum Kind
    {
        /** A ship physically moves: launch, landing, recall, storing. */
        MOVE,
        /** Blocks are placed or replaced together with store records. */
        BUILD,
        /** Store records change and no blocks do. */
        RECORD
    }

    private static final Pattern NAME = Pattern.compile("^(\\d{8,})-([0-9a-fA-F-]+)(?:\\.s(\\d+))?\\.nbt$");

    /** A journal file name, taken apart. {@code saveNumber} is -1 while the journal is unfinished. */
    public record Name(long sequence, String id, long saveNumber)
    {
        public boolean finished()
        {
            return saveNumber >= 0;
        }
    }

    /** A version 1 file, named only by its id. It holds whole-store copies and is rolled back with the old rules. */
    public static boolean legacy(Path file)
    {
        return parse(file) == null && file.getFileName().toString().endsWith(".nbt");
    }

    public static Name parse(Path file)
    {
        var matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches())
            return null;
        return new Name(Long.parseLong(matcher.group(1)), matcher.group(2), matcher.group(3) == null ? -1 : Long.parseLong(matcher.group(3)));
    }

    public static Path directory(Path storeDirectory)
    {
        return storeDirectory.resolve("transfers");
    }

    public static Path quarantine(Path storeDirectory)
    {
        return directory(storeDirectory).resolve("quarantine");
    }

    /** The journal files in the order the operations happened, legacy files first. */
    public static List<Path> list(Path storeDirectory) throws IOException
    {
        var directory = directory(storeDirectory);
        if (!Files.isDirectory(directory))
            return List.of();
        try (var files = Files.list(directory))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".nbt")).sorted(Comparator.comparingLong((Path p) ->
            {
                var name = parse(p);
                return name == null ? -1 : name.sequence();
            }).thenComparing(p -> p.getFileName().toString())).toList();
        }
    }

    /** The sequence number the next journal takes: one more than any file present, in either folder. */
    public static long nextSequence(Path storeDirectory) throws IOException
    {
        long highest = 0;
        for (var folder : List.of(directory(storeDirectory), quarantine(storeDirectory)))
        {
            if (!Files.isDirectory(folder))
                continue;
            try (var files = Files.list(folder))
            {
                for (var file : files.toList())
                {
                    // A journal that was discarded keeps its number, so a new one never reuses it.
                    var name = parse(file.resolveSibling(file.getFileName().toString().replaceAll("(\\.discarded|\\.restoring|\\.restored|\\.reason\\.txt)+$", "")));
                    if (name != null)
                        highest = Math.max(highest, name.sequence());
                }
            }
        }
        return highest + 1;
    }

    /** The header of a new journal. The caller adds what the operation needs and writes it at each stage. */
    public static CompoundTag create(Path storeDirectory, Kind kind, String operation, Collection<String> ships) throws IOException
    {
        var journal = new CompoundTag();
        journal.putInt("version", VERSION);
        journal.putString("id", UUID.randomUUID().toString());
        journal.putLong("sequence", nextSequence(storeDirectory));
        journal.putString("kind", kind.name());
        journal.putString("operation", operation);
        journal.putString("stage", "OPEN");
        var list = new ListTag();
        ships.forEach(id -> list.add(StringTag.valueOf(id)));
        journal.put("ships", list);
        journal.putString("modVersion", FabricLoader.getInstance().getModContainer("nullspace").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        journal.putLong("time", System.currentTimeMillis());
        journal.putLong("saveNumber", -1);
        return journal;
    }

    public static Path file(Path storeDirectory, CompoundTag journal)
    {
        return directory(storeDirectory).resolve(String.format("%08d-%s.nbt", journal.getLongOr("sequence", 0), journal.getStringOr("id", "")));
    }

    public static boolean finishedStage(String stage)
    {
        return stage.equals("COMMITTED") || stage.equals("ROLLED_BACK");
    }

    /** Marks a finished journal with the save number of the first store save that includes it. */
    public static Path finish(Path file, long saveNumber) throws IOException
    {
        var name = parse(file);
        if (name == null)
            return file;
        var finished = file.resolveSibling(String.format("%08d-%s.s%d.nbt", name.sequence(), name.id(), saveNumber));
        Files.move(file, finished, StandardCopyOption.REPLACE_EXISTING);
        return finished;
    }

    /**
     * Deletes finished journals that every kept copy of the store includes. Reads only file names.
     *
     * @param olderNumber the save number of the older kept copy
     */
    public static int prune(Path storeDirectory, long olderNumber)
    {
        int deleted = 0;
        try
        {
            for (var file : list(storeDirectory))
            {
                var name = parse(file);
                if (name == null || !name.finished() || name.saveNumber() > olderNumber)
                    continue;
                Files.deleteIfExists(file);
                deleted++;
                NullSpace.LOGGER.info("Journal {} finished and included in every store copy; deleted", file.getFileName());
            }
        }
        catch (IOException error)
        {
            NullSpace.LOGGER.warn("Could not prune finished journals; they stay until the next save", error);
        }
        return deleted;
    }

    /** An otherwise unchanged world save must advance the older copy once before its last journals can retire. */
    public static boolean needsRotation(Path storeDirectory, long olderNumber, long newestNumber)
    {
        try
        {
            for (var file : list(storeDirectory))
            {
                var name = parse(file);
                if (name != null && name.finished() && name.saveNumber() > olderNumber && name.saveNumber() <= newestNumber)
                    return true;
            }
        }
        catch (IOException error)
        {
            NullSpace.LOGGER.warn("Could not inspect journal retention; it will be retried on the next save", error);
        }
        return false;
    }

    /** One line for the log when an operation finishes: the history lives in the log, not on disk. */
    public static String summary(CompoundTag journal, String outcome)
    {
        var ships = journal.getListOrEmpty("ships").stream().map(t -> t.asString().orElse("")).toList();
        String what = journal.getStringOr("kind", "?") + " " + journal.getStringOr("operation", "?");
        return "journal " + journal.getLongOr("sequence", 0) + " " + what + " ships " + ships + " " + outcome;
    }

    private Journals()
    {
    }
}
