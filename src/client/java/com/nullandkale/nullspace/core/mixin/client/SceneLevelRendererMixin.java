package com.nullandkale.nullspace.core.mixin.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.nullandkale.nullspace.render.effects.PreviewRenderer;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class SceneLevelRendererMixin {
  @Inject(method = "render", at = @At("HEAD"))
  private void nullspace$hideStorageNeighbors(CallbackInfo ci) {
    var renderer = (LevelRenderer) (Object) this;
    PreviewRenderer.draw(renderer);
    java.util.function.Predicate<
            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection>
        hidden =
            section -> {
              var p = net.minecraft.core.SectionPos.of(section.getSectionNode()).origin();
              return !SpaceSceneRenderer.physicalVisible(
                  new net.minecraft.world.phys.AABB(
                      p.getX(), p.getY(), p.getZ(), p.getX() + 16, p.getY() + 16, p.getZ() + 16));
            };
    renderer.visibleSections().removeIf(hidden);
    renderer.nearbyVisibleSections().removeIf(hidden);
  }

  @Inject(method = "executeSolid", at = @At("TAIL"))
  private void nullspace$composeScene(
      ChunkSectionsToRender sections,
      FeatureRenderDispatcher.PreparedFrame frame,
      RenderPass pass,
      CallbackInfo ci) {
    SpaceSceneRenderer.compose(pass);
  }
}
