#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ComputeView {
    mat4 InvView;
    mat4 ViewProjection;
    vec4 ViewInfo;
    vec4 HistoryInfo;
    mat4 DepthInverse;
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
    fragColor = vec4(pow(max(vec3(0), L + linear * optical.a), vec3(1. / 2.2)), c.a);
}
