package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.Button;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.ChartPlace;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.Entry;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.View;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl.Action;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl.Session;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds the Ship Controller's view: location, destination catalog, star chart and trip preview.
 */
public final class ControllerScreen
{

    static View setupView(Session s, boolean opening)
    {
        var buttons = Arrays.stream(Action.values()).filter(a -> a != Action.EXPLAIN).map(a -> new Button(a, a == Action.INSPECT || a == Action.SHOW_PROBLEM || a == Action.CLOSE ? "" : s.setupProblem)).toList();
        return new View(s.id, s.position, s.scanner, opening, "Ship setup", "Power: unknown | Air here: unknown", "", "Not selected", "", s.message, List.of(), buttons, s.response);
    }

    static ShipDestination selected(ServerPlayer p, Session s, CompoundTag ship)
    {
        var saved = CourseService.departureTarget(p.level().getServer(), ship, ShipDestination.selected(ship));
        if (saved == null)
            return s.catalog.stream().filter(d -> d.kind() == ShipDestination.Kind.REGION && d.region().equals(ship.getCompoundOrEmpty("journey").getStringOr("destination", ""))).findFirst().orElse(null);
        return s.catalog.stream().filter(d -> d.id().equals(saved.id())).findFirst().orElse(new ShipDestination(saved.kind(), saved.reference(), saved.port(), saved.region(), saved.name(), "UNAVAILABLE"));
    }

    static List<ShipDestination> catalog(ServerPlayer p, Session s, CompoundTag ship)
    {
        if (s.scanner && (!ShipSystems.connected(p.level(), ship).contains(s.position) || !ShipSystems.active(ship, s.position) || !ShipSensors.installed(p.level(), ship, false)))
        {
            s.message = "Scanner needs a hull connection, On, and sensor power from a generator.";
            return List.of();
        }
        return ShipDestination.navigationCatalog(p, ship);
    }

    static void refreshCatalog(ServerPlayer p, Session session, CompoundTag ship)
    {
        session.catalog = catalog(p, session, ship);
    }

    /** What the scanner screen offers. */
    private static final List<Action> SCANNER_ACTIONS = List.of(Action.SELECT, Action.REFRESH, Action.ADVANCED);

    /** What the ship screen offers, in the order of its buttons. */
    private static final List<Action> SHIP_ACTIONS = shipActions();

    private static List<Action> shipActions()
    {
        var actions = new ArrayList<Action>();
        actions.add(Action.SELECT);
        actions.add(Action.LAUNCH);
        actions.add(Action.JUMP);
        actions.add(Action.LAND);
        actions.add(Action.DOCK);
        actions.add(Action.UNDOCK);
        actions.add(Action.PILOT);
        actions.add(Action.CAMERA);
        actions.add(Action.CLOSE_DOORS);
        actions.add(Action.AIRLOCK);
        actions.add(Action.ADVANCED);
        actions.add(Action.REFRESH);
        actions.add(Action.INSPECT);
        actions.add(Action.REVIEW_LOOSE_BLOCKS);
        actions.add(Action.SHOW_PROBLEM);
        actions.add(Action.ACCEPT);
        actions.add(Action.DECLINE);
        actions.add(Action.CANCEL);
        return List.copyOf(actions);
    }

