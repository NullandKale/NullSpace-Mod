package com.nullandkale.nullspace.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Runs before the hidden utility context, including every subsequent compatible window. */
@Mixin(GlBackend.class)
public abstract class GlContextRequestMixin {
  @ModifyArgs(
      method = "createWindow",
      at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z"))
  private void nullspace$computeContext(Args args) {
    if (!Boolean.parseBoolean(System.getProperty("nullspace.compute", "true"))) return;
    int attribute = args.get(0);
    if (attribute == SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION) args.set(1, 4);
    if (attribute == SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION) args.set(1, 3);
  }
}
