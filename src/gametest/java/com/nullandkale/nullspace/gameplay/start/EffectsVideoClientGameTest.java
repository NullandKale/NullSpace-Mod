package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.systems.EngineEffects;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Opt-in (run with {@code -Dnullspace.video.effects=true}; it does nothing otherwise): a video of the effects of the crash
 * start. First the wreck's smoke, sparks and embers up close at two broken machines, then the explosions that crater the
 * ground around the pad, then a ship's engines throwing exhaust in space. One screenshot every two ticks; join them with
 * {@code scripts/test/make-video.py} (the crash frames are named {@code nullspace-fx-crash}, the exhaust {@code nullspace-fx-engine}).
 */
public final class EffectsVideoClientGameTest implements FabricClientGameTest
{
    private record Shot(Vec3 eye, Vec3 target)
    {}

    private static void aim(ClientGameTestContext c, net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world, ServerLevel[] level, Shot shot)
    {
        world.getServer().runOnServer(server ->
        {
            var player = world.getConnection().getServerPlayer();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            var look = shot.target().subtract(shot.eye());
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
            player.teleportTo(level[0], shot.eye().x, shot.eye().y - 1.62, shot.eye().z, Set.of(), yaw, pitch, true);
        });
        c.waitTicks(10);
    }

    private static void hideHud(ClientGameTestContext c)
    {
        c.runOnClient(mc ->
        {
            if (!mc.gui.hud.isHidden())
                mc.gui.hud.toggle();
        });
    }

