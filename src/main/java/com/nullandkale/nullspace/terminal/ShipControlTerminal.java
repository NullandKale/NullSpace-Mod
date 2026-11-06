package com.nullandkale.nullspace.terminal;

import com.nullandkale.nullspace.navigation.*;
import java.util.*;

/** One course action and a destination browser organized by system and planet. */
public final class ShipControlTerminal implements TerminalDevice {
  // Shared terminal palette: shaded backgrounds only on things that can be clicked.
  private static final int CANVAS = TerminalTheme.BACKGROUND,
      SURFACE = TerminalTheme.PANEL,
      FOCUS = TerminalTheme.SELECTED;

  private record Hit(
      int x, int y, int width, String text, Runnable run, boolean enabled, String reason) {}

  private final TerminalBuffer display = new TerminalBuffer();
  private final ArrayDeque<ShipControl.Input> actions = new ArrayDeque<>();
  private final List<Hit> hits = new ArrayList<>();
  private ShipControl.View view;
  private boolean picker;
  private final TerminalField search = new TerminalField("", 64);
  private String highlighted = "", filter = "All";
  private boolean searchFocused = true, pending, close, details;
  private int pendingTicks;
  private String pendingTarget = "";
  private int pendingResponse;
  private int selection, page, statusScroll;
  private String notice = "";
  private int tab; // 0 Trip, 1 Manual steps, 2 More
  private String scopeSystem = "", scopeRegion = "";

  public ShipControlTerminal(ShipControl.View view) {
    this.view = view;
    picker = view.scanner();
    highlighted = view.selected();
    if (!view.here().isBlank()) {
      scopeSystem = view.here().split(" / ")[0];
      String body = view.here().split(" / ").length > 1 ? view.here().split(" / ")[1] : "";
      scopeRegion =
          view.entries().stream()
              .filter(
                  e ->
                      e.kind() == ShipDestination.Kind.REGION
                          && e.name().equals(scopeSystem + " / " + body))
              .map(ShipControl.Entry::region)
              .findFirst()
              .orElse("");
    }
  }

  public void update(ShipControl.View next) {
    boolean wasBrowser = browser();
    if (!view.message().equals(next.message()) || !view.buttons().equals(next.buttons())) {
      statusScroll = 0;
      if (!pending || next.response() > pendingResponse) notice = "";
    }
    if (pending && next.response() > pendingResponse) {
      pending = false;
      if (!pendingTarget.isBlank() && next.selected().equals(pendingTarget) && !view.scanner()) {
        picker = false;
        selection = 0;
      }
      pendingTarget = "";
    }
    view = next;
    if (!view.scanner() && wasBrowser != browser()) {
      picker = browser();
      selection = 0;
    }
    clearHiddenSelection();
  }

  public ShipControl.View state() {
    return view;
  }

  public int selection() {
    return selection;
  }

  public String status() {
    return notice.isEmpty() ? (details ? view.details() : view.message()) : notice;
  }

  public boolean picker() {
    return picker;
  }

  private boolean browser() {
    return view.scanner();
  }

  public ShipControl.Input pollAction() {
    return actions.pollFirst();
  }

  private void send(ShipControl.Action action, String target) {
    if (pending) return;
    notice = "Waiting for server...";
    pending = true;
    pendingTicks = 0;
    pendingResponse = view.response();
    actions.add(new ShipControl.Input(view.session(), action, target));
  }

  private void command(ShipControl.Action action) {
    if (action == ShipControl.Action.REVIEW_LOOSE_BLOCKS
        || action == ShipControl.Action.CONFIRM_LEAVE_BLOCKS) {
      details = false;
      statusScroll = 0;
    }
    var button = view.buttons().stream().filter(b -> b.action() == action).findFirst();
    if (button.isPresent() && button.get().enabled()) send(action, "");
    else {
      send(ShipControl.Action.EXPLAIN, action.name());
      notice =
          label(action)
              + " blocked: "
              + button.map(ShipControl.Button::reason).orElse("Unavailable");
      statusScroll = 0;
    }
  }

