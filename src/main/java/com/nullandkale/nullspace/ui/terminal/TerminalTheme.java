package com.nullandkale.nullspace.ui.terminal;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Presentation only: labels and numerical readings retain their original server-owned text. */
public final class TerminalTheme
{
    public static final int BACKGROUND = 0xff0a1020, PANEL = 0xff111f35, SELECTED = 0xff203f60, TEXT = 0xffdce8f5, MUTED = 0xff99adc5, BORDER = 0xff466383, CYAN = 0xff66dfee, BLUE = 0xff91bfff, GREEN = 0xff87e6a5, AMBER = 0xffffcf78, RED = 0xffff8695, PURPLE = 0xffc8adff;
    private static final Pattern LABELS = pattern("\\b(?:generation|draw|battery|stored energy|output|recharge|muzzle|outlet" + " room|facing|source|device|ship|priority|doors|feed|capacity)\\b");
    private static final Pattern SYSTEMS = pattern("\\b(?:controls|air|doors|engines|cameras|weapons)\\b");
    private static final Pattern GOOD = pattern("\\b(?:clear|connected|on|ready|live|sealed|success|valid|closed)\\b");
    private static final Pattern CAUTION = pattern("\\b(?:legacy|install|starting|loading|queued|unknown|occupied|waiting|open)\\b");
    private static final Pattern BAD = pattern("\\b(?:no power|no camera|not ready|not" + " connected|offline|disconnected|disabled|blocked|missing|invalid|unavailable|expired|rejected|failed|cancelled|canceled|stale|leak|outlet" + " outside ship|off)\\b");
    private static final Pattern PERCENT = pattern("\\b(\\d+)%");
    private static final Pattern LEAKS = pattern("\\b(\\d+) leak edges\\b");
    private static final Pattern ALLOCATION = pattern("\\b(?:controls|air|doors|engines|cameras|weapons) (\\d+)/(\\d+)|\\b(\\d+) delivered /" + " (\\d+) requested|(?:This pump:|Stored energy:) (\\d+) / (\\d+)");

    private static Pattern pattern(String regex)
    {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public static int accent(String mode)
    {
        return switch (mode.toLowerCase(Locale.ROOT))
        {
            case "systems" -> PURPLE;
            case "generator", "battery", "engine", "engines" -> AMBER;
            case "air_supply", "air" -> GREEN;
            case "weapon", "weapons" -> PURPLE;
            case "pad", "doors", "controls" -> BLUE;
            default -> CYAN;
        };
    }

    public static int action(String verb)
    {
        if (verb.contains("disable") || verb.startsWith("fire"))
            return RED;
        if (verb.startsWith("open_") || verb.contains("reset") || verb.contains("allocat") || verb.startsWith("power_") || verb.endsWith("_power"))
            return AMBER;
        if (verb.contains("enable") || verb.startsWith("close_") || verb.startsWith("register") || verb.equals("ship_buy"))
            return GREEN;
        if (verb.equals("priority") || verb.equals("upgrade") || verb.equals("systems"))
            return PURPLE;
        return CYAN;
    }

    private static void paint(int[] colors, int start, int end, int color)
    {
        Arrays.fill(colors, start, end, color);
    }

    private static void paint(int[] colors, String text, Pattern pattern, int color)
    {
        var match = pattern.matcher(text);
        while (match.find())
            paint(colors, match.start(), match.end(), color);
    }

    public static int[] statusColors(String text)
    {
        var colors = new int[text.length()];
        Arrays.fill(colors, TEXT);
        paint(colors, text, LABELS, CYAN);
        var systems = SYSTEMS.matcher(text);
        while (systems.find())
            paint(colors, systems.start(), systems.end(), accent(systems.group()));
        paint(colors, text, GOOD, GREEN);
        paint(colors, text, CAUTION, AMBER);
        paint(colors, text, BAD, RED);
        var percent = PERCENT.matcher(text);
        while (percent.find())
        {
            double value = Double.parseDouble(percent.group(1));
            paint(colors, percent.start(), percent.end(), value <= 25 ? RED : value < 60 ? AMBER : GREEN);
        }
        var allocation = ALLOCATION.matcher(text);
        while (allocation.find())
        {
            int group = allocation.group(1) != null ? 1 : allocation.group(3) != null ? 3 : 5;
            double actual = Double.parseDouble(allocation.group(group)), requested = Double.parseDouble(allocation.group(group + 1));
            boolean battery = allocation.group().toLowerCase(Locale.ROOT).startsWith("stored energy");
            int color = requested == 0 ? MUTED : battery ? (actual / requested <= .2 ? RED : actual / requested < .5 ? AMBER : GREEN) : actual == 0 ? RED : actual < requested ? AMBER : GREEN;
            paint(colors, allocation.start(group), allocation.end(group + 1), color);
        }
        var leaks = LEAKS.matcher(text);
        while (leaks.find())
            paint(colors, leaks.start(), leaks.end(), Double.parseDouble(leaks.group(1)) == 0 ? GREEN : RED);
        return colors;
    }

    public static int feedStatus(String text)
    {
        return BAD.matcher(text).find() ? RED : text.startsWith("LIVE") ? GREEN : AMBER;
    }

    private TerminalTheme()
    {
    }
}
