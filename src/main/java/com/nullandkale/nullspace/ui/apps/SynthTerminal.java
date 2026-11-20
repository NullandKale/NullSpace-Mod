package com.nullandkale.nullspace.ui.apps;

import com.nullandkale.nullspace.model.sound.SoundCues;
import com.nullandkale.nullspace.model.sound.SynthPatch;
import com.nullandkale.nullspace.ui.terminal.ControllerApplication;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The Synth block's screen: every setting of its patch with {@code [-]} and {@code [+]}, a preset list of the game's cues, and Play and Stop. Each button
 * sends one setting to the server, which saves it in the block and answers with the patch as it now is. Pitches step by a semitone, times and levels by small
 * amounts.
 */
public final class SynthTerminal implements ControllerApplication
{
    private final TerminalBuffer buffer = new TerminalBuffer();
    private final ArrayDeque<ControllerTerminal.Action> actions = new ArrayDeque<>();
    private final List<Runnable> commands = new ArrayList<>();
    private final List<int[]> hits = new ArrayList<>();
    private SynthPatch patch = SynthPatch.DEFAULT;
    private boolean playing, waiting, close;
    private String status, silence = "";
    private int selected, ticks, preset;

    public SynthTerminal(String status, List<TerminalPackets.Destination> data)
    {
        this.status = status;
        catalog(data);
    }

    public void catalog(List<TerminalPackets.Destination> data)
    {
        data.stream().filter(d -> d.id().equals("patch")).findFirst().ifPresent(d ->
        {
            try
            {
                patch = SynthPatch.parse(d.name());
            }
            catch (IllegalArgumentException damaged)
            {
                patch = SynthPatch.DEFAULT;
            }
            playing = d.state().equals("PLAYING");
            silence = d.detail();
        });
    }

    @Override
    public ControllerTerminal.Action pollAction()
    {
        return actions.poll();
    }

    @Override
    public void update(String status)
    {
        this.status = status;
        waiting = false;
    }

    @Override
    public void tick()
    {
        if (waiting && ++ticks > 100)
        {
            waiting = false;
            status = "No reply yet. Try again.";
        }
    }

    @Override
    public int selection()
    {
        return selected;
    }

    @Override
    public String status()
    {
        return status;
    }

    @Override
    public String fieldValue()
    {
        return "";
    }

    @Override
    public boolean closeRequested()
    {
        return close;
    }

    private void send(String verb, String argument)
    {
        if (waiting)
            return;
        waiting = true;
        ticks = 0;
        actions.add(new ControllerTerminal.Action(verb, argument));
    }

    private void set(String setting, float value)
    {
        send("synth_set", setting + " " + number(value));
    }

