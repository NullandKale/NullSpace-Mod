package com.nullandkale.nullspace.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.terminal.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Minecraft adapter for a reusable character terminal. No controller-specific drawing. */
public final class TerminalScreen extends Screen {
  public static final int COLUMNS = TerminalBuffer.COLUMNS, ROWS = TerminalBuffer.ROWS;
  private static TerminalScreen returning;
  private static Object returnLevel;

  public static void clearReturn() {
    returning = null;
    returnLevel = null;
  }

  public static void rememberReturn(TerminalScreen screen) {
    returning = screen;
    returnLevel = net.minecraft.client.Minecraft.getInstance().level;
  }

  public static TerminalScreen restore(com.nullandkale.nullspace.navigation.ShipControl.View view) {
    var saved = returning;
    if (saved != null
        && returnLevel == net.minecraft.client.Minecraft.getInstance().level
        && saved.device instanceof ShipControlTerminal control
        && control.state().position().equals(view.position())) {
      clearReturn();
      control.reopened(view);
      return saved;
    }
    clearReturn();
    return new TerminalScreen(view);
  }

  public static TerminalScreen restore(ComputerTerminal.Open view) {
    var saved = returning;
    if (saved != null
        && returnLevel == net.minecraft.client.Minecraft.getInstance().level
        && saved.state != null
        && saved.state.position().equals(view.position())
        && saved.state.mode().equals(view.mode())) {
      clearReturn();
      saved.update(view);
      return saved;
    }
    clearReturn();
    return new TerminalScreen(view);
  }

  private ComputerTerminal.Open state;
  private TerminalDevice device;
  private int ticks;
  private Runnable deviceTick = () -> {}, deviceClose = () -> {};

  private static ControllerApplication application(ComputerTerminal.Open state) {
    return state.mode().equals("pad")
        ? new PadTerminal(state.message(), state.value(), state.destinations())
        : state.mode().equals("station_core")
            ? new StationCoreTerminal(state.message(), state.destinations())
            : state.mode().equals("recall")
                ? new ShipRecallTerminal(state.message(), state.destinations())
                : controller(state);
  }

  private static ControllerTerminal controller(ComputerTerminal.Open state) {
    var terminal = new ControllerTerminal(state.mode(), state.message(), state.value());
    terminal.catalog(state.destinations());
    return terminal;
  }

  public TerminalScreen(ComputerTerminal.Open state) {
    super(Component.literal("NullSpace Terminal"));
    this.state = state;
    device = application(state);
  }

  public TerminalScreen(com.nullandkale.nullspace.navigation.ShipControl.View view) {
    this(new ShipControlTerminal(view));
  }

  public boolean shipControl(com.nullandkale.nullspace.navigation.ShipControl.View view) {
    if (!(device instanceof ShipControlTerminal control)
        || !control.state().session().equals(view.session())) return false;
    control.update(view);
    return true;
  }

  public boolean shipController() {
    return device instanceof ShipControlTerminal;
  }

  /** A future computer supplies its own device without changing this renderer or input adapter. */
  public TerminalScreen(TerminalDevice device) {
    super(Component.literal("NullSpace Terminal"));
    this.device = device;
  }

  public TerminalScreen(TerminalDevice device, Runnable tick, Runnable close) {
    this(device);
    deviceTick = tick;
    deviceClose = close;
  }

  @Override
  protected void init() {
    minecraft.textInputManager().startTextInput(this);
  }

