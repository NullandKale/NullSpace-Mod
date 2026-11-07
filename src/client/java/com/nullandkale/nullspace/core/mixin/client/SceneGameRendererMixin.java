package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.render.compute.NativeVolumeUniforms;
import com.nullandkale.nullspace.render.scene.MonitorRenderer;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.ui.screen.SceneScreen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SceneGameRendererMixin {
  @Inject(method = "renderLevel", at = @At("RETURN"))
  private void nullspace$finishMonitors(CallbackInfo ci) {
    NativeVolumeUniforms.end();
    MonitorRenderer.endFrame();
  }

  // Use the actual world projection, after hurt/view bobbing and portal effects.
  // Preparing here also keeps uploads and offscreen passes outside the world render pass.
  @ModifyArg(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
      index = 0)
  private org.joml.Matrix4f nullspace$prepareScene(org.joml.Matrix4f projection) {
    SpaceSceneRenderer.prepare(projection);
    if (!(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof SceneScreen))
      MonitorRenderer.prepare(projection);
    return projection;
  }
}
