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
import com.nullandkale.nullspace.testing.ClientWaits;
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
    /** A docked visitor and its berth as they stand once their hulls have joined: their ids and the Docking Bases under their two doors. */
    public record Docked(String visitor, String host, BlockPos visitorDoor, BlockPos hostDoor)
    {}

    private static CompoundTag vessel(net.minecraft.server.MinecraftServer server, String id)
    {
        return WorldStore.get(server).repository().find(id);
    }

    /**
     * Waits for {@code visitor} to clamp and join its berth (its hull moved beside the berth, with the crew, behind the fade) and for the fade to clear, and
     * returns where the two doors now are. Joining replaces the saved records, so read them again by id afterwards.
     */
    public static Docked docked(ClientGameTestContext c, TestSingleplayerContext world, String visitor, int ticks)
    {
        ClientWaits.waitFor(world.getServer(), server ->
        {
            var v = vessel(server, visitor);
            var d = v == null ? new CompoundTag() : v.getCompoundOrEmpty("dock");
            return d.getStringOr("state", "").equals("CLAMPED") && d.getBooleanOr("joined", false) && !TransferLocks.inTransit(server, v) && !TransferLocks.busy(world.getConnection().getServerPlayer()) && !CourseService.active(v);
        }, ticks);
        ClientWaits.waitFor(c, mc -> !(mc.gui.screen() instanceof com.nullandkale.nullspace.ui.screen.TravelScreen), 200);
        // The clamp is heard beside the berth, where the crew now are, after the loading screen.
        ClientWaits.waitFor(c, mc -> com.nullandkale.nullspace.render.sound.ClientSounds.heard("dock.clamp") > .3 && com.nullandkale.nullspace.render.sound.ClientSounds.heardHere("dock.clamp") > 0, 200);
        return world.getServer().computeOnServer(server ->
        {
            var v = vessel(server, visitor);
            var d = v.getCompoundOrEmpty("dock");
            var h = vessel(server, d.getStringOr("target", ""));
            return new Docked(visitor, h.getStringOr("id", ""), WorldStore.pos(v, "base").offset(WorldStore.pos(d, "sourcePort")), WorldStore.pos(h, "base").offset(WorldStore.pos(d, "targetPort")));
        });
    }

    /**
     * Walks from one joined vessel into the other with real input: from the chamber behind {@code fromDoor}, a click opens that door, the player walks the
     * docking arm, a click opens the far door, and they walk on into the other vessel, which makes them its crew.
     */
    public static void walkThrough(ClientGameTestContext c, TestSingleplayerContext world, String from, BlockPos fromDoor, String to, BlockPos toDoor)
    {
        float yaw = world.getServer().computeOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            p.closeContainer();
            var port = DockingPorts.readPort(p.level(), vessel(server, from), fromDoor, false);
            var at = port.anchors().getFirst();
            p.teleportTo(p.level(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), port.facing().toYRot(), 0, true);
            return port.facing().toYRot();
        });
        ClientWaits.waitFor(c, mc -> mc.gui.screen() == null, 100);
        c.waitTicks(8);
        openDoor(c, world, fromDoor);
        c.getInput().lookAt(yaw, 0);
        // Part of the arm may lie inside the far vessel's area (its hull ahead of its door), so progress is measured from the far door, outward along its facing.
        walk(c, world, () -> outward(world, to, toDoor) < 2.3);
        openDoor(c, world, toDoor);
        c.getInput().lookAt(yaw, 0);
        walk(c, world, () -> outward(world, to, toDoor) < -.7);
        ClientWaits.waitFor(world.getServer(), server -> vessel(server, to).getListOrEmpty("crew").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(world.getConnection().getServerPlayer().getUUID().toString())), 60);
    }

    /** How far the player stands outside the door over {@code base}, along the door's outward facing: negative once they are through it, in the cabin. */
    private static double outward(TestSingleplayerContext world, String vessel, BlockPos base)
    {
        return world.getServer().computeOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            var port = DockingPorts.readPort(p.level(), vessel(server, vessel), base, false);
            return p.position().subtract(net.minecraft.world.phys.Vec3.atBottomCenterOf(port.outer())).dot(com.nullandkale.nullspace.model.docking.DockingMotion.normal(port.facing()));
        });
    }

    private static void openDoor(ClientGameTestContext c, TestSingleplayerContext world, BlockPos base)
    {
        c.getInput().lookAt(base.above());
        c.waitTicks(3);
        boolean open = world.getServer().computeOnServer(server -> ShipDoors.open(world.getConnection().getServerPlayer().level().getBlockState(base.above())));
        if (!open)
        {
            c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            ClientWaits.waitFor(world.getServer(), server -> ShipDoors.open(world.getConnection().getServerPlayer().level().getBlockState(base.above())), 100);
        }
    }

    private static void walk(ClientGameTestContext c, TestSingleplayerContext world, java.util.function.BooleanSupplier arrived)
    {
        c.getInput().holdKey(InputConstants.KEY_W);
        try
        {
            for (int tick = 0; !arrived.getAsBoolean(); tick++)
            {
                if (tick > 400)
                    throw new AssertionError("The player did not get across the docking arm; standing at " + world.getServer().computeOnServer(server -> world.getConnection().getServerPlayer().position()));
                c.waitTicks(1);
            }
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
            ClientWaits.chunksRendered(world.getConnection());
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var scanner = ShipSystems.connected(p.level(), vessels[0]).stream().filter(pos -> p.level().getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)).findFirst().orElseThrow();
                ((MachineBlockEntity) p.level().getBlockEntity(scanner)).setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
                p.teleportTo(scanner.getX() + .5, scanner.getY(), scanner.getZ() + 1.5);
                MachineMenus.open(p, scanner);
            });
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("local_scanner"), 200);
            c.runOnClient(mc ->
            {
                var screen = (MachineScreen) mc.gui.screen();
                var buttons = screen.children().stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button).map(w -> (net.minecraft.client.gui.components.Button) w).toList();
                if (buttons.stream().anyMatch(b -> Set.of("Nearby", "Destinations", "Set destination").contains(b.getMessage().getString())))
                    throw new AssertionError("Equipment upgrades cannot expose the retired destination browser");
                var open = buttons.stream().filter(b -> b.getMessage().getString().equals("Open scanner")).findFirst().orElseThrow();
                screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(open.getX() + open.getWidth() / 2.0, open.getY() + open.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            });
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("SCANNER"), 100);
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
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(5).contains("Test station"), 200);
            c.takeScreenshot("nullspace-unified-docking");
            ShipControlTestUi.action(c, ShipControl.Action.ENGAGE);
            var ids = new String[]{world.getServer().computeOnServer(server -> vessels[0].getStringOr("id", "")), world.getServer().computeOnServer(server -> vessels[1].getStringOr("id", ""))};
            var docked = docked(c, world, ids[0], 1800);
            // Joining moved the ship's blocks and replaced the saved records: read them again.
            world.getServer().runOnServer(server ->
            {
                vessels[0] = vessel(server, ids[0]);
                vessels[1] = vessel(server, ids[1]);
                doors[0] = docked.visitorDoor();
                doors[1] = docked.hostDoor();
                var p = world.getConnection().getServerPlayer();
                if (!WorldStore.bounds(vessels[0]).contains(p.blockPosition()))
                    throw new AssertionError("The crew move with the hull to the berth");
            });
            c.runOnClient(mc ->
            {
                if (mc.gui.screen() != null)
                    mc.gui.screen().onClose();
            });
            c.waitTicks(4);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var at = DockingPorts.readPort(p.level(), vessels[0], doors[0], true).anchors().getFirst();
                p.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                MachineMenus.open(p, doors[0]);
            });
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof MachineScreen, 200);
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
            // The docking arm from the ship's chamber, seen through its open door.
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var port = DockingPorts.readPort(p.level(), vessels[0], doors[0], false);
                var at = port.anchors().getFirst();
                ShipDoors.setOpen(p.level(), doors[0].above(), true);
                ShipDoors.setOpen(p.level(), doors[1].above(), true);
                p.teleportTo(p.level(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), port.facing().toYRot(), 8, true);
            });
            ClientWaits.chunksRendered(world.getConnection());
            c.waitTicks(10);
            c.takeScreenshot("nullspace-docking-arm");
            world.getServer().runOnServer(server ->
            {
                ShipDoors.setOpen(world.getConnection().getServerPlayer().level(), doors[0].above(), false);
                ShipDoors.setOpen(world.getConnection().getServerPlayer().level(), doors[1].above(), false);
            });
            walkThrough(c, world, ids[0], doors[0], ids[1], doors[1]);
            c.takeScreenshot("nullspace-docked-blackout-arrival");
            world.getServer().runOnServer(server ->
            {
                for (int i = 0; i < 2; i++)
                {
                    vessels[i].getCompoundOrEmpty("systems").putIntArray("requests", powerRequests[i]);
                    ShipSystems.tick(server.getLevel(TravelService.SPACE), vessels[i]);
                }
            });
            ClientWaits.chunksRendered(world.getConnection());
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
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("STATION CORE"), 200);
            ClientWaits.withoutOverlay(c);
            c.runOnClient(mc ->
            {
                var t = (TerminalScreen) mc.gui.screen();
                ShipControlTestUi.click(t, 8, 3);
                t.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
                mc.keyboardHandler.textInput(mc.getWindow().handle(), "Test station updated");
                ShipControlTestUi.click(t, 60, 3);
            });
            ClientWaits.waitFor(world.getServer(), server -> vessels[1].getStringOr("home", "").equals("Test station updated"), 100);
            c.waitTicks(6);
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 4, 14));
            ClientWaits.waitFor(world.getServer(), server -> vessels[1].getStringOr("access", "").equals("private"), 100);
            c.takeScreenshot("nullspace-station-core");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var station = vessels[1];
                station.putString("owner", "");
                station.putBoolean("worldGenerated", true);
                station.putInt("encounterKind", 0);
                station.putString("access", "public");
                var core = WorldStore.pos(station, "controller");
                p.teleportTo(p.level(), core.getX() + .5, core.getY(), core.getZ() + 1.5, Set.of(), 0, 0, true);
                StationService.open(p, core, "");
            });
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("STATION VISITOR"), 100);
            c.takeScreenshot("nullspace-encounter-station-visitor");
            world.getServer().runOnServer(server ->
            {
                vessels[1].putBoolean("salvage", true);
                vessels[1].putInt("encounterKind", 1);
                StationService.open(world.getConnection().getServerPlayer(), WorldStore.pos(vessels[1], "controller"), "");
            });
            // Claiming is off in the game: a derelict is a wreck with no claim button.
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("DERELICT VESSEL"), 100);
            c.runOnClient(mc ->
            {
                if (ShipControlTestUi.clickLabel((TerminalScreen) mc.gui.screen(), "[ Claim vessel ]"))
                    throw new AssertionError("A derelict must not offer claiming while it is switched off");
            });
            c.takeScreenshot("nullspace-derelict-no-claim");
            world.getServer().runOnServer(server ->
            {
                com.nullandkale.nullspace.gameplay.ownership.Claims.enable(true);
                StationService.open(world.getConnection().getServerPlayer(), WorldStore.pos(vessels[1], "controller"), "");
            });
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("UNCLAIMED VESSEL"), 100);
            c.takeScreenshot("nullspace-derelict-claim-explanation");
            c.runOnClient(mc ->
            {
                if (!ShipControlTestUi.clickLabel((TerminalScreen) mc.gui.screen(), "[ Claim vessel ]"))
                    throw new AssertionError("A derelict must offer claiming as its main action");
            });
            ClientWaits.waitFor(world.getServer(), server -> vessels[1].getStringOr("owner", "").equals(world.getConnection().getServerPlayer().getUUID().toString()) && server.getLevel(TravelService.SPACE).getBlockState(WorldStore.pos(vessels[1], "controller")).is(ModBlocks.COMPUTER), 100);
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SHIP CONTROLLER"), 100);
            world.getServer().runOnServer(server -> com.nullandkale.nullspace.gameplay.ownership.Claims.enable(false));
            ClientWaits.waitFor(world.getServer(), server -> !TransferLocks.busy(world.getConnection().getServerPlayer()), 100);
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
            walkThrough(c, world, ids[1], doors[1], ids[0], doors[0]);
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
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            c.waitTicks(4);
            Object releaseBefore = c.computeOnClient(mc -> com.nullandkale.nullspace.render.sound.ClientSounds.latest("dock.release"));
            String before = world.getServer().computeOnServer(server -> "propulsion " + com.nullandkale.nullspace.gameplay.navigation.NavigationService.propulsion(server.getLevel(TravelService.SPACE), vessel(server, ids[0])) + ", dock " + vessel(server, ids[0]).getCompoundOrEmpty("dock") + ", station crew " + vessel(server, ids[1]).getListOrEmpty("crew"));
            ShipControlTestUi.action(c, ShipControl.Action.UNDOCK);
            c.waitTicks(10);
            String screen = c.computeOnClient(mc ->
            {
                if (!(mc.gui.screen() instanceof TerminalScreen t))
                    return String.valueOf(mc.gui.screen());
                var rows = new StringBuilder();
                for (int row = 0; row < 24; row++)
                    rows.append(t.buffer().row(row).strip()).append(" | ");
                return rows.toString();
            });
            // The hull first moves to an area of its own (with the player, behind the fade), then backs away.
            try
            {
                ClientWaits.waitFor(world.getServer(), server -> vessel(server, ids[0]) != null && !vessel(server, ids[0]).contains("dock"), 900);
            }
            catch (AssertionError stuck)
            {
                String after = world.getServer().computeOnServer(server -> vessel(server, ids[0]).getStringOr("flightStatus", "") + " " + vessel(server, ids[0]).getCompoundOrEmpty("dock"));
                throw new AssertionError("Undocking never finished. Before: " + before + ". Terminal: " + screen + ". Now: " + after, stuck);
            }
            // The release is heard where the crew now are, after the loading screen, not left behind at the berth.
            ClientWaits.waitFor(c, mc -> com.nullandkale.nullspace.render.sound.ClientSounds.latest("dock.release") != releaseBefore && com.nullandkale.nullspace.render.sound.ClientSounds.heard("dock.release") > .3 && com.nullandkale.nullspace.render.sound.ClientSounds.heardHere("dock.release") > 0, 200);
            world.getServer().runOnServer(server ->
            {
                if (!WorldStore.bounds(vessel(server, ids[0])).contains(world.getConnection().getServerPlayer().blockPosition()))
                    throw new AssertionError("The crew leave with the hull");
            });
            ClientWaits.waitFor(world.getServer(), server -> !TransferLocks.busy(world.getConnection().getServerPlayer()), 100);
            ClientTestEvidence.passed(getClass());
        }
    }
}
