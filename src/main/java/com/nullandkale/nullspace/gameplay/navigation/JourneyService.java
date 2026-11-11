package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.JourneyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;

import java.util.*;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Saved, per-vessel gameplay time. The loading handshake is deliberately separate. */
public final class JourneyService
{
    private static final long SIGNAL_COOLDOWN = 5 * 60 * 20;

    public static boolean active(CompoundTag ship)
    {
        return ship != null && ship.contains("journey");
    }

    public static boolean cruising(CompoundTag ship)
    {
        return active(ship) && ship.getCompoundOrEmpty("journey").getStringOr("state", "").equals("CRUISE");
    }

    public static boolean special(MinecraftServer server, String address)
    {
        return WorldStore.get(server).section("journeyRegions").contains(address);
    }

    public static LocalSpaces.Destination region(MinecraftServer server, String address)
    {
        var t = WorldStore.get(server).section("journeyRegions").getCompoundOrEmpty(address);
        if (t.isEmpty())
            return null;
        return new LocalSpaces.Destination(address, t.getStringOr("system", "sol"), t.getStringOr("systemName", "Sol"), t.getStringOr("name", "Deep space"), t.getLongOr("seed", 1), 0xff999999, false);
    }

    private static void region(MinecraftServer server, String id, LocalSpaces.Destination origin, String name, long seed)
    {
        var regions = WorldStore.get(server).section("journeyRegions");
        if (regions.contains(id))
            return;
        var t = new CompoundTag();
        t.putString("system", origin.system());
        t.putString("systemName", origin.systemName());
        t.putString("name", name);
        t.putLong("seed", seed);
        regions.put(id, t);
    }

