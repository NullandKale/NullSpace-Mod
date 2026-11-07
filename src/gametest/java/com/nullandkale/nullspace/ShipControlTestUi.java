package com.nullandkale.nullspace;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.client.TerminalScreen;
import com.nullandkale.nullspace.navigation.ShipControl;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.*;

/** Interact with the real controls rather than bypassing them with action packets. */
final class ShipControlTestUi {
  static void click(TerminalScreen screen, int col, int row) {
    double scale = Math.min((screen.width - 16) / 480.0, (screen.height - 16) / 250.0);
    double x = (screen.width - 480 * scale) / 2 + (col * 6 + 3) * scale;
    double y = (screen.height - 250 * scale) / 2 + (row * 10 + 5) * scale;
    screen.mouseClicked(
        new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
        false);
  }

  /** Clicks the first on-screen control whose text contains {@code label}. */
  static boolean clickLabel(TerminalScreen screen, String label) {
    for (int row = 0; row < com.nullandkale.nullspace.terminal.TerminalBuffer.ROWS; row++) {
      int column = screen.buffer().row(row).indexOf(label);
      if (column >= 0) {
        click(screen, column + 1, row);
        return true;
      }
    }
    return false;
  }

  private static boolean visible(TerminalScreen screen, String label) {
    for (int row = 0; row < com.nullandkale.nullspace.terminal.TerminalBuffer.ROWS; row++)
      if (screen.buffer().row(row).contains(label)) return true;
    return false;
  }

