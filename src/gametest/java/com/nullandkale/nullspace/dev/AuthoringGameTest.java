package com.nullandkale.nullspace.dev;

import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.StructureMode;

/** Pieces are built on a pad, saved as templates, loaded back, previewed and shown in a gallery. */
public final class AuthoringGameTest {
  @GameTest(maxTicks = 200)
  public void piecesSaveLoadPreviewAndAppearInTheGallery(GameTestHelper ctx) throws Exception {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(9216, 64, 9216));
    String tag = UUID.randomUUID().toString().substring(0, 8);
    SphereLab.buildPad(level, base, "Authoring " + tag);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 5.5, base.getY() + 1, base.getZ() + 5.5);

    // A small piece: a floor, a wall with glass, a chest, a loot marker and one valuable block.
    for (int x = 2; x <= 6; x++)
      for (int z = 2; z <= 6; z++)
        level.setBlock(base.offset(x, 1, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
    for (int y = 2; y <= 4; y++)
      level.setBlock(
          base.offset(2, y, 4),
          (y == 3 ? Blocks.GLASS : Blocks.SMOOTH_STONE).defaultBlockState(),
          18);
    level.setBlock(base.offset(4, 2, 4), Blocks.CHEST.defaultBlockState(), 18);
    level.setBlock(
        base.offset(5, 2, 5),
        Blocks.STRUCTURE_BLOCK
            .defaultBlockState()
            .setValue(StructureBlock.MODE, StructureMode.DATA),
        18);
    level.setBlock(base.offset(6, 2, 6), Blocks.IRON_BLOCK.defaultBlockState(), 18);

    String kind = "testauthoring";
    String name = "piece_" + tag;
    String saved = Authoring.save(player, kind, name);
    ctx.assertTrue(
        saved.startsWith("Saved nullspace:" + kind + "/" + name)
            && saved.contains("1 markers")
            && saved.contains("5 x 4 x 5")
            && saved.contains("Warning"),
        "Save trims to the build, counts markers and warns about valuable blocks: " + saved);
    var id = Authoring.id(kind, name);
    var manager = level.getServer().getStructureTemplateManager();
    ctx.assertTrue(manager.get(id).isPresent(), "The piece is a saved structure template");

    String loaded = Authoring.load(player, kind, name);
    var volume = Authoring.volume(player);
    ctx.assertTrue(
        loaded.startsWith("Loaded")
            && level.getBlockState(volume.min()).is(Blocks.STONE_BRICKS)
            // Trimmed to its build and placed at the pad corner: the chest moves from (4,2,4).
            && level.getBlockState(base.offset(2, 2, 2)).is(Blocks.CHEST)
            && level.getBlockState(base.offset(6, 1, 6)).isAir(),
        "Load clears the pad and places the piece at its corner: " + loaded);

    String preview = Authoring.preview(player, "derelict_survey", 2, 5);
    ctx.assertTrue(
        preview.startsWith("Previewed derelict_survey (danger 2, seed 5)")
            && !preview.contains("Warning")
            && !preview.contains(" 0 containers"),
        "A generated derelict previews on the pad with loot containers: " + preview);
    String ship = Authoring.preview(player, "ship:sparrow", 0, 1);
    ctx.assertTrue(
        ship.startsWith("Previewed ship:sparrow"), "Shipyard designs preview too: " + ship);
    boolean refused = false;
    try {
      Authoring.preview(player, "not_a_site", 0, 1);
    } catch (IllegalArgumentException expected) {
      refused = expected.getMessage().contains("derelict_survey");
    }
    ctx.assertTrue(refused, "Unknown archetypes list the ones that exist");

    String gallery = Authoring.gallery(player, kind);
    ctx.assertTrue(
        gallery.startsWith("Gallery: 1 pieces"), "The gallery shows each piece: " + gallery);
    var shown = level.getServer().overworld().getBlockState(Authoring.GALLERY.above());
    ctx.assertTrue(shown.is(Blocks.STONE_BRICKS), "The piece stands on its gallery pad");

    manager.remove(id);
    ctx.succeed();
  }
}
