package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The Scanner's upgrades screen with a Creative System Upgrade installed: a screenshot of the builder, then the real buttons.
 * The test cycles the danger and the story, types a seed, presses Start FTL, and waits for the ship to reach a signal made
 * with exactly those settings, so the buttons, their wire format and the server's reading of them are all exercised.
 */
public final class CreativeScannerClientGameTest implements FabricClientGameTest
{
    /** Presses the visible button with exactly this label, as a click at its middle. */
    private static void press(MachineScreen screen, String label)
    {
        var button = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractButton.class::isInstance).map(net.minecraft.client.gui.components.AbstractButton.class::cast).filter(b -> b.visible && b.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("No button labelled " + label));
        if (!button.active)
            throw new AssertionError("The button " + label + " is not active");
        screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }

    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            BlockPos[] scanner = {BlockPos.ZERO};
            String[] shipId = {""};
            world.getServer().runOnServer(server ->
            {
                var level = server.getLevel(TravelService.SPACE);
                var ship = StationGameTest.fixture(level, new BlockPos(0, 96, 0), true);
                shipId[0] = ship.getStringOr("id", "");
                scanner[0] = ShipSystems.connected(level, ship).stream().filter(pos -> level.getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)).findFirst().orElseThrow();
                ((MachineBlockEntity) level.getBlockEntity(scanner[0])).setItem(0, new ItemStack(ModBlocks.CREATIVE_UPGRADE));
                var stand = java.util.stream.Stream.of(net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST).flatMap(d -> java.util.stream.Stream.of(scanner[0].relative(d), scanner[0].relative(d).below())).filter(at -> level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).isSolidRender()).findFirst().orElseThrow();
                world.getConnection().getServerPlayer().teleportTo(level, stand.getX() + .5, stand.getY(), stand.getZ() + .5, Set.of(), 0, 0, true);
            });
            ClientWaits.chunksRendered(world.getConnection());
            c.waitTicks(8);
            ClientWaits.waitFor(c, mc -> mc.player != null && mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(scanner[0])) < 3, 200);
            c.getInput().lookAt(scanner[0]);
            ClientWaits.waitFor(c, mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(scanner[0]), 100);
            c.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(0).contains("SCANNER"), 100);
            c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 32, 23));
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof MachineScreen m && m.getMenu().reading.getStringOr("mode", "").equals("local_scanner"), 100);
            c.waitTicks(10);
            c.takeScreenshot("nullspace-scanner-creative-upgrade");
            // Salvage ship, danger 1, story by seed: cycle to danger 3 and the breach story, and type a seed.
            c.runOnClient(mc ->
            {
                var screen = (MachineScreen) mc.gui.screen();
                press(screen, "Danger 1");
                press(screen, "Danger 2");
                press(screen, "Story: by seed");
                press(screen, "Evacuated");
                var seed = screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).filter(b -> b.getMessage().getString().equals("Seed")).findFirst().orElseThrow(() -> new AssertionError("The builder has a seed field"));
                seed.setValue("777");
                press(screen, "Start FTL");
            });
            ClientWaits.waitFor(world.getServer(), server ->
            {
                var ship = WorldStore.get(server).repository().find(shipId[0]);
                var journey = ship == null ? new net.minecraft.nbt.CompoundTag() : ship.getCompoundOrEmpty("journey");
                return journey.getBooleanOr("autoVisit", false) && journey.getIntOr("kind", -1) == 2 && journey.getIntOr("danger", -1) == 3 && journey.getLongOr("seed", 0) == 777 && journey.getCompoundOrEmpty("custom").getIntOr("history", -1) == com.nullandkale.nullspace.worldgen.condition.DerelictHistory.BREACH.ordinal();
            }, 300);
            // The ship goes to the signal by itself once the wreck is built; the site keeps every setting the buttons sent.
            ClientWaits.waitFor(world.getServer(), server ->
            {
                var ship = WorldStore.get(server).repository().find(shipId[0]);
                if (ship == null || !ship.getCompoundOrEmpty("journey").getBooleanOr("visited", false))
                    return false;
                var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(ship.getCompoundOrEmpty("journey").getStringOr("encounter", ""));
                return site.getIntOr("danger", -1) == 3 && site.getIntOr("kind", -1) == 2 && site.getLongOr("seed", 0) == 777 && site.getIntOr("history", -1) == com.nullandkale.nullspace.worldgen.condition.DerelictHistory.BREACH.ordinal() && !site.getStringOr("vessel", "").isEmpty();
            }, 3000);
        }
        ClientTestEvidence.passed(getClass());
    }
}
