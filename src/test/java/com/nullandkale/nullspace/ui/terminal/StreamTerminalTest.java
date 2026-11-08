package com.nullandkale.nullspace.ui.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamTerminalTest
{
    @Test
    void sendsTextControlAndNavigationWithoutControllerActions()
    {
        var received = new ArrayList<String>();
        var t = new StreamTerminal(bytes -> received.add(new String(bytes, StandardCharsets.UTF_8)));
        t.input(new TerminalInput.Text("hello"));
        t.input(TerminalInput.Key.UP);
        t.input(new TerminalInput.Control(3));
        t.input(TerminalInput.Key.ENTER);
        t.input(TerminalInput.Key.ESCAPE);
        assertEquals(java.util.List.of("hello", "\033[A", "\003", "\r", "\033"), received);
        t.input(new TerminalInput.Pointer(2, 3));
        assertEquals(5, received.size());
    }

    @Test
    void outputIsPersistentAndUsesAddressableCells()
    {
        var t = new StreamTerminal(bytes ->
        {});
        t.write("boot\r\n");
        t.write("\033[25;80H!");
        assertTrue(t.display().row(0).startsWith("boot"));
        assertEquals('!', t.display().cell(79, 24).glyph());
        assertSame(t.display(), t.display());
    }
}
