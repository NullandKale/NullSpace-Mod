package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Derelicts reset 2.5 in-game days after the last player left; claimed ones never do. */
public final class SiteResetGameTest {
  private record Site(String address, CompoundTag site, CompoundTag ship, BlockPos base) {}

  private static Site site(GameTestHelper ctx, String address, int offset) {
    var server = ctx.getLevel().getServer();
    var space = server.getLevel(TravelService.SPACE);
    var store = ShipStore.get(server);
    var base = new BlockPos(1_900_000 + offset * 64, 96, 1_900_000);
    var ship = new CompoundTag();
    String id = UUID.randomUUID().toString();
    ship.putString("id", id);
    ship.putString("dimension", TravelService.SPACE.identifier().toString());
    ship.putString("localSpace", address);
    ship.putLong("base", base.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    ship.putInt("height", 8);
    ship.putBoolean("salvage", true);
    store.ships().add(ship);
    // The derelict's hull, plus a chest a player left inside and a dropped item.
    for (int x = 0; x < 8; x++)
      for (int z = 0; z < 8; z++)
        space.setBlock(base.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
    space.setBlock(base.offset(3, 1, 3), Blocks.CHEST.defaultBlockState(), 18);
    var drop = new ItemEntity(space, base.getX() + 4.5, base.getY() + 1.5, base.getZ() + 4.5,
        new ItemStack(Items.DIAMOND));
    drop.setNoGravity(true);
    space.addFreshEntity(drop);
    var record = new CompoundTag();
    record.putString("id", id);
    record.putString("role", "salvage-0");
    record.putLong("seed", 42);
    record.putString("state", "placed");
    var sites = new ListTag();
    sites.add(record);
    var region = new CompoundTag();
    region.putInt("version", SpacePopulationPlan.VERSION);
    region.put("sites", sites);
    store.section("spacePopulation").put(address, region);
    // Work on the record as the ledger holds it.
    var saved =
        (CompoundTag)
            store
                .section("spacePopulation")
                .getCompoundOrEmpty(address)
                .getListOrEmpty("sites")
                .get(0);
    return new Site(address, saved, ship, base);
  }

  @GameTest(maxTicks = 40)
  public void abandonedDerelictsResetOccupiedOnesWaitAndClaimedOnesNeverReset(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var space = server.getLevel(TravelService.SPACE);
    var store = ShipStore.get(server);
    long now = server.overworld().getGameTime();

    var abandoned = site(ctx, "test_reset_abandoned", 0);
    abandoned.site().putLong("seen", now - SpacePopulation.RESET_TICKS - 1);
    var occupied = site(ctx, "test_reset_occupied", 1);
    occupied.site().putLong("seen", now - SpacePopulation.RESET_TICKS - 1);
    var claimed = site(ctx, "test_reset_claimed", 2);
    claimed.site().putLong("seen", now - SpacePopulation.RESET_TICKS - 1);
    claimed.ship().putString("owner", UUID.randomUUID().toString());
    var fresh = site(ctx, "test_reset_fresh", 3);
    fresh.site().putLong("seen", now - 100);

    SpacePopulation.resetAbandoned(server, Set.of("test_reset_occupied"));

    ctx.assertTrue(space.getBlockState(abandoned.base()).isAir(), "A reset removes the hull");
    ctx.assertTrue(
        space.getBlockState(abandoned.base().offset(3, 1, 3)).isAir(),
        "A reset removes containers players left inside");
    ctx.assertTrue(
        space.getEntitiesOfClass(ItemEntity.class, ShipStore.bounds(abandoned.ship()).box())
            .isEmpty(),
        "A reset removes dropped items: "
            + space.getEntitiesOfClass(ItemEntity.class, ShipStore.bounds(abandoned.ship()).box()));
    ctx.assertTrue(
        store.repository().find(abandoned.ship().getStringOr("id", "")) == null
            && abandoned.site().getStringOr("state", "").equals("planned")
            && abandoned.site().getLongOr("resets", 0) == 1
            && abandoned.site().getLongOr("seed", 42) != 42,
        "The site is planned again with a new seed, so population rebuilds a new variation");
    ctx.assertTrue(
        space.getBlockState(occupied.base()).is(Blocks.STONE_BRICKS)
            && occupied.site().getLongOr("seen", 0) == now,
        "A site with a player in its zone does not reset, and counts as just seen");
    ctx.assertTrue(
        space.getBlockState(claimed.base()).is(Blocks.STONE_BRICKS)
            && claimed.site().getStringOr("state", "").equals("claimed"),
        "A claimed derelict is a player's ship and never resets");
    ctx.assertTrue(
        space.getBlockState(fresh.base()).is(Blocks.STONE_BRICKS)
            && fresh.site().getStringOr("state", "").equals("placed"),
        "A site left recently waits for the full reset time");

    for (var s : List.of(occupied, claimed, fresh)) store.ships().remove(s.ship());
    for (var s : List.of(abandoned, occupied, claimed, fresh)) {
      store.section("spacePopulation").remove(s.address());
      for (var at :
          BlockPos.betweenClosed(s.base(), s.base().offset(7, 7, 7)))
        space.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
    }
    space
        .getEntitiesOfClass(
            ItemEntity.class, ShipStore.bounds(abandoned.ship()).box().inflate(256))
        .forEach(ItemEntity::discard);
    ctx.succeed();
  }
}