    private static void record(ClientGameTestContext c, String name, int ticks, Runnable each)
    {
        for (int t = 0; t < ticks; t += 2)
        {
            if (each != null)
                each.run();
            c.waitTicks(2);
            c.takeScreenshot(name);
        }
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null || !Boolean.getBoolean("nullspace.video.effects"))
        {
            ClientTestEvidence.passed(getClass());
            return;
        }
        if (!Boolean.getBoolean("nullspace.video.skipcrash"))
            crash(c);
        if (!Boolean.getBoolean("nullspace.video.skiphelm"))
            helm(c);
        if (!Boolean.getBoolean("nullspace.video.skiptiers"))
            engines(c);
        ClientTestEvidence.passed(getClass());
    }

    /** The wreck's smoke and sparks at two broken machines, then explosions around the pad from a distance. */
    private void crash(ClientGameTestContext c)
    {
        try (var world = c.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> settings.setSeed("20261104")).create())
        {
            world.getConnection().waitForChunksRender();
            var level = new ServerLevel[1];
            var found = new AtomicReference<java.util.List<Shot>>();
            var site = new AtomicReference<CrashSites.Site>();
            world.getServer().runOnServer(server ->
            {
                var commands = server.getCommands();
                commands.performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                commands.performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                var player = world.getConnection().getServerPlayer();
                var ship = SpaceStart.start(player, new WorldStartSettings(true, "rivet_bolt", true, false, "terra"));
                level[0] = server.overworld();
                site.set(CrashSites.of(WorldStore.get(server), LocalSpaces.HOME, ship.getStringOr("id", "")));
                var bounds = WorldStore.bounds(ship);
                var wrecks = new ArrayList<BlockPos>();
                for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
                    if (BrokenMachines.broken(level[0].getBlockState(at)) && !level[0].getBlockState(at).is(BrokenMachines.PAD_COMPUTER))
                        wrecks.add(at.immutable());
                var shots = new ArrayList<Shot>();
                for (int i = 0; i < Math.min(2, wrecks.size()); i++)
                {
                    var at = wrecks.get(i * wrecks.size() / Math.min(2, wrecks.size()));
                    for (var side : Direction.Plane.HORIZONTAL)
                    {
                        var near = at.relative(side);
                        if (level[0].getBlockState(near).isAir() && level[0].getBlockState(near.above()).isAir())
                        {
                            shots.add(new Shot(Vec3.atBottomCenterOf(near).add(0, 1.9, 0).add(side.getStepX() * .3, 0, side.getStepZ() * .3), Vec3.atCenterOf(at).add(0, .2, 0)));
                            break;
                        }
                    }
                }
                var center = new Vec3((bounds.min().getX() + bounds.max().getX() + 1) / 2.0, (bounds.min().getY() + bounds.max().getY()) / 2.0, (bounds.min().getZ() + bounds.max().getZ() + 1) / 2.0);
                // From behind and above, near enough for the blasts around the pad to fill the picture.
                shots.add(new Shot(center.add(0, 26, -30), center));
                found.set(shots);
            });
            hideHud(c);
            var shots = found.get();
            for (int i = 0; i < shots.size(); i++)
            {
                aim(c, world, level, shots.get(i));
                boolean wide = i == shots.size() - 1;
                if (wide)
                {
                    // The blasts that crater the ground, replayed for the camera one after another: the same blasts the crash start makes.
                    for (int blast = 0; blast < CrashSites.BLASTS; blast++)
                    {
                        int index = blast;
                        world.getServer().runOnServer(server -> CrashSites.blast(level[0], site.get(), index, 77));
                        record(c, "nullspace-fx-crash", 24, null);
                    }
                    record(c, "nullspace-fx-crash", 30, null);
                }
                else
                    record(c, "nullspace-fx-crash", 120, null);
            }
        }
    }

    /** Installs upgrades in every engine so its tier is {@code tier} (0 to 3). */
    private static void upgrade(ServerLevel level, com.nullandkale.nullspace.core.save.WorldStore store, java.util.Set<BlockPos> engines, int tier)
    {
        for (var at : engines)
            if (level.getBlockEntity(at) instanceof com.nullandkale.nullspace.content.machine.MachineBlockEntity machine)
            {
                var items = new net.minecraft.world.item.ItemStack[]{tier >= 3 ? new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.EXOTIC_UPGRADE) : tier == 2 ? new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.ADVANCED_UPGRADE) : tier == 1 ? new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.SYSTEM_UPGRADE) : net.minecraft.world.item.ItemStack.EMPTY, tier >= 3 ? new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.ADVANCED_UPGRADE) : net.minecraft.world.item.ItemStack.EMPTY, tier >= 2 ? new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.SYSTEM_UPGRADE) : net.minecraft.world.item.ItemStack.EMPTY};
                for (int slot = 0; slot < 3; slot++)
                    machine.setItem(slot, items[slot]);
            }
    }

    /**
     * Each engine upgrade tier burns its own colour: amber, blue, violet and green, filmed from behind the engines while the ship
     * holds thrust. The flight model is stepped from here, twice for every two ticks filmed, so the plume and speed are the real ones.
     */
    private void engines(ClientGameTestContext c)
    {
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var level = new ServerLevel[1];
            var shipId = new AtomicReference<String>();
            var shot = new AtomicReference<Shot>();
            var engineBlocks = new AtomicReference<Set<BlockPos>>();
            world.getServer().runOnServer(server ->
            {
                var player = world.getConnection().getServerPlayer();
                var ship = SpaceStart.start(player, new WorldStartSettings(true, "rivet_bolt"));
                level[0] = server.getLevel(TravelService.SPACE);
                shipId.set(ship.getStringOr("id", ""));
                ship.putInt("thrustDemand", 6);
                var exhausts = EngineEffects.exhausts(level[0], ship);
                if (exhausts.isEmpty())
                    throw new AssertionError("The ship has no engine exhaust to film");
                var exhaust = exhausts.getFirst();
                var back = exhaust.origin().add(exhaust.direction().getStepX() * 14, 3, exhaust.direction().getStepZ() * 14);
                shot.set(new Shot(back, exhaust.origin().add(exhaust.direction().getStepX() * 4, -.5, exhaust.direction().getStepZ() * 4)));
                ship.putInt("thrustDemand", 0);
                var set = new java.util.HashSet<BlockPos>();
                for (var at : com.nullandkale.nullspace.gameplay.systems.ShipSystems.connected(level[0], ship))
                    if (level[0].getBlockState(at).is(com.nullandkale.nullspace.content.ModBlocks.ENGINE))
                        set.add(at);
                engineBlocks.set(set);
            });
            hideHud(c);
            aim(c, world, level, shot.get());
            int[] tick = {0};
            record(c, "nullspace-fx-engine", 280, () -> world.getServer().runOnServer(server ->
            {
                var store = WorldStore.get(server);
                var ship = store.repository().find(shipId.get());
                if (ship == null)
                    return;
                for (int step = 0; step < 2; step++)
                {
                    int t = tick[0]++;
                    if (t % 70 == 0)
                        upgrade(level[0], store, engineBlocks.get(), t / 70);
                    // Thrust forward the whole time; the tier changes every 3.5 seconds.
                    int power = com.nullandkale.nullspace.gameplay.navigation.NavigationService.propulsion(level[0], ship);
                    var next = com.nullandkale.nullspace.gameplay.navigation.NavigationService.fly(level[0], ship, com.nullandkale.nullspace.model.flight.FlightDynamics.FORWARD, power, com.nullandkale.nullspace.model.flight.ShipHeading.forward(level[0], ship).toYRot(), true, server.getTickCount());
                    com.nullandkale.nullspace.gameplay.navigation.Piloting.apply(ship, next);
                }
            }));
        }
    }

    /**
     * The helm view: a real pilot at the real controls in the real pilot screen, so the video shows what the flight looks like from
     * the helm. W accelerates; letting go lets assist brake the ship; D turns and assist damps the spin; G turns assist off, and the
     * ship coasts and keeps spinning; G again and assist brings it all to rest.
     */
    private void helm(ClientGameTestContext c)
    {
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var controller = new AtomicReference<BlockPos>();
            world.getServer().runOnServer(server ->
            {
                var player = world.getConnection().getServerPlayer();
                var ship = SpaceStart.start(player, new WorldStartSettings(true, "rivet_bolt"));
                controller.set(WorldStore.pos(ship, "controller"));
            });
            world.getServer().runOnServer(server -> com.nullandkale.nullspace.gameplay.ship.ShipService.open(world.getConnection().getServerPlayer(), controller.get(), "Ready"));
            c.waitFor(mc -> mc.gui.screen() instanceof com.nullandkale.nullspace.ui.screen.TerminalScreen, 200);
            c.waitTicks(12);
            com.nullandkale.nullspace.testing.ShipControlTestUi.action(c, com.nullandkale.nullspace.gameplay.navigation.ShipControl.Action.PILOT);
            c.waitFor(mc -> mc.gui.screen() instanceof com.nullandkale.nullspace.ui.screen.ShipPilotScreen s && s.viewport() != null && s.viewport().compute() != null, 200);
            c.waitTicks(20);
            // {from tick, to tick, key}: the keys held over each stretch.
            int w = com.mojang.blaze3d.platform.InputConstants.KEY_W, d = com.mojang.blaze3d.platform.InputConstants.KEY_D, a = com.mojang.blaze3d.platform.InputConstants.KEY_A;
            int[][] held = {{0, 80, w}, {160, 200, d}, {300, 330, w}, {350, 366, a}};
            int[] assistToggles = {290, 460};
            int end = 600;
            var down = new java.util.HashSet<Integer>();
            for (int tick = 0; tick < end; tick += 2)
            {
                var wanted = new java.util.HashSet<Integer>();
                for (var h : held)
                    if (tick >= h[0] && tick < h[1])
                        wanted.add(h[2]);
                for (int key : wanted)
                    if (down.add(key))
                        c.runOnClient(mc -> ((com.nullandkale.nullspace.ui.screen.ShipPilotScreen) mc.gui.screen()).keyPressed(new net.minecraft.client.input.KeyEvent(key, 0, 0)));
                for (int key : java.util.List.copyOf(down))
                    if (!wanted.contains(key))
                    {
                        down.remove(key);
                        c.runOnClient(mc -> ((com.nullandkale.nullspace.ui.screen.ShipPilotScreen) mc.gui.screen()).keyReleased(new net.minecraft.client.input.KeyEvent(key, 0, 0)));
                    }
                for (int toggle : assistToggles)
                    if (tick == toggle)
                        c.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_G, 0, 0)));
                c.waitTicks(2);
                c.takeScreenshot("nullspace-fx-helm");
            }
        }
    }
}
