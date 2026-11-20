package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/**
 * Real ships launching and landing in view of the player, blinking into a grid in space. Each launch is a real
 * transfer; the launched ship is then placed on its cell of the grid so the whole lattice can be seen at once.
 */
public final class SpaceGridClientGameTest implements FabricClientGameTest
{
    static final int COLUMNS = 4, ROWS = 3, PITCH = 44, AHEAD = 50;

    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        if (!Boolean.getBoolean("nullspace.video.spaceGrid"))
        {
            ClientTestEvidence.passed(getClass());
            return;
        }
        try (var world = context.worldBuilder().create())
        {
            var fixture = world.getServer().computeOnServer(server ->
            {
                try
                {
                    var perf = new PerfWorld(server.overworld());
                    // Random catalog designs, a new set each run; -Dnullspace.video.seed=<n> repeats one.
                    long seed = Long.getLong("nullspace.video.seed", System.nanoTime());
                    System.out.println("SPACEGRID design seed " + seed);
                    perf.designs = new java.util.Random(seed);
                    perf.beacons = true;
                    perf.requestedCount = COLUMNS * ROWS + 1;
                    // Every ship on the same planet, so all of them launch into the one orbit the camera is in.
                    perf.launchedCount = Integer.MAX_VALUE;
                    for (int i = 0; i <= COLUMNS * ROWS; i++)
                    {
                        var ship = perf.build(i);
                        ship.actor = i == 0 ? world.getConnection().getServerPlayer() : FakePlayer.get(server.overworld());
                    }
                    return perf;
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            // The observer ship carries the real player into space.
            move(context, world, fixture, 0);
            ClientWaits.waitFor(context, c -> c.level != null && c.level.dimension().equals(TravelService.SPACE) && c.gui.screen() == null, 6000);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var base = WorldStore.pos(fixture.record(fixture.ships.get(0)), "base");
                // A launched ship arrives rotated. The grid is laid out ahead of an unrotated observer.
                WorldStore.get(server).repository().mutate(new ShipRepository.ShipId(fixture.ships.get(0).id), ShipRepository.Mutation.SIMULATION, candidate ->
                {
                    candidate.putDouble("simYaw", 0);
                    candidate.putDouble("simPitch", 0);
                    candidate.putDouble("simRoll", 0);
                });
                p.setGameMode(GameType.SPECTATOR);
                // Above and behind the ship, looking along the grid, close enough that every ship is well framed against open stars.
                p.teleportTo(server.getLevel(TravelService.SPACE), base.getX() + 16.5, base.getY() + 30, base.getZ() + 36, java.util.Set.of(), 180, 12, true);
            });
            // A wider view, so the whole grid fits close in.
            context.runOnClient(mc -> mc.options.fov().set(85));
            ClientWaits.chunksRendered(world.getConnection());
            context.waitTicks(40);
            context.takeScreenshot("nullspace-space-grid-0-empty");
            // Every frame of the video is one screenshot, two game ticks apart: ten frames per real second.
            // scripts/test/make-video.py joins the frames, so ships blink in and out as they do in the game.
            frames(context, 10);
            for (int shown = 1; shown <= COLUMNS * ROWS; shown++)
            {
                move(context, world, fixture, shown);
                frames(context, 6);
            }
            context.takeScreenshot("nullspace-space-grid-12-ships");
            frames(context, 10);
            // Ships blink out of the grid as they land, leaving gaps.
            for (int landing : new int[]{2, 6, 7, 11})
            {
                move(context, world, fixture, landing);
                frames(context, 8);
            }
            context.takeScreenshot("nullspace-space-grid-gaps-after-landing");
            frames(context, 10);
            for (int launching : new int[]{2, 6})
            {
                move(context, world, fixture, launching);
                frames(context, 8);
            }
            frames(context, 20);
            context.takeScreenshot("nullspace-space-grid-refilled");
        }
        ClientTestEvidence.passed(getClass());
    }

    private static void frames(ClientGameTestContext context, int count)
    {
        for (int i = 0; i < count; i++)
        {
            context.waitTicks(2);
            context.takeScreenshot("nullspace-space-grid-frame");
        }
    }

    /** One real launch or landing. A launch waits for the chunks' saved entities to load, so it is retried. */
    private static void move(ClientGameTestContext context, net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world, PerfWorld fixture, int index)
    {
        for (int attempt = 0; attempt < 200; attempt++)
        {
            String problem = world.getServer().computeOnServer(server ->
            {
                var ship = fixture.ships.get(index);
                String result = fixture.move(ship);
                if (result.isEmpty() && index > 0 && fixture.inSpace(ship))
                    place(fixture, index);
                return result;
            });
            if (problem.isEmpty())
                return;
            if (!problem.contains("still loading"))
                throw new AssertionError("Ship " + index + " could not move: " + problem);
            context.waitTicks(2);
        }
        throw new AssertionError("Ship " + index + " never finished loading");
    }

    /** Puts a launched ship on its cell of the grid in front of the observer (toward negative z), in simulated space. */
    private static void place(PerfWorld fixture, int index)
    {
        var observer = fixture.record(fixture.ships.get(0));
        int column = (index - 1) % COLUMNS, row = (index - 1) / COLUMNS;
        double x = observer.getDoubleOr("simX", 0) + (column - (COLUMNS - 1) / 2.0) * PITCH;
        double y = observer.getDoubleOr("simY", 0) - 12;
        double z = observer.getDoubleOr("simZ", 0) - AHEAD - row * PITCH;
        var ship = fixture.ships.get(index);
        var store = WorldStore.get(fixture.server());
        store.repository().mutate(new ShipRepository.ShipId(ship.id), ShipRepository.Mutation.SIMULATION, candidate ->
        {
            candidate.putDouble("simX", x);
            candidate.putDouble("simY", y);
            candidate.putDouble("simZ", z);
            candidate.putDouble("simYaw", 0);
            candidate.putDouble("simPitch", 0);
            candidate.putDouble("simRoll", 0);
        });
    }
}
