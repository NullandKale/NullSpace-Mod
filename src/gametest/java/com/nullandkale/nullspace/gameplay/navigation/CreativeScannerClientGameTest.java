package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
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
import net.minecraft.world.item.ItemStack;

/** Graphical review of the Scanner's upgrades screen with a Creative System Upgrade installed: the spawn tools. */
public final class CreativeScannerClientGameTest implements FabricClientGameTest
{
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            BlockPos[] scanner = {BlockPos.ZERO};
            world.getServer().runOnServer(server ->
            {
                var level = server.getLevel(TravelService.SPACE);
                var ship = StationGameTest.fixture(level, new BlockPos(0, 96, 0), true);
                scanner[0] = ShipSystems.connected(level, ship).stream().filter(pos -> level.getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)).findFirst().orElseThrow();
                ((MachineBlockEntity) level.getBlockEntity(scanner[0])).setItem(0, new ItemStack(ModBlocks.CREATIVE_UPGRADE));
                var stand = java.util.stream.Stream.of(net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST).flatMap(d -> java.util.stream.Stream.of(scanner[0].relative(d), scanner[0].relative(d).below())).filter(at -> level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).isSolidRender()).findFirst().orElseThrow();
                world.getConnection().getServerPlayer().teleportTo(level, stand.getX() + .5, stand.getY(), stand.getZ() + .5, Set.of(), 0, 0, true);
            });
            world.getConnection().waitForChunksRender();
            c.waitTicks(8);
            c.waitFor(mc -> mc.player != null && mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(scanner[0])) < 3, 200);
            c.getInput().lookAt(scanner[0]);
            c.waitFor(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(scanner[0]), 100);
            c.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("SCANNER"), 100);
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 32, 23));
            c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("local_scanner"), 100);
            c.waitTicks(10);
            c.takeScreenshot("nullspace-scanner-creative-upgrade");
        }
        ClientTestEvidence.passed(getClass());
    }
}
