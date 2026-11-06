package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.station.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Saved, per-vessel gameplay time. The loading handshake is deliberately separate. */
public final class JourneyService {
  public static boolean active(CompoundTag ship) {
    return ship != null && ship.contains("journey");
  }

  public static boolean cruising(CompoundTag ship) {
    return active(ship)
        && ship.getCompoundOrEmpty("journey").getStringOr("state", "").equals("CRUISE");
  }

  public static boolean special(MinecraftServer server, String address) {
    return ShipStore.get(server).section("journeyRegions").contains(address);
  }

  public static LocalSpaces.Destination region(MinecraftServer server, String address) {
    var t = ShipStore.get(server).section("journeyRegions").getCompoundOrEmpty(address);
    if (t.isEmpty()) return null;
    return new LocalSpaces.Destination(
        address,
        t.getStringOr("system", "sol"),
        t.getStringOr("systemName", "Sol"),
        t.getStringOr("name", "Deep space"),
        t.getLongOr("seed", 1),
        0xff999999,
        false);
  }

  private static void region(
      MinecraftServer server, String id, LocalSpaces.Destination origin, String name, long seed) {
    var regions = ShipStore.get(server).section("journeyRegions");
    if (regions.contains(id)) return;
    var t = new CompoundTag();
    t.putString("system", origin.system());
    t.putString("systemName", origin.systemName());
    t.putString("name", name);
    t.putLong("seed", seed);
    regions.put(id, t);
  }

