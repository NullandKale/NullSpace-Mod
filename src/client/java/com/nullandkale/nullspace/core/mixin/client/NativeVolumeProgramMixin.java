package com.nullandkale.nullspace.core.mixin.client;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.nullandkale.nullspace.render.compute.NativeVolumeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class NativeVolumeProgramMixin {
  @Inject(method = "_glUseProgram", at = @At("TAIL"))
  private static void nullspace$volumeUniforms(int program, CallbackInfo ci) {
    NativeVolumeUniforms.apply(program);
  }

  @Inject(method = "glDeleteProgram", at = @At("HEAD"))
  private static void nullspace$forgetProgram(int program, CallbackInfo ci) {
    NativeVolumeUniforms.remove(program);
  }
}
