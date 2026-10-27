package com.nullandkale.nullspace.client.scene.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL;

/** Small GLSL 430 compute program. All ownership and calls belong to the current render context. */
public final class ComputeProgram implements AutoCloseable {
  private final int handle;
  private final Map<String, Integer> uniforms = new HashMap<>();

  public static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty("nullspace.compute", "true"));
  }

  public static void require() {
    RenderSystem.assertOnRenderThread();
    String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
    if (!backend.equalsIgnoreCase("OpenGL") || !GL.getCapabilities().OpenGL43)
      throw new IllegalStateException(
          "NullSpace compute requires OpenGL 4.3; actual backend "
              + backend
              + ", device "
              + RenderSystem.getDevice().getDeviceInfo().name()
              + ". Select OpenGL and restart, or use -Dnullspace.compute=false for legacy"
              + " rendering.");
    if (glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) < 3
        || glGetInteger(GL_MAX_IMAGE_UNITS) < 8
        || glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS) < 64)
      throw new IllegalStateException("Insufficient compute image/SSBO/workgroup limits");
  }

  public ComputeProgram(String source) {
    require();
    int shader = glCreateShader(GL_COMPUTE_SHADER);
    int program = 0;
    try {
      glShaderSource(shader, source);
      glCompileShader(shader);
      if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
        throw new IllegalArgumentException("Compute compilation: " + glGetShaderInfoLog(shader));
      program = glCreateProgram();
      glAttachShader(program, shader);
      glLinkProgram(program);
      if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
        throw new IllegalArgumentException("Compute link: " + glGetProgramInfoLog(program));
      handle = program;
    } catch (RuntimeException e) {
      if (program != 0) glDeleteProgram(program);
      throw e;
    } finally {
      glDeleteShader(shader);
    }
  }

  public static ComputeProgram load(String name) {
    return new ComputeProgram(source(name + ".comp", new HashSet<>()));
  }

  private static String source(String name, Set<String> stack) {
    if (!name.matches("[a-z0-9_]+\\.(comp|glsl)") || !stack.add(name) || stack.size() > 8)
      throw new IllegalArgumentException("Invalid or cyclic compute include: " + name);
    var id = Identifier.parse("nullspace:shaders/compute/" + name);
    try (var reader =
        Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).openAsReader()) {
      String source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
      var includes =
          java.util.regex.Pattern.compile("(?m)^\\s*#include \\\"([^\\\"]+)\\\"\\s*$")
              .matcher(source);
      var result = new StringBuilder();
      while (includes.find())
        includes.appendReplacement(
            result, java.util.regex.Matcher.quoteReplacement(source(includes.group(1), stack)));
      includes.appendTail(result);
      return result.toString();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read " + id, e);
    } finally {
      stack.remove(name);
    }
  }

  public void use() {
    glUseProgram(handle);
  }

  public int uniform(String name) {
    return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(handle, n));
  }

  public void integer(String name, int value) {
    glUniform1i(uniform(name), value);
  }

  public void scalar(String name, float value) {
    glUniform1f(uniform(name), value);
  }

  public void matrix(String name, org.joml.Matrix4f value) {
    try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
      glUniformMatrix4fv(uniform(name), false, value.get(stack.mallocFloat(16)));
    }
  }

  public void dispatch(int width, int height) {
    dispatch(width, height, 8, 8);
  }

  public void dispatch(int width, int height, int tileX, int tileY) {
    glDispatchCompute((width + tileX - 1) / tileX, (height + tileY - 1) / tileY, 1);
  }

  @Override
  public void close() {
    glDeleteProgram(handle);
  }
}
