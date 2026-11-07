package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

public final class PlaytestWorld {
  private static final ServerContext.Key<Set<UUID>> ARRIVALS = new ServerContext.Key<>();

  public static void initialize() {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> {
          if (!Boolean.getBoolean("nullspace.playtest")) return;
          var root = server.getWorldPath(LevelResource.ROOT);
          String save = root.toAbsolutePath().normalize().getFileName().toString();
          if (server.isDedicatedServer()
              || !(save.equals("NullSpace-Dev") || save.equals("NullSpace-Lab"))) return;
          // JOIN precedes native registration in the captured login level. A lab
          // teleport here can register a space player in both worlds and corrupt
          // the entity callbacks and distance-manager membership.
          ServerContext.get(server).service(ARRIVALS, HashSet::new).add(handler.player.getUUID());
        });
    ServerContext.tick(
        ServerContext.Phase.FIXTURES,
        server -> {
          var arrivals = ServerContext.get(server).remove(ARRIVALS);
          if (arrivals != null)
            for (var id : arrivals) {
              var player = server.getPlayerList().getPlayer(id);
              if (player != null) prepare(player);
            }
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> ServerContext.get(server).remove(ARRIVALS));
  }

  private static void prepare(ServerPlayer player) {
    FieldGuide.supply(player);
    var server = player.level().getServer();
    var root = server.getWorldPath(LevelResource.ROOT);
    String save = root.toAbsolutePath().normalize().getFileName().toString();
    var supplies = root.resolve("nullspace-ship-supplies-v2-" + player.getUUID());
    if (!Files.exists(supplies)) {
      for (var item :
          new net.minecraft.world.item.Item[] {
            ModBlocks.COMPUTER.asItem(),
            ModBlocks.ENGINE.asItem(),
            ModBlocks.HELMET,
            ModBlocks.CHEST,
            ModBlocks.LEGS,
            ModBlocks.BOOTS
          }) {
        var stack = new ItemStack(item);
        player.getInventory().add(stack);
        if (!stack.isEmpty())
          player
              .level()
              .addFreshEntity(
                  new net.minecraft.world.entity.item.ItemEntity(
                      player.level(), player.getX(), player.getY(), player.getZ(), stack));
      }
      try {
        Files.writeString(
            supplies, "Ship equipment supplied. Existing pads and player builds preserved.");
      } catch (java.io.IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
      player.sendSystemMessage(
          Component.literal(
              "Ship equipment supplied: computer, engine, and four suit pieces. Register your pad,"
                  + " face the computer into the cabin and engine exhaust outside, close doors,"
                  + " then inspect/launch."));
    }
    var marker = root.resolve("nullspace-fixtures-v1");
    try {
      var lab = ShipLab.ensure(server);
      var gallery = ShipGallery.ensure(server);
      var sphere = SphereLab.ensure(server, gallery);
      SystemsLab.ensure(server);
      RecipeLab.ensure(server);
      player.sendSystemMessage(
          Component.literal(
              "Ship gallery at "
                  + gallery.origin().toShortString()
                  + "; 32-cube sphere at "
                  + sphere.base().toShortString()
                  + ". Use /nullspace lab, /nullspace lab sphere, or /nullspace lab recipes."
                  + " Original reference at x="
                  + lab.getX()
                  + " and existing edits are preserved."));
    } catch (Exception error) {
      NullSpace.LOGGER.error("Could not prepare reference ship lab", error);
    }
    if (save.equals("NullSpace-Lab")) {
      var boarded = root.resolve("nullspace-lab-boarded-" + player.getUUID());
      if (!Files.exists(boarded)) {
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        server
            .getCommands()
            .performPrefixedCommand(player.createCommandSourceStack(), "nullspace lab");
        try {
          Files.writeString(
              boarded,
              "Ship gallery introduction completed; preserve player position on subsequent joins.");
        } catch (java.io.IOException error) {
          throw new java.io.UncheckedIOException(error);
        }
      }
      return;
    }
    if (Files.exists(marker)) return;
    var level = server.overworld();
    int surface =
        level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)
            - 1;
    for (int base : new int[] {0, 16, 32}) {
      for (int x = -1; x <= 8; x++)
        for (int z = -1; z <= 8; z++) {
          boolean edge = x == -1 || x == 8 || z == -1 || z == 8;
          level.setBlockAndUpdate(
              new BlockPos(base + x, surface, z),
              (edge ? Blocks.COBBLESTONE : Blocks.GRASS_BLOCK).defaultBlockState());
        }
      level.setBlockAndUpdate(
          new BlockPos(base - 1, surface + 1, 3),
          PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.EAST));
    }
    level.setBlockAndUpdate(new BlockPos(24, surface, 8), Blocks.AIR.defaultBlockState());
    level.setBlockAndUpdate(new BlockPos(36, surface, 4), Blocks.DIRT.defaultBlockState());
    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    player.teleportTo(level, -3, surface + 1, 3, Set.of(), -90, 10, true);
    for (var item :
        new net.minecraft.world.item.Item[] {
          PadComputers.BLOCK.asItem(),
          Items.GRASS_BLOCK,
          Items.COBBLESTONE,
          Items.STONE,
          Items.GLASS,
          Items.IRON_DOOR,
          Items.OAK_DOOR,
          Items.LEVER,
          Items.TORCH
        }) player.getInventory().add(new ItemStack(item, 64));
    player.sendSystemMessage(
        Component.literal(
            "NullSpace lab: valid pad at x=0; broken border at x=16; valid mixed grass/dirt pad at"
                + " x=32. Right-click a computer. Arrow keys select; Enter runs."));
    try {
      Files.writeString(
          marker,
          "Prepared development fixtures. Remove this marker only if you want to rebuild the test"
              + " area.");
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private PlaytestWorld() {}
}
