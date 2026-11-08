package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.computer.ScriptLanguage;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.SpaceBvh;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;

/** Flying a ship by hand or by script: helm leases, pilot input, thrust and automation. */
public final class Piloting
{
    public record Input(UUID session, int sequence, int buttons, boolean close) implements CustomPacketPayload
    {

        public static final Type<Input> TYPE = new Type<>(Identifier.parse("nullspace:pilot_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeVarInt(p.sequence);
            b.writeVarInt(p.buttons);
            b.writeBoolean(p.close);
        }, b -> new Input(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readBoolean()));

        @Override
        public Type<Input> type()
        {
            return TYPE;
        }
    }

    record Automation(String computer, BlockPos controller, int buttons, int until)
    {}

    static CompoundTag helm(ServerPlayer p, BlockPos at)
    {
        if (!p.isAlive() || !p.level().dimension().equals(TravelService.SPACE) || !p.level().hasChunkAt(at) || !ModBlocks.navigation(p.level().getBlockState(at)) || p.distanceToSqr(Vec3.atCenterOf(at)) > 64 || !p.mayInteract(p.level(), at))
            throw new IllegalArgumentException("Use the helm aboard your ship in space");
        var s = WorldStore.get(p.level().getServer()).at(p.level(), at);
        if (s == null)
            throw new IllegalArgumentException("Helm is not aboard a registered ship");
        return s;
    }

    public static boolean input(ServerPlayer p, Input input)
    {
        var l = NavigationService.state(p.level().getServer()).leases.get(p.getUUID());
        if (l == null || !l.token.equals(input.session) || !NavigationService.valid(p, l))
            return false;
        if (input.close)
        {
            NavigationService.close(p.level().getServer(), p.getUUID());
            if (ShipControl.open(p, l.helm, "Returned to ship controls") == null)
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal("View closed: ship controls are no longer reachable"));
            return true;
        }
        if (input.sequence < 0 || input.sequence <= l.sequence || (input.buttons & ~FlightDynamics.ALL) != 0 || !l.pilot && input.buttons != 0)
            return false;
        l.sequence = input.sequence;
        l.buttons = input.buttons;
        l.expires = p.level().getServer().getTickCount() + 80;
        return true;
    }

    public static void stop(CompoundTag s)
    {
        s.putInt("thrustDemand", 0);
        s.putDouble("simVX", 0);
        s.putDouble("simVY", 0);
        s.putDouble("simVZ", 0);
    }

    static FlightDynamics.State flight(CompoundTag s)
    {
        var pose = SceneService.pose(s);
        return new FlightDynamics.State(pose.toUniverse(new Vec3(16, 16, 16)), new Vec3(s.getDoubleOr("simVX", 0), s.getDoubleOr("simVY", 0), s.getDoubleOr("simVZ", 0)), s.getDoubleOr("simYaw", 0), s.getDoubleOr("simPitch", 0), s.getDoubleOr("simRoll", 0));
    }

    static void apply(CompoundTag s, FlightDynamics.State next)
    {
        var origin = next.center().subtract(FlightDynamics.rotate(FlightDynamics.orientation(next.yaw(), next.pitch(), next.roll()), new Vec3(16, 16, 16)));
        s.putDouble("simX", origin.x);
        s.putDouble("simY", origin.y);
        s.putDouble("simZ", origin.z);
        s.putDouble("simYaw", next.yaw());
        s.putDouble("simPitch", next.pitch());
        s.putDouble("simRoll", next.roll());
        s.putDouble("simVX", next.velocity().x);
        s.putDouble("simVY", next.velocity().y);
        s.putDouble("simVZ", next.velocity().z);
    }

    static boolean blocked(MinecraftServer server, CompoundTag own, FlightDynamics.State old, FlightDynamics.State next)
    {
        if (next.center().length() > FlightDynamics.LOCAL_RADIUS)
            return true;
        var delta = next.center().subtract(old.center());
        double distance = delta.length();
        if (distance < 1e-8)
            return false;
        var direction = delta.scale(1 / distance);
        double radius = 28;
        var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, own));
        double time = server.overworld().getGameTime() / 20.0 - definition.epoch();
        for (var body : definition.bodies())
        {
            var center = body.at(time);
            double bound = body.radius() * (1 + body.roughness()) + radius;
            if (old.center().distanceToSqr(center) < bound * bound)
            {
                if (next.center().distanceToSqr(center) < old.center().distanceToSqr(center))
                    return true;
            }
            else if (SpaceBvh.sphere(old.center(), direction, center, bound) <= distance)
                return true;
        }
        for (var tag : WorldStore.get(server).ships())
        {
            var other = (CompoundTag) tag;
            if (other == own || !other.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) || !LocalSpaces.together(own, other))
                continue;
            var center = SceneService.pose(other).toUniverse(new Vec3(other.getIntOr("width", 32) / 2., 16, other.getIntOr("depth", 32) / 2.));
            double combined = radius + StationSpace.radius(other);
            if (old.center().distanceToSqr(center) < combined * combined)
            {
                if (next.center().distanceToSqr(center) < old.center().distanceToSqr(center))
                    return true;
            }
            else if (SpaceBvh.sphere(old.center(), direction, center, combined) <= distance)
                return true;
        }
        return false;
    }

    public static String automate(ServerLevel level, CompoundTag ship, BlockPos controller, String computer, int buttons, int ticks)
    {
        if (JourneyService.cruising(ship))
            throw new IllegalArgumentException("FTL is steering the ship");
        if (DockingService.motionLocked(ship))
            throw new IllegalArgumentException("Docking or station anchoring owns movement");
        if (!level.dimension().equals(TravelService.SPACE))
            throw new IllegalArgumentException("Local thrust operates in space");
        if ((buttons & ~FlightDynamics.ALL) != 0 || ticks < 1 || ticks > 200)
            throw new IllegalArgumentException("Use valid flight controls for 1..200 ticks");
        if (TransferLocks.locked(level, controller) || NavigationService.state(level.getServer()).pilots.containsKey(ship.getStringOr("id", "")))
            throw new IllegalArgumentException("A crew pilot or transit currently owns flight control");
        if (!ShipTopology.loaded(level, ship))
            throw new ScriptLanguage.Unavailable("Ship chunks are not fully loaded");
        if (!ShipSystems.connected(level, ship).contains(controller) || !ShipSystems.active(ship, controller) || NavigationService.propulsion(level, ship) == 0)
            throw new IllegalArgumentException("Computer and propulsion must be connected, enabled and powered");
        String id = ship.getStringOr("id", "");
        var old = NavigationService.state(level.getServer()).computers.get(id);
        if (old != null && !old.computer.equals(computer))
            throw new IllegalArgumentException("Another computer owns flight control");
        NavigationService.state(level.getServer()).computers.put(id, new Automation(computer, controller.immutable(), buttons, level.getServer().getTickCount() + ticks));
        return "Flight command accepted";
    }

    public static void navigation(ServerPlayer p, BlockPos at)
    {
        helm(p, at);
        ShipControl.open(p, at, "Choose a destination, then Engage. Departure, travel and arrival are automatic.");
    }

    private Piloting()
    {
    }
}
