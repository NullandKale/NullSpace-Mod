package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;

/** Chunk 6: claim an existing derelict without refilling it or letting its site regenerate. */
public final class ClaimsGameTest {
  private static int items(net.minecraft.server.level.ServerLevel level, CompoundTag ship) {
    int total = 0;
    for (var at :
        BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
      if (level.getBlockEntity(at) instanceof Container container)
        for (int i = 0; i < container.getContainerSize(); i++)
          total += container.getItem(i).getCount();
    return total;
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 3000)
  public void aPlayerAboardAnUnclaimedDerelictClaimsTheSameShipWithItsSuppliesAsFound(
      GameTestHelper ctx) throws Exception {
    var f = new ShipStorageGameTest.Fixture(ctx, 216);
    var server = f.surface.getServer();
    var space = f.space;
    // Built synchronously, like StationGameTest.fixture: the shared construction queue is also
    // used by tests that inject construction failures, which would only delay this check.
    var plan = EncounterSites.plan(1, 3);
    var base = f.base.offset(0, 0, 1024);
    var derelict = new CompoundTag();
    derelict.putString("id", UUID.randomUUID().toString());
    derelict.putString("dimension", space.dimension().identifier().toString());
    derelict.putLong("base", base.asLong());
    derelict.putLong("controller", base.offset(plan.core()).asLong());
    derelict.putString("home", "Claim fixture");
    derelict.putString("localSpace", LocalSpaces.HOME);
    derelict.putString("kind", "station");
    derelict.putString("owner", "");
    derelict.putString("access", "public");
    derelict.put("crew", new net.minecraft.nbt.ListTag());
    derelict.putDouble("simX", 31000);
    derelict.putDouble("simZ", 31000);
    derelict.putBoolean("salvage", true);
    derelict.putInt("encounterKind", 1);
    derelict.putLong("encounterSeed", 3);
    for (int x = -16; x < 48; x += 16)
      for (int z = -16; z < 48; z += 16)
        space.setChunkForced(
            base.offset(x, 0, z).getX() >> 4, base.offset(x, 0, z).getZ() >> 4, true);
    for (var e : plan.blocks().entrySet())
      space.setBlock(base.offset(e.getKey()), e.getValue(), 18);
    WorldStore.get(server).ships().add(derelict);
    EncounterSites.stock(space, derelict);
    String id = derelict.getStringOr("id", "");
    Runnable[] poll = {null};
    poll[0] =
        () -> {
          // Other fixtures commit real transfers between ticks, replacing repository records.
          // A player action resolves its vessel at execution time; the test must do the same.
          var current = WorldStore.get(server).repository().find(id);
          ctx.assertTrue(current != null, "The derelict remains registered before the claim");
          if (!StationService.ready(current)) {
            // A fresh lambda: runAfterDelay drops a Runnable rescheduled from inside itself.
            ctx.runAfterDelay(5, () -> poll[0].run());
            return;
          }
          ctx.assertTrue(Claims.claimable(current), "An unclaimed derelict can be claimed");
          // A world-generated service station is an ownerless record without the salvage flag.
          // Check the record only: a second physical station would share other tests' space.
          var publicStation = current.copy();
          publicStation.remove("salvage");
          ctx.assertTrue(
              !Claims.claimable(publicStation),
              "A public service station is never claimable just because it has no owner");
          ctx.assertTrue(
              Claims.claim(f.player, current).startsWith("Step aboard"),
              "Claiming needs the player physically aboard");
          int supplies = items(space, current);
          var center = WorldStore.bounds(current).box().getCenter();
          f.player.teleportTo(space, center.x, center.y, center.z, Set.of(), 0, 0, true);
          String result = Claims.claim(f.player, current);
          ctx.assertTrue(result.startsWith("You claimed"), result);
          // Verify the authoritative repository, not just a possibly detached fixture object.
          var claimed = WorldStore.get(server).repository().find(id);
          ctx.assertTrue(
              claimed != null
                  && claimed.getStringOr("kind", "").equals("ship")
                  && claimed.getStringOr("owner", "").equals(f.player.getUUID().toString()),
              "The same derelict record becomes the claimant's ship");
          ctx.assertTrue(
              space.getBlockState(WorldStore.pos(claimed, "controller")).is(ModBlocks.COMPUTER),
              "Take command: the derelict's core becomes a ship controller");
          ctx.assertTrue(ShipTitles.holds(f.player, claimed), "The claimant receives its title");
          ctx.assertTrue(
              items(space, claimed) == supplies,
              "Claiming refills nothing: supplies are exactly as found");
          ctx.assertTrue(
              !Claims.claimable(claimed)
                  && Claims.claim(f.player, claimed).startsWith("This vessel is not"),
              "A claimed ship cannot be claimed again");
          f.close();
          ctx.succeed();
        };
    ctx.runAfterDelay(5, poll[0]);
  }
}
