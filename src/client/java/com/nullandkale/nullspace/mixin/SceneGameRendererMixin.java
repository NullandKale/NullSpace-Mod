package com.nullandkale.nullspace.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SceneGameRendererMixin {
  @Inject(method = "renderLevel", at = @At("RETURN"))
  private void nullspace$finishMonitors(CallbackInfo ci) {
    com.nullandkale.nullspace.client.scene.compute.NativeVolumeUniforms.end();
    com.nullandkale.nullspace.client.scene.MonitorRenderer.endFrame();
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
    com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.prepare(projection);
    if (!(net.minecraft.client.Minecraft.getInstance().gui.screen()
        instanceof com.nullandkale.nullspace.client.scene.SceneScreen))
      com.nullandkale.nullspace.client.scene.MonitorRenderer.prepare(projection);
    return projection;
  }
}
