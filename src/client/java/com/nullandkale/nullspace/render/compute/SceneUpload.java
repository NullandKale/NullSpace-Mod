package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import com.nullandkale.nullspace.model.space.PlanetSky;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.render.SpaceSettings;

import java.nio.ByteBuffer;
import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Writes one frame's scene into the shader buffers: bodies, volumes, the selected atmosphere and
 * per-pass uniforms.
 */
final class SceneUpload
{

    static void upload(SpaceComputeRenderer o, int buffer, ByteBuffer bytes, int binding)
    {
        bytes.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_STREAM_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
        o.uploadedBytes += bytes.remaining();
    }

    static void prepareData(SpaceComputeRenderer o, CompoundTag scene, Vec3 eye, double time)
    {
        o.surfaceAtmosphere = scene.getBooleanOr("surfaceAtmosphere", false);
        o.surfaceRain = (float) scene.getDoubleOr("surfaceRain", 0);
        o.surfaceThunder = (float) scene.getDoubleOr("surfaceThunder", 0);
        var level = Minecraft.getInstance().level;
        // Weather belongs to the viewed surface, never to an unrelated world or an airless moon.
        if (o.surfaceAtmosphere && scene.getBooleanOr("planetSky", false) && level != null && level.dimension().identifier().toString().equals(scene.getStringOr("dimension", "")))
        {
            o.surfaceRain = level.getRainLevel(1);
            o.surfaceThunder = level.getThunderLevel(1);
        }
        o.surfaceRain = o.surfaceAtmosphere ? Math.clamp(Float.isFinite(o.surfaceRain) ? o.surfaceRain : 0, 0, 1) : 0;
        o.surfaceThunder = o.surfaceAtmosphere ? Math.clamp(Float.isFinite(o.surfaceThunder) ? o.surfaceThunder : 0, 0, o.surfaceRain) : 0;
        var observer = scene.getListOrEmpty("subjects").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", ""))).findFirst().orElse(new CompoundTag());
        o.surfaceUp.set(0, 1, 0).rotate(new Quaternionf().rotationYXZ((float) Math.toRadians(observer.getDoubleOr("yaw", 0)), (float) Math.toRadians(observer.getDoubleOr("pitch", 0)), (float) Math.toRadians(observer.getDoubleOr("roll", 0))));
        o.current = PreparedScenes.prepared(scene.getCompoundOrEmpty("catalog"));
        o.bodyBytes.clear();
        o.sceneBytes.clear();
        o.bodyCount = o.current.bodies.size();
        double elapsed = time - o.current.definition.epoch();
        selectAtmosphere(o, scene, eye, elapsed);
        for (var b : o.current.bodies)
        {
            var p = b.at(elapsed).subtract(o.celestialEye);
            ComputeMath.vec(o.bodyBytes, p.x, p.y, p.z, b.radius());
            ComputeMath.rgb(o.bodyBytes, b.color(), b.emission());
            ComputeMath.vec(o.bodyBytes, b.velocity().x, b.velocity().y, b.velocity().z, b.kind());
            ComputeMath.vec(o.bodyBytes, b.roughness(), Math.floorMod(b.seed(), 10000), b.atmosphere(), b.spin());
            ComputeMath.vec(o.bodyBytes, b.rotationAxis().x, b.rotationAxis().y, b.rotationAxis().z, elapsed * b.spin());
        }
        var bvh = o.current.bvh(elapsed);
        o.nodeCount = bvh.nodes().size();
        o.nodeBytes.clear();
        bvh.write(o.nodeBytes, o.celestialEye);
        var volumes = new ArrayList<>(o.current.definition.volumes());
        for (var b : o.current.definition.bodies())
            if (b.atmosphere() > 0 && !b.id().equals(o.atmosphereBody))
            {
                double outer = b.radius() * (1 + b.atmosphere());
                volumes.add(new SpaceDefinition.Volume(b.id() + ":air", b.center(), new Vec3(outer, outer, outer), b.velocity(), 0xff75b8ff, .14, .008, 0, .1, b.seed(), 1));
            }
        for (var b : o.current.definition.bodies())
            if (b.kind() == 4)
            {
                var axis = b.velocity().lengthSqr() > 0 ? b.velocity().normalize() : new Vec3(0, 0, 1);
                volumes.add(new SpaceDefinition.Volume(b.id() + ":tail", b.center().subtract(axis.scale(b.radius() * 8)), new Vec3(b.radius() * 2, b.radius() * 2, b.radius() * 12), b.velocity(), 0xff87bfd9, .22, .012, .2, .4, b.seed(), 2));
            }
        if (volumes.size() > 16)
            throw new IllegalArgumentException("Atmospheres/tails exceed volume budget");
        o.volumeCount = volumes.size();
        for (var v : volumes)
        {
            var p = v.center().add(v.velocity().scale(elapsed)).subtract(o.celestialEye);
            ComputeMath.vec(o.sceneBytes, p.x, p.y, p.z, v.kind());
            ComputeMath.vec(o.sceneBytes, v.extent().x, v.extent().y, v.extent().z, v.density());
            ComputeMath.rgb(o.sceneBytes, v.color(), v.extinction());
            ComputeMath.vec(o.sceneBytes, (v.color() >> 16 & 255) / 255. * v.emission(), (v.color() >> 8 & 255) / 255. * v.emission(), (v.color() & 255) / 255. * v.emission(), v.phase());
            double inner = v.kind() == 1 ? o.current.definition.bodies().stream().filter(b -> (b.id() + ":air").equals(v.id())).mapToDouble(SpaceDefinition.Body::radius).findFirst().orElse(0) : 0;
            var axis = v.velocity().lengthSqr() > 0 ? v.velocity().normalize() : new Vec3(0, 0, 1);
            ComputeMath.vec(o.sceneBytes, v.kind() == 2 ? axis.x : inner, v.kind() == 2 ? axis.y : 0, v.kind() == 2 ? axis.z : 0, Math.floorMod(v.seed(), 10000));
        }
        while (o.sceneBytes.position() < 80 * 16)
            o.sceneBytes.putLong(0);
        o.cabinCount = 0;
        for (Tag tag : scene.getListOrEmpty("subjects"))
        {
            if (o.cabinCount == 64)
                break;
            var subject = (CompoundTag) tag;
            var mask = VisualCabins.mask(subject);
            boolean interior = false;
            for (int bits : mask)
                if (bits != 0)
                {
                    interior = true;
                    break;
                }
            if (!interior)
                continue;
            var position = new Vec3(subject.getDoubleOr("x", 0), subject.getDoubleOr("y", 0), subject.getDoubleOr("z", 0));
            var orientation = new Quaternionf().rotationYXZ((float) Math.toRadians(subject.getDoubleOr("yaw", 0)), (float) Math.toRadians(subject.getDoubleOr("pitch", 0)), (float) Math.toRadians(subject.getDoubleOr("roll", 0))).conjugate();
            var delta = eye.subtract(position);
            var inverse = new Matrix4f().rotation(orientation).translate((float) delta.x, (float) delta.y, (float) delta.z);
            inverse.get(o.sceneBytes.position(), o.sceneBytes);
            o.sceneBytes.position(o.sceneBytes.position() + 64);
            ComputeMath.vec(o.sceneBytes, 0, 0, 0, 0);
            for (int bits : mask)
                o.sceneBytes.putInt(bits);
            o.cabinCount++;
        }
        if (o.bodyBytes.position() == 0)
            ComputeMath.vec(o.bodyBytes, 0, 0, 0, 0);
        if (o.nodeBytes.position() == 0)
            ComputeMath.vec(o.nodeBytes, 0, 0, 0, 0);
        upload(o, o.bodyBuffer, o.bodyBytes, 0);
        upload(o, o.nodeBuffer, o.nodeBytes, 1);
        upload(o, o.sceneBuffer, o.sceneBytes, 2);
    }

