package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingThrusters;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.start.CrashDamage;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;

import java.util.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Landing flies first. Like docking, the ship turns its bow to the planet and flies close, accelerating and braking, while the planet grows in the
 * windows; only then does the landing transfer move the hull to the pad. The approach burns fuel each moving tick, as automatic docking does.
 * <p>
 * With no fuel to brake there is an emergency landing: the ship falls the same way without braking and crash-lands on the pad, and the crash breaks
 * machines and tears the hull as a crash start does ({@link CrashDamage}, which always leaves the bridge breathing). A ship whose fuel runs out on
 * the way falls the rest of the way. The approach is saved on the ship, so a restart resumes it.
 */
public final class PlanetApproach
{
    static final String KEY = "planetApproach", CRASH = "crashLanding";
    /** Acceleration, top speed and emergency top speed, in blocks per tick; the turn rate in degrees per tick. */
    static final double ACCELERATION = .6, TOP_SPEED = 36, FALL_SPEED = 44, TURN = 4;
    /** The ship starts flying once its bow is within this many degrees of the planet, and finishes the turn on the way. */
    static final double ROUGHLY_FACING = 30;
    /** Fuel each moving tick, before engine upgrades (as {@link FuelSystem#travelCost}). */
    static final int FUEL_PER_TICK = 3;
    /** Ticks assumed for an approach whose distance is not known yet (from another region or the surface). */
    static final int TYPICAL_TICKS = 180;

    public static boolean active(CompoundTag ship)
    {
        return ship.contains(KEY);
    }

    public static boolean emergency(CompoundTag ship)
    {
        return ship.getCompoundOrEmpty(KEY).getBooleanOr("emergency", false);
    }

    /** Whether any ship is approaching a planet, so scene frames go out at the flight rate. */
    public static boolean moving(MinecraftServer server)
    {
        return WorldStore.get(server).ships().stream().anyMatch(t -> ((CompoundTag) t).contains(KEY));
    }

