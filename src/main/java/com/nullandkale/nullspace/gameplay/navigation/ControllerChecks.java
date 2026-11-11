package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl.Action;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Why a controller action is blocked right now, in words the player can act on. */
public final class ControllerChecks
{
    /** Without set-up systems there is no air aboard, so the ship stays on the ground. */
    public static final String NO_SYSTEMS = "Ship systems are not set up, so there is no air aboard. Connect a Generator and an Air" + " Supply to the controller, then choose Upgrade systems.";

    /** Cheap state checks for display; the operation still performs its complete preflight. */
    public static String reason(ServerPlayer p, CompoundTag ship, ShipDestination target, Action action)
    {
        boolean space = p.level().dimension().equals(TravelService.SPACE);
        var dock = ship.getCompoundOrEmpty("dock");
        String phase = dock.getStringOr("state", "");
        boolean incoming = !dock.getStringOr("source", "").equals(ship.getStringOr("id", ""));
        if ((action == Action.ENGAGE || action == Action.LAUNCH) && !ShipSystems.enabled(ship) && !p.level().dimension().equals(TravelService.SPACE))
            return NO_SYSTEMS;
        if (action == Action.ENGAGE)
            return CourseService.reason(p, ship, target);
        if (action == Action.DISENGAGE)
            return CourseService.active(ship) ? "" : "No engaged course";
        if (action == Action.MARK || action == Action.BROWSE_SCANNER || action == Action.RETURN_TO_CONTROLLER)
            return "Open the Scanner block to browse and set a destination";
        if (CourseService.active(ship) && Set.of(Action.SELECT, Action.LAUNCH, Action.JUMP, Action.LAND, Action.DOCK, Action.UNDOCK, Action.PILOT).contains(action))
            return "Disengage the current course first";
        if (action == Action.SHOW_PROBLEM || action == Action.ADVANCED || action == Action.REFRESH || action == Action.CLOSE || action == Action.INSPECT)
            return "";
        if (action == Action.REVIEW_LOOSE_BLOCKS || action == Action.CONFIRM_LEAVE_BLOCKS)
        {
            if (StationAccess.station(ship))
                return "Stations are anchored";
            if (JourneyService.cruising(ship))
                return "Finish the FTL journey first";
            return DockingService.motionLocked(ship) ? "Undock or cancel the docking maneuver first" : "";
        }
        if (action == Action.UNDOCK)
            return !phase.equals("CLAMPED") || incoming ? (phase.equals("CLAMPED") ? "Use the visiting ship's controller" : "Not docked") : NavigationService.propulsion(p.level(), ship) > 0 ? "" : "Your ship needs controls, engine power and fuel to undock";
        if (action == Action.CANCEL)
            return phase.isEmpty() || phase.equals("CLAMPED") || incoming ? "No outgoing maneuver to cancel" : "";
        if (action == Action.ACCEPT || action == Action.DECLINE)
            return phase.equals("WAITING_CONSENT") && incoming ? "" : "No incoming docking request";
        if (action == Action.SELECT)
            return JourneyService.cruising(ship) ? "Journey underway: investigate or arrive before changing destination" : "";
        if (action == Action.CLOSE_DOORS || action == Action.AIRLOCK)
            return "";
        if (action == Action.WAYPOINT || action == Action.SHOWCASE)
            return space ? "" : "Launch into space first";
        if (action == Action.CAMERA)
            return space ? "" : "Launch into space first";
        if (StationAccess.station(ship))
            return "Stations are anchored";
        if (action == Action.RESUME)
            return CourseService.resumeReason(p, ship);
        if (DockingService.motionLocked(ship))
            return "Undock or cancel the docking maneuver first";
        if (ShipStorage.active(p))
            return "Finish or cancel your recall first";
        if (TransferLocks.busy(p.level().getServer(), ship))
            return "This ship is already travelling or boarding";
        if (action == Action.INVESTIGATE)
            return JourneyService.stopReason(p.level().getServer(), ship);
        if (JourneyService.cruising(ship))
            return "FTL underway: investigate a signal or wait for arrival";
        if (action == Action.JUMP && JourneyService.active(ship))
            return "Use Continue journey in Trip to resume your paid trip";
        if (action == Action.LAUNCH)
            return space ? "Already in space" : fuelReason(p, ship, FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
        if (!space)
            return "Launch into space first";
        if (action == Action.DEPLOY)
            return "In space, hold a Station Core and Shift-right-click empty space or a non-interactive" + " block";
        if (action == Action.PILOT || action == Action.CAMERA)
            return "";
        if (target == null)
            return "Choose a destination";
        if (action == Action.JUMP)
            return jumpReason(p, ship, target);
        if (action == Action.LAND && target.kind() != ShipDestination.Kind.PAD)
            return "Choose a landing pad as the destination";
        if (action == Action.DOCK && target.kind() != ShipDestination.Kind.PORT)
            return "Choose a docking port as the destination";
        if (!LocalSpaces.address(ship).equals(target.region()))
            return (JourneyService.active(ship) ? "Resume the journey to " : "Jump to ") + LocalSpaces.get(p.level().getServer(), target.region()).name() + " first";
        if ((action == Action.LAND || action == Action.DOCK) && (target.state().equalsIgnoreCase("OCCUPIED") || target.state().equalsIgnoreCase("UNAVAILABLE")))
            return "Destination " + (action == Action.DOCK ? "port" : "pad") + " is " + target.state().toLowerCase(Locale.ROOT);
        if (action == Action.DOCK)
        {
            var readiness = DockingManeuver.readiness(p, ship);
            return readiness.ready() ? "" : readiness.message();
        }
        if (action == Action.LAND)
            return fuelReason(p, ship, FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
        return "";
    }

    /** Why a jump to this destination cannot start, or empty. The first reason found wins. */
    private static String jumpReason(ServerPlayer p, CompoundTag ship, ShipDestination target)
    {
        String scanner = CourseService.scannerReason(p, ship, target.region());
        if (!scanner.isEmpty())
            return scanner;
        if (JourneyService.special(p.level().getServer(), target.region()))
            return "Reach this signal using Investigate during a journey";
        if (LocalSpaces.address(ship).equals(target.region()))
            return "Already in the destination's region";
        String range = StarSystems.rangeReason(p.level(), ship, target.region());
        if (!range.isEmpty())
            return range;
        return fuelReason(p, ship, StarSystems.jumpBase(p.level().getServer(), LocalSpaces.address(ship), target.region()));
    }

    static String fuelReason(ServerPlayer p, CompoundTag ship, int base)
    {
        int cost = FuelSystem.travelCost(p.level(), ship, base);
        return FuelSystem.available(p.level(), ship) >= cost ? "" : "Fuel " + FuelSystem.display(FuelSystem.available(p.level(), ship)) + "/" + FuelSystem.display(cost) + ": load furnace fuel in a generator";
    }

    private ControllerChecks()
    {
    }
}
