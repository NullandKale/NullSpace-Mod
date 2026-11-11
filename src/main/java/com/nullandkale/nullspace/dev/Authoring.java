package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.worldgen.blueprint.GenerationPalette;
import com.nullandkale.nullspace.worldgen.condition.Condition;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.encounters.SiteLoot;
import com.nullandkale.nullspace.worldgen.piece.Archetype;
import com.nullandkale.nullspace.worldgen.piece.Assembler;
import com.nullandkale.nullspace.worldgen.piece.Kit;
import com.nullandkale.nullspace.worldgen.piece.PieceLibrary;
import com.nullandkale.nullspace.worldgen.piece.TemplateCodec;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

import com.mojang.brigadier.arguments.*;

/**
 * Authoring tools for generated content (procedural generation design, step 1). Pieces are built on
 * a registered pad and saved as vanilla structure templates {@code nullspace:<kind>/<name>}.
 * Vanilla structure blocks in DATA mode and jigsaw blocks inside a piece are its markers (loot and
 * mob spots, doorway connectors); they are saved with it. In a development run the template is also
 * written to {@code -Dnullspace.authoringDir} (the mod's resources) so it ships with the mod.
 */
public final class Authoring
{
    public record Volume(PadRegistry.Pad pad, BlockPos min, Vec3i size)
    {}

    /** The build volume of the registered pad the player stands on. */
    public static Volume volume(ServerPlayer player)
    {
        var pad = PadRegistry.below(player.level(), player.blockPosition()).orElseThrow(() -> new IllegalArgumentException("Stand on a registered pad to author a piece"));
        var b = pad.bounds();
        int height = PadRegistry.buildHeight(player.level(), b);
        return new Volume(pad, new BlockPos(b.minX(), b.y() + 1, b.minZ()), new Vec3i(b.width(), height, b.depth()));
    }

    /**
     * The template id for a kind and name. A dotted kind names nested folders: {@code kit.rivet} and
     * {@code nose_a} give {@code nullspace:kit/rivet/nose_a}.
     */
    public static Identifier id(String kind, String name)
    {
        if (!kind.matches("[a-z0-9_]{1,32}([.][a-z0-9_]{1,32})*") || !name.matches("[a-z0-9_]{1,48}"))
            throw new IllegalArgumentException("Kind and name use lowercase letters, digits and _ (a kind may use dots for folders)");
        return Identifier.parse("nullspace:" + kind.replace('.', '/') + "/" + name);
    }

