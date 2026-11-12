package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;

import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Current trip state, independent of the last button's response or an old flight message. */
public record TripPresentation(Phase phase, String title, String hint)
{
    public enum Phase
    {
        READY, PREPARING, SYNCHRONIZING, CRUISING, SIGNAL, ARRIVAL_READY, APPROACH, CAPTURING, CAPTURED, PAUSED, DEPARTING, COMPLETE;

        public boolean automatic()
        {
            return Set.of(PREPARING, SYNCHRONIZING, CRUISING, SIGNAL, APPROACH, CAPTURING, DEPARTING).contains(this);
        }
    }

    public static final TripPresentation EMPTY = new TripPresentation(Phase.READY, "", "");

    static TripPresentation current(ServerPlayer player, CompoundTag ship)
    {
        var server = player.level().getServer();
        String id = ship.getStringOr("id", "");
        var transition = NavigationService.state(server).jumps.get(id);
        if (transition != null)
            return new TripPresentation(Phase.SYNCHRONIZING, switch (transition.mode)
            {
                case START -> "Entering FTL";
                case RESUME -> "Resuming FTL";
                case INVESTIGATE -> "Leaving FTL for the signal";
                case ARRIVE -> "Arriving in orbit";
            }, "Synchronizing the space view for everyone aboard. Please wait.");
        if (JourneyService.cruising(ship))
        {
            var journey = ship.getCompoundOrEmpty("journey");
            if (journey.getIntOr("elapsed", 0) >= journey.getIntOr("duration", 0))
                return new TripPresentation(Phase.ARRIVAL_READY, "Arrival ready", NavigationService.crewAboard(player.level(), ship) ? "Waiting for the current transfer to finish. Arrival retries automatically." : "Return everyone to safe cabins. Arrival continues automatically.");
            int seconds = Math.max(0, (journey.getIntOr("duration", 0) - journey.getIntOr("elapsed", 0) + 19) / 20);
            boolean signal = JourneyService.contact(ship);
            return new TripPresentation(signal ? Phase.SIGNAL : Phase.CRUISING, "FTL: " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) + " remaining", signal ? JourneyService.stopReason(server, ship).isEmpty() ? "Optional signal available. Ignore it to keep travelling." : "Signal resolving. Keep travelling or wait for Visit signal." : "Stay aboard; you can move around. Arrival is automatic.");
        }
        var dock = ship.getCompoundOrEmpty("dock");
        String dockState = dock.getStringOr("state", "");
        if (dockState.equals("CLAMPED"))
            return BuildJournal.pending(server, id) || TransferLocks.inTransit(server, ship) || CourseService.active(ship) ? new TripPresentation(Phase.CAPTURING, "Securing the docking connection", "Wait for the collar to be saved before boarding or continuing.") : new TripPresentation(Phase.CAPTURED, "Docked", JourneyService.active(ship) ? "Open the dock door to visit. Continue journey departs automatically." : "Open the connected dock door to board; return through the same port.");
        if (Set.of("UNDOCKING", "EMERGENCY_BACKOFF").contains(dockState))
            return new TripPresentation(Phase.DEPARTING, "Leaving the dock", "Backing away safely. Stay aboard until departure finishes.");
        if (!dockState.isEmpty())
            return new TripPresentation(Phase.APPROACH, dockState.equals("WAITING_CONSENT") ? "Waiting for docking permission" : "Approaching the docking port", "Automatic alignment and capture are in progress.");
        var course = ship.getCompoundOrEmpty("course");
        String state = course.getStringOr("state", "");
        if (state.equals("STOPPING"))
            return new TripPresentation(Phase.DEPARTING, "Stopping safely", CourseService.summary(ship));
        if (CourseService.active(ship))
            return new TripPresentation(Phase.PREPARING, switch (course.getStringOr("step", ""))
            {
                case "LAUNCH" -> "Preparing launch";
                case "LAND" -> "Preparing landing";
                case "JUMP", "RESUME" -> "Preparing FTL";
                case "DOCK" -> "Preparing docking";
                default -> "Preparing the trip";
            }, CourseService.summary(ship));
        if (JourneyService.active(ship))
            return new TripPresentation(Phase.PAUSED, "Journey paused", state.equals("PAUSED") ? CourseService.summary(ship) : "Continue journey resumes the paid trip; it does not charge jump fuel again.");
        if (state.equals("PAUSED"))
            return new TripPresentation(Phase.PAUSED, "Trip paused", CourseService.summary(ship));
        var selected = ShipDestination.selected(ship);
        var completed = ShipDestination.read(course.getCompoundOrEmpty("destination"));
        if (state.equals("ARRIVED") && selected != null && completed != null && selected.id().equals(completed.id()))
            return new TripPresentation(Phase.COMPLETE, "Arrived", CourseService.summary(ship));
        if (TransferLocks.inTransit(server, ship))
            return new TripPresentation(Phase.PREPARING, "Preparing the ship", "Wait for the current transfer or construction to finish.");
        return new TripPresentation(Phase.READY, "Ready to travel", selected == null ? "Choose a destination, then Engage." : "Engage follows the selected trip. Manual steps remain available.");
    }
}
