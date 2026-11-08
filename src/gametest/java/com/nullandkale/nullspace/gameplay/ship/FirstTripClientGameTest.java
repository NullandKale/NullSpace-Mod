package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.MachineTestUi;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.ShipPilotScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.ui.terminal.TerminalBuffer;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Normal GUI fuel/registration/launch/return, including two real preflight failures. */
public final class FirstTripClientGameTest implements FabricClientGameTest
{
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            var floor = new BlockPos(0, -59, 0);
            var pad = floor.offset(-1, 0, -1);
            var helm = floor.offset(2, 1, 4);
            var generator = floor.offset(4, 1, 2);
            var obstruction = floor.offset(1, 1, 5);
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                for (int x = -1; x <= 5; x++)
                    for (int z = -1; z <= 5; z++)
                        level.setBlock(floor.offset(x, -1, z), (x == -1 || z == -1 || x == 5 || z == 5 ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 18);
                level.setBlock(pad, PadComputers.BLOCK.defaultBlockState(), 18);
                for (int y = 0; y < 4; y++)
                    for (int x = 0; x < 5; x++)
                        for (int z = 0; z < 5; z++)
                            if (y == 0 || y == 3 || x == 0 || z == 0 || x == 4 || z == 4)
                                level.setBlock(floor.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 18);
                var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
                level.setBlock(floor.offset(2, 1, 0), door, 18);
                level.setBlock(floor.offset(2, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
                level.setBlock(helm, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 18);
                level.setBlock(generator, ModBlocks.GENERATOR.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 18);
                level.setBlock(floor.offset(0, 1, 2), ModBlocks.AIR_SUPPLY.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST), 18);
                level.setBlock(floor.offset(1, 1, 4), ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 18);
                level.setBlock(floor.offset(2, 2, 4), Blocks.GLASS.defaultBlockState(), 18);
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                p.teleportTo(2.5, -58, 3.5);
                p.getInventory().add(new ItemStack(Items.COAL, 8));
                ShipControl.open(p, helm, "First trip");
                if (WorldStore.get(server).at(level, helm) != null)
                    throw new AssertionError("Unregistered setup must not allocate a ship");
            });
            world.getConnection().waitForChunksRender();
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController() && t.status().contains("Register the pad"), 200);
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(4);
            world.getServer().runOnServer(server -> PadComputers.open(world.getConnection().getServerPlayer(), pad, "Check and register this pad"));
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("PAD COMPUTER"), 100);
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 4, 7));
            world.getServer().waitFor(server -> PadRegistry.read(server).stream().anyMatch(p -> p.name().equals("Home")), 100);
            c.takeScreenshot("nullspace-first-trip-pad");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(4);
            world.getServer().runOnServer(server -> ShipControl.open(world.getConnection().getServerPlayer(), helm, "First launch"));
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
            c.runOnClient(mc ->
            {
                var buffer = ((TerminalScreen) mc.gui.screen()).buffer();
                var rows = new StringBuilder();
                boolean hint = false;
                for (int row = 0; row < TerminalBuffer.ROWS; row++)
                {
                    var text = buffer.row(row);
                    rows.append(row).append(": ").append(text.strip()).append(" | ");
                    if (text.toLowerCase(java.util.Locale.ROOT).matches(".*(load (furnace )?fuel|fuel:? empty).*"))
                        hint = true;
                }
                if (!hint)
                    throw new AssertionError("Fuel repair must be visible before a blocked launch click: " + rows);
            });
            ShipControlTestUi.action(c, ShipControl.Action.LAUNCH);
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.status().contains("blocked"), 100);
            world.getServer().runOnServer(server ->
            {
                if (TransferLocks.active(server))
                    throw new AssertionError("No-fuel click must not start travel");
            });
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(4);
            world.getServer().runOnServer(server -> MachineMenus.open(world.getConnection().getServerPlayer(), generator));
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("generator"), 100);
            MachineTestUi.shiftItem(c, Items.COAL);
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getIntOr("fuelHere", 0) > 0, 100);
            c.takeScreenshot("nullspace-first-trip-fuel");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(4);
            world.getServer().runOnServer(server ->
            {
                server.overworld().setBlock(obstruction, Blocks.COBBLESTONE.defaultBlockState(), 18);
                ShipControl.open(world.getConnection().getServerPlayer(), helm, "Launch checks your ship before leaving");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
            c.waitTicks(12);
            ShipControlTestUi.action(c, ShipControl.Action.LAUNCH);
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.status().toLowerCase(Locale.ROOT).contains("exhaust"), 100);
            world.getServer().runOnServer(server ->
            {
                if (TransferLocks.active(server))
                    throw new AssertionError("Covered exhaust must block travel");
                server.overworld().setBlock(obstruction, Blocks.AIR.defaultBlockState(), 18);
            });
            c.takeScreenshot("nullspace-first-trip-repair");
            c.waitTicks(4);
            ShipControlTestUi.action(c, ShipControl.Action.LAUNCH);
            c.waitFor(mc -> mc.player != null && mc.player.level().dimension().equals(TravelService.SPACE) && mc.level == mc.player.level() && (mc.gui.screen() == null || mc.gui.screen() instanceof TerminalScreen), 500);
            world.getConnection().waitForChunksRender();
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var ship = WorldStore.get(server).at(p.level(), p.blockPosition());
                ShipControl.open(p, WorldStore.pos(ship, "controller"), "In orbit");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
            ShipControlTestUi.action(c, ShipControl.Action.CAMERA);
            c.waitFor(mc -> mc.gui.screen() instanceof ShipPilotScreen, 100);
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
            c.runOnClient(mc ->
            {
                if (!((TerminalScreen) mc.gui.screen()).buffer().row(5).contains("Home"))
                    throw new AssertionError("Home must stay selected without a second picker trip");
            });
            ShipControlTestUi.action(c, ShipControl.Action.LAND);
            c.waitFor(mc -> mc.player != null && mc.player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && mc.level == mc.player.level() && (mc.gui.screen() == null || mc.gui.screen() instanceof TerminalScreen), 500);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var ship = WorldStore.get(server).at(p.level(), helm);
                if (ship == null || !ship.getStringOr("home", "").equals("Home"))
                    throw new AssertionError("The same ship must return to its registered home");
                if (FuelSystem.available(p.level(), ship) <= 0)
                    throw new AssertionError("Fuel should remain after the real trip");
            });
            c.takeScreenshot("nullspace-first-trip-home");
            ClientTestEvidence.passed(getClass());
        }
    }
}