    /** Saves everything on the pad, trimmed to the blocks actually built, as a template. */
    public static String save(ServerPlayer player, String kind, String name) throws Exception
    {
        var id = id(kind, name);
        var volume = volume(player);
        var level = player.level();
        BlockPos lo = null, hi = null;
        var blocks = new HashMap<BlockPos, BlockState>();
        int markers = 0;
        for (var cursor : BlockPos.betweenClosed(volume.min(), volume.min().offset(volume.size()).offset(-1, -1, -1)))
        {
            var state = level.getBlockState(cursor);
            if (state.isAir())
                continue;
            var at = cursor.immutable();
            blocks.put(at, state);
            if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW))
                markers++;
            lo = lo == null ? at : new BlockPos(Math.min(lo.getX(), at.getX()), Math.min(lo.getY(), at.getY()), Math.min(lo.getZ(), at.getZ()));
            hi = hi == null ? at : new BlockPos(Math.max(hi.getX(), at.getX()), Math.max(hi.getY(), at.getY()), Math.max(hi.getZ(), at.getZ()));
        }
        if (lo == null)
            throw new IllegalArgumentException("The pad is empty; build the piece first");
        if (kind.equals("ship"))
        {
            // A shipyard design keeps the origin it was loaded at (the pad corner) and never
            // shrinks, so its helm, rooms and pad placement keep their coordinates.
            lo = volume.min();
            var existing = PieceLibrary.template(id.getPath()).map(TemplateCodec.Template::size).orElse(Vec3i.ZERO);
            hi = new BlockPos(Math.max(hi.getX(), lo.getX() + existing.getX() - 1), Math.max(hi.getY(), lo.getY() + existing.getY() - 1), Math.max(hi.getZ(), lo.getZ() + existing.getZ() - 1));
        }
        var manager = level.getServer().getStructureTemplateManager();
        var template = manager.getOrCreate(id);
        template.fillFromWorld(level, lo, new Vec3i(hi.getX() - lo.getX() + 1, hi.getY() - lo.getY() + 1, hi.getZ() - lo.getZ() + 1), false, List.of(Blocks.STRUCTURE_VOID));
        template.setAuthor(player.getPlainTextName());
        if (!manager.save(id))
            throw new IllegalStateException("Could not save " + id);
        String copied = "";
        String dir = System.getProperty("nullspace.authoringDir");
        if (dir != null && !dir.isBlank())
        {
            var file = PieceLibrary.authoringFile(id.getPath()).orElseThrow();
            Files.createDirectories(file.getParent());
            StructureTemplateManager.save(file, template, false);
            copied = " Copied into the mod's resources.";
        }
        PieceLibrary.invalidate();
        ShipBlueprints.invalidate();
        var bad = GenerationPalette.violations(blocks);
        var size = template.getSize();
        return "Saved " + id + ": " + blocks.size() + " blocks in " + size.getX() + " x " + size.getY() + " x " + size.getZ() + ", " + markers + " markers." + copied + (bad.isEmpty() ? "" : " Warning: " + bad.size() + " valuable blocks (generated content must use cheap blocks), first at " + bad.getFirst().toShortString());
    }

    /** Clears the pad's build volume. */
    static void clear(ServerLevel level, Volume volume)
    {
        for (var at : BlockPos.betweenClosed(volume.min(), volume.min().offset(volume.size()).offset(-1, -1, -1)))
            if (!level.getBlockState(at).isAir())
            {
                level.removeBlockEntity(at);
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
            }
    }

    /** Places a saved template on the pad for editing, replacing whatever was there. */
    public static String load(ServerPlayer player, String kind, String name)
    {
        var id = id(kind, name);
        var volume = volume(player);
        var saved = player.level().getServer().getStructureTemplateManager().get(id);
        if (saved.isEmpty() && kind.equals("ship"))
        {
            // A generated design has no template until someone saves an edit of it: build it on the pad.
            var preset = com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.PRESETS.stream().filter(p -> p.id().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("No saved piece " + id));
            if (preset.width() > volume.size().getX() || preset.height() > volume.size().getY() || preset.depth() > volume.size().getZ())
                throw new IllegalArgumentException("Piece " + id + " is larger than this pad");
            clear(player.level(), volume);
            for (var e : com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.blocks(preset).entrySet())
                player.level().setBlock(volume.min().offset(e.getKey()), e.getValue(), Block.UPDATE_CLIENTS);
            return "Loaded generated design " + id + " (" + preset.size() + "). Save it to replace the generated ship with your edit.";
        }
        var template = saved.orElseThrow(() -> new IllegalArgumentException("No saved piece " + id));
        var size = template.getSize();
        if (size.getX() > volume.size().getX() || size.getY() > volume.size().getY() || size.getZ() > volume.size().getZ())
            throw new IllegalArgumentException("Piece " + id + " is larger than this pad");
        clear(player.level(), volume);
        template.placeInWorld(player.level(), volume.min(), volume.min(), new StructurePlaceSettings(), RandomSource.create(), Block.UPDATE_CLIENTS);
        return "Loaded " + id + " (" + size.getX() + " x " + size.getY() + " x " + size.getZ() + ")";
    }

    /** The generated site archetypes that can be previewed today. */
    public static final List<String> ARCHETYPES = List.of("growing_station", "derelict_freighter", "derelict_survey");

    /** Point names the generators understand; anything else is flagged by {@link #check}. */
    public static final Set<String> POINT_NAMES = Set.of("loot", "mob", "core", "light", "breach_ok");

    /** Lists a saved piece's connectors and points and the problems that would stop it joining. */
    public static String check(String kind, String name)
    {
        var id = id(kind, name).getPath();
        PieceLibrary.invalidate();
        var piece = PieceLibrary.piece(id);
        var size = piece.size();
        var problems = new ArrayList<String>();
        var text = new StringBuilder(id + ": " + size.getX() + " x " + size.getY() + " x " + size.getZ());
        for (var c : piece.connectors())
        {
            text.append("\n  connector ").append(c.type()).append(c.target().equals(c.type()) ? "" : " -> " + c.target()).append(" facing ").append(c.facing().getSerializedName()).append(" at ").append(c.pos().toShortString());
            var at = c.pos();
            boolean edge = switch (c.facing())
            {
                case NORTH -> at.getZ() == 0;
                case SOUTH -> at.getZ() == size.getZ() - 1;
                case WEST -> at.getX() == 0;
                case EAST -> at.getX() == size.getX() - 1;
                case DOWN -> at.getY() == 0;
                case UP -> at.getY() == size.getY() - 1;
            };
            if (!edge)
                problems.add("connector at " + at.toShortString() + " faces " + c.facing().getSerializedName() + " but is not on that face of the piece");
        }
        for (var point : piece.points())
        {
            text.append("\n  point ").append(point.name()).append(point.argument().isEmpty() ? "" : ":" + point.argument()).append(" at ").append(point.pos().toShortString());
            if (!POINT_NAMES.contains(point.name()))
                problems.add("unknown point name " + point.name() + " (known: " + String.join(", ", new TreeSet<>(POINT_NAMES)) + ")");
        }
        var bad = GenerationPalette.violations(piece.blocks());
        if (!bad.isEmpty())
            problems.add(bad.size() + " valuable blocks, first at " + bad.getFirst().toShortString());
        text.append(problems.isEmpty() ? "\nNo problems." : "\nProblems:");
        problems.forEach(p -> text.append("\n  ").append(p));
        return text.toString();
    }

    /**
     * Builds a generated site, a shipyard design ({@code ship:<id>}) or a kit assembly
     * ({@code kit:<kit>:<archetype>}) on the pad so its look and variation can be reviewed.
     * Containers get the site loot for {@code danger}.
     */
    public static String preview(ServerPlayer player, String archetype, int danger, long seed)
    {
        var volume = volume(player);
        Map<BlockPos, BlockState> blocks;
        String problems = "";
        if (archetype.startsWith("ship:"))
        {
            var preset = ShipBlueprints.get(archetype.substring(5));
            if (preset == null)
                throw new IllegalArgumentException("No shipyard design " + archetype);
            blocks = ShipBlueprints.blocks(preset);
        }
        else if (archetype.startsWith("kit:"))
        {
            var parts = archetype.split(":");
            if (parts.length != 3)
                throw new IllegalArgumentException("Use kit:<kit>:<archetype>");
            PieceLibrary.invalidate();
            var type = Archetype.load(parts[2]);
            var build = Condition.omitSection(Assembler.assemble(Kit.load(parts[1]), type, seed), type, danger, seed);
            var conditioned = Condition.apply(Assembler.plan(build), danger, seed);
            blocks = conditioned.plan().blocks();
            var report = conditioned.report();
            problems = ". " + build.placed().size() + " pieces, " + report.breaches() + " breaches, " + report.scorched() + " scorched, " + report.darkened() + " lights out";
            if (!build.problems().isEmpty())
                problems += ". Problems: " + String.join("; ", build.problems());
        }
        else
        {
            int kind = ARCHETYPES.indexOf(archetype);
            if (kind < 0)
                throw new IllegalArgumentException("Unknown archetype. Try " + String.join(", ", ARCHETYPES) + " or ship:<design>");
            blocks = EncounterSites.plan(kind, seed).blocks();
        }
        int maxX = 0, maxY = 0, maxZ = 0;
        for (var at : blocks.keySet())
        {
            maxX = Math.max(maxX, at.getX());
            maxY = Math.max(maxY, at.getY());
            maxZ = Math.max(maxZ, at.getZ());
        }
        if (maxX >= volume.size().getX() || maxY >= volume.size().getY() || maxZ >= volume.size().getZ())
            throw new IllegalArgumentException(archetype + " is larger than this pad");
        var level = player.level();
        clear(level, volume);
        for (var e : blocks.entrySet())
            level.setBlock(volume.min().offset(e.getKey()), e.getValue(), Block.UPDATE_CLIENTS);
        int containers = 0;
        for (var e : blocks.entrySet())
            if (SiteLoot.assign(level, volume.min().offset(e.getKey()), containers == 0 ? "treasure_" + Math.clamp(danger, 0, 3) : "cargo", seed + containers, "Preview supplies"))
                containers++;
        return "Previewed " + archetype + " (danger " + danger + ", seed " + seed + "): " + blocks.size() + " blocks, " + containers + " containers" + (GenerationPalette.violations(blocks).isEmpty() ? "" : ". Warning: uses valuable blocks") + problems;
    }

    /** Origin of the authoring gallery: a grid of pads, one per saved piece. */
    static final BlockPos GALLERY = new BlockPos(30_000, 100, 30_000);

    /** Builds a pad for every saved piece (optionally of one kind) with the piece on it. */
    public static String gallery(ServerPlayer player, String kind) throws Exception
    {
        var level = player.level().getServer().overworld();
        var manager = level.getServer().getStructureTemplateManager();
        var ids = manager.listTemplates().filter(i -> i.getNamespace().equals("nullspace")).filter(i -> kind.isEmpty() || i.getPath().startsWith(kind + "/")).sorted(Comparator.comparing(Identifier::toString)).toList();
        if (ids.isEmpty())
            return "No saved pieces" + (kind.isEmpty() ? "" : " of kind " + kind);
        var existing = new HashSet<String>();
        for (var pad : PadRegistry.read(level.getServer()))
            existing.add(pad.name());
        for (int i = 0; i < ids.size(); i++)
        {
            var base = GALLERY.offset((i % 6) * 40, 0, (i / 6) * 40);
            // Pad names allow 32 letters, digits, spaces, underscores and hyphens.
            String name = ("G" + (i + 1) + " " + ids.get(i).getPath().replace('/', ' '));
            if (name.length() > 32)
                name = name.substring(0, 32).strip();
            if (!existing.contains(name))
                SphereLab.buildPad(level, base, name);
            var volume = new Volume(null, base.above(), new Vec3i(32, 32, 32));
            clear(level, volume);
            var template = manager.get(ids.get(i)).orElseThrow();
            template.placeInWorld(level, base.above(), base.above(), new StructurePlaceSettings(), RandomSource.create(), Block.UPDATE_CLIENTS);
        }
        player.teleportTo(level, GALLERY.getX() - 4.5, GALLERY.getY() + 2, GALLERY.getZ() - 4.5, Set.of(), -45, 20, true);
        return "Gallery: " + ids.size() + " pieces on pads starting at " + GALLERY.toShortString();
    }

    public static void initialize()
    {
        // Pieces saved in this world are visible to check, preview and kits without a rebuild.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> PieceLibrary.worldSource(id -> server.getStructureTemplateManager().get(Identifier.parse("nullspace:" + id)).map(template -> template.save(new net.minecraft.nbt.CompoundTag()))));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> PieceLibrary.worldSource(null));
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(Commands.literal("nullspace").then(authorCommand())));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, String> kind()
    {
        return Commands.argument("kind", StringArgumentType.word());
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, String> name()
    {
        return Commands.argument("name", StringArgumentType.word());
    }

    private static String text(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, String argument)
    {
        return StringArgumentType.getString(context, argument);
    }

    private static ServerPlayer player(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        return context.getSource().getPlayerOrException();
    }

    /** /nullspace author: save, load, preview, check and gallery, each built on its own. Operators only. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> authorCommand()
    {
        var saveCommand = Commands.literal("save").then(kind().then(name().executes(c -> reply(c.getSource(), () -> save(player(c), text(c, "kind"), text(c, "name"))))));
        var loadCommand = Commands.literal("load").then(kind().then(name().executes(c -> reply(c.getSource(), () -> load(player(c), text(c, "kind"), text(c, "name"))))));
        var checkCommand = Commands.literal("check").then(kind().then(name().executes(c -> reply(c.getSource(), () -> check(text(c, "kind"), text(c, "name"))))));
        var galleryCommand = Commands.literal("gallery").executes(c -> reply(c.getSource(), () -> gallery(player(c), ""))).then(kind().executes(c -> reply(c.getSource(), () -> gallery(player(c), text(c, "kind")))));
        // preview <archetype> [danger [seed]]: a danger level and then a seed narrow it.
        var withSeed = Commands.argument("seed", LongArgumentType.longArg()).executes(c -> reply(c.getSource(), () -> preview(player(c), text(c, "archetype"), IntegerArgumentType.getInteger(c, "danger"), LongArgumentType.getLong(c, "seed"))));
        var withDanger = Commands.argument("danger", IntegerArgumentType.integer(0, 3)).executes(c -> reply(c.getSource(), () -> preview(player(c), text(c, "archetype"), IntegerArgumentType.getInteger(c, "danger"), c.getSource().getLevel().getGameTime()))).then(withSeed);
        var previewCommand = Commands.literal("preview").then(Commands.argument("archetype", StringArgumentType.string()).executes(c -> reply(c.getSource(), () -> preview(player(c), text(c, "archetype"), 0, c.getSource().getLevel().getGameTime()))).then(withDanger));
        var operatorsOnly = Commands.literal("author").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
        return operatorsOnly.then(saveCommand).then(loadCommand).then(previewCommand).then(checkCommand).then(galleryCommand);
    }

    private interface Action
    {
        String run() throws Exception;
    }

    private static int reply(net.minecraft.commands.CommandSourceStack source, Action action)
    {
        try
        {
            String message = action.run();
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        }
        catch (Exception failure)
        {
            source.sendFailure(Component.literal(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            return 0;
        }
    }

    private Authoring()
    {
    }
}
