package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.navigation.TripPresentation;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipControlTerminalTest
{
    private static final UUID SESSION = UUID.randomUUID();
    private static final TripPresentation READY = new TripPresentation(TripPresentation.Phase.READY, "Ready to travel", "Choose a destination, then Engage.");

    // ------------------------------------------------------------------------------------------------ the helm

    @Test
    void theHelmListsOnlyThePlacesHereAndOneClickChoosesWithoutMoving()
    {
        var t = new ShipControlTerminal(helm("", READY, buttons(Map.of(ShipControl.Action.ENGAGE, "Choose a destination"))));
        assertTrue(t.display().row(7).contains("NEARBY  places at Terra"));
        assertTrue(shows(t, "Terra Harbor") && shows(t, "Rivet Cargo Station"), "Pads and stations here are listed");
        assertFalse(shows(t, "Luna Landing") || shows(t, "Waypoint berth"), "Nothing elsewhere is listed on the helm, and berths are not rows");
        assertFalse(shows(t, "[ Change ]"), "There is no separate destination screen");
        assertTrue(t.display().row(14).contains("Click a place above"));
        assertTrue(click(t, "Terra Harbor"));
        assertEquals(new ControllerPackets.Input(SESSION, ShipControl.Action.SELECT, "pad"), t.pollAction());
        assertNull(t.pollAction(), "Choosing a place never starts travel");
        assertFalse(t.picker());
    }

    @Test
    void aChosenPlaceShowsTheTripAndEngageIsTheOnlyWayToGo()
    {
        var t = new ShipControlTerminal(helm("pad", READY, buttons(Map.of()), "Trip: Approach -> Land\nMinimum fuel: 0.50 + docking reserve"));
        assertTrue(t.display().row(14).contains("Terra Harbor") && t.display().row(14).contains("landing pad"));
        assertTrue(t.display().row(15).contains("[ Engage ]") && t.display().row(15).contains("Approach -> Land - needs 0.50 fuel"), t.display().row(15));
        assertTrue(click(t, "[ Engage ]"));
        assertEquals(ShipControl.Action.ENGAGE, t.pollAction().action());
    }

    @Test
    void theOrbitOfThePlaceHereReadsAsOrbitAndComesFirst()
    {
        var entries = new ArrayList<>(entries());
        entries.addFirst(new ControllerPackets.Entry("orbit", ShipDestination.Kind.REGION, "Terra orbit", "sol_terra", "Launch to orbit"));
        var t = new ShipControlTerminal(view(false, "", entries, READY, buttons(Map.of()), "", "Sol / Terra / Surface"));
        assertTrue(t.display().row(8).contains("Terra orbit") && t.display().row(8).contains("Orbit") && t.display().row(8).contains("Launch to orbit"), t.display().row(8));
        assertFalse(t.display().row(8).contains("Planet/moon"));
    }

    @Test
    void manyPlacesPageSixAtATimeWithTheArrowsOrTheWheel()
    {
        var entries = new ArrayList<ControllerPackets.Entry>();
        for (int i = 0; i < 10; i++)
            entries.add(new ControllerPackets.Entry("pad" + i, ShipDestination.Kind.PAD, "Pad " + i, "sol_terra", "READY"));
        var t = new ShipControlTerminal(view(false, "", entries, READY, buttons(Map.of()), "", "Sol / Terra / Surface"));
        assertTrue(t.display().row(13).contains("Pad 5") && t.display().row(7).contains("1/2"));
        assertTrue(click(t, "[ > ]"));
        assertTrue(t.display().row(8).contains("Pad 6") && t.display().row(11).contains("Pad 9"));
        t.input(new TerminalInput.Scroll(10, 9, 1));
        assertTrue(t.display().row(8).contains("Pad 0"), "The wheel over the list pages back");
    }

    @Test
    void whileTravellingTheTripReplacesTheListAndASignalIsOneClick()
    {
        for (var action : List.of(ShipControl.Action.INVESTIGATE, ShipControl.Action.RESUME))
        {
            var phase = action == ShipControl.Action.RESUME ? TripPresentation.Phase.PAUSED : TripPresentation.Phase.SIGNAL;
            var t = new ShipControlTerminal(helm("pad", new TripPresentation(phase, "FTL: 0:41 remaining", "Optional signal available."), buttons(Map.of(action, ""))));
            assertFalse(shows(t, "NEARBY"), "No other place can be chosen mid-journey");
            assertTrue(click(t, action == ShipControl.Action.RESUME ? "[ Continue journey ]" : "[ Visit signal (optional) ]"));
            assertEquals(action, t.pollAction().action());
        }
    }

    @Test
    void quietFtlShowsAutomaticArrivalWithoutAnotherEngage()
    {
        var t = new ShipControlTerminal(helm("pad", new TripPresentation(TripPresentation.Phase.CRUISING, "FTL: 0:32 remaining", "Stay aboard; you can move around."), buttons(Map.of(ShipControl.Action.ENGAGE, "Journey underway"))));
        assertTrue(t.display().row(8).contains("FTL: 0:32 remaining"));
        assertTrue(t.display().row(15).contains("arrival is automatic"));
        assertFalse(shows(t, "[ Engage ]") || shows(t, "Visit signal"));
    }

    @Test
    void aDockedShipWithNoCourseOffersUndock()
    {
        var t = new ShipControlTerminal(helm("", new TripPresentation(TripPresentation.Phase.CAPTURED, "Docked", "Open the dock door."), buttons(Map.of(ShipControl.Action.ENGAGE, "Choose a destination", ShipControl.Action.UNDOCK, ""))));
        assertTrue(click(t, "[ Undock ]"));
        assertEquals(ShipControl.Action.UNDOCK, t.pollAction().action());
    }

    @Test
    void manualStepsStayAvailableAndExplainWhatBlocksThem()
    {
        var t = new ShipControlTerminal(helm("pad", READY, buttons(Map.of(ShipControl.Action.LAND, "Jump to the destination's region first"))));
        assertTrue(click(t, "[ Manual steps ]"));
        String[] labels = {"Launch", "Jump", "Land", "Dock", "Undock"};
        for (int i = 0; i < labels.length; i++)
            assertTrue(t.display().row(7 + i).contains(labels[i]));
        assertTrue(t.display().row(9).contains("Jump to the destination"), "A blocker is readable before clicking");
        t.input(new TerminalInput.Pointer(3, 9));
        assertEquals(new ControllerPackets.Input(SESSION, ShipControl.Action.EXPLAIN, "LAND"), t.pollAction());
        assertTrue(t.status().contains("Jump to the destination"));
        t = new ShipControlTerminal(helm("pad", READY, buttons(Map.of())));
        assertTrue(click(t, "[ Manual steps ]"));
        t.input(new TerminalInput.Pointer(3, 7));
        assertEquals(ShipControl.Action.LAUNCH, t.pollAction().action());
        t.input(new TerminalInput.Pointer(3, 7));
        assertNull(t.pollAction(), "A pending action cannot be sent twice");
    }

    @Test
    void leavingLooseBlocksRequiresASeparateConfirmationFromTheServer()
    {
        var original = helm("pad", READY, buttons(Map.of()));
        var t = new ShipControlTerminal(original);
        assertTrue(click(t, "[ More ]"));
        assertTrue(click(t, "[ Details ]"));
        assertTrue(click(t, "[ Loose blocks ]"));
        assertEquals(ShipControl.Action.REVIEW_LOOSE_BLOCKS, t.pollAction().action());
        assertFalse(click(t, "[ Confirm leave blocks ]"), "Confirmation is not offered before the server's review");
        var reviewed = new ControllerPackets.View(SESSION, BlockPos.ZERO, false, false, "Ship", "Ready", "pad", "Terra Harbor", "", "3 loose blocks and their contents will stay here", original.entries(), List.of(new ControllerPackets.Button(ShipControl.Action.REVIEW_LOOSE_BLOCKS, ""), new ControllerPackets.Button(ShipControl.Action.CONFIRM_LEAVE_BLOCKS, "")), 1, "Sol / Terra / Orbit", "", "", List.of(), 25, READY, "sol_terra");
        t.update(reviewed);
        assertEquals(reviewed.message(), t.status(), "Details mode cannot hide the leave-behind warning");
        assertTrue(click(t, "[ Confirm leave blocks ]"));
        assertEquals(ShipControl.Action.CONFIRM_LEAVE_BLOCKS, t.pollAction().action());
    }

    @Test
    void longMessagesCanBeReadAndLiveUpdatesKeepTheScrollPosition()
    {
        var next = view(false, "pad", entries(), READY, buttons(Map.of()), "", "Sol / Terra / Orbit", "Fault details ".repeat(30) + " Location: 100, 65, -30");
        var t = new ShipControlTerminal(next);
        t.input(TerminalInput.Key.PAGE_DOWN);
        t.input(TerminalInput.Key.PAGE_DOWN);
        String lastPage = t.display().row(20) + t.display().row(21) + t.display().row(22);
        assertTrue(lastPage.contains("100, 65, -30"), lastPage);
        t.update(next);
        assertEquals(lastPage, t.display().row(20) + t.display().row(21) + t.display().row(22));
    }

    @Test
    void lastActionLabelDoesNotChangeTheStatusValue()
    {
        var t = new ShipControlTerminal(view(false, "", entries(), READY, buttons(Map.of()), "", "Sol / Terra / Orbit", "Ship ready"));
        assertEquals("Ship ready", t.status(), "Consumers read the action result without presentation labels");
        assertTrue(t.display().row(20).contains("Last action: Ship ready"));
    }

    @Test
    void theControlUnderTheMouseLightsAndAGreyOneSaysWhy()
    {
        var t = new ShipControlTerminal(helm("", READY, buttons(Map.of(ShipControl.Action.ENGAGE, "Choose a destination"))));
        int row = rowOf(t, "Terra Harbor");
        t.hover(10, row);
        assertEquals(TerminalTheme.SELECTED, t.display().cell(10, row).background(), "A list row lights under the mouse");
        t.hover(4, 15);
        assertTrue(t.display().row(19).contains("Engage: Choose a destination"), t.display().row(19));
        t.hover(-1, -1);
        assertFalse(t.display().row(19).contains("Engage:"));
    }

    // ------------------------------------------------------------------------------------------------ the Scanner

    @Test
    void theScannerListsTheSamePlacesHereFirstThenTheSystemThenTheStars()
    {
        var helm = new ShipControlTerminal(helm("", READY, buttons(Map.of())));
        var t = new ShipControlTerminal(scanner(""));
        assertTrue(t.display().row(3).contains("HERE  Terra"));
        for (var place : helm.nearby())
            assertTrue(shows(t, place.name()), "The Scanner lists what the helm lists here: " + place.name());
        assertTrue(shows(t, "THIS SYSTEM  Sol") && shows(t, "Luna") && shows(t, "OTHER STARS") && shows(t, "Star map"));
        assertFalse(shows(t, "Luna Landing"), "A planet's places stay closed until it is opened");
        assertFalse(shows(t, "Pelagos"), "Another star's planets are behind the star map");
    }

    @Test
    void aPlanetOpensInPlaceAndADoubleClickSetsAPlace()
    {
        var t = new ShipControlTerminal(scanner(""));
        assertTrue(click(t, "Luna"));
        assertTrue(shows(t, "Luna Landing") && shows(t, "Outpost"), "Its pads and stations are listed under it");
        assertFalse(shows(t, "Outpost A"), "Berths are not rows");
        int row = rowOf(t, "Luna Landing");
        t.input(new TerminalInput.Pointer(5, row));
        assertNull(t.pollAction(), "One click highlights");
        assertTrue(t.display().row(22).contains("Selected: Luna Landing"));
        t.input(new TerminalInput.Pointer(5, row, true));
        assertEquals(new ControllerPackets.Input(SESSION, ShipControl.Action.SELECT, "luna_pad"), t.pollAction());
        assertTrue(t.picker(), "The Scanner stays open; Engage is at the helm");
    }

    @Test
    void setDestinationSetsTheHighlightedPlace()
    {
        var t = new ShipControlTerminal(scanner(""));
        assertTrue(click(t, "Rivet Cargo Station"));
        assertTrue(click(t, "[ Set destination ]"));
        assertEquals(new ControllerPackets.Input(SESSION, ShipControl.Action.SELECT, "station"), t.pollAction());
    }

    @Test
    void aLongListScrollsWithTheWheel()
    {
        var entries = new ArrayList<>(scannerEntries());
        for (int i = 0; i < 30; i++)
            entries.add(new ControllerPackets.Entry("p" + i, ShipDestination.Kind.PAD, "Pad " + i, "sol_terra", "READY"));
        var t = new ShipControlTerminal(view(true, "", entries, READY, buttons(Map.of()), "", "Sol / Terra / Orbit"));
        assertFalse(shows(t, "OTHER STARS"));
        for (int i = 0; i < 10; i++)
            t.input(new TerminalInput.Scroll(10, 10, -1));
        assertTrue(shows(t, "OTHER STARS"), "Scrolling down reaches the end");
        assertTrue(t.display().row(21).contains("of"));
    }

    @Test
    void theStarMapOpensFromTheListAndChoosingAStarTwiceListsItsPlanets()
    {
        var chart = List.of(new ControllerPackets.ChartPlace("sol", "Sol", "System", 100, 100, "sol_terra", 0, 0, true, true, "G star", ""), new ControllerPackets.ChartPlace("feature_a", "Kel Nebula", "Nebula", 300, 100, "feature_a", 18.2, 2.82, true, false, "Nebula | stepping stone", ""), new ControllerPackets.ChartPlace("vega", "Vega", "System", 800, 900, "vega_pelagos", 61.0, 9.4, false, false, "A star | 1 planet", "3 hops via Kel Nebula, Tal Drift"));
        var entries = new ArrayList<>(scannerEntries());
        entries.add(new ControllerPackets.Entry("nebula", ShipDestination.Kind.REGION, "Kel Nebula / Kel Nebula", "feature_a", "Nebula"));
        var t = new ShipControlTerminal(new ControllerPackets.View(SESSION, BlockPos.ZERO, true, true, "Ship", "Ready", "", "None", "", "", entries, buttons(Map.of()), 0, "Sol / Terra / Orbit", "", "", chart, 25, READY, "sol_terra"));
        assertTrue(shows(t, "1 places within one jump"));
        assertTrue(click(t, "Star map"));
        assertTrue(t.display().row(0).contains("STAR MAP") && t.display().row(0).contains("Range 25 ly"));
        var map = t.display();
        for (int row = 3; row <= 20; row++)
            for (int col = 2; col < 78; col++)
                assertEquals(0xff000000, map.cell(col, row).background(), "The chart keeps its full width and 18 rows");
        assertTrue(click(t, "Kel Nebula"));
        assertTrue(t.display().row(21).contains("18.2 ly | jump 2.82 fuel | in range"));
        assertTrue(click(t, "[ Set destination ]"));
        assertEquals(new ControllerPackets.Input(SESSION, ShipControl.Action.SELECT, "nebula"), t.pollAction());
        t.update(t.state());
        assertTrue(click(t, "Vega"));
        assertTrue(t.display().row(21).contains("3 hops via Kel Nebula"), t.display().row(21));
        assertTrue(click(t, "Vega"));
        assertTrue(shows(t, "SYSTEM  Vega") && shows(t, "Pelagos"), "Choosing a star again lists its planets");
        assertTrue(click(t, "< Back to Sol"));
        assertTrue(shows(t, "HERE  Terra"));
    }

    @Test
    void filtersListPlacesFlatAndKeepStationsApartFromShips()
    {
        var entries = new ArrayList<>(scannerEntries());
        entries.add(new ControllerPackets.Entry("courier", ShipDestination.Kind.VESSEL, "Courier", "sol_luna", "Ship: 1/1 free"));
        var t = new ShipControlTerminal(view(true, "", entries, READY, buttons(Map.of()), "", "Sol / Terra / Orbit"));
        chooseView(t, "Stations");
        assertTrue(shows(t, "Outpost") && shows(t, "Rivet Cargo Station") && !shows(t, "Courier"));
        chooseView(t, "Ships");
        assertTrue(shows(t, "Courier") && !shows(t, "Outpost"));
        chooseView(t, "Docking ports");
        assertTrue(shows(t, "Outpost A") && shows(t, "Outpost B"), "Berths are listed when asked for");
    }

    @Test
    void anEmptySearchSaysSoAndSetsNothing()
    {
        var t = new ShipControlTerminal(scanner("pad"));
        t.searchFocused = true;
        t.input(new TerminalInput.Text("does not exist"));
        for (int i = 0; i < 20; i++)
        {
            t.input(TerminalInput.Key.DOWN);
            t.input(TerminalInput.Key.PAGE_DOWN);
        }
        assertTrue(t.display().row(3).contains("No matching destinations"));
        assertNull(t.pollAction());
    }

    @Test
    void viewMenuCanBeDismissedWithoutClickingThePlaceUnderneath()
    {
        var t = new ShipControlTerminal(scanner(""));
        assertTrue(click(t, "[ View:"));
        assertTrue(t.viewMenu);
        t.input(new TerminalInput.Pointer(3, 4));
        assertFalse(t.viewMenu);
        assertEquals("", t.highlighted);
        assertNull(t.pollAction());
        assertTrue(click(t, "[ View:"));
        t.input(TerminalInput.Key.ESCAPE);
        assertFalse(t.viewMenu);
    }

    @Test
    void aSelectionThatIsNotConfirmedCanBeRetried()
    {
        var original = scanner("");
        var t = new ShipControlTerminal(original);
        assertTrue(click(t, "Rivet Cargo Station"));
        assertTrue(click(t, "[ Set destination ]"));
        assertEquals("station", t.pollAction().target());
        for (int i = 0; i < 101; i++)
            t.tick();
        assertTrue(click(t, "Terra Harbor"));
        assertTrue(click(t, "[ Set destination ]"));
        assertEquals("pad", t.pollAction().target(), "A missing reply must not lock the Scanner");
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private static List<ControllerPackets.Entry> entries()
    {
        return List.of(new ControllerPackets.Entry("pad", ShipDestination.Kind.PAD, "Terra Harbor", "sol_terra", "READY"), new ControllerPackets.Entry("station", ShipDestination.Kind.VESSEL, "Rivet Cargo Station", "sol_terra", "Station: 2/4 free"));
    }

    private static List<ControllerPackets.Entry> scannerEntries()
    {
        var out = new ArrayList<>(entries());
        out.add(new ControllerPackets.Entry("luna", ShipDestination.Kind.REGION, "Sol / Luna", "sol_luna", "Barren world"));
        out.add(new ControllerPackets.Entry("luna_pad", ShipDestination.Kind.PAD, "Luna Landing", "sol_luna", "Charted"));
        out.add(new ControllerPackets.Entry("outpost", ShipDestination.Kind.VESSEL, "Outpost", "sol_luna", "Station: 2/2 free"));
        out.add(new ControllerPackets.Entry("berth1", ShipDestination.Kind.PORT, "Outpost A", "sol_luna", "Available"));
        out.add(new ControllerPackets.Entry("berth2", ShipDestination.Kind.PORT, "Outpost B", "sol_luna", "Available"));
        out.add(new ControllerPackets.Entry("pelagos", ShipDestination.Kind.REGION, "Vega / Pelagos", "vega_pelagos", "Ocean world"));
        return out;
    }

    private static ControllerPackets.View helm(String selected, TripPresentation trip, List<ControllerPackets.Button> buttons)
    {
        return helm(selected, trip, buttons, "");
    }

    private static ControllerPackets.View helm(String selected, TripPresentation trip, List<ControllerPackets.Button> buttons, String details)
    {
        // What the server sends the helm: only this place's rows (the Luna pad here would never be sent; it is listed to prove the filter).
        var entries = new ArrayList<>(entries());
        entries.add(new ControllerPackets.Entry("luna_pad", ShipDestination.Kind.PAD, "Luna Landing", "sol_luna", "Charted"));
        entries.add(new ControllerPackets.Entry("port", ShipDestination.Kind.PORT, "Waypoint berth", "sol_terra", "Available"));
        return view(false, selected, entries, trip, buttons, details, "Sol / Terra / Orbit");
    }

    private static ControllerPackets.View scanner(String selected)
    {
        return view(true, selected, scannerEntries(), READY, buttons(Map.of()), "", "Sol / Terra / Orbit");
    }

    private static ControllerPackets.View view(boolean scanner, String selected, List<ControllerPackets.Entry> entries, TripPresentation trip, List<ControllerPackets.Button> buttons, String details, String here)
    {
        return view(scanner, selected, entries, trip, buttons, details, here, "");
    }

    private static ControllerPackets.View view(boolean scanner, String selected, List<ControllerPackets.Entry> entries, TripPresentation trip, List<ControllerPackets.Button> buttons, String details, String here, String message)
    {
        String destination = entries.stream().filter(e -> e.id().equals(selected)).map(ControllerPackets.Entry::name).findFirst().orElse("Choose destination");
        return new ControllerPackets.View(SESSION, BlockPos.ZERO, scanner, true, "Ship", "Fuel: loaded | Controls: online", selected, destination, "", message, entries, buttons, 0, here, "", details, List.of(), 25, trip, "sol_terra");
    }

    /** The buttons the server sends a ship screen; Visit signal and Continue journey only when given a reason (empty: enabled). */
    private static List<ControllerPackets.Button> buttons(Map<ShipControl.Action, String> reasons)
    {
        var out = new ArrayList<ControllerPackets.Button>();
        for (var a : ShipControl.Action.values())
        {
            if (Set.of(ShipControl.Action.CONFIRM_LEAVE_BLOCKS, ShipControl.Action.MARK, ShipControl.Action.RETURN_TO_CONTROLLER, ShipControl.Action.EXPLAIN).contains(a))
                continue;
            if ((a == ShipControl.Action.INVESTIGATE || a == ShipControl.Action.RESUME) && !reasons.containsKey(a))
                continue;
            String fallback = Set.of(ShipControl.Action.ACCEPT, ShipControl.Action.DECLINE, ShipControl.Action.CANCEL, ShipControl.Action.UNDOCK, ShipControl.Action.DISENGAGE).contains(a) ? "Not available" : "";
            out.add(new ControllerPackets.Button(a, reasons.getOrDefault(a, fallback)));
        }
        return out;
    }

    /** Clicks the first control showing this label, wherever the layout puts it. */
    private static boolean click(ShipControlTerminal t, String label)
    {
        int row = rowOf(t, label);
        if (row < 0)
            return false;
        t.input(new TerminalInput.Pointer(t.display().row(row).indexOf(label) + 1, row));
        return true;
    }

    private static int rowOf(ShipControlTerminal t, String label)
    {
        var display = t.display();
        for (int row = 0; row < TerminalBuffer.ROWS; row++)
            if (display.row(row).contains(label))
                return row;
        return -1;
    }

    private static boolean shows(ShipControlTerminal t, String label)
    {
        return rowOf(t, label) >= 0;
    }

    private static void chooseView(ShipControlTerminal t, String name)
    {
        assertTrue(click(t, "[ View:"));
        for (int row = 3; row < 9; row++)
            if (t.display().row(row).substring(50).contains(name))
            {
                t.input(new TerminalInput.Pointer(52, row));
                return;
            }
        fail("Missing view " + name);
    }
}
