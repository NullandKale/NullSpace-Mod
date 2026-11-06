package com.nullandkale.nullspace.terminal;

import java.util.*;

/** Construction and access stay together; pickers choose values without spending kits. */
public final class StationCoreTerminal implements ControllerApplication {
  private record Hit(String id, int x, int y, int width, Runnable action) {}

  private final TerminalBuffer buffer = new TerminalBuffer();
  private final ArrayDeque<ControllerTerminal.Action> actions = new ArrayDeque<>();
  private final List<Hit> hits = new ArrayList<>();
  private List<ComputerTerminal.Destination> catalog;
  private final TerminalField name,
      player = new TerminalField("", 32),
      query = new TerminalField("", 32);
  private String status, notice = "", builderId = "", picker = "";
  private int selection, tile, role, section, scroll, pickerPage;
  private boolean waiting;

  public StationCoreTerminal(String status, List<ComputerTerminal.Destination> catalog) {
    this.status = status;
    this.catalog = List.copyOf(catalog);
    String initial =
        catalog.stream()
            .filter(d -> d.id().equals("station_name"))
            .map(ComputerTerminal.Destination::name)
            .findFirst()
            .orElse(status.lines().findFirst().orElse("Station"));
    name = new TerminalField(initial.substring(0, Math.min(32, initial.length())), 32);
  }

  public void catalog(List<ComputerTerminal.Destination> next) {
    if (!picker.isEmpty()
        && !catalog.stream()
            .filter(d -> d.state().equals(picker))
            .map(ComputerTerminal.Destination::id)
            .toList()
            .equals(
                next.stream()
                    .filter(d -> d.state().equals(picker))
                    .map(ComputerTerminal.Destination::id)
                    .toList())) selection = 0;
    catalog = List.copyOf(next);
    if (catalog.stream().noneMatch(d -> d.state().equals("BUILDER") && d.id().equals(builderId)))
      builderId = "";
  }

  public int selection() {
    return selection;
  }

  public String status() {
    return notice.isEmpty() ? status : notice;
  }

  public String fieldValue() {
    return field() == player ? player.value() : name.value();
  }

  public ControllerTerminal.Action pollAction() {
    return actions.pollFirst();
  }

  public void update(String message) {
    status = message;
    scroll = 0;
  }

  public void refresh(String message) {
    status = message;
  }

  public void acknowledged() {
    waiting = false;
    notice = "";
  }

  public void rejected(String reason) {
    waiting = false;
    notice = reason;
    scroll = 0;
  }

  private void send(String verb, String arg) {
    if (waiting) return;
    waiting = true;
    notice = "Waiting for server...";
    actions.add(new ControllerTerminal.Action(verb, arg));
  }

  private String setting(String id, String fallback) {
    return catalog.stream()
        .filter(d -> d.id().equals(id))
        .map(ComputerTerminal.Destination::name)
        .findFirst()
        .orElse(fallback);
  }

  private void text(int x, int y, String s, int color) {
    s = s.replaceAll("[^ -~]", "?");
    buffer.put(x, y, s.substring(0, Math.min(78 - x, s.length())), color, TerminalTheme.BACKGROUND);
  }

  private void control(String id, int x, int y, int width, String label, Runnable action) {
    int index = hits.size();
    String blocked =
        !setting("station_rights", "Owner").equals("Owner")
                && Set.of("access", "invite", "remove").contains(id)
            ? "Only the station owner can change access and builders"
            : id.equals("remove") && builderId.isBlank()
                ? "Choose a builder before removing access"
                : "";
    hits.add(new Hit(id, x, y, width, blocked.isEmpty() ? action : () -> notice = blocked));
    buffer.put(
        x,
        y,
        label.substring(0, Math.min(width, label.length())),
        blocked.isEmpty() ? TerminalTheme.CYAN : TerminalTheme.MUTED,
        selection == index ? TerminalTheme.SELECTED : TerminalTheme.PANEL);
  }

  private void field(String id, TerminalField field, int x, int y, int width) {
    control(id, x, y, width, "", () -> {});
    field.draw(buffer, x, y, width, selection == hits.size() - 1);
  }

  private void choose(String kind) {
    picker = kind;
    query.clear();
    pickerPage = 0;
    selection = 0;
  }

  private TerminalField field() {
    if (selection >= hits.size()) return null;
    return switch (hits.get(selection).id()) {
      case "name" -> name;
      case "player" -> player;
      case "query" -> query;
      default -> null;
    };
  }

