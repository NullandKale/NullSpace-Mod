package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.sound.VanillaHearing;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Each player near an explosion gets its sound as {@link VanillaHearing} says they hear it; the particles and push are unchanged. */
@Mixin(ServerLevel.class)
public abstract class ExplosionSoundMixin
{
    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void nullspace$hearExplosion(ServerGamePacketListenerImpl connection, Packet<?> packet, Operation<Void> original)
    {
        original.call(connection, VanillaHearing.explosion((ServerLevel) (Object) this, connection.player, packet));
    }
}
