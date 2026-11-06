// Original implementation of exponential-density transport and Hillaire-style LUT factorization.
// All lengths and inverse-length coefficients use the scene units in AtmosphereProfile.
uniform int HasAtmosphere, CloudSteps, CloudsEnabled;
uniform vec3 AtmoCenter, AtmoRayleigh, AtmoAbsorption;
uniform float AtmoRadius, AtmoHeight, AtmoMie, AtmoRayleighHeight, AtmoMieHeight;
uniform float AtmoAlbedo, AtmoClouds, CloudTime;
uniform float AtmoDistanceScale;
uniform vec3 CloudOffset;
uniform mat4 CloudRotation;
uniform sampler2D TransmittanceLut, MultipleScatteringLut, SkyRadianceLut, SkyTransmissionLut;
const float PI = 3.14159265359;

vec4 lutRead(sampler2D image, vec2 uv) {
    ivec2 size = textureSize(image, 0);
    vec2 p = clamp(uv, 0., 1.) * vec2(size - 1);
    ivec2 a = ivec2(floor(p)), b = min(a + 1, size - 1);
    vec2 f = fract(p);
    return mix(mix(texelFetch(image, a, 0), texelFetch(image, ivec2(b.x, a.y), 0), f.x),
               mix(texelFetch(image, ivec2(a.x, b.y), 0), texelFetch(image, b, 0), f.x), f.y);
}
vec3 atmoDensities(float h) {
    return vec3(exp(-max(h, 0.) / AtmoRayleighHeight), exp(-max(h, 0.) / AtmoMieHeight),
                max(0., 1. - abs(h - AtmoHeight * .25) / (AtmoHeight * .15)));
}
vec3 atmoExtinction(float h) {
    vec3 rho = atmoDensities(h);
    return AtmoRayleigh * rho.x + vec3(AtmoMie / .9 * rho.y) + AtmoAbsorption * rho.z;
}
vec2 atmoSphere(vec3 p, vec3 d, float radius) {
    float b = dot(p, d), c = dot(p, p) - radius * radius;
    float discriminant = b * b - c;
    if (discriminant < 0.) return vec2(FAR, -FAR);
    float root = sqrt(max(discriminant, 0.));
    return vec2(-b - root, -b + root);
}
bool atmoGround(vec3 p, vec3 d) {
    if (length(p) < AtmoRadius - .0001) return true;
    vec2 g = atmoSphere(p, d, AtmoRadius);
    return g.x > .0001 && g.y > g.x;
}
vec2 atmoTransUv(float radius, float mu) {
    float horizon = -sqrt(max(0., 1. - AtmoRadius * AtmoRadius / (radius * radius)));
    return vec2(sqrt(clamp((mu - horizon) / (1. - horizon), 0., 1.)),
                sqrt(clamp((radius - AtmoRadius) / AtmoHeight, 0., 1.)));
}
vec3 atmoTrans(vec3 p, vec3 d) {
    float radius = max(length(p), AtmoRadius + .0001);
    if (atmoGround(p, d)) return vec3(0);
    return lutRead(TransmittanceLut, atmoTransUv(radius, dot(p, d) / radius)).rgb;
}
vec3 atmoSunDirection(vec3 p) {
    if (LightCount == 0) return vec3(0, -1, 0);
    vec3 delta = BodyData[Lights[0] * 5].xyz - (p + AtmoCenter);
    return length(delta) > .0001 ? normalize(delta) : vec3(0, 1, 0);
}
vec3 atmoSunEnergy() {
    // Catalog colors author the visible stellar disk, not a calibrated illuminant spectrum.
    // Adapt the incident white point so Sol's orange sprite does not turn noon skies sepia.
    return LightCount > 0 ? mix(vec3(1), pow(BodyData[Lights[0] * 5 + 1].rgb, vec3(2.2)), .15) *
        (20. * BodyData[Lights[0] * 5 + 1].w / 12.) : vec3(0);
}
float rayleighPhase(float mu) { return 3. * (1. + mu * mu) / (16. * PI); }
float miePhase(float mu) {
    const float g = .8;
    return (1. - g * g) / (4. * PI * pow(max(.01, 1. + g * g - 2. * g * mu), 1.5));
}
vec3 atmoMultiple(vec3 p, vec3 sun) {
    float r = max(length(p), AtmoRadius + .0001);
    return lutRead(MultipleScatteringLut,
        vec2(dot(p, sun) / r * .5 + .5, sqrt(clamp((r - AtmoRadius) / AtmoHeight, 0., 1.)))).rgb;
}
float cloudDensity(vec3 p) {
    if (CloudsEnabled == 0) return 0.;
    float h = (length(p) - AtmoRadius) / AtmoHeight;
    if (h < .24 || h > .52) return 0.;
    vec3 anchored = (CloudRotation * vec4(p, 0)).xyz + CloudOffset +
        vec3(CloudTime * AtmoHeight * .0008, 0, CloudTime * AtmoHeight * .0003);
    float weather = densityNoise(anchored / AtmoHeight * 2.);
    // Quantized density cells retain the broad, block-shaped formations of Minecraft clouds.
    vec3 cell = floor(anchored / (AtmoHeight * .045));
    float shape = densityFbm(cell * .43);
    float coverage = mix(AtmoClouds, 1., SurfaceRain);
    float density = smoothstep(1. - coverage - .15, 1. - coverage + .12, weather * .65 + shape * .35);
    density = mix(density, max(.45, density), SurfaceRain);
    return density * smoothstep(.24, .27, h) * (1. - smoothstep(.47, .52, h));
}
float cloudExtinction(vec3 p) {
    return cloudDensity(p) * (18. + SurfaceRain * 42.) / AtmoHeight;
}
float cloudShadow(vec3 p, vec3 sun) {
    vec2 span = atmoSphere(p, sun, AtmoRadius + AtmoHeight * .52);
    float distance = max(0., span.y), optical = 0.;
    // Fixed, world-anchored samples avoid temporally animated cloud grain.
    for (int i = 0; i < 5; i++) {
        float t = (float(i) + .5) / 5.;
        optical += cloudExtinction(p + sun * distance * t * t) * distance * (2. * t / 5.);
    }
    return exp(-optical);
}
void atmoMedium(vec3 p, vec3 d, out vec3 extinction, out vec3 source) {
    float h = length(p) - AtmoRadius;
    vec3 rho = atmoDensities(h), sun = atmoSunDirection(p);
    vec3 scatter = AtmoRayleigh * rho.x + vec3(AtmoMie * rho.y);
    vec3 solar = atmoTrans(p, sun) * atmoSunEnergy();
    if (CloudsEnabled != 0 && h < AtmoHeight * .52 && max(solar.r, max(solar.g, solar.b)) > .0001)
        solar *= cloudShadow(p, sun);
    float mu = dot(d, sun);
    extinction = atmoExtinction(h);
    source = (AtmoRayleigh * rho.x * rayleighPhase(mu) + AtmoMie * rho.y * miePhase(mu)) * solar;
    source += scatter * atmoMultiple(p, sun) * atmoSunEnergy() * (1. - SurfaceRain * .65);
    float cloud = cloudExtinction(p);
    if (cloud > .00001) {
        float ambient = mix(.12, .018, SurfaceThunder);
        vec3 lighting = solar * (.08 + .25 * miePhase(mu)) +
            atmoSunEnergy() * atmoMultiple(p, sun) * ambient * 4.;
        // A small isotropic term approximates higher-order scattering inside thick clouds.
        lighting += atmoSunEnergy() * max(0., dot(normalize(p), sun)) * ambient * .035;
        source += cloud * lighting * .97;
        extinction += vec3(cloud);
    }
}
void atmoSegment(vec3 d, float start, float end, int steps, inout vec3 L, inout vec3 T) {
    if (end <= start) return;
    float ds = (end - start) / float(steps);
    for (int i = 0; i < 64; i++) {
        if (i >= steps || max(T.r, max(T.g, T.b)) < .0005) break;
        vec3 p = d * ((start + (float(i) + .5) * ds) * AtmoDistanceScale) - AtmoCenter;
        vec3 sigma, source;
        atmoMedium(p, d, sigma, source);
        vec3 attenuation = exp(-sigma * ds * AtmoDistanceScale);
        L += T * source * (1. - attenuation) / max(sigma, vec3(1e-8));
        T *= attenuation;
    }
}
vec2 atmoSpan(vec3 d, float limit) {
    vec3 p = -AtmoCenter;
    if (length(p) < AtmoRadius - .0001) return vec2(FAR, -FAR);
    vec2 span = atmoSphere(p, d, AtmoRadius + AtmoHeight);
    span /= AtmoDistanceScale;
    span.x = max(0., span.x); span.y = min(limit, span.y);
    vec2 ground = atmoSphere(p, d, AtmoRadius);
    if (ground.x > .0001) span.y = min(span.y, ground.x / AtmoDistanceScale);
    return span;
}
// Split at cloud-layer boundaries so long horizon/space rays cannot step over thin clouds.
void atmoIntegrate(vec3 d, float nearDistance, float limit, int clearSteps, int cloudSteps,
                   inout vec3 L, inout vec3 T) {
    vec2 span = atmoSpan(d, limit);
    span.x = max(nearDistance, span.x);
    if (span.y <= span.x) return;
    float cuts[6]; cuts[0] = span.x; cuts[1] = span.y;
    vec2 lower = atmoSphere(-AtmoCenter, d, AtmoRadius + AtmoHeight * .24);
    vec2 upper = atmoSphere(-AtmoCenter, d, AtmoRadius + AtmoHeight * .52);
    lower /= AtmoDistanceScale; upper /= AtmoDistanceScale;
    cuts[2] = clamp(lower.x, span.x, span.y); cuts[3] = clamp(lower.y, span.x, span.y);
    cuts[4] = clamp(upper.x, span.x, span.y); cuts[5] = clamp(upper.y, span.x, span.y);
    for (int i = 1; i < 6; i++)
        for (int j = i; j > 0 && cuts[j] < cuts[j - 1]; j--) {
            float a = cuts[j]; cuts[j] = cuts[j - 1]; cuts[j - 1] = a;
        }
    for (int i = 0; i < 5; i++) {
        float h = (length(d * ((cuts[i] + cuts[i + 1]) * .5 * AtmoDistanceScale) - AtmoCenter) - AtmoRadius) / AtmoHeight;
        int steps = h >= .24 && h <= .52 && CloudsEnabled != 0 ? cloudSteps : clearSteps;
        atmoSegment(d, cuts[i], cuts[i + 1], steps, L, T);
    }
}
mat3 skyBasis() {
    vec3 up = length(AtmoCenter) > .0001 ? normalize(-AtmoCenter) : vec3(0, 1, 0);
    vec3 sun = atmoSunDirection(-AtmoCenter);
    vec3 right = cross(up, sun);
    if (length(right) < .001) right = cross(up, abs(up.y) < .9 ? vec3(0, 1, 0) : vec3(1, 0, 0));
    right = normalize(right);
    return mat3(right, up, normalize(cross(right, up)));
}
vec3 skyLutRay(vec2 uv) {
    // Square elevation rather than its sine: retain horizon detail without a coarse polar cap.
    float v = uv.y * 2. - 1., mu = sin(sign(v) * v * v * (.5 * PI));
    float angle = uv.x * 2. * PI;
    return skyBasis() * vec3(sin(angle) * sqrt(max(0., 1. - mu * mu)), mu,
                            cos(angle) * sqrt(max(0., 1. - mu * mu)));
}
vec4 skyLutRead(sampler2D image, vec3 d) {
    vec3 local = transpose(skyBasis()) * d;
    float elevation = asin(clamp(local.y, -1., 1.)) / (.5 * PI);
    // Azimuth is undefined at the poles; every column there stores the same vertical ray.
    float azimuth = dot(local.xz, local.xz) > 1e-12 ? atan(local.x, local.z) : 0.;
    vec2 uv = vec2(fract(azimuth / (2. * PI)),
                   .5 + .5 * sign(elevation) * sqrt(abs(elevation)));
    ivec2 size = textureSize(image, 0);
    vec2 p = vec2(uv.x * float(size.x) - .5, clamp(uv.y, 0., 1.) * float(size.y - 1));
    ivec2 a = ivec2(floor(p)), b = a + 1;
    a.x = (a.x + size.x) % size.x; b.x = (b.x + size.x) % size.x; b.y = min(b.y, size.y - 1);
    vec2 f = fract(p);
    return mix(mix(texelFetch(image, a, 0), texelFetch(image, ivec2(b.x, a.y), 0), f.x),
               mix(texelFetch(image, ivec2(a.x, b.y), 0), texelFetch(image, b, 0), f.x), f.y);
}
