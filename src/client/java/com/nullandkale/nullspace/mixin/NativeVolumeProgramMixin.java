package com.nullandkale.nullspace.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.nullandkale.nullspace.client.scene.compute.NativeVolumeUniforms;
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
