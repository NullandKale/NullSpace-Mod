package com.nullandkale.nullspace.ui.terminal;

public interface ControllerApplication extends TerminalDevice
{
    ControllerTerminal.Action pollAction();

    void update(String status);

    default void rejected(String reason)
    {
        update(reason);
    }

    default void refresh(String status)
    {
        update(status);
    }

    default void tick()
    {
    }

    default void acknowledged()
    {
    }

    int selection();

    String status();

    String fieldValue();
}
