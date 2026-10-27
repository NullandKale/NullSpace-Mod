#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform Projection {
    mat4 DepthTransform;
};
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    float d = texture(Sampler1, texCoord).r;
    if (d <= 0.0)
        gl_FragDepth = 0.0;
    else {
        float ndc = DepthTransform[1].x > 0.5 ? d : d * 2.0 - 1.0;
        float z = -DepthTransform[0].y / (ndc + DepthTransform[0].x);
        float target = -DepthTransform[0].z - DepthTransform[0].w / z;
        gl_FragDepth = clamp(DepthTransform[1].x > 0.5 ? target : target * 0.5 + 0.5, 0.0, 1.0);
    }
    fragColor = texture(Sampler0, texCoord);
}
