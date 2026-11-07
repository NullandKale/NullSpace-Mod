package com.nullandkale.nullspace.generation;

import com.mojang.brigadier.arguments.*;
import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.shipyard.ShipBlueprints;
import com.nullandkale.nullspace.station.*;
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

/**
 * Authoring tools for generated content (procedural generation design, step 1). Pieces are built
 * on a registered pad and saved as vanilla structure templates {@code nullspace:<kind>/<name>}.
 * Vanilla structure blocks in DATA mode and jigsaw blocks inside a piece are its markers (loot and
 * mob spots, doorway connectors); they are saved with it. In a development run the template is
 * also written to {@code -Dnullspace.authoringDir} (the mod's resources) so it ships with the mod.
 */
public final class Authoring {
  public record Volume(PadRegistry.Pad pad, BlockPos min, Vec3i size) {}

  /** The build volume of the registered pad the player stands on. */
  public static Volume volume(ServerPlayer player) {
    var pad =
        PadRegistry.below(player.level(), player.blockPosition())
            .orElseThrow(
                () -> new IllegalArgumentException("Stand on a registered pad to author a piece"));
    var b = pad.bounds();
    int height = PadRegistry.buildHeight(player.level(), b);
    return new Volume(
        pad, new BlockPos(b.minX(), b.y() + 1, b.minZ()), new Vec3i(b.width(), height, b.depth()));
  }

  public static Identifier id(String kind, String name) {
    if (!kind.matches("[a-z0-9_]{1,32}") || !name.matches("[a-z0-9_]{1,48}"))
      throw new IllegalArgumentException("Kind and name use lowercase letters, digits and _");
    return Identifier.parse("nullspace:" + kind + "/" + name);
  }

