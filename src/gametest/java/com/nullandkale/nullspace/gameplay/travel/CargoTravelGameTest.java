package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.Occupants;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;

/** Everything aboard travels: leashed animals, riders, vehicles, decorations, liquids, clocks. */
public final class CargoTravelGameTest {
  private static void registerPad(GameTestHelper ctx, BlockPos base, String name) {
    var level = ctx.getLevel();
    var bounds =
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
    var file =
        level
            .getServer()
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("nullspace-pads.json");
    try {
      var records =
          java.nio.file.Files.exists(file)
              ? com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
                  .getAsJsonArray()
              : new com.google.gson.JsonArray();
      records.asList().removeIf(t -> t.getAsJsonObject().get("name").getAsString().equals(name));
      var entry = new com.google.gson.JsonObject();
      entry.addProperty("id", UUID.randomUUID().toString());
      entry.addProperty("name", name);
      entry.addProperty("dimension", level.dimension().identifier().toString());
      entry.addProperty("computer", base.offset(-1, 1, 3).asLong());
      entry.addProperty("minX", bounds.minX());
      entry.addProperty("maxX", bounds.maxX());
      entry.addProperty("minZ", bounds.minZ());
      entry.addProperty("maxZ", bounds.maxZ());
      entry.addProperty("y", bounds.y());
      records.add(entry);
      java.nio.file.Files.writeString(file, records.toString());
      PadRegistry.reload(level.getServer());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static <T extends Entity> T spawn(
      GameTestHelper ctx, EntityType<T> type, BlockPos base, double x, double z) {
    var entity = type.create(ctx.getLevel(), EntitySpawnReason.COMMAND);
    entity.setPos(base.getX() + x, base.getY() + 3, base.getZ() + z);
    if (entity instanceof Mob mob) mob.setNoAi(true);
    ctx.getLevel().addFreshEntity(entity);
    return entity;
  }

  @GameTest(maxTicks = 200)
  public void leashedAnimalsRidersVehiclesDecorationsLiquidsAndClocksAllTravel(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(10240, 64, 10240));
    var controller = ShipTestFixture.build(level, base);
    String name = "Cargo " + UUID.randomUUID().toString().substring(0, 6);
    registerPad(ctx, base, name);
    for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
        level.setChunkForced(x, z, true);

    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
    var trader = spawn(ctx, EntityTypes.WANDERING_TRADER, base, 1.5, 4.5);
    // A naturally spawned trader has this timer; at 0 its leashed llamas despawn at once.
    trader.setDespawnDelay(48000);
    var llama = spawn(ctx, EntityTypes.TRADER_LLAMA, base, 2.5, 4.5);
    llama.setLeashedTo(trader, true);
    var cow = spawn(ctx, EntityTypes.COW, base, 3.5, 4.5);
    cow.setLeashedTo(player, true);
    var boat = spawn(ctx, EntityTypes.OAK_BOAT, base, 4.5, 1.5);
    var pig = spawn(ctx, EntityTypes.PIG, base, 4.5, 1.5);
    pig.startRiding(boat, true, false);
    var stand = spawn(ctx, EntityTypes.ARMOR_STAND, base, 2.5, 1.5);
    var slab = base.offset(1, 3, 5); // Back corner of the cabin, off the walkway.
    level.setBlock(
        slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true), 18);
    var repeater = base.offset(1, 3, 4);
    level.setBlock(repeater, Blocks.REPEATER.defaultBlockState(), 18);
    level.scheduleTick(repeater, Blocks.REPEATER, 400);

    int slot = 0;
    while (!TransferLocks.spaceSlotFree(server, slot)) slot++;
    server
        .getLevel(TravelService.SPACE)
        .setChunkForced((slot * TravelService.SPACE_SPACING) >> 4, 0, true);
    var occupants = List.of(trader, llama, cow, boat, pig, stand);
    var ids = occupants.stream().map(Entity::getUUID).toList();
    ctx.runAfterDelay(
        10,
        () -> {
          var scan = ShipService.inspect(level, controller);
          var cargo = Occupants.inspect(level, scan.scan()).cargo();
          for (var e : occupants)
            ctx.assertTrue(
                cargo.stream().anyMatch(c -> c.getUUID().equals(e.getUUID())),
                "Inspection accepts every occupant as cargo: "
                    + e
                    + " removed="
                    + e.getRemovalReason()
                    + " in bounds "
                    + scan.scan().bounds());
          var result = TravelService.transfer(player, controller, "", scan);
          ctx.assertTrue(
              result.outcome() == TravelService.Outcome.COMPLETED,
              "The ship launches with all of it aboard: " + result.message());
          var space = server.getLevel(TravelService.SPACE);
          var ship = WorldStore.get(server).at(space, player.blockPosition());
          var delta = WorldStore.pos(ship, "base").subtract(base.above());
          var moved = new HashMap<UUID, Entity>();
          for (var e : HullEntities.inBounds(space, WorldStore.bounds(ship)))
            moved.put(e.getUUID(), e);
          for (var id : ids)
            ctx.assertTrue(
                moved.containsKey(id) && level.getEntity(id) == null,
                "Exactly one copy of each occupant arrives in space: " + id);
          ctx.assertTrue(
              moved.get(llama.getUUID()) instanceof Leashable l
                  && l.getLeashHolder() != null
                  && l.getLeashHolder().getUUID().equals(trader.getUUID()),
              "The trader's llama is still leashed to the trader");
          ctx.assertTrue(
              moved.get(cow.getUUID()) instanceof Leashable l && l.getLeashHolder() == player,
              "The cow is still on the lead the player holds");
          ctx.assertTrue(
              moved.get(pig.getUUID()).getVehicle() != null
                  && moved.get(pig.getUUID()).getVehicle().getUUID().equals(boat.getUUID()),
              "The pig is still riding the boat");
          ctx.assertTrue(
              moved.values().stream()
                  .noneMatch(e -> e instanceof ItemEntity item && item.getItem().is(Items.LEAD)),
              "No leads were dropped on the way");
          ctx.assertTrue(
              space.getBlockState(slab.offset(delta)).getValue(SlabBlock.WATERLOGGED),
              "Waterlogged blocks travel");
          ctx.assertTrue(
              space.getBlockTicks().hasScheduledTick(repeater.offset(delta), Blocks.REPEATER),
              "A pending block action travels with the block");
          for (var e : moved.values())
            if (!(e instanceof net.minecraft.server.level.ServerPlayer)) e.discard();
          // The ship record stays, so its space slot stays reserved for the rest of the run.
          ctx.succeed();
        });
  }
}
