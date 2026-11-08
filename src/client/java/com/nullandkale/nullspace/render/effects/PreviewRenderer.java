package com.nullandkale.nullspace.render.effects;

import com.nullandkale.nullspace.gameplay.pads.BuildPreviews;
import com.nullandkale.nullspace.ui.screen.SceneScreen;

import java.util.*;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.*;
import net.minecraft.gizmos.*;
import net.minecraft.world.phys.Vec3;

/** Per-viewer, fading world highlights. Native gizmos batch the outlines; no custom GPU targets. */
public final class PreviewRenderer
{
    private record View(UUID id, BlockPos controller, String dimension, boolean ship, long expires, List<BlockPos> surface, int count)
    {}

    private static final Map<Boolean, View> VIEWS = new HashMap<>();
    private static final long LIFETIME_NANOS = 30_000_000_000L;

    public static void accept(BuildPreviews.Preview packet)
    {
        var old = VIEWS.get(packet.ship());
        if (packet.remaining() == 0)
        {
            if (old != null && old.id.equals(packet.id()))
                VIEWS.remove(packet.ship());
            return;
        }
        var points = new HashSet<BlockPos>();
        for (long pos : packet.positions())
            points.add(BlockPos.of(pos));
        var surface = packet.ship() ? points.stream().filter(p ->
        {
            for (var d : Direction.values())
                if (!points.contains(p.relative(d)))
                    return true;
            return false;
        }).toList() : List.copyOf(points);
        long expires = System.nanoTime() + packet.remaining() * 50_000_000L;
        if (old != null && old.id.equals(packet.id()))
            expires = Math.min(expires, old.expires);
        VIEWS.put(packet.ship(), new View(packet.id(), packet.controller(), packet.dimension(), packet.ship(), expires, surface, points.size()));
    }

    public static int count(boolean ship)
    {
        var view = VIEWS.get(ship);
        return view == null || view.expires <= System.nanoTime() ? 0 : view.count;
    }

    public static double alpha(boolean ship)
    {
        var view = VIEWS.get(ship);
        return view == null ? 0 : Math.clamp((view.expires - System.nanoTime()) / (double) LIFETIME_NANOS, 0, 1);
    }

    public static void draw(LevelRenderer renderer)
    {
        var mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = System.nanoTime();
        VIEWS.values().removeIf(v -> v.expires <= now || !v.dimension.equals(mc.level.dimension().identifier().toString()));
        if (VIEWS.isEmpty() || mc.gui.screen() instanceof SceneScreen)
            return;
        try (var collector = renderer.collectPerFrameRenderThreadGizmos())
        {
            for (var view : VIEWS.values())
            {
                double fade = Math.clamp((view.expires - now) / (double) LIFETIME_NANOS, 0, 1);
                int rgb = view.ship ? 0xffbc48 : 0x38e8ff, stroke = ((int) (200 * fade) << 24) | rgb, fill = ((int) (24 * fade) << 24) | rgb;
                for (var pos : view.surface)
                {
                    if (!mc.level.hasChunkAt(pos) || mc.level.getBlockState(pos).isAir())
                        continue;
                    if (view.ship)
                        Gizmos.cuboid(pos, .003f, GizmoStyle.stroke(stroke, 1.15f)).setAlwaysOnTop();
                    else
                        Gizmos.rect(new Vec3(pos.getX() + .003, pos.getY() + 1.004, pos.getZ() + .003), new Vec3(pos.getX() + .997, pos.getY() + 1.004, pos.getZ() + .997), Direction.UP, GizmoStyle.strokeAndFill(stroke, 1, fill)).setAlwaysOnTop();
                }
            }
        }
    }

    public static void initialize()
    {
        ClientPlayNetworking.registerGlobalReceiver(BuildPreviews.Preview.TYPE, (packet, context) -> context.client().execute(() -> accept(packet)));
    }

    public static void clear()
    {
        VIEWS.clear();
    }

    private PreviewRenderer()
    {
    }
}
