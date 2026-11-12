package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/** The Ship Controller's star map: system scopes, the chart of places and their glyphs. */
final class StarMapPanel
{

    static boolean drawScopes(ShipControlTerminal o)
    {
        if (!o.search.value().isBlank() || o.view.here().isBlank() || !(o.filter.equals("All") || o.filter.equals("Planets & moons")))
            return false;
        o.hit(2, 1, 76, "[ Back ] Stars" + (o.scopeSystem.isBlank() ? "" : " / " + o.scopeSystem) + (o.scopeRegion.isBlank() ? "" : " / " + o.region(o.scopeRegion).replace(o.scopeSystem + " / ", "")), () ->
        {
            if (!o.scopeRegion.isBlank())
                o.scopeRegion = "";
            else
                o.scopeSystem = "";
            o.highlighted = "";
            o.chartSelected = "";
            o.page = 0;
        }, true, "", true, TerminalTheme.BLUE);
        if (!o.scopeRegion.isBlank())
            return false;
        var bodies = o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION).toList();
        if (o.scopeSystem.isBlank() && !o.view.chart().isEmpty())
        {
            drawMap(o);
            return true;
        }
        if (o.scopeSystem.isBlank())
        {
            var systems = bodies.stream().map(x -> DestinationPicker.system(o, x)).distinct().sorted().toList();
            o.page = Math.clamp(o.page, 0, Math.max(0, (systems.size() - 1) / DestinationPicker.PAGE_SIZE));
            for (int i = 0; i < DestinationPicker.PAGE_SIZE && o.page * DestinationPicker.PAGE_SIZE + i < systems.size(); i++)
            {
                String name = systems.get(o.page * DestinationPicker.PAGE_SIZE + i);
                o.hit(2, DestinationPicker.FIRST_ROW + i, 76, name + " | " + bodies.stream().filter(e -> DestinationPicker.system(o, e).equals(name)).count() + " planets/moons >", () ->
                {
                    o.scopeSystem = name;
                    o.page = 0;
                    o.highlighted = "";
                }, true, "", true, TerminalTheme.TEXT);
            }
        }
        else
        {
            var local = bodies.stream().filter(e -> DestinationPicker.system(o, e).equals(o.scopeSystem)).toList();
            o.page = Math.clamp(o.page, 0, Math.max(0, (local.size() - 1) / DestinationPicker.PAGE_SIZE));
            for (int i = 0; i < DestinationPicker.PAGE_SIZE && o.page * DestinationPicker.PAGE_SIZE + i < local.size(); i++)
            {
                var body = local.get(o.page * DestinationPicker.PAGE_SIZE + i);
                long contacts = o.view.entries().stream().filter(e -> e.region().equals(body.region()) && (e.kind() == ShipDestination.Kind.VESSEL || e.kind() == ShipDestination.Kind.PAD)).count();
                o.hit(2, DestinationPicker.FIRST_ROW + i, 76, body.name() + " | " + o.state(body.state()) + " | " + contacts + " stations / ships / pads >", () ->
                {
                    o.scopeRegion = body.region();
                    o.page = 0;
                    o.highlighted = "";
                }, true, "", true, TerminalTheme.TEXT);
            }
        }
        return true;
    }

    /** Map glyphs: the star map legend uses the same characters. */
    static char glyph(ControllerPackets.ChartPlace place)
    {
        if (place.here())
            return '@';
        return switch (place.kind())
        {
            case "System" -> '*';
            case "Nebula" -> '~';
            case "Asteroid field" -> ':';
            case "Rogue planet" -> 'o';
            case "Pulsar" -> '+';
            case "Black hole" -> '#';
            default -> '?';
        };
    }

    /** Full-width chart; names stay on the chart and use nearby free space when crowded. */
    static void drawMap(ShipControlTerminal o)
    {
        final int black = 0xff000000;
        o.text(2, 0, "SCANNER / STAR MAP", TerminalTheme.BLUE);
        o.text(40, 0, String.format(Locale.ROOT, "Range %.0f ly | green: one jump", o.view.range()), TerminalTheme.MUTED);
        for (int row = 3; row <= 20; row++)
            o.display.put(2, row, " ".repeat(76), TerminalTheme.TEXT, black);
        var places = new ArrayList<>(o.view.chart());
        places.sort(Comparator.comparing((ControllerPackets.ChartPlace p) -> !p.id().equals(o.chartSelected)).thenComparing(p -> !p.here()));
        var layout = ChartLabels.place(places.stream().map(p -> new ChartLabels.Contact(p.id(), p.name(), p.x(), p.y())).toList(), 76, 18);
        for (var placed : layout)
        {
            var place = places.stream().filter(p -> p.id().equals(placed.id())).findFirst().orElseThrow();
            int color = place.id().equals(o.chartSelected) ? TerminalTheme.AMBER : place.here() ? TerminalTheme.BLUE : place.inRange() ? TerminalTheme.GREEN : TerminalTheme.MUTED;
            Runnable choose = () ->
            {
                if (o.chartSelected.equals(place.id()) && place.kind().equals("System"))
                {
                    o.scopeSystem = systemNameOf(o, place);
                    o.page = 0;
                    o.highlighted = "";
                    return;
                }
                o.chartSelected = place.id();
                o.highlighted = o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(place.zone())).map(ControllerPackets.Entry::id).findFirst().orElse("");
            };
            for (var line : placed.line())
                o.display.put(2 + line.x(), 3 + line.y(), ".", TerminalTheme.MUTED, black);
            o.hit(2 + placed.x(), 3 + placed.y(), 1, String.valueOf(glyph(place)), choose, true, "", true, color, black);
            o.hit(2 + placed.labelX(), 3 + placed.labelY(), placed.label().length(), placed.label(), choose, true, "", true, color, black);
        }
        var selected = o.view.chart().stream().filter(p -> p.id().equals(o.chartSelected)).findFirst();
        selected.ifPresent(p -> o.text(2, 21, p.name() + (p.here() ? " | you are here" : p.inRange() ? String.format(Locale.ROOT, " | %.1f ly | jump %.2f fuel | in range", p.lightYears(), p.fuel()) : String.format(Locale.ROOT, " | %.1f ly | %.2f fuel total | %s", p.lightYears(), p.fuel(), p.route())), p.inRange() || p.here() ? TerminalTheme.TEXT : TerminalTheme.AMBER));
        o.text(2, 22, selected.map(ControllerPackets.ChartPlace::summary).orElse("Select a contact. Select a star again to browse its planets."), TerminalTheme.MUTED);
    }

    static String systemNameOf(ShipControlTerminal o, ControllerPackets.ChartPlace place)
    {
        return o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(place.zone())).map(e -> e.name().split(" / ")[0]).findFirst().orElse(place.name());
    }

    private StarMapPanel()
    {
    }
}
