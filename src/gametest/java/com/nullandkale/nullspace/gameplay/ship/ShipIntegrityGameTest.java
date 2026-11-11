package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.ControllerBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.UnresolvedJournals;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** The chunk-load check: controllers carry their ship's id and the store is compared with them in both directions. */
public final class ShipIntegrityGameTest
{
    /** A registered ship on its own pad. Returns its controller. */
    private static BlockPos ship(ServerLevel level, BlockPos base, String pad) throws java.io.IOException
    {
        for (int x = base.getX() >> 4; x <= (base.getX() >> 4) + 2; x++)
            for (int z = base.getZ() >> 4; z <= (base.getZ() >> 4) + 2; z++)
                level.setChunkForced(x, z, true);
        ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), pad, false);
        var controller = base.offset(6, 3, 3);
        SystemsUpgrade.upgrade(level, ShipTopology.resolve(level, controller));
        return controller;
    }

    private static List<UnresolvedJournals.Entry> checks(net.minecraft.server.MinecraftServer server)
    {
        // Other tests share the world; this test's ships stand between x 8192..8300 and z 9000..9400.
        return UnresolvedJournals.all(server).stream().filter(e -> e.kind().equals(ShipIntegrity.CHECK) && e.min().getX() >= 8192 && e.min().getX() < 8300 && e.min().getZ() >= 9000 && e.min().getZ() < 9400).toList();
    }

    @GameTest(environment = "nullspace:ship_integrity")
    public void controllersAreStampedAndRealDisagreementsLockTheShipUntilAnOperatorAccepts(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var base = new BlockPos(8192, 96, 9000);
        var controller = ship(level, base, "Integrity Pad");
        var other = ship(level, new BlockPos(8192, 96, 9300), "Integrity Other");
        var store = WorldStore.get(server);
        String id = store.at(level, controller).getStringOr("id", "");
        String otherId = store.at(level, other).getStringOr("id", "");
        var chunk = level.getChunkAt(controller);
        var be = (ControllerBlockEntity) level.getBlockEntity(controller);

        ctx.assertTrue(be.ship.equals(id), "Registering a ship stamps its controller");
        be.ship = "";
        ShipIntegrity.check(level, chunk);
        ctx.assertTrue(be.ship.equals(id), "A ship from before the upgrade has no id on its controller; the first load where record and controller agree stamps it");
        ctx.assertTrue(checks(server).isEmpty(), "Agreement flags nothing");

        // A controller that is simply gone is an ordinary state of a damaged ship: never flagged.
        level.setBlock(controller, Blocks.AIR.defaultBlockState(), 18);
        ShipIntegrity.check(level, chunk);
        ctx.assertTrue(checks(server).isEmpty(), "A missing controller is not a disagreement");
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        var replaced = (ControllerBlockEntity) level.getBlockEntity(controller);

        // A stamp left by a ship the store no longer has is not a disagreement between ships: the record that stands here wins.
        replaced.ship = "a-ship-the-store-no-longer-has";
        ShipIntegrity.check(level, chunk);
        ctx.assertTrue(replaced.ship.equals(id) && checks(server).isEmpty(), "A stale stamp is replaced with the id of the record that stands at the controller");

        // Two ships that both exist claim the same controller: a real disagreement.
        replaced.ship = otherId;
        ShipIntegrity.check(level, chunk);
        var flagged = checks(server);
        ctx.assertTrue(flagged.size() == 1 && flagged.getFirst().ships().containsAll(List.of(id, otherId)), "A controller that carries another existing ship's id is flagged once, naming both ships");
        ctx.assertTrue(UnresolvedJournals.shipBusy(server, id) && TransferLocks.physicalBusy(server, store.at(level, controller)), "The flagged ship cannot travel, be recalled or be stored");
        ShipIntegrity.check(level, chunk);
        ctx.assertTrue(checks(server).size() == 1, "Checking again does not flag it twice");
        ShipIntegrity.accept(server, flagged.getFirst());
        ctx.assertTrue(replaced.ship.isEmpty() && checks(server).isEmpty() && !UnresolvedJournals.shipBusy(server, id), "Accepting clears the stamp and releases the ship");

        // A controller stamped for a ship the store does not record, with no record placing a controller there: a store older than the world.
        var ghost = base.offset(20, 3, 3);
        level.setBlock(ghost, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
        ((ControllerBlockEntity) level.getBlockEntity(ghost)).ship = "ship-the-store-never-heard-of";
        ShipIntegrity.check(level, level.getChunkAt(ghost));
        flagged = checks(server);
        ctx.assertTrue(flagged.size() == 1 && flagged.getFirst().reason().contains("does not record"), "A controller of an unrecorded ship is listed with its position");
        ShipIntegrity.accept(server, flagged.getFirst());
        ctx.assertTrue(((ControllerBlockEntity) level.getBlockEntity(ghost)).ship.isEmpty() && checks(server).isEmpty(), "Accepting clears the stale stamp");
        level.setBlock(ghost, Blocks.AIR.defaultBlockState(), 18);
        ctx.succeed();
    }
}
