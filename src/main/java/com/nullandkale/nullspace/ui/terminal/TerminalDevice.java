package com.nullandkale.nullspace.ui.terminal;

/** A display/input port. Controller programs use it today; a future computer can implement it. */
public interface TerminalDevice
{
    TerminalBuffer display();

    void input(TerminalInput input);

    default boolean closeRequested()
    {
        return false;
    }

    /**
     * Where the mouse is, before each frame is drawn: {@code -1} when it is off the grid. A device that draws its own hover (and can say why a control is
     * unavailable) returns true; otherwise the screen lights the shaded control under the mouse.
     */
    default boolean hover(int column, int row)
    {
        return false;
    }

    /** Whether this device scrolls with {@link TerminalInput.Scroll}; otherwise the wheel pages with Page Up and Page Down. */
    default boolean scrolls()
    {
        return false;
    }
}
