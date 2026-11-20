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
 * when it rushes), a soft vent at each Air Supply while its room fills, and, for each player breathing thin air, an alarm that beeps faster as the air falls.
 * The alarm is the crew's own warning and needs no power, so it keeps sounding when the generators stop.
 */
public final class AirSounds
{
    /** Air per tick through a breached face that is heard as a hiss, and that rushes into a whistle. */
    static final int HISS = 1, WHISTLE = 60;
    /** Oxygen, as players see it, below which the alarm sounds. */
    static final int LOW_AIR = 40;

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
                float strength = Math.min(1, .3f + breach.amount() / 200f);
                out.accept(new ShipSounds.Loop("air.breach:" + entry.getKey(), breach.amount() >= WHISTLE ? "air.leak" : "air.hiss", null, aboard.level(), at, null, strength, 1, Math.min(1, breach.amount() / 300f), 1));
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
                out.accept(new ShipSounds.Loop("air.refill:" + entry.getKey() + ":" + index++, "air.refill", null, aboard.level(), Vec3.atCenterOf(outlet), null, percent >= 80 ? .45f : .8f, 1, percent >= 80 ? -.2f : 0, 1));
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
                // Faster as the air falls: the cue's pace (two beeps a second) at the threshold, three times that with no air.
                float rate = 1 + 2 * (LOW_AIR - oxygen) / (float) LOW_AIR;
                out.accept(new ShipSounds.Loop("alarm.lowair:" + p.getUUID(), "alarm.lowair", null, aboard.level(), null, List.of(p), .8f, 1, 0, rate));
            }
        }
    }

    private AirSounds()
    {
    }
}