  /** Uses a controller action the way a player does: open its tab, then press its button. */
  static void action(ClientGameTestContext c, ShipControl.Action action) {
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          if (!screen.shipController()) throw new AssertionError("Expected unified controller");
          if (!screen.buffer().row(1).contains("SHIP CONTROLLER"))
            throw new AssertionError("Close the destination picker before using travel controls");
          String tab =
              switch (action) {
                case LAUNCH, JUMP, LAND, DOCK, UNDOCK -> "[ Manual steps ]";
                case ENGAGE, DISENGAGE -> "[ Trip ]";
                case ADVANCED, INSPECT, SHOW_PROBLEM, REVIEW_LOOSE_BLOCKS, CONFIRM_LEAVE_BLOCKS ->
                    "[ More ]";
                default -> "";
              };
          String label =
              switch (action) {
                case ENGAGE -> "[ Engage ]";
                case DISENGAGE -> "[ Stop ]";
                case LAUNCH -> "[ Launch ]";
                case JUMP -> "[ Jump ]";
                case LAND -> "[ Land ]";
                case DOCK -> "[ Dock ]";
                case UNDOCK -> "[ Undock ]";
                case INVESTIGATE -> "[ Investigate signal ]";
                case RESUME -> "[ Resume journey ]";
                case PILOT -> "[ Fly manually ]";
                case CAMERA -> "[ View ship ]";
                case AIRLOCK, CLOSE_DOORS -> "[ Close all doors ]";
                case ADVANCED -> "[ Advanced ]";
                case INSPECT -> "[ Check ship ]";
                case SHOW_PROBLEM -> "[ Locate problem ]";
                case REVIEW_LOOSE_BLOCKS -> "[ Loose blocks ]";
                case CONFIRM_LEAVE_BLOCKS -> "[ Confirm leave blocks ]";
                default -> throw new AssertionError("Unhandled test action " + action);
              };
          if (!tab.isEmpty() && !visible(screen, label)) clickLabel(screen, tab);
          if (!clickLabel(screen, label))
            throw new AssertionError("Controller has no " + label + " control");
        });
  }

  static void destination(ClientGameTestContext c, String name, String kind) {
    // Older tests select remote contacts too. Browse through the Scanner feed, then explicitly
    // select the mark at the controller, just as a player does.
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          clickLabel(screen, "[ Change ]");
          search(c, screen, name, kind);
        });
    boolean browser =
        c.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).buffer().row(7).isBlank());
    if (browser) {
      throw new AssertionError("Use the physical Scanner for this destination: " + name);
    }
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          if (browser) search(c, screen, name, kind);
          if (screen.buffer().row(7).isBlank())
            throw new AssertionError("No destination matching " + name);
          click(screen, 3, 7);
          click(screen, 3, 22);
        });
    c.waitFor(
        mc ->
            mc.gui.screen() instanceof TerminalScreen t
                && t.buffer().row(1).contains("SHIP CONTROLLER")
                && t.buffer().row(5).contains(name),
        100);
  }

  static void destination(
      ClientGameTestContext c,
      net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
      String name,
      String kind) {
    String[] shipId = {""};
    world
        .getServer()
        .runOnServer(
            server -> {
              var p = world.getConnection().getServerPlayer();
              var ship = com.nullandkale.nullspace.scene.SceneService.observer(p);
              shipId[0] = ship.getStringOr("id", "");
              var scanner =
                  com.nullandkale.nullspace.systems.ShipSystems.connected(p.level(), ship).stream()
                      .filter(
                          pos ->
                              p.level()
                                  .getBlockState(pos)
                                  .is(com.nullandkale.nullspace.ship.ShipBlocks.LOCAL_SCANNER))
                      .findFirst()
                      .orElseThrow();
              p.teleportTo(scanner.getX() + .5, scanner.getY(), scanner.getZ() + 1.5);
              p.gameMode.useItemOn(
                  p,
                  p.level(),
                  net.minecraft.world.item.ItemStack.EMPTY,
                  net.minecraft.world.InteractionHand.MAIN_HAND,
                  new net.minecraft.world.phys.BlockHitResult(
                      net.minecraft.world.phys.Vec3.atCenterOf(scanner),
                      net.minecraft.core.Direction.SOUTH,
                      scanner,
                      false));
            });
    c.waitFor(
        mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SCANNER"),
        100);
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          search(c, screen, name, kind);
          if (screen.buffer().row(7).isBlank())
            throw new AssertionError("Scanner cannot find " + name);
          click(screen, 3, 7);
          click(screen, 3, 22);
        });
    c.waitFor(
        mc ->
            mc.gui.screen() instanceof TerminalScreen t && t.status().contains("Destination set:"),
        100);
    world
        .getServer()
        .runOnServer(
            server -> {
              var p = world.getConnection().getServerPlayer();
              var ship =
                  com.nullandkale.nullspace.ship.ShipStore.get(server).repository().find(shipId[0]);
              var at =
                  com.nullandkale.nullspace.ship.StationGameTest.boardingPoint(p.level(), ship);
              p.teleportTo(at.x, at.y, at.z);
              com.nullandkale.nullspace.ship.ShipService.open(
                  p,
                  com.nullandkale.nullspace.ship.ShipStore.pos(ship, "controller"),
                  "Scanner destination selected");
            });
    c.waitFor(
        mc ->
            mc.gui.screen() instanceof TerminalScreen t
                && t.buffer().row(1).contains("SHIP CONTROLLER")
                && t.buffer().row(5).contains(name),
        100);
  }

  /** Opens the Scanner, backs out to the star map (P9), screenshots it and closes. */
  static void starMap(
      ClientGameTestContext c,
      net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
    String[] shipId = {""};
    world
        .getServer()
        .runOnServer(
            server -> {
              var p = world.getConnection().getServerPlayer();
              var ship = com.nullandkale.nullspace.scene.SceneService.observer(p);
              shipId[0] = ship.getStringOr("id", "");
              var scanner =
                  com.nullandkale.nullspace.systems.ShipSystems.connected(p.level(), ship).stream()
                      .filter(
                          pos ->
                              p.level()
                                  .getBlockState(pos)
                                  .is(com.nullandkale.nullspace.ship.ShipBlocks.LOCAL_SCANNER))
                      .findFirst()
                      .orElseThrow();
              p.teleportTo(scanner.getX() + .5, scanner.getY(), scanner.getZ() + 1.5);
              p.gameMode.useItemOn(
                  p,
                  p.level(),
                  net.minecraft.world.item.ItemStack.EMPTY,
                  net.minecraft.world.InteractionHand.MAIN_HAND,
                  new net.minecraft.world.phys.BlockHitResult(
                      net.minecraft.world.phys.Vec3.atCenterOf(scanner),
                      net.minecraft.core.Direction.SOUTH,
                      scanner,
                      false));
            });
    c.waitFor(
        mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SCANNER"),
        100);
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          for (int i = 0; i < 3 && !screen.buffer().row(6).contains("STAR MAP"); i++)
            click(screen, 3, 2);
          if (!screen.buffer().row(6).contains("STAR MAP"))
            throw new AssertionError("The Scanner's top level is the star map");
          boolean you = false;
          for (int y = 7; y <= 18; y++) you |= screen.buffer().row(y).contains("@ ");
          if (!you) throw new AssertionError("The star map marks where you are");
        });
    c.waitTicks(4);
    c.takeScreenshot("nullspace-star-map");
    c.runOnClient(
        mc -> {
          var screen = (TerminalScreen) mc.gui.screen();
          // Select the first place that is not you, to show its distance and jump fuel.
          for (int y = 7; y <= 18; y++) {
            String row = screen.buffer().row(y);
            for (int x = 2; x < 78; x++) {
              char ch = row.charAt(x);
              if ("*~:o+#".indexOf(ch) >= 0 && x + 1 < 80 && row.charAt(x + 1) == ' ') {
                click(screen, x, y);
                return;
              }
            }
          }
        });
    c.waitTicks(4);
    c.takeScreenshot("nullspace-star-map-selected");
    c.runOnClient(mc -> click((TerminalScreen) mc.gui.screen(), 67, 24));
    c.waitFor(mc -> mc.gui.screen() == null, 100);
    world
        .getServer()
        .runOnServer(
            server -> {
              var p = world.getConnection().getServerPlayer();
              var ship =
                  com.nullandkale.nullspace.ship.ShipStore.get(server).repository().find(shipId[0]);
              var at =
                  com.nullandkale.nullspace.ship.StationGameTest.boardingPoint(p.level(), ship);
              p.teleportTo(at.x, at.y, at.z);
            });
  }

  private static void search(
      ClientGameTestContext c, TerminalScreen screen, String name, String kind) {
    click(screen, 11, 3);
    screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
    for (int i = 0; i < 64; i++)
      screen.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, 0, 0));
    net.minecraft.client.Minecraft.getInstance()
        .keyboardHandler
        .textInput(net.minecraft.client.Minecraft.getInstance().getWindow().handle(), name);
    if (kind.equals("Ports")) click(screen, 60, 19);
    else {
      String label =
          kind.equals("Pads")
              ? "Landing pads"
              : kind.equals("Regions")
                  ? "Planets & moons"
                  : kind.equals("Stations") ? "Stations" : kind.equals("Ships") ? "Ships" : "All";
      int column = screen.buffer().row(5).indexOf(label);
      if (column < 0) throw new AssertionError("Missing destination category " + label);
      click(screen, column + 1, 5);
    }
  }

  private ShipControlTestUi() {}
  /**
   * Interstellar courses need an enabled, powered Scanner. Lab ships predate that rule, so give
   * the observer ship one inside its cabin (beside a walkable floor) and power the sensor bus.
   */
  static void ensureScanner(
      net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
    world
        .getServer()
        .runOnServer(
            server -> {
              var p = world.getConnection().getServerPlayer();
              var level = p.level();
              var ship = com.nullandkale.nullspace.scene.SceneService.observer(p);
              var blocks = com.nullandkale.nullspace.systems.ShipSystems.connected(level, ship);
              boolean has =
                  blocks.stream()
                      .anyMatch(
                          b -> level.getBlockState(b).is(com.nullandkale.nullspace.ship.ShipBlocks.LOCAL_SCANNER));
              if (!has) {
                var spot =
                    blocks.stream()
                        .map(net.minecraft.core.BlockPos::above)
                        .filter(
                            at ->
                                level.getBlockState(at).isAir()
                                    && level.getBlockState(at.south()).isAir()
                                    && level.getBlockState(at.south().above()).isAir()
                                    && level.getBlockState(at.south().below()).isSolidRender()
                                    && com.nullandkale.nullspace.systems.ShipSystems.breathable(
                                        level, ship, at.south().above()))
                        .sorted(java.util.Comparator.comparingLong(net.minecraft.core.BlockPos::asLong))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No cabin floor for a test Scanner"));
                level.setBlock(
                    spot, com.nullandkale.nullspace.ship.ShipBlocks.LOCAL_SCANNER.defaultBlockState(), 3);
              }
              var systems = ship.getCompoundOrEmpty("systems");
              var req =
                  java.util.Arrays.copyOf(
                      systems.getIntArray("requests").orElse(new int[] {1, 2, 1, 3, 1, 0}), 6);
              req[com.nullandkale.nullspace.systems.PowerBus.System.CAMERAS.ordinal()] = 8;
              systems.putIntArray("requests", req);
              com.nullandkale.nullspace.systems.ShipSystems.tick(level, ship);
            });
  }
}