  public TerminalBuffer display() {
    buffer.reset();
    buffer.cursorVisible(false);
    for (int y = 0; y < 25; y++)
      buffer.put(0, y, " ".repeat(80), TerminalTheme.TEXT, TerminalTheme.BACKGROUND);
    hits.clear();
    text(
        2,
        1,
        picker.isEmpty()
            ? "STATION CORE"
            : picker.equals("PLAYER") ? "CHOOSE A PLAYER TO INVITE" : "CHOOSE A BUILDER",
        TerminalTheme.BLUE);
    if (!picker.isEmpty()) {
      text(2, 3, "Search:", TerminalTheme.TEXT);
      field("query", query, 10, 3, 32);
      var people =
          catalog.stream()
              .filter(d -> d.state().equals(picker))
              .filter(
                  d ->
                      d.name()
                          .toLowerCase(Locale.ROOT)
                          .contains(query.value().toLowerCase(Locale.ROOT)))
              .toList();
      pickerPage = Math.clamp(pickerPage, 0, Math.max(0, (people.size() - 1) / 12));
      if (people.isEmpty())
        text(
            2,
            6,
            picker.equals("PLAYER") ? "No matching online players" : "No matching builders",
            TerminalTheme.MUTED);
      for (int i = 0; i < 12 && pickerPage * 12 + i < people.size(); i++) {
        var person = people.get(pickerPage * 12 + i);
        control(
            "person",
            2,
            6 + i,
            Math.min(70, person.name().length() + 4),
            "[ " + person.name() + " ]",
            () -> {
              if (picker.equals("PLAYER")) {
                player.clear();
                player.input(new TerminalInput.Text(person.name()));
              } else builderId = person.id();
              picker = "";
              selection = 0;
            });
      }
      control("prev", 2, 20, 12, "[ Previous ]", () -> pickerPage = Math.max(0, pickerPage - 1));
      control(
          "next",
          20,
          20,
          8,
          "[ Next ]",
          () -> pickerPage = Math.min(Math.max(0, (people.size() - 1) / 12), pickerPage + 1));
      control(
          "back",
          2,
          23,
          10,
          "[ Back ]",
          () -> {
            picker = "";
            selection = 0;
          });
      selection = Math.clamp(selection, 0, Math.max(0, hits.size() - 1));
      return buffer;
    }
    text(2, 3, "Name:", TerminalTheme.TEXT);
    field("name", name, 8, 3, 32);
    control("save", 57, 3, 13, "[ Save name ]", () -> send("station_rename", name.value()));
    int side =
        Integer.parseInt(
            catalog.stream()
                .filter(d -> d.state().equals("STATION_AREA"))
                .map(ComputerTerminal.Destination::name)
                .findFirst()
                .orElse("1"));
    section = Math.clamp(section, 0, side * side - 1);
    int ox = section % side * 2, oz = section / side * 2;
    control("prev", 2, 4, 20, "[ Previous section ]", () -> section = Math.max(0, section - 1));
    control(
        "next",
        24,
        4,
        20,
        "Section " + (section + 1) + "/" + side * side + " [Next]",
        () -> section = Math.min(side * side - 1, section + 1));
    if (side == 1)
      control("expand", 48, 4, 28, "[ Enlarge area: 128 x 128 ]", () -> send("station_expand", ""));
    else text(48, 4, "Build area: 128 x 128", TerminalTheme.MUTED);
    for (int i = 0; i < 4; i++) {
      final int cell = i;
      String coords = (ox + i % 2) + "_" + (oz + i / 2);
      var room = catalog.stream().filter(d -> d.id().equals(coords)).findFirst();
      control(
          "tile",
          2 + i % 2 * 36,
          6 + i / 2 * 2,
          34,
          (tile == i ? "> " : "  ")
              + "[ "
              + coords.replace('_', ',')
              + " "
              + room.map(
                      r ->
                          switch (r.name()) {
                            case "ARRIVAL" -> "Entrance";
                            case "HABITATION" -> "Bedroom";
                            case "ENGINEERING" -> "Power room";
                            case "WORKSHOP" -> "Workshop";
                            default -> r.name();
                          })
                  .orElse("Empty")
              + " ]",
          () -> tile = cell);
    }
    String[] labels = {"Entrance", "Workshop", "Bedroom", "Power room"};
    var roles = com.nullandkale.nullspace.station.StationLayout.Role.values();
    for (int i = 0; i < roles.length; i++) {
      final int r = i;
      control(
          "role",
          2 + i * 19,
          10,
          18,
          (role == i ? ">" : " ") + "[" + labels[i] + "]",
          () -> role = r);
    }
    String coords = (ox + tile % 2) + "_" + (oz + tile / 2);
    boolean occupied = catalog.stream().anyMatch(d -> d.id().equals(coords));
    control(
        "build",
        2,
        12,
        35,
        occupied ? "[ Room already built ]" : "[ Build " + labels[role] + ": 1 kit ]",
        () -> {
          if (occupied) notice = "Choose an empty room tile; existing construction is preserved.";
          else send("station_module", roles[role].name() + " " + coords.replace('_', ' '));
        });
    control(
        "whole",
        40,
        12,
        37,
        "[ Build whole station: " + (side == 1 ? 4 : 28) + " kits ]",
        () -> {
          if (catalog.stream().anyMatch(d -> d.state().equals("ROOM")))
            notice =
                "Build whole station needs an empty station; use Build room to expand this one.";
          else send("station_generate", "");
        });
    control(
        "access",
        2,
        14,
        34,
        "[ Visitors: " + setting("station_access", "Unknown") + " ]",
        () -> send("station_access", ""));
    text(39, 14, "Docking access, not build rights", TerminalTheme.MUTED);
    text(2, 16, "Player:", TerminalTheme.TEXT);
    field("player", player, 10, 16, 20);
    control("choose", 31, 16, 17, "[ Choose player ]", () -> choose("PLAYER"));
    control(
        "invite",
        52,
        16,
        18,
        "[ Invite builder ]",
        () -> {
          if (player.value().isBlank()) choose("PLAYER");
          else send("station_invite", player.value());
        });
    var selected =
        catalog.stream()
            .filter(d -> d.state().equals("BUILDER") && d.id().equals(builderId))
            .findFirst();
    control(
        "builders",
        2,
        18,
        47,
        "[ Builders: "
            + selected.map(ComputerTerminal.Destination::name).orElse("Choose player")
            + " ]",
        () -> choose("BUILDER"));
    control(
        "remove",
        52,
        18,
        25,
        "[ Remove build access ]",
        () -> {
          if (selected.isPresent()) send("station_remove", selected.get().id().replace("-", ""));
          else notice = "Choose a builder first";
        });
    if (!setting("station_rights", "Owner").equals("Owner"))
      text(2, 17, "Only the owner can change visitors and builder access.", TerminalTheme.MUTED);
    String source = notice.isEmpty() ? status : notice;
    var lines =
        source
            .replaceAll("[^ -~\\n]", "?")
            .lines()
            .flatMap(
                line ->
                    java.util.stream.IntStream.range(0, Math.max(1, (line.length() + 75) / 76))
                        .mapToObj(
                            i -> line.substring(i * 76, Math.min(line.length(), (i + 1) * 76))))
            .toList();
    scroll = Math.clamp(scroll, 0, Math.max(0, lines.size() - 3));
    for (int i = 0; i < 3 && scroll + i < lines.size(); i++)
      text(2, 20 + i, lines.get(scroll + i), TerminalTheme.TEXT);
    if (catalog.stream().anyMatch(d -> d.id().equals("construction_return")))
      control("return", 2, 24, 28, "[ Back to construction ship ]", () -> send("dock_return", ""));
    else if (catalog.stream().anyMatch(d -> d.id().equals("station_claim")))
      control("claim", 2, 24, 30, "[ Claim and take command ]", () -> send("station_claim", ""));
    control("advanced", 40, 24, 14, "[ Advanced ]", () -> send("station_systems", ""));
    control("problem", 60, 24, 18, "[ Show problem ]", () -> send("station_refresh", ""));
    selection = Math.clamp(selection, 0, Math.max(0, hits.size() - 1));
    return buffer;
  }

