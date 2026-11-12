package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.core.save.WorldStore;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Saves the ship store at the end of every world save: autosave, save-all, shutdown and the forced saves of transfers.
 * The store is then written at the same moment as the chunks, so after a crash both come from the same moment.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin
{
    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void nullspace$saveStoreWithWorld(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> result)
    {
        WorldStore.worldSaved((MinecraftServer) (Object) this, flush, force);
    }
}