    public static CompoundTag plan(MinecraftServer server, CompoundTag ship, String destination, int cost)
    {
        // Every journey rolls its own signal; tests may fix the next one with nextSignalNonce.
        long nonce = ship.contains("nextSignalNonce") ? ship.getLongOr("nextSignalNonce", 0) : java.util.concurrent.ThreadLocalRandom.current().nextLong();
        ship.remove("nextSignalNonce");
        // Tests may also shorten the next journey, as they fix its signal, so that waiting through a real cruise stays cheap.
        int seconds = ship.contains("nextJourneySeconds") ? Math.max(1, ship.getIntOr("nextJourneySeconds", 1)) : -1;
        ship.remove("nextJourneySeconds");
        var plan = preview(server, ship, destination, nonce);
        var from = LocalSpaces.get(server, LocalSpaces.address(ship));
        // A signal is as dangerous as the more dangerous end of the trip.
        int danger = Math.max(StarSystems.danger(server, from.id()), StarSystems.danger(server, destination));
        String contact = danger >= 2 && (plan.kind() == 1 || plan.kind() == 2) ? "Distress call: " + plan.name() : plan.name();
        var j = new CompoundTag();
        j.putInt("version", 1);
        j.putString("destination", destination);
        j.putString("from", from.id());
        j.putString("corridor", "j_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        j.putString("encounter", plan.site());
        j.putString("contact", contact);
        j.putInt("danger", danger);
        j.putLong("seed", plan.seed());
        j.putInt("kind", plan.kind());
        int length = seconds > 0 ? seconds : plan.seconds();
        j.putInt("duration", length * 20);
        boolean cooledDown = !ship.contains("lastSignalAt") || server.overworld().getGameTime() - ship.getLongOr("lastSignalAt", 0) >= SIGNAL_COOLDOWN;
        j.putInt("contactAt", plan.hasSignal() && cooledDown ? Math.min(plan.contactSecond(), length) * 20 : Integer.MAX_VALUE);
        j.putInt("elapsed", 0);
        j.putInt("paid", cost);
        j.putString("state", "CRUISE");
        if (ship.contains("destination"))
            j.put("selection", ship.getCompoundOrEmpty("destination").copy());
        return j;
    }

    public static JourneyPlan preview(MinecraftServer server, CompoundTag ship, String destination)
    {
        return preview(server, ship, destination, null);
    }

    private static JourneyPlan preview(MinecraftServer server, CompoundTag ship, String destination, Long nonce)
    {
        var level = WorldStore.level(server, ship.getStringOr("dimension", ""));
        var from = LocalSpaces.get(server, LocalSpaces.address(ship));
        var to = LocalSpaces.get(server, destination);
        double lightYears = StarSystems.sameSystem(server, from.id(), to.id()) ? 0 : StarSystems.lightYears(server, from.id(), to.id());
        long seed = server.overworld().getSeed();
        int tier = StarSystems.engineTier(level, ship);
        return nonce == null ? JourneyPlan.between(seed, from.id(), to.id(), lightYears, tier) : JourneyPlan.between(seed, from.id(), to.id(), lightYears, tier, nonce);
    }

    public static void register(MinecraftServer server, CompoundTag j)
    {
        var from = LocalSpaces.get(server, j.getStringOr("from", ""));
        region(server, j.getStringOr("corridor", ""), from, "FTL journey", j.getLongOr("seed", 1));
        region(server, j.getStringOr("encounter", ""), from, j.getStringOr("contact", "Signal"), j.getLongOr("seed", 1));
        var sites = WorldStore.get(server).section("encounters");
        String id = j.getStringOr("encounter", "");
        if (!sites.contains(id))
        {
            var site = new CompoundTag();
            site.putString("state", "planned");
            site.putInt("kind", j.getIntOr("kind", 0));
            site.putLong("seed", j.getLongOr("seed", 1));
            site.putString("name", j.getStringOr("contact", "Signal"));
            site.putInt("danger", j.getIntOr("danger", 0));
            sites.put(id, site);
        }
    }

    public static boolean contact(CompoundTag ship)
    {
        var j = ship.getCompoundOrEmpty("journey");
        return cruising(ship) && !j.getBooleanOr("visited", false) && j.getIntOr("elapsed", 0) >= j.getIntOr("contactAt", Integer.MAX_VALUE);
    }

    public static String summary(MinecraftServer server, CompoundTag ship)
    {
        if (!active(ship))
            return "";
        var j = ship.getCompoundOrEmpty("journey");
        String to = LocalSpaces.get(server, j.getStringOr("destination", "")).name();
        int remaining = Math.max(0, (j.getIntOr("duration", 0) - j.getIntOr("elapsed", 0) + 19) / 20);
        return (!cruising(ship) ? "Journey paused" : "FTL " + remaining / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", remaining % 60)) + " to " + to + (contact(ship) ? " | Signal: " + j.getStringOr("contact", "") + " (Trip: Visit signal)" : "");
    }

    public static String stopReason(MinecraftServer server, CompoundTag ship)
    {
        if (!contact(ship))
            return "No unvisited signal on this journey";
        var site = WorldStore.get(server).section("encounters").getCompoundOrEmpty(ship.getCompoundOrEmpty("journey").getStringOr("encounter", ""));
        var vessel = WorldStore.get(server).repository().find(site.getStringOr("vessel", ""));
        if (vessel == null)
            return !site.getStringOr("state", "").equals("planned") ? "This site has already been salvaged" : "Signal resolving; try again shortly";
        return StationAccess.ready(vessel) ? "" : "Signal resolving; try again shortly";
    }

    /** Select the investigated site's berth once; the paid destination stays in journey.selection. */
    public static boolean selectEncounterDock(net.minecraft.server.level.ServerPlayer player, CompoundTag ship)
    {
        if (!active(ship) || cruising(ship))
            return false;
        var journey = ship.getCompoundOrEmpty("journey");
        String region = journey.getStringOr("encounter", "");
        if (journey.getBooleanOr("encounterDockSelected", false) || !LocalSpaces.address(ship).equals(region))
            return false;
        var selected = ShipDestination.selected(ship);
        if (selected != null && selected.kind() == ShipDestination.Kind.PORT && selected.region().equals(region))
        {
            journey.putBoolean("encounterDockSelected", true);
            return true;
        }
        String vessel = WorldStore.get(player.level().getServer()).section("encounters").getCompoundOrEmpty(region).getStringOr("vessel", "");
        var berth = ShipDestination.catalog(player, ship).stream().filter(d -> d.kind() == ShipDestination.Kind.PORT && d.region().equals(region) && d.reference().equals(vessel) && !d.state().equalsIgnoreCase("Occupied") && !d.state().equalsIgnoreCase("Unavailable")).findFirst();
        if (berth.isEmpty())
            return false;
        ship.put("destination", berth.get().save());
        journey.putBoolean("encounterDockSelected", true);
        return true;
    }

    public static void tick(MinecraftServer server)
    {
        if (server.getTickCount() % 20 != 0)
            return;
        EncounterSites.tick(server);
        for (var tag : List.copyOf(WorldStore.get(server).ships()))
        {
            var ship = (CompoundTag) tag;
            if (!active(ship) || !ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
                continue;
            var crew = server.getPlayerList().getPlayers().stream().filter(p -> p.isAlive() && SceneService.observer(p) == ship).toList();
            if (crew.isEmpty() || NavigationService.pending(server, ship.getStringOr("id", "")))
                continue;
            var j = ship.getCompoundOrEmpty("journey");
            if (cruising(ship))
            {
                int elapsed = Math.min(j.getIntOr("duration", 0), j.getIntOr("elapsed", 0) + 20);
                j.putInt("elapsed", elapsed);
                Piloting.stop(ship);
                if (contact(ship) && !j.getBooleanOr("announced", false))
                {
                    j.putBoolean("announced", true);
                    ship.putLong("lastSignalAt", server.overworld().getGameTime());
                    for (var p : crew)
                        p.sendSystemMessage(Component.literal("Signal: " + j.getStringOr("contact", "") + ". In the ship controller's Trip panel, choose Visit signal, or keep travelling."));
                }
                if (elapsed >= j.getIntOr("duration", 0))
                    Jumps.arrive(crew.getFirst(), ship);
                WorldStore.get(server).requestSave();
            }
            if (active(ship))
                for (var p : crew)
                    p.sendSystemMessage(Component.literal(summary(server, ship)), true);
        }
    }

    private JourneyService()
    {
    }
}
