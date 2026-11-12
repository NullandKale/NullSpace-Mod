package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/** The destination picker: filtering and searching destinations and drawing the list. */
final class DestinationPicker
{

    static final int FIRST_ROW = 3, PAGE_SIZE = 18;

    static List<ControllerPackets.Entry> filtered(ShipControlTerminal o)
    {
        String query = o.search.value().toLowerCase(Locale.ROOT);
        boolean grouped = o.view.entries().stream().anyMatch(e -> e.kind() == ShipDestination.Kind.VESSEL);
        return o.view.entries().stream().filter(e -> o.filter.equals("Docking ports") || !grouped || e.kind() != ShipDestination.Kind.PORT).filter(e -> !query.isBlank() || o.scopeRegion.isBlank() || o.filter.equals("Planets & moons") && (o.scopeSystem.isBlank() || system(o, e).equals(o.scopeSystem)) || e.region().equals(o.scopeRegion)).filter(e -> switch (o.filter)
        {
            case "Planets & moons" -> e.kind() == ShipDestination.Kind.REGION;
            case "Landing pads" -> e.kind() == ShipDestination.Kind.PAD;
            case "Docking ports" -> e.kind() == ShipDestination.Kind.PORT;
            case "Stations" -> e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Station");
            case "Ships" -> e.kind() == ShipDestination.Kind.VESSEL && e.state().startsWith("Ship");
            default -> true;
        }).filter(e -> (e.name() + " " + e.region() + " " + e.state()).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    static void choose(ShipControlTerminal o)
    {
        if (o.highlighted.isEmpty() || filtered(o).stream().noneMatch(e -> e.id().equals(o.highlighted)))
        {
            o.notice = "Choose a destination from the list";
            return;
        }
        o.pendingTarget = o.browser() ? "" : o.highlighted;
        o.send(ShipControl.Action.SELECT, o.highlighted);
    }

    static String system(ShipControlTerminal o, ControllerPackets.Entry entry)
    {
        return o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(entry.region())).map(e -> e.name().split(" / ")[0]).findFirst().orElse("Local space");
    }

    static boolean chart(ShipControlTerminal o)
    {
        return o.search.value().isBlank() && o.scopeSystem.isBlank() && !o.view.here().isBlank() && !o.view.chart().isEmpty() && (o.filter.equals("All") || o.filter.equals("Planets & moons"));
    }

    static int visibleCount(ShipControlTerminal o)
    {
        if (chart(o))
            return 1; // The star map is one o.page.
        if (!o.search.value().isBlank() || o.view.here().isBlank() || !o.scopeRegion.isBlank() || !(o.filter.equals("All") || o.filter.equals("Planets & moons")))
            return filtered(o).size();
        return (int) o.view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION).filter(e -> o.scopeSystem.isBlank() || system(o, e).equals(o.scopeSystem)).map(e -> o.scopeSystem.isBlank() ? system(o, e) : e.region()).distinct().count();
    }