    /** The planet of the ship's region as the scene draws it, or null where there is none to fly to (a nebula, a corridor). */
    static SpaceDefinition.Body planet(MinecraftServer server, CompoundTag ship)
    {
        var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, ship));
        // A generated orbit names its planet by the region; the home scene (Terra) by a stable id made from the planet's name (SceneCatalog).
        String address = LocalSpaces.address(ship), id = "nullspace:" + address;
        String home = UUID.nameUUIDFromBytes(("nullspace:" + LocalSpaces.get(server, address).name()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return definition.bodies().stream().filter(b -> b.id().equals(id) || b.id().equals(home)).findFirst().orElse(null);
    }

    static Vec3 at(MinecraftServer server, CompoundTag ship, SpaceDefinition.Body body)
    {
        var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, ship));
        return body.at(server.overworld().getGameTime() / 20. - definition.epoch());
    }

    static Vec3 center(CompoundTag ship)
    {
        return SceneService.pose(ship).toUniverse(new Vec3(ship.getIntOr("width", 32) / 2., ship.getIntOr("height", 32) / 2., ship.getIntOr("depth", 32) / 2.));
    }

    /** How high above the surface the approach ends: close enough that the planet fills the view. */
    static double standOff(SpaceDefinition.Body body)
    {
        return Math.max(120, body.radius() * .08);
    }

    /** The distance the ship still has to fly, or 0 when it is there or there is no planet. */
    public static double distance(MinecraftServer server, CompoundTag ship)
    {
        var body = planet(server, ship);
        if (body == null)
            return 0;
        var c = at(server, ship, body);
        double surface = body.radius() * (1 + body.roughness());
        return Math.max(0, center(ship).distanceTo(c) - surface - standOff(body));
    }

    /** Ticks of thrust to cover a distance: accelerate, cruise, brake. */
    static int ticks(double distance)
    {
        double ramp = TOP_SPEED * TOP_SPEED / ACCELERATION;
        return (int) Math.ceil(distance >= ramp ? 2 * TOP_SPEED / ACCELERATION + (distance - ramp) / TOP_SPEED : 2 * Math.sqrt(distance / ACCELERATION));
    }

    /** Fuel the approach to a pad will burn: exact when the ship is already over the planet, typical otherwise. */
    public static int fuel(ServerLevel level, CompoundTag ship, ShipDestination target)
    {
        if (target == null || target.kind() != ShipDestination.Kind.PAD)
            return 0;
        boolean over = level.dimension().equals(TravelService.SPACE) && LocalSpaces.address(ship).equals(target.region());
        return (over ? ticks(distance(level.getServer(), ship)) : TYPICAL_TICKS) * FuelSystem.travelCost(level, ship, FUEL_PER_TICK);
    }

    /** Starts flying to a pad on the planet below. Landing starts at once when there is no planet body to fly to or the ship is already close. */
    public static String begin(ServerPlayer player, CompoundTag ship, ShipDestination pad, boolean emergency)
    {
        var server = player.level().getServer();
        if (!player.level().dimension().equals(TravelService.SPACE))
            throw new IllegalArgumentException("Launch to space before landing");
        if (JourneyService.cruising(ship))
            throw new IllegalArgumentException("Finish the FTL journey before landing");
        if (ship.contains("dock"))
            throw new IllegalArgumentException("Undock before landing");
        if (pad == null || pad.kind() != ShipDestination.Kind.PAD || !pad.region().equals(LocalSpaces.address(ship)))
            throw new IllegalArgumentException("Choose a landing pad on this planet");
        if (active(ship))
            throw new IllegalArgumentException("Already approaching " + LocalSpaces.get(server, LocalSpaces.address(ship)).name());
        if (!emergency && NavigationService.propulsion(player.level(), ship) == 0)
            throw new IllegalArgumentException("Load fuel and power controls and an engine");
        var approach = new CompoundTag();
        approach.putString("actor", player.getUUID().toString());
        approach.putString("pad", pad.reference());
        approach.putString("padName", pad.name());
        approach.putBoolean("emergency", emergency);
        approach.putDouble("speed", 0);
        ship.put(KEY, approach);
        Piloting.stop(ship);
        NavigationService.closeShip(server, ship.getStringOr("id", ""));
        WorldStore.get(server).requestSave();
        String planet = LocalSpaces.get(server, LocalSpaces.address(ship)).name();
        tell(server, ship, emergency ? "Emergency landing: no fuel to brake. Brace for a crash landing on " + pad.name() + "." : "Approaching " + planet + " to land at " + pad.name() + ".");
        return emergency ? "Emergency landing started" : "Approaching " + planet;
    }

    /** Stops an approach that can still stop (an emergency landing cannot: there is nothing to brake with). */
    public static boolean cancel(MinecraftServer server, CompoundTag ship)
    {
        if (!active(ship) || emergency(ship))
            return false;
        ship.remove(KEY);
        DockingThrusters.stop(server, ship);
        WorldStore.get(server).requestSave();
        tell(server, ship, "Approach stopped; holding position.");
        return true;
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.NAVIGATION, server ->
        {
            for (var entry : List.copyOf(WorldStore.get(server).ships()))
            {
                var ship = (CompoundTag) entry;
                try
                {
                    if (ship.contains(KEY))
                        step(server, ship);
                    if (ship.getBooleanOr(CRASH, false))
                        afterCrash(server, ship);
                }
                catch (RuntimeException error)
                {
                    NullSpace.LOGGER.error("Planet approach failed for {}", ship.getStringOr("id", ""), error);
                    ship.remove(KEY);
                    ship.remove(CRASH);
                    tell(server, ship, "Approach stopped after an unexpected error; see the server log.");
                }
            }
        });
    }

    private static void step(MinecraftServer server, CompoundTag ship)
    {
        var approach = ship.getCompoundOrEmpty(KEY);
        var level = server.getLevel(TravelService.SPACE);
        if (level == null || !ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) || TransferLocks.busy(server, ship))
            return;
        var actor = server.getPlayerList().getPlayer(UUID.fromString(approach.getStringOr("actor", "")));
        boolean emergency = approach.getBooleanOr("emergency", false);
        if (actor == null || SceneService.observer(actor) != ship)
        {
            if (!emergency)
            {
                ship.remove(KEY);
                DockingThrusters.stop(server, ship);
                tell(server, ship, "Approach stopped: the pilot left the ship. Engage again aboard.");
                return;
            }
            // A falling ship keeps falling; it lands for whoever is aboard when it gets there.
        }
        var body = planet(server, ship);
        if (body == null)
        {
            land(server, ship, actor);
            return;
        }
        if (!emergency && !FuelSystem.spend(level, ship, FuelSystem.travelCost(level, ship, FUEL_PER_TICK)))
        {
            approach.putBoolean("emergency", true);
            emergency = true;
            tell(server, ship, "Out of fuel on the approach: the ship is falling. Brace for a crash landing.");
        }
        var old = SceneService.pose(ship);
        var planet = at(server, ship, body);
        var center = center(ship);
        var toPlanet = planet.subtract(center).normalize();
        // Turn the bow to the planet first, about the hull's centre, so the windows look at where the ship is going.
        var half = new Vec3(ship.getIntOr("width", 32) / 2., ship.getIntOr("height", 32) / 2., ship.getIntOr("depth", 32) / 2.);
        var bowLocal = DockingMotion.normal(ShipHeading.forward(level, ship));
        var bow = FlightDynamics.rotate(old.orientation(), bowLocal);
        var goal = new Quaterniond().rotationTo(new Vector3d(bow.x, bow.y, bow.z), new Vector3d(toPlanet.x, toPlanet.y, toPlanet.z)).mul(old.orientation());
        var turned = DockingMotion.step(old, new ScenePose(old.position(), goal), 0, Math.toRadians(TURN)).orientation();
        boolean facing = Math.abs(turned.dot(goal)) > Math.cos(Math.toRadians(ROUGHLY_FACING) / 2);
        double remaining = Math.max(0, center.distanceTo(planet) - body.radius() * (1 + body.roughness()) - standOff(body));
        double speed = approach.getDoubleOr("speed", 0);
        if (facing || emergency)
        {
            speed = Math.min(speed + ACCELERATION, emergency ? FALL_SPEED : TOP_SPEED);
            if (!emergency)
                speed = Math.min(speed, Math.max(ACCELERATION, Math.sqrt(2 * ACCELERATION * remaining)));
        }
        double move = Math.min(speed, remaining);
        var nextCenter = center.add(toPlanet.scale(move));
        // A ship already inside another's clearance may still leave it, as a docking overlap may shrink: only obstacles it is outside of can block.
        var obstacles = others(server, ship).stream().filter(o -> center.distanceTo(o.center()) > o.radius() + 29).toList();
        if (move > 0 && !DockingMotion.clear(center, nextCenter, obstacles, 29))
        {
            // Another ship is in the way: hold, as docking does, until it moves.
            speed = 0;
            nextCenter = center;
        }
        approach.putDouble("speed", speed);
        var next = new ScenePose(nextCenter.subtract(FlightDynamics.rotate(turned, half)), turned);
        pose(ship, next);
        if (!emergency)
            DockingThrusters.fire(server, level, ship, old, next);
        if (remaining - move <= .5)
            land(server, ship, actor);
    }

    /** Other ships here, as the docking route sees them; the planet itself is where the approach ends, so it is not an obstacle. */
    private static List<DockingMotion.Obstacle> others(MinecraftServer server, CompoundTag own)
    {
        var out = new ArrayList<DockingMotion.Obstacle>();
        for (var tag : WorldStore.get(server).ships())
        {
            var s = (CompoundTag) tag;
            if (s != own && s.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) && LocalSpaces.together(own, s))
                out.add(new DockingMotion.Obstacle(center(s), com.nullandkale.nullspace.gameplay.station.StationSpace.radius(s)));
        }
        return out;
    }

    /** At the stand-off: hand over to the landing transfer, free and unpowered for a crash landing. */
    private static void land(MinecraftServer server, CompoundTag ship, ServerPlayer actor)
    {
        var approach = ship.getCompoundOrEmpty(KEY);
        boolean emergency = approach.getBooleanOr("emergency", false);
        String pad = approach.getStringOr("pad", "");
        ship.remove(KEY);
        DockingThrusters.stop(server, ship);
        WorldStore.get(server).requestSave();
        var pilot = actor != null ? actor : aboard(server, ship);
        if (pilot == null)
        {
            tell(server, ship, "Holding above the planet: nobody is aboard to land.");
            return;
        }
        if (emergency)
            ship.putBoolean(CRASH, true);
        try
        {
            var controller = WorldStore.pos(ship, "controller");
            TravelService.request(pilot, controller, "id:" + pad, ShipService.preflight(pilot.level(), controller));
        }
        catch (IllegalArgumentException refused)
        {
            ship.remove(CRASH);
            tell(server, ship, "Landing refused: " + refused.getMessage());
        }
    }

    private static ServerPlayer aboard(MinecraftServer server, CompoundTag ship)
    {
        return server.getPlayerList().getPlayers().stream().filter(p -> SceneService.observer(p) == ship).findFirst().orElse(null);
    }

    /** After a crash landing has moved the hull to the pad, the crash: broken machines and a torn hull, once. */
    private static void afterCrash(MinecraftServer server, CompoundTag ship)
    {
        if (TransferLocks.busy(server, ship) || active(ship))
            return;
        if (ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
        {
            // The landing did not happen (refused or rolled back): there is no crash to apply.
            ship.remove(CRASH);
            return;
        }
        var level = WorldStore.level(server, ship.getStringOr("dimension", ""));
        if (level == null)
            return;
        ship.remove(CRASH);
        var report = CrashDamage.apply(level, ship, server.overworld().getSeed() ^ level.getGameTime(), !PlanetSurfaces.breathable(level));
        WorldStore.get(server).requestSave();
        NullSpace.LOGGER.info("Crash landing: ship={} {}", ship.getStringOr("id", ""), report);
        tell(server, ship, "Crash landing. Engines, generators and air supplies are broken and the hull is torn; the bridge still has air. Use a broken machine to see what to craft.");
    }

    /** Saves a new scene pose, as docking does. */
    static void pose(CompoundTag ship, ScenePose p)
    {
        var angles = p.orientation().getEulerAnglesYXZ(new Vector3d());
        ship.putDouble("simX", p.position().x);
        ship.putDouble("simY", p.position().y);
        ship.putDouble("simZ", p.position().z);
        ship.putDouble("simYaw", Math.toDegrees(angles.y));
        ship.putDouble("simPitch", Math.toDegrees(angles.x));
        ship.putDouble("simRoll", Math.toDegrees(angles.z));
    }

    private static void tell(MinecraftServer server, CompoundTag ship, String message)
    {
        ship.getCompoundOrEmpty(KEY).putString("message", message);
        for (var p : server.getPlayerList().getPlayers())
            if (SceneService.observer(p) == ship)
                p.sendSystemMessage(Component.literal(message));
    }

    /** What the approach says now, for the trip panel. */
    public static String message(CompoundTag ship)
    {
        return ship.getCompoundOrEmpty(KEY).getStringOr("message", "");
    }

    /** The pad a pad reference names, for messages. */
    static String padName(MinecraftServer server, String reference)
    {
        return PadRegistry.read(server).stream().filter(p -> p.id().equals(reference)).map(PadRegistry.Pad::name).findFirst().orElse("the pad");
    }

    private PlanetApproach()
    {
    }
}
