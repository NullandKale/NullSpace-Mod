package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;

import java.util.LinkedHashMap;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The hum of a ship's engines while they thrust, for ships with someone aboard. It plays at the engine nearest the middle of the ship's engines, so the engine
 * room hears it clearly and the rooms beyond hear it through their doors. It follows the same rule as the exhaust ({@link EngineEffects#exhausts}): engines
 * that are connected, powered and asked to push. Harder thrust makes it louder and higher, and speed makes it brighter.
 */
public final class EngineSounds
{
    public static void initialize()
    {
        ShipSounds.producer(EngineSounds::loops);
    }

    private record Aboard(ServerLevel level, CompoundTag ship)
    {}

    static void loops(MinecraftServer server, Consumer<ShipSounds.Loop> out)
    {
        var store = WorldStore.get(server);
        var ships = new LinkedHashMap<String, Aboard>();
        for (var p : server.getPlayerList().getPlayers())
        {
            if (p.isSpectator() || !p.isAlive())
                continue;
            var ship = store.at(p.level(), BlockPos.containing(p.getEyePosition()));
            if (ship != null)
                ships.putIfAbsent(ship.getStringOr("id", ""), new Aboard(p.level(), ship));
        }
        for (var entry : ships.entrySet())
        {
            var hum = hum(entry.getValue().level(), entry.getValue().ship());
            if (hum != null)
                out.accept(new ShipSounds.Loop("engine.hum:" + entry.getKey(), "engine.hum", null, entry.getValue().level(), hum.at(), null, hum.volume(), hum.pitch(), hum.brightness(), 1));
        }
    }

    /** Where a ship's engines are heard from and how they sound now. */
    public record Hum(Vec3 at, float volume, float pitch, float brightness)
    {}

    /** The engines' hum, or null when they are not thrusting. */
    public static Hum hum(ServerLevel level, CompoundTag ship)
    {
        var exhausts = EngineEffects.exhausts(level, ship);
        if (exhausts.isEmpty())
            return null;
        // The engine blocks themselves, behind their exhausts.
        var engines = exhausts.stream().map(e -> e.origin().subtract(e.direction().getStepX() * .6, 0, e.direction().getStepZ() * .6)).toList();
        var middle = engines.stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / engines.size());
        var at = engines.stream().min((a, b) -> Double.compare(a.distanceToSqr(middle), b.distanceToSqr(middle))).orElseThrow();
        return hum(at, ship.getDoubleOr("thrustShare", Math.min(1, ship.getIntOr("thrustDemand", 0) / 8.0)), ship.getDoubleOr("speedShare", 0));
    }

    /** The hum at {@code at} for a thrust and a speed, each 0 to 1 (also the sound gallery's engine stands). */
    public static Hum hum(Vec3 at, double thrust, double speed)
    {
        thrust = Math.clamp(thrust, 0, 1);
        speed = Math.clamp(speed, 0, 1);
        return new Hum(at, (float) (.4 + .6 * thrust), (float) (.85 + .4 * thrust), (float) (.25 * speed));
    }

    private EngineSounds()
    {
    }
}
