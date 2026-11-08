package com.nullandkale.nullspace.render.scene;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.*;
import org.joml.Vector3f;

/**
 * Turning snapshot data into quads: block and fluid meshing for a section, celestial spheres and
 * coloured quads.
 */
public final class SceneMeshes
{

    static final class Quad
    {
        final Vector3f[] points;
        float[] uv;
        int color;
        boolean translucent;
        double distance;

        Quad(Vector3f[] points, float[] uv, int color, boolean translucent, double distance)
        {
            this.points = points;
            this.uv = uv;
            this.color = color;
            this.translucent = translucent;
            this.distance = distance;
        }

        double distance()
        {
            return distance;
        }

        boolean translucent()
        {
            return translucent;
        }
    }

    static void localBlocks(CompoundTag data, List<Quad> output, float fill)
    {
        int[] states = data.getIntArray("blocks").orElse(new int[0]);
        int size = data.getIntOr("size", 32);
        if ((size != 16 && size != 32) || states.length != size * size * size)
            return;
        var world = new SectionWorld(data, size);
        var fluids = new net.minecraft.client.renderer.block.FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
        var lights = data.getByteArray("light").orElse(new byte[0]);
        var random = RandomSource.create(0);
        var parts = new ArrayList<BlockStateModelPart>();
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        for (int i = 0; i < states.length; i++)
        {
            var state = Block.stateById(states[i]);
            if (state.isAir())
                continue;
            int x = i % size, y = i / (size * size), z = i / size % size;
            if (!state.getFluidState().isEmpty())
            {
                var collector = new FluidCollector(output);
                fluids.tesselate(world, new BlockPos(x, y, z), layer ->
                {
                    collector.translucent = layer == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT;
                    return collector;
                }, state, state.getFluidState());
                collector.flush();
            }
            parts.clear();
            random.setSeed(i);
            models.get(state).collectParts(random, parts);
            for (var part : parts)
                for (int face = -1; face < 6; face++)
                {
                    var facing = face < 0 ? null : Direction.values()[face];
                    if (facing != null)
                    {
                        int nx = x + facing.getStepX(), ny = y + facing.getStepY(), nz = z + facing.getStepZ();
                        if (!Block.shouldRenderFace(state, world.getBlockState(new BlockPos(nx, ny, nz)), facing))
                            continue;
                    }
                    for (BakedQuad q : part.getQuads(facing))
                    {
                        Vector3f[] positions = new Vector3f[4];
                        float[] uv = new float[8];
                        double distance = 0;
                        for (int v = 0; v < 4; v++)
                        {
                            var p = q.position(v);
                            var w = new Vec3(x + p.x(), y + p.y(), z + p.z());
                            positions[v] = new Vector3f((float) w.x, (float) w.y, (float) w.z);
                            distance += w.lengthSqr();
                            uv[v * 2] = UVPair.unpackU(q.packedUV(v));
                            uv[v * 2 + 1] = UVPair.unpackV(q.packedUV(v));
                        }
                        float shade = q.materialInfo().lightEmission() > 0 ? 1 : switch (q.direction())
                        {
                            case UP -> 1;
                            case DOWN -> .55f;
                            case NORTH, SOUTH -> .8f;
                            default -> .7f;
                        };
                        if (q.materialInfo().lightEmission() == 0 && lights.length == states.length)
                        {
                            int light = Byte.toUnsignedInt(lights[i]);
                            var dir = q.direction();
                            int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size && nz >= 0 && nz < size)
                                light = Math.max(light, Byte.toUnsignedInt(lights[nx + nz * size + ny * size * size]));
                            shade *= Math.max(fill, .15f + .85f * Math.min(15, light) / 15f);
                        }
                        int c = (int) (shade * 255);
                        int color = 0xff000000 | c << 16 | c << 8 | c;
                        if (q.materialInfo().isTinted())
                        {
                            var tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, q.materialInfo().tintIndex());
                            int tint = tintSource == null ? -1 : tintSource.colorInWorld(state, world, new BlockPos(x, y, z));
                            color = 0xff000000 | ((tint >> 16 & 255) * c / 255) << 16 | ((tint >> 8 & 255) * c / 255) << 8 | (tint & 255) * c / 255;
                        }
                        output.add(new Quad(positions, uv, color, q.materialInfo().layer().translucent(), distance));
                    }
                }
        }
    }

    static final class SectionWorld implements net.minecraft.client.renderer.block.BlockAndTintGetter
    {
        final CompoundTag data;
        final int size;
        final int[] blocks, halo;
        final byte[] lights;

        SectionWorld(CompoundTag d, int n)
        {
            data = d;
            size = n;
            blocks = d.getIntArray("blocks").orElse(new int[0]);
            halo = d.getIntArray("halo").orElse(new int[0]);
            lights = d.getByteArray("light").orElse(new byte[0]);
        }

        public net.minecraft.world.level.CardinalLighting cardinalLighting()
        {
            return net.minecraft.world.level.CardinalLighting.DEFAULT;
        }

        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine()
        {
            return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
        }

        public int getBlockTint(BlockPos p, net.minecraft.world.level.ColorResolver c)
        {
            return data.getIntOr(c == BiomeColors.GRASS_COLOR_RESOLVER ? "grass" : c == BiomeColors.FOLIAGE_COLOR_RESOLVER || c == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER ? "foliage" : "water", c == BiomeColors.WATER_COLOR_RESOLVER ? 0x3f76e4 : 0x90bc70);
        }

        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p)
        {
            return null;
        }

        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p)
        {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            if (x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size)
                return Block.stateById(blocks[x + z * size + y * size * size]);
            if (size == 16 && halo.length == 5832 && x >= -1 && x <= 16 && y >= -1 && y <= 16 && z >= -1 && z <= 16)
                return Block.stateById(halo[(x + 1) + (z + 1) * 18 + (y + 1) * 324]);
            return Blocks.AIR.defaultBlockState();
        }

        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p)
        {
            return getBlockState(p).getFluidState();
        }

        public int getHeight()
        {
            return size;
        }

        public int getMinY()
        {
            return 0;
        }

        public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos p)
        {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            return x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size && lights.length == blocks.length ? Byte.toUnsignedInt(lights[x + z * size + y * size * size]) : 15;
        }

        public int getRawBrightness(BlockPos p, int dark)
        {
            return getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p);
        }
    }

    static final class FluidCollector implements VertexConsumer
    {
        final List<Quad> output;
        final Vector3f[] points = new Vector3f[4];
        final float[] uv = new float[8];
        int count, color = -1;
        boolean translucent = true;

        FluidCollector(List<Quad> output)
        {
            this.output = output;
        }

        void flush()
        {
            if (count == 4)
            {
                double d = 0;
                for (var p : points)
                    d += p.lengthSquared();
                output.add(new Quad(points.clone(), uv.clone(), translucent ? (color & 0x00ffffff) | 0xb0000000 : color, translucent, d));
            }
            count = 0;
        }

        public VertexConsumer addVertex(float x, float y, float z)
        {
            if (count == 4)
                flush();
            points[count++] = new Vector3f(x, y, z);
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a)
        {
            color = a << 24 | r << 16 | g << 8 | b;
            return this;
        }

        public VertexConsumer setColor(int c)
        {
            color = c;
            return this;
        }

        public VertexConsumer setUv(float u, float v)
        {
            uv[(count - 1) * 2] = u;
            uv[(count - 1) * 2 + 1] = v;
            return this;
        }

        public VertexConsumer setUv1(int u, int v)
        {
            return this;
        }

        public VertexConsumer setUv2(int u, int v)
        {
            return this;
        }

        public VertexConsumer setUv3(float u, float v)
        {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z)
        {
            return this;
        }

        public VertexConsumer setLineWidth(float w)
        {
            return this;
        }
    }

    static void sphere(Vec3 center, double radius, Vec3 eye, List<Quad> output, int color, boolean emissive, boolean earth, Vec3 sun)
    {
        int segments = 48, rings = 24;
        var light = sun.subtract(center).normalize();
        for (int y = 0; y < rings; y++)
            for (int x = 0; x < segments; x++)
            {
                Vector3f[] vertices = new Vector3f[4];
                var normal = Vec3.ZERO;
                int[][] corners = {{x, y}, {x + 1, y}, {x + 1, y + 1}, {x, y + 1}};
                for (int n = 0; n < 4; n++)
                {
                    double lat = corners[n][1] * Math.PI / rings, lon = corners[n][0] * Math.PI * 2 / segments;
                    var unit = new Vec3(Math.sin(lat) * Math.cos(lon), Math.cos(lat), Math.sin(lat) * Math.sin(lon));
                    normal = normal.add(unit);
                    var point = center.add(unit.scale(radius)).subtract(eye);
                    vertices[n] = new Vector3f((float) point.x, (float) point.y, (float) point.z);
                }
                normal = normal.normalize();
                int base = color;
                if (earth && Math.sin(normal.x * 13 + normal.z * 7) + Math.sin(normal.y * 19 - normal.z * 9) > .45)
                    base = 0xff4c944d;
                float shade = emissive ? 1 : (float) (.08 + .92 * Math.max(0, normal.dot(light)));
                int r = (int) ((base >> 16 & 255) * shade), g = (int) ((base >> 8 & 255) * shade), b = (int) ((base & 255) * shade);
                output.add(colored(vertices, 0xff000000 | r << 16 | g << 8 | b, false));
            }
    }

    static Quad colored(Vector3f[] p, int color, boolean transparent)
    {
        double d = 0;
        for (var v : p)
            d += v.lengthSquared();
        return new Quad(p, new float[]{-1, 0, -1, 0, -1, 0, -1, 0}, color, transparent, d);
    }

    static void emit(VertexConsumer output, Quad quad)
    {
        for (int i = 0; i < 4; i++)
            output.addVertex(quad.points[i]).setUv(quad.uv[i * 2], quad.uv[i * 2 + 1]).setColor(quad.color);
    }

    private SceneMeshes()
    {
    }
}
