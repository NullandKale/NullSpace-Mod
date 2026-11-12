package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Chunk 4: store a ship to clear its pad, use the pad, then deploy the same ship with its title.
 */
public final class ShipTitleGameTest
{
    private static int shipBlocks(net.minecraft.server.level.ServerLevel level, CompoundTag ship)
    {
        int count = 0;
        for (var at : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
            if (!level.getBlockState(at).isAir())
                count++;
        return count;
    }

    private static ItemStack takeTitle(ShipStorageGameTest.Fixture f)
    {
        var inventory = f.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).is(ModBlocks.SHIP_TITLE))
                return inventory.removeItemNoUpdate(i);
        return ItemStack.EMPTY;
    }

    /**
     * Store follow-ups: Show blocks outlines what Store takes, offline crew go to Terra Harbor
     * instead of blocking, anyone may clear an abandoned ship off a pad, and Scrap ship needs a
     * second press and then removes the ship for good.
     */
    @GameTest(environment = "nullspace:ship_recall", maxTicks = 900)
    public void abandonedShipsCanBeClearedOfflineCrewGoToTheHarborAndScrapIsPermanent(GameTestHelper ctx) throws Exception
    {
        var f = new ShipStorageGameTest.Fixture(ctx, 219);
        var server = f.surface.getServer();
        String surface = f.surface.dimension().identifier().toString();
        var visitor = ShipStorageGameTest.player(ctx, 220);
        String owner = f.player.getUUID().toString();
        String away = UUID.randomUUID().toString(), sleeper = UUID.randomUUID().toString();
        String[] key = {""};
        int[] phase = {0}, wait = {0};
        ctx.assertTrue(ShipStorage.request(f.player, f.computer, f.selection(), null).startsWith("Recall requested"), "Recall onto the pad starts");
        ctx.onEachTick(() ->
        {
            if (ShipStorage.active(server) || ++wait[0] < 5)
                return;
            wait[0] = 0;
            var ship = f.ship();
            switch (phase[0])
            {
                case 0 ->
                {
                    ctx.assertTrue(ship.getStringOr("dimension", "").equals(surface), "On the pad");
                    key[0] = f.selection();
                    ctx.assertTrue(ShipScrap.show(f.player, f.computer, key[0]).startsWith("Showing the "), "Show blocks outlines the blocks Store takes");
                    // The owner leaves for a long time; one crew member is offline aboard.
                    ship.putString("owner", away);
                    ship.put("recallUsers", new CompoundTag());
                    var crew = new net.minecraft.nbt.ListTag();
                    var member = new CompoundTag();
                    member.putString("id", sleeper);
                    crew.add(member);
                    ship.put("crew", crew);
                    WorldStore.get(server).section("lastSeen").putLong(away, server.overworld().getGameTime() - CrewRelease.ABANDONED_TICKS - 1);
                    visitor.teleportTo(f.surface, f.computer.getX() + .5, f.computer.getY(), f.computer.getZ() + 1.5, Set.of(), 0, 0, true);
                    var listed = ShipStorage.candidates(visitor, f.computer).stream().filter(d -> d.id().replace("-", "").equals(key[0])).findFirst();
                    ctx.assertTrue(listed.isPresent() && listed.get().state().equals("ABANDONED"), "Another player sees the ship as abandoned");
                    String notice = ShipStorage.store(visitor, f.computer, key[0], null);
                    ctx.assertTrue(notice.startsWith("Storing"), "Anyone may clear it: " + notice);
                    phase[0] = 1;
                }
                case 1 ->
                {
                    ctx.assertTrue(ShipTitles.stored(ship) && ship.getStringOr("owner", "").equals(away) && !ShipTitles.holds(visitor, ship), "It moves to its owner's storage; the visitor gets no title");
                    ctx.assertTrue(ship.getListOrEmpty("crew").isEmpty() && WorldStore.get(server).list("targets").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(sleeper)), "Offline crew are taken off and will log in at Terra Harbor");
                    // Back to the real owner, who scraps it.
                    ship.putString("owner", owner);
                    ctx.assertTrue(ShipScrap.scrap(visitor, f.computer, key[0]).startsWith("Only the owner"), "Only the owner can scrap a ship");
                    ctx.assertTrue(ShipScrap.scrap(f.player, f.computer, key[0]).contains("Press Scrap ship again"), "The first press asks for confirmation");
                    ctx.assertTrue(WorldStore.get(server).repository().find(f.id) != null, "Not yet");
                    String done = ShipScrap.scrap(f.player, f.computer, key[0]);
                    ctx.assertTrue(done.contains("was scrapped"), done);
                    ctx.assertTrue(WorldStore.get(server).repository().find(f.id) == null, "A scrapped ship is gone for good");
                    server.getPlayerList().remove(visitor);
                    f.close();
                    ctx.succeed();
                }
                default ->
                    {
                }
            }
        });
    }

    @GameTest(environment = "nullspace:ship_recall", maxTicks = 600)
    public void storingClearsThePadAndDeployReturnsTheSameShipOnlyWithItsTitle(GameTestHelper ctx) throws Exception
    {
        var f = new ShipStorageGameTest.Fixture(ctx, 211);
        var cargo = f.base.offset(3, 1, 7);
        f.space.setBlock(cargo, Blocks.CHEST.defaultBlockState(), 18);
        ((ChestBlockEntity) f.space.getBlockEntity(cargo)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        int blocks = shipBlocks(f.space, f.ship());
        String surface = f.surface.dimension().identifier().toString();
        String space = TravelService.SPACE.identifier().toString();
        ItemStack[] oldTitle = {ItemStack.EMPTY};
        int[] phase = {0}, wait = {0};
        // Phase 0: bring the ship onto the pad with the existing recall.
        ctx.assertTrue(ShipStorage.request(f.player, f.computer, f.selection(), null).startsWith("Recall requested"), "Recall onto the pad starts");
        ctx.onEachTick(() ->
        {
            if (ShipStorage.active(f.surface.getServer()) || ++wait[0] < 5)
                return;
            wait[0] = 0;
            var ship = f.ship();
            switch (phase[0])
            {
                case 0 ->
                {
                    ctx.assertTrue(ship.getStringOr("dimension", "").equals(surface), "The ship stands on the pad");
                    ctx.assertTrue(ShipStorage.candidates(f.player, f.computer).getFirst().state().equals("ON_PAD"), "My ships lists it as on this pad");
                    String notice = ShipStorage.store(f.player, f.computer, f.selection(), null);
                    ctx.assertTrue(notice.startsWith("Storing"), notice);
                    phase[0] = 1;
                }
                case 1 ->
                {
                    ctx.assertTrue(ShipTitles.stored(ship) && ship.getStringOr("dimension", "").equals(space), "Store moves the ship into private storage in space");
                    var pad = f.padBase;
                    for (var at : BlockPos.betweenClosed(pad.offset(0, 1, 0), pad.offset(11, 32, 11)))
                        ctx.assertTrue(f.surface.getBlockState(at).isAir(), "The pad is clear at " + at.toShortString());
                    ctx.assertTrue(ShipTitles.holds(f.player, ship), "The owner receives the ship's title");
                    ctx.assertTrue(shipBlocks(f.space, ship) == blocks, "Every ship block is kept in storage");
                    var other = ship.copy();
                    other.remove("stored");
                    ctx.assertTrue(!LocalSpaces.together(ship, other), "A stored ship is hidden from other ships");
                    ctx.assertTrue(ShipStorage.candidates(f.player, f.computer).getFirst().state().equals("STORED"), "My ships lists it as stored");
                    // The cleared pad is usable: build on it, then clear it again.
                    f.surface.setBlock(pad.offset(5, 1, 5), Blocks.STONE.defaultBlockState(), 18);
                    f.surface.setBlock(pad.offset(5, 1, 5), Blocks.AIR.defaultBlockState(), 18);
                    // Playtest 3: the owner deploys without the paper, so losing a title costs nothing.
                    oldTitle[0] = takeTitle(f);
                    String notice = ShipStorage.request(f.player, f.computer, f.selection(), null);
                    f.player.getInventory().add(oldTitle[0].copy());
                    ctx.assertTrue(notice.startsWith("Recall requested"), notice);
                    phase[0] = 2;
                }
                case 2 ->
                {
                    ctx.assertTrue(!ShipTitles.stored(ship) && ship.getStringOr("dimension", "").equals(surface) && ship.getStringOr("id", "").equals(f.id), "Deploy returns the same ship to the pad");
                    var delta = WorldStore.pos(ship, "base").subtract(f.base);
                    var chest = (ChestBlockEntity) f.surface.getBlockEntity(cargo.offset(delta));
                    ctx.assertTrue(chest != null && chest.getItem(0).getCount() == 7, "Cargo survives storage and deployment");
                    ctx.assertTrue(!ShipTitles.holds(f.player, ship), "Successful deployment consumes matching carried titles");
                    // A replacement title invalidates every older copy.
                    ctx.assertTrue(ShipStorage.title(f.player, f.computer, f.selection()).startsWith("New title"), "A lost title can be replaced by the owner");
                    ctx.assertTrue(ShipStorage.title(f.player, f.computer, f.selection()).startsWith("You already"), "No second title while one is carried");
                    ctx.assertTrue(!ShipTitles.valid(oldTitle[0], ship) && ShipTitles.holds(f.player, ship), "The replacement works and the old paper does not");
                    // A stale copy is visibly marked void, once; the real title is untouched.
                    var stale = oldTitle[0].copy();
                    ctx.assertTrue(ShipTitles.markIfVoid(f.surface.getServer(), stale) && stale.getHoverName().getString().startsWith("Void Ship Title") && !ShipTitles.markIfVoid(f.surface.getServer(), stale), "Stale titles are marked void exactly once");
                    var inventory = f.player.getInventory();
                    for (int i = 0; i < inventory.getContainerSize(); i++)
                        ctx.assertTrue(!ShipTitles.markIfVoid(f.surface.getServer(), inventory.getItem(i)), "The current title is never marked void");
                    ctx.assertTrue(ShipStorage.store(f.player, f.computer, f.selection(), null).startsWith("Storing"), "The next storage cycle starts");
                    phase[0] = 3;
                }
                case 3, 5 ->
                {
                    int titles = 0;
                    for (int i = 0; i < f.player.getInventory().getContainerSize(); i++)
                        if (ShipTitles.valid(f.player.getInventory().getItem(i), ship))
                            titles += f.player.getInventory().getItem(i).getCount();
                    ctx.assertTrue(titles == 1, "Repeated storage issues exactly one current title");
                    ctx.assertTrue(ShipStorage.request(f.player, f.computer, f.selection(), null).startsWith("Recall requested"), "Repeated deployment starts");
                    phase[0]++;
                }
                case 4 ->
                {
                    ctx.assertTrue(!ShipTitles.holds(f.player, ship) && !ShipTitles.stored(ship), "The second deployment consumes its title");
                    ctx.assertTrue(ShipStorage.store(f.player, f.computer, f.selection(), null).startsWith("Storing"), "The third storage cycle starts");
                    phase[0] = 5;
                }
                case 6 ->
                {
                    ctx.assertTrue(!ShipTitles.holds(f.player, ship) && !ShipTitles.stored(ship), "The third deployment leaves no extra title");
                    f.close();
                    ctx.succeed();
                }
                default ->
                    {
                }
            }
        });
    }

    @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
    public void handingOverASignedTitleTransfersTheSameShipAndRetiresEveryOlderCopy(GameTestHelper ctx) throws Exception
    {
        var f = new ShipStorageGameTest.Fixture(ctx, 214);
        var friend = ShipStorageGameTest.player(ctx, 215);
        friend.teleportTo(f.surface, f.computer.getX() + .5, f.computer.getY(), f.computer.getZ() + 1.5, Set.of(), 0, 0, true);
        ShipTitles.ensureHeld(f.player, f.ship());
        var paper = takeTitle(f);
        String owner = f.player.getUUID().toString();
        ctx.assertTrue(ShipTitles.use(friend, paper).startsWith("This title is not signed over") && f.ship().getStringOr("owner", "").equals(owner), "A found or stolen unsigned title transfers nothing");
        ctx.assertTrue(ShipTitles.use(f.player, paper).startsWith("Title signed over"), "The owner signs it over");
        var copyBeforeHandover = paper.copy();
        String taken = ShipTitles.use(friend, paper);
        ctx.assertTrue(taken.startsWith("You now own"), taken);
        var ship = f.ship();
        ctx.assertTrue(ship.getStringOr("owner", "").equals(friend.getUUID().toString()) && ship.getStringOr("id", "").equals(f.id), "The same vessel now belongs to the friend");
        ctx.assertTrue(ShipTitles.valid(paper, ship) && !ShipTitles.valid(copyBeforeHandover, ship), "The handed-over paper works; a copy made before the handover does not");
        String key = ShipStorage.candidates(friend, f.computer).getFirst().id().replace("-", "");
        ctx.assertTrue(ShipStorage.candidates(f.player, f.computer).isEmpty() && ShipStorage.title(f.player, f.computer, key).startsWith("Only the owner"), "The former owner can neither deploy it nor issue a new title");
        ctx.assertTrue(ShipTitles.use(f.player, copyBeforeHandover).startsWith("This title is void"), "An old copy cannot sign the ship away again");
        f.surface.getServer().getPlayerList().remove(friend);
        f.close();
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
    public void aShipInSpaceIsStoredWhereItIsAndOnlyItsOwnerCanStoreIt(GameTestHelper ctx) throws Exception
    {
        var f = new ShipStorageGameTest.Fixture(ctx, 212);
        var stranger = ShipStorageGameTest.player(ctx, 213);
        stranger.teleportTo(f.surface, f.computer.getX() + .5, f.computer.getY(), f.computer.getZ() + 1.5, Set.of(), 0, 0, true);
        String key = f.selection();
        ctx.assertTrue(!ShipStorage.store(stranger, f.computer, key, null).contains("stored"), "Another player cannot store someone else's ship");
        String notice = ShipStorage.store(f.player, f.computer, key, null);
        ctx.assertTrue(notice.contains("stored where it was"), notice);
        ctx.assertTrue(ShipTitles.stored(f.ship()) && ShipTitles.holds(f.player, f.ship()), "A ship in space is stored in place and its owner holds the title");
        f.surface.getServer().getPlayerList().remove(stranger);
        f.close();
        ctx.succeed();
    }
}
