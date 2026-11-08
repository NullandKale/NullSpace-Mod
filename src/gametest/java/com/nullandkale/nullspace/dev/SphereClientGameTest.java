package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;

import com.mojang.blaze3d.platform.InputConstants;

/** Native movement through the maximum-size ship's airlock and two stair flights to its bridge. */
public final class SphereClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = context.worldBuilder().create())
        {
            var annex = world.getServer().computeOnServer(server ->
            {
                try
                {
                    return SphereLab.ensure(server, ShipGallery.ensure(server));
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            var base = annex.base();
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                p.teleportTo(server.overworld(), base.getX() - 28, base.getY() + 36, base.getZ() - 35, java.util.Set.of(), -41.4f, 16.2f, true);
            });
            context.runOnClient(c ->
            {
                c.options.renderDistance().set(12);
                c.options.broadcastOptions();
                if (!c.gui.hud.isHidden())
                    c.gui.hud.toggle();
            });
            try
            {
                context.waitFor(c -> c.level.hasChunkAt(base) && c.level.hasChunkAt(base.offset(31, 32, 31)), 600);
                context.waitTicks(60);
                context.takeScreenshot("nullspace-meridian-exterior");
            }
            finally
            {
                context.runOnClient(c ->
                {
                    if (c.gui.hud.isHidden())
                        c.gui.hud.toggle();
                });
                context.restoreDefaultGameOptions();
            }
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "nullspace lab sphere");
                var level = server.overworld();
                var door = base.offset(15, 2, 1);
                ((DoorBlock) level.getBlockState(door).getBlock()).setOpen(null, level, level.getBlockState(door), door, true);
            });
            world.getConnection().waitForChunksRender();
            walk(context, base, 15.5, 2.5);
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                var outer = base.offset(15, 2, 1);
                var inner = base.offset(15, 6, 9);
                ((DoorBlock) level.getBlockState(outer).getBlock()).setOpen(null, level, level.getBlockState(outer), outer, false);
                ((DoorBlock) level.getBlockState(inner).getBlock()).setOpen(null, level, level.getBlockState(inner), inner, true);
            });
            walk(context, base, 15.5, 10.5);
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                var door = base.offset(15, 6, 9);
                ((DoorBlock) level.getBlockState(door).getBlock()).setOpen(null, level, level.getBlockState(door), door, false);
            });
            walk(context, base, 12.5, 10.5);
            walk(context, base, 12.5, 17.5);
            walk(context, base, 16.5, 17.5);
            walk(context, base, 16.5, 10.5);
            walk(context, base, 18.5, 10.5);
            walk(context, base, 18.5, 17.5);
            walk(context, base, 16.5, 17.5);
            walk(context, base, 16.5, 23.5);
            world.getServer().runOnServer(server ->
            {
                var player = world.getConnection().getServerPlayer();
                if (Math.abs(player.getY() - (base.getY() + 16)) > .1)
                    throw new AssertionError("Must walk up to the third-deck bridge without flight");
            });
            context.getInput().lookAt(180, 8);
            context.waitTicks(4);
            context.runOnClient(c -> c.gui.hud.toggle());
            try
            {
                context.takeScreenshot("nullspace-meridian-bridge");
            }
            finally
            {
                context.runOnClient(c -> c.gui.hud.toggle());
            }
            context.getInput().lookAt(annex.controller());
            context.waitTicks(2);
            context.waitFor(c -> c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(annex.controller()), 40);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 200);
            ShipControlTestUi.action(context, ShipControl.Action.INSPECT);
            context.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"), 200);
            context.takeScreenshot("nullspace-meridian-terminal");
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                var cargo = (net.minecraft.world.Container) level.getBlockEntity(base.offset(6, 6, 15));
                cargo.clearContent();
                level.setBlock(annex.controller(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
                try
                {
                    var revisited = SphereLab.ensure(server, ShipGallery.ensure(server));
                    if (!revisited.equals(annex) || !level.getBlockState(annex.controller()).isAir() || !cargo.isEmpty())
                        throw new AssertionError("Repeat visits must preserve the sphere, empty supplies, and departed" + " controller");
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        }
        ClientTestEvidence.passed(getClass());
    }

    private static void walk(ClientGameTestContext context, BlockPos base, double x, double z)
    {
        double targetX = base.getX() + x, targetZ = base.getZ() + z;
        float[] yaw = new float[1];
        context.runOnClient(c -> yaw[0] = (float) Math.toDegrees(Math.atan2(-(targetX - c.player.getX()), targetZ - c.player.getZ())));
        context.getInput().lookAt(yaw[0], 0);
        context.waitTicks(2);
        context.getInput().holdKey(InputConstants.KEY_W);
        try
        {
            context.waitFor(c -> Math.hypot(c.player.getX() - targetX, c.player.getZ() - targetZ) < .4, 240);
        }
        finally
        {
            context.getInput().releaseKey(InputConstants.KEY_W);
        }
        context.waitTicks(2);
    }
}
