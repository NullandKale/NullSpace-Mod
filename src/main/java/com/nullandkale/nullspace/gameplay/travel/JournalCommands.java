package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.StoreRecords;
import com.nullandkale.nullspace.core.save.WorldStore;

import java.nio.file.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

/**
 * The operator's way out when a journal could not be recovered: list what is stuck, look inside, retry, export the
 * ship for rebuilding by hand, or accept the world as it is. Every unresolved journal stays locked until one of these.
 */
public final class JournalCommands
{
    private static final SuggestionProvider<CommandSourceStack> JOURNALS = (context, builder) ->
    {
        UnresolvedJournals.all(context.getSource().getServer()).forEach(e -> builder.suggest(UnresolvedJournals.shortId(e)));
        return builder.buildFuture();
    };

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("recovery").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("list").executes(JournalCommands::list)).then(Commands.literal("show").then(Commands.argument("journal", StringArgumentType.word()).suggests(JOURNALS).executes(JournalCommands::show))).then(Commands.literal("retry").then(Commands.argument("journal", StringArgumentType.word()).suggests(JOURNALS).executes(JournalCommands::retry))).then(Commands.literal("export").then(Commands.argument("journal", StringArgumentType.word()).suggests(JOURNALS).executes(JournalCommands::export))).then(Commands.literal("restore").then(Commands.argument("journal", StringArgumentType.word()).suggests(JOURNALS).then(Commands.argument("destination", StringArgumentType.greedyString()).executes(JournalCommands::restore)))).then(Commands.literal("discard").then(Commands.argument("journal", StringArgumentType.word()).suggests(JOURNALS).executes(JournalCommands::discard))))));
    }

    private static UnresolvedJournals.Entry find(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        String prefix = StringArgumentType.getString(context, "journal");
        var matches = UnresolvedJournals.all(context.getSource().getServer()).stream().filter(e -> e.id().startsWith(prefix)).toList();
        if (matches.size() != 1)
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal(matches.isEmpty() ? "No unresolved journal starts with " + prefix : prefix + " matches " + matches.size() + " journals; type more of the id")).create();
        return matches.getFirst();
    }

    private static int list(CommandContext<CommandSourceStack> context)
    {
        var entries = UnresolvedJournals.all(context.getSource().getServer());
        var source = context.getSource();
        if (entries.isEmpty())
            source.sendSuccess(() -> Component.literal("No journals need attention."), false);
        for (var entry : entries)
            source.sendSuccess(() -> Component.literal(UnresolvedJournals.describeForOperators(entry)), false);
        var store = WorldStore.existing(source.getServer());
        if (store != null)
            for (var damaged : store.damagedCopies())
                source.sendSuccess(() -> Component.literal("Store copy " + damaged.slot() + " was damaged at start, kept as " + damaged.keptAs().getFileName() + ": " + damaged.reason()), false);
        return entries.size();
    }

    private static CompoundTag read(UnresolvedJournals.Entry entry) throws java.io.IOException
    {
        return WorldStore.read(entry.file());
    }

    private static int show(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var entry = find(context);
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal(UnresolvedJournals.describeForOperators(entry) + (entry.file() == null ? "" : "  file " + entry.file())), false);
        if (entry.file() == null)
            return 1;
        try
        {
            var journal = read(entry);
            source.sendSuccess(() -> Component.literal("stage " + journal.getStringOr("stage", "?") + ", written by NullSpace " + journal.getStringOr("modVersion", "?") + ", " + journal.getCompoundOrEmpty("template").getListOrEmpty("blocks").size() + " hull blocks, " + journal.getListOrEmpty("entities").size() + " entities, " + journal.getListOrEmpty("players").size() + " players"), false);
            for (var change : StoreRecords.decode(journal.getListOrEmpty("records")))
            {
                if (!change.top().equals("ships") || !(change.before() instanceof CompoundTag ship))
                    continue;
                source.sendSuccess(() -> Component.literal("ship " + change.key() + ": " + ship.getStringOr("home", ship.getStringOr("name", "ship")) + ", owner " + ship.getStringOr("owner", "none") + ", " + ship.getListOrEmpty("crew").size() + " crew, in " + ship.getStringOr("dimension", "?") + " at base " + BlockPosText.of(ship.getLongOr("base", 0))), false);
            }
        }
        catch (java.io.IOException | RuntimeException unreadable)
        {
            source.sendFailure(Component.literal("The journal file cannot be read: " + unreadable));
        }
        return 1;
    }

    private static int retry(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var entry = find(context);
        if (!inactive(context.getSource(), entry))
            return 0;
        var server = context.getSource().getServer();
        var source = context.getSource();
        if (entry.file() == null)
        {
            source.sendFailure(Component.literal("This is a check of the store against the world, not a journal. Reload the chunk to check it again, or discard it to accept the store."));
            return 0;
        }
        try
        {
            Path file = entry.file();
            if (entry.state() == UnresolvedJournals.State.SET_ASIDE)
            {
                // Back to the live folder: recovery runs it again and sets it aside again if it still cannot finish.
                var live = Journals.directory(WorldStore.get(server).directory).resolve(file.getFileName());
                Files.move(file, live, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".reason.txt"));
                UnresolvedJournals.remove(server, entry.id());
            }
            TransferRecovery.recover(server);
            var after = UnresolvedJournals.get(server, entry.id());
            source.sendSuccess(() -> Component.literal(after == null ? "Journal " + entry.id() + " was recovered." : "Journal " + entry.id() + " is still " + after.state() + ": " + after.reason()), true);
            return after == null ? 1 : 0;
        }
        catch (java.io.IOException error)
        {
            source.sendFailure(Component.literal("Retry failed: " + error));
            return 0;
        }
    }

    private static int restore(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var entry = find(context);
        if (!inactive(context.getSource(), entry))
            return 0;
        try
        {
            String message = JournalRestore.restore(context.getSource().getServer(), entry, StringArgumentType.getString(context, "destination"));
            context.getSource().sendSuccess(() -> Component.literal(message), true);
            return 1;
        }
        catch (java.io.IOException | RuntimeException error)
        {
            context.getSource().sendFailure(Component.literal("Restore failed: " + error.getMessage()));
            return 0;
        }
    }

    private static boolean inactive(CommandSourceStack source, UnresolvedJournals.Entry entry)
    {
        if (entry.state() == UnresolvedJournals.State.BUILDING || entry.file() != null && entry.file().toString().endsWith(".restoring"))
        {
            source.sendFailure(Component.literal("This operation is still in progress. Finish its BUILD recovery before changing the original journal."));
            return false;
        }
        return true;
    }

    private static int export(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var entry = find(context);
        var source = context.getSource();
        if (entry.file() == null)
        {
            source.sendFailure(Component.literal("There is no hull to export for a check of the store against the world."));
            return 0;
        }
        try
        {
            var journal = read(entry);
            var folder = WorldStore.get(source.getServer()).directory.resolve("rescue").resolve(entry.id());
            Files.createDirectories(folder);
            // The hull is a vanilla structure file: place it with a structure block or load it in any structure tool.
            NbtIo.writeCompressed(journal.getCompoundOrEmpty("template"), folder.resolve("hull.nbt"));
            var text = new StringBuilder();
            text.append("Journal ").append(entry.id()).append(' ').append(entry.kind()).append(' ').append(entry.operation()).append('\n');
            text.append("State ").append(entry.state()).append(": ").append(entry.reason()).append('\n');
            text.append("From ").append(journal.getStringOr("source", "?")).append(" box ").append(BlockPosText.of(journal.getLongOr("min", 0))).append(" to ").append(BlockPosText.of(journal.getLongOr("max", 0))).append('\n');
            text.append("To ").append(journal.getStringOr("target", "?")).append(" offset ").append(BlockPosText.of(journal.getLongOr("delta", 0))).append("\n\n");
            for (var change : StoreRecords.decode(journal.getListOrEmpty("records")))
            {
                text.append("Record ").append(change.top()).append(' ').append(change.key()).append('\n');
                text.append("  before: ").append(change.before() == null ? "(did not exist)" : change.before().toString()).append('\n');
                text.append("  after:  ").append(change.after() == null ? "(removed)" : change.after().toString()).append('\n');
            }
            text.append("\nPlayers aboard\n");
            for (var player : journal.getListOrEmpty("players"))
                text.append("  ").append(player).append('\n');
            text.append("\nEntities aboard (saved data in entities.nbt)\n");
            Files.writeString(folder.resolve("rescue.txt"), text);
            var entities = new CompoundTag();
            entities.put("entities", journal.getListOrEmpty("entities"));
            NbtIo.writeCompressed(entities, folder.resolve("entities.nbt"));
            source.sendSuccess(() -> Component.literal("Exported to " + folder + ": hull.nbt (a structure file), rescue.txt (the ship's records) and entities.nbt"), true);
            return 1;
        }
        catch (java.io.IOException | RuntimeException error)
        {
            source.sendFailure(Component.literal("Export failed: " + error));
            return 0;
        }
    }

    private static int discard(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var entry = find(context);
        if (!inactive(context.getSource(), entry))
            return 0;
        var server = context.getSource().getServer();
        if (entry.file() == null)
        {
            com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.accept(server, entry);
            context.getSource().sendSuccess(() -> Component.literal("Accepted. The store keeps its records and the stale controller stamp was cleared."), true);
            return 1;
        }
        try
        {
            // The world is accepted as it is. The file is kept under another name and its locks are released.
            var file = entry.file();
            if (Files.exists(file))
            {
                var folder = Journals.quarantine(WorldStore.get(server).directory);
                Files.createDirectories(folder);
                Files.move(file, folder.resolve(file.getFileName() + ".discarded"), StandardCopyOption.REPLACE_EXISTING);
                var note = file.resolveSibling(file.getFileName() + ".reason.txt");
                if (Files.exists(note))
                    Files.move(note, folder.resolve(file.getFileName() + ".discarded.reason.txt"), StandardCopyOption.REPLACE_EXISTING);
            }
            UnresolvedJournals.remove(server, entry.id());
            com.nullandkale.nullspace.NullSpace.LOGGER.warn("Operator {} discarded journal {}", context.getSource().getTextName(), entry.id());
            context.getSource().sendSuccess(() -> Component.literal("Journal " + entry.id() + " discarded. The world stays as it is and its locks are released."), true);
            return 1;
        }
        catch (java.io.IOException error)
        {
            context.getSource().sendFailure(Component.literal("Discard failed: " + error));
            return 0;
        }
    }

    private static final class BlockPosText
    {
        static String of(long packed)
        {
            var pos = net.minecraft.core.BlockPos.of(packed);
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    }

    private JournalCommands()
    {
    }
}