  public void input(TerminalInput input) {
    display();
    if (input instanceof TerminalInput.Pointer p) {
      for (int i = 0; i < hits.size(); i++) {
        var hit = hits.get(i);
        if (p.row() == hit.y && p.column() >= hit.x && p.column() < hit.x + hit.width) {
          selection = i;
          if (field() != null) field().click(p.column() - hit.x);
          else hit.action.run();
          return;
        }
      }
    } else if (field() != null
        && (input instanceof TerminalInput.Text
            || input instanceof TerminalInput.Key k
                && Set.of(
                        TerminalInput.Key.SELECT_ALL,
                        TerminalInput.Key.HOME,
                        TerminalInput.Key.END,
                        TerminalInput.Key.LEFT,
                        TerminalInput.Key.RIGHT,
                        TerminalInput.Key.BACKSPACE,
                        TerminalInput.Key.DELETE)
                    .contains(k))) {
      field().input(input);
      pickerPage = 0;
    } else if (input instanceof TerminalInput.Key k)
      switch (k) {
        case UP, LEFT -> selection = Math.floorMod(selection - 1, hits.size());
        case DOWN, RIGHT, TAB -> selection = (selection + 1) % hits.size();
        case ENTER -> hits.get(selection).action.run();
        case ESCAPE -> {
          picker = "";
          selection = 0;
        }
        case PAGE_UP -> {
          if (!picker.isEmpty()) pickerPage = Math.max(0, pickerPage - 1);
          else scroll = Math.max(0, scroll - 3);
        }
        case PAGE_DOWN -> {
          if (!picker.isEmpty()) pickerPage++;
          else scroll += 3;
        }
        default -> {}
      }
    ;
  }

  public boolean picking() {
    return !picker.isEmpty();
  }
}
