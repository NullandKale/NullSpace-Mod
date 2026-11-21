package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.model.air.SpatialAir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The sounds of a hull's air, from the air system's last step, for ships with someone aboard: a hiss where air escapes or enters through a breach (a whistle
 * when it rushes), a soft vent at each Air Supply while its room fills, the ship's breach klaxon while air is lost to the outside, and, for each player
 * breathing thin air, an alarm that beeps faster as the air falls. The low-air alarm is the crew's own warning and needs no power; the klaxon is the ship's and
 * needs power for its controls, which a battery gives when the generators stop.
 */
public final class AirSounds
{
    /** Air per tick through a breached face that is heard as a hiss, and that rushes into a whistle. */
    public static final int HISS = 1, WHISTLE = 60;
    /** Oxygen, as players see it, below which the alarm sounds. */
    public static final int LOW_AIR = 40;
    /** Air per tick lost through all of a hull's breaches that sounds the klaxon. */
    static final int KLAXON = 10;

    public static void initialize()
    {
        ShipSounds.producer(AirSounds::loops);
    }

    private record Aboard(ServerLevel level, CompoundTag ship, List<ServerPlayer> players)
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
            if (ship == null || !ShipSystems.enabled(ship))
                continue;
            ships.computeIfAbsent(ship.getStringOr("id", ""), id -> new Aboard(p.level(), ship, new ArrayList<>())).players().add(p);
        }
        for (var entry : ships.entrySet())
        {
            var aboard = entry.getValue();
            var r = ShipSystems.runtime(aboard.level(), aboard.ship());
            var grid = ShipSystems.grid(aboard.ship());
            var base = WorldStore.pos(aboard.ship(), "base");
            SpatialAir.Flow breach = null;
            for (var flow : r.flows)
                if (flow.exterior() && (breach == null || flow.amount() > breach.amount()))
                    breach = flow;
            if (breach != null && breach.amount() >= HISS)
            {
                var at = Vec3.atCenterOf(base.offset(grid.x(breach.cell()), grid.y(breach.cell()), grid.z(breach.cell()))).add(breach.dx() * .5, breach.dy() * .5, breach.dz() * .5);
                out.accept(new ShipSounds.Loop("air.breach:" + entry.getKey(), breachCue(breach.amount()), null, aboard.level(), at, null, breachVolume(breach.amount()), 1, breachBrightness(breach.amount()), 1));
            }
            if (escaping(r) >= KLAXON && ShipSystems.power(aboard.level(), aboard.ship(), com.nullandkale.nullspace.model.power.PowerBus.System.CONTROLS) > 0)
                out.accept(new ShipSounds.Loop("alarm.breach:" + entry.getKey(), "alarm.breach", null, aboard.level(), null, List.copyOf(aboard.players()), KLAXON_VOLUME, 1, 0, 1));
            // An Air Supply reports its flow only while its room takes air; a room that is nearly full fills gently.
            int index = 0;
            for (var outlet : r.outlets)
            {
                int cell = ShipSystems.local(aboard.ship(), outlet);
                boolean filling = r.flows.stream().anyMatch(f -> !f.exterior() && f.cell() == cell && f.amount() > 0);
                if (!filling || cell < 0 || cell >= r.air.length)
                    continue;
                int percent = SpatialAir.percent(r.air[cell]);
                out.accept(new ShipSounds.Loop("air.refill:" + entry.getKey() + ":" + index++, "air.refill", null, aboard.level(), Vec3.atCenterOf(outlet), null, refillVolume(percent), 1, refillBrightness(percent), 1));
                if (index >= 3)
                    break;
            }
            for (var p : aboard.players())
            {
                if (p.isCreative())
                    continue;
                int oxygen = ShipAir.oxygen(aboard.level(), aboard.ship(), BlockPos.containing(p.getEyePosition()));
                if (oxygen >= LOW_AIR)
                    continue;
                out.accept(new ShipSounds.Loop("alarm.lowair:" + p.getUUID(), "alarm.lowair", null, aboard.level(), null, List.of(p), ALARM_VOLUME, 1, 0, alarmRate(oxygen)));
            }
        }
    }

    /** How loud the alarms are: the low-air alarm and the breach klaxon. */
    public static final float ALARM_VOLUME = .8f, KLAXON_VOLUME = .55f;

    /** The sound of a breach through which {@code amount} air flows per tick: a hiss, a whistle when it rushes. */
    public static String breachCue(int amount)
    {
        return amount >= WHISTLE ? "air.leak" : "air.hiss";
    }

    public static float breachVolume(int amount)
    {
        return Math.min(1, .3f + amount / 200f);
    }

    public static float breachBrightness(int amount)
    {
        return Math.min(1, amount / 300f);
    }

    /** A room filling is heard plainly, and once it is over 80% softer and darker. */
    public static float refillVolume(int percent)
    {
        return percent >= 80 ? .45f : .8f;
    }

    public static float refillBrightness(int percent)
    {
        return percent >= 80 ? -.2f : 0;
    }

    /** The low-air alarm's pace for a player's oxygen: the cue's own (two beeps a second) at the threshold, three times that with no air. */
    public static float alarmRate(int oxygen)
    {
        return 1 + 2 * (LOW_AIR - Math.clamp(oxygen, 0, LOW_AIR)) / (float) LOW_AIR;
    }

    /**
     * Air lost to the outside per tick: the exterior flows that point out of their room. A flow of air coming in (on a world with more air than the hull) points
     * into its cell, where the next cell is the room's own.
     */
    static int escaping(ShipRuntime r)
    {
        if (r.atmosphere == null)
            return 0;
        var grid = r.atmosphere.grid();
        var roomAt = r.atmosphere.roomAt();
        int total = 0;
        for (var flow : r.flows)
        {
            if (!flow.exterior())
                continue;
            int x = grid.x(flow.cell()) + flow.dx(), y = grid.y(flow.cell()) + flow.dy(), z = grid.z(flow.cell()) + flow.dz();
            if (!grid.contains(x, y, z) || roomAt[grid.index(x, y, z)] < 0)
                total += flow.amount();
        }
        return total;
    }

    private AirSounds()
    {
    }
}
