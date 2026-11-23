package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.navigation.TripPresentation;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/**
 * The helm's panels. The Trip tab lists the places here (the same rows the Scanner lists first): one click chooses a place, Engage goes. While a trip
 * runs the list gives way to the trip, which cannot change mid-journey. Layout, by row: 1-3 ship and state, 5 tabs, 7-13 places or trip, 14 destination,
 * 15 the action, 16 a hint or a docking request, 18 the controls on every tab, 19 why the control under the mouse is unavailable, 20-22 messages.
 */
final class ControllerPanels
{
    /** Places shown at once on the Trip tab, rows 8 to 13. */
    static final int NEARBY_ROWS = 6;

    /** "Fuel: loaded | Controls: online | ..." as separate labeled items colored by state. */
    static void statusItems(ShipControlTerminal o, int y, String summary)
    {
        if (!summary.contains(": "))
        {
            o.text(2, y, summary, TerminalTheme.AMBER);
            return;
        }
        int x = 2;
        for (String part : summary.split(" \\| "))
        {
            int split = part.indexOf(": ");
            if (split < 0)
                continue;
            String label = part.substring(0, split), value = part.substring(split + 2);
            // Compact so all four items fit: "Air here: safe here" -> "Air safe",
            // "Controls: no controls power" -> "Controls no power".
            if (label.endsWith(" here"))
            {
                label = label.substring(0, label.length() - 5);
                value = value.replace(" here", "");
            }
            value = value.replace("no " + label.toLowerCase(Locale.ROOT) + " ", "no ");
            boolean good = Set.of("loaded", "online", "ready", "safe").contains(value);
            if (x + label.length() + value.length() + 2 > 78)
                break;
            o.text(x, y, label, TerminalTheme.MUTED);
            x += label.length() + 1;
            o.text(x, y, value, good ? TerminalTheme.GREEN : TerminalTheme.RED);
            x += value.length() + 3;
        }
    }

    static void drawController(ShipControlTerminal o)
    {
        o.text(2, 1, "SHIP CONTROLLER", TerminalTheme.BLUE);
        o.text(19, 1, o.view.ship(), TerminalTheme.MUTED);
        o.text(2, 2, "Here", TerminalTheme.MUTED);
        o.text(12, 2, o.view.here(), TerminalTheme.TEXT);
        statusItems(o, 3, o.view.summary());
        o.tab(2, 5, 8, "Trip", 0);
        o.tab(12, 5, 16, "Manual steps", 1);
        o.tab(30, 5, 8, "More", 2);
        if (o.tab == 0)
            drawTrip(o);
        else if (o.tab == 1)
            drawManual(o);
        else
            drawMore(o);
        o.text(2, 14, "Going to", TerminalTheme.MUTED);
        String destination = o.view.selected().isEmpty() ? (o.tab == 0 ? "Click a place above" : "Choose a place on the Trip tab") : ShipControlTerminal.clean(o.view.destination()) + kindLabel(o);
        o.text(12, 14, destination.substring(0, Math.min(66, destination.length())), TerminalTheme.AMBER);
        boolean request = false;
        for (var a : List.of(ShipControl.Action.ACCEPT, ShipControl.Action.DECLINE, ShipControl.Action.CANCEL))
            if (enabled(o, a))
            {
                o.button(a == ShipControl.Action.ACCEPT ? 2 : a == ShipControl.Action.DECLINE ? 27 : 52, 16, 23, a == ShipControl.Action.ACCEPT ? "Allow docking" : ShipControlTerminal.label(a), a);
                request = true;
            }
        // With no fuel to brake, a pad here can still be reached the hard way: a crash landing, confirmed by a second press.
        if (!request && o.tab == 0 && enabled(o, ShipControl.Action.EMERGENCY_LAND))
        {
            o.hit(2, 16, 24, "[ Emergency landing ]", () -> o.command(ShipControl.Action.EMERGENCY_LAND), true, "", false, TerminalTheme.RED);
            o.text(28, 16, "No fuel to brake: the ship will crash on the pad", TerminalTheme.RED);
            request = true;
        }
        if (!request && o.tab != 0 && !o.view.connection().isBlank())
            o.text(2, 16, o.view.connection(), TerminalTheme.MUTED);
        else if (!request && o.tab == 0 && listing(o))
            o.text(2, 16, "Farther places (other planets, other stars): open the Scanner", TerminalTheme.MUTED);
        o.button(2, 18, 18, "Fly manually", ShipControl.Action.PILOT);
        o.button(22, 18, 15, "View ship", ShipControl.Action.CAMERA);
        o.button(39, 18, 21, "Close all doors", ShipControl.Action.CLOSE_DOORS);
    }