  private void line(int y, String text, int color) {
    display.put(2, y, clean(text).substring(0, Math.min(76, clean(text).length())), color, CANVAS);
  }

  private static String clean(String text) {
    return text.replaceAll("[^ -~]", "?");
  }

  private void hit(int x, int y, int width, String text, Runnable run, boolean enabled) {
    hit(x, y, width, text, run, enabled, "");
  }

  private void hit(
      int x, int y, int width, String text, Runnable run, boolean enabled, String reason) {
    int color =
        text.contains("[ Engage ]") || text.contains("[ Set destination ]")
            ? TerminalTheme.GREEN
            : TerminalTheme.TEXT;
    hit(x, y, width, text, run, enabled, reason, false, color);
  }

  /** A clickable area. Plain hits (list rows, categories) get no bar unless focused. */
  private void hit(
      int x,
      int y,
      int width,
      String text,
      Runnable run,
      boolean enabled,
      String reason,
      boolean plain,
      int color) {
    int index = hits.size();
    hits.add(new Hit(x, y, width, text, run, enabled, reason));
    String label = clean(text);
    label = label.substring(0, Math.min(width, label.length()));
    display.put(
        x,
        y,
        label + " ".repeat(width - label.length()),
        enabled ? color : TerminalTheme.MUTED,
        selection == index ? FOCUS : plain ? CANVAS : SURFACE);
  }

  private void button(int x, int y, int width, String text, ShipControl.Action action) {
    String reason =
        view.buttons().stream()
            .filter(b -> b.action() == action)
            .map(ShipControl.Button::reason)
            .findFirst()
            .orElse("Unavailable");
    hit(x, y, width, "[ " + text + " ]", () -> command(action), reason.isEmpty(), reason);
  }

  private List<ShipControl.Entry> filtered() {
    String query = search.value().toLowerCase(Locale.ROOT);
    boolean grouped =
        view.entries().stream().anyMatch(e -> e.kind() == ShipDestination.Kind.VESSEL);
    return view.entries().stream()
        .filter(
            e ->
                filter.equals("Docking ports") || !grouped || e.kind() != ShipDestination.Kind.PORT)
        .filter(
            e ->
                !query.isBlank()
                    || scopeRegion.isBlank()
                    || filter.equals("Planets & moons")
                        && (scopeSystem.isBlank() || system(e).equals(scopeSystem))
                    || e.region().equals(scopeRegion))
        .filter(
            e ->
                switch (filter) {
                  case "Planets & moons" -> e.kind() == ShipDestination.Kind.REGION;
                  case "Landing pads" -> e.kind() == ShipDestination.Kind.PAD;
                  case "Docking ports" -> e.kind() == ShipDestination.Kind.PORT;
                  case "Stations" ->
                      e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Station");
                  case "Ships" ->
                      e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Ship");
                  default -> true;
                })
        .filter(
            e ->
                (e.name() + " " + e.region() + " " + e.state())
                    .toLowerCase(Locale.ROOT)
                    .contains(query))
        .toList();
  }

  private void choose() {
    if (highlighted.isEmpty() || filtered().stream().noneMatch(e -> e.id().equals(highlighted))) {
      notice = "Choose a destination from the list";
      return;
    }
    pendingTarget = browser() ? "" : highlighted;
    send(ShipControl.Action.SELECT, highlighted);
  }