  @Override
  public void removed() {
    if (device instanceof ShipControlTerminal control && minecraft.getConnection() != null)
      net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
          new com.nullandkale.nullspace.navigation.ShipControl.Input(
              control.state().session(),
              com.nullandkale.nullspace.navigation.ShipControl.Action.CLOSE,
              ""));
    if (state != null
        && !state.session().equals(ComputerTerminal.NONE)
        && minecraft.getConnection() != null)
      net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
          new ComputerTerminal.Close(state.session()));
    deviceClose.run();
    minecraft.textInputManager().stopTextInput(this);
    super.removed();
  }

  public void acknowledged(java.util.UUID session) {
    if (sessionId().equals(session) && device instanceof ControllerApplication app)
      app.acknowledged();
  }

  public void refresh(ComputerTerminal.Open next) {
    if (state == null || !state.session().equals(next.session())) return;
    if (device instanceof ControllerApplication app) app.refresh(next.message());
    if (device instanceof ControllerTerminal controller) controller.catalog(next.destinations());
    if (device instanceof StationCoreTerminal core) core.catalog(next.destinations());
    if (device instanceof ShipRecallTerminal recall) recall.catalog(next.destinations());
    state = next;
  }

  public void rejected(ComputerTerminal.Rejected packet) {
    if (state != null
        && state.session().equals(packet.session())
        && device instanceof ControllerApplication p) p.rejected(packet.reason());
  }

  public void reading(ComputerTerminal.Update packet) {
    if (state == null
        || !(state.mode().equals("systems")
            || EquipmentMenus.supports(state.mode())
            || com.nullandkale.nullspace.station.StationMenus.supports(state.mode()))
        || !state.session().equals(packet.session())
        || packet.revision() < revision()) return;
    state =
        new ComputerTerminal.Open(
            state.position(),
            packet.message(),
            state.mode(),
            Integer.toString(packet.revision()),
            state.session(),
            state.destinations());
    if (device instanceof ControllerApplication controller) controller.refresh(packet.message());
  }

  public void update(ComputerTerminal.Open next) {
    if (state == null
        || !state.mode().equals(next.mode())
        || !state.position().equals(next.position())
        || !(device instanceof ControllerApplication)) device = application(next);
    else ((ControllerApplication) device).update(next.message());
    if (device instanceof ControllerTerminal controller) controller.catalog(next.destinations());
    if (device instanceof StationCoreTerminal core) core.catalog(next.destinations());
    if (device instanceof ShipRecallTerminal recall) recall.catalog(next.destinations());
    if (device instanceof PadTerminal pad) {
      pad.catalog(next.destinations());
      pad.savedName(next.value());
    }
    state = next;
  }

  public java.util.UUID sessionId() {
    return device instanceof ShipControlTerminal c
        ? c.state().session()
        : state == null ? ComputerTerminal.NONE : state.session();
  }

  public java.util.List<ComputerTerminal.Destination> destinations() {
    return state == null ? java.util.List.of() : state.destinations();
  }

  public int revision() {
    return state != null
            && (state.mode().equals("systems")
                || EquipmentMenus.supports(state.mode())
                || com.nullandkale.nullspace.station.StationMenus.supports(state.mode()))
        ? Integer.parseInt(state.value())
        : -1;
  }

  public int selection() {
    return device instanceof ShipControlTerminal c
        ? c.selection()
        : device instanceof ControllerApplication p ? p.selection() : -1;
  }

  public String status() {
    return device instanceof ShipControlTerminal c
        ? c.status()
        : device instanceof ControllerApplication p ? p.status() : "";
  }

  public String fieldValue() {
    return device instanceof ControllerApplication p ? p.fieldValue() : "";
  }

  public TerminalBuffer buffer() {
    return device.display();
  }

  @Override
  public void tick() {
    ticks++;
    deviceTick.run();
    if (device instanceof ControllerApplication p) p.tick();
    if (device instanceof ShipControlTerminal c) c.tick();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private float scale() {
    return Math.max(.01f, Math.min((width - 16) / 480f, (height - (shell() ? 40 : 16)) / 250f));
  }

  private boolean shell() {
    return device instanceof StreamTerminal;
  }

  private void input(TerminalInput event) {
    device.input(event);
    if (device.closeRequested()) {
      onClose();
      return;
    }
    if (device instanceof ShipControlTerminal control && minecraft.getConnection() != null) {
      com.nullandkale.nullspace.navigation.ShipControl.Input action;
      while ((action = control.pollAction()) != null)
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(action);
    }
    if (device instanceof ControllerApplication p && state != null) {
      ControllerTerminal.Action action;
      while ((action = p.pollAction()) != null) {
        if (minecraft.getConnection() == null) {
          p.update("Not connected to a server.");
          break;
        }
        String id = "";
        if (action.verb().equals("land"))
          for (var d : state.destinations())
            if (d.name().equalsIgnoreCase(action.argument().strip())) {
              id = d.id();
              break;
            }
        int revision = revision();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new ComputerTerminal.Request(
                state.session(),
                ComputerTerminal.Action.valueOf(action.verb().toUpperCase(java.util.Locale.ROOT)),
                action.argument(),
                id,
                revision));
      }
    }
  }

  private boolean hasParent() {
    return state != null
        && minecraft.level != null
        && (minecraft.level.getBlockEntity(state.position())
                instanceof com.nullandkale.nullspace.systems.MachineEntity
            || state.mode().equals("systems")
                && (com.nullandkale.nullspace.ship.ShipBlocks.navigation(
                        minecraft.level.getBlockState(state.position()))
                    || minecraft
                        .level
                        .getBlockState(state.position())
                        .is(com.nullandkale.nullspace.ship.ShipBlocks.STATION_CORE)));
  }

  private void backToParent() {
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
        new ComputerTerminal.Request(
            state.session(),
            minecraft.level.getBlockEntity(state.position())
                    instanceof com.nullandkale.nullspace.systems.MachineEntity
                ? ComputerTerminal.Action.MACHINE_BACK
                : ComputerTerminal.Action.CONTROLLER_BACK,
            "",
            "",
            revision()));
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
    if (shell() && event.y() >= height - 18 && event.x() >= width - 106) {
      onClose();
      return true;
    }
    float scale = scale();
    int col = (int) Math.floor((event.x() - (width - 480 * scale) / 2) / scale / 6);
    int row = (int) Math.floor((event.y() - (height - 250 * scale) / 2) / scale / 10);
    if (col < 0 || col >= COLUMNS || row < 0 || row >= ROWS) return false;
    if (hasParent() && row == 24 && col >= 66) {
      backToParent();
      return true;
    }
    input(new TerminalInput.Pointer(col, row));
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (device instanceof ShipControlTerminal c && event.key() == InputConstants.KEY_ESCAPE) {
      if (c.picker() && !c.state().scanner()) input(TerminalInput.Key.ESCAPE);
      else onClose();
      return true;
    }
    // Ctrl+Esc belongs to the Windows shell. Reserve a chord delivered to the game instead.
    if (event.key() == InputConstants.KEY_ESCAPE && (state != null || event.hasShiftDown())) {
      if (device instanceof StationCoreTerminal core && core.picking()) {
        input(TerminalInput.Key.ESCAPE);
        return true;
      }
      if (device instanceof ShipRecallTerminal) {
        input(TerminalInput.Key.ESCAPE);
        return true;
      }
      if (hasParent()) {
        backToParent();
        return true;
      }
      onClose();
      return true;
    }
    if (state == null) {
      if (event.key() == InputConstants.KEY_ESCAPE) {
        input(TerminalInput.Key.ESCAPE);
        return true;
      }
      if (event.hasControlDown()
          && event.key() >= InputConstants.KEY_A
          && event.key() <= InputConstants.KEY_Z
          && event.key() != InputConstants.KEY_V) {
        input(new TerminalInput.Control(event.key() - InputConstants.KEY_A + 1));
        return true;
      }
    }
    if (event.hasControlDown()) {
      if (event.key() == InputConstants.KEY_A) {
        input(TerminalInput.Key.SELECT_ALL);
        return true;
      }
      if (event.key() == InputConstants.KEY_V) {
        input(new TerminalInput.Text(minecraft.keyboardHandler.getClipboard()));
        return true;
      }
    }
    TerminalInput.Key key =
        switch (event.key()) {
          case InputConstants.KEY_UP -> TerminalInput.Key.UP;
          case InputConstants.KEY_DOWN -> TerminalInput.Key.DOWN;
          case InputConstants.KEY_LEFT -> TerminalInput.Key.LEFT;
          case InputConstants.KEY_RIGHT -> TerminalInput.Key.RIGHT;
          case InputConstants.KEY_HOME -> TerminalInput.Key.HOME;
          case InputConstants.KEY_END -> TerminalInput.Key.END;
          case InputConstants.KEY_BACKSPACE -> TerminalInput.Key.BACKSPACE;
          case InputConstants.KEY_DELETE -> TerminalInput.Key.DELETE;
          case InputConstants.KEY_RETURN -> TerminalInput.Key.ENTER;
          case InputConstants.KEY_TAB -> TerminalInput.Key.TAB;
          case InputConstants.KEY_PAGEUP -> TerminalInput.Key.PAGE_UP;
          case InputConstants.KEY_PAGEDOWN -> TerminalInput.Key.PAGE_DOWN;
          default -> null;
        };
    if (key != null) {
      input(key);
      return true;
    }
    return super.keyPressed(event);
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    input(new TerminalInput.Text(event.codepointAsString()));
    return true;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
    var buffer = device.display();
    if (hasParent()) buffer.put(66, 24, "[ Back ]", TerminalTheme.CYAN, TerminalTheme.PANEL);
    float scale = scale();
    graphics.fill(0, 0, width, height, buffer.cell(0, 0).background());
    graphics.pose().pushMatrix();
    graphics.pose().translate((width - 480 * scale) / 2, (height - 250 * scale) / 2);
    graphics.pose().scale(scale, scale);
    for (int row = 0; row < ROWS; row++)
      for (int col = 0; col < COLUMNS; col++) {
        var cell = buffer.cell(col, row);
        int x = col * 6, y = row * 10;
        graphics.fill(x, y, x + 6, y + 10, cell.background());
        if (cell.glyph() != ' ') {
          var glyph = Component.literal(String.valueOf(cell.glyph()));
          if (cell.bold()) glyph.withStyle(net.minecraft.ChatFormatting.BOLD);
          float glyphWidth = font.width(glyph), fit = Math.min(1, 6 / Math.max(1, glyphWidth));
          graphics.pose().pushMatrix();
          graphics.pose().translate(x + (6 - glyphWidth * fit) / 2, y);
          graphics.pose().scale(fit, 1);
          graphics.text(font, glyph, 0, 0, cell.foreground(), false);
          graphics.pose().popMatrix();
        }
      }
    if (buffer.cursorVisible() && ticks % 20 < 10) {
      int x = buffer.cursorColumn() * 6, y = buffer.cursorRow() * 10;
      graphics.fill(
          x,
          y + 9,
          x + 6,
          y + 10,
          buffer.cell(buffer.cursorColumn(), buffer.cursorRow()).foreground());
      minecraft
          .textInputManager()
          .setTextInputArea(
              (int) ((width - 480 * scale) / 2 + x * scale),
              (int) ((height - 250 * scale) / 2 + y * scale),
              (int) ((width - 480 * scale) / 2 + (x + 6) * scale),
              (int) ((height - 250 * scale) / 2 + (y + 10) * scale));
    }
    graphics.pose().popMatrix();
    if (shell()) {
      graphics.text(
          font, "Shift+Esc: close | Esc: program", 8, height - 14, TerminalTheme.MUTED, false);
      graphics.text(font, "[Close terminal]", width - 106, height - 14, TerminalTheme.CYAN, false);
    }
  }
}
