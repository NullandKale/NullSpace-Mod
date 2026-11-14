package com.nullandkale.nullspace.content.machine;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

/** Real machine slots. Vanilla container saving and transfer journals carry these with the hull. */
public final class MachineBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer
{
    public static final int SIZE = 10, RETURN = 9;
    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int burn;
    public String label = "", camera = "";

    public MachineBlockEntity(BlockPos pos, BlockState state)
    {
        super(MachineMenus.ENTITY, pos, state);
    }

    public boolean generator()
    {
        return getBlockState().is(ModBlocks.GENERATOR);
    }

    public boolean upgrades()
    {
        return generator() || getBlockState().is(ModBlocks.ENGINE) || getBlockState().is(ModBlocks.BATTERY) || getBlockState().is(ModBlocks.LOCAL_SCANNER);
    }

    public int tier()
    {
        int tier = 0;
        // The number of upgrades installed, capped by the best one: tier 2 needs an advanced
        // upgrade (amethyst), tier 3 an exotic one (netherite scrap or an echo shard).
        int best = 0;
        for (int i = 0; i < 3; i++)
        {
            int rank = ModBlocks.upgradeRank(items.get(i));
            // The creative upgrade alone makes a Scanner tier 4.
            if (rank == 4 && getBlockState().is(ModBlocks.LOCAL_SCANNER))
                return 4;
            if (rank > 0)
                tier++;
            best = Math.max(best, rank);
        }
        tier = Math.min(tier, best);
        return tier;
    }

    public int burn()
    {
        return burn;
    }

    public int duration(ItemStack stack)
    {
        if (stack.isEmpty() || !stack.has(DataComponents.COOKING_FUEL))
            return 0;
        return level instanceof ServerLevel server ? Math.max(0, ResolvableInt.getFromItem(stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, getLootContext(server), 0)) : stack.has(DataComponents.COOKING_FUEL) ? 1 : 0;
    }

    private boolean returnsFit(ItemStack fuel)
    {
        var remainder = fuel.getItem().getCraftingRemainder();
        if (remainder == null)
            return true;
        var returned = remainder.create();
        var output = items.get(RETURN);
        return output.isEmpty() || ItemStack.isSameItemSameComponents(output, returned) && output.getCount() + returned.getCount() <= output.getMaxStackSize();
    }

    public boolean blockedReturn()
    {
        for (int i = 3; i < RETURN; i++)
            if (duration(items.get(i)) > 0 && !returnsFit(items.get(i)))
                return true;
        return false;
    }

    public int available()
    {
        long total = burn;
        var output = items.get(RETURN).copy();
        for (int i = 3; i < RETURN; i++)
        {
            var fuel = items.get(i);
            int count = fuel.getCount();
            var template = fuel.getItem().getCraftingRemainder();
            if (template != null)
            {
                var remainder = template.create();
                if (!output.isEmpty() && !ItemStack.isSameItemSameComponents(output, remainder))
                    continue;
                int room = remainder.getMaxStackSize() - (output.isEmpty() ? 0 : output.getCount());
                count = Math.min(count, room / Math.max(1, remainder.getCount()));
                if (count > 0)
                {
                    if (output.isEmpty())
                        output = remainder.copyWithCount(count * remainder.getCount());
                    else
                        output.grow(count * remainder.getCount());
                }
            }
            total += (long) duration(fuel) * 8 * count;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Called only for actual work; status queries never consume an item. */
    public int draw(int requested)
    {
        int taken = 0;
        while (taken < requested)
        {
            if (burn == 0)
            {
                int slot = -1;
                for (int i = 3; i < RETURN; i++)
                    if (duration(items.get(i)) > 0 && returnsFit(items.get(i)))
                    {
                        slot = i;
                        break;
                    }
                if (slot < 0)
                    break;
                var fuel = items.get(slot);
                burn = (int) Math.min(Integer.MAX_VALUE, (long) duration(fuel) * 8);
                var remainder = fuel.getItem().getCraftingRemainder();
                if (remainder != null)
                {
                    var result = remainder.create();
                    if (items.get(RETURN).isEmpty())
                        items.set(RETURN, result);
                    else
                        items.get(RETURN).grow(result.getCount());
                }
                fuel.shrink(1);
            }
            int used = Math.min(requested - taken, burn);
            burn -= used;
            taken += used;
        }
        if (taken > 0)
            setChanged();
        return taken;
    }

    public boolean canRemoveUpgrade()
    {
        if (!(level instanceof ServerLevel server) || !getBlockState().is(ModBlocks.BATTERY))
            return true;
        var ship = WorldStore.get(server.getServer()).at(server, worldPosition);
        return ship == null || ShipSystems.deviceEnergy(ship, worldPosition) <= 1200 * Math.max(1, tier());
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
    protected Component getDefaultName()
    {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory)
    {
        return new MachineMenu(id, inventory, this);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack)
    {
        return slot < 3 ? upgrades() && ModBlocks.upgradeRank(stack) > 0 && (ModBlocks.upgradeRank(stack) < 4 || getBlockState().is(ModBlocks.LOCAL_SCANNER)) : generator() && slot < RETURN && duration(stack) > 0;
    }

    @Override
    public int[] getSlotsForFace(Direction face)
    {
        return !generator() ? new int[0] : face == Direction.DOWN ? new int[]{RETURN} : new int[]{3, 4, 5, 6, 7, 8};
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face)
    {
        if (level instanceof ServerLevel server && TransferLocks.locked(server, worldPosition))
            return false;
        return face != Direction.DOWN && slot >= 3 && slot < RETURN && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face)
    {
        return !(level instanceof ServerLevel server && TransferLocks.locked(server, worldPosition)) && face == Direction.DOWN && slot == RETURN;
    }

    @Override
    public void setChanged()
    {
        super.setChanged();
    }

    @Override
    public void setItem(int slot, ItemStack item)
    {
        super.setItem(slot, item);
        if (slot < 3 && level instanceof ServerLevel server)
            ShipSystems.changed(server, worldPosition, false);
    }

    @Override
    public ItemStack removeItem(int slot, int count)
    {
        var item = super.removeItem(slot, count);
        if (slot < 3 && level instanceof ServerLevel server)
            ShipSystems.changed(server, worldPosition, false);
        return item;
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        burn = Math.max(0, input.getIntOr("BurnEnergy", 0));
        label = input.getStringOr("Label", "");
        camera = input.getStringOr("Camera", "");
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("BurnEnergy", burn);
        output.putString("Label", label);
        output.putString("Camera", camera);
    }
}