  public static CompoundTag plan(
      MinecraftServer server, CompoundTag ship, String destination, int cost) {
    var plan = preview(server, ship, destination);
    var from = LocalSpaces.get(server, LocalSpaces.address(ship));
    var j = new CompoundTag();
    j.putInt("version", 1);
    j.putString("destination", destination);
    j.putString("from", from.id());
    j.putString("corridor", "j_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    j.putString("encounter", plan.site());
    j.putString("contact", plan.name());
    j.putLong("seed", plan.seed());
    j.putInt("kind", plan.kind());
    j.putInt("duration", plan.seconds() * 20);
    j.putInt("contactAt", plan.contactSecond() * 20);
    j.putInt("elapsed", 0);
    j.putInt("paid", cost);
    j.putString("state", "CRUISE");
    if (ship.contains("destination"))
      j.put("selection", ship.getCompoundOrEmpty("destination").copy());
    return j;
  }

  public static JourneyPlan preview(MinecraftServer server, CompoundTag ship, String destination) {
    var level = ShipStore.level(server, ship.getStringOr("dimension", ""));
    var from = LocalSpaces.get(server, LocalSpaces.address(ship));
    var to = LocalSpaces.get(server, destination);
    int tier =
        ShipSystems.connected(level, ship).stream()
            .filter(
                p -> level.getBlockState(p).is(ShipBlocks.ENGINE) && ShipSystems.active(ship, p))
            .mapToInt(p -> FuelSystem.tier(level, p))
            .max()
            .orElse(0);
    return JourneyPlan.between(
        server.overworld().getSeed(), from.id(), to.id(), !from.system().equals(to.system()), tier);
  }

  public static void register(MinecraftServer server, CompoundTag j) {
    var from = LocalSpaces.get(server, j.getStringOr("from", ""));
    region(server, j.getStringOr("corridor", ""), from, "FTL journey", j.getLongOr("seed", 1));
    region(
        server,
        j.getStringOr("encounter", ""),
        from,
        j.getStringOr("contact", "Signal"),
        j.getLongOr("seed", 1));
    var sites = ShipStore.get(server).section("encounters");
    String id = j.getStringOr("encounter", "");
    if (!sites.contains(id)) {
      var site = new CompoundTag();
      site.putString("state", "planned");
      site.putInt("kind", j.getIntOr("kind", 0));
      site.putLong("seed", j.getLongOr("seed", 1));
      site.putString("name", j.getStringOr("contact", "Signal"));
      sites.put(id, site);
    }
  }

  public static boolean contact(CompoundTag ship) {
    var j = ship.getCompoundOrEmpty("journey");
    return cruising(ship)
        && !j.getBooleanOr("visited", false)
        && j.getIntOr("elapsed", 0) >= j.getIntOr("contactAt", Integer.MAX_VALUE);
  }

  public static String summary(MinecraftServer server, CompoundTag ship) {
    if (!active(ship)) return "";
    var j = ship.getCompoundOrEmpty("journey");
    String to = LocalSpaces.get(server, j.getStringOr("destination", "")).name();
    int remaining = Math.max(0, (j.getIntOr("duration", 0) - j.getIntOr("elapsed", 0) + 19) / 20);
    return (!cruising(ship)
            ? "Journey paused"
            : "FTL "
                + remaining / 60
                + ":"
                + String.format(java.util.Locale.ROOT, "%02d", remaining % 60))
        + " to "
        + to
        + (contact(ship)
            ? " | Signal: " + j.getStringOr("contact", "") + " (controller: Investigate)"
            : "");
  }

  public static String stopReason(MinecraftServer server, CompoundTag ship) {
    if (!contact(ship)) return "No unvisited signal on this journey";
    var site =
        ShipStore.get(server)
            .section("encounters")
            .getCompoundOrEmpty(ship.getCompoundOrEmpty("journey").getStringOr("encounter", ""));
    var vessel = ShipStore.get(server).repository().find(site.getStringOr("vessel", ""));
    if (vessel == null)
      return !site.getStringOr("state", "").equals("planned")
          ? "This site has already been salvaged"
          : "Signal resolving; try again shortly";
    return StationService.ready(vessel) ? "" : "Signal resolving; try again shortly";
  }

  /** Select the investigated site's berth once; the paid destination stays in journey.selection. */
  public static boolean selectEncounterDock(
      net.minecraft.server.level.ServerPlayer player, CompoundTag ship) {
    if (!active(ship) || cruising(ship)) return false;
    var journey = ship.getCompoundOrEmpty("journey");
    String region = journey.getStringOr("encounter", "");
    if (journey.getBooleanOr("encounterDockSelected", false)
        || !LocalSpaces.address(ship).equals(region)) return false;
    var selected = ShipDestination.selected(ship);
    if (selected != null
        && selected.kind() == ShipDestination.Kind.PORT
        && selected.region().equals(region)) {
      journey.putBoolean("encounterDockSelected", true);
      return true;
    }
    String vessel =
        ShipStore.get(player.level().getServer())
            .section("encounters")
            .getCompoundOrEmpty(region)
            .getStringOr("vessel", "");
    var berth =
        ShipDestination.catalog(player, ship).stream()
            .filter(
                d ->
                    d.kind() == ShipDestination.Kind.PORT
                        && d.region().equals(region)
                        && d.reference().equals(vessel)
                        && !d.state().equalsIgnoreCase("Occupied")
                        && !d.state().equalsIgnoreCase("Unavailable"))
            .findFirst();
    if (berth.isEmpty()) return false;
    ship.put("destination", berth.get().save());
    journey.putBoolean("encounterDockSelected", true);
    return true;
  }

  public static void tick(MinecraftServer server) {
    if (server.getTickCount() % 20 != 0) return;
    EncounterSites.tick(server);
    for (var tag : List.copyOf(ShipStore.get(server).ships())) {
      var ship = (CompoundTag) tag;
      if (!active(ship)
          || !ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
        continue;
      var crew =
          server.getPlayerList().getPlayers().stream()
              .filter(p -> p.isAlive() && SceneService.observer(p) == ship)
              .toList();
      if (crew.isEmpty() || NavigationService.pending(server, ship.getStringOr("id", ""))) continue;
      var j = ship.getCompoundOrEmpty("journey");
      if (cruising(ship)) {
        int elapsed = Math.min(j.getIntOr("duration", 0), j.getIntOr("elapsed", 0) + 20);
        j.putInt("elapsed", elapsed);
        NavigationService.stop(ship);
        if (contact(ship) && !j.getBooleanOr("announced", false)) {
          j.putBoolean("announced", true);
          for (var p : crew)
            p.sendSystemMessage(
                Component.literal(
                    "Signal: "
                        + j.getStringOr("contact", "")
                        + ". Use Investigate at the ship controller, or keep travelling."));
        }
        if (elapsed >= j.getIntOr("duration", 0)) NavigationService.arrive(crew.getFirst(), ship);
        ShipStore.get(server).requestSave();
      }
      if (active(ship))
        for (var p : crew) p.sendSystemMessage(Component.literal(summary(server, ship)), true);
    }
  }

  private JourneyService() {}
}
