package com.nullandkale.nullspace.ui.apps;

import java.util.*;

/** Places chart labels around protected contact glyphs, then draws unobstructed leader dots. */
final class ChartLabels
{
    record Contact(String id, String name, int x, int y)
    {}

    record Cell(int x, int y)
    {}

    record Placed(String id, int x, int y, int labelX, int labelY, String label, List<Cell> line)
    {}

    static List<Placed> place(List<Contact> contacts, int width, int height)
    {
        boolean[][] used = new boolean[height][width];
        var pins = new LinkedHashMap<String, Cell>();
        for (var contact : contacts.stream().sorted(Comparator.comparing(Contact::id)).toList())
        {
            int x = Math.clamp(contact.x() * (width - 1) / 1000, 0, width - 1);
            int y = Math.clamp(contact.y() * (height - 1) / 1000, 0, height - 1);
            Cell best = null;
            int score = Integer.MAX_VALUE;
            for (int row = 0; row < height; row++)
                for (int col = 0; col < width; col++)
                {
                    int cost = (col - x) * (col - x) * 36 + (row - y) * (row - y) * 100;
                    if (!used[row][col] && cost < score)
                    {
                        best = new Cell(col, row);
                        score = cost;
                    }
                }
            if (best == null)
                throw new IllegalArgumentException("Too many chart contacts");
            used[best.y()][best.x()] = true;
            pins.put(contact.id(), best);
        }
        var result = new ArrayList<Placed>();
        for (var contact : contacts)
        {
            var pin = pins.get(contact.id());
            String label = ShipControlTerminal.clean(contact.name());
            label = label.substring(0, Math.min(width - 2, label.length()));
            Cell best = null;
            int score = Integer.MAX_VALUE;
            for (int row = 0; row < height; row++)
                for (int col = 0; col + label.length() <= width; col++)
                {
                    boolean free = true;
                    for (int k = Math.max(0, col - 1); k < Math.min(width, col + label.length() + 1); k++)
                        free &= !used[row][k];
                    if (!free)
                        continue;
                    int dx = pin.x() < col ? col - pin.x() : Math.max(0, pin.x() - col - label.length() + 1);
                    int dy = row - pin.y();
                    int cost = dy * dy * 900 + dx * dx * 36 + (col < pin.x() ? 4 : 0);
                    if (cost < score)
                    {
                        score = cost;
                        best = new Cell(col, row);
                    }
                }
            if (best == null)
                throw new IllegalArgumentException("Chart labels exceed available space");
            for (int k = 0; k < label.length(); k++)
                used[best.y()][best.x() + k] = true;
            result.add(new Placed(contact.id(), pin.x(), pin.y(), best.x(), best.y(), label, List.of()));
        }
        return result.stream().map(p ->
        {
            var line = new ArrayList<Cell>();
            int endX = Math.clamp(p.x(), p.labelX(), p.labelX() + p.label().length() - 1);
            if (p.labelY() != p.y() || Math.abs(endX - p.x()) > 2)
            {
                int x = p.x(), y = p.y();
                while (x != endX || y != p.labelY())
                {
                    if (x != endX)
                        x += Integer.signum(endX - x);
                    else
                        y += Integer.signum(p.labelY() - y);
                    if (!used[y][x])
                        line.add(new Cell(x, y));
                }
            }
            return new Placed(p.id(), p.x(), p.y(), p.labelX(), p.labelY(), p.label(), List.copyOf(line));
        }).toList();
    }

    private ChartLabels()
    {
    }
}
