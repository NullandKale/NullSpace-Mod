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

/**
 * The helm and the Scanner. The helm lists the places here on its Trip tab, where one click chooses and Engage goes. The Scanner lists the same places
 * first, then this system's planets (each opens in place to show its pads and contacts), then the star map.
 */
public final class ShipControlTerminal implements TerminalDevice
{
    // Shared terminal palette: shaded backgrounds only on things that can be clicked.
    private static final int CANVAS = TerminalTheme.BACKGROUND, SURFACE = TerminalTheme.PANEL, FOCUS = TerminalTheme.SELECTED;

    record Hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason, Runnable twice)
    {}

    final TerminalBuffer display = new TerminalBuffer();
    private final ArrayDeque<ControllerPackets.Input> actions = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    ControllerPackets.View view;
    boolean picker;
    boolean viewMenu;
    private boolean focusViewMenu;
    private int viewMenuStart;
    final TerminalField search = new TerminalField("", 64);
    String highlighted = "", filter = "All";
    boolean searchFocused = true, pending, close, details;
    private int pendingTicks;
    String pendingTarget = "";
    private int pendingResponse;
    private ShipControl.Action pendingAction;
    int selection, page, statusScroll;
    String notice = "";
    int tab; // 0 Trip, 1 Manual steps, 2 More
    /** The Scanner's system: blank for the ship's own. {@code map} shows the star map instead of the list. */
    String scopeSystem = "", chartSelected = "";
    boolean map;
    /** First line of the Scanner's list shown, the helm's page of places here, and the planets opened in the Scanner. */
    int listScroll, nearbyPage;
    final Set<String> opened = new HashSet<>();
    /** The mouse cell, -1 when off the grid, and why the control under it is unavailable. */
    int hoverColumn = -1, hoverRow = -1;
    String hoverReason = "";

    public ShipControlTerminal(ControllerPackets.View view)
    {
        this.view = view;
        picker = view.scanner();
        highlighted = view.selected();
    }

    /** The system and place the ship is at, from "System / Place / Orbit". */
    String hereSystem()
    {
        return view.here().split(" / ")[0];
    }

    String herePlace()
    {
        var parts = view.here().split(" / ");
        return parts.length > 1 ? parts[1] : parts[0];
    }

    /** The rows both screens list for this place: its orbit (when landed or docked), then pads, then stations and ships. */
    List<ControllerPackets.Entry> nearby()
    {
        var out = new ArrayList<ControllerPackets.Entry>();
        for (var kind : List.of(ShipDestination.Kind.REGION, ShipDestination.Kind.PAD, ShipDestination.Kind.VESSEL))
            for (var e : view.entries())
                if (e.kind() == kind && e.region().equals(view.hereRegion()))
                    out.add(e);
        return out;
    }

    /** Chooses a place as the ship's destination. It never starts travel: Engage does. */
    void choose(String id)
    {
        if (pending || id.isEmpty())
            return;
        highlighted = id;
        pendingTarget = id;
        send(ShipControl.Action.SELECT, id);
    }

    @Override
    public boolean hover(int column, int row)
    {
        hoverColumn = column;
        hoverRow = row;
        return true;
    }

    @Override
    public boolean scrolls()
    {
        return true;
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

    private String statusForDisplay()
    {
        return !view.scanner() && !view.trip().title().isEmpty() && !details && notice.isEmpty() && !view.message().isBlank() ? "Last action: " + status() : status();
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
        pendingAction = action;
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
        hit(x, y, width, text, run, enabled, reason, plain, color, CANVAS);
    }

    void hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason, boolean plain, int color, int background)
    {
        hit(x, y, width, text, run, enabled, reason, plain, color, background, null);
    }

    /** A clickable area; {@code twice} runs on a double click (a list row: choose it). */
    void hit(int x, int y, int width, String text, Runnable run, boolean enabled, String reason, boolean plain, int color, int background, Runnable twice)
    {
        int index = hits.size();
        hits.add(new Hit(x, y, width, text, run, enabled, reason, twice));
        String label = clean(text);
        label = label.substring(0, Math.min(width, label.length()));
        display.put(x, y, label + " ".repeat(width - label.length()), enabled ? color : TerminalTheme.MUTED, selection == index ? FOCUS : plain ? background : SURFACE);
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
        var messages = wrapped(statusForDisplay());
        statusScroll = Math.clamp(statusScroll, 0, Math.max(0, messages.size() - 3));
        int first = picker ? 0 : statusScroll;
        if (!picker && messages.size() > 3)
            line(19, "More: PgUp / PgDn", TerminalTheme.MUTED);
        for (int row = 0; row < (picker ? 1 : 3) && first + row < messages.size(); row++)
            if (!picker || !notice.isEmpty() || pending || !view.message().isBlank() && !view.message().equals("Ready"))
                line((picker ? 22 : 20) + row, messages.get(first + row), TerminalTheme.TEXT);
        hit(66, 24, 12, "[ Close ]", () -> close = true, true);
        if (picker && viewMenu)
            DestinationPicker.drawViewMenu(this);
        selection = Math.clamp(selection, 0, Math.max(0, hits.size() - 1));
        drawHover();
        return display;
    }

    /** Lights the control under the mouse, and says why it is unavailable when it is (on the helm's free line or the Scanner's hint line). */
    private void drawHover()
    {
        hoverReason = "";
        var hit = hitAt(hoverColumn, hoverRow);
        if (hit == null)
            return;
        if (!hit.enabled() && !hit.reason().isEmpty())
        {
            String label = hit.text().replace("[", "").replace("]", "").strip();
            hoverReason = label + ": " + hit.reason();
            int row = picker ? 21 : 19;
            display.put(2, row, " ".repeat(76), TerminalTheme.TEXT, CANVAS);
            line(row, hoverReason, TerminalTheme.AMBER);
            return;
        }
        if (!hit.enabled())
            return;
        for (int x = hit.x(); x < hit.x() + hit.width() && x < TerminalBuffer.COLUMNS; x++)
        {
            var cell = display.cell(x, hit.y());
            display.put(x, hit.y(), String.valueOf(cell.glyph()), cell.foreground(), FOCUS);
        }
    }

    private Hit hitAt(int column, int row)
    {
        for (int i = hits.size() - 1; i >= 0; i--)
        {
            var hit = hits.get(i);
            if (row == hit.y && column >= hit.x && column < hit.x + hit.width)
                return hit;
        }
        return null;
    }

    void openViewMenu()
    {
        viewMenu = true;
        focusViewMenu = true;
        searchFocused = false;
    }

    void beginViewMenu()
    {
        viewMenuStart = hits.size();
        if (focusViewMenu)
        {
            selection = viewMenuStart;
            focusViewMenu = false;
        }
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
        if (action == ShipControl.Action.EMERGENCY_LAND)
            return "Emergency landing";
        String name = action.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Override
    public void input(TerminalInput input)
    {
        display();
        if (input instanceof TerminalInput.Pointer p)
        {
            if (picker && viewMenu && !(p.column() >= 50 && p.column() < 78 && p.row() >= 3 && p.row() < 3 + hits.size() - viewMenuStart))
            {
                viewMenu = false;
                return;
            }
            searchFocused = picker && !viewMenu && p.row() == 2 && p.column() >= 9 && p.column() < 48;
            if (searchFocused)
            {
                search.click(p.column() - 9);
                return;
            }
            for (int i = hits.size() - 1; i >= 0; i--)
            {
                var hit = hits.get(i);
                if (p.row() == hit.y && p.column() >= hit.x && p.column() < hit.x + hit.width)
                {
                    selection = i;
                    // The first click of a double click has already run; the second chooses the row instead of running it again.
                    if (p.twice() && hit.twice != null)
                        hit.twice.run();
                    else if (hit.enabled)
                        hit.run.run();
                    else if (!hit.reason.isEmpty())
                        hit.run.run();
                    break;
                }
            }
        }
        else if (input instanceof TerminalInput.Scroll wheel)
        {
            if (picker && map)
                return;
            if (picker)
            {
                listScroll = Math.max(0, listScroll - wheel.steps() * 3);
                page = Math.max(0, page - wheel.steps());
            }
            else if (tab == 0 && wheel.row() >= 7 && wheel.row() <= 13)
                nearbyPage = Math.max(0, nearbyPage - wheel.steps());
            else
                statusScroll = Math.clamp(statusScroll - wheel.steps() * 3, 0, Math.max(0, wrapped(statusForDisplay()).size() - 3));
        }
        else if (input instanceof TerminalInput.Text && picker && searchFocused)
        {
            search.input(input);
            page = 0;
            highlighted = "";
        }
        else if (input instanceof TerminalInput.Key key)
        {
            if (picker && viewMenu && key == TerminalInput.Key.ESCAPE)
            {
                viewMenu = false;
                return;
            }
            if (picker && viewMenu && Set.of(TerminalInput.Key.UP, TerminalInput.Key.DOWN, TerminalInput.Key.LEFT, TerminalInput.Key.RIGHT, TerminalInput.Key.TAB).contains(key))
            {
                int step = key == TerminalInput.Key.UP || key == TerminalInput.Key.LEFT ? -1 : 1;
                selection = viewMenuStart + Math.floorMod(selection - viewMenuStart + step, hits.size() - viewMenuStart);
                return;
            }
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
                    if (hits.get(i).y == DestinationPicker.FIRST_ROW)
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
                    if (picker && map)
                        map = false;
                }
                case PAGE_UP ->
                {
                    if (picker)
                    {
                        page = Math.max(0, page - 1);
                        listScroll = Math.max(0, listScroll - DestinationPicker.PAGE_SIZE);
                    }
                    else
                        statusScroll = Math.max(0, statusScroll - 3);
                }
                case PAGE_DOWN ->
                {
                    if (picker)
                    {
                        page++;
                        listScroll += DestinationPicker.PAGE_SIZE;
                    }
                    else
                        statusScroll = Math.min(Math.max(0, wrapped(statusForDisplay()).size() - 3), statusScroll + 3);
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
        {
            if (pendingAction == ShipControl.Action.SELECT)
            {
                pending = false;
                pendingTarget = "";
                notice = "Selection not confirmed. Choose a destination and retry.";
            }
            else
                notice = "No reply yet. Close and reopen these controls to check the action.";
        }
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
