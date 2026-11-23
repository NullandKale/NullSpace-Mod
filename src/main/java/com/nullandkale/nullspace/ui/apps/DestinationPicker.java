package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/**
 * The Scanner's list. Without a search or a filter it is one scrolling list: HERE (the same rows as the helm), then the system's planets, each opening
 * in place to show its pads, stations and ships, then OTHER STARS, which opens the star map. A search or a View filter lists matching places flat.
 * One click highlights a place, a double click sets it, and Set destination sets the highlighted one. Rows 3-20 hold the list.
 */
final class DestinationPicker
{

    static final int FIRST_ROW = 3, PAGE_SIZE = 18;

    /** One line of the list: a heading (no action), a place, or a planet that opens. */
    private record Line(String text, int color, Runnable click, Runnable twice, boolean chosen)
    {}

    static List<ControllerPackets.Entry> filtered(ShipControlTerminal o)
    {
        String query = o.search.value().toLowerCase(Locale.ROOT);
        boolean grouped = o.view.entries().stream().anyMatch(e -> e.kind() == ShipDestination.Kind.VESSEL);
        return o.view.entries().stream().filter(e -> o.filter.equals("Docking ports") || !grouped || e.kind() != ShipDestination.Kind.PORT).filter(e -> switch (o.filter)
        {
            case "Planets & moons" -> e.kind() == ShipDestination.Kind.REGION;
            case "Landing pads" -> e.kind() == ShipDestination.Kind.PAD;
            case "Docking ports" -> e.kind() == ShipDestination.Kind.PORT;
            case "Stations" -> e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Station");
            case "Ships" -> e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Ship");
            default -> true;
        }).filter(e -> (e.name() + " " + e.region() + " " + e.state()).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    /** Set destination: the highlighted place, wherever it is listed. */
    static void choose(ShipControlTerminal o)
    {
        if (o.highlighted.isEmpty() || o.view.entries().stream().noneMatch(e -> e.id().equals(o.highlighted)))
        {
            o.notice = "Choose a destination from the list";
            return;
        }
        o.choose(o.highlighted);
    }

    static String system(ShipControlTerminal o, ControllerPackets.Entry entry)
    {
        if (entry.region().equals(o.view.hereRegion()))
            return o.hereSystem();
        return o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(entry.region())).map(e -> e.name().split(" / ")[0]).findFirst().orElse("Local space");
    }

    /** Whether the list is flat: a search or a filter narrows it to matching places. */
    static boolean flat(ShipControlTerminal o)
    {
        return !o.search.value().isBlank() || !o.filter.equals("All");
    }

    static void drawPicker(ShipControlTerminal o)
    {
        o.text(2, 0, o.map ? "SCANNER / STAR MAP" : "SCANNER", TerminalTheme.BLUE);
        o.text(o.map ? 21 : 12, 0, o.view.ship(), TerminalTheme.MUTED);
        o.text(44, 0, String.format(Locale.ROOT, "Range %.0f ly | green: one jump", o.view.range()), TerminalTheme.MUTED);
        o.text(2, 1, "Destination:", TerminalTheme.MUTED);
        String destination = ShipControlTerminal.clean(o.view.destination());
        o.text(15, 1, destination.substring(0, Math.min(46, destination.length())), TerminalTheme.AMBER);
        o.text(2, 2, "Search", TerminalTheme.MUTED);
        o.hit(70, 2, 8, "[Clear]", () ->
        {
            o.search.clear();
            o.page = 0;
            o.listScroll = 0;
        }, true);
        o.search.draw(o.display, 9, 2, 39, o.searchFocused && !o.viewMenu);
        String viewName = switch (o.filter)
        {
            case "Planets & moons" -> "Planets";
            case "Landing pads" -> "Pads";
            case "Docking ports" -> "Berths";
            default -> o.filter;
        };
        o.hit(50, 2, 18, "[ View: " + viewName + " v ]", o::openViewMenu, true);
        if (o.map && !flat(o))
        {
            o.hit(62, 1, 16, "[ Back to list ]", () -> o.map = false, true);
            StarMapPanel.drawMap(o);
        }
        else
        {
            var lines = flat(o) ? flatLines(o) : treeLines(o);
            o.listScroll = Math.clamp(o.listScroll, 0, Math.max(0, lines.size() - PAGE_SIZE));
            for (int i = 0; i < PAGE_SIZE && o.listScroll + i < lines.size(); i++)
            {
                var line = lines.get(o.listScroll + i);
                if (line.click() == null)
                    o.text(2, FIRST_ROW + i, line.text(), line.color());
                else
                    o.hit(2, FIRST_ROW + i, 76, line.text(), line.click(), true, "", true, line.chosen() ? TerminalTheme.BLUE : line.color(), line.chosen() ? TerminalTheme.SELECTED : TerminalTheme.BACKGROUND, line.twice());
            }
            if (lines.size() > PAGE_SIZE)
                o.text(66, 21, (o.listScroll + 1) + "-" + Math.min(lines.size(), o.listScroll + PAGE_SIZE) + " of " + lines.size(), TerminalTheme.MUTED);
            o.text(2, 21, "Wheel or PgUp/PgDn scrolls. Double-click a place to set it.", TerminalTheme.MUTED);
            o.view.entries().stream().filter(e -> e.id().equals(o.highlighted)).findFirst().ifPresent(e -> o.text(2, 22, "Selected: " + e.name() + " - " + ControllerPanels.kind(o, e) + " - " + ShipControlTerminal.state(e.state()), TerminalTheme.TEXT));
        }
        o.hit(2, 23, 26, "[ Set destination ]", () -> choose(o), !o.pending && !o.highlighted.isEmpty() && o.view.entries().stream().anyMatch(e -> e.id().equals(o.highlighted)), o.highlighted.isEmpty() ? "Click a place first" : "");
        o.hit(31, 23, 26, "[ Upgrades ]", () -> o.command(ShipControl.Action.ADVANCED), true);
    }

    private static Line place(ShipControlTerminal o, ControllerPackets.Entry e, String indent)
    {
        boolean chosen = e.id().equals(o.highlighted);
        return new Line(ControllerPanels.row(o, e, indent, chosen), TerminalTheme.TEXT, () -> o.highlighted = e.id(), () -> o.choose(e.id()), chosen);
    }

    private static List<Line> flatLines(ShipControlTerminal o)
    {
        var out = new ArrayList<Line>();
        for (var e : filtered(o))
            out.add(place(o, e, ""));
        if (out.isEmpty())
            out.add(new Line(o.filter.equals("Docking ports") ? "No matching ports. Local contacts update automatically; clear the search." : "No matching destinations. The list updates automatically.", TerminalTheme.MUTED, null, null, false));
        return out;
    }

    private static List<Line> treeLines(ShipControlTerminal o)
    {
        var out = new ArrayList<Line>();
        boolean home = o.scopeSystem.isBlank() || o.scopeSystem.equals(o.hereSystem());
        String system = home ? o.hereSystem() : o.scopeSystem;
        if (home)
        {
            out.add(new Line("HERE  " + o.herePlace(), TerminalTheme.MUTED, null, null, false));
            var here = o.nearby();
            for (var e : here)
                out.add(place(o, e, ""));
            if (here.isEmpty())
                out.add(new Line("    Nothing to travel to here", TerminalTheme.MUTED, null, null, false));
            out.add(new Line("THIS SYSTEM  " + system, TerminalTheme.MUTED, null, null, false));
        }
        else
        {
            out.add(new Line("SYSTEM  " + system, TerminalTheme.MUTED, null, null, false));
            out.add(new Line("  < Back to " + o.hereSystem(), TerminalTheme.TEXT, () ->
            {
                o.scopeSystem = "";
                o.listScroll = 0;
            }, null, false));
        }
        for (var body : o.view.entries())
        {
            if (body.kind() != ShipDestination.Kind.REGION || body.region().equals(o.view.hereRegion()) || !system(o, body).equals(system))
                continue;
            var contents = o.view.entries().stream().filter(e -> e.region().equals(body.region()) && (e.kind() == ShipDestination.Kind.PAD || e.kind() == ShipDestination.Kind.VESSEL)).toList();
            boolean open = o.opened.contains(body.region());
            boolean chosen = body.id().equals(o.highlighted);
            String name = body.name().contains(" / ") ? body.name().substring(body.name().indexOf(" / ") + 3) : body.name();
            String places = contents.isEmpty() ? "" : contents.size() + (contents.size() == 1 ? " place" : " places");
            String text = (chosen ? "> " : "  ") + (open ? "v " : "> ") + String.format(Locale.ROOT, "%-34.34s %-26.26s %s", name, ShipControlTerminal.state(body.state()), places);
            out.add(new Line(text, TerminalTheme.TEXT, () ->
            {
                o.highlighted = body.id();
                if (!o.opened.remove(body.region()))
                    o.opened.add(body.region());
            }, () -> o.choose(body.id()), chosen));
            if (open)
                for (var e : contents)
                    out.add(place(o, e, "    "));
        }
        out.add(new Line("OTHER STARS", TerminalTheme.MUTED, null, null, false));
        long reachable = o.view.chart().stream().filter(c -> c.inRange() && !c.here()).count();
        out.add(new Line("  > Star map   " + reachable + " places within one jump, " + o.view.chart().size() + " charted", TerminalTheme.TEXT, () ->
        {
            o.map = true;
            o.chartSelected = "";
            o.selection = 0;
        }, null, false));
        return out;
    }

    static void drawViewMenu(ShipControlTerminal o)
    {
        String[] filters = new String[]{"All", "Stations", "Ships", "Planets & moons", "Landing pads", "Docking ports"};
        o.beginViewMenu();
        for (int i = 0; i < filters.length; i++)
        {
            String chosen = filters[i];
            o.hit(50, 3 + i, 28, (o.filter.equals(chosen) ? "> " : "  ") + chosen, () ->
            {
                o.filter = chosen;
                o.page = 0;
                o.listScroll = 0;
                o.viewMenu = false;
                o.searchFocused = false;
                o.selection = 0;
            }, true);
        }
    }

    /** A highlighted place that is no longer offered is forgotten. */
    static void clearHiddenSelection(ShipControlTerminal o)
    {
        if (!o.highlighted.isEmpty() && o.view.entries().stream().noneMatch(e -> e.id().equals(o.highlighted)))
            o.highlighted = "";
    }

    private DestinationPicker()
    {
    }
}
