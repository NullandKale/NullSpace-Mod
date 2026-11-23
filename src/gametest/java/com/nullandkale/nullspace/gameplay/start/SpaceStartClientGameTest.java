package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;
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
        var preset = ShipBlueprints.get(System.getProperty("nullspace.dev.startShip", preparing ? "halcyon_swift" : "halcyon_wisp"));
        var failure = new AtomicReference<Throwable>();
        var selected = new AtomicReference<String>();
        var folder = new AtomicReference<String>();
        // Offer what players are offered (the other client tests get the plain start), and only for this screen.
        c.runOnClient(mc ->
        {
            com.nullandkale.nullspace.ui.screen.WorldStartDefaults.playersDefault(true);
            CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen()));
        });
        ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof CreateWorldScreen, 1200);
        c.runOnClient(mc ->
        {
            var screen = (CreateWorldScreen) mc.gui.screen();
            com.nullandkale.nullspace.ui.screen.WorldStartDefaults.playersDefault(false);
            screen.getUiState().setName(preparing ? "World 5" : "NullSpace space-start test");
            if (preparing)
            {
                screen.getUiState().setSeed("20261104");
                screen.getUiState().setAllowCommands(true);
                screen.getUiState().setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.SURVIVAL);
            }
            var access = (WorldStartAccess) screen;
            if (!access.nullspace$start().equals(WorldStartSettings.NEW_WORLD))
                throw new AssertionError("Create New World offers the tiny starter crash-landed on Terra first: " + access.nullspace$start());
            var tabs = screen.children().stream().filter(MenuTabBar.class::isInstance).map(MenuTabBar.class::cast).findFirst().orElseThrow();
            tabs.selectTab(3, false);
            click(screen, "Start: Crash-landed");
            click(screen, "Start: On a planet");
            if (!access.nullspace$start().space() || access.nullspace$start().crash())
                throw new AssertionError("Start button did not select a start in orbit");
            // In orbit over a planet drawn from the home system, as this test (and the World 5 it prepares) always started. Shift-click steps the Planet
            // button back from Terra to Random; stepping forward through the planet types would rewrite the seed field for each.
            var planet = screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).filter(b -> b.getMessage().getString().startsWith("Planet: ")).findFirst().orElseThrow();
            planet.mouseClicked(new MouseButtonEvent(planet.getX() + planet.getWidth() / 2.0, planet.getY() + planet.getHeight() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT)), false);
            if (!access.nullspace$start().planet().equals("random"))
                throw new AssertionError("The Planet button cycles back to a random planet");
            click(screen, "Starting ship: " + ShipBlueprints.get(WorldStartSettings.DEFAULT.ship()).name());
        });
        ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof StartingShipScreen, 100);
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
        ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof CreateWorldScreen, 100);
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
        ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof CreateWorldScreen, 200);
        c.runOnClient(mc -> click(mc.gui.screen(), "Create New World"));
        ClientWaits.waitFor(c, mc ->
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
        ClientWaits.waitFor(c, mc -> selected.get() != null || failure.get() != null, 200);
        if (failure.get() != null)
            throw new AssertionError("Space start validation failed", failure.get());
        // The cover goes up once vanilla's own terrain screen closes, which can be after the server has finished the start.
        for (int i = 0; i < 200 && c.computeOnClient(mc -> com.nullandkale.nullspace.ui.screen.StartScreen.covers()) == 0; i++)
            c.waitTicks(2);
        if (c.computeOnClient(mc -> com.nullandkale.nullspace.ui.screen.StartScreen.covers()) == 0)
            throw new AssertionError("The start cover must hide the spawn while the ship is built: " + c.computeOnClient(mc -> com.nullandkale.nullspace.ui.screen.StartScreen.state()));
        ClientWaits.waitFor(c, mc -> mc.gui.screen() == null && mc.level != null && mc.player != null && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1200);
        c.waitTicks(10);
        c.takeScreenshot("nullspace-world-start-aboard");
        var closing = new AtomicReference<net.minecraft.client.server.IntegratedServer>();
        c.runOnClient(mc ->
        {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectWithSavingScreen();
        });
        ClientWaits.waitFor(c, mc -> mc.level == null && mc.getSingleplayerServer() == null && closing.get().isShutdown(), 1200);
        c.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        c.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(folder.get(), () -> mc.gui.setScreen(new TitleScreen())));
        ClientWaits.waitFor(c, mc -> mc.getSingleplayerServer() != null && mc.level != null && mc.player != null && mc.player.level() == mc.level && mc.player.level().dimension().equals(TravelService.SPACE), 6000);
        var reloaded = new java.util.concurrent.atomic.AtomicBoolean();
        ClientWaits.waitFor(c, mc -> mc.gui.screen() == null && mc.level != null && mc.player != null && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1200);
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
        ClientWaits.waitFor(c, mc -> reloaded.get() || failure.get() != null, 200);
        if (failure.get() != null)
            throw new AssertionError("Reload validation failed", failure.get());
        c.runOnClient(mc ->
        {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectWithSavingScreen();
        });
        ClientWaits.waitFor(c, mc -> mc.level == null && mc.getSingleplayerServer() == null && closing.get().isShutdown(), 1200);
        c.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        ClientTestEvidence.passed(getClass());
    }

    private static void click(Screen screen, String label)
    {
        var button = screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label + " on " + screen.getClass().getSimpleName() + " (" + screen.getTitle().getString() + "); visible: " + screen.children()));
        if (!button.active)
            throw new AssertionError("Disabled button: " + label);
        screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }
}
