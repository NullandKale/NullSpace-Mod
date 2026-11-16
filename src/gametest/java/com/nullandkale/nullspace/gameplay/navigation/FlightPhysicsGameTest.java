package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

/** A real pilot lease on a real ship: momentum is kept, assist brakes over time and burns fuel, and without assist nothing damps the ship. */
public final class FlightPhysicsGameTest
{
    private static double speed(CompoundTag ship)
    {
        return Math.sqrt(Math.pow(ship.getDoubleOr("simVX", 0), 2) + Math.pow(ship.getDoubleOr("simVY", 0), 2) + Math.pow(ship.getDoubleOr("simVZ", 0), 2));
    }

    @GameTest(environment = "nullspace:flight_physics", maxTicks = 160)
    public void aPilotedShipKeepsItsMomentumAssistBrakesItOverTimeAndWithoutAssistItDrifts(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var anchor = ctx.absolutePos(new BlockPos(29000, 96, 29000));
        var base = new BlockPos(anchor.getX(), 96, anchor.getZ());
        var helm = ShipTestFixture.build(level, base);
        var ship = new CompoundTag();
        String id = UUID.randomUUID().toString();
        ship.putString("id", id);
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", helm.asLong());
        ship.putDouble("simX", -2500);
        ship.putDouble("simY", 0);
        ship.putDouble("simZ", -2500);
        WorldStore.get(server).ships().add(ship);
        var p = ctx.makeMockServerPlayerInLevel();
        p.teleportTo(level, base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, Set.of(), 0, 0, true);
        var lease = NavigationService.open(p, helm, true);
        int[] sequence = {1};
        ctx.assertTrue(Piloting.input(p, new Piloting.Input(lease, sequence[0]++, FlightDynamics.FORWARD, false)), "The pilot's thrust input is accepted");
        ctx.runAfterDelay(30, () ->
        {
            try
            {
                var flying = WorldStore.get(server).repository().find(id);
                double cruise = speed(flying);
                ctx.assertTrue(cruise > 0, "Thrust gives the ship speed; status \"" + flying.getStringOr("flightStatus", "") + "\", mass " + flying.getIntOr("simMass", -1) + ", demand " + flying.getIntOr("thrustDemand", -1));
                ctx.assertTrue(flying.getIntOr("simMass", 0) >= 150, "The ship's hull mass is counted and saved");
                ctx.assertTrue(flying.getIntOr("thrustDemand", 0) > 0, "Pushing shows as thrust, so the exhaust follows it");
                Piloting.input(p, new Piloting.Input(lease, sequence[0]++, 0, false));
                ctx.runAfterDelay(3, () ->
                {
                    var coasting = WorldStore.get(server).repository().find(id);
                    double now = speed(coasting);
                    ctx.assertTrue(now > 0 && now < cruise, "Letting go does not stop the ship at once, and assist begins to brake it (" + now + " from " + cruise + ")");
                    ctx.assertTrue(coasting.getIntOr("thrustDemand", 0) > 0, "Braking burns the engines");
                    Piloting.input(p, new Piloting.Input(lease, sequence[0]++, FlightDynamics.ASSIST_OFF, false));
                    ctx.runAfterDelay(2, () ->
                    {
                        double before = speed(WorldStore.get(server).repository().find(id));
                        ctx.runAfterDelay(12, () ->
                        {
                            try
                            {
                                var drifting = WorldStore.get(server).repository().find(id);
                                ctx.assertTrue(before > 0 && Math.abs(speed(drifting) - before) < 1e-6, "With assist off and no keys nothing slows the ship: " + before + " then " + speed(drifting));
                                ctx.assertTrue(drifting.getIntOr("thrustDemand", 0) == 0, "No thrust, no exhaust");
                                ctx.succeed();
                            }
                            finally
                            {
                                NavigationService.closeShip(server, id);
                                server.getPlayerList().remove(p);
                                WorldStore.get(server).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
                            }
                        });
                    });
                });
            }
            catch (RuntimeException failure)
            {
                NavigationService.closeShip(server, id);
                server.getPlayerList().remove(p);
                WorldStore.get(server).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
                throw failure;
            }
        });
    }