  private String system(ShipControl.Entry entry) {
    return view.entries().stream()
        .filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(entry.region()))
        .map(e -> e.name().split(" / ")[0])
        .findFirst()
        .orElse("Local space");
  }

  private boolean drawScopes() {
    if (!search.value().isBlank() || view.here().isBlank()) return false;
    hit(
        2,
        2,
        76,
        "[ Back ] Stars"
            + (scopeSystem.isBlank() ? "" : " / " + scopeSystem)
            + (scopeRegion.isBlank()
                ? ""
                : " / " + region(scopeRegion).replace(scopeSystem + " / ", "")),
        () -> {
          if (!scopeRegion.isBlank()) scopeRegion = "";
          else scopeSystem = "";
          highlighted = "";
          page = 0;
        },
        true,
        "",
        true,
        TerminalTheme.BLUE);
    if (!scopeRegion.isBlank()) return false;
    var bodies =
        view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION).toList();
    if (scopeSystem.isBlank()) {
      var systems = bodies.stream().map(this::system).distinct().sorted().toList();
      page = Math.clamp(page, 0, Math.max(0, (systems.size() - 1) / 12));
      for (int i = 0; i < 12 && page * 12 + i < systems.size(); i++) {
        String name = systems.get(page * 12 + i);
        hit(
            2,
            7 + i,
            76,
            name
                + " | "
                + bodies.stream().filter(e -> system(e).equals(name)).count()
                + " planets/moons >",
            () -> {
              scopeSystem = name;
              page = 0;
              highlighted = "";
            },
            true,
            "",
            true,
            TerminalTheme.TEXT);
      }
    } else {
      var local = bodies.stream().filter(e -> system(e).equals(scopeSystem)).toList();
      page = Math.clamp(page, 0, Math.max(0, (local.size() - 1) / 12));
      for (int i = 0; i < 12 && page * 12 + i < local.size(); i++) {
        var body = local.get(page * 12 + i);
        long contacts =
            view.entries().stream()
                .filter(
                    e ->
                        e.region().equals(body.region())
                            && (e.kind() == ShipDestination.Kind.VESSEL
                                || e.kind() == ShipDestination.Kind.PAD))
                .count();
        hit(
            2,
            7 + i,
            76,
            body.name() + " | " + contacts + " stations / ships / landing sites >",
            () -> {
              scopeRegion = body.region();
              page = 0;
              highlighted = "";
            },
            true,
            "",
            true,
            TerminalTheme.TEXT);
      }
    }
    return true;
  }

  private int visibleCount() {
    if (!search.value().isBlank() || view.here().isBlank() || !scopeRegion.isBlank())
      return filtered().size();
    return (int)
        view.entries().stream()
            .filter(e -> e.kind() == ShipDestination.Kind.REGION)
            .filter(e -> scopeSystem.isBlank() || system(e).equals(scopeSystem))
            .map(e -> scopeSystem.isBlank() ? system(e) : e.region())
            .distinct()
            .count();
  }

  @Override
  public TerminalBuffer display() {
    display.reset();
    display.cursorVisible(false);
    hits.clear();
    for (int y = 0; y < 25; y++) display.put(0, y, " ".repeat(80), TerminalTheme.TEXT, CANVAS);
    if (picker) drawPicker();
    else drawController();
    var messages = wrapped(status());
    statusScroll = Math.clamp(statusScroll, 0, Math.max(0, messages.size() - 3));
    int first = picker ? 0 : statusScroll;
    if (!picker && messages.size() > 3) line(19, "More: PgUp / PgDn", TerminalTheme.MUTED);
    for (int row = 0; row < (picker ? 1 : 3) && first + row < messages.size(); row++)
      line((picker ? 23 : 20) + row, messages.get(first + row), TerminalTheme.TEXT);
    hit(66, 24, 12, "[ Close ]", () -> close = true, true);
    selection = Math.clamp(selection, 0, Math.max(0, hits.size() - 1));
    return display;
  }

  /** Writes plain, non-interactive text at a column; no background bar. */
  private void text(int x, int y, String text, int color) {
    String value = clean(text);
    display.put(x, y, value.substring(0, Math.min(78 - x, value.length())), color, CANVAS);
  }

  /** "Fuel: loaded | Controls: online | ..." as separate labeled items colored by state. */
  private void statusItems(int y, String summary) {
    if (!summary.contains(": ")) {
      text(2, y, summary, TerminalTheme.AMBER);
      return;
    }
    int x = 2;
    for (String part : summary.split(" \\| ")) {
      int split = part.indexOf(": ");
      if (split < 0) continue;
      String label = part.substring(0, split), value = part.substring(split + 2);
      // Compact so all four items fit: "Air here: safe here" -> "Air safe",
      // "Controls: no controls power" -> "Controls no power".
      if (label.endsWith(" here")) {
        label = label.substring(0, label.length() - 5);
        value = value.replace(" here", "");
      }
      value = value.replace("no " + label.toLowerCase(Locale.ROOT) + " ", "no ");
      boolean good = Set.of("loaded", "online", "ready", "safe").contains(value);
      if (x + label.length() + value.length() + 2 > 78) break;
      text(x, y, label, TerminalTheme.MUTED);
      x += label.length() + 1;
      text(x, y, value, good ? TerminalTheme.GREEN : TerminalTheme.RED);
      x += value.length() + 3;
    }
  }

  private void drawController() {
    text(2, 1, "SHIP CONTROLLER", TerminalTheme.BLUE);
    text(19, 1, view.ship(), TerminalTheme.MUTED);
    text(2, 2, "Here", TerminalTheme.MUTED);
    text(12, 2, view.here(), TerminalTheme.TEXT);
    statusItems(3, view.summary());
    text(2, 5, "Going to", TerminalTheme.MUTED);
    String destination = clean(view.destination());
    text(12, 5, destination.substring(0, Math.min(52, destination.length())), TerminalTheme.AMBER);
    hit(
        66,
        5,
        12,
        "[ Change ]",
        () -> {
          picker = true;
          searchFocused = true;
          selection = 0;
          highlighted = view.selected();
        },
        true);
    tab(2, 7, 8, "Trip", 0);
    tab(12, 7, 16, "Manual steps", 1);
    tab(30, 7, 8, "More", 2);
    text(17, 17, view.connection(), TerminalTheme.MUTED);
    if (tab == 0) drawTrip();
    else if (tab == 1) drawManual();
    else drawMore();
    button(2, 14, 20, "Fly manually", ShipControl.Action.PILOT);
    if (view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.INVESTIGATE))
      button(2, 13, 30, "Investigate signal", ShipControl.Action.INVESTIGATE);
    if (view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.RESUME))
      button(2, 13, 30, "Resume journey", ShipControl.Action.RESUME);
    button(24, 14, 22, "View ship", ShipControl.Action.CAMERA);
    button(48, 14, 28, "Close all doors", ShipControl.Action.CLOSE_DOORS);
    for (var a :
        List.of(ShipControl.Action.ACCEPT, ShipControl.Action.DECLINE, ShipControl.Action.CANCEL))
      if (view.buttons().stream().anyMatch(b -> b.action() == a && b.enabled()))
        button(
            a == ShipControl.Action.ACCEPT ? 27 : 52,
            18,
            23,
            a == ShipControl.Action.ACCEPT ? "Allow docking" : label(a),
            a);
  }

  private void tab(int x, int y, int width, String name, int index) {
    hit(
        x,
        y,
        width,
        "[ " + name + " ]",
        () -> {
          tab = index;
          statusScroll = 0;
        },
        true,
        "",
        false,
        tab == index ? TerminalTheme.BLUE : TerminalTheme.MUTED);
  }

  private void drawTrip() {
    String trip =
        view.details()
            .lines()
            .filter(l -> l.startsWith("Trip:") || l.startsWith("Already at"))
            .findFirst()
            .orElse("Choose a course, then Engage")
            .replaceFirst("^Trip: ", "");
    var fuel =
        java.util.regex.Pattern.compile("Minimum fuel: (\\d+)")
            .matcher(view.details());
    if (fuel.find() && !fuel.group(1).equals("0")) trip += " - needs " + fuel.group(1) + " fuel";
    text(2, 8, trip, TerminalTheme.TEXT);
    button(2, 10, 20, "Engage", ShipControl.Action.ENGAGE);
    String blocker =
        view.buttons().stream()
            .filter(b -> b.action() == ShipControl.Action.ENGAGE)
            .map(ShipControl.Button::reason)
            .findFirst()
            .orElse("Choose a destination");
    if (!blocker.isEmpty()) text(24, 10, blocker, TerminalTheme.AMBER);
    button(2, 11, 20, "Stop", ShipControl.Action.DISENGAGE);
  }

  private void drawManual() {
    var travel =
        new ShipControl.Action[] {
          ShipControl.Action.LAUNCH,
          ShipControl.Action.JUMP,
          ShipControl.Action.LAND,
          ShipControl.Action.DOCK,
          ShipControl.Action.UNDOCK
        };
    String[] purposes = {
      "Leave this planet",
      "Go to another region",
      "Land at destination",
      "Connect to dock",
      "Leave connected dock"
    };
    for (int i = 0; i < travel.length; i++) {
      var action = travel[i];
      button(2, 8 + i, 13, label(action), action);
      String reason =
          view.buttons().stream()
              .filter(b -> b.action() == action)
              .map(ShipControl.Button::reason)
              .findFirst()
              .orElse("Unavailable");
      text(
          17,
          8 + i,
          reason.isEmpty() ? purposes[i] : reason,
          reason.isEmpty() ? TerminalTheme.GREEN : TerminalTheme.MUTED);
    }
  }

  private void drawMore() {
    button(2, 8, 28, "Loose blocks", ShipControl.Action.REVIEW_LOOSE_BLOCKS);
    if (view.buttons().stream()
        .anyMatch(b -> b.action() == ShipControl.Action.CONFIRM_LEAVE_BLOCKS))
      button(33, 8, 31, "Confirm leave blocks", ShipControl.Action.CONFIRM_LEAVE_BLOCKS);
    button(2, 9, 28, "Check ship", ShipControl.Action.INSPECT);
    button(2, 10, 28, "Locate problem", ShipControl.Action.SHOW_PROBLEM);
    hit(
        2,
        11,
        28,
        details ? "[ Hide detail ]" : "[ Details ]",
        () -> {
          details = !details;
          statusScroll = 0;
          notice = "";
        },
        true);
    button(2, 12, 28, "Advanced", ShipControl.Action.ADVANCED);
  }

  private static String state(String state) {
    var berths = java.util.regex.Pattern.compile("^Station: (\\d+)/\\d+ free$").matcher(state);
    if (berths.find()) return berths.group(1) + " berths free";
    return state.equals(state.toUpperCase(Locale.ROOT)) && state.length() > 1
        ? state.charAt(0) + state.substring(1).toLowerCase(Locale.ROOT)
        : state;
  }

  private void drawPicker() {
    clearHiddenSelection();
    text(2, 1, view.scanner() ? "SCANNER" : "CHOOSE DESTINATION", TerminalTheme.BLUE);
    text(2, 2, "Ship destination: " + view.destination(), TerminalTheme.MUTED);
    text(2, 3, "Search", TerminalTheme.MUTED);
    hit(
        70,
        3,
        8,
        "[Clear]",
        () -> {
          search.clear();
          page = 0;
          highlighted = "";
        },
        true);
    search.draw(display, 10, 3, 58, searchFocused);
    view.entries().stream()
        .filter(e -> e.id().equals(highlighted))
        .findFirst()
        .ifPresent(
            e ->
                text(
                    2,
                    21,
                    "Selected: "
                        + e.name()
                        + " - "
                        + kind(e.kind())
                        + " - "
                        + region(e.region())
                        + " - "
                        + state(e.state()),
                    TerminalTheme.TEXT));
    String[] filters =
        view.scanner()
            ? new String[] {"All", "Stations", "Ships", "Planets & moons", "Landing pads"}
            : new String[] {"All", "Planets & moons", "Landing pads"};
    int x = 2;
    for (String chosen : filters) {
      hit(
          x,
          5,
          chosen.length() + 2,
          (filter.equals(chosen) ? "> " : "  ") + chosen,
          () -> {
            filter = chosen;
            page = 0;
            selection = 0;
            highlighted = "";
          },
          true,
          "",
          true,
          filter.equals(chosen) ? TerminalTheme.BLUE : TerminalTheme.MUTED);
      x += chosen.length() + 5;
    }
    var entries = filtered();
    hit(
        57,
        19,
        21,
        (filter.equals("Docking ports") ? "> " : "  ") + "[ Berths ]",
        () -> {
          filter = filter.equals("Docking ports") ? "All" : "Docking ports";
          page = 0;
          highlighted = "";
        },
        true,
        "",
        true,
        filter.equals("Docking ports") ? TerminalTheme.BLUE : TerminalTheme.MUTED);
    boolean scopes = drawScopes();
    int count = visibleCount();
    page = Math.clamp(page, 0, Math.max(0, (count - 1) / 12));
    for (int i = 0; !scopes && i < 12 && page * 12 + i < entries.size(); i++) {
      var entry = entries.get(page * 12 + i);
      boolean chosen = highlighted.equals(entry.id());
      hit(
          2,
          7 + i,
          76,
          (chosen ? "> " : "  ")
              + String.format(
                  Locale.ROOT,
                  "%-40.40s %-13.13s %s",
                  entry.name(),
                  entry.kind() == ShipDestination.Kind.VESSEL
                      ? entry.state().startsWith("Station") ? "Station" : "Ship"
                      : kind(entry.kind()),
                  state(entry.state())),
          () -> highlighted = entry.id(),
          true,
          "",
          true,
          chosen ? TerminalTheme.BLUE : TerminalTheme.TEXT);
    }
    if (!scopes && entries.isEmpty())
      text(
          2,
          8,
          filter.equals("Docking ports") && !view.scanner()
              ? "No matching ports. Local contacts update automatically; clear the search."
              : "No matching destinations. The list updates automatically.",
          TerminalTheme.MUTED);
    if (count > 12) {
      hit(
          2,
          20,
          8,
          "[ < ]",
          () -> {
            page = Math.max(0, page - 1);
            highlighted = "";
            selection = 0;
          },
          page > 0);
      text(11, 20, (page + 1) + " / " + ((count + 11) / 12), TerminalTheme.MUTED);
      hit(
          20,
          20,
          8,
          "[ > ]",
          () -> {
            page++;
            highlighted = "";
            selection = 0;
          },
          (page + 1) * 12 < count);
    }
    if (!view.scanner())
      text(2, 19, "Set distant destinations at the Scanner block", TerminalTheme.MUTED);
    hit(
        2,
        22,
        26,
        "[ Set destination ]",
        this::choose,
        !pending
            && !highlighted.isEmpty()
            && entries.stream().anyMatch(e -> e.id().equals(highlighted)));
    hit(
        31,
        22,
        26,
        view.scanner() ? "[ Upgrades ]" : "[ Back to controller ]",
        () -> {
          if (view.scanner()) command(ShipControl.Action.ADVANCED);
          else {
            picker = false;
            selection = 0;
          }
        },
        true);
  }

  private static List<String> wrapped(String message) {
    var lines = new ArrayList<String>();
    for (String part : clean(message.replace('\n', ' ')).split(" ")) {
      if (part.isEmpty()) continue;
      if (lines.isEmpty() || lines.getLast().length() + part.length() + 1 > 76) {
        while (part.length() > 76) {
          lines.add(part.substring(0, 76));
          part = part.substring(76);
        }
        lines.add(part);
      } else lines.set(lines.size() - 1, lines.getLast() + " " + part);
    }
    return lines;
  }

  private static String label(ShipControl.Action action) {
    if (action == ShipControl.Action.REVIEW_LOOSE_BLOCKS) return "Review loose blocks";
    if (action == ShipControl.Action.CONFIRM_LEAVE_BLOCKS) return "Confirm leave blocks";
    String name = action.name().toLowerCase(Locale.ROOT);
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  @Override
  public void input(TerminalInput input) {
    display();
    if (input instanceof TerminalInput.Pointer p) {
      searchFocused = picker && p.row() == 3 && p.column() >= 10 && p.column() < 68;
      if (searchFocused) {
        search.click(p.column() - 10);
        return;
      }
      for (int i = 0; i < hits.size(); i++) {
        var hit = hits.get(i);
        if (p.row() == hit.y && p.column() >= hit.x && p.column() < hit.x + hit.width) {
          selection = i;
          if (hit.enabled) hit.run.run();
          else if (!hit.reason.isEmpty()) hit.run.run();
          break;
        }
      }
    } else if (input instanceof TerminalInput.Text && picker && searchFocused) {
      search.input(input);
      page = 0;
      highlighted = "";
    } else if (input instanceof TerminalInput.Key key) {
      if (picker
          && searchFocused
          && Set.of(
                  TerminalInput.Key.LEFT,
                  TerminalInput.Key.RIGHT,
                  TerminalInput.Key.HOME,
                  TerminalInput.Key.END,
                  TerminalInput.Key.SELECT_ALL,
                  TerminalInput.Key.BACKSPACE,
                  TerminalInput.Key.DELETE)
              .contains(key)) {
        search.input(input);
        page = 0;
        highlighted = "";
        return;
      }
      if (picker
          && searchFocused
          && (key == TerminalInput.Key.TAB
              || key == TerminalInput.Key.DOWN
              || key == TerminalInput.Key.ENTER)) {
        searchFocused = false;
        for (int i = 0; i < hits.size(); i++)
          if (hits.get(i).y == 7) {
            selection = i;
            break;
          }
        return;
      }
      switch (key) {
        case UP, LEFT -> selection = Math.floorMod(selection - 1, hits.size());
        case DOWN, RIGHT, TAB -> selection = (selection + 1) % hits.size();
        case ENTER -> {
          var hit = hits.get(selection);
          if (hit.enabled) hit.run.run();
          else if (!hit.reason.isEmpty()) hit.run.run();
        }
        case ESCAPE -> {
          if (picker && !view.scanner()) {
            picker = false;
            selection = 0;
          }
        }
        case PAGE_UP -> {
          if (picker) {
            page = Math.max(0, page - 1);
            highlighted = "";
          } else statusScroll = Math.max(0, statusScroll - 3);
        }
        case PAGE_DOWN -> {
          if (picker) {
            page++;
            highlighted = "";
          } else
            statusScroll = Math.min(Math.max(0, wrapped(status()).size() - 3), statusScroll + 3);
        }
        default -> {}
      }
    }
  }

  public void reopened(ShipControl.View next) {
    pending = false;
    pendingTarget = "";
    close = false;
    notice = "";
    view = next;
    clearHiddenSelection();
  }

  public boolean closeRequested() {
    return close;
  }

  public void tick() {
    if (pending && ++pendingTicks > 100)
      notice = "No reply yet. Close and reopen these controls to check the action.";
  }

  private void clearHiddenSelection() {
    var entries = filtered();
    page = Math.clamp(page, 0, Math.max(0, (visibleCount() - 1) / 12));
    if (entries.stream().skip(page * 12L).limit(12).noneMatch(e -> e.id().equals(highlighted)))
      highlighted = "";
  }

  private static String kind(ShipDestination.Kind kind) {
    return switch (kind) {
      case REGION -> "Planet/moon";
      case PAD -> "Landing pad";
      case PORT -> "Dock";
      case VESSEL -> "Station/ship";
    };
  }

  private String region(String id) {
    return view.entries().stream()
        .filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(id))
        .map(ShipControl.Entry::name)
        .findFirst()
        .orElse(id.replace('_', ' '));
  }
}