    static void selectAtmosphere(SpaceComputeRenderer o, CompoundTag scene, Vec3 eye, double elapsed)
    {
        o.atmosphere = null;
        o.atmosphereBody = "";
        o.celestialEye = eye;
        o.cloudOffset = Vec3.ZERO;
        o.cloudRotation.identity();
        o.atmosphereDistanceScale = 1;
        if (o.surfaceAtmosphere)
        {
            var profile = scene.getCompoundOrEmpty("atmosphereProfile");
            o.atmosphere = profile.isEmpty() ? AtmosphereProfile.temperate(2200, .04) : AtmosphereProfile.decode(profile);
            if (!profile.isEmpty())
            {
                o.atmosphereCenter = new Vec3(scene.getDoubleOr("atmosphereX", 0), scene.getDoubleOr("atmosphereY", 0), scene.getDoubleOr("atmosphereZ", 0)).subtract(eye);
            }
            else
            {
                // Compatibility with old frame fixtures/saves which predate an explicit planet profile.
                var observer = scene.getListOrEmpty("subjects").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", ""))).findFirst().orElse(new CompoundTag());
                o.atmosphereCenter = new Vec3(observer.getDoubleOr("x", 0), observer.getDoubleOr("y", 0), observer.getDoubleOr("z", 0)).subtract(eye).subtract(new Vec3(o.surfaceUp.x, o.surfaceUp.y, o.surfaceUp.z).scale(o.atmosphere.radius() + .05));
            }
            o.atmosphereBody = scene.getStringOr("surfaceBody", "");
        }
        if (scene.getBooleanOr("planetSky", false) && scene.contains("surfaceRadius"))
        {
            var observer = scene.getListOrEmpty("subjects").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", ""))).findFirst().orElse(new CompoundTag());
            var view = PlanetSky.surfaceView(scene, observer, eye, o.surfaceAtmosphere ? o.atmosphere : null);
            o.celestialEye = view.celestialEye();
            if (o.surfaceAtmosphere)
            {
                o.atmosphereCenter = view.center();
                o.cloudOffset = view.cloudOffset();
                o.cloudRotation.rotation(new Quaternionf(view.cloudRotation()));
                o.atmosphereDistanceScale = (float) view.distanceScale();
            }
        }
        if (!o.surfaceAtmosphere)
        {
            double largest = .002;
            for (var b : o.current.definition.bodies())
            {
                if (b.atmosphere() <= 0 || b.radius() < 1)
                    continue;
                var position = b.at(elapsed).subtract(o.celestialEye);
                double apparent = b.radius() / Math.max(1, position.length());
                if (apparent > largest)
                {
                    o.atmosphere = b.atmosphereProfile();
                    o.atmosphereCenter = position;
                    o.atmosphereBody = b.id();
                    largest = apparent;
                }
            }
        }
    }

