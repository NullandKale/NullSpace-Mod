package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

/** Real Runtime.halt, then startup recovery in another JVM. Never opens a user's world. */
public final class ShipYardCrashGameTest
{
    private static net.minecraft.nbt.ListTag identities(CompoundTag ship)
    {
        var result = new net.minecraft.nbt.ListTag();
        for (var tag : ship.getCompoundOrEmpty("systems").getListOrEmpty("devices"))
        {
            var device = (CompoundTag) tag;
            var identity = new CompoundTag();
            identity.putString("id", device.getStringOr("id", ""));
            identity.putString("type", device.getStringOr("type", ""));
            identity.putLong("position", device.getLongOr("position", Long.MIN_VALUE));
            result.add(identity);
        }
        return result;
    }

    @GameTest(maxTicks = 300)
    public void restart(GameTestHelper ctx) throws java.io.IOException
    {
        String mode = System.getProperty("nullspace.shipyard.crash.mode", "");
        if (mode.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var base = new BlockPos(8192, 96, 8192);
        for (int x = (base.getX() - 1) >> 4; x <= (base.getX() + 16) >> 4; x++)
            for (int z = (base.getZ() - 1) >> 4; z <= (base.getZ() + 20) >> 4; z++)
                level.setChunkForced(x, z, true);
        var beacon = base.offset(-1, 1, 1);
        var marker = base.offset(-2, 0, 0);
        var file = server.getWorldPath(LevelResource.ROOT).resolve("shipyard-crash-manifest.nbt");
        var preset = ShipBlueprints.get("mule");
        var bounds = new PadScanner.Pad(base.getX(), base.getX() + 15, base.getY(), base.getZ(), base.getZ() + 19);
        var origin = ShipBlueprints.origin(preset, bounds, 32);
        var cargo = ShipBlueprints.blocks(preset).entrySet().stream().filter(entry -> entry.getValue().is(Blocks.BARREL)).findFirst().orElseThrow().getKey();
        String stage = System.getProperty("nullspace.shipyard.crash.stage");
        if (mode.equals("prepare"))
        {
            ShipYard.buildPad(level, base, 16, 20, "Crash Delivery");
            level.setBlock(beacon, ModBlocks.SHIP_BEACON.defaultBlockState(), 18);
            level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            var buyer = ctx.makeMockServerPlayerInLevel();
            buyer.teleportTo(level, base.getX() - 2.5, base.getY() + 1, base.getZ() + 1.5, Set.of(), 0, 0, true);
            // Persist the pre-existing ring and beacon; the harness must not fake their survival.
            WorldStore.get(server).save();
            server.saveEverything(true, true, true);
            ctx.runAfterDelay(25, () ->
            {
                Runnable halt = () ->
                {
                    var manifest = new CompoundTag();
                    var orders = WorldStore.get(server).list("shipyardOrders");
                    var ship = WorldStore.get(server).at(level, origin.offset(preset.helm()));
                    manifest.putString("order", !orders.isEmpty() ? ((CompoundTag) orders.getFirst()).getStringOr("id", "") : ship.getStringOr("shipyardOrder", ""));
                    if (ship != null)
                    {
                        manifest.putString("ship", ship.getStringOr("id", ""));
                        manifest.put("devices", identities(ship));
                    }
                    WorldStore.write(file, manifest);
                    Runtime.getRuntime().halt(86);
                };
                ShipYardService.placementObserver = count ->
                {
                    if (count == 7 && (stage.equals("PARTIAL_UNSAVED") || stage.equals("PARTIAL_SAVED")))
                    {
                        if (stage.equals("PARTIAL_SAVED"))
                            server.saveEverything(true, true, true);
                        halt.run();
                    }
                };
                ShipYardService.deliveryObserver = checkpoint ->
                {
                    if (checkpoint == ShipYardService.DeliveryStage.HULL_PLACED)
                    {
                        var barrel = (BarrelBlockEntity) level.getBlockEntity(origin.offset(cargo));
                        barrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
                        barrel.setChanged();
                        if (stage.equals("HULL_PLACED"))
                            server.saveEverything(true, true, true);
                    }
                    if (checkpoint.name().equals(stage))
                        halt.run();
                };
                ShipYardService.deliver(buyer, beacon, preset.id());
                throw new AssertionError("Expected process termination at " + stage);
            });
        }
        else if (mode.equals("verify"))
        {
            var manifest = WorldStore.read(file);
            ctx.runAfterDelay(80, () ->
            {
                ctx.assertTrue(WorldStore.get(server).list("shipyardOrders").isEmpty(), "Startup recovery finishes delivery without a player clicking the beacon");
                var ships = WorldStore.get(server).ships().stream().map(tag -> (CompoundTag) tag).filter(ship -> ship.getStringOr("shipyardOrder", "").equals(manifest.getStringOr("order", ""))).toList();
                ctx.assertTrue(ships.size() == 1, "A real restart creates exactly one ship per durable order");
                var ship = ships.getFirst();
                if (manifest.contains("ship"))
                {
                    ctx.assertTrue(ship.getStringOr("id", "").equals(manifest.getStringOr("ship", "")), "Committed ship identity survives restart");
                    ctx.assertTrue(identities(ship).equals(manifest.getListOrEmpty("devices")), "Installed device identities survive recovery");
                }
                ctx.assertTrue(ShipSystems.enabled(ship) && ship.getStringOr("blueprint", "").equals(preset.id()), "Recovered ship has selected blueprint and working systems");
                for (var entry : ShipBlueprints.blocks(preset).entrySet())
                    ctx.assertTrue(level.getBlockState(origin.offset(entry.getKey())).equals(entry.getValue()), "Every planned hull block survives or is reconstructed");
                if (Set.of("HULL_PLACED", "WORLD_SAVED", "ORDER_CLEARED").contains(stage))
                    ctx.assertTrue(((BarrelBlockEntity) level.getBlockEntity(origin.offset(cargo))).getItem(0).getCount() == 7, "Matching containers retain their saved inventory during replay");
                ctx.assertTrue(level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "Blocks outside the delivery are preserved");
                HullScan.inspect(level, origin.offset(preset.helm()), bounds, true);
                var id = ship.getStringOr("id", "");
                ShipYardService.recover(server);
                ShipYardService.recover(server);
                ctx.assertTrue(WorldStore.get(server).at(level, origin.offset(preset.helm())).getStringOr("id", "").equals(id), "Repeated recovery is idempotent");
                try
                {
                    Files.writeString(Path.of(System.getProperty("nullspace.shipyard.crash.result")), "passed");
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
                ctx.succeed();
            });
        }
        else
            throw new IllegalArgumentException("Unknown shipyard crash mode: " + mode);
    }
}
