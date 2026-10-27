package com.nullandkale.nullspace.terminal;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Pad/ship application. Owns editing and actions, but knows nothing about Minecraft screens or
 * packets.
 */
public final class ControllerTerminal implements TerminalDevice {
  public record Action(String verb, String argument) {}

  private final boolean ship;
  private final boolean systems;
  private final String mode;
  private final List<EquipmentMenus.Entry> equipment;
  private final TerminalBuffer display = new TerminalBuffer();
  private final ArrayDeque<Action> actions = new ArrayDeque<>();
  private String status, name;
  private int selected, cursor, scroll, pendingTicks, quietTicks;
  private boolean overview = true;
  private boolean waiting, selectAll;
  private boolean dirty = true;
  private String readingSource;
  private List<Reading> cachedReadings;
  private int[] readingColors;

  public ControllerTerminal(boolean ship, String status, String name) {
    this(ship ? "ship" : "pad", status, name);
  }

  public ControllerTerminal(String mode, String status, String name) {
    this.mode = mode;
    this.ship = !mode.equals("pad");
    this.systems = mode.equals("systems");
    this.equipment = EquipmentMenus.supports(mode) ? EquipmentMenus.entries(mode) : List.of();
    this.status = status;
    this.name = systems || !equipment.isEmpty() ? "" : name;
    cursor = this.name.length();
  }

  public int selection() {
    return selected;
  }

  public int field() {
    return mode.equals("navigation") || mode.equals("shipyard")
        ? -1
        : !equipment.isEmpty() ? -1 : systems ? 8 : ship ? 2 : 1;
  }

  public String status() {
    return status;
  }

  public String fieldValue() {
    return name;
  }

  public Action pollAction() {
    return actions.pollFirst();
  }

  public void update(String status) {
    this.status = status;
    waiting = false;
    scroll = 0;
    quietTicks = 40;
    dirty = true;
  }

  public void refresh(String status) {
    if (!waiting && overview && quietTicks == 0) {
      dirty |= !this.status.equals(status);
      this.status = status;
      scroll = Math.min(scroll, maxScroll());
    }
  }

  public void tick() {
    if (quietTicks > 0) quietTicks--;
    if (waiting && --pendingTicks <= 0)
      update("No response received. Check your connection and try again.");
  }

  private int count() {
    return mode.equals("shipyard")
        ? com.nullandkale.nullspace.shipyard.ShipBlueprints.PRESETS.size() + 1
        : mode.equals("navigation")
            ? com.nullandkale.nullspace.navigation.LocalSpaces.DESTINATIONS.size() + 1
            : !equipment.isEmpty() ? equipment.size() : systems ? 10 : ship ? 10 : 5;
  }

  private int menuRow() {
    return 12;
  }

  private int statusRows() {
    return equipment.isEmpty() ? 5 : 6;
  }

  private record Reading(String text, int offset) {}

  private String statusText() {
    return status.replaceAll("[^ -~\\n]", "?");
  }

  private List<Reading> statusLines() {
    if (status.equals(readingSource)) return cachedReadings;
    var lines = new java.util.ArrayList<Reading>();
    int offset = 0;
    for (var part : statusText().split("\\n", -1)) {
      if (part.isEmpty()) lines.add(new Reading("", offset));
      for (int i = 0; i < part.length(); i += 76)
        lines.add(new Reading(part.substring(i, Math.min(i + 76, part.length())), offset + i));
      offset += part.length() + 1;
    }
    readingSource = status;
    readingColors = TerminalTheme.statusColors(statusText());
    cachedReadings = lines;
    return lines;
  }

  private int maxScroll() {
    return Math.max(0, statusLines().size() - statusRows());
  }

