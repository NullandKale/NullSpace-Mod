package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.sound.VanillaHearing;

import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's sounds, broadcast around the place they happen, reach each player as {@link VanillaHearing} says they hear them. */
@Mixin(PlayerList.class)
public abstract class VanillaSoundMixin
{
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void nullspace$hear(Player except, double x, double y, double z, double radius, ResourceKey<Level> dimension, Packet<?> packet, CallbackInfo ci)
    {
        if (!VanillaHearing.applies(packet))
            return;
        var self = (PlayerList) (Object) this;
        if (VanillaHearing.broadcast(self.getPlayers(), self.getServer().getLevel(dimension), except, x, y, z, radius, packet))
            ci.cancel();
    }
}
