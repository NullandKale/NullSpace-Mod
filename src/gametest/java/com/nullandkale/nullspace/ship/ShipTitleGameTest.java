package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.navigation.LocalSpaces;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Chunk 4: store a ship to clear its pad, use the pad, then deploy the same ship with its title. */
public final class ShipTitleGameTest {
  private static int shipBlocks(net.minecraft.server.level.ServerLevel level, CompoundTag ship) {
    int count = 0;
    for (var at : BlockPos.betweenClosed(ShipStore.bounds(ship).min(), ShipStore.bounds(ship).max()))
      if (!level.getBlockState(at).isAir()) count++;
    return count;
  }

  private static ItemStack takeTitle(ShipRecallGameTest.Fixture f) {
    var inventory = f.player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++)
      if (inventory.getItem(i).is(ShipBlocks.SHIP_TITLE)) return inventory.removeItemNoUpdate(i);
    return ItemStack.EMPTY;
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 600)
  public void storingClearsThePadAndDeployReturnsTheSameShipOnlyWithItsTitle(GameTestHelper ctx)
      throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 211);
    var cargo = f.base.offset(3, 1, 7);
    f.space.setBlock(cargo, Blocks.CHEST.defaultBlockState(), 18);
    ((ChestBlockEntity) f.space.getBlockEntity(cargo)).setItem(0, new ItemStack(Items.DIAMOND, 7));
    int blocks = shipBlocks(f.space, f.ship());
    String surface = f.surface.dimension().identifier().toString();
    String space = TravelService.SPACE.identifier().toString();
    ItemStack[] oldTitle = {ItemStack.EMPTY};
    int[] phase = {0}, wait = {0};
    // Phase 0: bring the ship onto the pad with the existing recall.
    ctx.assertTrue(
        ShipRecall.request(f.player, f.computer, f.selection(), null).startsWith("Recall requested"),
        "Recall onto the pad starts");
    ctx.onEachTick(
        () -> {
          if (ShipRecall.active(f.surface.getServer()) || ++wait[0] < 5) return;
          wait[0] = 0;
          var ship = f.ship();
          switch (phase[0]) {
            case 0 -> {
              ctx.assertTrue(
                  ship.getStringOr("dimension", "").equals(surface), "The ship stands on the pad");
              ctx.assertTrue(
                  ShipRecall.candidates(f.player, f.computer).getFirst().state().equals("ON_PAD"),
                  "My ships lists it as on this pad");
              String notice = ShipRecall.store(f.player, f.computer, f.selection(), null);
              ctx.assertTrue(notice.startsWith("Storing"), notice);
              phase[0] = 1;
            }
            case 1 -> {
              ctx.assertTrue(
                  ShipTitles.stored(ship) && ship.getStringOr("dimension", "").equals(space),
                  "Store moves the ship into private storage in space");
              var pad = f.padBase;
              for (var at : BlockPos.betweenClosed(pad.offset(0, 1, 0), pad.offset(11, 32, 11)))
                ctx.assertTrue(
                    f.surface.getBlockState(at).isAir(), "The pad is clear at " + at.toShortString());
              ctx.assertTrue(ShipTitles.holds(f.player, ship), "The owner receives the ship's title");
              ctx.assertTrue(
                  shipBlocks(f.space, ship) == blocks, "Every ship block is kept in storage");
              var other = ship.copy();
              other.remove("stored");
              ctx.assertTrue(
                  !LocalSpaces.together(ship, other), "A stored ship is hidden from other ships");
              ctx.assertTrue(
                  ShipRecall.candidates(f.player, f.computer).getFirst().state().equals("STORED"),
                  "My ships lists it as stored");
              // The cleared pad is usable: build on it, then clear it again.
              f.surface.setBlock(pad.offset(5, 1, 5), Blocks.STONE.defaultBlockState(), 18);
              f.surface.setBlock(pad.offset(5, 1, 5), Blocks.AIR.defaultBlockState(), 18);
              // Without the title the stored ship cannot be deployed.
              oldTitle[0] = takeTitle(f);
              String refused = ShipRecall.request(f.player, f.computer, f.selection(), null);
              ctx.assertTrue(refused.startsWith("Carry "), "Deploy needs the title: " + refused);
              f.player.getInventory().add(oldTitle[0].copy());
              String notice = ShipRecall.request(f.player, f.computer, f.selection(), null);
              ctx.assertTrue(notice.startsWith("Recall requested"), notice);
              phase[0] = 2;
            }
            case 2 -> {
              ctx.assertTrue(
                  !ShipTitles.stored(ship)
                      && ship.getStringOr("dimension", "").equals(surface)
                      && ship.getStringOr("id", "").equals(f.id),
                  "Deploy returns the same ship to the pad");
              var delta = ShipStore.pos(ship, "base").subtract(f.base);
              var chest = (ChestBlockEntity) f.surface.getBlockEntity(cargo.offset(delta));
              ctx.assertTrue(
                  chest != null && chest.getItem(0).getCount() == 7,
                  "Cargo survives storage and deployment");
              // A replacement title invalidates every older copy.
              ctx.assertTrue(
                  ShipRecall.title(f.player, f.computer, f.selection()).startsWith("You already"),
                  "No second title while one is carried");
              takeTitle(f);
              ctx.assertTrue(
                  ShipRecall.title(f.player, f.computer, f.selection()).startsWith("New title"),
                  "A lost title can be replaced by the owner");
              ctx.assertTrue(
                  !ShipTitles.valid(oldTitle[0], ship) && ShipTitles.holds(f.player, ship),
                  "The replacement works and the old paper does not");
              f.close();
              ctx.succeed();
            }
            default -> {}
          }
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
  public void handingOverASignedTitleTransfersTheSameShipAndRetiresEveryOlderCopy(
      GameTestHelper ctx) throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 214);
    var friend = ShipRecallGameTest.player(ctx, 215);
    friend.teleportTo(
        f.surface, f.computer.getX() + .5, f.computer.getY(), f.computer.getZ() + 1.5, Set.of(), 0, 0, true);
    ShipTitles.ensureHeld(f.player, f.ship());
    var paper = takeTitle(f);
    String owner = f.player.getUUID().toString();
    ctx.assertTrue(
        ShipTitles.use(friend, paper).startsWith("This title is not signed over")
            && f.ship().getStringOr("owner", "").equals(owner),
        "A found or stolen unsigned title transfers nothing");
    ctx.assertTrue(
        ShipTitles.use(f.player, paper).startsWith("Title signed over"), "The owner signs it over");
    var copyBeforeHandover = paper.copy();
    String taken = ShipTitles.use(friend, paper);
    ctx.assertTrue(taken.startsWith("You now own"), taken);
    var ship = f.ship();
    ctx.assertTrue(
        ship.getStringOr("owner", "").equals(friend.getUUID().toString())
            && ship.getStringOr("id", "").equals(f.id),
        "The same vessel now belongs to the friend");
    ctx.assertTrue(
        ShipTitles.valid(paper, ship) && !ShipTitles.valid(copyBeforeHandover, ship),
        "The handed-over paper works; a copy made before the handover does not");
    String key =
        ShipRecall.candidates(friend, f.computer).getFirst().id().replace("-", "");
    ctx.assertTrue(
        ShipRecall.candidates(f.player, f.computer).isEmpty()
            && ShipRecall.title(f.player, f.computer, key).startsWith("Only the owner"),
        "The former owner can neither deploy it nor issue a new title");
    ctx.assertTrue(
        ShipTitles.use(f.player, copyBeforeHandover).startsWith("This title is no longer valid"),
        "An old copy cannot sign the ship away again");
    f.surface.getServer().getPlayerList().remove(friend);
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
  public void aShipInSpaceIsStoredWhereItIsAndOnlyItsOwnerCanStoreIt(GameTestHelper ctx)
      throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 212);
    var stranger = ShipRecallGameTest.player(ctx, 213);
    stranger.teleportTo(
        f.surface, f.computer.getX() + .5, f.computer.getY(), f.computer.getZ() + 1.5, Set.of(), 0, 0, true);
    String key = f.selection();
    ctx.assertTrue(
        !ShipRecall.store(stranger, f.computer, key, null).contains("stored"),
        "Another player cannot store someone else's ship");
    String notice = ShipRecall.store(f.player, f.computer, key, null);
    ctx.assertTrue(notice.contains("stored where it was"), notice);
    ctx.assertTrue(
        ShipTitles.stored(f.ship()) && ShipTitles.holds(f.player, f.ship()),
        "A ship in space is stored in place and its owner holds the title");
    f.surface.getServer().getPlayerList().remove(stranger);
    f.close();
    ctx.succeed();
  }
}