  @Override
  public void input(TerminalInput input) {
    dirty = true;
    if (input instanceof TerminalInput.Pointer point) {
      int row = point.row() - menuRow();
      if (point.column() < 0 || point.column() >= 80 || row < 0 || row >= count()) return;
      selected = row;
      selectAll = false;
      if (selected == field()) cursor = Math.clamp(point.column() - 22, 0, name.length());
      else execute();
    } else if (input instanceof TerminalInput.Text text) {
      if (field() < 0) return;
      // Typing is an explicit request to edit the name/destination, even if an action was selected.
      selected = field();
      for (char c : text.text().toCharArray())
        if (c >= 'A' && c <= 'Z'
            || c >= 'a' && c <= 'z'
            || c >= '0' && c <= '9'
            || c == ' '
            || c == '_'
            || c == '-') {
          if (selectAll) {
            name = "";
            cursor = 0;
            selectAll = false;
          }
          if (name.length() < 32) {
            name = name.substring(0, cursor) + c + name.substring(cursor);
            cursor++;
          }
        }
    } else if (input instanceof TerminalInput.Key key) {
      switch (key) {
        case UP -> {
          selected = Math.floorMod(selected - 1, count());
          selectAll = false;
        }
        case DOWN, TAB -> {
          selected = (selected + 1) % count();
          selectAll = false;
        }
        case ENTER -> {
          if (selected == field()) selected = field() + 1;
          else execute();
          selectAll = false;
        }
        case PAGE_UP -> scroll = Math.max(0, scroll - 4);
        case PAGE_DOWN -> scroll = Math.min(maxScroll(), scroll + 4);
        default -> {
          if (selected == field())
            switch (key) {
              case SELECT_ALL -> selectAll = true;
              case HOME -> {
                cursor = 0;
                selectAll = false;
              }
              case END -> {
                cursor = name.length();
                selectAll = false;
              }
              case LEFT -> {
                cursor = selectAll ? 0 : Math.max(0, cursor - 1);
                selectAll = false;
              }
              case RIGHT -> {
                cursor = selectAll ? name.length() : Math.min(name.length(), cursor + 1);
                selectAll = false;
              }
              case BACKSPACE, DELETE -> {
                if (selectAll) {
                  name = "";
                  cursor = 0;
                  selectAll = false;
                } else if (key == TerminalInput.Key.BACKSPACE && cursor > 0) {
                  name = name.substring(0, cursor - 1) + name.substring(cursor);
                  cursor--;
                } else if (key == TerminalInput.Key.DELETE && cursor < name.length())
                  name = name.substring(0, cursor) + name.substring(cursor + 1);
              }
              default -> {}
            }
        }
      }
    }
  }

  private void execute() {
    if (waiting) return;
    if (mode.equals("shipyard")) {
      var presets = com.nullandkale.nullspace.shipyard.ShipBlueprints.PRESETS;
      if (selected < presets.size()) name = presets.get(selected).id();
      actions.addLast(new Action(selected < presets.size() ? "ship_quote" : "ship_buy", name));
      waiting = true;
      pendingTicks = 100;
      return;
    }
    if (!equipment.isEmpty()) {
      actions.addLast(new Action(equipment.get(selected).verb(), ""));
      waiting = true;
      pendingTicks = 100;
      return;
    }
    if (mode.equals("navigation")) {
      var routes = com.nullandkale.nullspace.navigation.LocalSpaces.DESTINATIONS;
      actions.addLast(
          new Action(
              selected < routes.size() ? "fast_travel" : "helm",
              selected < routes.size() ? routes.get(selected).id() : ""));
      waiting = true;
      pendingTicks = 100;
      return;
    }
    if (systems) {
      String command = name.strip().toLowerCase(java.util.Locale.ROOT);
      overview =
          selected != 9
              || !(command.equals("help")
                  || command.startsWith("devices")
                  || command.equals("doors list")
                  || command.equals("groups"));
      actions.addLast(
          new Action(
              selected == 9
                  ? "configure"
                  : List.of(
                          "systems",
                          "upgrade",
                          "close_doors",
                          "open_doors",
                          "power_air",
                          "power_engines",
                          "power_weapons",
                          "priority")
                      .get(selected),
              selected == 9 ? name : ""));
      waiting = true;
      pendingTicks = 100;
      return;
    }
    if (selected == (ship ? 3 : 2) && name.isBlank()) {
      update(
          ship
              ? "Enter a destination pad name before landing."
              : "Enter a pad name before registering. Select Pad name and type.");
      selected = field();
      cursor = name.length();
      return;
    }
    String verb =
        ship
            ? switch (selected) {
              case 0 -> "inspect";
              case 1 -> "launch";
              case 3 -> "land";
              case 5 -> "systems";
              case 6 -> "fire";
              case 7 -> "pilot";
              case 8 -> "chase";
              case 9 -> "navigation";
              default -> "destinations";
            }
            : switch (selected) {
              case 0 -> "inspect";
              case 3 -> "preview_pad";
              case 4 -> "preview_ship";
              default -> "register";
            };
    actions.addLast(new Action(verb, verb.equals("register") || verb.equals("land") ? name : ""));
    waiting = true;
    pendingTicks = 100;
  }

