#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ComputeView {
    mat4 InvView;
    mat4 ViewProjection;
    vec4 ViewInfo;
    vec4 HistoryInfo;
};
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
vec3 display(vec3 c) {
    return pow(c / (1. + c), vec3(1. / 2.2));
}
void main() {
    vec2 hit = texture(Sampler1, texCoord).xy;
    gl_FragDepth = 0.;
    if (hit.y >= 0.) {
        vec4 p = InvView *
                 vec4((texCoord + vec2(ViewInfo.w, HistoryInfo.w) / ViewInfo.xy) * 2. - 1., 1, 1);
        vec3 ray = normalize(p.xyz / p.w);
        vec4 clip = ViewProjection * vec4(ray * hit.x, 1);
        float ndc = clip.z / clip.w;
        gl_FragDepth = ViewInfo.z > .5 ? ndc : ndc * .5 + .5;
    }
    fragColor = vec4(display(texture(Sampler0, texCoord).rgb), 1);
}
