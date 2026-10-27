package com.nullandkale.nullspace.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nullandkale.nullspace.client.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.client.scene.compute.WorldVolumeBridge;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class ScenePassBoundaryMixin {
  @WrapOperation(
      method = "*",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
  private RenderPass nullspace$mainPass(
      CommandEncoder encoder,
      Supplier<String> label,
      GpuTextureView color,
      Optional<Vector4fc> clear,
      GpuTextureView depth,
      OptionalDouble clearDepth,
      Operation<RenderPass> original) {
    var pass = original.call(encoder, label, color, clear, depth, clearDepth);
    String name = label.get();
    if (SpaceSceneRenderer.computeReady() && (name.equals("Main") || name.equals("Solid")))
      return WorldVolumeBridge.wrap(
          pass,
          () ->
              encoder.createRenderPass(
                  label, color, Optional.empty(), depth, OptionalDouble.empty()));
    return pass;
  }

  @WrapOperation(
      method = "*",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/LevelRenderer;executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
  private void nullspace$opaqueBoundary(
      LevelRenderer renderer,
      ChunkSectionsToRender sections,
      FeatureRenderDispatcher.PreparedFrame features,
      RenderPass pass,
      Operation<Void> original) {
    original.call(renderer, sections, features, pass);
    WorldVolumeBridge.afterOpaque(pass);
  }
}
