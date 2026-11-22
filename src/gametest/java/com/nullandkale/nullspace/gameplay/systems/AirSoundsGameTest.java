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

/**
 * A breached cabin on an airless world hisses at the breach. A suited crew member breathing from the suit hears only the quiet suit chime; once the suit is off
 * and their air runs out, the low-air alarm sounds.
 */
public final class AirSoundsGameTest
{
    private static List<ShipSounds.Loop> loops(MinecraftServer server)
    {
        var out = new ArrayList<ShipSounds.Loop>();
        AirSounds.loops(server, out::add);
        return out;
    }

    private static void suit(ServerPlayer player, boolean on)
    {
        var parts = new net.minecraft.world.item.Item[]{com.nullandkale.nullspace.content.ModBlocks.HELMET, com.nullandkale.nullspace.content.ModBlocks.CHEST, com.nullandkale.nullspace.content.ModBlocks.LEGS, com.nullandkale.nullspace.content.ModBlocks.BOOTS};
        var slots = new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
        for (int i = 0; i < parts.length; i++)
            player.setItemSlot(slots[i], on ? new net.minecraft.world.item.ItemStack(parts[i]) : net.minecraft.world.item.ItemStack.EMPTY);
    }

    @GameTest(environment = "nullspace:air_sounds", maxTicks = 2400)
    public void aBreachHissesSuitAirChimesAndOnlySuffocatingSoundsTheAlarm(GameTestHelper ctx) throws Exception
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
            ctx.assertTrue(loops(server).stream().noneMatch(l -> l.key().startsWith("air.breach:") || l.key().startsWith("alarm.") || l.key().startsWith("air.suit:")), "A sealed cabin full of air is quiet: " + loops(server).stream().map(ShipSounds.Loop::key).toList());
            // A suit with a canister of air: whatever the ship's air line does, the suit keeps them breathing.
            suit(crew, true);
            crew.getInventory().add(new net.minecraft.world.item.ItemStack(com.nullandkale.nullspace.content.ModBlocks.AIR_CANISTER));
            luna.setBlock(base.offset(6, 4, 2), Blocks.AIR.defaultBlockState(), 3);
            TestWaits.until(ctx, () -> loops(server).stream().anyMatch(l -> l.key().startsWith("air.breach:") && (l.cue().equals("air.hiss") || l.cue().equals("air.leak")) && l.at() != null), () ->
            {
                ctx.assertTrue(loops(server).stream().noneMatch(l -> l.key().startsWith("alarm.")), "A breach sounds no alarm of its own: " + loops(server).stream().map(ShipSounds.Loop::key).toList());
                var breach = loops(server).stream().filter(l -> l.key().startsWith("air.breach:")).findFirst().orElseThrow();
                ctx.assertTrue(breach.at().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(base.offset(6, 4, 2))) < 2, "The hiss comes from the breach: " + breach.at());
                // Once the room air is too thin to breathe, the suited crew member breathes from the suit and hears its quiet chime, alone.
                TestWaits.until(ctx, () -> loops(server).stream().anyMatch(l -> l.key().equals("air.suit:" + crew.getUUID()) && l.listeners().equals(List.of(crew)) && l.at() == null), () ->
                {
                    ctx.assertTrue(loops(server).stream().noneMatch(l -> l.key().startsWith("alarm.lowair:")), "Suit air is not an alarm: " + loops(server).stream().map(ShipSounds.Loop::key).toList());
                    // Without the suit their air runs out: only then does the alarm sound, in place of the chime.
                    suit(crew, false);
                    TestWaits.until(ctx, () -> loops(server).stream().anyMatch(l -> l.key().equals("alarm.lowair:" + crew.getUUID()) && l.listeners().equals(List.of(crew)) && l.at() == null), () ->
                    {
                        ctx.assertTrue(com.nullandkale.nullspace.gameplay.crew.LifeSupport.oxygen(crew).orElse(100) < AirSounds.DYING, "The alarm waits until the player is suffocating: " + com.nullandkale.nullspace.gameplay.crew.LifeSupport.oxygen(crew));
                        ctx.assertTrue(loops(server).stream().noneMatch(l -> l.key().equals("air.suit:" + crew.getUUID())), "Without a suit there is no suit chime");
                        server.getPlayerList().remove(crew);
                        WorldStore.get(server).ships().remove(ship);
                        ctx.succeed();
                    });
                });
            });
        });
    }
}
