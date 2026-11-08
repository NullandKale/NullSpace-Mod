package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;
import java.util.*;

/** The Ship Controller's star map: system scopes, the chart of places and their glyphs. */
final class StarMapPanel {

  static boolean drawScopes(ShipControlTerminal o) {
    if (!o.search.value().isBlank() || o.view.here().isBlank()) return false;
    o.hit(
        2,
        2,
        76,
        "[ Back ] Stars"
            + (o.scopeSystem.isBlank() ? "" : " / " + o.scopeSystem)
            + (o.scopeRegion.isBlank()
                ? ""
                : " / " + o.region(o.scopeRegion).replace(o.scopeSystem + " / ", "")),
        () -> {
          if (!o.scopeRegion.isBlank()) o.scopeRegion = "";
          else o.scopeSystem = "";
          o.highlighted = "";
          o.chartSelected = "";
          o.page = 0;
        },
        true,
        "",
        true,
        TerminalTheme.BLUE);
    if (!o.scopeRegion.isBlank()) return false;
    var bodies =
        o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION).toList();
    if (o.scopeSystem.isBlank() && !o.view.chart().isEmpty()) {
      drawMap(o);
      return true;
    }
    if (o.scopeSystem.isBlank()) {
      var systems =
          bodies.stream().map(x -> DestinationPicker.system(o, x)).distinct().sorted().toList();
      o.page = Math.clamp(o.page, 0, Math.max(0, (systems.size() - 1) / 12));
      for (int i = 0; i < 12 && o.page * 12 + i < systems.size(); i++) {
        String name = systems.get(o.page * 12 + i);
        o.hit(
            2,
            7 + i,
            76,
            name
                + " | "
                + bodies.stream().filter(e -> DestinationPicker.system(o, e).equals(name)).count()
                + " planets/moons >",
            () -> {
              o.scopeSystem = name;
              o.page = 0;
              o.highlighted = "";
            },
            true,
            "",
            true,
            TerminalTheme.TEXT);
      }
    } else {
      var local =
          bodies.stream()
              .filter(e -> DestinationPicker.system(o, e).equals(o.scopeSystem))
              .toList();
      o.page = Math.clamp(o.page, 0, Math.max(0, (local.size() - 1) / 12));
      for (int i = 0; i < 12 && o.page * 12 + i < local.size(); i++) {
        var body = local.get(o.page * 12 + i);
        long contacts =
            o.view.entries().stream()
                .filter(
                    e ->
                        e.region().equals(body.region())
                            && (e.kind() == ShipDestination.Kind.VESSEL
                                || e.kind() == ShipDestination.Kind.PAD))
                .count();
        o.hit(
            2,
            7 + i,
            76,
            body.name()
                + " | "
                + o.state(body.state())
                + " | "
                + contacts
                + " stations / ships / pads >",
            () -> {
              o.scopeRegion = body.region();
              o.page = 0;
              o.highlighted = "";
            },
            true,
            "",
            true,
            TerminalTheme.TEXT);
      }
    }
    return true;
  }

  /** Map glyphs: the star map legend uses the same characters. */
  static char glyph(ControllerPackets.ChartPlace place) {
    if (place.here()) return '@';
    return switch (place.kind()) {
      case "System" -> '*';
      case "Nebula" -> '~';
      case "Asteroid field" -> ':';
      case "Rogue planet" -> 'o';
      case "Pulsar" -> '+';
      case "Black hole" -> '#';
      default -> '?';
    };
  }

  /**
   * The galaxy chart (P9) on the 76x12 area under the filters: every system and feature at its 2D
   * position, colored by whether the ship's engines reach it in one jump.
   */
  static void drawMap(ShipControlTerminal o) {
    o.text(
        2,
        6,
        String.format(
            Locale.ROOT,
            "STAR MAP  engine range %.0f ly  green: one jump  grey: hop via others",
            o.view.range()),
        TerminalTheme.MUTED);
    var selected = o.view.chart().stream().filter(p -> p.id().equals(o.chartSelected)).findFirst();
    o.text(
        2,
        20,
        selected.isPresent()
            ? selected.get().summary()
                + (selected.get().kind().equals("System") ? " | select again to open" : "")
            : "@ you  * star  ~ nebula  : asteroids  o rogue planet  + pulsar  # black hole",
        selected.isPresent() ? TerminalTheme.TEXT : TerminalTheme.MUTED);
    boolean[][] used = new boolean[25][80];
    var places = new ArrayList<>(o.view.chart());
    // Draw the current place last so its glyph wins any collision.
    places.sort(Comparator.comparing(ControllerPackets.ChartPlace::here));
    for (var place : places) {
      int col = 2 + Math.clamp(place.x() * 73 / 1000, 0, 73);
      int row = 7 + Math.clamp(place.y() * 11 / 1000, 0, 11);
      while (used[row][col] && col < 76) col++;
      String label = glyph(place) + " " + place.name();
      boolean room = col + label.length() <= 78;
      for (int i = 0; room && i < label.length() + 1 && col + i < 80; i++)
        if (used[row][col + i]) room = false;
      String shown = room ? label : String.valueOf(glyph(place));
      for (int i = 0; i < shown.length() + (room ? 1 : 0) && col + i < 80; i++)
        used[row][col + i] = true;
      int color =
          place.id().equals(o.chartSelected)
              ? TerminalTheme.AMBER
              : place.here()
                  ? TerminalTheme.BLUE
                  : place.inRange() ? TerminalTheme.GREEN : TerminalTheme.MUTED;
      o.hit(
          col,
          row,
          shown.length(),
          shown,
          () -> {
            if (o.chartSelected.equals(place.id()) && place.kind().equals("System")) {
              o.scopeSystem = systemNameOf(o, place);
              o.page = 0;
              o.highlighted = "";
              return;
            }
            o.chartSelected = place.id();
            o.highlighted =
                o.view.entries().stream()
                    .filter(
                        e ->
                            e.kind() == ShipDestination.Kind.REGION
                                && e.region().equals(place.zone()))
                    .map(ControllerPackets.Entry::id)
                    .findFirst()
                    .orElse("");
          },
          true,
          "",
          true,
          color);
    }
    o.view.chart().stream()
        .filter(p -> p.id().equals(o.chartSelected))
        .findFirst()
        .ifPresent(
            p ->
                o.text(
                    2,
                    19,
                    p.name()
                        + (p.here()
                            ? " | you are here"
                            : p.inRange()
                                ? String.format(
                                    Locale.ROOT,
                                    " | %.1f ly | jump %.2f fuel | in range",
                                    p.lightYears(),
                                    p.fuel())
                                : String.format(
                                    Locale.ROOT,
                                    " | %.1f ly | %.2f fuel total | %s",
                                    p.lightYears(),
                                    p.fuel(),
                                    p.route())),
                    p.inRange() || p.here() ? TerminalTheme.TEXT : TerminalTheme.AMBER));
  }

  static String systemNameOf(ShipControlTerminal o, ControllerPackets.ChartPlace place) {
    return o.view.entries().stream()
        .filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(place.zone()))
        .map(e -> e.name().split(" / ")[0])
        .findFirst()
        .orElse(place.name());
  }

  private StarMapPanel() {}
}