    /** " (landing pad)" for the chosen place, when it is listed here. */
    private static String kindLabel(ShipControlTerminal o)
    {
        return o.view.entries().stream().filter(e -> e.id().equals(o.view.selected())).findFirst().map(e -> "  (" + kind(o, e).toLowerCase(Locale.ROOT) + ")").orElse("");
    }

    /** How a place is labelled: the orbit of the place the ship is at reads "Orbit", not "Planet/moon". */
    static String kind(ShipControlTerminal o, ControllerPackets.Entry e)
    {
        if (e.kind() == ShipDestination.Kind.REGION && e.region().equals(o.view.hereRegion()))
            return "Orbit";
        if (e.kind() == ShipDestination.Kind.VESSEL)
            return e.state().startsWith("Station") ? "Station" : "Ship";
        return ShipControlTerminal.kind(e.kind());
    }

    /** One place: name, kind and state in fixed columns. */
    static String row(ShipControlTerminal o, ControllerPackets.Entry e, String indent, boolean chosen)
    {
        return (chosen ? "> " : "  ") + indent + String.format(Locale.ROOT, "%-" + (36 - indent.length()) + "." + (36 - indent.length()) + "s %-13.13s %s", e.name(), kind(o, e), ShipControlTerminal.state(e.state()));
    }

