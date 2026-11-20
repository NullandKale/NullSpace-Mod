package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * A storage area in space is used again once the place in it is gone, and the place before can leave entities behind: a signal's site removed while nobody
 * was near kept its item frames, because they load after their chunks, and they floated in the next signal built there. A world-generated place clears its
 * area of everything but players before its first block.
 */
public final class SiteLeftoversGameTest
{
    @GameTest(environment = "nullspace:site_leftovers", maxTicks = 2400)
    public void aPlaceBuiltWhereAnEarlierOneStoodClearsWhatThatOneLeftBehind(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var metadata = new CompoundTag();
        metadata.putBoolean("worldGenerated", true);
        metadata.putBoolean("salvage", true);
        String id = UUID.randomUUID().toString();
        var vessel = StationBuilds.queueAmbient(server, id, "Leftovers test", "test_site_leftovers", new Vec3(0, 0, 600), EncounterSites.plan(EncounterKind.DRIFTING_CARGO.id, 7), metadata);
        ctx.assertTrue(vessel != null, "The site has storage to be built in");
        var bounds = WorldStore.bounds(vessel);
        var middle = bounds.box().getCenter();
        // What the place before left: a stand from its rooms and an item that was in a frame.
        var stand = new ArmorStand(space, middle.x, middle.y, middle.z);
        stand.setNoGravity(true);
        space.addFreshEntity(stand);
        var item = new ItemEntity(space, middle.x + 1, middle.y, middle.z + 1, new ItemStack(Items.DIAMOND));
        item.setNoGravity(true);
        item.setUnlimitedLifetime();
        space.addFreshEntity(item);
        TestWaits.until(ctx, () ->
        {
            var current = WorldStore.get(server).repository().find(id);
            return current != null && StationAccess.ready(current) && !BuildJournal.pending(server, id);
        }, () ->
        {
            var current = WorldStore.get(server).repository().find(id);
            ctx.assertTrue(stand.isRemoved() && item.isRemoved(), "The new place cleared what the earlier one left: stand removed " + stand.isRemoved() + ", item removed " + item.isRemoved());
            ctx.assertTrue(space.getEntitiesOfClass(ArmorStand.class, bounds.box()).isEmpty() && space.getEntitiesOfClass(ItemEntity.class, bounds.box()).isEmpty(), "Nothing of the earlier place stands in the new one");
            SpacePopulation.dispose(server, current);
            ctx.succeed();
        });
    }
}