    static void drawPicker(ShipControlTerminal o)
    {
        clearHiddenSelection(o);
        o.text(2, 0, o.view.scanner() ? "SCANNER" : "CHOOSE DESTINATION", TerminalTheme.BLUE);
        o.text(2, 1, "Ship destination: " + o.view.destination(), TerminalTheme.MUTED);
        o.text(2, 2, "Search", TerminalTheme.MUTED);
        o.hit(70, 2, 8, "[Clear]", () ->
        {
            o.search.clear();
            o.page = 0;
            o.highlighted = "";
        }, true);
        o.search.draw(o.display, 9, 2, 39, o.searchFocused && !o.viewMenu);
        String viewName = switch (o.filter)
        {
            case "Planets & moons" -> "Planets";
            case "Landing pads" -> "Pads";
            case "Docking ports" -> "Berths";
            default -> o.filter;
        };
        o.hit(50, 2, 20, "[ View: " + viewName + " v ]", o::openViewMenu, true);
        o.view.entries().stream().filter(e -> e.id().equals(o.highlighted)).findFirst().ifPresent(e -> o.text(2, 22, "Selected: " + e.name() + " - " + ShipControlTerminal.kind(e.kind()) + " - " + o.state(e.state()), TerminalTheme.TEXT));
        var entries = filtered(o);
        boolean scopes = StarMapPanel.drawScopes(o);
        int count = visibleCount(o);
        o.page = Math.clamp(o.page, 0, Math.max(0, (count - 1) / PAGE_SIZE));
        for (int i = 0; !scopes && i < PAGE_SIZE && o.page * PAGE_SIZE + i < entries.size(); i++)
        {
            var entry = entries.get(o.page * PAGE_SIZE + i);
            boolean chosen = o.highlighted.equals(entry.id());
            o.hit(2, FIRST_ROW + i, 76, (chosen ? "> " : "  ") + String.format(Locale.ROOT, "%-40.40s %-13.13s %s", entry.name(), entry.kind() == ShipDestination.Kind.VESSEL ? entry.state().startsWith("Station") ? "Station" : "Ship" : ShipControlTerminal.kind(entry.kind()), o.state(entry.state())), () -> o.highlighted = entry.id(), true, "", true, chosen ? TerminalTheme.BLUE : TerminalTheme.TEXT);
        }
        if (!scopes && entries.isEmpty())
            o.text(2, FIRST_ROW + 1, o.filter.equals("Docking ports") && !o.view.scanner() ? "No matching ports. Local contacts update automatically; clear the search." : "No matching destinations. The list updates automatically.", TerminalTheme.MUTED);
        if (count > PAGE_SIZE)
        {
            o.hit(2, 21, 8, "[ < ]", () ->
            {
                o.page = Math.max(0, o.page - 1);
                o.highlighted = "";
                o.selection = 0;
            }, o.page > 0);
            o.text(11, 21, (o.page + 1) + " / " + ((count + PAGE_SIZE - 1) / PAGE_SIZE), TerminalTheme.MUTED);
            o.hit(20, 21, 8, "[ > ]", () ->
            {
                o.page++;
                o.highlighted = "";
                o.selection = 0;
            }, (o.page + 1) * PAGE_SIZE < count);
        }
        if (!o.view.scanner() && count <= PAGE_SIZE)
            o.text(2, 21, "Set distant destinations at the Scanner block", TerminalTheme.MUTED);
        o.hit(2, 23, 26, "[ Set destination ]", () -> choose(o), !o.pending && !o.highlighted.isEmpty() && entries.stream().anyMatch(e -> e.id().equals(o.highlighted)));
        o.hit(31, 23, 26, o.view.scanner() ? "[ Upgrades ]" : "[ Back to controller ]", () ->
        {
            if (o.view.scanner())
                o.command(ShipControl.Action.ADVANCED);
            else
            {
                o.picker = false;
                o.selection = 0;
            }
        }, true);
    }

    static void drawViewMenu(ShipControlTerminal o)
    {
        String[] filters = o.view.scanner() ? new String[]{"All", "Stations", "Ships", "Planets & moons", "Landing pads", "Docking ports"} : new String[]{"All", "Planets & moons", "Landing pads", "Docking ports"};
        o.beginViewMenu();
        for (int i = 0; i < filters.length; i++)
        {
            String chosen = filters[i];
            o.hit(50, 3 + i, 28, (o.filter.equals(chosen) ? "> " : "  ") + chosen, () ->
            {
                o.filter = chosen;
                o.page = 0;
                o.highlighted = "";
                o.viewMenu = false;
                o.searchFocused = false;
                o.selection = 0;
            }, true);
        }
    }

    static void clearHiddenSelection(ShipControlTerminal o)
    {
        // On the star map the o.selection is a chart place, not a row on a list o.page.
        if (o.picker && chart(o))
            return;
        var entries = filtered(o);
        o.page = Math.clamp(o.page, 0, Math.max(0, (visibleCount(o) - 1) / PAGE_SIZE));
        if (entries.stream().skip(o.page * (long) PAGE_SIZE).limit(PAGE_SIZE).noneMatch(e -> e.id().equals(o.highlighted)))
            o.highlighted = "";
    }

    private DestinationPicker()
    {
    }
}