    static View view(ServerPlayer p, Session s, CompoundTag ship, boolean opening)
    {
        var target = selected(p, s, ship);
        var actions = s.scanner ? SCANNER_ACTIONS : SHIP_ACTIONS;
        var visibleActions = new ArrayList<>(actions);
        if (!s.scanner)
        {
            visibleActions.addAll(List.of(Action.ENGAGE, Action.DISENGAGE));
        }
        if (!s.scanner && s.looseReview != null)
            visibleActions.add(Action.CONFIRM_LEAVE_BLOCKS);
        if (!s.scanner && JourneyService.active(ship))
            visibleActions.add(JourneyService.cruising(ship) ? Action.INVESTIGATE : Action.RESUME);
        var buttons = visibleActions.stream().map(a -> new Button(a, limit(ControllerChecks.reason(p, ship, target, a), 256))).toList();
        String system = LocalSpaces.get(p.level().getServer(), LocalSpaces.address(ship)).system();
        var listed = new ArrayList<Entry>();
        for (var d : s.catalog)
        {
            if (listed.size() >= 512)
                break;
            if (!s.scanner)
            {
                boolean local = (d.kind() == ShipDestination.Kind.REGION || d.kind() == ShipDestination.Kind.PAD) && LocalSpaces.get(p.level().getServer(), d.region()).system().equals(system);
                boolean chosen = target != null && d.id().equals(target.id());
                if (!local && !chosen)
                    continue;
            }
            listed.add(new Entry(d.id(), d.kind(), limit(d.name(), 160), d.region(), limit(d.state(), 32)));
        }
        var entries = List.copyOf(listed);
        String location = target == null ? "No destination selected" : LocalSpaces.get(p.level().getServer(), target.region()).systemName() + " / " + LocalSpaces.get(p.level().getServer(), target.region()).name() + " | " + switch (target.kind())
        {
            case REGION -> "Planet/moon";
            case PAD -> "Landing pad";
            case PORT -> "Docking port";
            case VESSEL -> "Station/ship";
        } + " | " + target.state();
        String summary;
        if (ShipSystems.enabled(ship))
        {
            String fuel = FuelSystem.available(p.level(), ship) > 0 ? "loaded" : "empty";
            String controls = ShipSystems.power(p.level(), ship, PowerBus.System.CONTROLS) > 0 ? "online" : "no controls power";
            String engines = NavigationService.propulsion(p.level(), ship) > 0 ? "ready" : "unavailable";
            String air = ShipAir.breathable(p.level(), ship, BlockPos.containing(p.getEyePosition())) ? "safe here" : "unsafe here";
            summary = "Fuel: " + fuel + " | Controls: " + controls + " | Engines: " + engines + " | Air here: " + air;
        }
        else
            summary = "Setup incomplete: connect a generator and an Air Supply";
        int transferCost = FuelSystem.travelCost(p.level(), ship, FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
        String costs = "Fuel costs: Launch/Land " + FuelSystem.display(transferCost);
        if (JourneyService.active(ship))
            costs += " | Jump already paid; Resume has no jump fee";
        else if (target != null)
            costs += " | Jump " + FuelSystem.display(FuelSystem.travelCost(p.level(), ship, StarSystems.jumpBase(p.level().getServer(), LocalSpaces.address(ship), target.region())));
        costs += " | Dock: burns while moving";
        if (target != null && !JourneyService.active(ship) && !LocalSpaces.address(ship).equals(target.region()) && !JourneyService.special(p.level().getServer(), target.region()))
            costs += "\nJourney: " + JourneyService.preview(p.level().getServer(), ship, target.region()).seconds() + " seconds; optional signals appear en route";
        var power = ShipSystems.powerStats(p.level(), ship);
        String resources = "Power " + power[0] + " / " + power[1] + " demand | Battery " + power[3] + " | Charging " + power[2] + "\nBow: " + ShipHeading.forward(p.level(), ship).getName() + " (opposite main engine exhaust)";
        var trip = TripPresentation.current(p, ship);
        trip = new TripPresentation(trip.phase(), limit(trip.title(), 160), limit(trip.hint(), 256));
        String flight = s.message;
        String course = CourseService.active(ship) ? CourseService.summary(ship) : connection(p, ship);
        String minimumFuel = "\nMinimum fuel: " + FuelSystem.display(CourseService.minimumFuel(p, ship, target)) + " + docking reserve";
        String fuelAboard = "\nFuel aboard: " + FuelSystem.display(FuelSystem.available(p.level(), ship));
        String plan = CourseService.preview(p, ship, target) + minimumFuel + fuelAboard + "\n" + costs + "\n" + resources;
        String name = limit(ship.getStringOr("home", "Ship"), 160);
        String destinationId = target == null ? "" : target.id();
        String destinationName = target == null ? "Choose destination" : limit(target.name(), 160);
        return new View(s.id, s.position, s.scanner, opening, name, summary, destinationId, destinationName, limit(location, 160), limit(flight, 2048), entries, buttons, s.response, locationHere(p, ship), course, plan, s.scanner ? chart(p, ship) : List.of(), s.scanner ? StarSystems.range(p.level(), ship) : 0, trip);
    }

    /** The star map from this ship's place: every system and feature, with jump fuel and range. */
    static List<ChartPlace> chart(ServerPlayer p, CompoundTag ship)
    {
        var server = p.level().getServer();
        var galaxy = StarSystems.galaxy(server);
        String address = LocalSpaces.address(ship);
        GalaxyPlan.Place here = StarSystems.placeOf(server, address);
        double range = StarSystems.range(p.level(), ship);
        var out = new ArrayList<ChartPlace>();
        for (var place : galaxy.places())
        {
            double ly = GalaxyPlan.distance(here, place);
            String kind, zone, summary;
            if (place instanceof GalaxyPlan.StarSystem s)
            {
                kind = "System";
                var planets = s.planets();
                long landable = s.bodies().stream().filter(GalaxyPlan.Body::landable).count();
                long moons = s.bodies().size() - planets.size();
                zone = s.bodies().stream().filter(b -> b.id().equals(address)).findFirst().orElse(planets.getFirst()).id();
                summary = s.spectralClass() + " star | " + planets.size() + " planets, " + moons + " moons, " + landable + " landable";
            }
            else
            {
                var f = (GalaxyPlan.Feature) place;
                kind = f.type().label;
                zone = f.id();
                summary = f.type().label + (f.steppingStone() ? " | stepping stone" : "");
            }
            boolean inRange = ly <= range + 1e-9;
            double fuel = place == here ? 0 : GalaxyPlan.jumpFuel(ly);
            String route = "";
            if (!inRange)
            {
                // Too far for one jump: the cheapest hop route with these engines, and its total fuel.
                var hops = GalaxyPlan.route(galaxy, here.id(), place.id(), range);
                fuel = 0;
                for (int i = 1; i < hops.size(); i++)
                    fuel += GalaxyPlan.jumpFuel(GalaxyPlan.distance(hops.get(i - 1), hops.get(i)));
                route = hops.size() < 2 ? "no route" : (hops.size() - 1) + " hops via " + String.join(", ", hops.subList(1, hops.size() - 1).stream().map(GalaxyPlan.Place::name).toList());
            }
            out.add(new ChartPlace(place.id(), limit(place.name(), 64), kind, (int) Math.round(place.x() / GalaxyPlan.SIZE * 1000), (int) Math.round(place.y() / GalaxyPlan.SIZE * 1000), zone, ly, fuel, inRange, place == here, limit(summary, 160), limit(route, 160)));
        }
        return List.copyOf(out);
    }

    static String locationHere(ServerPlayer p, CompoundTag ship)
    {
        String id = p.level().dimension().equals(TravelService.SPACE) ? LocalSpaces.address(ship) : PlanetSurfaces.address(p.level());
        if (id == null)
            return limit(p.level().dimension().identifier() + " / Surface", 160);
        var place = LocalSpaces.get(p.level().getServer(), id);
        return limit(place.systemName() + " / " + place.name() + (p.level().dimension().equals(TravelService.SPACE) ? " / Orbit" : " / Surface"), 160);
    }

    static String connection(ServerPlayer p, CompoundTag ship)
    {
        if (JourneyService.active(ship))
            return limit(JourneyService.summary(p.level().getServer(), ship), 160);
        var dock = ship.getCompoundOrEmpty("dock");
        String phase = dock.getStringOr("state", "");
        if (phase.isBlank())
            return "";
        if (!phase.equals("CLAMPED"))
            return "Docking: " + phase.replace('_', ' ').toLowerCase(Locale.ROOT);
        String other = dock.getStringOr("source", "").equals(ship.getStringOr("id", "")) ? dock.getStringOr("target", "") : dock.getStringOr("source", "");
        var partner = WorldStore.get(p.level().getServer()).repository().find(other);
        return limit("Docked: " + (partner == null ? "connected vessel" : partner.getStringOr("home", "Vessel")) + ". Open the dock door to walk through.", 160);
    }

    static String limit(String text, int length)
    {
        return text.substring(0, Math.min(length, text.length()));
    }

    private ControllerScreen()
    {
    }
}
