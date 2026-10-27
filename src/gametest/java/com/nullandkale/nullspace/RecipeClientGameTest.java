package com.nullandkale.nullspace;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.client.TerminalScreen;
import com.nullandkale.nullspace.dev.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;

/**
 * Real chest screens, a paid native crafting transaction, and a crafted powered ship round trip.
 */
public final class RecipeClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = context.worldBuilder().create()) {
      var gallery = world.getServer().computeOnServer(RecipeLab::ensure);
      world
          .getServer()
          .runOnServer(
              server -> {
                var at = gallery.chest(1);
                var player = world.getConnection().getServerPlayer();
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.teleportTo(
                    server.overworld(),
                    at.getX() + .5,
                    at.getY(),
                    at.getZ() - 2.5,
                    Set.of(),
                    0,
                    0,
                    true);
              });
      world.getConnection().waitForChunksRender();
      context.waitTicks(10);
      context.getInput().lookAt(gallery.chest(1));
      context.waitTicks(2);
      context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(c -> c.gui.screen() instanceof ContainerScreen, 200);
      context.runOnClient(
          c -> {
            var screen = (ContainerScreen) c.gui.screen();
            var menu = screen.getMenu();
            if (!menu.getSlot(RecipeLab.OUTPUT_SLOT).getItem().is(ShipBlocks.CIRCUIT_BOARD)
                || menu.getSlot(RecipeLab.OUTPUT_SLOT).getItem().getCount() != 4)
              throw new AssertionError("Recipe chest must show the real four-board output");
            if (!menu.getSlot(1).getItem().is(Items.COPPER_INGOT)
                || !menu.getSlot(2).getItem().is(Items.REDSTONE_TORCH)
                || !menu.getSlot(11).getItem().is(Items.OAK_PLANKS))
              throw new AssertionError("Chest screen must show the 3x3 board pattern");
          });
      context.takeScreenshot("nullspace-recipe-chest");
      context.runOnClient(c -> c.player.closeContainer());
      context.waitFor(c -> c.gui.screen() == null, 200);
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                player.getInventory().clearContent();
                player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 4));
                player.getInventory().add(new ItemStack(Items.REDSTONE_TORCH, 2));
                player.getInventory().add(new ItemStack(Items.REDSTONE, 2));
                player.getInventory().add(new ItemStack(Items.OAK_PLANKS));
                player.inventoryMenu.broadcastChanges();
              });
      context.waitTicks(5);
      context.getInput().lookAt(gallery.chest(1).offset(2, 0, 0));
      context.waitTicks(2);
      context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(c -> c.gui.screen() instanceof CraftingScreen, 200);
      // Pick up each hotbar stack, place one per corresponding cell, then return any remainder.
      context.runOnClient(
          c -> {
            int[] sources = {37, 38, 39, 40};
            int[][] destinations = {{1, 3, 7, 9}, {2, 8}, {4, 6}, {5}};
            for (int i = 0; i < sources.length; i++) {
              c.gameMode.handleContainerInput(
                  c.player.containerMenu.containerId,
                  sources[i],
                  0,
                  ContainerInput.PICKUP,
                  c.player);
              for (int slot : destinations[i])
                c.gameMode.handleContainerInput(
                    c.player.containerMenu.containerId, slot, 1, ContainerInput.PICKUP, c.player);
              if (!c.player.containerMenu.getCarried().isEmpty())
                c.gameMode.handleContainerInput(
                    c.player.containerMenu.containerId,
                    sources[i],
                    0,
                    ContainerInput.PICKUP,
                    c.player);
            }
          });
      context.waitFor(
          c -> c.player.containerMenu.getSlot(0).getItem().is(ShipBlocks.CIRCUIT_BOARD), 200);
      context.takeScreenshot("nullspace-shaped-crafting");
      context.runOnClient(
          c ->
              c.gameMode.handleContainerInput(
                  c.player.containerMenu.containerId, 0, 0, ContainerInput.QUICK_MOVE, c.player));
      context.waitTicks(5);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                if (p.getInventory().countItem(ShipBlocks.CIRCUIT_BOARD) != 4
                    || p.containerMenu.getSlot(0).hasItem())
                  throw new AssertionError(
                      "Real survival click must craft exactly four boards and consume ingredients");
                p.closeContainer();
              });
      context.waitFor(c -> c.gui.screen() == null, 200);
      var base =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    var p = world.getConnection().getServerPlayer();
                    var at = gallery.chest(1).offset(2, 0, 0);
                    SurvivalCrafting.starter(p, at);
                    var home = ShipLab.ensure(server);
                    var level = server.overworld();
                    Block[] hardware = {
                      ShipBlocks.GENERATOR,
                      ShipBlocks.BATTERY,
                      ShipBlocks.AIR_SUPPLY,
                      ShipBlocks.DOOR_CONTROL
                    };
                    SurvivalCrafting.take(
                        p, com.nullandkale.nullspace.pad.PadComputers.BLOCK.asItem());
                    SurvivalCrafting.take(p, ShipBlocks.HELM.asItem());
                    level.setBlock(
                        home.offset(6, 3, 3),
                        ShipBlocks.HELM
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST),
                        18);
                    SurvivalCrafting.take(p, ShipBlocks.ENGINE.asItem());
                    level.setBlock(
                        home.offset(0, 3, 3),
                        ShipBlocks.ENGINE
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST),
                        18);
                    for (int i = 0; i < hardware.length; i++) {
                      SurvivalCrafting.take(p, hardware[i].asItem());
                      level.setBlock(
                          home.offset(1 + i, 3, 6),
                          hardware[i]
                              .defaultBlockState()
                              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                          18);
                    }
                    p.setItemSlot(EquipmentSlot.HEAD, SurvivalCrafting.take(p, ShipBlocks.HELMET));
                    p.setItemSlot(EquipmentSlot.CHEST, SurvivalCrafting.take(p, ShipBlocks.CHEST));
                    p.setItemSlot(EquipmentSlot.LEGS, SurvivalCrafting.take(p, ShipBlocks.LEGS));
                    p.setItemSlot(EquipmentSlot.FEET, SurvivalCrafting.take(p, ShipBlocks.BOOTS));
                    p.teleportTo(
                        level,
                        home.getX() + 4.5,
                        home.getY() + 3,
                        home.getZ() + 3.5,
                        Set.of(),
                        90,
                        0,
                        true);
                    ShipSystems.action(p, home.offset(6, 3, 3), ComputerTerminal.Action.UPGRADE);
                    return home;
                  });
      context.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.revision() >= 0, 200);
      context.waitTicks(40);
      world
          .getServer()
          .runOnServer(
              server -> {
                var ship = ShipTopology.resolve(server.overworld(), base.offset(6, 3, 3));
                if (ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
                    .map(t -> (net.minecraft.nbt.CompoundTag) t)
                    .filter(t -> t.getStringOr("type", "").equals("nullspace:battery"))
                    .noneMatch(t -> t.getIntOr("energy", 0) > 0))
                  throw new AssertionError("Crafted empty battery must charge before launch");
                ShipService.open(
                    world.getConnection().getServerPlayer(),
                    base.offset(6, 3, 3),
                    "Crafted ship ready");
              });
      context.waitFor(
          c ->
              c.gui.screen() instanceof TerminalScreen t && t.status().equals("Crafted ship ready"),
          200);
      context.waitTicks(20);
      send(context, ComputerTerminal.Action.LAUNCH);
      context.waitFor(
          c ->
              c.level != null
                  && c.level.dimension().equals(TravelService.SPACE)
                  && c.gui.screen() == null,
          6000);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship = ShipStore.get(server).at(p.level(), p.blockPosition());
                ShipService.open(p, ShipStore.pos(ship, "controller"), "Crafted ship return");
              });
      context.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 200);
      context.waitTicks(20);
      send(context, ComputerTerminal.Action.LAND);
      context.waitFor(
          c ->
              c.level != null
                  && c.level.dimension().equals(Level.OVERWORLD)
                  && c.gui.screen() == null,
          6000);
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
    ClientTestEvidence.passed(getClass());
  }

  private static void send(ClientGameTestContext context, ComputerTerminal.Action action) {
    context.runOnClient(
        c -> {
          var screen = (TerminalScreen) c.gui.screen();
          String argument = "", id = "";
          if (action == ComputerTerminal.Action.LAND) {
            argument = screen.fieldValue();
            final String name = argument;
            id =
                screen.destinations().stream()
                    .filter(d -> d.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow()
                    .id();
          }
          ClientPlayNetworking.send(
              new ComputerTerminal.Request(screen.sessionId(), action, argument, id));
        });
  }
}
