package com.nullandkale.nullspace.content;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * What the airless worlds give: the green gem, its block, the clusters that line a green geode, and the green fluid that fills it
 * (see {@code docs/design/rewards-work-plan.md}, chunk A1). The geodes are placed by data ({@code worldgen/feature/green_geode.json}).
 */
public final class SpaceMaterials
{
    /** What a cluster drops: two gems, more with Fortune, itself with Silk Touch. A geode lined with them holds well over nine gems. */
    public static final int CLUSTER_GEMS = 2;

    public static final Item GREEN_GEM = Registry.register(BuiltInRegistries.ITEM, Identifier.parse("nullspace:green_gem"), new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.parse("nullspace:green_gem")))));

    /** Nine gems; the price of one repair bay. */
    public static final Block GREEN_GEM_BLOCK = block("green_gem_block", BlockBehaviour.Properties.of().mapColor(MapColor.EMERALD).strength(3.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.AMETHYST).lightLevel(state -> 4));

    public static final Block GREEN_GEM_CLUSTER = block("green_gem_cluster", BlockBehaviour.Properties.of().mapColor(MapColor.EMERALD).strength(1.5F).requiresCorrectToolForDrops().sound(SoundType.AMETHYST_CLUSTER).lightLevel(state -> 6));

    public static final FlowingFluid GREEN_FLUID = Registry.register(BuiltInRegistries.FLUID, Identifier.parse("nullspace:green_fluid"), new GreenFluid.Source());

    public static final FlowingFluid FLOWING_GREEN_FLUID = Registry.register(BuiltInRegistries.FLUID, Identifier.parse("nullspace:flowing_green_fluid"), new GreenFluid.Flowing());

    public static final Block GREEN_FLUID_BLOCK = liquid();

    public static final Item GREEN_FLUID_BUCKET = Registry.register(BuiltInRegistries.ITEM, Identifier.parse("nullspace:green_fluid_bucket"), new BucketItem(GREEN_FLUID, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.parse("nullspace:green_fluid_bucket"))).craftRemainder(Items.BUCKET).stacksTo(1)));

    private static Block block(String name, BlockBehaviour.Properties properties)
    {
        var id = Identifier.parse("nullspace:" + name);
        var block = Registry.register(BuiltInRegistries.BLOCK, id, new Block(properties.setId(ResourceKey.create(Registries.BLOCK, id))));
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
        return block;
    }

    private static Block liquid()
    {
        var id = Identifier.parse("nullspace:green_fluid");
        return Registry.register(BuiltInRegistries.BLOCK, id, new LiquidBlock(GREEN_FLUID, BlockBehaviour.Properties.of().mapColor(MapColor.EMERALD).replaceable().noCollision().strength(100.0F).pushReaction(PushReaction.POPPED).noLootTable().liquid().sound(SoundType.EMPTY).lightLevel(state -> 7).setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    public static void initialize()
    {
    }

    private SpaceMaterials()
    {
    }
}
