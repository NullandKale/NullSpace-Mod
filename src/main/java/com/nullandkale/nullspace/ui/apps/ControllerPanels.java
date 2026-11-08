package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/** The controller's main panels: status, trip preview, manual steps and the More tab. */
final class ControllerPanels
{

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
        o.text(2, 5, "Going to", TerminalTheme.MUTED);
        String destination = ShipControlTerminal.clean(o.view.destination());
        o.text(12, 5, destination.substring(0, Math.min(52, destination.length())), TerminalTheme.AMBER);
        o.hit(66, 5, 12, "[ Change ]", () ->
        {
            o.picker = true;
            o.searchFocused = true;
            o.selection = 0;
            o.highlighted = o.view.selected();
        }, true);
        o.tab(2, 7, 8, "Trip", 0);
        o.tab(12, 7, 16, "Manual steps", 1);
        o.tab(30, 7, 8, "More", 2);
        o.text(17, 17, o.view.connection(), TerminalTheme.MUTED);
        if (o.tab == 0)
            drawTrip(o);
        else if (o.tab == 1)
            drawManual(o);
        else
            drawMore(o);
        o.button(2, 14, 20, "Fly manually", ShipControl.Action.PILOT);
        if (o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.INVESTIGATE))
            o.button(2, 13, 30, "Investigate signal", ShipControl.Action.INVESTIGATE);
        if (o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.RESUME))
            o.button(2, 13, 30, "Resume journey", ShipControl.Action.RESUME);
        o.button(24, 14, 22, "View ship", ShipControl.Action.CAMERA);
        o.button(48, 14, 28, "Close all doors", ShipControl.Action.CLOSE_DOORS);
        for (var a : List.of(ShipControl.Action.ACCEPT, ShipControl.Action.DECLINE, ShipControl.Action.CANCEL))
            if (o.view.buttons().stream().anyMatch(b -> b.action() == a && b.enabled()))
                o.button(a == ShipControl.Action.ACCEPT ? 27 : 52, 18, 23, a == ShipControl.Action.ACCEPT ? "Allow docking" : ShipControlTerminal.label(a), a);
    }

    static void drawTrip(ShipControlTerminal o)
    {
        String trip = o.view.details().lines().filter(l -> l.startsWith("Trip:") || l.startsWith("Already at")).findFirst().orElse("Choose a course, then Engage").replaceFirst("^Trip: ", "");
        var fuel = java.util.regex.Pattern.compile("Minimum fuel: ([0-9.]+)").matcher(o.view.details());
        if (fuel.find() && !fuel.group(1).equals("0.00"))
            trip += " - needs " + fuel.group(1) + " fuel";
        o.text(2, 8, trip, TerminalTheme.TEXT);
        o.button(2, 10, 20, "Engage", ShipControl.Action.ENGAGE);
        String blocker = o.view.buttons().stream().filter(b -> b.action() == ShipControl.Action.ENGAGE).map(ControllerPackets.Button::reason).findFirst().orElse("Choose a destination");
        if (!blocker.isEmpty())
            o.text(24, 10, blocker, TerminalTheme.AMBER);
        o.button(2, 11, 20, "Stop", ShipControl.Action.DISENGAGE);
    }

    static void drawManual(ShipControlTerminal o)
    {
        var travel = new ShipControl.Action[]{ShipControl.Action.LAUNCH, ShipControl.Action.JUMP, ShipControl.Action.LAND, ShipControl.Action.DOCK, ShipControl.Action.UNDOCK};
        String[] purposes = {"Leave this planet", "Go to another region", "Land at destination", "Connect to dock", "Leave connected dock"};
        for (int i = 0; i < travel.length; i++)
        {
            var action = travel[i];
            o.button(2, 8 + i, 13, ShipControlTerminal.label(action), action);
            String reason = o.view.buttons().stream().filter(b -> b.action() == action).map(ControllerPackets.Button::reason).findFirst().orElse("Unavailable");
            o.text(17, 8 + i, reason.isEmpty() ? purposes[i] : reason, reason.isEmpty() ? TerminalTheme.GREEN : TerminalTheme.MUTED);
        }
    }

    static void drawMore(ShipControlTerminal o)
    {
        o.button(2, 8, 28, "Loose blocks", ShipControl.Action.REVIEW_LOOSE_BLOCKS);
        if (o.view.buttons().stream().anyMatch(b -> b.action() == ShipControl.Action.CONFIRM_LEAVE_BLOCKS))
            o.button(33, 8, 31, "Confirm leave blocks", ShipControl.Action.CONFIRM_LEAVE_BLOCKS);
        o.button(2, 9, 28, "Check ship", ShipControl.Action.INSPECT);
        o.button(2, 10, 28, "Locate problem", ShipControl.Action.SHOW_PROBLEM);
        o.hit(2, 11, 28, o.details ? "[ Hide detail ]" : "[ Details ]", () ->
        {
            o.details = !o.details;
            o.statusScroll = 0;
            o.notice = "";
        }, true);
        o.button(2, 12, 28, "Advanced", ShipControl.Action.ADVANCED);
    }

    private ControllerPanels()
    {
    }
}
