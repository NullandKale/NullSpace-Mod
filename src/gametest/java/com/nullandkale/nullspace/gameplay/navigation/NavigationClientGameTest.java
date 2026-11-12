package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.NullSpaceClient;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipLab;
import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.PlanetSky;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.testing.TestZones;
import com.nullandkale.nullspace.ui.screen.ShipPilotScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.ui.screen.TravelScreen;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.KeyEvent;

import com.mojang.blaze3d.platform.InputConstants;

public final class NavigationClientGameTest implements FabricClientGameTest
{
    private static void choose(ClientGameTestContext c, int index)
    {
        c.runOnClient(mc ->
        {
            var t = (TerminalScreen) mc.gui.screen();
            while (t.selection() != index)
                t.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            t.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            final net.minecraft.core.BlockPos[] launchHelm = new net.minecraft.core.BlockPos[1];
            world.getConnection().waitForChunksRender();
            c.waitTicks(12);
            world.getServer().runOnServer(server ->
            {
                try
                {
                    var base = ShipLab.ensure(server);
                    server.overworld().setBlock(base.offset(3, 2, 0), com.nullandkale.nullspace.content.ModBlocks.DOCKING_BASE.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH), 18);
                    launchHelm[0] = base.offset(6, 3, 3);
                    var p = world.getConnection().getServerPlayer();
                    p.teleportTo(server.overworld(), base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, java.util.Set.of(), 0, 0, true);
                    ShipService.open(p, base.offset(6, 3, 3), "Navigation test");
                }
                catch (java.io.IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            world.getConnection().waitForChunksRender();
            c.waitTicks(12);
            world.getServer().waitFor(server ->
            {
                var p = world.getConnection().getServerPlayer();
                try
                {
                    ShipService.preflight(p.level(), launchHelm[0]);
                    return true;
                }
                catch (IllegalArgumentException loading)
                {
                    return false;
                }
            }, 200);
            ShipControlTestUi.action(c, ShipControl.Action.LAUNCH);
            try
            {
                c.waitFor(mc -> mc.level != null && mc.level.dimension().equals(TravelService.SPACE) && mc.gui.screen() == null, 600);
            }
            catch (AssertionError error)
            {
                c.runOnClient(mc ->
                {
                    throw new AssertionError("Launch screen=" + mc.gui.screen() + " status=" + (mc.gui.screen() instanceof TerminalScreen t ? t.status() : "none"), error);
                });
                throw error;
            }
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                ShipService.open(p, WorldStore.pos(SceneService.observer(p), "controller"), "Ready");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
            c.waitTicks(12);
            ShipControlTestUi.action(c, ShipControl.Action.PILOT);
            c.waitFor(mc -> mc.gui.screen() instanceof ShipPilotScreen s && s.viewport() != null && s.viewport().compute() != null, 200);
            c.runOnClient(mc ->
            {
                var s = (ShipPilotScreen) mc.gui.screen();
                var target = s.viewport().target;
                if (Math.abs((long) target.width * s.height - (long) target.height * s.width) > Math.max(s.width, s.height))
                    throw new AssertionError("Pilot render target must match the whole screen aspect ratio");
            });
            var original = world.getServer().computeOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                return new double[]{s.getDoubleOr("simX", 0), s.getDoubleOr("simZ", 0), p.getX(), p.getY(), p.getZ()};
            });
            c.runOnClient(mc -> ((ShipPilotScreen) mc.gui.screen()).keyPressed(new KeyEvent(InputConstants.KEY_W, 0, 0)));
            c.waitTicks(30);
            c.runOnClient(mc -> ((ShipPilotScreen) mc.gui.screen()).keyReleased(new KeyEvent(InputConstants.KEY_W, 0, 0)));
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                if (Math.hypot(s.getDoubleOr("simX", 0) - original[0], s.getDoubleOr("simZ", 0) - original[1]) < 1)
                    throw new AssertionError("Pilot input must move the simulated ship");
                if (p.position().distanceToSqr(new net.minecraft.world.phys.Vec3(original[2], original[3], original[4])) > .01)
                    throw new AssertionError("Piloting must retain the physical crew frame");
            });
            var pixels = new java.util.concurrent.atomic.AtomicInteger(-1);
            c.runOnClient(mc -> net.minecraft.client.Screenshot.takeScreenshot(((ShipPilotScreen) mc.gui.screen()).viewport().target, image ->
            {
                try
                {
                    int bright = 0, cx = image.getWidth() / 2, cy = image.getHeight() / 2;
                    for (int y = cy - 60; y < cy + 60; y++)
                        for (int x = cx - 80; x < cx + 80; x++)
                        {
                            int color = image.getPixel(x, y);
                            if ((color >> 16 & 255) + (color >> 8 & 255) + (color & 255) > 100)
                                bright++;
                        }
                    pixels.set(bright);
                }
                finally
                {
                    image.close();
                }
            }));
            c.waitFor(mc -> pixels.get() >= 0, 100);
            if (pixels.get() < 200)
                throw new AssertionError("Third-person hull must be framed and readable, got " + pixels.get() + " visible center pixels");
            c.takeScreenshot("nullspace-third-person-flight");
            c.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
            c.waitTicks(1);
            c.takeScreenshot("nullspace-pilot-controls");
            c.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(5);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                if (s.getDoubleOr("simVX", 0) != 0 || s.getDoubleOr("simVZ", 0) != 0)
                    throw new AssertionError("Releasing helm must stop motion");
                ShipService.open(p, WorldStore.pos(s, "controller"), "Ready");
            });
            c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 100);
            c.waitTicks(12);
            ShipControlTestUi.action(c, ShipControl.Action.CAMERA);
            c.waitFor(mc -> mc.gui.screen() instanceof ShipPilotScreen s && !s.canPilot(), 100);
            c.runOnClient(mc -> mc.gui.screen().onClose());
            c.waitTicks(3);
            ShipControlTestUi.ensureScanner(world);
            ShipControlTestUi.starMap(c, world);
            // P9: a local jump, a jump to another chart place in starting range, back to land on
            // Ares (real generated terrain), then home.
            var elsewhere = new String[1];
            world.getServer().runOnServer(server -> elsewhere[0] = TestZones.elsewhere(server));
            for (String destination : new String[]{"sol_luna", elsewhere[0], "sol_ares", "sol_terra"})
            {
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    Piloting.navigation(p, WorldStore.pos(SceneService.observer(p), "controller"));
                });
                c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
                c.waitTicks(12);
                c.takeScreenshot("nullspace-helm-navigation");
                var bodyName = new String[1];
                world.getServer().runOnServer(server -> bodyName[0] = LocalSpaces.get(server, destination).name());
                ShipControlTestUi.destination(c, world, bodyName[0], "Regions");
                c.takeScreenshot("nullspace-unified-destination");
                ShipControlTestUi.action(c, ShipControl.Action.JUMP);
                c.waitFor(mc -> mc.gui.screen() instanceof TravelScreen, 100);
                c.waitFor(mc -> mc.gui.screen() == null && NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().id().startsWith("nullspace:j_"), 300);
                c.takeScreenshot("nullspace-ftl-walkable-cabin");
                if (destination.equals("sol_luna"))
                {
                    world.getServer().runOnServer(server ->
                    {
                        var ship = SceneService.observer(world.getConnection().getServerPlayer());
                        ship.getCompoundOrEmpty("journey").putInt("contactAt", 0);
                    });
                    world.getServer().waitFor(server -> JourneyService.stopReason(server, SceneService.observer(world.getConnection().getServerPlayer())).isEmpty(), 600);
                    world.getServer().runOnServer(server ->
                    {
                        var player = world.getConnection().getServerPlayer();
                        ShipControl.open(player, WorldStore.pos(SceneService.observer(player), "controller"), "Signal found");
                    });
                    c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
                    c.waitTicks(5);
                    c.takeScreenshot("nullspace-ftl-investigate");
                    ShipControlTestUi.action(c, ShipControl.Action.INVESTIGATE);
                    c.waitFor(mc -> mc.gui.screen() == null && NullSpaceClient.definitions().scene().id().startsWith("nullspace:e_"), 300);
                    world.getServer().waitFor(server ->
                    {
                        var ship = SceneService.observer(world.getConnection().getServerPlayer());
                        if (ship.getCompoundOrEmpty("course").getStringOr("state", "").equals("PAUSED"))
                            throw new AssertionError("Encounter approach paused: " + CourseService.summary(ship));
                        return ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED") && !TransferLocks.inTransit(server, ship) && !CourseService.active(ship);
                    }, 1600);
                    world.getServer().runOnServer(server ->
                    {
                        var player = world.getConnection().getServerPlayer();
                        ShipControl.open(player, WorldStore.pos(SceneService.observer(player), "controller"), "Ready to resume");
                    });
                    c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
                    c.waitTicks(5);
                    c.takeScreenshot("nullspace-ftl-resume");
                    ShipControlTestUi.action(c, ShipControl.Action.RESUME);
                    // Departure backs away 112 blocks at .35 blocks/tick before re-entering FTL.
                    c.waitFor(mc -> mc.gui.screen() == null && NullSpaceClient.definitions().scene().id().startsWith("nullspace:j_"), 800);
                }
                world.getServer().runOnServer(server ->
                {
                    var ship = SceneService.observer(world.getConnection().getServerPlayer());
                    if (!JourneyService.cruising(ship) || TransferLocks.busy(world.getConnection().getServerPlayer()))
                        throw new AssertionError("FTL cruise must release the loading screen and block interaction lock");
                    // Shorten only this fixture; production journeys retain their saved duration.
                    var journey = ship.getCompoundOrEmpty("journey");
                    journey.putInt("elapsed", journey.getIntOr("duration", 0) - 20);
                });
                c.waitFor(mc -> mc.gui.screen() == null && NullSpaceClient.definitions().scene() != null && NullSpaceClient.definitions().scene().id().equals(destination.equals(LocalSpaces.HOME) ? "nullspace:local-space" : "nullspace:" + destination), 500);
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    if (!destination.equals(LocalSpaces.address(SceneService.observer(p))) || TransferLocks.busy(p))
                        throw new AssertionError("Destination metadata and readiness must commit together");
                    if (!ShipDestination.selected(SceneService.observer(p)).region().equals(destination))
                        throw new AssertionError("Jump lost the selected destination");
                    if (!destination.equals(LocalSpaces.HOME))
                    {
                        boolean rejected = false;
                        try
                        {
                            TravelService.request(p, WorldStore.pos(SceneService.observer(p), "controller"), "id:" + PadRegistry.read(server).stream().filter(pad -> pad.dimension().equals("minecraft:overworld")).findFirst().orElseThrow().id(), ShipService.preflight(p.level(), WorldStore.pos(SceneService.observer(p), "controller")));
                        }
                        catch (IllegalArgumentException expected)
                        {
                            rejected = expected.getMessage().contains("Terra");
                        }
                        if (!rejected)
                            throw new AssertionError("Remote planet cannot land on Terra pads");
                    }
                });
                c.waitTicks(10);
                c.takeScreenshot("nullspace-arrival-" + destination);
                if (destination.equals("sol_ares"))
                {
                    // Travel through a generated system using the same controller and real surface transfer.
                    world.getServer().runOnServer(server ->
                    {
                        var player = world.getConnection().getServerPlayer();
                        var ship = SceneService.observer(player);
                        ShipService.open(player, WorldStore.pos(ship, "controller"), "Land on the generated planet");
                    });
                    c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
                    ShipControlTestUi.destination(c, world, bodyName[0] + " Landing", "Pads");
                    ShipControlTestUi.action(c, ShipControl.Action.LAND);
                    c.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().toString().equals("nullspace:sol_ares") && mc.gui.screen() == null, 600);
                    world.getConnection().waitForChunksRender();
                    c.waitTicks(12);
                    c.takeScreenshot("nullspace-generated-planet-surface");
                    world.getServer().runOnServer(server ->
                    {
                        var player = world.getConnection().getServerPlayer();
                        var ship = SceneService.observer(player);
                        if (ship == null || !PlanetSurfaces.address(player.level()).equals(destination))
                            throw new AssertionError("Generated planet landing lost the registered ship or surface address");
                        var sky = new net.minecraft.nbt.CompoundTag();
                        if (!PlanetSky.decorate(sky, player.level(), player.blockPosition()) || !sky.getStringOr("surfaceBody", "").equals("nullspace:" + destination))
                            throw new AssertionError("Generated surface must reference its actual planet");
                        ShipService.open(player, WorldStore.pos(ship, "controller"), "Launch from the generated planet");
                    });
                    c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 100);
                    ShipControlTestUi.action(c, ShipControl.Action.LAUNCH);
                    c.waitFor(mc -> mc.level != null && mc.level.dimension().equals(TravelService.SPACE) && mc.gui.screen() == null, 600);
                    world.getServer().runOnServer(server ->
                    {
                        var player = world.getConnection().getServerPlayer();
                        if (!LocalSpaces.address(SceneService.observer(player)).equals(destination))
                            throw new AssertionError("Surface launch must return to the generated system's planet orbit");
                    });
                }
            }
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                var origin = WorldStore.pos(s, "base");
                SphereLab.buildShip(p.level(), origin.below());
                var helm = SphereLab.controller(origin.below());
                s.putLong("controller", helm.asLong());
                p.teleportTo(p.level(), helm.getX() + .5, helm.getY(), helm.getZ() - 2, java.util.Set.of(), 0, 0, true);
                NavigationService.open(p, helm, false);
            });
            c.waitFor(mc -> mc.gui.screen() instanceof ShipPilotScreen view && view.viewport() != null && view.viewport().cachedSubjects() > 0, 200);
            c.waitTicks(12);
            c.takeScreenshot("nullspace-third-person-sphere");
            c.runOnClient(mc ->
            {
                var view = (ShipPilotScreen) mc.gui.screen();
                view.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 70, 20);
                view.mouseScrolled(0, 0, 0, 2);
            });
            c.waitTicks(5);
            c.takeScreenshot("nullspace-third-person-orbit");
            c.runOnClient(mc -> mc.gui.screen().onClose());
        }
        ClientTestEvidence.passed(getClass());
    }
}