  /** Saves everything on the pad, trimmed to the blocks actually built, as a template. */
  public static String save(ServerPlayer player, String kind, String name) throws Exception {
    var id = id(kind, name);
    var volume = volume(player);
    var level = player.level();
    BlockPos lo = null, hi = null;
    var blocks = new HashMap<BlockPos, BlockState>();
    int markers = 0;
    for (var cursor :
        BlockPos.betweenClosed(volume.min(), volume.min().offset(volume.size()).offset(-1, -1, -1))) {
      var state = level.getBlockState(cursor);
      if (state.isAir()) continue;
      var at = cursor.immutable();
      blocks.put(at, state);
      if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW)) markers++;
      lo = lo == null ? at : new BlockPos(Math.min(lo.getX(), at.getX()), Math.min(lo.getY(), at.getY()), Math.min(lo.getZ(), at.getZ()));
      hi = hi == null ? at : new BlockPos(Math.max(hi.getX(), at.getX()), Math.max(hi.getY(), at.getY()), Math.max(hi.getZ(), at.getZ()));
    }
    if (lo == null) throw new IllegalArgumentException("The pad is empty; build the piece first");
    var manager = level.getServer().getStructureTemplateManager();
    var template = manager.getOrCreate(id);
    template.fillFromWorld(
        level, lo, new Vec3i(hi.getX() - lo.getX() + 1, hi.getY() - lo.getY() + 1, hi.getZ() - lo.getZ() + 1),
        false, List.of(Blocks.STRUCTURE_VOID));
    template.setAuthor(player.getPlainTextName());
    if (!manager.save(id)) throw new IllegalStateException("Could not save " + id);
    String copied = "";
    String dir = System.getProperty("nullspace.authoringDir");
    if (dir != null && !dir.isBlank()) {
      var file =
          Path.of(dir, "data", "nullspace", "structure", kind, name + ".nbt");
      Files.createDirectories(file.getParent());
      StructureTemplateManager.save(file, template, false);
      copied = " Copied into the mod's resources.";
    }
    var bad = GenerationPalette.violations(blocks);
    var size = template.getSize();
    return "Saved "
        + id
        + ": "
        + blocks.size()
        + " blocks in "
        + size.getX() + " x " + size.getY() + " x " + size.getZ()
        + ", "
        + markers
        + " markers."
        + copied
        + (bad.isEmpty()
            ? ""
            : " Warning: "
                + bad.size()
                + " valuable blocks (generated content must use cheap blocks), first at "
                + bad.getFirst().toShortString());
  }

  /** Clears the pad's build volume. */
  static void clear(ServerLevel level, Volume volume) {
    for (var at :
        BlockPos.betweenClosed(volume.min(), volume.min().offset(volume.size()).offset(-1, -1, -1)))
      if (!level.getBlockState(at).isAir()) {
        level.removeBlockEntity(at);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
      }
  }

  /** Places a saved template on the pad for editing, replacing whatever was there. */
  public static String load(ServerPlayer player, String kind, String name) {
    var id = id(kind, name);
    var volume = volume(player);
    var template =
        player.level().getServer().getStructureTemplateManager().get(id)
            .orElseThrow(() -> new IllegalArgumentException("No saved piece " + id));
    var size = template.getSize();
    if (size.getX() > volume.size().getX()
        || size.getY() > volume.size().getY()
        || size.getZ() > volume.size().getZ())
      throw new IllegalArgumentException("Piece " + id + " is larger than this pad");
    clear(player.level(), volume);
    template.placeInWorld(
        player.level(), volume.min(), volume.min(), new StructurePlaceSettings(),
        RandomSource.create(), Block.UPDATE_CLIENTS);
    return "Loaded " + id + " (" + size.getX() + " x " + size.getY() + " x " + size.getZ() + ")";
  }

  /** The generated site archetypes that can be previewed today. */
  public static final List<String> ARCHETYPES =
      List.of("growing_station", "derelict_freighter", "derelict_survey");

  /**
   * Builds a generated site, or a shipyard design ({@code ship:<id>}), on the pad so its look and
   * variation can be reviewed. Containers get the site loot for {@code danger}.
   */
  public static String preview(ServerPlayer player, String archetype, int danger, long seed) {
    var volume = volume(player);
    Map<BlockPos, BlockState> blocks;
    if (archetype.startsWith("ship:")) {
      var preset = ShipBlueprints.get(archetype.substring(5));
      if (preset == null) throw new IllegalArgumentException("No shipyard design " + archetype);
      blocks = ShipBlueprints.blocks(preset);
    } else {
      int kind = ARCHETYPES.indexOf(archetype);
      if (kind < 0)
        throw new IllegalArgumentException(
            "Unknown archetype. Try " + String.join(", ", ARCHETYPES) + " or ship:<design>");
      blocks = EncounterSites.plan(kind, seed).blocks();
    }
    int maxX = 0, maxY = 0, maxZ = 0;
    for (var at : blocks.keySet()) {
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
      if (SiteLoot.assign(
          level,
          volume.min().offset(e.getKey()),
          containers == 0 ? "treasure_" + Math.clamp(danger, 0, 3) : "cargo",
          seed + containers,
          "Preview supplies")) containers++;
    return "Previewed "
        + archetype
        + " (danger "
        + danger
        + ", seed "
        + seed
        + "): "
        + blocks.size()
        + " blocks, "
        + containers
        + " containers"
        + (GenerationPalette.violations(blocks).isEmpty() ? "" : ". Warning: uses valuable blocks");
  }

  /** Origin of the authoring gallery: a grid of pads, one per saved piece. */
  static final BlockPos GALLERY = new BlockPos(30_000, 100, 30_000);

  /** Builds a pad for every saved piece (optionally of one kind) with the piece on it. */
  public static String gallery(ServerPlayer player, String kind) throws Exception {
    var level = player.level().getServer().overworld();
    var manager = level.getServer().getStructureTemplateManager();
    var ids =
        manager.listTemplates()
            .filter(i -> i.getNamespace().equals("nullspace"))
            .filter(i -> kind.isEmpty() || i.getPath().startsWith(kind + "/"))
            .sorted(Comparator.comparing(Identifier::toString))
            .toList();
    if (ids.isEmpty()) return "No saved pieces" + (kind.isEmpty() ? "" : " of kind " + kind);
    var existing = new HashSet<String>();
    for (var pad : PadRegistry.read(level.getServer())) existing.add(pad.name());
    for (int i = 0; i < ids.size(); i++) {
      var base = GALLERY.offset((i % 6) * 40, 0, (i / 6) * 40);
      // Pad names allow 32 letters, digits, spaces, underscores and hyphens.
      String name = ("G" + (i + 1) + " " + ids.get(i).getPath().replace('/', ' '));
      if (name.length() > 32) name = name.substring(0, 32).strip();
      if (!existing.contains(name)) SphereLab.buildPad(level, base, name);
      var volume =
          new Volume(null, base.above(), new Vec3i(32, 32, 32));
      clear(level, volume);
      var template = manager.get(ids.get(i)).orElseThrow();
      template.placeInWorld(
          level, base.above(), base.above(), new StructurePlaceSettings(), RandomSource.create(),
          Block.UPDATE_CLIENTS);
    }
    player.teleportTo(
        level, GALLERY.getX() - 4.5, GALLERY.getY() + 2, GALLERY.getZ() - 4.5, Set.of(), -45, 20,
        true);
    return "Gallery: " + ids.size() + " pieces on pads starting at " + GALLERY.toShortString();
  }

  public static void initialize() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("author")
                            .requires(
                                s ->
                                    s.permissions()
                                        .hasPermission(
                                            net.minecraft.server.permissions.Permissions
                                                .COMMANDS_GAMEMASTER))
                            .then(
                                Commands.literal("save")
                                    .then(
                                        Commands.argument("kind", StringArgumentType.word())
                                            .then(
                                                Commands.argument("name", StringArgumentType.word())
                                                    .executes(
                                                        c ->
                                                            reply(
                                                                c.getSource(),
                                                                () ->
                                                                    save(
                                                                        c.getSource()
                                                                            .getPlayerOrException(),
                                                                        StringArgumentType
                                                                            .getString(c, "kind"),
                                                                        StringArgumentType
                                                                            .getString(c, "name")))))))
                            .then(
                                Commands.literal("load")
                                    .then(
                                        Commands.argument("kind", StringArgumentType.word())
                                            .then(
                                                Commands.argument("name", StringArgumentType.word())
                                                    .executes(
                                                        c ->
                                                            reply(
                                                                c.getSource(),
                                                                () ->
                                                                    load(
                                                                        c.getSource()
                                                                            .getPlayerOrException(),
                                                                        StringArgumentType
                                                                            .getString(c, "kind"),
                                                                        StringArgumentType
                                                                            .getString(c, "name")))))))
                            .then(
                                Commands.literal("preview")
                                    .then(
                                        Commands.argument("archetype", StringArgumentType.string())
                                            .executes(
                                                c ->
                                                    reply(
                                                        c.getSource(),
                                                        () ->
                                                            preview(
                                                                c.getSource().getPlayerOrException(),
                                                                StringArgumentType.getString(
                                                                    c, "archetype"),
                                                                0,
                                                                c.getSource()
                                                                    .getLevel()
                                                                    .getGameTime())))
                                            .then(
                                                Commands.argument(
                                                        "danger", IntegerArgumentType.integer(0, 3))
                                                    .executes(
                                                        c ->
                                                            reply(
                                                                c.getSource(),
                                                                () ->
                                                                    preview(
                                                                        c.getSource()
                                                                            .getPlayerOrException(),
                                                                        StringArgumentType
                                                                            .getString(
                                                                                c, "archetype"),
                                                                        IntegerArgumentType
                                                                            .getInteger(
                                                                                c, "danger"),
                                                                        c.getSource()
                                                                            .getLevel()
                                                                            .getGameTime())))
                                                    .then(
                                                        Commands.argument(
                                                                "seed", LongArgumentType.longArg())
                                                            .executes(
                                                                c ->
                                                                    reply(
                                                                        c.getSource(),
                                                                        () ->
                                                                            preview(
                                                                                c.getSource()
                                                                                    .getPlayerOrException(),
                                                                                StringArgumentType
                                                                                    .getString(
                                                                                        c,
                                                                                        "archetype"),
                                                                                IntegerArgumentType
                                                                                    .getInteger(
                                                                                        c,
                                                                                        "danger"),
                                                                                LongArgumentType
                                                                                    .getLong(
                                                                                        c,
                                                                                        "seed"))))))))
                            .then(
                                Commands.literal("gallery")
                                    .executes(
                                        c ->
                                            reply(
                                                c.getSource(),
                                                () ->
                                                    gallery(
                                                        c.getSource().getPlayerOrException(), "")))
                                    .then(
                                        Commands.argument("kind", StringArgumentType.word())
                                            .executes(
                                                c ->
                                                    reply(
                                                        c.getSource(),
                                                        () ->
                                                            gallery(
                                                                c.getSource().getPlayerOrException(),
                                                                StringArgumentType.getString(
                                                                    c, "kind")))))))));
  }

  private interface Action {
    String run() throws Exception;
  }

  private static int reply(net.minecraft.commands.CommandSourceStack source, Action action) {
    try {
      String message = action.run();
      source.sendSuccess(() -> Component.literal(message), false);
      return 1;
    } catch (Exception failure) {
      source.sendFailure(
          Component.literal(
              failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
      return 0;
    }
  }

  private Authoring() {}
}
