package com.nullandkale.nullspace.compat;

import java.util.*;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/** What goes into a container to see whether it comes back: ordinary things, awkward things, and other mods' items. */
final class ContainerFill
{
    /** A named stack, a damaged tool, a shulker box holding other mods' items, then those items themselves. */
    static List<ItemStack> pool(Set<String> namespaces)
    {
        var modded = new ArrayList<ItemStack>();
        for (var item : BuiltInRegistries.ITEM)
        {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (namespaces.contains(id.getNamespace()) && item != Items.AIR && modded.size() < 80)
                modded.add(new ItemStack(item, Math.min(3, item.getDefaultMaxStackSize())));
        }
        var pool = new ArrayList<ItemStack>();
        var named = new ItemStack(Items.DIAMOND, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Test Diamond"));
        pool.add(named);
        var damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(10);
        pool.add(damaged);
        var box = new ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(modded.stream().limit(12).map(ItemStack::copy).toList()));
        pool.add(box);
        pool.addAll(modded);
        return pool;
    }

    static int fill(Container container, ServerLevel level, Set<String> namespaces)
    {
        var pool = pool(namespaces);
        int slots = container.getContainerSize();
        for (int slot = 0; slot < slots; slot++)
            container.setItem(slot, pool.get(slot % pool.size()).copy());
        return slots;
    }

    private ContainerFill()
    {
    }
}
