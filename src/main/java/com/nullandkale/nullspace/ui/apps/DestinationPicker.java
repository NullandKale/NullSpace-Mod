package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;
import java.util.*;

/** The destination picker: filtering and searching destinations and drawing the list. */
final class DestinationPicker {

  static List<ControllerPackets.Entry> filtered(ShipControlTerminal o) {
    String query = o.search.value().toLowerCase(Locale.ROOT);
    boolean grouped =
        o.view.entries().stream().anyMatch(e -> e.kind() == ShipDestination.Kind.VESSEL);
    return o.view.entries().stream()
        .filter(
            e ->
                o.filter.equals("Docking ports")
                    || !grouped
                    || e.kind() != ShipDestination.Kind.PORT)
        .filter(
            e ->
                !query.isBlank()
                    || o.scopeRegion.isBlank()
                    || o.filter.equals("Planets & moons")
                        && (o.scopeSystem.isBlank() || system(o, e).equals(o.scopeSystem))
                    || e.region().equals(o.scopeRegion))
        .filter(
            e ->
                switch (o.filter) {
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

  static void choose(ShipControlTerminal o) {
    if (o.highlighted.isEmpty()
        || filtered(o).stream().noneMatch(e -> e.id().equals(o.highlighted))) {
      o.notice = "Choose a destination from the list";
      return;
    }
    o.pendingTarget = o.browser() ? "" : o.highlighted;
    o.send(ShipControl.Action.SELECT, o.highlighted);
  }

  static String system(ShipControlTerminal o, ControllerPackets.Entry entry) {
    return o.view.entries().stream()
        .filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(entry.region()))
        .map(e -> e.name().split(" / ")[0])
        .findFirst()
        .orElse("Local space");
  }

  static int visibleCount(ShipControlTerminal o) {
    if (o.search.value().isBlank()
        && o.scopeSystem.isBlank()
        && !o.view.here().isBlank()
        && !o.view.chart().isEmpty()) return 1; // The star map is one o.page.
    if (!o.search.value().isBlank() || o.view.here().isBlank() || !o.scopeRegion.isBlank())
      return filtered(o).size();
    return (int)
        o.view.entries().stream()
            .filter(e -> e.kind() == ShipDestination.Kind.REGION)
            .filter(e -> o.scopeSystem.isBlank() || system(o, e).equals(o.scopeSystem))
            .map(e -> o.scopeSystem.isBlank() ? system(o, e) : e.region())
            .distinct()
            .count();
  }

  static void drawPicker(ShipControlTerminal o) {
    clearHiddenSelection(o);
    o.text(2, 1, o.view.scanner() ? "SCANNER" : "CHOOSE DESTINATION", TerminalTheme.BLUE);
    o.text(2, 2, "Ship destination: " + o.view.destination(), TerminalTheme.MUTED);
    o.text(2, 3, "Search", TerminalTheme.MUTED);
    o.hit(
        70,
        3,
        8,
        "[Clear]",
        () -> {
          o.search.clear();
          o.page = 0;
          o.highlighted = "";
        },
        true);
    o.search.draw(o.display, 10, 3, 58, o.searchFocused);
    o.view.entries().stream()
        .filter(e -> e.id().equals(o.highlighted))
        .findFirst()
        .ifPresent(
            e ->
                o.text(
                    2,
                    21,
                    "Selected: "
                        + e.name()
                        + " - "
                        + ShipControlTerminal.kind(e.kind())
                        + " - "
                        + o.region(e.region())
                        + " - "
                        + o.state(e.state()),
                    TerminalTheme.TEXT));
    String[] filters =
        o.view.scanner()
            ? new String[] {"All", "Stations", "Ships", "Planets & moons", "Landing pads"}
            : new String[] {"All", "Planets & moons", "Landing pads"};
    int x = 2;
    for (String chosen : filters) {
      o.hit(
          x,
          5,
          chosen.length() + 2,
          (o.filter.equals(chosen) ? "> " : "  ") + chosen,
          () -> {
            o.filter = chosen;
            o.page = 0;
            o.selection = 0;
            o.highlighted = "";
          },
          true,
          "",
          true,
          o.filter.equals(chosen) ? TerminalTheme.BLUE : TerminalTheme.MUTED);
      x += chosen.length() + 5;
    }
    var entries = filtered(o);
    // The star map uses row 19 for the selected place's route; berths belong to a planet's list.
    if (!(o.search.value().isBlank()
        && o.scopeSystem.isBlank()
        && !o.view.here().isBlank()
        && !o.view.chart().isEmpty()))
      o.hit(
          57,
          19,
          21,
          (o.filter.equals("Docking ports") ? "> " : "  ") + "[ Berths ]",
          () -> {
            o.filter = o.filter.equals("Docking ports") ? "All" : "Docking ports";
            o.page = 0;
            o.highlighted = "";
          },
          true,
          "",
          true,
          o.filter.equals("Docking ports") ? TerminalTheme.BLUE : TerminalTheme.MUTED);
    boolean scopes = StarMapPanel.drawScopes(o);
    int count = visibleCount(o);
    o.page = Math.clamp(o.page, 0, Math.max(0, (count - 1) / 12));
    for (int i = 0; !scopes && i < 12 && o.page * 12 + i < entries.size(); i++) {
      var entry = entries.get(o.page * 12 + i);
      boolean chosen = o.highlighted.equals(entry.id());
      o.hit(
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
                      : ShipControlTerminal.kind(entry.kind()),
                  o.state(entry.state())),
          () -> o.highlighted = entry.id(),
          true,
          "",
          true,
          chosen ? TerminalTheme.BLUE : TerminalTheme.TEXT);
    }
    if (!scopes && entries.isEmpty())
      o.text(
          2,
          8,
          o.filter.equals("Docking ports") && !o.view.scanner()
              ? "No matching ports. Local contacts update automatically; clear the search."
              : "No matching destinations. The list updates automatically.",
          TerminalTheme.MUTED);
    if (count > 12) {
      o.hit(
          2,
          20,
          8,
          "[ < ]",
          () -> {
            o.page = Math.max(0, o.page - 1);
            o.highlighted = "";
            o.selection = 0;
          },
          o.page > 0);
      o.text(11, 20, (o.page + 1) + " / " + ((count + 11) / 12), TerminalTheme.MUTED);
      o.hit(
          20,
          20,
          8,
          "[ > ]",
          () -> {
            o.page++;
            o.highlighted = "";
            o.selection = 0;
          },
          (o.page + 1) * 12 < count);
    }
    if (!o.view.scanner())
      o.text(2, 19, "Set distant destinations at the Scanner block", TerminalTheme.MUTED);
    o.hit(
        2,
        22,
        26,
        "[ Set destination ]",
        () -> choose(o),
        !o.pending
            && !o.highlighted.isEmpty()
            && entries.stream().anyMatch(e -> e.id().equals(o.highlighted)));
    o.hit(
        31,
        22,
        26,
        o.view.scanner() ? "[ Upgrades ]" : "[ Back to controller ]",
        () -> {
          if (o.view.scanner()) o.command(ShipControl.Action.ADVANCED);
          else {
            o.picker = false;
            o.selection = 0;
          }
        },
        true);
  }

  static void clearHiddenSelection(ShipControlTerminal o) {
    // On the star map the o.selection is a chart place, not a row on a list o.page.
    if (o.picker
        && o.search.value().isBlank()
        && o.scopeSystem.isBlank()
        && !o.view.here().isBlank()
        && !o.view.chart().isEmpty()) return;
    var entries = filtered(o);
    o.page = Math.clamp(o.page, 0, Math.max(0, (visibleCount(o) - 1) / 12));
    if (entries.stream().skip(o.page * 12L).limit(12).noneMatch(e -> e.id().equals(o.highlighted)))
      o.highlighted = "";
  }

  private DestinationPicker() {}
}
