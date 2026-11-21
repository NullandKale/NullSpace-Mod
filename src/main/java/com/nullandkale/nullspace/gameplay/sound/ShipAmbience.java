package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The low, steady hum of a hull in space, heard by everyone inside it wherever they stand: the background a crew lives in. Outside a hull, on EVA, there is
 * only silence, and on a world the hum gives way to the world's own sounds.
 */
public final class ShipAmbience
{
    /** How loud the hum plays: under every other sound aboard. */
    public static final float VOLUME = .4f;

    public static void initialize()
    {
        ShipSounds.producer(ShipAmbience::loops);
    }

    static void loops(MinecraftServer server, Consumer<ShipSounds.Loop> out)
    {
        var space = server.getLevel(TravelService.SPACE);
        if (space == null)
            return;
        var store = WorldStore.get(server);
        var crews = new LinkedHashMap<String, List<ServerPlayer>>();
        for (var p : space.players())
        {
            if (p.isSpectator() || !p.isAlive())
                continue;
            var hull = store.at(space, BlockPos.containing(p.getEyePosition()));
            if (hull != null)
                crews.computeIfAbsent(hull.getStringOr("id", ""), id -> new ArrayList<>()).add(p);
        }
        crews.forEach((id, crew) -> out.accept(new ShipSounds.Loop("ship.hum:" + id, "ship.hum", space, null, List.copyOf(crew), VOLUME)));
    }

    private ShipAmbience()
    {
    }
}
