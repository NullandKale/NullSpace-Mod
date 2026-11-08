package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import com.nullandkale.nullspace.ui.terminal.TerminalDevice;
import com.nullandkale.nullspace.ui.terminal.TerminalField;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.*;

/** One course action and a destination browser organized by system and planet. */
public final class ShipControlTerminal implements TerminalDevice
{
    // Shared terminal palette: shaded backgrounds only on things that can be clicked.
    private static final int CANVAS = TerminalTheme.BACKGROUND, SURFACE = TerminalTheme.PANEL, FOCUS = TerminalTheme.SELECTED;

    record Hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason)
    {}

    final TerminalBuffer display = new TerminalBuffer();
    private final ArrayDeque<ControllerPackets.Input> actions = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    ControllerPackets.View view;
    boolean picker;
    final TerminalField search = new TerminalField("", 64);
    String highlighted = "", filter = "All";
    boolean searchFocused = true, pending, close, details;
    private int pendingTicks;
    String pendingTarget = "";
    private int pendingResponse;
    int selection, page, statusScroll;
    String notice = "";
    int tab; // 0 Trip, 1 Manual steps, 2 More
    String scopeSystem = "", scopeRegion = "", chartSelected = "";

    public ShipControlTerminal(ControllerPackets.View view)
    {
        this.view = view;
        picker = view.scanner();
        highlighted = view.selected();
        if (!view.here().isBlank())
        {
            scopeSystem = view.here().split(" / ")[0];
            String body = view.here().split(" / ").length > 1 ? view.here().split(" / ")[1] : "";
            scopeRegion = view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.name().equals(scopeSystem + " / " + body)).map(ControllerPackets.Entry::region).findFirst().orElse("");
        }
    }

    public void update(ControllerPackets.View next)
    {
        boolean wasBrowser = browser();
        if (!view.message().equals(next.message()) || !view.buttons().equals(next.buttons()))
        {
            statusScroll = 0;
            if (!pending || next.response() > pendingResponse)
                notice = "";
        }
        if (pending && next.response() > pendingResponse)
        {
            pending = false;
            if (!pendingTarget.isBlank() && next.selected().equals(pendingTarget) && !view.scanner())
            {
                picker = false;
                selection = 0;
            }
            pendingTarget = "";
        }
        view = next;
        if (!view.scanner() && wasBrowser != browser())
        {
            picker = browser();
            selection = 0;
        }
        DestinationPicker.clearHiddenSelection(this);
    }

    public ControllerPackets.View state()
    {
        return view;
    }

    public int selection()
    {
        return selection;
    }

    public String status()
    {
        return notice.isEmpty() ? (details ? view.details() : view.message()) : notice;
    }

    public boolean picker()
    {
        return picker;
    }

    boolean browser()
    {
        return view.scanner();
    }

    public ControllerPackets.Input pollAction()
    {
        return actions.pollFirst();
    }

    void send(ShipControl.Action action, String target)
    {
        if (pending)
            return;
        notice = "Waiting for server...";
        pending = true;
        pendingTicks = 0;
        pendingResponse = view.response();
        actions.add(new ControllerPackets.Input(view.session(), action, target));
    }

    void command(ShipControl.Action action)
    {
        if (action == ShipControl.Action.REVIEW_LOOSE_BLOCKS || action == ShipControl.Action.CONFIRM_LEAVE_BLOCKS)
        {
            details = false;
            statusScroll = 0;
        }
        var button = view.buttons().stream().filter(b -> b.action() == action).findFirst();
        if (button.isPresent() && button.get().enabled())
            send(action, "");
        else
        {
            send(ShipControl.Action.EXPLAIN, action.name());
            notice = label(action) + " blocked: " + button.map(ControllerPackets.Button::reason).orElse("Unavailable");
            statusScroll = 0;
        }
    }

    private void line(int y, String text, int color)
    {
        display.put(2, y, clean(text).substring(0, Math.min(76, clean(text).length())), color, CANVAS);
    }

    static String clean(String text)
    {
        return text.replaceAll("[^ -~]", "?");
    }

    void hit(int x, int y, int width, String text, Runnable run, boolean enabled)
    {
        hit(x, y, width, text, run, enabled, "");
    }

    void hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason)
    {
        int color = text.contains("[ Engage ]") || text.contains("[ Set destination ]") ? TerminalTheme.GREEN : TerminalTheme.TEXT;
        hit(x, y, width, text, run, enabled, reason, false, color);
    }

    /** A clickable area. Plain hits (list rows, categories) get no bar unless focused. */
    void hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason, boolean plain, int color)
    {
        int index = hits.size();
        hits.add(new Hit(x, y, width, text, run, enabled, reason));
        String label = clean(text);
        label = label.substring(0, Math.min(width, label.length()));
        display.put(x, y, label + " ".repeat(width - label.length()), enabled ? color : TerminalTheme.MUTED, selection == index ? FOCUS : plain ? CANVAS : SURFACE);
    }

    void button(int x, int y, int width, String text, ShipControl.Action action)
    {
        String reason = view.buttons().stream().filter(b -> b.action() == action).map(ControllerPackets.Button::reason).findFirst().orElse("Unavailable");
        hit(x, y, width, "[ " + text + " ]", () -> command(action), reason.isEmpty(), reason);
    }

    @Override
    public TerminalBuffer display()
    {
        display.reset();
        display.cursorVisible(false);
        hits.clear();
        for (int y = 0; y < 25; y++)
            display.put(0, y, " ".repeat(80), TerminalTheme.TEXT, CANVAS);
        if (picker)
            DestinationPicker.drawPicker(this);
        else
            ControllerPanels.drawController(this);
        var messages = wrapped(status());
        statusScroll = Math.clamp(statusScroll, 0, Math.max(0, messages.size() - 3));
        int first = picker ? 0 : statusScroll;
        if (!picker && messages.size() > 3)
            line(19, "More: PgUp / PgDn", TerminalTheme.MUTED);
        for (int row = 0; row < (picker ? 1 : 3) && first + row < messages.size(); row++)
            line((picker ? 23 : 20) + row, messages.get(first + row), TerminalTheme.TEXT);
        hit(66, 24, 12, "[ Close ]", () -> close = true, true);
        selection = Math.clamp(selection, 0, Math.max(0, hits.size() - 1));
        return display;
    }

    /** Writes plain, non-interactive text at a column; no background bar. */
    void text(int x, int y, String text, int color)
    {
        String value = clean(text);
        display.put(x, y, value.substring(0, Math.min(78 - x, value.length())), color, CANVAS);
    }

    void tab(int x, int y, int width, String name, int index)
    {
        hit(x, y, width, "[ " + name + " ]", () ->
        {
            tab = index;
            statusScroll = 0;
        }, true, "", false, tab == index ? TerminalTheme.BLUE : TerminalTheme.MUTED);
    }

    static String state(String state)
    {
        var berths = java.util.regex.Pattern.compile("^Station: (\\d+)/\\d+ free$").matcher(state);
        if (berths.find())
            return berths.group(1) + " berths free";
        return state.equals(state.toUpperCase(Locale.ROOT)) && state.length() > 1 ? state.charAt(0) + state.substring(1).toLowerCase(Locale.ROOT) : state;
    }

    private static List<String> wrapped(String message)
    {
        var lines = new ArrayList<String>();
        for (String part : clean(message.replace('\n', ' ')).split(" "))
        {
            if (part.isEmpty())
                continue;
            if (lines.isEmpty() || lines.getLast().length() + part.length() + 1 > 76)
            {
                while (part.length() > 76)
                {
                    lines.add(part.substring(0, 76));
                    part = part.substring(76);
                }
                lines.add(part);
            }
            else
                lines.set(lines.size() - 1, lines.getLast() + " " + part);
        }
        return lines;
    }

    static String label(ShipControl.Action action)
    {
        if (action == ShipControl.Action.REVIEW_LOOSE_BLOCKS)
            return "Review loose blocks";
        if (action == ShipControl.Action.CONFIRM_LEAVE_BLOCKS)
            return "Confirm leave blocks";
        String name = action.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Override
    public void input(TerminalInput input)
    {
        display();
        if (input instanceof TerminalInput.Pointer p)
        {
            searchFocused = picker && p.row() == 3 && p.column() >= 10 && p.column() < 68;
            if (searchFocused)
            {
                search.click(p.column() - 10);
                return;
            }
            for (int i = 0; i < hits.size(); i++)
            {
                var hit = hits.get(i);
                if (p.row() == hit.y && p.column() >= hit.x && p.column() < hit.x + hit.width)
                {
                    selection = i;
                    if (hit.enabled)
                        hit.run.run();
                    else if (!hit.reason.isEmpty())
                        hit.run.run();
                    break;
                }
            }
        }
        else if (input instanceof TerminalInput.Text && picker && searchFocused)
        {
            search.input(input);
            page = 0;
            highlighted = "";
        }
        else if (input instanceof TerminalInput.Key key)
        {
            if (picker && searchFocused && Set.of(TerminalInput.Key.LEFT, TerminalInput.Key.RIGHT, TerminalInput.Key.HOME, TerminalInput.Key.END, TerminalInput.Key.SELECT_ALL, TerminalInput.Key.BACKSPACE, TerminalInput.Key.DELETE).contains(key))
            {
                search.input(input);
                page = 0;
                highlighted = "";
                return;
            }
            if (picker && searchFocused && (key == TerminalInput.Key.TAB || key == TerminalInput.Key.DOWN || key == TerminalInput.Key.ENTER))
            {
                searchFocused = false;
                for (int i = 0; i < hits.size(); i++)
                    if (hits.get(i).y == 7)
                    {
                        selection = i;
                        break;
                    }
                return;
            }
            switch (key)
            {
                case UP, LEFT -> selection = Math.floorMod(selection - 1, hits.size());
                case DOWN, RIGHT, TAB -> selection = (selection + 1) % hits.size();
                case ENTER ->
                {
                    var hit = hits.get(selection);
                    if (hit.enabled)
                        hit.run.run();
                    else if (!hit.reason.isEmpty())
                        hit.run.run();
                }
                case ESCAPE ->
                {
                    if (picker && !view.scanner())
                    {
                        picker = false;
                        selection = 0;
                    }
                }
                case PAGE_UP ->
                {
                    if (picker)
                    {
                        page = Math.max(0, page - 1);
                        highlighted = "";
                    }
                    else
                        statusScroll = Math.max(0, statusScroll - 3);
                }
                case PAGE_DOWN ->
                {
                    if (picker)
                    {
                        page++;
                        highlighted = "";
                    }
                    else
                        statusScroll = Math.min(Math.max(0, wrapped(status()).size() - 3), statusScroll + 3);
                }
                default ->
                    {
                    }
            }
        }
    }

    public void reopened(ControllerPackets.View next)
    {
        pending = false;
        pendingTarget = "";
        close = false;
        notice = "";
        view = next;
        DestinationPicker.clearHiddenSelection(this);
    }

    public boolean closeRequested()
    {
        return close;
    }

    public void tick()
    {
        if (pending && ++pendingTicks > 100)
            notice = "No reply yet. Close and reopen these controls to check the action.";
    }

    static String kind(ShipDestination.Kind kind)
    {
        return switch (kind)
        {
            case REGION -> "Planet/moon";
            case PAD -> "Landing pad";
            case PORT -> "Dock";
            case VESSEL -> "Station/ship";
        };
    }

    String region(String id)
    {
        return view.entries().stream().filter(e -> e.kind() == ShipDestination.Kind.REGION && e.region().equals(id)).map(ControllerPackets.Entry::name).findFirst().orElse(id.replace('_', ' '));
    }
}
