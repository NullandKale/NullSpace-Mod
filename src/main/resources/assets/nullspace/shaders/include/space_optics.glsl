#ifndef NULLSPACE_OPTICS
#define NULLSPACE_OPTICS
float spaceOpaqueDistance(sampler2D depth, mat4 inverseView, vec2 uv, bool zeroOne) {
    float z = texture(depth, uv).r;
    if (z <= 0.)
        return 1000000.;
    vec4 p = inverseView * vec4(uv * 2. - 1., zeroOne ? z : z * 2. - 1., 1.);
    return length(p.xyz / p.w);
}
vec4 spacePrefix(sampler2D prefix, vec2 uv, float wanted, int slices) {
    float layer = clamp(log(max(.05, wanted) / .05) / log(1000000. / .05) * float(slices - 1), 0.,
                        float(slices - 1));
    vec4 a = texture(prefix, vec2(uv.x, (uv.y + floor(layer)) / float(slices)));
    vec4 b = texture(prefix, vec2(uv.x, (uv.y + ceil(layer)) / float(slices)));
    return mix(a, b, fract(layer));
}
vec4 spaceOptics(sampler2D prefix, sampler2D depth, mat4 inverseView, vec2 uv, float wanted,
                 int slices, bool zeroOne) {
    vec2 size = vec2(textureSize(prefix, 0)) / vec2(1., float(slices));
    vec2 p = uv * size - .5, base = floor(p), f = fract(p);
    float reference = spaceOpaqueDistance(depth, inverseView, uv, zeroOne);
    vec4 sum = vec4(0);
    float total = 0.;
    for (int y = 0; y < 2; y++)
        for (int x = 0; x < 2; x++) {
            vec2 at = clamp((base + vec2(x, y) + .5) / size, .5 / size, 1. - .5 / size);
            float stop = spaceOpaqueDistance(depth, inverseView, at, zeroOne);
            if (stop + max(.05, wanted * .01) < wanted)
                continue;
            float edge = reference > 999999.
                             ? step(999999., stop)
                             : exp(-abs(stop - reference) / max(.05, reference * .025));
            float weight = (x == 0 ? 1. - f.x : f.x) * (y == 0 ? 1. - f.y : f.y) * edge;
            sum += spacePrefix(prefix, at, wanted, slices) * weight;
            total += weight;
        }
    return total > .00001
               ? sum / total
               : spacePrefix(prefix,
                             clamp((floor(uv * size) + .5) / size, .5 / size, 1. - .5 / size),
                             wanted, slices);
}
#endif