    private static String number(float value)
    {
        String text = String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** A pitch to a tenth of a hertz, for reading; the block keeps it exactly. */
    private static String hertz(float value)
    {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + " Hz";
    }

    private static float time(float value, int direction)
    {
        float step = value < .1f ? .01f : value < 1 ? .05f : .25f;
        if (direction < 0 && value <= .1f && value > .01f)
            step = .01f;
        return Math.max(0, value + direction * step);
    }

    private static float level(float value, int direction)
    {
        return Math.max(0, Math.min(1, Math.round((value + direction * .05f) * 20) / 20f));
    }

    private static float pitch(float value, int direction)
    {
        return (float) (value * Math.pow(2, direction / 12.0));
    }

    private void text(int x, int y, String s, int color)
    {
        buffer.put(x, y, s.substring(0, Math.min(80 - x, s.length())), color, TerminalTheme.BACKGROUND);
    }

    private void button(int x, int y, String label, Runnable action, boolean enabled)
    {
        int index = commands.size();
        commands.add(enabled ? action : () ->
        {});
        hits.add(new int[]{x, y, label.length()});
        buffer.put(x, y, label, enabled ? TerminalTheme.CYAN : TerminalTheme.MUTED, selected == index ? TerminalTheme.SELECTED : TerminalTheme.PANEL);
    }

    /** One setting: its name, its value, and a {@code [-]}/{@code [+]} pair that sends the next value. Returns the column after it. */
    private int setting(int x, int y, String name, String value, String key, Function<Integer, Float> step)
    {
        text(x, y, name, TerminalTheme.MUTED);
        x += name.length() + 1;
        button(x, y, "[-]", () -> set(key, step.apply(-1)), !waiting);
        text(x + 4, y, value, TerminalTheme.TEXT);
        button(x + 5 + value.length(), y, "[+]", () -> set(key, step.apply(1)), !waiting);
        return x + 9 + value.length() + 2;
    }

    @Override
    public TerminalBuffer display()
    {
        buffer.reset();
        buffer.cursorVisible(false);
        for (int y = 0; y < 25; y++)
            buffer.put(0, y, " ".repeat(80), TerminalTheme.TEXT, TerminalTheme.BACKGROUND);
        commands.clear();
        hits.clear();
        text(2, 1, "SYNTH", TerminalTheme.BLUE);
        text(9, 1, playing ? "PLAYING" : "", TerminalTheme.GREEN);
        if (!silence.isBlank())
            text(2, 2, silence, TerminalTheme.AMBER);
        var names = new ArrayList<>(SoundCues.names());
        names.sort(null);
        if (!names.isEmpty())
        {
            preset = Math.floorMod(preset, names.size());
            text(2, 4, "Preset", TerminalTheme.MUTED);
            button(10, 4, "[<]", () -> preset--, true);
            text(14, 4, String.format(Locale.ROOT, "%-16s", names.get(preset)), TerminalTheme.TEXT);
            button(31, 4, "[>]", () -> preset++, true);
            String chosen = names.get(preset);
            button(36, 4, "[ Load ]", () -> send("synth_preset", chosen), !waiting);
        }
        text(2, 6, "Tone", TerminalTheme.MUTED);
        String wave = patch.wave().name().toLowerCase(Locale.ROOT);
        var next = SynthPatch.Wave.values()[(patch.wave().ordinal() + 1) % SynthPatch.Wave.values().length].name().toLowerCase(Locale.ROOT);
        button(10, 6, "[ " + String.format(Locale.ROOT, "%-6s", wave) + " ]", () -> send("synth_set", "wave " + next), !waiting);
        int x = setting(22, 6, "from", hertz(patch.toneFrom()), "from", d -> pitch(patch.toneFrom(), d));
        setting(x, 6, "to", hertz(patch.toneTo()), "to", d -> pitch(patch.toneTo(), d));
        x = setting(10, 7, "sweep", number(patch.sweep()) + " s", "sweep", d -> time(patch.sweep(), d));
        setting(x, 7, "level", number(patch.toneLevel()), "tonelevel", d -> level(patch.toneLevel(), d));
        text(2, 9, "Noise", TerminalTheme.MUTED);
        x = setting(10, 9, "level", number(patch.noiseLevel()), "noiselevel", d -> level(patch.noiseLevel(), d));
        setting(x, 9, "brightness", number(patch.brightness()), "bright", d -> level(patch.brightness(), d));
        text(2, 11, "Shape", TerminalTheme.MUTED);
        x = setting(10, 11, "attack", number(patch.attack()) + " s", "attack", d -> Math.max(.001f, time(patch.attack(), d)));
        x = setting(x, 11, "hold", number(patch.hold()) + " s", "hold", d -> time(patch.hold(), d));
        setting(x, 11, "release", number(patch.release()) + " s", "release", d -> Math.max(.001f, time(patch.release(), d)));
        text(2, 13, "Pulse", TerminalTheme.MUTED);
        x = setting(10, 13, "rate", number(patch.pulseRate()) + " /s", "rate", d -> Math.max(0, Math.min(30, patch.pulseRate() + d * .5f)));
        setting(x, 13, "on", number(patch.pulseDuty()), "duty", d -> Math.max(.05f, Math.min(.95f, level(patch.pulseDuty(), d))));
        text(2, 15, "Loop", TerminalTheme.MUTED);
        button(10, 15, patch.loop() ? "[ on  ]" : "[ off ]", () -> send("synth_set", "loop " + (patch.loop() ? "off" : "on")), !waiting);
        text(20, 15, "Redstone plays it; a loop plays while powered.", TerminalTheme.MUTED);
        var lines = wrap(patch.text(), 76);
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            text(2, 17 + i, lines.get(i), TerminalTheme.MUTED);
        button(2, 20, "[ Play ]", () -> send("synth_play", ""), !waiting);
        button(12, 20, "[ Stop ]", () -> send("synth_stop", ""), !waiting && playing);
        button(22, 20, "[ Close ]", () -> close = true, true);
        var notice = wrap(waiting ? "Waiting for the Synth..." : status.replaceAll("[^ -~]", "?"), 76);
        for (int i = 0; i < Math.min(2, notice.size()); i++)
            text(2, 22 + i, notice.get(i), TerminalTheme.TEXT);
        return buffer;
    }

    /** Lines of at most {@code width}, broken between words. */
    private static List<String> wrap(String text, int width)
    {
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        for (String word : text.split(" "))
        {
            if (line.length() > 0 && line.length() + 1 + word.length() > width)
            {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0)
                line.append(' ');
            line.append(word.length() > width ? word.substring(0, width) : word);
        }
        if (line.length() > 0)
            out.add(line.toString());
        return out;
    }

    @Override
    public void input(TerminalInput input)
    {
        display();
        if (input instanceof TerminalInput.Pointer p)
        {
            for (int i = 0; i < hits.size(); i++)
            {
                var h = hits.get(i);
                if (p.row() == h[1] && p.column() >= h[0] && p.column() < h[0] + h[2])
                {
                    selected = i;
                    commands.get(i).run();
                    break;
                }
            }
        }
        else if (input instanceof TerminalInput.Key k && !commands.isEmpty())
            switch (k)
            {
                case UP -> selected = Math.floorMod(selected - 1, commands.size());
                case DOWN, TAB -> selected = (selected + 1) % commands.size();
                case LEFT -> selected = Math.floorMod(selected - 1, commands.size());
                case RIGHT -> selected = (selected + 1) % commands.size();
                case ENTER -> commands.get(Math.min(selected, commands.size() - 1)).run();
                default ->
                    {
                    }
            }
    }
}
