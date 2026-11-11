package com.nullandkale.nullspace.content.machine;

import com.nullandkale.nullspace.content.ModBlocks;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Carries the id of the ship a controller belongs to: a few bytes, never ship data. The record in the ship store stays
 * the only source of a ship's state; this is compared with it whenever the chunk loads (see ShipIntegrity). It has no
 * inventory and no menu, so hoppers and players cannot put anything in it. Like every block entity it travels with the hull.
 */
public final class ControllerBlockEntity extends BlockEntity
{
    public static final BlockEntityType<ControllerBlockEntity> TYPE = register();

    private static BlockEntityType<ControllerBlockEntity> register()
    {
        var controllers = Set.of(ModBlocks.COMPUTER, ModBlocks.STATION_CORE);
        var type = new BlockEntityType<>(ControllerBlockEntity::new, controllers);
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.parse("nullspace:controller"), type);
    }

    /** The ship this controller belongs to, or empty before it has been stamped. */
    public String ship = "";

    public ControllerBlockEntity(BlockPos pos, BlockState state)
    {
        super(TYPE, pos, state);
    }

    /** Registers the type. Called once at start, after the blocks exist. */
    public static void initialize()
    {
        // Referencing TYPE runs the registration above.
        assert TYPE != null;
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        ship = input.getStringOr("Ship", "");
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putString("Ship", ship);
    }
}