  @Override
  public TerminalBuffer display() {
    if (!dirty) return display;
    dirty = false;
    display.reset();
    for (int row = 0; row < 25; row++) line(row, "", false);
    line(0, "+" + "-".repeat(78) + "+", false);
    line(
        1,
        "| NULLSPACE / "
            + (!equipment.isEmpty()
                ? mode.toUpperCase(java.util.Locale.ROOT).replace('_', ' ')
                : mode.equals("shipyard")
                    ? "SHIPYARD"
                    : systems ? "ENGINEERING" : ship ? "HELM" : "PAD ")
            + " TERMINAL",
        false);
    display.put(70, 1, "80 x 25  |", TerminalTheme.MUTED, TerminalTheme.PANEL);
    if (equipment.isEmpty() && !mode.equals("shipyard")) {
      display.put(2, 3, "KEY:", TerminalTheme.MUTED, TerminalTheme.BACKGROUND);
      display.put(8, 3, "OK", TerminalTheme.GREEN, TerminalTheme.BACKGROUND);
      display.put(13, 3, "CAUTION", TerminalTheme.AMBER, TerminalTheme.BACKGROUND);
      display.put(24, 3, "FAULT", TerminalTheme.RED, TerminalTheme.BACKGROUND);
      display.put(55, 3, "POWER", TerminalTheme.AMBER, TerminalTheme.BACKGROUND);
      display.put(63, 3, "AIR", TerminalTheme.GREEN, TerminalTheme.BACKGROUND);
      display.put(69, 3, "CAMERA", TerminalTheme.CYAN, TerminalTheme.BACKGROUND);
    }
    line(2, "+" + "-".repeat(78) + "+", false);
    line(4, "  STATUS", false);
    var readings = statusLines();
    var colors = readingColors;
    int first = equipment.isEmpty() ? 6 : 4;
    for (int i = 0; i < statusRows(); i++) {
      int row = first + i;
      display.put(0, row, " ".repeat(80), TerminalTheme.TEXT, TerminalTheme.PANEL);
      if (scroll + i >= readings.size()) continue;
      var reading = readings.get(scroll + i);
      for (int c = 0; c < reading.text.length(); c++)
        display.put(
            2 + c,
            row,
            reading.text.substring(c, c + 1),
            colors[reading.offset + c],
            TerminalTheme.PANEL);
    }
    if (mode.equals("shipyard")) {
      var presets = com.nullandkale.nullspace.shipyard.ShipBlueprints.PRESETS;
      for (int i = 0; i < presets.size(); i++) {
        var p = presets.get(i);
        menu(
            12 + i,
            i,
            p.name(),
            p.size()
                + " | "
                + p.decks()
                + " deck(s) | FREE"
                + (p.id().equals(name) ? " | SELECTED" : ""),
            "ship_quote");
      }
      menu(
          12 + presets.size(),
          presets.size(),
          "Call selected",
          com.nullandkale.nullspace.shipyard.ShipBlueprints.get(name).name() + " | FREE",
          "ship_buy");
      line(
          22,
          waiting
              ? "  Waiting for server..."
              : "  Select a design for details, then Call selected. Stand off the pad.",
          false);
      line(23, "  Arrows / Click Select   ENTER Run   PgUp/Dn Details   ESC Close", false);
      display.cursorVisible(false);
      return display;
    }
    if (!equipment.isEmpty()) {
      for (int i = 0; i < equipment.size(); i++) {
        var entry = equipment.get(i);
        menu(menuRow() + i, i, entry.label(), entry.description(), entry.verb());
      }
      if (waiting) line(22, "  Waiting for server...", false);
      line(23, "  Arrows / Click Select   ENTER Run   ESC Close", false);
      line(24, "+" + "-".repeat(78) + "+", false);
      display.cursorVisible(false);
      return display;
    }
    if (mode.equals("navigation")) {
      var routes = com.nullandkale.nullspace.navigation.LocalSpaces.DESTINATIONS;
      for (int i = 0; i < routes.size(); i++) {
        var r = routes.get(i);
        menu(
            12 + i,
            i,
            r.systemName() + " / " + r.name(),
            r.id().equals(name)
                ? "CURRENT LOCAL SPACE"
                : r.system()
                        .equals(com.nullandkale.nullspace.navigation.LocalSpaces.get(name).system())
                    ? "Orbital fast travel"
                    : "Interstellar fast travel",
            "fast_travel");
      }
      menu(12 + routes.size(), routes.size(), "return to helm", "", "helm");
      line(
          22,
          waiting
              ? "  Waiting for server..."
              : "  ENTER jump | Local flight stays within this region",
          false);
      line(23, "  Arrows Select   ENTER Run   ESC Exit", false);
      display.cursorVisible(false);
      return display;
    }
    List<String> labels =
        systems
            ? List.of(
                "refresh",
                "upgrade systems",
                "close all doors",
                "open all doors",
                "air power",
                "engine power",
                "weapon power",
                "priority",
                "command",
                "execute")
            : ship
                ? List.of(
                    "inspect ship",
                    "launch",
                    "destination",
                    "land",
                    "destinations",
                    "systems",
                    "fire weapon",
                    "pilot ship",
                    "ship camera",
                    "navigation")
                : List.of("inspect pad", "pad name", "register pad", "pad preview", "ship preview");
    List<String> descriptions =
        systems
            ? List.of(
                "Read power and rooms",
                "Enable finite air/power",
                "Queue powered closure",
                "Queue powered opening",
                "Cycle requested power",
                "Cycle requested power",
                "Cycle requested power",
                "Toggle air/controls first",
                "",
                "Run engineering command")
            : ship
                ? List.of(
                    "Validate ship and cabin",
                    "Depart for space",
                    "",
                    "Land at named pad",
                    "List registered pads",
                    "Power, air and door controls",
                    "Fire mounted cannon",
                    "Thrust and steer in local space",
                    "Orbit third-person view",
                    "Jump to a planet or solar system")
                : List.of(
                    "Scan surface and border",
                    "",
                    "Register or rename pad",
                    "Toggle cyan pad highlights",
                    "Toggle amber ship highlights");
    for (int i = 0; i < count(); i++)
      menu(
          12 + i,
          i,
          labels.get(i),
          i == field() ? "[" + name + "]" : descriptions.get(i),
          labels.get(i).replace(' ', '_'));
    line(
        systems || ship ? 22 : 19,
        waiting
            ? "  Waiting for server..."
            : systems
                ? "  power cameras 2 | doors list | help | Ctrl+A edit command"
                : "  READY - type to edit " + (ship ? "destination" : "pad name"),
        false);
    if (!systems && !ship) line(21, "  Ctrl+A Select all  Ctrl+V Paste  Home/End Cursor", false);
    line(23, "  Click / Arrows Select  ENTER Run  PgUp/Dn Scroll  ESC Exit", false);
    line(24, "+" + "-".repeat(78) + "+", false);
    if (selectAll && selected == field())
      display.put(22, 12 + field(), name, TerminalTheme.BACKGROUND, TerminalTheme.CYAN);
    display.cursor(22 + cursor, 12 + field());
    display.cursorVisible(selected == field());
    return display;
  }

