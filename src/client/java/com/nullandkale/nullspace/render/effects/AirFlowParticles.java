package com.nullandkale.nullspace.render.effects;

import com.nullandkale.nullspace.gameplay.systems.AirEffects;
import com.nullandkale.nullspace.render.SpaceSettings;

import java.util.*;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

/** Owned short-lived flow particles: a settled room can clear its own effects immediately. */
public final class AirFlowParticles
{
    private static ClientLevel world;
    private static final Map<BlockPos, List<Particle>> SOURCES = new LinkedHashMap<>();

    public static void accept(AirEffects.FlowPacket packet)
    {
        var client = Minecraft.getInstance();
        if (world != client.level)
            clear();
        world = client.level;
        if (world == null || !SpaceSettings.get().airParticles)
        {
            clear();
            return;
        }
        if (!world.dimension().identifier().toString().equals(packet.dimension()))
            return;
        if (packet.values().length == 0)
        {
            remove(packet.base());
            return;
        }
        if (!SOURCES.containsKey(packet.base()) && SOURCES.size() >= 16)
            remove(SOURCES.keySet().iterator().next());
        var particles = SOURCES.computeIfAbsent(packet.base(), key -> new ArrayList<>());
        particles.removeIf(p -> !p.isAlive());
        var data = packet.values();
        for (int i = 0; i + 5 < data.length && particles.size() < 256; i += 6)
        {
            int cell = data[i];
            if (cell < 0 || cell >= 32768 || data[i + 4] <= 0)
                continue;
            double x = packet.base().getX() + cell % 32 + .5;
            double y = packet.base().getY() + cell / 1024 + .5;
            double z = packet.base().getZ() + cell / 32 % 32 + .5;
            double speed = Math.min(.35, .025 + data[i + 4] / 1000.0);
            for (int n = 0; n < 2; n++)
            {
                double dx = Math.clamp(data[i + 1], -1, 1), dy = Math.clamp(data[i + 2], -1, 1), dz = Math.clamp(data[i + 3], -1, 1);
                if (dx == 0 && dy == 0 && dz == 0)
                {
                    dx = world.getRandom().nextDouble() * 2 - 1;
                    dy = world.getRandom().nextDouble() * 2 - 1;
                    dz = world.getRandom().nextDouble() * 2 - 1;
                }
                var particle = client.particleEngine.createParticle(ParticleTypes.CLOUD, x + (world.getRandom().nextDouble() - .5) * .3, y + (world.getRandom().nextDouble() - .5) * .3, z + (world.getRandom().nextDouble() - .5) * .3, dx * speed, dy * speed, dz * speed);
                if (particle != null)
                {
                    particle.setLifetime(8);
                    particles.add(particle);
                }
            }
        }
    }

    public static int activeParticles()
    {
        return SOURCES.values().stream().mapToInt(p -> (int) p.stream().filter(Particle::isAlive).count()).sum();
    }

    private static void remove(BlockPos base)
    {
        var particles = SOURCES.remove(base);
        if (particles != null)
            particles.forEach(Particle::remove);
    }

    public static void clear()
    {
        SOURCES.values().forEach(p -> p.forEach(Particle::remove));
        SOURCES.clear();
        world = null;
    }

    public static void initialize()
    {
        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            if (world != client.level || !SpaceSettings.get().airParticles)
            {
                clear();
                return;
            }
            SOURCES.values().forEach(p -> p.removeIf(particle -> !particle.isAlive()));
            SOURCES.values().removeIf(List::isEmpty);
        });
    }

    private AirFlowParticles()
    {
    }
}
