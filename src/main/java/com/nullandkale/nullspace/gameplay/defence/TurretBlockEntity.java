package com.nullandkale.nullspace.gameplay.defence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A salvage turret's own state: the nine slots where it keeps what its kills drop, the experience it has gathered, and two
 * switches. A <b>hostile</b> turret is a security turret of a generated site: it shoots players instead of mobs until the site is
 * claimed.
 */
public final class TurretBlockEntity extends BaseContainerBlockEntity
{
    public static final int SIZE = 9;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private int xp;
    private boolean enabled = true, hostile;
    private long lastShot = Long.MIN_VALUE / 2;

    /** Where the gun points, in degrees: yaw turns it about the vertical (0 faces north), pitch lifts it. Sent to clients to draw it. */
    private float aimYaw, aimPitch;

    /** What the client draws, easing toward the aim (client only, never saved). */
    public float drawYaw, drawPitch;

    public TurretBlockEntity(BlockPos pos, BlockState state)
    {
        super(DefenceBlocks.TURRET, pos, state);
    }

    public int xp()
    {
        return xp;
    }

    public void addExperience(int points)
    {
        xp += Math.max(0, points);
        setChanged();
    }

    /** Hands over all the experience kept and clears it. */
    public int takeExperience()
    {
        int points = xp;
        xp = 0;
        setChanged();
        return points;
    }

    public boolean enabled()
    {
        return enabled;
    }

    public void enabled(boolean value)
    {
        enabled = value;
        setChanged();
    }

    public boolean hostile()
    {
        return hostile;
    }

    public void hostile(boolean value)
    {
        hostile = value;
        setChanged();
    }

    public float aimYaw()
    {
        return aimYaw;
    }

    public float aimPitch()
    {
        return aimPitch;
    }

    /** Turns the gun to look from a point to a target, and tells the clients if it moved visibly. */
    public void aim(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to)
    {
        var d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, -d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - aimYaw)) < 1.5F && Math.abs(pitch - aimPitch) < 1.5F)
            return;
        aimYaw = yaw;
        aimPitch = pitch;
        setChanged();
        if (level != null)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket()
    {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries)
    {
        return saveCustomOnly(registries);
    }

    public long lastShot()
    {
        return lastShot;
    }

    public void shot(long time)
    {
        lastShot = time;
    }

    /** Puts a stack into the turret where it fits; what does not fit is returned. */
    public ItemStack insert(ItemStack stack)
    {
        var rest = stack.copy();
        for (int i = 0; i < SIZE && !rest.isEmpty(); i++)
        {
            var slot = items.get(i);
            if (slot.isEmpty())
            {
                items.set(i, rest.split(Math.min(rest.getCount(), rest.getMaxStackSize())));
            }
            else if (ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < slot.getMaxStackSize())
            {
                int moved = Math.min(rest.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(moved);
                rest.shrink(moved);
            }
        }
        setChanged();
        return rest;
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
        return Component.literal(hostile ? "Security Turret" : "Salvage Turret");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory)
    {
        return new ChestMenu(MenuType.GENERIC_9x1, id, inventory, this, 1);
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        xp = Math.max(0, input.getIntOr("Experience", 0));
        enabled = input.getBooleanOr("Enabled", true);
        hostile = input.getBooleanOr("Hostile", false);
        aimYaw = input.getFloatOr("AimYaw", 0F);
        aimPitch = input.getFloatOr("AimPitch", 0F);
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("Experience", xp);
        output.putBoolean("Enabled", enabled);
        output.putBoolean("Hostile", hostile);
        output.putFloat("AimYaw", aimYaw);
        output.putFloat("AimPitch", aimPitch);
    }
}