    /** A ship nobody flies, made of nothing: no engines and no fuel, so no power. It must keep drifting at the speed it had. */
    @GameTest(environment = "nullspace:flight_physics", maxTicks = 80)
    public void anUnpilotedShipWithNoPowerDriftsOnAtItsSpeedAndMovesThroughSpace(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var anchor = ctx.absolutePos(new BlockPos(31000, 96, 31000));
        var ship = new CompoundTag();
        String id = UUID.randomUUID().toString();
        ship.putString("id", id);
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", new BlockPos(anchor.getX(), 96, anchor.getZ()).asLong());
        ship.putLong("controller", new BlockPos(anchor.getX(), 96, anchor.getZ()).asLong());
        ship.putDouble("simX", 4000);
        ship.putDouble("simY", 0);
        ship.putDouble("simZ", 4000);
        ship.putDouble("simVX", 6);
        ship.putDouble("simSpinYaw", 20);
        WorldStore.get(server).ships().add(ship);
        ctx.runAfterDelay(30, () ->
        {
            try
            {
                var drifting = WorldStore.get(server).repository().find(id);
                ctx.assertTrue(Math.abs(drifting.getDoubleOr("simVX", 0) - 6) < 1e-6, "With no engine power nothing slows it: " + drifting.getDoubleOr("simVX", 0));
                ctx.assertTrue(Math.abs(drifting.getDoubleOr("simSpinYaw", 0) - 20) < 1e-6, "Nor its spin");
                ctx.assertTrue(drifting.getDoubleOr("simX", 4000) > 4000 + 6 * .05 * 20, "It has moved through space: " + drifting.getDoubleOr("simX", 0));
                ctx.assertTrue(drifting.getStringOr("flightStatus", "").startsWith("DRIFTING"), "The ship says it is drifting: " + drifting.getStringOr("flightStatus", ""));
                ctx.succeed();
            }
            finally
            {
                WorldStore.get(server).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
            }
        });
    }

    /** A real ship with engines and fuel, left moving with nobody at the helm: the computer brakes it under assist. */
    @GameTest(environment = "nullspace:flight_physics", maxTicks = 400)
    public void anUnpilotedShipWithPowerBrakesItselfToRestOverSeconds(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var anchor = ctx.absolutePos(new BlockPos(33000, 96, 33000));
        var base = new BlockPos(anchor.getX(), 96, anchor.getZ());
        var helm = ShipTestFixture.build(level, base);
        var ship = new CompoundTag();
        String id = UUID.randomUUID().toString();
        ship.putString("id", id);
        ship.putString("dimension", TravelService.SPACE.identifier().toString());
        ship.putLong("base", base.above().asLong());
        ship.putLong("controller", helm.asLong());
        ship.putDouble("simX", -5000);
        ship.putDouble("simY", 0);
        ship.putDouble("simZ", 5000);
        ship.putDouble("simVX", 3);
        ship.putDouble("simSpinYaw", 15);
        WorldStore.get(server).ships().add(ship);
        double[] first = {-1};
        ctx.runAfterDelay(4, () -> first[0] = speed(WorldStore.get(server).repository().find(id)));
        ctx.runAfterDelay(380, () ->
        {
            try
            {
                var stopped = WorldStore.get(server).repository().find(id);
                ctx.assertTrue(first[0] > 0 && first[0] < 3, "Braking begins at once but does not stop the ship in a tick: " + first[0]);
                ctx.assertTrue(speed(stopped) == 0 && stopped.getDoubleOr("simSpinYaw", 1) == 0, "With power and fuel the computer brakes it to rest: speed " + speed(stopped) + ", spin " + stopped.getDoubleOr("simSpinYaw", 1));
                ctx.assertTrue(stopped.getDoubleOr("simX", -5000) > -5000, "It travelled while it braked");
                ctx.succeed();
            }
            finally
            {
                WorldStore.get(server).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
            }
        });
    }
}
