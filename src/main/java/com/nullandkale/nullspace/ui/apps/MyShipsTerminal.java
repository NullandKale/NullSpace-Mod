package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.ControllerApplication;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;
import java.util.*;

/** My ships: deploy, store and title actions on one page; selecting a row never moves anything. */
public final class MyShipsTerminal implements ControllerApplication {
  private final TerminalBuffer buffer = new TerminalBuffer();
  private final ArrayDeque<ControllerTerminal.Action> actions = new ArrayDeque<>();
  private List<ComputerTerminal.Destination> ships;
  private String status, selected = "";
  private int page, selection, scroll;
  private boolean active, waiting, close;
  private String padName = "this pad";

  public MyShipsTerminal(String status, List<ComputerTerminal.Destination> ships) {
    this.status = status;
    catalog(ships);
  }

  public void catalog(List<ComputerTerminal.Destination> next) {
    ships = next.stream().filter(d -> !d.id().equals("recall_pad")).toList();
    next.stream()
        .filter(d -> d.id().equals("recall_pad"))
        .findFirst()
        .ifPresent(
            d -> {
              padName = d.name();
              active = d.state().equals("ACTIVE");
            });
    if (ships.stream().noneMatch(s -> s.id().equals(selected))) selected = "";
    page = Math.clamp(page, 0, Math.max(0, (ships.size() - 1) / 9));
  }

  public int selection() {
    return selection;
  }

  public String status() {
    return status;
  }

  public String fieldValue() {
    return "";
  }

  public ControllerTerminal.Action pollAction() {
    return actions.pollFirst();
  }

  public void update(String next) {
    status = next;
    scroll = 0;
  }

  private void text(int y, String s, int color) {
    buffer.put(2, y, s.substring(0, Math.min(76, s.length())), color, TerminalTheme.BACKGROUND);
  }

  public void acknowledged() {
    waiting = false;
  }

  public void rejected(String reason) {
    waiting = false;
    update(reason);
  }

  public void refresh(String next) {
    if (!next.isBlank()) status = next;
  }

  private void send(String verb, String arg) {
    if (waiting) return;
    waiting = true;
    actions.add(new ControllerTerminal.Action(verb, arg));
  }

  private Optional<ComputerTerminal.Destination> chosen() {
    return ships.stream().filter(s -> s.id().equals(selected)).findFirst();
  }

  private static String state(String state) {
    return switch (state) {
      case "ON_PAD" -> "On this pad";
      case "STORED" -> "Stored";
      case "AVAILABLE" -> "In space";
      case "DOCKED" -> "Docked";
      case "ABANDONED" -> "Abandoned: owner away";
      case "CREW ABOARD" -> "Crew aboard";
      default -> state;
    };
  }

  private String blocker() {
    return chosen()
        .map(
            s ->
                !s.detail().isBlank()
                    ? s.detail()
                    : switch (s.state()) {
                      case "ON_PAD" -> "Store frees this pad; you keep the ship's title";
                      case "ABANDONED" ->
                          "Its owner has been away a day: Store moves it to their storage";
                      case "STORED" -> "Deploy brings it here; carry its title";
                      default -> "Deploy brings it to this pad, or Store it where it is";
                    })
        .orElse("Choose a ship from the list");
  }

  private boolean can(String... states) {
    return !waiting
        && !active
        && chosen().map(s -> Set.of(states).contains(s.state())).orElse(false);
  }

  private void activate(int index) {
    if (index < 9) {
      int at = page * 9 + index;
      if (at < ships.size()) selected = ships.get(at).id();
      return;
    }
    String key = selected.replace("-", "");
    switch (index) {
      case 9 -> {
        page = Math.max(0, page - 1);
        selected = "";
      }
      case 10 -> {
        page = Math.min(Math.max(0, (ships.size() - 1) / 9), page + 1);
        selected = "";
      }
      case 11 -> {
        if (can("AVAILABLE", "STORED")) send("recall_ship", key);
        else status = blocker();
      }
      case 12 -> {
        if (can("ON_PAD", "AVAILABLE", "ABANDONED")) send("ship_store", key);
        else status = blocker();
      }
      case 13 -> {
        if (!waiting && chosen().isPresent()) send("ship_title", key);
        else status = blocker();
      }
      case 14 -> send("recall_list", "");
      case 15 -> {
        if (active) send("recall_cancel", "");
      }
      case 16 -> actions.add(new ControllerTerminal.Action("recall_back", ""));
      case 17 -> {
        if (can("ON_PAD", "ABANDONED")) send("ship_show", key);
        else status = "Show blocks works for a ship standing on this pad";
      }
      case 18 -> {
        if (can("ON_PAD", "AVAILABLE", "STORED")) send("ship_scrap", key);
        else status = blocker();
      }
      default -> {}
    }
  }

