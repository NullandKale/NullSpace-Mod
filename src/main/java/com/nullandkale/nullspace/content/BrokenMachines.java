package com.nullandkale.nullspace.content;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The burnt-out stand-ins of the three machines a ship cannot fly without: the Engine, the Generator and the Air Supply. A crash
 * landing replaces some of the real machines with these. They are a solid block of the same shape and facing, charred and
 * cracked, with no inventory and no function, so the player sees what to build and where it goes: mine the wreck, craft the
 * machine, place it in the same spot. The ship computer is never replaced; it is the player's window onto what is broken.
 */
public final class BrokenMachines
{
    public static final Block ENGINE = broken("engine", "ship engine");
    public static final Block GENERATOR = broken("generator", "generator");
    public static final Block AIR_SUPPLY = broken("air_supply", "air supply");

    /** The wreck of the Pad Computer: it stands at every crash site, and no crash ever registers a pad. */
    public static final Block PAD_COMPUTER = broken("pad_computer", "pad computer");

    /** The block entity every wreck carries, so it can smoke on the client. */
    public static final net.minecraft.world.level.block.entity.BlockEntityType<WreckBlockEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:wreck"), new net.minecraft.world.level.block.entity.BlockEntityType<>(WreckBlockEntity::new, java.util.Set.of(ENGINE, GENERATOR, AIR_SUPPLY, PAD_COMPUTER)));

    private static final Map<Block, Block> BROKEN = Map.of(ModBlocks.ENGINE, ENGINE, ModBlocks.GENERATOR, GENERATOR, ModBlocks.AIR_SUPPLY, AIR_SUPPLY, com.nullandkale.nullspace.gameplay.pads.PadComputers.BLOCK, PAD_COMPUTER);

    /** The wreck of a working machine, or null when the machine has no broken form. */
    public static Block of(Block working)
    {
        return BROKEN.get(working);
    }

    public static boolean broken(BlockState state)
    {
        return BROKEN.containsValue(state.getBlock());
    }

    private static Block broken(String name, String label)
    {
        var id = Identifier.parse("nullspace:broken_" + name);
        var block = Registry.register(BuiltInRegistries.BLOCK, id, new Wreck(label, BlockBehaviour.Properties.of().strength(2).setId(ResourceKey.create(Registries.BLOCK, id))));
        var lore = new ItemLore(java.util.List.of(Component.literal("Burnt out and useless"), Component.literal("Mine it, craft a " + label + ", and place that here")));
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix().component(DataComponents.LORE, lore)));
        return block;
    }

    public static void initialize()
    {
    }

    private static final class Wreck extends ModBlocks.Machine implements net.minecraft.world.level.block.EntityBlock
    {
        private final String label;

        Wreck(String label, Properties props)
        {
            super(props);
            this.label = label;
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos at, BlockState state)
        {
            return new WreckBlockEntity(at, state);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type)
        {
            return level.isClientSide() && type == TYPE ? (net.minecraft.world.level.block.entity.BlockEntityTicker<T>) (net.minecraft.world.level.block.entity.BlockEntityTicker<WreckBlockEntity>) WreckBlockEntity::clientTick : null;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
        {
            if (player instanceof ServerPlayer serverPlayer)
                serverPlayer.sendOverlayMessage(Component.literal("Burnt out. Mine it, then craft a " + label + " and place it here."));
            return InteractionResult.SUCCESS;
        }
    }

    private BrokenMachines()
    {
    }
}
