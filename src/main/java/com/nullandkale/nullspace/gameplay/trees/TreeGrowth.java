package com.nullandkale.nullspace.gameplay.trees;

/**
 * How a golden apple tree grows apples: one for every Minecraft day, up to eight, counted from the game time the tree last looked, so a tree in
 * a place nobody is in still has its apples when the place loads again. Plain numbers, so it is checked without a game.
 */
public final class TreeGrowth
{
    /** Ticks in a Minecraft day: one apple. */
    public static final long DAY = 24000L;

    public static final int MAX_APPLES = 8;

    /** The time of a tree that has not looked yet. */
    public static final long NEVER = Long.MIN_VALUE;

    /** A tree's apples and the game time up to which its days have been counted ({@link #NEVER} for a tree that has not looked yet). */
    public record State(int apples, long seen)
    {}

    /**
     * What a tree has at {@code now}: each whole day since it last looked gives an apple, and the part of a day left over is kept for the
     * next. A full tree banks no time, so picking one starts the next apple's day then and not when it filled. A clock that ran backwards
     * adds nothing.
     */
    public static State advance(State tree, long now)
    {
        if (tree.seen() == NEVER)
            return new State(tree.apples(), now);
        if (tree.apples() >= MAX_APPLES)
            return new State(MAX_APPLES, now);
        long days = Math.max(0, (now - tree.seen()) / DAY);
        if (days == 0)
            return tree;
        int apples = (int) Math.min(MAX_APPLES, tree.apples() + days);
        return new State(apples, apples >= MAX_APPLES ? now : tree.seen() + days * DAY);
    }

    private TreeGrowth()
    {
    }
}
