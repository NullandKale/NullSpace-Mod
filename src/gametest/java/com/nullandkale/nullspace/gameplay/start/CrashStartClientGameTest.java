package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.ui.screen.WorldStartAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Actual Create New World with the crash-landing start chosen: the player ends on Terra beside a wrecked ship, with its machines
 * broken. The photographs are the review: the cabin the player wakes in, the wreck from two sides, and each broken machine up close.
 */
public final class CrashStartClientGameTest implements FabricClientGameTest
{
    private record Shot(String name, Vec3 eye, Vec3 target)
    {}

    private static void click(Screen screen, String label)
    {
        var button = screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label + "; visible: " + screen.children()));
        if (!button.active)
            throw new AssertionError("Disabled button: " + label);
        screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        var failure = new AtomicReference<Throwable>();
        c.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        c.waitFor(mc -> mc.gui.screen() instanceof CreateWorldScreen, 1200);
        c.runOnClient(mc ->
        {
            var screen = (CreateWorldScreen) mc.gui.screen();
            screen.getUiState().setName("NullSpace crash-start test");
            screen.getUiState().setSeed("20261104");
            var tabs = screen.children().stream().filter(MenuTabBar.class::isInstance).map(MenuTabBar.class::cast).findFirst().orElseThrow();
            tabs.selectTab(3, false);
            click(screen, "Start: On a planet");
            click(screen, "Start: Aboard a ship");
            click(screen, "Planet: Random planet");
            if (!((WorldStartAccess) screen).nullspace$start().equals(new WorldStartSettings(true, WorldStartSettings.DEFAULT.ship(), true, false, "terra")))
                throw new AssertionError("The third start option did not select the crash landing: " + ((WorldStartAccess) screen).nullspace$start());
        });
        c.takeScreenshot("nullspace-crash-start-options");
        c.runOnClient(mc -> click(mc.gui.screen(), "Create New World"));
        c.waitFor(mc ->
        {
            if (mc.gui.screen() instanceof ConfirmScreen screen)
                screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b -> java.util.Set.of("Proceed", "Yes").contains(b.getMessage().getString())).findFirst().ifPresent(b -> click(screen, b.getMessage().getString()));
            return mc.getSingleplayerServer() != null && mc.level != null && mc.player != null && mc.player.level() == mc.level;
        }, 6000);
        // The start finishes a few ticks after login: the ship is built in orbit, lands on Terra's pad, and is wrecked.
        var done = new AtomicBoolean();
        var shots = new AtomicReference<List<Shot>>();
        for (int attempt = 0; attempt < 120 && !done.get() && failure.get() == null; attempt++)
        {
            c.runOnClient(mc -> mc.getSingleplayerServer().execute(() ->
            {
                try
                {
                    var server = mc.getSingleplayerServer();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    var record = WorldStore.get(server).section("spaceStarts").getCompoundOrEmpty(player.getUUID().toString());
                    if (!record.getBooleanOr("completed", false))
                        return;
                    if (!WorldStartSettings.read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(WorldStartSettings.FILE)).equals(new WorldStartSettings(true, WorldStartSettings.DEFAULT.ship(), true, false, "terra")))
                        throw new AssertionError("The crash choice was not written into the new world");
                    var ship = WorldStore.get(server).repository().find(record.getStringOr("ship", ""));
                    if (ship == null || !player.level().dimension().equals(Level.OVERWORLD) || !ship.getStringOr("dimension", "").equals(Level.OVERWORLD.identifier().toString()))
                        throw new AssertionError("The crash start did not leave the ship and the player on Terra");
                    var level = server.overworld();
                    var bounds = WorldStore.bounds(ship);
                    var broken = new ArrayList<BlockPos>();
                    for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
                        if (BrokenMachines.broken(level.getBlockState(at)))
                            broken.add(at.immutable());
                    if (broken.isEmpty())
                        throw new AssertionError("No machine is broken");
                    var center = new Vec3((bounds.min().getX() + bounds.max().getX() + 1) / 2.0, (bounds.min().getY() + bounds.max().getY()) / 2.0, (bounds.min().getZ() + bounds.max().getZ() + 1) / 2.0);
                    var list = new ArrayList<Shot>();
                    double reach = Math.max(bounds.max().getX() - bounds.min().getX(), bounds.max().getZ() - bounds.min().getZ()) * .5 + 12;
                    list.add(new Shot("nullspace-crash-wreck-front", center.add(0, 7, -reach), center));
                    list.add(new Shot("nullspace-crash-wreck-side", center.add(reach, 7, 0), center));
                    list.add(new Shot("nullspace-crash-wreck-back", center.add(0, 8, reach), center));
                    for (int i = 0; i < Math.min(4, broken.size()); i++)
                    {
                        var at = broken.get(i * broken.size() / Math.min(4, broken.size()));
                        // Stand in an open cell beside the wreck, looking at it.
                        Vec3 stand = null;
                        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL)
                        {
                            var near = at.relative(side);
                            if (level.getBlockState(near).isAir() && level.getBlockState(near.above()).isAir())
                            {
                                stand = Vec3.atBottomCenterOf(near).add(0, 1.62, 0).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(.3));
                                break;
                            }
                        }
                        if (stand != null)
                            list.add(new Shot("nullspace-crash-broken-" + i, stand, Vec3.atCenterOf(at)));
                    }
                    var frames = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new net.minecraft.world.phys.AABB(bounds.min().getX(), bounds.min().getY(), bounds.min().getZ(), bounds.max().getX() + 1, bounds.max().getY() + 1, bounds.max().getZ() + 1));
                    if (!frames.isEmpty())
                    {
                        var frame = frames.getFirst();
                        var look = frame.position();
                        list.add(new Shot("nullspace-crash-frames", look.add(frame.getDirection().getStepX() * 2.2, 0, frame.getDirection().getStepZ() * 2.2), look));
                    }
                    shots.set(list);
                    done.set(true);
                }
                catch (Throwable error)
                {
                    failure.set(error);
                }
            }));
            c.waitTicks(10);
        }
        if (failure.get() != null)
            throw new AssertionError("Crash start validation failed", failure.get());
        if (!done.get())
            throw new AssertionError("The crash start never finished");
        c.waitFor(mc -> mc.gui.screen() == null && mc.level != null && mc.player != null && mc.level.dimension().equals(Level.OVERWORLD) && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1200);
        c.waitTicks(60);
        c.takeScreenshot("nullspace-crash-cabin");
        c.runOnClient(mc ->
        {
            if (!mc.gui.hud.isHidden())
                mc.gui.hud.toggle();
        });
        for (var shot : shots.get())
        {
            c.runOnClient(mc -> mc.getSingleplayerServer().execute(() ->
            {
                ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                var look = shot.target().subtract(shot.eye());
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
                player.teleportTo(mc.getSingleplayerServer().overworld(), shot.eye().x, shot.eye().y - 1.62, shot.eye().z, java.util.Set.of(), yaw, pitch, true);
            }));
            c.waitTicks(80);
            c.takeScreenshot(shot.name());
        }
        var closing = new AtomicReference<net.minecraft.client.server.IntegratedServer>();
        c.runOnClient(mc ->
        {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectWithSavingScreen();
        });
        c.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null && closing.get().isShutdown(), 1200);
        c.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        ClientTestEvidence.passed(getClass());
    }
}
