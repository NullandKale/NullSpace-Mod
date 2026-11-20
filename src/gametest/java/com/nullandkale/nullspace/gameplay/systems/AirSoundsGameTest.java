package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.TestPads;
import com.nullandkale.nullspace.testing.TestWaits;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** A breached cabin on an airless world hisses at the breach, and once its air is thin the crew's alarm sounds, needing no power. */
public final class AirSoundsGameTest
{
    private static List<ShipSounds.Loop> loops(MinecraftServer server)
    {
        var out = new ArrayList<ShipSounds.Loop>();
        AirSounds.loops(server, out::add);
        return out;
    }

    @GameTest(environment = "nullspace:air_sounds", maxTicks = 2400)
    public void aBreachHissesAndThinAirSoundsTheCrewsAlarm(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var luna = server.getLevel(PlanetSurfaces.dimension(server, "sol_luna"));
        var base = new BlockPos(21000, 150, 21000);
        var helm = ShipTestFixture.build(luna, base);
        TestPads.register(luna, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Air sound test");
        var ship = ShipTopology.resolve(luna, helm);
        SystemsUpgrade.upgrade(luna, ship);
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "air-crew"), false);
        var crew = new ServerPlayer(server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, crew, cookie);
        crew.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        // Alive through the thin air, so the alarm has someone to warn (the alarm skips creative players).
        crew.getAbilities().invulnerable = true;
        crew.onUpdateAbilities();
        crew.teleportTo(luna, base.getX() + 3.5, base.getY() + 3, base.getZ() + 2.5, Set.of(), 0, 0, true);
        var ear = BlockPos.containing(crew.getEyePosition());
        // Once the cabin holds air, open a hole in its wall.
        TestWaits.until(ctx, () -> ShipAir.oxygen(luna, ship, ear) >= 60, () ->
        {
            ctx.assertTrue(loops(server).stream().noneMatch(l -> l.key().startsWith("air.breach:") || l.key().startsWith("alarm.lowair:")), "A sealed cabin full of air is quiet: " + loops(server).stream().map(ShipSounds.Loop::key).toList());
            luna.setBlock(base.offset(6, 4, 2), Blocks.AIR.defaultBlockState(), 3);
            TestWaits.until(ctx, () -> loops(server).stream().anyMatch(l -> l.key().startsWith("air.breach:") && (l.cue().equals("air.hiss") || l.cue().equals("air.leak")) && l.at() != null), () ->
            {
                var breach = loops(server).stream().filter(l -> l.key().startsWith("air.breach:")).findFirst().orElseThrow();
                ctx.assertTrue(breach.at().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(base.offset(6, 4, 2))) < 2, "The hiss comes from the breach: " + breach.at());
                TestWaits.until(ctx, () -> loops(server).stream().anyMatch(l -> l.key().equals("alarm.lowair:" + crew.getUUID()) && l.listeners().contains(crew) && l.at() == null), () ->
                {
                    server.getPlayerList().remove(crew);
                    WorldStore.get(server).ships().remove(ship);
                    ctx.succeed();
                });
            });
        });
    }
}
