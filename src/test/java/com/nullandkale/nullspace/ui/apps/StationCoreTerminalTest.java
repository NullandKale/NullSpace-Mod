package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationCoreTerminalTest
{
    @Test
    void stationNameAndInvitedPlayerRemainSeparateAcrossLiveReadings()
    {
        var terminal = new StationCoreTerminal("Station", List.of());
        terminal.input(new TerminalInput.Pointer(10, 16));
        terminal.input(new TerminalInput.Text("Builder"));
        terminal.input(new TerminalInput.Pointer(8, 3));
        terminal.input(TerminalInput.Key.SELECT_ALL);
        terminal.input(new TerminalInput.Text("Ship Hub"));
        terminal.refresh("Station\nPower: 8 / 4");
        terminal.input(new TerminalInput.Pointer(60, 3));
        assertEquals(new ControllerTerminal.Action("station_rename", "Ship Hub"), terminal.pollAction());
        terminal.acknowledged();
        terminal.input(new TerminalInput.Pointer(55, 16));
        assertEquals(new ControllerTerminal.Action("station_invite", "Builder"), terminal.pollAction());
    }

    @Test
    void occupiedRoomCannotQueueAReplacementAndSelectingAnEmptyTileCostsNothing()
    {
        var terminal = new StationCoreTerminal("Station", List.of(new TerminalPackets.Destination("0_0", "ARRIVAL", "ROOM")));
        terminal.input(new TerminalInput.Pointer(4, 12));
        assertNull(terminal.pollAction());
        assertTrue(terminal.status().contains("empty room tile"));
        terminal.input(new TerminalInput.Pointer(40, 6));
        assertNull(terminal.pollAction());
        terminal.input(new TerminalInput.Pointer(4, 12));
        assertEquals("station_module", terminal.pollAction().verb());
        terminal.input(new TerminalInput.Pointer(43, 12));
        assertNull(terminal.pollAction());
        assertTrue(terminal.status().contains("empty station"));
    }
}
