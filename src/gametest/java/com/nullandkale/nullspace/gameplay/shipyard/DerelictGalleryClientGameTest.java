package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;
import com.nullandkale.nullspace.worldgen.condition.DerelictDamage;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Graphical review of derelict wrecks: one station at each danger from 0 to 3, and a salvage ship at danger 3. Each gets an
 * exterior shot, a plan view and a look inside its first damaged room. Screenshots go to the client test run folder.
 */
public final class DerelictGalleryClientGameTest implements FabricClientGameTest
{
    private static void camera(ClientGameTestContext context, TestSingleplayerContext world, BlockPos probe, Vec3 eye, Vec3 target)
    {
        var d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        world.getServer().runOnServer(server ->
        {
            var player = world.getConnection().getServerPlayer();
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            player.teleportTo(server.overworld(), eye.x, eye.y - 1.62, eye.z, Set.of(), yaw, pitch, true);
        });
        ClientWaits.waitFor(context, client -> client.player != null && client.player.getEyePosition().distanceToSqr(eye) < 4, 300);
        ClientWaits.waitFor(context, client -> client.level != null && client.level.hasChunkAt(probe), 600);
        long section = net.minecraft.core.SectionPos.asLong(probe.getX() >> 4, probe.getY() >> 4, probe.getZ() >> 4);
        ClientWaits.waitFor(context, client -> client.levelRenderer.visibleSections().stream().anyMatch(rendered -> rendered.getSectionNode() == section && rendered.getSectionMesh().hasRenderableLayers()), 900);
        context.waitTicks(40);
    }

    private static void shot(ClientGameTestContext context, String name)
    {
        context.runOnClient(client ->
        {
            if (!client.gui.hud.isHidden())
                client.gui.hud.toggle();
        });
        context.takeScreenshot(name);
        context.runOnClient(client ->
        {
            if (client.gui.hud.isHidden())
                client.gui.hud.toggle();
        });
    }

    /** The exterior from a corner, the plan from above, and the inside of the first damaged room from its far corner. */
    private static void review(ClientGameTestContext context, TestSingleplayerContext world, StationPlan layout, DerelictDamage.Report report, BlockPos origin, int dz, int width, int height, int depth, BlockPos probe, String name)
    {
        var c = Vec3.atLowerCornerOf(origin).add(width / 2.0, height / 2.0, depth / 2.0);
        double span = Math.max(width, depth);
        camera(context, world, probe, c.add(-span * .75, span * .45 + 8, -span * .75), c);
        shot(context, name + "-exterior");
        camera(context, world, probe, c.add(0, span * .85 + 10, .01), c);
        shot(context, name + "-plan");
        for (var id : report.damaged().stream().limit(2).toList())
        {
            var room = layout.room(id);
            var i = room.interior().shift(0, dz);
            var eye = Vec3.atBottomCenterOf(origin.offset(i.x0(), i.y0(), i.z0())).add(0.2, 1.6, 0.2);
            var target = Vec3.atBottomCenterOf(origin.offset(i.x1(), i.y0() + 2, i.z1()));
            camera(context, world, origin.offset(i.x0(), i.y0(), i.z0()), eye, target);
            shot(context, name + "-room-" + id + "-" + room.use().name().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = context.worldBuilder().create())
        {
            int[] oldDistance = new int[1];
            context.runOnClient(client ->
            {
                oldDistance[0] = client.options.renderDistance().get();
                client.options.renderDistance().set(16);
                client.options.broadcastOptions();
            });
            var stations = new ArrayList<StationPreview.Placed>();
            var reports = new ArrayList<DerelictDamage.Report>();
            long seed = 4242;
            var spec = new StationSpec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.STANDARD, StationSpec.Condition.DERELICT, seed);
            var ship = new Object[3];
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                try
                {
                    for (int danger = 0; danger <= 3; danger++)
                    {
                        var placed = StationPreview.build(server, List.of(spec), danger).getFirst();
                        stations.add(placed);
                        reports.add(DerelictDamage.apply(placed.planned(), 0, StationStructure.build(placed.planned()), seed, danger).report());
                    }
                }
                catch (java.io.IOException failure)
                {
                    throw new RuntimeException(failure);
                }
                // A salvage ship at danger 3, on its own patch of clear sky.
                var level = server.overworld();
                long shipSeed = 7;
                String design = EncounterSites.design(2, shipSeed);
                var preset = ShipBlueprints.get(design);
                var layout = ShipBlueprints.layout(preset);
                var plan = EncounterSites.plan(2, shipSeed, 3);
                int x = 3200, z = 5200;
                for (int cx = (x - 40) >> 4; cx <= (x + preset.width() + 40) >> 4; cx++)
                    for (int cz = (z - 40) >> 4; cz <= (z + preset.depth() + 40) >> 4; cz++)
                        level.getChunk(cx, cz);
                int surface = level.getMinY();
                for (int dx = -20; dx < preset.width() + 20; dx += 2)
                    for (int dzz = -20; dzz < preset.depth() + 20; dzz += 2)
                        surface = Math.max(surface, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dzz));
                var origin = new BlockPos(x, surface + 20, z);
                plan.blocks().forEach((at, state) -> level.setBlock(origin.offset(at), state, 18));
                ship[0] = origin;
                ship[1] = layout;
                ship[2] = new Object[]{preset, DerelictDamage.apply(layout, ShipPlanner.RAMP, EncounterSites.plan(2, shipSeed), shipSeed, 3).report(), plan.core()};
            });
            for (int danger = 0; danger <= 3; danger++)
            {
                var p = stations.get(danger);
                review(context, world, p.planned(), reports.get(danger), p.origin(), 0, p.planned().width(), p.planned().height(), p.planned().depth(), p.origin().offset(p.plan().core()), "nullspace-derelict-station-danger" + danger);
            }
            var origin = (BlockPos) ship[0];
            var layout = (StationPlan) ship[1];
            var parts = (Object[]) ship[2];
            var preset = (ShipBlueprints.Preset) parts[0];
            review(context, world, layout, (DerelictDamage.Report) parts[1], origin, ShipPlanner.RAMP, preset.width(), preset.height(), preset.depth(), origin.offset((BlockPos) parts[2]), "nullspace-derelict-ship-danger3");
            context.runOnClient(client -> client.options.renderDistance().set(oldDistance[0]));
        }
        ClientTestEvidence.passed(getClass());
    }
}