    /** Whether the Trip tab lists places: when the ship is ready to choose, not while a trip or a paid journey runs. */
    static boolean listing(ShipControlTerminal o)
    {
        var phase = o.view.trip().phase();
        boolean journey = o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.RESUME || b.action() == ShipControl.Action.INVESTIGATE);
        return !journey && Set.of(TripPresentation.Phase.READY, TripPresentation.Phase.COMPLETE, TripPresentation.Phase.CAPTURED, TripPresentation.Phase.PAUSED).contains(phase);
    }

    static void drawTrip(ShipControlTerminal o)
    {
        if (listing(o))
            drawNearby(o);
        else
            drawJourney(o);
        drawAction(o);
    }

    /** Rows 7-13: the places here, a page at a time. */
    static void drawNearby(ShipControlTerminal o)
    {
        var places = o.nearby();
        int pages = Math.max(1, (places.size() + NEARBY_ROWS - 1) / NEARBY_ROWS);
        o.nearbyPage = Math.clamp(o.nearbyPage, 0, pages - 1);
        o.text(2, 7, "NEARBY  places at " + o.herePlace(), TerminalTheme.MUTED);
        if (pages > 1)
        {
            o.hit(58, 7, 7, "[ < ]", () -> o.nearbyPage--, o.nearbyPage > 0);
            o.text(66, 7, (o.nearbyPage + 1) + "/" + pages, TerminalTheme.MUTED);
            o.hit(71, 7, 7, "[ > ]", () -> o.nearbyPage++, o.nearbyPage < pages - 1);
        }
        if (places.isEmpty())
            o.text(4, 8, "Nothing to travel to here. Open the Scanner for farther places.", TerminalTheme.MUTED);
        for (int i = 0; i < NEARBY_ROWS && o.nearbyPage * NEARBY_ROWS + i < places.size(); i++)
        {
            var e = places.get(o.nearbyPage * NEARBY_ROWS + i);
            boolean chosen = e.id().equals(o.view.selected()) || e.id().equals(o.pendingTarget);
            o.hit(2, 8 + i, 76, row(o, e, "", chosen), () -> o.choose(e.id()), true, "", true, chosen ? TerminalTheme.BLUE : TerminalTheme.TEXT, chosen ? TerminalTheme.SELECTED : TerminalTheme.BACKGROUND, () -> o.choose(e.id()));
        }
    }

    /** Rows 7-13 while travelling: where to, how long, and an optional signal. */
    static void drawJourney(ShipControlTerminal o)
    {
        var trip = o.view.trip();
        o.text(2, 7, "TRIP", TerminalTheme.MUTED);
        o.text(2, 8, trip.title(), TerminalTheme.TEXT);
        o.text(2, 9, trip.hint(), TerminalTheme.MUTED);
        if (enabled(o, ShipControl.Action.INVESTIGATE))
        {
            String signal = o.view.message().lines().filter(l -> l.startsWith("Signal") || l.startsWith("Distress")).findFirst().orElse("A signal is in range.");
            o.text(2, 11, signal, TerminalTheme.AMBER);
            o.button(2, 12, 30, "Visit signal (optional)", ShipControl.Action.INVESTIGATE);
        }
    }

    /** Row 15: Engage (or Continue journey, or Undock), with the trip it would run or what blocks it. */
    static void drawAction(ShipControlTerminal o)
    {
        var phase = o.view.trip().phase();
        boolean resume = o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.RESUME);
        boolean undock = enabled(o, ShipControl.Action.UNDOCK) && !enabled(o, ShipControl.Action.RESUME) && !enabled(o, ShipControl.Action.ENGAGE);
        if (phase == TripPresentation.Phase.ARRIVAL_READY)
            o.text(2, 15, "No new travel action is needed", TerminalTheme.AMBER);
        else if (phase.automatic())
            o.text(2, 15, phase == TripPresentation.Phase.CRUISING || phase == TripPresentation.Phase.SIGNAL ? "Travelling; arrival is automatic" : "In progress; no action needed", TerminalTheme.GREEN);
        else if (undock && o.view.selected().isEmpty())
            o.button(2, 15, 14, "Undock", ShipControl.Action.UNDOCK);
        else
        {
            var action = resume ? ShipControl.Action.RESUME : ShipControl.Action.ENGAGE;
            o.button(2, 15, resume ? 22 : 14, resume ? "Continue journey" : "Engage", action);
            String reason = o.view.buttons().stream().filter(b -> b.action() == action).map(ControllerPackets.Button::reason).findFirst().orElse("Unavailable");
            int x = resume ? 26 : 18;
            if (!reason.isEmpty())
                o.text(x, 15, reason.substring(0, Math.min(reason.length(), 62 - x)), TerminalTheme.AMBER);
            else
                o.text(x, 15, preview(o), TerminalTheme.TEXT);
        }
        if (enabled(o, ShipControl.Action.DISENGAGE))
            o.button(64, 15, 14, "Stop", ShipControl.Action.DISENGAGE);
    }

    /** "Approach -> Land - needs 0.50 fuel", from the server's trip preview. */
    static String preview(ShipControlTerminal o)
    {
        String trip = o.view.details().lines().filter(l -> l.startsWith("Trip:") || l.startsWith("Already at")).findFirst().orElse("").replaceFirst("^Trip: ", "");
        var fuel = java.util.regex.Pattern.compile("Minimum fuel: ([0-9.]+)").matcher(o.view.details());
        if (fuel.find() && !fuel.group(1).equals("0.00"))
            trip += " - needs " + fuel.group(1) + " fuel";
        return trip.length() > 44 ? trip.substring(0, 44) : trip;
    }

    private static boolean enabled(ShipControlTerminal o, ShipControl.Action action)
    {
        return o.view.buttons().stream().anyMatch(b -> b.action() == action && b.enabled());
    }

    static void drawManual(ShipControlTerminal o)
    {
        var travel = new ShipControl.Action[]{ShipControl.Action.LAUNCH, ShipControl.Action.JUMP, ShipControl.Action.LAND, ShipControl.Action.DOCK, ShipControl.Action.UNDOCK};
        String[] purposes = {"Leave this planet", "Go to another region", "Fly close and land at the destination", "Connect to dock", "Leave connected dock"};
        for (int i = 0; i < travel.length; i++)
        {
            var action = travel[i];
            o.button(2, 7 + i, 13, ShipControlTerminal.label(action), action);
            String reason = o.view.buttons().stream().filter(b -> b.action() == action).map(ControllerPackets.Button::reason).findFirst().orElse("Unavailable");
            o.text(17, 7 + i, reason.isEmpty() ? purposes[i] : reason, reason.isEmpty() ? TerminalTheme.GREEN : TerminalTheme.MUTED);
        }
    }

    static void drawMore(ShipControlTerminal o)
    {
        o.button(2, 7, 28, "Loose blocks", ShipControl.Action.REVIEW_LOOSE_BLOCKS);
        if (o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.CONFIRM_LEAVE_BLOCKS))
            o.button(33, 7, 31, "Confirm leave blocks", ShipControl.Action.CONFIRM_LEAVE_BLOCKS);
        o.button(2, 8, 28, "Check ship", ShipControl.Action.INSPECT);
        o.button(2, 9, 28, "Locate problem", ShipControl.Action.SHOW_PROBLEM);
        o.hit(2, 10, 28, o.details ? "[ Hide detail ]" : "[ Details ]", () ->
        {
            o.details = !o.details;
            o.statusScroll = 0;
            o.notice = "";
        }, true);
        o.button(2, 11, 28, "Advanced", ShipControl.Action.ADVANCED);
    }

    private ControllerPanels()
    {
    }
}
