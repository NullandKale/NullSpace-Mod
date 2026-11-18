package com.nullandkale.nullspace.gameplay.defence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A repair bay holds up to five tools and mends them one at a time. The fuel for a tool is paid when the bay starts on it, from
 * the vessel's generators ({@link com.nullandkale.nullspace.gameplay.systems.RepairBays}); the bay only changes the damage, so
 * enchantments and every other component stay exactly as they were.
 */
public final class RepairBayBlockEntity extends BaseContainerBlockEntity
{
    public static final int SIZE = 5;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int working = -1;
    private String status = "Ready";

    public RepairBayBlockEntity(BlockPos pos, BlockState state)
    {
        super(DefenceBlocks.BAY, pos, state);
    }

    public int working()
    {
        return working;
    }

    public void working(int slot)
    {
        working = slot;
        setChanged();
    }

    public String status()
    {
        return status;
    }

    public void status(String value)
    {
        if (!status.equals(value))
        {
            status = value;
            setChanged();
        }
    }

    @Override
    public int getContainerSize()
    {
        return SIZE;
    }

    @Override
    protected NonNullList<ItemStack> getItems()
    {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items)
    {
        this.items = items;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack)
    {
        return stack.isDamageableItem();
    }

    @Override
    protected Component getDefaultName()
    {
        return Component.literal("Repair Bay");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory)
    {
        return new HopperMenu(id, inventory, this);
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        working = input.getIntOr("Working", -1);
        status = input.getStringOr("Status", "Ready");
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("Working", working);
        output.putString("Status", status);
    }
}
