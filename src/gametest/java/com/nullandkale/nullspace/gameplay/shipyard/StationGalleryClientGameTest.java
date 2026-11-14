package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.*;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Graphical review of generated stations: the three manufacturer methods on one brief, four seeds of
 * one brief, and a large multi-level station. Screenshots go to the client test run folder.
 */
public final class StationGalleryClientGameTest implements FabricClientGameTest
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
        context.waitFor(client -> client.player != null && client.player.getEyePosition().distanceToSqr(eye) < 4, 300);
        // Wait for the station's own meshes, not every background section at distance 16.
        context.waitFor(client -> client.level != null && client.level.hasChunkAt(probe), 600);
        long section = net.minecraft.core.SectionPos.asLong(probe.getX() >> 4, probe.getY() >> 4, probe.getZ() >> 4);
        context.waitFor(client -> client.levelRenderer.visibleSections().stream().anyMatch(rendered -> rendered.getSectionNode() == section && rendered.getSectionMesh().hasRenderableLayers()), 900);
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

    private static Vec3 centre(StationPreview.Placed p)
    {
        return Vec3.atLowerCornerOf(p.origin()).add(p.planned().width() / 2.0, p.planned().height() / 2.0, p.planned().depth() / 2.0);
    }

    /** Exterior from a corner, plan view from above, and the arrival room from inside. */
    private static void review(ClientGameTestContext context, TestSingleplayerContext world, StationPreview.Placed p, String name)
    {
        var c = centre(p);
        double span = Math.max(p.planned().width(), p.planned().depth());
        var probe = p.origin().offset(p.plan().core());
        camera(context, world, probe, c.add(-span * .75, span * .45 + 8, -span * .75), c);
        shot(context, name + "-exterior");
        camera(context, world, probe, c.add(0, span * .85 + 10, .01), c);
        shot(context, name + "-plan");
        var arrival = p.plan().points().stream().filter(point -> point.name().equals("room") && point.argument().startsWith(p.arrival() + ":")).findFirst().orElseThrow().pos();
        var door = p.planned().doors().stream().filter(d -> d.a().equals(p.arrival()) || d.b().equals(p.arrival())).findFirst().orElseThrow();
        var eye = Vec3.atBottomCenterOf(p.origin().offset(arrival)).add(0, 1.6, 0);
        camera(context, world, p.origin().offset(door.at()), eye, Vec3.atCenterOf(p.origin().offset(door.at())).add(0, 1, 0));
        shot(context, name + "-arrival");
        if (!p.planned().berths().isEmpty())
        {
            var berth = p.planned().berths().getFirst();
            var port = p.origin().offset(berth.port());
            var out = Vec3.atCenterOf(port).relative(berth.facing(), 20).add(0, 6, 0);
            camera(context, world, port, out, Vec3.atCenterOf(port));
            shot(context, name + "-berth");
        }
        var hall = p.planned().room(p.arrival());
        if (hall.levels() >= 2 && hall.climb() != null)
        {
            var i = hall.interior();
            var dir = hall.climb();
            var band = dir.getClockWise();
            boolean alongX = dir.getAxis() == net.minecraft.core.Direction.Axis.X;
            int x = alongX ? (dir == net.minecraft.core.Direction.EAST ? i.x0() : i.x1()) : (band == net.minecraft.core.Direction.EAST ? i.x1() - 1 : i.x0() + 1);
            int z = alongX ? (band == net.minecraft.core.Direction.SOUTH ? i.z1() - 1 : i.z0() + 1) : (dir == net.minecraft.core.Direction.SOUTH ? i.z0() : i.z1());
            var start = new BlockPos(x, hall.standY(), z);
            var top = start.relative(dir, 5).above(8);
            camera(context, world, p.origin().offset(start).below(), Vec3.atBottomCenterOf(p.origin().offset(start)).add(0, 1.9, 0).relative(dir.getOpposite(), 0.2), Vec3.atCenterOf(p.origin().offset(top)));
            shot(context, name + "-stairs");
            // The gallery ring: from its corner across the open hall.
            var ring = new BlockPos(i.x0() + 1, hall.standY() + StationPlan.PITCH, i.z0() + 1);
            var across = new BlockPos(i.x1() - 1, hall.standY() + 2, i.z1() - 1);
            camera(context, world, p.origin().offset(ring).below(), Vec3.atBottomCenterOf(p.origin().offset(ring)).add(0, 1.6, 0), Vec3.atCenterOf(p.origin().offset(across)));
            shot(context, name + "-gallery");
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
            var placed = new ArrayList<StationPreview.Placed>();
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                try
                {
                    var makers = new ArrayList<StationSpec>();
                    for (var maker : Maker.values())
                        makers.add(new StationSpec(maker, StationSpec.Purpose.HARBOR, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 4242));
                    placed.addAll(StationPreview.build(server, makers));
                    var seeds = new ArrayList<StationSpec>();
                    for (int k = 0; k < 4; k++)
                        seeds.add(new StationSpec(Maker.RIVET, StationSpec.Purpose.AGRICULTURE, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 7 + k * 1_000_003L));
                    placed.addAll(StationPreview.build(server, seeds));
                    placed.addAll(StationPreview.build(server, List.of(new StationSpec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.LARGE, StationSpec.Condition.OPERATIONAL, 11), new StationSpec(Maker.BASTION, StationSpec.Purpose.CARGO, StationSpec.Size.LARGE, StationSpec.Condition.OPERATIONAL, 11))));
                }
                catch (java.io.IOException failure)
                {
                    throw new RuntimeException(failure);
                }
            });
            for (int i = 0; i < placed.size(); i++)
            {
                var p = placed.get(i);
                String group = i < 3 ? "makers" : i < 7 ? "seeds" : "large";
                review(context, world, p, "nullspace-station-" + group + "-" + (i + 1) + "-" + p.spec().maker().name().toLowerCase(Locale.ROOT) + "-" + p.spec().purpose().name().toLowerCase(Locale.ROOT) + "-" + p.spec().size().name().toLowerCase(Locale.ROOT));
            }
            context.runOnClient(client -> client.options.renderDistance().set(oldDistance[0]));
        }
        ClientTestEvidence.passed(getClass());
    }
}
