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
 * The sounds of air. For ships with someone aboard, from the air system's last step: a hiss where air escapes or enters through a breach (a whistle when it
 * rushes) and a soft vent at each Air Supply while its room fills. For each player: a quiet chime every few seconds while they breathe from their suit inside a
 * hull, and the low-air alarm, which beeps faster as their air runs out, only once they are close to suffocating, wherever they are. Both are the suit's own
 * and need no power from the ship.
 */
public final class AirSounds
{
    /** Air per tick through a breached face that is heard as a hiss, and that rushes into a whistle. */
    public static final int HISS = 1, WHISTLE = 60;
    /**
     * A player's oxygen meter, as they see it, below which the low-air alarm sounds: a couple of seconds before they start to drown. A suited player on the
     * ship's air line rests at this level (the tether floor) and is not dying, so they hear the suit chime and not the alarm.
     */
    public static final int DYING = com.nullandkale.nullspace.gameplay.crew.LifeSupport.SUIT_TETHER_FLOOR;

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
        }
        // The suit's own sounds, for each player whose air life support manages: the alarm close to suffocating anywhere, the chime on suit air in a hull.
        for (var p : server.getPlayerList().getPlayers())
        {
            if (p.isSpectator() || p.isCreative() || !p.isAlive())
                continue;
            ServerLevel level = p.level();
            var meter = com.nullandkale.nullspace.gameplay.crew.LifeSupport.oxygen(p);
            if (meter.isEmpty())
                continue;
            if (meter.getAsInt() < DYING)
                out.accept(new ShipSounds.Loop("alarm.lowair:" + p.getUUID(), "alarm.lowair", null, level, null, List.of(p), ALARM_VOLUME, 1, 0, alarmRate(meter.getAsInt())));
            else if (com.nullandkale.nullspace.gameplay.crew.LifeSupport.onSuitAir(p) && store.at(level, BlockPos.containing(p.getEyePosition())) != null)
                out.accept(new ShipSounds.Loop("air.suit:" + p.getUUID(), "air.suit", null, level, null, List.of(p), SUIT_VOLUME, 1, 0, 1));
        }
    }

    /** How loud the suit's sounds are: the low-air alarm, and the chime on suit air, which is meant to be heard without being noticed. */
    public static final float ALARM_VOLUME = .6f, SUIT_VOLUME = .22f;

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

    /** The low-air alarm's pace for a player's oxygen: the cue's own (about one and a half beeps a second) at the threshold, twice that with no air. */
    public static float alarmRate(int oxygen)
    {
        return 1 + (DYING - Math.clamp(oxygen, 0, DYING)) / (float) DYING;
    }

    private AirSounds()
    {
    }
}
