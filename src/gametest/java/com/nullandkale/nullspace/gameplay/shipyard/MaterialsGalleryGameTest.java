package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.SpaceMaterials;
import com.nullandkale.nullspace.gameplay.boss.SpaceWither;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public final class MaterialsGalleryGameTest
{
    @GameTest(maxTicks = 600)
    public void theMaterialsGalleryShowsTheGeodeTheFluidTheMachinesTheItemsAndTheWither(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var origin = MaterialsGallery.build(server);
        var box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + MaterialsGallery.PITCH * MaterialsGallery.SECTIONS, origin.getY() + MaterialsGallery.HEIGHT, origin.getZ() + MaterialsGallery.DEPTH);
        int gems = 0, clusters = 0, fluid = 0, glass = 0, turrets = 0, bays = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1)))
        {
            var state = level.getBlockState(pos);
            if (state.is(SpaceMaterials.GREEN_GEM_CLUSTER))
                clusters++;
            else if (state.is(SpaceMaterials.GREEN_GEM_BLOCK))
                gems++;
            else if (state.is(SpaceMaterials.GREEN_FLUID_BLOCK))
                fluid++;
            else if (state.is(Blocks.GLASS))
                glass++;
            else if (state.is(DefenceBlocks.SALVAGE_TURRET))
                turrets++;
            else if (state.is(DefenceBlocks.REPAIR_BAY))
                bays++;
        }
        ctx.assertTrue(clusters >= 3 && gems >= 4, "Clusters and gem blocks stand in the hall: " + clusters + " clusters, " + gems + " blocks");
        ctx.assertTrue(fluid >= 20, "The basin and the geode hold the green fluid: " + fluid);
        ctx.assertTrue(glass > 20, "A pane of glass covers the cut geode: " + glass);
        ctx.assertTrue(turrets == 1 && bays == 1, "One turret and one repair bay");
        ctx.assertTrue(level.getEntitiesOfClass(ItemFrame.class, box).size() == 6, "Four items and the two saplings hang on frames");
        var withers = level.getEntitiesOfClass(SpaceWither.class, box);
        ctx.assertTrue(withers.size() == 1 && withers.getFirst().isNoAi() && withers.getFirst().isInvulnerable(), "One space wither stands still and cannot be hurt");
        MaterialsGallery.build(server);
        ctx.assertTrue(level.getEntitiesOfClass(SpaceWither.class, box).size() == 1 && level.getEntitiesOfClass(ItemFrame.class, box).size() == 6, "A second build replaces the first");
        level.getEntitiesOfClass(SpaceWither.class, box).forEach(w -> w.discard());
        ctx.succeed();
    }
}