  private void button(int x, int y, String label, boolean enabled, boolean main, int index) {
    buffer.put(
        x,
        y,
        label,
        !enabled ? TerminalTheme.MUTED : main ? TerminalTheme.GREEN : TerminalTheme.CYAN,
        selection == index ? TerminalTheme.SELECTED : TerminalTheme.PANEL);
  }

  public TerminalBuffer display() {
    buffer.reset();
    buffer.cursorVisible(false);
    for (int y = 0; y < 25; y++)
      buffer.put(0, y, " ".repeat(80), TerminalTheme.TEXT, TerminalTheme.BACKGROUND);
    text(1, "MY SHIPS", TerminalTheme.BLUE);
    text(3, "This pad: " + padName, TerminalTheme.TEXT);
    if (ships.isEmpty())
      text(6, "No ships yet. Ships you fly or receive are listed here.", TerminalTheme.MUTED);
    for (int row = 0; row < 9 && page * 9 + row < ships.size(); row++) {
      var ship = ships.get(page * 9 + row);
      boolean picked = ship.id().equals(selected);
      buffer.put(
          2,
          6 + row,
          String.format(
              Locale.ROOT, "%s%-40.40s %s", picked ? "> " : "  ", ship.name(), state(ship.state())),
          picked ? TerminalTheme.BLUE : TerminalTheme.TEXT,
          selection == row ? TerminalTheme.SELECTED : TerminalTheme.BACKGROUND);
    }
    text(15, waiting ? "Waiting for server..." : blocker(), TerminalTheme.AMBER);
    if (ships.size() > 9) {
      button(2, 16, "[ < ]", page > 0, false, 9);
      text(10, (page + 1) + " / " + ((ships.size() + 8) / 9), TerminalTheme.MUTED);
      button(20, 16, "[ > ]", (page + 1) * 9 < ships.size(), false, 10);
    }
    if (active) button(55, 16, "[ Cancel ]", true, false, 15);
    button(2, 18, "[ Deploy here ]", can("AVAILABLE", "STORED"), true, 11);
    button(20, 18, "[ Store ]", can("ON_PAD", "AVAILABLE", "ABANDONED"), false, 12);
    button(32, 18, "[ Get title ]", !waiting && chosen().isPresent(), false, 13);
    button(48, 18, "[ Refresh ]", true, false, 14);
    button(62, 18, "[ Show blocks ]", can("ON_PAD", "ABANDONED"), false, 17);
    var lines = new ArrayList<String>();
    for (var line : status.replaceAll("[^ -~\\n]", "?").split("\\n"))
      for (int i = 0; i < Math.max(1, line.length()); i += 76)
        lines.add(line.substring(i, Math.min(line.length(), i + 76)));
    scroll = Math.clamp(scroll, 0, Math.max(0, lines.size() - 3));
    for (int row = 0; row < 3 && scroll + row < lines.size(); row++)
      text(20 + row, lines.get(scroll + row), TerminalTheme.TEXT);
    button(2, 24, "[ Back to pad ]", true, false, 16);
    button(62, 24, "[ Scrap ship ]", can("ON_PAD", "AVAILABLE", "STORED"), false, 18);
    return buffer;
  }

  @Override
  public boolean closeRequested() {
    return close;
  }

  public void input(TerminalInput input) {
    if (input instanceof TerminalInput.Pointer p) {
      int r = p.row(), c = p.column();
      if (r >= 6 && r <= 14 && c >= 2 && c < 78) selection = r - 6;
      else if (r == 16 && c >= 2 && c < 7) selection = 9;
      else if (r == 16 && c >= 20 && c < 25) selection = 10;
      else if (r == 16 && active && c >= 55 && c < 65) selection = 15;
      else if (r == 18 && c >= 2 && c < 17) selection = 11;
      else if (r == 18 && c >= 20 && c < 29) selection = 12;
      else if (r == 18 && c >= 32 && c < 45) selection = 13;
      else if (r == 18 && c >= 48 && c < 59) selection = 14;
      else if (r == 24 && c >= 2 && c < 17) selection = 16;
      else if (r == 18 && c >= 62 && c < 77) selection = 17;
      else if (r == 24 && c >= 62 && c < 76) selection = 18;
      else return;
      activate(selection);
    } else if (input instanceof TerminalInput.Key k)
      switch (k) {
        case UP, LEFT -> selection = Math.floorMod(selection - 1, 19);
        case DOWN, RIGHT, TAB -> selection = (selection + 1) % 19;
        case ENTER -> activate(selection);
        // Escape always closes locally, so a lost server session can never trap the player.
        case ESCAPE -> close = true;
        case PAGE_UP -> scroll = Math.max(0, scroll - 3);
        case PAGE_DOWN -> scroll += 3;
        default -> {}
      }
  }
}