    static void uniforms(SpaceComputeRenderer o, ComputeProgram p, Matrix4f projection, int w, int h)
    {
        // A cache miss dispatches other programs. Resolve it before binding o pass's program.
        if (o.atmosphere != null)
            o.atmosphereTables = AtmosphereLuts.get(o.atmosphere, SpaceComputeRenderer.transmittanceProgram, SpaceComputeRenderer.multipleProgram);
        p.use();
        p.matrix("InvView", new Matrix4f(projection).invert());
        p.matrix("ViewProjection", projection);
        glUniform3f(p.uniform("StarEye"), (float) o.currentEye.x, (float) o.currentEye.y, (float) o.currentEye.z);
        glUniform3f(p.uniform("StarForward"), (float) o.starForward.x, (float) o.starForward.y, (float) o.starForward.z);
        p.scalar("StarFtl", o.starFtl);
        p.scalar("StarParallax", o.starParallax);
        p.scalar("StarTime", (float) (o.currentTime % 4096));
        p.integer("BodyCount", o.bodyCount);
        int[] lights = java.util.stream.IntStream.range(0, o.current.bodies.size()).filter(i -> o.current.bodies.get(i).emission() > 0).limit(8).toArray();
        p.integer("LightCount", lights.length);
        if (lights.length > 0)
            glUniform1iv(p.uniform("Lights"), lights);
        p.integer("NodeCount", o.nodeCount);
        p.integer("VolumeCount", o.volumeCount);
        p.integer("CabinCount", o.cabinCount);
        p.integer("HasSurfaceAtmosphere", o.surfaceAtmosphere ? 1 : 0);
        p.scalar("SurfaceRain", o.surfaceRain);
        p.scalar("SurfaceThunder", o.surfaceThunder);
        p.integer("HasAtmosphere", o.atmosphere == null ? 0 : 1);
        if (o.atmosphere != null)
        {
            AtmosphereLuts.uniforms(p, o.atmosphere);
            glUniform3f(p.uniform("AtmoCenter"), (float) o.atmosphereCenter.x, (float) o.atmosphereCenter.y, (float) o.atmosphereCenter.z);
            p.integer("TransmittanceLut", 4);
            p.integer("MultipleScatteringLut", 5);
            p.integer("SkyRadianceLut", 6);
            p.integer("SkyTransmissionLut", 7);
            p.integer("CloudsEnabled", SpaceSettings.get().clouds || o.surfaceRain > 0 ? 1 : 0);
            p.scalar("AtmoDistanceScale", o.atmosphereDistanceScale);
            p.matrix("CloudRotation", o.cloudRotation);
            glUniform3f(p.uniform("CloudOffset"), (float) o.cloudOffset.x, (float) o.cloudOffset.y, (float) o.cloudOffset.z);
            p.integer("CloudSteps", SpaceSettings.get().cloudSteps);
            p.scalar("CloudTime", (float) (Math.floor(o.currentTime * 10) / 10));
            o.texture(4, o.atmosphereTables.transmittance.texture());
            o.texture(5, o.atmosphereTables.multiple.texture());
            o.texture(6, o.skyRadiance.texture());
            o.texture(7, o.skyTransmission.texture());
        }
        glUniform3f(p.uniform("SurfaceUp"), o.surfaceUp.x, o.surfaceUp.y, o.surfaceUp.z);
        p.integer("SurfaceSamples", SpaceSettings.get().surfaceSamples);
        p.integer("VolumeSteps", SpaceSettings.get().volumeSteps);
        p.integer("BruteForce", Boolean.getBoolean("nullspace.compute.bruteForce") ? 1 : 0);
        p.scalar("Time", (float) (o.currentTime % 100000));
        glUniform2i(p.uniform("ViewSize"), w, h);
        glUniform2f(p.uniform("Jitter"), 0, 0);
    }

    static void metadata(SpaceComputeRenderer o, Matrix4f projection)
    {
        try (var stack = MemoryStack.stackPush())
        {
            var b = stack.malloc(160);
            new Matrix4f(projection).invert().get(0, b);
            projection.get(64, b);
            b.putFloat(128, o.width).putFloat(132, o.height).putFloat(136, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0).putFloat(140, o.jitterX);
            b.putFloat(144, .05f).putFloat(148, 1e6f).putFloat(152, SpaceComputeRenderer.SLICES).putFloat(156, o.jitterY);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(o.viewBuffer.slice(), b);
        }
    }

    private SceneUpload()
    {
    }
}
