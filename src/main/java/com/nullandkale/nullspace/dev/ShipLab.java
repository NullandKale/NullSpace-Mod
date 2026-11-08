package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import java.nio.file.Files;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in lab fixtures: only use an empty volume above terrain, never replace an existing build. */
public final class ShipLab {
  public static void initialize() {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("lab")
                            .executes(ctx -> visit(ctx.getSource().getPlayerOrException(), 0))
                            .then(
                                Commands.literal("gallery")
                                    .executes(
                                        ctx -> visit(ctx.getSource().getPlayerOrException(), 0)))
                            .then(
                                Commands.literal("sphere")
                                    .executes(
                                        ctx -> visit(ctx.getSource().getPlayerOrException(), 21)))
                            .then(
                                Commands.literal("systems")
                                    .executes(
                                        ctx -> visit(ctx.getSource().getPlayerOrException(), 23)))
                            .then(
                                Commands.literal("recipes")
                                    .executes(
                                        ctx -> visit(ctx.getSource().getPlayerOrException(), -2)))
                            .then(
                                Commands.literal("plot")
                                    .then(
                                        Commands.argument(
                                                "number",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                    .integer(1, 24))
                                            .executes(
                                                ctx ->
                                                    visit(
                                                        ctx.getSource().getPlayerOrException(),
                                                        com.mojang.brigadier.arguments
                                                            .IntegerArgumentType.getInteger(
                                                            ctx, "number")))))
                            .then(
                                Commands.literal("reference")
                                    .executes(
                                        ctx ->
                                            visit(ctx.getSource().getPlayerOrException(), -1))))));
  }

  private static int visit(ServerPlayer player, int plot) {
    try {
      if (TransferLocks.busy(player))
        throw new IllegalArgumentException(
            "Wait for ship travel to finish before visiting the lab");
      FieldGuide.supply(player);
      var server = player.level().getServer();
      var level = server.overworld();
      if (plot == -2) {
        var gallery = RecipeLab.ensure(server);
        var at = gallery.spawn();
        player.teleportTo(
            level, at.getX() + .5, at.getY(), at.getZ() + .5, java.util.Set.of(), 0, 0, true);
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "Recipe gallery: 19 labeled chests. Each shows the crafting grid on the left and"
                    + " output on the right; crafting tables are beside the chests. You can take"
                    + " the samples. Returning does not refill or replace your edits."));
      } else if (plot >= 23) {
        var origin = SystemsLab.ensure(server);
        var at = SystemsLab.base(origin, plot).offset(15, 1, -2);
        player.teleportTo(
            level, at.getX() + .5, at.getY(), at.getZ() + .5, java.util.Set.of(), 0, 0, true);
        supply(player);
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "Powered systems lab: helm and systems controller on deck 3; engineering,"
                    + " batteries, air supply and cameras on deck 1. Inspect/launch at the helm;"
                    + " power/doors at a systems controller. Right-click a monitor, Tab to change"
                    + " camera."));
      } else if (plot >= 21) {
        var gallery = ShipGallery.ensure(server);
        var annex = SphereLab.ensure(server, gallery);
        var at = plot == 21 ? annex.entry() : annex.blank().offset(15, 1, -2);
        player.teleportTo(
            level, at.getX() + .5, at.getY(), at.getZ() + .5, java.util.Set.of(), 0, 0, true);
        supply(player);
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "Meridian sphere: 32 x 32 x 32, five decks, ten rooms. Walk through the stair"
                    + " airlock and follow the stairs to the bridge on deck 3. Close all doors"
                    + " before Inspect/Launch. Plot 22 is an empty full-size landing pad."));
      } else if (plot >= 0) {
        var gallery = ShipGallery.ensure(server);
        var at = plot == 0 ? gallery.spawn() : gallery.base(plot).offset(5, 1, -3);
        player.teleportTo(
            level, at.getX() + .5, at.getY(), at.getZ() + .5, java.util.Set.of(), 0, 0, true);
        supply(player);
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "Ship gallery: 01-08 launchable, 09-12 repairs, 13-20 blanks. /nullspace lab sphere"
                    + " visits the 32-cube sphere; plot 22 is a full-size blank. Supplies at the"
                    + " entrance. /nullspace lab plot <1-22> visits a plot."));
      } else {
        var base = ensure(server);
        var computer = base.offset(6, 3, 3);
        if (level.getBlockState(computer).is(ModBlocks.COMPUTER)) {
          player.teleportTo(
              level,
              base.getX() + 4.5,
              base.getY() + 3,
              base.getZ() + 3.5,
              java.util.Set.of(),
              90,
              0,
              true);
          supply(player);
          ShipService.open(
              player,
              computer,
              "Reference ship ready. Inspect, then Launch. Home pad is already registered. Use"
                  + " /gamemode survival to test suit and vacuum hazards.");
        } else {
          player.teleportTo(
              level,
              base.getX() - 2,
              base.getY() + 1,
              base.getZ() + 3.5,
              java.util.Set.of(),
              -90,
              0,
              true);
          player.sendSystemMessage(
              net.minecraft.network.chat.Component.literal(
                  "The reference ship has left or was edited. Its registered pad is here; existing"
                      + " edits are preserved."));
        }
      }
      return 1;
    } catch (Exception error) {
      player.sendSystemMessage(
          net.minecraft.network.chat.Component.literal(
              "Cannot prepare ship lab: " + error.getMessage()));
      return 0;
    }
  }

  private static void supply(ServerPlayer player) {
    for (var item :
        new net.minecraft.world.item.Item[] {
          ModBlocks.HELMET,
          ModBlocks.CHEST,
          ModBlocks.LEGS,
          ModBlocks.BOOTS,
          net.minecraft.world.item.Items.COOKED_BEEF
        }) {
      if (player.getInventory().countItem(item) > 0) continue;
      var stack =
          new net.minecraft.world.item.ItemStack(
              item, item == net.minecraft.world.item.Items.COOKED_BEEF ? 64 : 1);
      player.getInventory().add(stack);
      if (!stack.isEmpty())
        player
            .level()
            .addFreshEntity(
                new net.minecraft.world.entity.item.ItemEntity(
                    player.level(), player.getX(), player.getY(), player.getZ(), stack));
    }
  }

  public static BlockPos ensure(MinecraftServer server) throws java.io.IOException {
    var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-reference-lab-v1");
    if (Files.exists(marker)) return BlockPos.of(Long.parseLong(Files.readString(marker).strip()));
    var level = server.overworld();
    for (int x = 128; x <= 2048; x += 32) {
      level.getChunk(new BlockPos(x, 0, 0));
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, 0) + 8;
      if (y + 40 > level.getMaxY()) continue;
      var base = new BlockPos(x, y, 0);
      boolean clear = true;
      outer:
      for (int dx = -1; dx <= 8; dx++)
        for (int z = -1; z <= 8; z++) {
          for (int dy = 0; dy <= 40; dy++)
            if (!level.getBlockState(base.offset(dx, dy, z)).isAir()) {
              clear = false;
              break outer;
            }
        }
      var bounds = new PadScanner.Pad(x, x + 7, y, 0, 7);
      if (!clear
          || PadRegistry.read(server).stream()
              .anyMatch(
                  p ->
                      p.dimension().equals(level.dimension().identifier().toString())
                          && bounds.overlaps(p.bounds()))) continue;
      String name = "Reference " + x;
      if (PadRegistry.read(server).stream().anyMatch(p -> p.name().equalsIgnoreCase(name)))
        continue;
      ReferenceShip.build(level, base);
      String result = PadComputers.register(level, base.offset(-1, 1, 3), bounds, name);
      if (!result.startsWith("Registered ")) throw new IllegalStateException(result);
      ShipService.inspect(level, base.offset(6, 3, 3));
      Files.writeString(marker, Long.toString(base.asLong()));
      return base;
    }
    throw new IllegalStateException("No clear lab volume available between x=128 and x=2048");
  }

  private ShipLab() {}
}
