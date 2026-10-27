package com.nullandkale.nullspace.scene;

/** OpenGL-only post-SPIR-V adapter. Vanilla descriptors and source assets stay intact. */
public final class NativeVolumeShader {
  private static java.util.function.Supplier<String> optics = NativeVolumeShader::readOptics;

  public static void resetSource() {
    optics = NativeVolumeShader::readOptics;
  }

  public static void source(java.util.function.Supplier<String> source) {
    optics = java.util.Objects.requireNonNull(source);
  }

  private static String readOptics() {
    try (var in =
        NativeVolumeShader.class.getResourceAsStream(
            "/assets/nullspace/shaders/include/space_optics.glsl")) {
      if (in == null) throw new IllegalStateException("Missing shared optical reconstruction");
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String patch(String source, String name, String pipeline) {
    if (!(name.contains("terrain") || name.contains("entity"))
        || !source.matches("(?s).*\\bout\\s+vec4\\s+_frag_output_00\\s*;.*")
        || source.contains("nullspace_original_main")) return source;
    if (!source.matches("(?s).*\\bvoid\\s+main\\s*\\(\\s*\\).*")) return source;
    boolean premultiplied =
        pipeline.toLowerCase(java.util.Locale.ROOT).contains("accumul")
            || source.contains("sampleColorForAccumulation");
    String original =
        source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*\\)", "void nullspace_original_main()");
    return original
        + "\n"
        + optics.get()
        + "\n"
        + """
        uniform sampler2D NullSpaceVolume;
        uniform sampler2D NullSpaceDepth;
        uniform vec4 NullSpaceVolumeInfo;
        uniform mat4 NullSpaceInverseView;
        uniform float NullSpaceVolumeEnabled;
        void main(){
          nullspace_original_main();
          if(NullSpaceVolumeEnabled>.5){
            vec2 uv=gl_FragCoord.xy/NullSpaceVolumeInfo.xy;
            vec4 p=NullSpaceInverseView*vec4(uv*2.-1.,NullSpaceVolumeInfo.z>.5?gl_FragCoord.z:gl_FragCoord.z*2.-1.,1.);
            float distance=length(p.xyz/p.w);
            vec4 optical=spaceOptics(NullSpaceVolume,NullSpaceDepth,NullSpaceInverseView,uv,distance,int(NullSpaceVolumeInfo.w),NullSpaceVolumeInfo.z>.5);
            float weight=NS_PREMULTIPLIED?_frag_output_00.a:1.;
            if(weight>.00001){vec3 linear=pow(max(vec3(0),_frag_output_00.rgb/weight),vec3(2.2));_frag_output_00.rgb=pow(max(vec3(0),optical.rgb/(1.+optical.rgb)+linear*optical.a),vec3(1./2.2))*weight;}
            else _frag_output_00.rgb*=optical.a;
          }
        }
        """
            .replace("NS_PREMULTIPLIED", premultiplied ? "true" : "false");
  }

  private NativeVolumeShader() {}
}
