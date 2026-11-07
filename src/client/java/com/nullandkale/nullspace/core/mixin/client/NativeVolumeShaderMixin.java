package com.nullandkale.nullspace.core.mixin.client;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.*;
import com.nullandkale.nullspace.model.optics.NativeVolumeShader;
import com.nullandkale.nullspace.render.compute.ComputeProgram;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlPipelineRecompiler.class)
public abstract class NativeVolumeShaderMixin {
  private static final ThreadLocal<String> nullspace$pipeline = ThreadLocal.withInitial(() -> "");

  @Inject(method = "compileProgram", at = @At("HEAD"))
  private void nullspace$pipelineLabel(
      BackendRenderPipeline.CreateInfo info,
      Map<?, ?> sources,
      CallbackInfoReturnable<GlProgram> ci) {
    nullspace$pipeline.set(info.name());
  }

  @ModifyVariable(method = "compileShader", at = @At("HEAD"), argsOnly = true, ordinal = 1)
  private String nullspace$volumeFragment(
      String source, String name, ShaderType type, String ignored) {
    return ComputeProgram.enabled() && type == ShaderType.FRAGMENT
        ? NativeVolumeShader.patch(source, name, nullspace$pipeline.get())
        : source;
  }
}
