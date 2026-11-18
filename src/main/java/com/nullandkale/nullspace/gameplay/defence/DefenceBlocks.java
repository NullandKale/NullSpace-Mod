package com.nullandkale.nullspace.gameplay.defence;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The machines a base defends and maintains itself with (see {@code docs/design/rewards-work-plan.md}): the salvage turret, which
 * shoots hostile mobs and keeps what they drop, and the repair bay, which mends tools for fuel and keeps their enchantments. Both
 * work in a ship or a station, through {@link com.nullandkale.nullspace.gameplay.systems.Turrets} and
 * {@link com.nullandkale.nullspace.gameplay.systems.RepairBays}, which the vessel's tick calls.
 */
public final class DefenceBlocks
{
    public static final Block SALVAGE_TURRET = block("salvage_turret");

    public static final Block REPAIR_BAY = block("repair_bay");

    public static final BlockEntityType<TurretBlockEntity> TURRET = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:salvage_turret"), new BlockEntityType<>(TurretBlockEntity::new, Set.of(SALVAGE_TURRET)));

    public static final BlockEntityType<RepairBayBlockEntity> BAY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:repair_bay"), new BlockEntityType<>(RepairBayBlockEntity::new, Set.of(REPAIR_BAY)));

    /** The turret's moving parts, drawn by the client renderer from these items' models; nobody holds them. */
    public static final Item TURRET_HEAD = part("turret_head"), TURRET_GUN = part("turret_gun");

    private static Item part(String name)
    {
        var id = Identifier.parse("nullspace:" + name);
        return Registry.register(BuiltInRegistries.ITEM, id, new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
    }

    private static Block block(String name)
    {
        var id = Identifier.parse("nullspace:" + name);
        var block = Registry.register(BuiltInRegistries.BLOCK, id, new DefenceBlock(name, BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 6.0F).noOcclusion().requiresCorrectToolForDrops().sound(SoundType.METAL).setId(ResourceKey.create(Registries.BLOCK, id))));
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
        return block;
    }

    public static void initialize()
    {
    }

    /** A block with a container that opens when used; the turret also hands over the experience it has kept. */
    private static final class DefenceBlock extends Block implements EntityBlock
    {
        private final String kind;

        DefenceBlock(String kind, Properties properties)
        {
            super(properties);
            this.kind = kind;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
        {
            return kind.equals("salvage_turret") ? new TurretBlockEntity(pos, state) : new RepairBayBlockEntity(pos, state);
        }

        private static final net.minecraft.world.phys.shapes.VoxelShape TURRET_SHAPE = Block.box(3, 0, 3, 13, 13, 13);

        @Override
        protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context)
        {
            return kind.equals("salvage_turret") ? TURRET_SHAPE : super.getShape(state, level, pos, context);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
        {
            if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof MenuProvider menu)
            {
                if (level.getBlockEntity(pos) instanceof TurretBlockEntity turret && turret.xp() > 0)
                {
                    serverPlayer.giveExperiencePoints(turret.takeExperience());
                    serverPlayer.sendOverlayMessage(Component.literal("Salvage turret: experience collected"));
                }
                else if (level.getBlockEntity(pos) instanceof RepairBayBlockEntity bay)
                    serverPlayer.sendOverlayMessage(Component.literal("Repair bay: " + bay.status()));
                serverPlayer.openMenu(menu);
            }
            return InteractionResult.SUCCESS;
        }
    }

    private DefenceBlocks()
    {
    }
}
