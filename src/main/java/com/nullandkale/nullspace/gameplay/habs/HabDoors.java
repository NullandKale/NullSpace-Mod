package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Doors that only power opens (see {@code docs/design/hab-rooms.md}). A door panel stands where a stone pressure plate would, on each side of the
 * door, and works like one (stepping on it powers the iron door beside it) but only while one of the hab's generators has fuel. Each panel
 * remembers the generators of its hab in its block entity, which {@link #link} fills when the hab is placed, so a door works from anywhere in the
 * hab. A panel remembers whether it last saw power, for the times its generators' chunks are not loaded. Opening a door burns a little fuel.
 */
public final class HabDoors
{
    private static final Identifier ID = Identifier.parse("nullspace:door_panel");

    public static final Block PANEL = Registry.register(BuiltInRegistries.BLOCK, ID, new Panel(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).noCollision().strength(1.5F).sound(SoundType.METAL).requiresCorrectToolForDrops().setId(ResourceKey.create(Registries.BLOCK, ID))));
    public static final BlockEntityType<PanelBlockEntity> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID, new BlockEntityType<>(PanelBlockEntity::new, Set.of(PANEL)));

    /** Registers the block (loading this class does it). */
    public static void initialize()
    {
        Registry.register(BuiltInRegistries.ITEM, ID, new BlockItem(PANEL, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix()));
    }

    /** Fuel units one opening burns. */
    static final int SPEND = 4;

    /**
     * Tells every door panel of a placed hab which generators power it: the generators in the build's machine halls, at {@code origin}.
     * Returns how many panels were linked.
     */
    public static int link(ServerLevel level, BlockPos origin, HabStructure.Build build)
    {
        var generators = new ArrayList<Long>();
        for (var e : build.plan().blocks().entrySet())
            if (e.getValue().is(com.nullandkale.nullspace.content.ModBlocks.GENERATOR))
                generators.add(origin.offset(e.getKey()).asLong());
        int linked = 0;
        for (var e : build.plan().blocks().entrySet())
            if (e.getValue().is(PANEL) && level.getBlockEntity(origin.offset(e.getKey())) instanceof PanelBlockEntity panel)
            {
                panel.link(generators);
                linked++;
            }
        return linked;
    }

    /** Tells the panel at {@code panel} which generators power it (a panel placed by hand, or for a test). Returns whether there is a panel there. */
    public static boolean link(ServerLevel level, BlockPos panel, List<BlockPos> generators)
    {
        if (!(level.getBlockEntity(panel) instanceof PanelBlockEntity entity))
            return false;
        entity.link(generators.stream().map(BlockPos::asLong).toList());
        return true;
    }

    /** True when the hab of the panel at {@code at} has power now. */
    public static boolean powered(Level level, BlockPos at)
    {
        return level.getBlockEntity(at) instanceof PanelBlockEntity panel && panel.on(level);
    }

    /** The panel: a pressure plate that signals only while the hab has power. */
    static final class Panel extends PressurePlateBlock implements EntityBlock
    {
        Panel(Properties properties)
        {
            super(BlockSetType.STONE, properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
        {
            return new PanelBlockEntity(pos, state);
        }

        @Override
        protected int getSignalStrength(Level level, BlockPos pos)
        {
            int signal = super.getSignalStrength(level, pos);
            if (signal == 0)
                return 0;
            if (!(level.getBlockEntity(pos) instanceof PanelBlockEntity panel) || !panel.on(level))
                return 0;
            // Burn fuel once, as the plate goes down.
            if (!level.getBlockState(pos).getValue(POWERED))
                panel.spend(level);
            return signal;
        }
    }

    /** The generators that power a panel, and whether they last had fuel. */
    public static final class PanelBlockEntity extends BlockEntity
    {
        private List<Long> sources = new ArrayList<>();
        private boolean last;

        public PanelBlockEntity(BlockPos pos, BlockState state)
        {
            super(ENTITY, pos, state);
        }

        void link(List<Long> generators)
        {
            sources = new ArrayList<>(generators);
            setChanged();
        }

        public int sources()
        {
            return sources.size();
        }

        /** True when a generator of the hab has fuel; the last answer when none of them is loaded. */
        boolean on(Level level)
        {
            boolean seen = false, fuel = false;
            for (long source : sources)
            {
                var at = BlockPos.of(source);
                if (!level.hasChunkAt(at))
                    continue;
                if (level.getBlockEntity(at) instanceof MachineBlockEntity machine && machine.generator())
                {
                    seen = true;
                    fuel |= machine.available() > 0;
                }
            }
            if (seen && fuel != last)
            {
                last = fuel;
                setChanged();
            }
            return seen ? fuel : last;
        }

        void spend(Level level)
        {
            for (long source : sources)
            {
                var at = BlockPos.of(source);
                if (level.hasChunkAt(at) && level.getBlockEntity(at) instanceof MachineBlockEntity machine && machine.generator() && machine.available() > 0)
                {
                    machine.draw(SPEND);
                    return;
                }
            }
        }

        @Override
        protected void loadAdditional(ValueInput input)
        {
            super.loadAdditional(input);
            int n = input.getIntOr("Sources", 0);
            sources = new ArrayList<>();
            for (int i = 0; i < n; i++)
                sources.add(input.getLongOr("S" + i, 0));
            last = input.getBooleanOr("Last", false);
        }

        @Override
        protected void saveAdditional(ValueOutput output)
        {
            super.saveAdditional(output);
            output.putInt("Sources", sources.size());
            for (int i = 0; i < sources.size(); i++)
                output.putLong("S" + i, sources.get(i));
            output.putBoolean("Last", last);
        }
    }

    private HabDoors()
    {
    }
}
