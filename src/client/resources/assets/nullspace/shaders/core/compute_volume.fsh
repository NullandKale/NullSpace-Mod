#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ComputeView {
    mat4 InvView;
    mat4 ViewProjection;
    vec4 ViewInfo;
    vec4 HistoryInfo;
    mat4 DepthInverse;
    vec4 FadeInfo;
};
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
#include <nullspace:space_optics.glsl>
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 c = texture(Sampler0, texCoord);
    float distance = spaceOpaqueDistance(Sampler2, DepthInverse, texCoord, ViewInfo.z > .5);
    vec4 optical = spaceOptics(Sampler1, Sampler2, DepthInverse, texCoord, distance,
                               int(HistoryInfo.z), ViewInfo.z > .5);
    vec3 linear = pow(c.rgb, vec3(2.2));
    vec3 L = optical.rgb / (1. + optical.rgb);
    vec3 lit = L + linear * optical.a;
    // Far terrain fades into the sky's own colour at the horizon, in place of vanilla's biome-coloured fog. The haze in front of far
    // terrain is a different colour from the sky above the horizon, so the finished pixel is faded, not just the terrain under it.
    // The sky colour is that of the nearest sky pixel straight above on the screen, which is the real, finished sky there. Real sky
    // pixels (no terrain) are left alone.
    if (FadeInfo.y > FadeInfo.x && FadeInfo.y < 1e6 && distance < 999999.) {
        float fade = smoothstep(FadeInfo.x, FadeInfo.y, distance);
        if (fade > 0.) {
            float pixel = 1. / ViewInfo.y;
            for (int i = 1; i <= 64; i++) {
                vec2 above = vec2(texCoord.x, texCoord.y + float(i) * pixel);
                if (above.y >= 1.) break;
                if (spaceOpaqueDistance(Sampler2, DepthInverse, above, ViewInfo.z > .5) > 999999.) {
                    lit = mix(lit, pow(texture(Sampler0, above).rgb, vec3(2.2)), fade);
                    break;
                }
            }
        }
    }
    fragColor = vec4(pow(max(vec3(0), lit), vec3(1. / 2.2)), c.a);
}
