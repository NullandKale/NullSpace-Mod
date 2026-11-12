package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenu;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.navigation.CourseService;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.MachineTestUi;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;

import com.mojang.blaze3d.platform.InputConstants;

/** Real terminal packets and loading-screen acknowledgments, without synthetic readiness. */
public final class StationClientGameTest implements FabricClientGameTest
{
    public static void walkThrough(ClientGameTestContext c, TestSingleplayerContext world, CompoundTag vessel, BlockPos base)
    {
        world.getServer().runOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            p.closeContainer();
            var port = DockingPorts.readPort(p.level(), vessel, base, false);
            var at = port.anchors().getFirst();
            p.teleportTo(p.level(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), port.facing().toYRot(), 0, true);
        });
        c.waitFor(mc -> mc.gui.screen() == null, 100);
        c.waitTicks(8);
        c.getInput().lookAt(base.above());
        c.waitTicks(3);
        boolean open = world.getServer().computeOnServer(server -> ShipDoors.open(world.getConnection().getServerPlayer().level().getBlockState(base.above())));
        if (!open)
        {
            c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            world.getServer().waitFor(server -> ShipDoors.open(world.getConnection().getServerPlayer().level().getBlockState(base.above())), 100);
        }
        float yaw = world.getServer().computeOnServer(server -> DockingPorts.readPort(world.getConnection().getServerPlayer().level(), vessel, base, false).facing().toYRot());
        c.getInput().lookAt(yaw, 0);
        double startX = world.getServer().computeOnServer(server -> world.getConnection().getServerPlayer().getX());
        c.getInput().holdKey(InputConstants.KEY_W);
        try
        {
            c.waitFor(mc -> mc.gui.screen() == null && mc.player != null && Math.abs(mc.player.getX() - startX) > 100, 600);
        }
        finally
        {
            c.getInput().releaseKey(InputConstants.KEY_W);
        }
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            var vessels = new CompoundTag[2];
            var doors = new BlockPos[2];
            var powerRequests = new int[2][];
            world.getServer().runOnServer(server ->
            {
                var level = server.getLevel(TravelService.SPACE);
                var a = new BlockPos(0, 96, 0);
                var b = new BlockPos(512, 96, 0);
                vessels[0] = StationGameTest.fixture(level, a, true);
                vessels[0].putString("home", "Halcyon Courier - abandoned freighter");
                vessels[1] = StationGameTest.fixture(level, b, false);
                var sourcePort = DockingPorts.firstPort(level, vessels[0]);
                for (var door : List.of(sourcePort.outer(), sourcePort.outer().above()))
                    level.setBlock(door, level.getBlockState(door).setValue(net.minecraft.world.level.block.DoorBlock.FACING, sourcePort.facing().getOpposite()), 18);
                var p = world.getConnection().getServerPlayer();
                vessels[1].putString("owner", p.getUUID().toString());
                var start = StationGameTest.boardingPoint(level, vessels[0]);
                p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
                p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
                doors[0] = DockingPorts.firstPort(level, vessels[0]).controller();
                doors[1] = DockingPorts.ports(level, vessels[1]).getFirst().controller();
                ShipService.open(p, WorldStore.pos(vessels[0], "controller"), "Station docking test");
            });
            world.getConnection().waitForChunksRender();
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var scanner = ShipSystems.connected(p.level(), vessels[0]).stream().filter(pos -> p.level().getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)).findFirst().orElseThrow();
                ((MachineBlockEntity) p.level().getBlockEntity(scanner)).setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
                p.teleportTo(scanner.getX() + .5, scanner.getY(), scanner.getZ() + 1.5);
                MachineMenus.open(p, scanner);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("local_scanner"), 200);
            c.runOnClient(mc ->
            {
                var screen = (MachineScreen) mc.gui.screen();
                var buttons = screen.children().stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button).map(w -> (net.minecraft.client.gui.components.Button) w).toList();
                if (buttons.stream().anyMatch(b -> Set.of("Nearby", "Destinations", "Set destination").contains(b.getMessage().getString())))
                    throw new AssertionError("Equipment upgrades cannot expose the retired destination browser");
                var open = buttons.stream().filter(b -> b.getMessage().getString().equals("Open scanner")).findFirst().orElseThrow();
                screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(open.getX() + open.getWidth() / 2.0, open.getY() + open.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SCANNER"), 100);
            ShipControlTestUi.scannerDestination(c, "Test station", "Stations");
            world.getServer().runOnServer(server ->
            {
                var selected = ShipDestination.selected(vessels[0]);
                if (selected == null || !selected.reference().equals(vessels[1].getStringOr("id", "")))
                    throw new AssertionError("The unified Scanner must select this station for the real docking course");
            });
            c.takeScreenshot("nullspace-scanner-destination");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var at = StationGameTest.boardingPoint(p.level(), vessels[0]);
                p.teleportTo(at.x, at.y, at.z);
                ShipService.open(p, WorldStore.pos(vessels[0], "controller"), "Scanner target selected");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(5).contains("Test station"), 200);
            c.takeScreenshot("nullspace-unified-docking");
            ShipControlTestUi.action(c, ShipControl.Action.ENGAGE);
            world.getServer().waitFor(server -> vessels[0].getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED") && !TransferLocks.inTransit(server, vessels[0]) && !CourseService.active(vessels[0]), 1800);
            c.runOnClient(mc ->
            {
                if (mc.gui.screen() != null)
                    mc.gui.screen().onClose();
            });
            c.waitTicks(4);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                doors[1] = WorldStore.pos(vessels[1], "base").offset(WorldStore.pos(vessels[0].getCompoundOrEmpty("dock"), "targetPort"));
                var at = DockingPorts.readPort(p.level(), vessels[0], doors[0], true).anchors().getFirst();
                p.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                MachineMenus.open(p, doors[0]);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen, 200);
            c.takeScreenshot("nullspace-station-dock-controls");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            world.getServer().runOnServer(server ->
            {
                var level = server.getLevel(TravelService.SPACE);
                for (int i = 0; i < 2; i++)
                {
                    var systems = vessels[i].getCompoundOrEmpty("systems");
                    powerRequests[i] = systems.getIntArray("requests").orElseThrow().clone();
                    systems.putIntArray("requests", new int[6]);
                    ShipSystems.tick(level, vessels[i]);
                    if (ShipSystems.power(level, vessels[i], PowerBus.System.CONTROLS) != 0)
                        throw new AssertionError("Blackout fixture still has controls power");
                }
            });
            walkThrough(c, world, vessels[0], doors[0]);
            c.takeScreenshot("nullspace-docked-blackout-arrival");
            world.getServer().runOnServer(server ->
            {
                for (int i = 0; i < 2; i++)
                {
                    vessels[i].getCompoundOrEmpty("systems").putIntArray("requests", powerRequests[i]);
                    ShipSystems.tick(server.getLevel(TravelService.SPACE), vessels[i]);
                }
            });
            world.getConnection().waitForChunksRender();
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(p.level(), 520.5, 99, 8.5, Set.of(), -90, 5, true);
                ShipDoors.setOpen(p.level(), new BlockPos(527, 99, 8), true);
            });
            c.waitTicks(12);
            c.takeScreenshot("nullspace-waypoint-station-interior");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                if (p.getInventory().countItem(Items.DIAMOND) != 7)
                    throw new AssertionError("Station boarding changed inventory");
            });
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var core = WorldStore.pos(vessels[1], "controller");
                p.teleportTo(core.getX() + .5, core.getY(), core.getZ() + 1.5);
                StationService.open(p, core, "");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("STATION CORE"), 200);
            c.runOnClient(mc ->
            {
                var t = (TerminalScreen) mc.gui.screen();
                ShipControlTestUi.click(t, 8, 3);
                t.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
                mc.keyboardHandler.textInput(mc.getWindow().handle(), "Test station updated");
                ShipControlTestUi.click(t, 60, 3);
            });
            world.getServer().waitFor(server -> vessels[1].getStringOr("home", "").equals("Test station updated"), 100);
            c.waitTicks(6);
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 4, 14));
            world.getServer().waitFor(server -> vessels[1].getStringOr("access", "").equals("private"), 100);
            c.takeScreenshot("nullspace-station-core");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(4);
            world.getServer().runOnServer(server ->
            {
                for (var vessel : vessels)
                {
                    vessel.getCompoundOrEmpty("systems").putIntArray("requests", new int[6]);
                    ShipSystems.tick(server.getLevel(TravelService.SPACE), vessel);
                }
            });
            walkThrough(c, world, vessels[1], doors[1]);
            world.getServer().runOnServer(server ->
            {
                for (int i = 0; i < 2; i++)
                {
                    vessels[i].getCompoundOrEmpty("systems").putIntArray("requests", powerRequests[i]);
                    ShipSystems.tick(server.getLevel(TravelService.SPACE), vessels[i]);
                }
            });
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                if (p.getInventory().countItem(Items.DIAMOND) != 7)
                    throw new AssertionError("Station return changed inventory");
                ShipService.open(p, WorldStore.pos(vessels[0], "controller"), "");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            c.waitTicks(4);
            ShipControlTestUi.action(c, ShipControl.Action.UNDOCK);
            world.getServer().waitFor(server -> !vessels[0].contains("dock"), 600);
            ClientTestEvidence.passed(getClass());
        }
    }
}
