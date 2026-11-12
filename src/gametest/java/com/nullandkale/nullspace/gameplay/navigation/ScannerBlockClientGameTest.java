package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

/** The actual block interaction must open the browser, not the machine inventory. */
public final class ScannerBlockClientGameTest implements FabricClientGameTest
{
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            BlockPos[] scanner = {BlockPos.ZERO}, helm = {BlockPos.ZERO};
            String[] id = {""};
            world.getServer().runOnServer(server ->
            {
                var level = server.getLevel(TravelService.SPACE);
                var ship = StationGameTest.fixture(level, new BlockPos(0, 96, 0), true);
                id[0] = ship.getStringOr("id", "");
                helm[0] = WorldStore.pos(ship, "controller");
                scanner[0] = ShipSystems.connected(level, ship).stream().filter(pos -> level.getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)).findFirst().orElseThrow();
                // Stand on whichever side of the Scanner has a floor and headroom, facing it,
                // so the click is an ordinary in-reach right-click with nothing in the way.
                var stand = java.util.stream.Stream.of(net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST)
                    // Wall-mounted Scanners sit at head height, so also try one block lower.
                    .flatMap(d -> java.util.stream.Stream.of(scanner[0].relative(d), scanner[0].relative(d).below())).filter(at -> level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).isSolidRender()).findFirst().orElseThrow(() ->
                    {
                        var around = new StringBuilder();
                        for (var at : BlockPos.betweenClosed(scanner[0].offset(-1, -2, -1), scanner[0].offset(1, 1, 1)))
                            around.append(at.subtract(scanner[0]).toShortString()).append('=').append(level.getBlockState(at).getBlock().getDescriptionId()).append("; ");
                        return new AssertionError("Scanner has no open side to use: " + around);
                    });
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(level, stand.getX() + .5, stand.getY(), stand.getZ() + .5, Set.of(), 0, 0, true);
            });
            world.getConnection().waitForChunksRender();
            c.waitTicks(8);
            c.waitFor(mc ->
            // The server may nudge the player off the exact spot; being within reach is enough.
            mc.player != null && mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(scanner[0])) < 3, 200);
            c.getInput().lookAt(scanner[0]);
            c.waitFor(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(scanner[0]), 100);
            c.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("SCANNER"), 100);
            c.takeScreenshot("nullspace-scanner-block-browser");
            c.runOnClient(mc ->
            {
                var screen = (TerminalScreen) mc.gui.screen();
                ShipControlTestUi.click(screen, 3, 1);
                ShipControlTestUi.click(screen, 3, 1);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("STAR MAP"), 100);
            c.takeScreenshot("nullspace-scanner-approved-full-width-map");

            c.runOnClient(mc ->
            {
                var screen = (TerminalScreen) mc.gui.screen();
                ShipControlTestUi.click(screen, 11, 2);
                mc.keyboardHandler.textInput(mc.getWindow().handle(), "Sol / Luna");
                ShipControlTestUi.view(screen, "Planets & moons");
                ShipControlTestUi.click(screen, 3, 3);
                ShipControlTestUi.click(screen, 3, 23);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.status().contains("Destination set:"), 100);
            world.getServer().runOnServer(server ->
            {
                var ship = WorldStore.get(server).repository().find(id[0]);
                if (!ShipDestination.selected(ship).region().equals("sol_luna") || CourseService.active(ship) || NavigationService.pending(server))
                    throw new AssertionError("Scanner directly sets the shared destination and starts no movement");
            });
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 32, 23));
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("local_scanner"), 100);
            c.takeScreenshot("nullspace-scanner-upgrades");
            c.runOnClient(mc ->
            {
                var screen = (MachineScreen) mc.gui.screen();
                var buttons = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractButton.class::isInstance).map(net.minecraft.client.gui.components.AbstractButton.class::cast).toList();
                if (buttons.stream().anyMatch(b -> Set.of("Nearby", "Destinations", "Set destination").contains(b.getMessage().getString())))
                    throw new AssertionError("Upgrades must not expose a second scanner destination browser");
                var open = buttons.stream().filter(b -> b.getMessage().getString().equals("Open scanner")).findFirst().orElseThrow();
                screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(open.getX() + open.getWidth() / 2.0, open.getY() + open.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("SCANNER"), 100);
            c.takeScreenshot("nullspace-scanner-return-from-upgrades");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.closeContainer();
                var ship = WorldStore.get(server).repository().find(id[0]);
                var at = StationGameTest.boardingPoint(p.level(), ship);
                p.teleportTo(at.x, at.y, at.z);
                ShipControl.open(p, helm[0], "Scanner destination selected");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SHIP CONTROLLER") && t.buffer().row(5).contains("Luna"), 100);
            c.runOnClient(mc ->
            {
                var screen = (TerminalScreen) mc.gui.screen();
                if (screen.buffer().row(11).contains("Browse scanner") || screen.buffer().row(6).contains("Marked:"))
                    throw new AssertionError("Controller must not expose the Scanner feed or a mark handoff");
            });
            c.takeScreenshot("nullspace-scanner-destination-at-controller");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            int before = world.getServer().computeOnServer(server -> WorldStore.get(server).ships().size());
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(ModBlocks.STATION_CORE));
                p.teleportTo(5.5, 112, 5.5);
            });
            c.waitTicks(5);
            c.getInput().lookAt(0, -90);
            c.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT);
            try
            {
                c.waitTicks(5);
                c.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
                world.getServer().waitFor(server -> WorldStore.get(server).ships().size() == before + 1, 100);
            }
            finally
            {
                c.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT);
            }
        }
        ClientTestEvidence.passed(getClass());
    }
}
