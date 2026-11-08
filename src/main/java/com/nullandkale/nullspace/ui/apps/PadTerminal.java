package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.ui.terminal.ControllerApplication;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import com.nullandkale.nullspace.ui.terminal.TerminalField;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;
import java.util.*;

/** One pad task, including first registration without a typing prerequisite. */
public final class PadTerminal implements ControllerApplication {
  private final TerminalBuffer buffer = new TerminalBuffer();
  private final ArrayDeque<ControllerTerminal.Action> actions = new ArrayDeque<>();
  private final TerminalField name;
  private String status, saved;
  private boolean valid, registered, waiting, close, nameEdited;
  private int selected, ticks;
  private String size = "Checking...";
  private final List<Runnable> commands = new ArrayList<>();
  private final List<int[]> hits = new ArrayList<>();

  public PadTerminal(String status, String name, List<TerminalPackets.Destination> data) {
    this.status = status;
    this.name = new TerminalField(name, 32);
    saved = name;
    catalog(data);
  }

  public void catalog(List<TerminalPackets.Destination> data) {
    data.stream()
        .filter(d -> d.id().equals("pad_status"))
        .findFirst()
        .ifPresent(
            d -> {
              size = d.name();
              valid = !d.state().equals("INVALID");
              registered = d.state().equals("REGISTERED");
            });
  }

  public int selection() {
    return selected;
  }

  public String status() {
    return status;
  }

  public String fieldValue() {
    return name.value();
  }

  public ControllerTerminal.Action pollAction() {
    return actions.poll();
  }

  public boolean closeRequested() {
    return close;
  }

  public void update(String status) {
    this.status = status;
    waiting = false;
  }

  public void savedName(String value) {
    if (!registered && !nameEdited && !name.value().equals(value)) {
      name.clear();
      name.input(new TerminalInput.Text(value));
    }
    saved = value;
  }

  public void tick() {
    if (waiting && ++ticks > 100) {
      waiting = false;
      status = "No reply yet. Check this pad before trying again.";
    }
  }

  private void send(String verb) {
    if (waiting) return;
    waiting = true;
    ticks = 0;
    actions.add(new ControllerTerminal.Action(verb, verb.equals("register") ? name.value() : ""));
  }

  private void text(int y, String s) {
    buffer.put(
        2,
        y,
        s.substring(0, Math.min(76, s.length())),
        TerminalTheme.TEXT,
        TerminalTheme.BACKGROUND);
  }

  private void button(int x, int y, String label, Runnable action, boolean enabled) {
    button(x, y, label, action, enabled, TerminalTheme.CYAN);
  }

  private void button(int x, int y, String label, Runnable action, boolean enabled, int color) {
    int index = commands.size();
    commands.add(enabled ? action : () -> {});
    hits.add(new int[] {x, y, label.length()});
    buffer.put(
        x,
        y,
        label,
        enabled ? color : TerminalTheme.MUTED,
        selected == index ? TerminalTheme.SELECTED : TerminalTheme.PANEL);
  }

  public TerminalBuffer display() {
    buffer.reset();
    buffer.cursorVisible(false);
    for (int y = 0; y < 25; y++)
      buffer.put(0, y, " ".repeat(80), TerminalTheme.TEXT, TerminalTheme.BACKGROUND);
    commands.clear();
    hits.clear();
    text(1, "PAD COMPUTER");
    text(3, "Pad " + size + (registered ? " - registered as " + saved : " - not registered yet"));
    // The server reports preview state as a status line; show it on the buttons instead.
    boolean padShown = status.contains("Previews: pad ON"), shipShown = status.contains("ship ON");
    text(5, "Name:");
    button(8, 5, " ".repeat(32), () -> {}, true);
    name.draw(buffer, 8, 5, 32, selected == 0);
    if (name.value().isBlank())
      button(
          43,
          5,
          registered ? "[ Keep current ]" : "[ Use suggested ]",
          () -> {
            name.clear();
            name.input(new TerminalInput.Text(saved));
            nameEdited = false;
          },
          !waiting);
    button(
        2,
        7,
        registered ? "[ Save name ]" : "[ Register pad ]",
        () -> send("register"),
        valid
            && !waiting
            && !name.value().isBlank()
            && (!registered || !saved.equals(name.value())));
    text(9, "Build a ship above this pad; the ground stays here.");
    button(
        2,
        11,
        padShown ? "[ Hide pad area ]" : "[ Show pad area ]",
        () -> send("preview_pad"),
        !waiting,
        padShown ? TerminalTheme.GREEN : TerminalTheme.CYAN);
    button(
        24,
        11,
        shipShown ? "[ Hide ship area ]" : "[ Show ship area ]",
        () -> send("preview_ship"),
        !waiting,
        shipShown ? TerminalTheme.GREEN : TerminalTheme.CYAN);
    button(2, 13, "[ Show problem ]", () -> send("pad_problem"), !waiting);
    button(24, 13, "[ Check pad ]", () -> send("inspect"), !waiting);
    var lines =
        status
            .replaceAll("[^ -~\\n]", "?")
            .lines()
            .filter(line -> !line.startsWith("Previews: pad "))
            .flatMap(
                line ->
                    java.util.stream.IntStream.range(0, Math.max(1, (line.length() + 75) / 76))
                        .mapToObj(
                            i -> line.substring(i * 76, Math.min(line.length(), (i + 1) * 76))))
            .toList();
    for (int i = 0; i < Math.min(6, lines.size()); i++) text(15 + i, lines.get(i));
    if (waiting) text(21, "Waiting for server...");
    button(2, 23, "[ My ships ]", () -> send("recall_list"), !waiting, TerminalTheme.GREEN);
    button(28, 23, "[ Advanced ]", () -> send("pad_advanced"), !waiting);
    button(47, 23, "[ Close ]", () -> close = true, true);
    return buffer;
  }

  public void input(TerminalInput input) {
    display();
    if (input instanceof TerminalInput.Pointer p) {
      for (int i = 0; i < hits.size(); i++) {
        var h = hits.get(i);
        if (p.row() == h[1] && p.column() >= h[0] && p.column() < h[0] + h[2]) {
          selected = i;
          if (i == 0) name.click(p.column() - 8);
          else commands.get(i).run();
          break;
        }
      }
    } else if (selected == 0
        && (input instanceof TerminalInput.Text
            || input instanceof TerminalInput.Key k
                && Set.of(
                        TerminalInput.Key.SELECT_ALL,
                        TerminalInput.Key.LEFT,
                        TerminalInput.Key.RIGHT,
                        TerminalInput.Key.HOME,
                        TerminalInput.Key.END,
                        TerminalInput.Key.DELETE,
                        TerminalInput.Key.BACKSPACE)
                    .contains(k))) {
      String before = name.value();
      name.input(input);
      if (input instanceof TerminalInput.Text || !before.equals(name.value())) nameEdited = true;
    } else if (input instanceof TerminalInput.Key k)
      switch (k) {
        case UP -> selected = Math.floorMod(selected - 1, commands.size());
        case DOWN, TAB -> selected = (selected + 1) % commands.size();
        case ENTER -> {
          if (selected == 0) selected = 1;
          else commands.get(selected).run();
        }
        default -> {}
      }
    ;
  }
}