  private void line(int row, String text, boolean selected) {
    int fg = selected ? TerminalTheme.TEXT : TerminalTheme.MUTED,
        bg = selected ? TerminalTheme.SELECTED : TerminalTheme.BACKGROUND;
    if (row == 0 || row == 2 || row == 24) fg = TerminalTheme.BORDER;
    if (row == 1) {
      fg = TerminalTheme.accent(mode);
      bg = TerminalTheme.PANEL;
    }
    if (text.contains("Waiting for server")) fg = TerminalTheme.AMBER;
    display.put(0, row, " ".repeat(80), fg, bg);
    display.put(0, row, text, fg, bg);
  }

  private void menu(int row, int index, String label, String description, String verb) {
    boolean active = selected == index;
    int bg = active ? TerminalTheme.SELECTED : TerminalTheme.BACKGROUND;
    line(row, "  " + (active ? "> " : "  ") + String.format("%-17s", label) + description, active);
    display.put(4, row, label, TerminalTheme.action(verb), bg);
    if (active) display.put(0, row, "| >", TerminalTheme.CYAN, bg);
    if (index == field())
      display.put(
          21,
          row,
          description,
          TerminalTheme.CYAN,
          active ? TerminalTheme.SELECTED : TerminalTheme.PANEL);
  }
}
