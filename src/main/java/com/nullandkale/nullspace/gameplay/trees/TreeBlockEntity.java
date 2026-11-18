package com.nullandkale.nullspace.gameplay.trees;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** What a golden apple tree remembers in its trunk core: how many apples it has and the game time up to which its days were counted. */
public final class TreeBlockEntity extends BlockEntity
{
    int apples;
    long seen = TreeGrowth.NEVER;

    public TreeBlockEntity(BlockPos pos, BlockState state)
    {
        super(GoldenTrees.TREE, pos, state);
    }

    public int apples()
    {
        return apples;
    }

    /** Takes one apple off the tree's count (an apple picked from a leaf). */
    public void picked()
    {
        if (apples > 0)
        {
            apples--;
            setChanged();
        }
    }

    /** Counts the days since the tree last looked and grows its apples; runs every second while the tree's chunk is loaded, and at once when it loads. */
    public static void tick(Level level, BlockPos pos, BlockState state, TreeBlockEntity tree)
    {
        if (level instanceof net.minecraft.server.level.ServerLevel server && (tree.seen == TreeGrowth.NEVER || (level.getGameTime() + pos.asLong()) % 20 == 0))
            tree.advanceNow(server);
    }

    /** Counts the days up to now and grows the apples they bring, showing them on the leaves. */
    public void advanceNow(net.minecraft.server.level.ServerLevel level)
    {
        // An apple somebody knocked off or took away is gone from the count.
        int hanging = GoldenTrees.fruitCount(level, worldPosition);
        if (hanging < apples)
        {
            apples = hanging;
            setChanged();
        }
        var next = TreeGrowth.advance(new TreeGrowth.State(apples, seen), level.getGameTime());
        boolean first = seen == TreeGrowth.NEVER;
        if (next.apples() == apples && next.seen() == seen)
            return;
        boolean grew = next.apples() != apples;
        apples = next.apples();
        seen = next.seen();
        setChanged();
        if (grew || first)
            GoldenTrees.refreshFruit(level, worldPosition, apples);
    }

    /** Gives the tree this many apples now (a tree that was generated has one to three) and counts its days from now. */
    public void stock(net.minecraft.server.level.ServerLevel level, int count)
    {
        apples = Math.clamp(count, 0, TreeGrowth.MAX_APPLES);
        seen = level.getGameTime();
        setChanged();
        GoldenTrees.refreshFruit(level, worldPosition, apples);
    }

    /** Makes the tree think it has been out of sight for this many ticks longer (for tests and commands). */
    public void awayFor(long ticks)
    {
        if (seen != TreeGrowth.NEVER)
            seen -= ticks;
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        apples = Math.clamp(input.getIntOr("Apples", 0), 0, TreeGrowth.MAX_APPLES);
        seen = input.getLongOr("Seen", TreeGrowth.NEVER);
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putInt("Apples", apples);
        output.putLong("Seen", seen);
    }
}
