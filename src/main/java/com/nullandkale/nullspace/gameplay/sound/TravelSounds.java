package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.scene.SceneService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The sounds of travel: the jolt into and out of FTL and the arrival chime (played by the jump itself, see {@code NavigationService}), and the low drone the
 * crew hear while their ship cruises.
 */
public final class TravelSounds
{
    public static void initialize()
    {
        ShipSounds.producer(TravelSounds::loops);
    }

    /** What a jump step sounds like for the crew: a jolt as the ship enters or leaves FTL, and the chime when the journey ends. */
    public static void jump(MinecraftServer server, CompoundTag ship, boolean arriving)
    {
        ShipSounds.crew(server, ship, "ftl.jolt");
        if (arriving)
            ShipSounds.crew(server, ship, "ftl.arrive");
    }

    static void loops(MinecraftServer server, Consumer<ShipSounds.Loop> out)
    {
        var crews = new HashMap<String, List<ServerPlayer>>();
        for (var p : server.getPlayerList().getPlayers())
        {
            var ship = SceneService.observer(p);
            if (ship != null && JourneyService.cruising(ship))
                crews.computeIfAbsent(ship.getStringOr("id", ""), s -> new ArrayList<>()).add(p);
        }
        crews.forEach((id, crew) -> out.accept(new ShipSounds.Loop("ftl.drone:" + id, "ftl.drone", null, crew.getFirst().level(), null, List.copyOf(crew), 1, 1, 0, 1)));
    }

    private TravelSounds()
    {
    }
}
