package com.nullandkale.nullspace.content.synth;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.render.sound.ClientSounds;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** The Synth block's screen changes its patch, Play makes real sound on the client, and a cue sent to the player plays too. */
public final class SynthClientGameTest implements FabricClientGameTest
{
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            BlockPos[] synth = {BlockPos.ZERO};
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var level = server.overworld();
                synth[0] = p.blockPosition().relative(Direction.NORTH, 2).above();
                level.setBlock(synth[0], ModBlocks.SYNTH.defaultBlockState().setValue(ModBlocks.Machine.FACING, Direction.SOUTH), 3);
            });
            ClientWaits.chunksRendered(world.getConnection());
            c.waitTicks(8);
            c.getInput().lookAt(synth[0]);
            ClientWaits.waitFor(c, mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(synth[0]), 100);
            c.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("SYNTH"), 100);
            c.takeScreenshot("nullspace-synth-screen");
            // The first [+] on the screen raises the tone's pitch by a semitone.
            c.runOnClient(mc -> ShipControlTestUi.clickLabel((TerminalScreen) mc.gui.screen(), "[+]"));
            ClientWaits.waitFor(world.getServer(), server -> server.overworld().getBlockEntity(synth[0]) instanceof SynthBlockEntity entity && Math.abs(entity.patch().toneFrom() - 440 * Math.pow(2, 1 / 12.0)) < .5, 200);
            ClientWaits.waitFor(c, mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(6).contains("466.2 Hz"), 100);
            // A terminal takes one action every two server ticks (ComputerTerminal), and the pitch change just now was one.
            c.waitTicks(5);
            c.runOnClient(mc -> ShipControlTestUi.clickLabel((TerminalScreen) mc.gui.screen(), "[ Play ]"));
            // The voice's clock moves only as the sound engine reads its samples: sound was made.
            ClientWaits.waitFor(c, mc -> ClientSounds.heard("patch") > .2, 400);
            c.takeScreenshot("nullspace-synth-played");
            c.runOnClient(mc -> mc.gui.setScreen(null));
            world.getServer().runOnServer(server -> ShipSounds.player(world.getConnection().getServerPlayer(), "ftl.jolt"));
            ClientWaits.waitFor(c, mc -> ClientSounds.heard("ftl.jolt") > .8, 400);
        }
        ClientTestEvidence.passed(getClass());
    }
}
