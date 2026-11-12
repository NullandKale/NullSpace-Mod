package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipService;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

public final class LandingClearanceGameTest
{
    @GameTest(environment = "nullspace:landing_rollback", maxTicks = 200)
    public void interruptedArrivalRestoresDisplacedFluidAndTheOriginalShip(GameTestHelper ctx)
    {
        var fixture = EntityTravelGameTest.referenceBed(ctx, 91);
        ctx.runAfterDelay(10, () ->
        {
            var bed = fixture.get();
            var base = WorldStore.pos(bed.ship(), "base");
            var departure = TravelService.transfer(bed.player(), bed.helm(), "", ShipService.inspect(bed.level(), bed.helm()));
            ctx.assertTrue(departure.outcome() == TravelService.Outcome.COMPLETED, "Fixture launches: " + departure.message());
            var lava = base.offset(2, 2, 2);
            bed.level().setBlock(lava, Blocks.LAVA.defaultBlockState(), 18);
            var helm = WorldStore.pos(bed.ship(), "controller");
            var hook = TravelService.failureInjector;
            var injected = new java.util.concurrent.atomic.AtomicBoolean();
            try
            {
                TravelService.failureInjector = stage ->
                {
                    if (stage.equals("DESTINATION_STAGED"))
                    {
                        injected.set(true);
                        throw new IllegalStateException("interrupted fluid landing");
                    }
                };
                TravelService.transfer(bed.player(), helm, padName(bed), ShipService.inspect(bed.space(), helm));
            }
            catch (IllegalStateException expected)
            {
                ctx.assertTrue(expected.getMessage().equals("interrupted fluid landing"), "Only the intended injected failure is accepted");
            }
            finally
            {
                TravelService.failureInjector = hook;
            }
            ctx.assertTrue(injected.get(), "Arrival reached its physical mutation checkpoint");
            TransferRecovery.recover(bed.level().getServer());
            ctx.assertTrue(bed.level().getBlockState(lava).is(Blocks.LAVA), "Rollback restores displaced fluid");
            ctx.assertTrue(WorldStore.pos(bed.ship(), "controller").equals(helm) && !bed.space().getBlockState(helm).isAir(), "The original ship remains authoritative in space");
            bed.level().setBlock(lava, Blocks.AIR.defaultBlockState(), 18);
            ctx.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void droppedLootAndLavaPermitArrivalButRemainRealObjectsAndLaunchHazards(GameTestHelper ctx)
    {
        var fixture = EntityTravelGameTest.referenceBed(ctx, 90);
        ctx.runAfterDelay(10, () ->
        {
            var bed = fixture.get();
            var base = WorldStore.pos(bed.ship(), "base");
            var departure = TravelService.transfer(bed.player(), bed.helm(), "", ShipService.inspect(bed.level(), bed.helm()));
            ctx.assertTrue(departure.outcome() == TravelService.Outcome.COMPLETED, "Fixture launches: " + departure.message());
            var loot = new ItemEntity(bed.level(), base.getX() + 3.5, base.getY() + 2, base.getZ() + 3.5, new ItemStack(Items.DIAMOND, 7));
            loot.setNoGravity(true);
            bed.level().addFreshEntity(loot);
            var lava = base.offset(2, 12, 2);
            bed.level().setBlock(lava, Blocks.LAVA.defaultBlockState(), 18);
            var ship = bed.ship();
            var helm = WorldStore.pos(ship, "controller");
            var landing = TravelService.transfer(bed.player(), helm, padName(bed), ShipService.inspect(bed.space(), helm));
            ctx.assertTrue(landing.outcome() == TravelService.Outcome.COMPLETED, "Dropped items and lava do not refuse landing: " + landing.message());
            ctx.assertTrue(!loot.isRemoved() && loot.getItem().is(Items.DIAMOND) && loot.getItem().getCount() == 7, "Landing preserves dropped loot");
            ctx.assertTrue(!WorldStore.bounds(bed.ship()).box().intersects(loot.getBoundingBox()), "Loot is moved outside the arriving hull");
            ctx.assertTrue(bed.level().getBlockState(lava).is(Blocks.LAVA), "Lava above the hull remains a hazard after arrival");
            boolean refused = false;
            try
            {
                ShipService.inspect(bed.level(), WorldStore.pos(bed.ship(), "controller"));
            }
            catch (IllegalArgumentException expected)
            {
                refused = true;
            }
            ctx.assertTrue(refused, "The lava obstruction must still be cleared before takeoff");
            loot.discard();
            bed.level().setBlock(lava, Blocks.AIR.defaultBlockState(), 18);
            ctx.succeed();
        });
    }

    private static String padName(EntityTravelGameTest.Bed bed)
    {
        return com.nullandkale.nullspace.gameplay.pads.PadRegistry.read(bed.level().getServer()).stream().filter(p -> p.id().equals(bed.padId())).findFirst().orElseThrow().name();
    }
}
