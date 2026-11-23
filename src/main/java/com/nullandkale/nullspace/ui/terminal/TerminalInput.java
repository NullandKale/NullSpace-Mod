package com.nullandkale.nullspace.ui.terminal;

/** Physical keys and committed text are separate; never infer text from key codes. */
public sealed interface TerminalInput
{
    enum Key implements TerminalInput
    {
        UP, DOWN, LEFT, RIGHT, HOME, END, BACKSPACE, DELETE, ENTER, PAGE_UP, PAGE_DOWN, TAB, SELECT_ALL, ESCAPE
    }

    record Control(int code) implements TerminalInput
    {
        public Control
        {
            if (code < 0 || code > 31)
                throw new IllegalArgumentException("Control code must be 0..31");
        }
    }

    record Text(String text) implements TerminalInput
    {}

    /** A left click on a cell; {@code twice} when it completes a double click (the first click has already arrived on its own). */
    record Pointer(int column, int row, boolean twice) implements TerminalInput
    {
        public Pointer(int column, int row)
        {
            this(column, row, false);
        }
    }

    /** The mouse wheel over a cell: positive {@code steps} scroll toward the top. */
    record Scroll(int column, int row, int steps) implements TerminalInput
    {}
}
