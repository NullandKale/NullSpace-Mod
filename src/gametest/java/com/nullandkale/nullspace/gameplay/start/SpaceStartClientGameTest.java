package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.DevStoryFixture;
import com.nullandkale.nullspace.ui.screen.StartingShipScreen;
import com.nullandkale.nullspace.ui.screen.WorldStartAccess;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.*;

import com.mojang.blaze3d.platform.InputConstants;

/** Actual Create New World -> ship picker -> fresh integrated login and persisted settings. */
public final class SpaceStartClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        boolean preparing = Boolean.getBoolean("nullspace.dev.prepare");
        var preset = ShipBlueprints.get(System.getProperty("nullspace.dev.startShip", "halcyon_swift"));
        var failure = new AtomicReference<Throwable>();
        var selected = new AtomicReference<String>();
        var folder = new AtomicReference<String>();
        c.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        c.waitFor(mc -> mc.gui.screen() instanceof CreateWorldScreen, 1200);
        c.runOnClient(mc ->
        {
            var screen = (CreateWorldScreen) mc.gui.screen();
            screen.getUiState().setName(preparing ? "World 5" : "NullSpace space-start test");
            if (preparing)
            {
                screen.getUiState().setSeed("20261104");
                screen.getUiState().setAllowCommands(true);
                screen.getUiState().setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.SURVIVAL);
            }
            var access = (WorldStartAccess) screen;
            if (access.nullspace$start().space())
                throw new AssertionError("Ordinary world creation must default to a planet");
            var tabs = screen.children().stream().filter(MenuTabBar.class::isInstance).map(MenuTabBar.class::cast).findFirst().orElseThrow();
            tabs.selectTab(3, false);
            click(screen, "Start: On a planet");
            if (!access.nullspace$start().space())
                throw new AssertionError("Start button did not select space");
            click(screen, "Starting ship: Rivet Bolt");
        });
        c.waitFor(mc -> mc.gui.screen() instanceof StartingShipScreen, 100);
        c.runOnClient(mc ->
        {
            for (int page = 0; page < 15; page++)
            {
                var screen = mc.gui.screen();
                if (screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).anyMatch(b -> b.getMessage().getString().equals(preset.name() + " | " + preset.role())))
                {
                    click(screen, preset.name() + " | " + preset.role());
                    return;
                }
                click(screen, "Next");
            }
            throw new AssertionError("Starting ship is missing from the picker: " + preset.name());
        });
        c.waitFor(mc -> mc.gui.screen() instanceof CreateWorldScreen, 100);
        c.runOnClient(mc ->
        {
            var screen = (CreateWorldScreen) mc.gui.screen();
            if (!((WorldStartAccess) screen).nullspace$start().equals(new WorldStartSettings(true, preset.id())))
                throw new AssertionError("Returning from ship selection lost the start choices");
            screen.children().stream().filter(MenuTabBar.class::isInstance).map(MenuTabBar.class::cast).findFirst().orElseThrow().selectTab(3, false);
            if (screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).noneMatch(b -> b.getMessage().getString().equals("Starting ship: " + preset.name())))
                throw new AssertionError("Starting ship button must show the chosen design");
        });
        c.takeScreenshot("nullspace-world-start-options");
        c.runOnClient(mc -> click(mc.gui.screen(), "Create New World"));
        c.waitFor(mc ->
        {
            if (mc.gui.screen() instanceof ConfirmScreen screen)
            {
                // Vanilla warns when a registry/datapack is experimental.
                screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b -> java.util.Set.of("Proceed", "Yes").contains(b.getMessage().getString())).findFirst().ifPresent(b -> click(screen, b.getMessage().getString()));
            }
            return mc.getSingleplayerServer() != null && mc.level != null && mc.player != null && mc.player.level() == mc.level && mc.player.level().dimension().equals(TravelService.SPACE);
        }, 6000);
        c.runOnClient(mc -> mc.getSingleplayerServer().execute(() ->
        {
            try
            {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                folder.set(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
                var ship = WorldStore.get(server).at(player.level(), player.blockPosition());
                if (ship == null || !ship.getStringOr("blueprint", "").equals(preset.id()))
                    throw new AssertionError("Actual first login did not commission the selected ship");
                if (!WorldStartSettings.read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(WorldStartSettings.FILE)).equals(new WorldStartSettings(true, preset.id())))
                    throw new AssertionError("Start choice was not written into the new world");
                if (preparing)
                    DevStoryFixture.prepare(server, player, ship);
                selected.set(ship.getStringOr("id", ""));
            }
            catch (Throwable error)
            {
                failure.set(error);
            }
        }));
        c.waitFor(mc -> selected.get() != null || failure.get() != null, 200);
        if (failure.get() != null)
            throw new AssertionError("Space start validation failed", failure.get());
        c.waitFor(mc -> mc.gui.screen() == null && mc.level != null && mc.player != null && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1200);
        c.waitTicks(10);
        c.takeScreenshot("nullspace-world-start-aboard");
        var closing = new AtomicReference<net.minecraft.client.server.IntegratedServer>();
        c.runOnClient(mc ->
        {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectWithSavingScreen();
        });
        c.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null && closing.get().isShutdown(), 1200);
        c.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        c.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(folder.get(), () -> mc.gui.setScreen(new TitleScreen())));
        c.waitFor(mc -> mc.getSingleplayerServer() != null && mc.level != null && mc.player != null && mc.player.level() == mc.level && mc.player.level().dimension().equals(TravelService.SPACE), 6000);
        var reloaded = new java.util.concurrent.atomic.AtomicBoolean();
        c.waitFor(mc -> mc.gui.screen() == null && mc.level != null && mc.player != null && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1200);
        c.runOnClient(mc -> mc.getSingleplayerServer().execute(() ->
        {
            try
            {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                var ship = WorldStore.get(server).at(player.level(), player.blockPosition());
                if (ship == null || !ship.getStringOr("id", "").equals(selected.get()))
                    throw new AssertionError("Reload must retain the same ship");
                if (SpaceStart.firstArrival(player))
                    throw new AssertionError("Reload must not queue a new starting ship");
                if (preparing)
                    DevStoryFixture.validate(server, player, ship);
                reloaded.set(true);
            }
            catch (Throwable error)
            {
                failure.set(error);
            }
        }));
        c.waitFor(mc -> reloaded.get() || failure.get() != null, 200);
        if (failure.get() != null)
            throw new AssertionError("Reload validation failed", failure.get());
        c.runOnClient(mc ->
        {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectWithSavingScreen();
        });
        c.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null && closing.get().isShutdown(), 1200);
        c.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        ClientTestEvidence.passed(getClass());
    }

    private static void click(Screen screen, String label)
    {
        var button = screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label + "; visible: " + screen.children()));
        if (!button.active)
            throw new AssertionError("Disabled button: " + label);
        screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }
}
