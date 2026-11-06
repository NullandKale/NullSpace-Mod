layout(std430, binding = 0) readonly buffer Bodies 
{
    vec4 BodyData[];
};

layout(std430, binding = 1) readonly buffer Nodes 
{
    uvec4 NodeBits[];
};

layout(std430, binding = 2) readonly buffer Scene 
{
    uvec4 SceneBits[];
};

uniform mat4 InvView;
uniform mat4 ViewProjection;

uniform int BodyCount, NodeCount, VolumeCount, CabinCount, BruteForce, SurfaceSamples, VolumeSteps;

uniform float Time;

uniform int HasSurfaceAtmosphere;
uniform float SurfaceRain, SurfaceThunder;
uniform vec3 SurfaceUp;

uniform int LightCount, Lights[8];

uniform vec2 Jitter;
uniform ivec2 ViewSize;

const int SLICES = 24;
const float FAR = 1000000.0;

vec4 scene(int n) 
{
    return uintBitsToFloat(SceneBits[n]);
}

float hash(vec3 p) 
{
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float noise(vec3 p) 
{
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3. - 2. * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y),
               f.z);
}

// Integer lattice hashing avoids transcendental noise evaluation in the inner volume loop.

float densityHash(vec3 p) 
{
    uvec3 q = uvec3(ivec3(p));
    uint n = q.x * 1597334677u ^ q.y * 3812015801u ^ q.z * 2798796415u;
    n = (n ^ (n >> 16)) * 2246822519u;
    n = (n ^ (n >> 13)) * 3266489917u;
    return float((n ^ (n >> 16)) >> 8) * (1. / 16777216.);
}

float densityNoise(vec3 p) 
{
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3. - 2. * f);
    return mix(mix(mix(densityHash(i), densityHash(i + vec3(1, 0, 0)), f.x),
                   mix(densityHash(i + vec3(0, 1, 0)), densityHash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(densityHash(i + vec3(0, 0, 1)), densityHash(i + vec3(1, 0, 1)), f.x),
                   mix(densityHash(i + vec3(0, 1, 1)), densityHash(i + vec3(1, 1, 1)), f.x), f.y),
               f.z);
}

float densityFbm(vec3 p) 
{
    return densityNoise(p) * .7 + densityNoise(p * 2.03 + 7.1) * .3;
}

float fbm(vec3 p) 
{
    return noise(p) * .57 + noise(p * 2.03 + 7.1) * .28 + noise(p * 4.13 + 13.7) * .15;
}

vec3 rayAt(vec2 uv) 
{
    vec4 p = InvView * vec4(uv * 2. - 1., 1., 1.);
    return normalize(p.xyz / p.w);
}

vec3 ray(ivec2 pixel, ivec2 size) 
{
    return rayAt((vec2(pixel) + .5 + Jitter) / vec2(size));
}

vec2 sphereInterval(vec3 o, vec3 d, vec3 c, float r) 
{
    vec3 q = o - c;
    float b = dot(q, d), h = b * b - dot(q, q) + r * r;
    if (h < 0.)
        return vec2(FAR, -FAR);
    float root = sqrt(h);
    return vec2(-b - root, -b + root);
}

bool boxHit(vec3 o, vec3 d, vec3 lo, vec3 hi, float limit) 
{
    vec3 sd = mix(d, vec3(1e-12), lessThan(abs(d), vec3(1e-12)));
    vec3 a = (lo - o) / sd, b = (hi - o) / sd;
    vec3 mn = min(a, b), mx = max(a, b);
    return max(max(mn.x, mn.y), max(mn.z, 0.)) <= min(min(mx.x, mx.y), min(mx.z, limit));
}

vec3 rotateAxis(vec3 p, vec4 rotation) 
{
    vec3 axis = normalize(rotation.xyz);
    float c = cos(rotation.w), s = sin(rotation.w);
    return p * c + cross(axis, p) * s + axis * dot(axis, p) * (1. - c);
}

float shapeDistance(vec3 p, int id) 
{
    vec4 c = BodyData[id * 5], m = BodyData[id * 5 + 3];
    vec4 rotation = BodyData[id * 5 + 4];
    rotation.w = -rotation.w;
    vec3 q = rotateAxis((p - c.xyz) / c.w, rotation);
    float wave = sin(q.x * 3.1 + m.y) * sin(q.y * 4.3 + m.y * .37) * sin(q.z * 3.7 - m.y * .21);
    return (length(p - c.xyz) - c.w * (1. + m.x * wave)) / (1. + m.x * 7.);
}

float bodyHit(vec3 o, vec3 d, int id, float limit) 
{
    vec4 c = BodyData[id * 5], m = BodyData[id * 5 + 3];
    vec2 span = sphereInterval(o, d, c.xyz, c.w * (1. + m.x));
    float start = max(span.x, .001), end = min(span.y, limit);

    if (start > end)
    {
        return FAR;
    }

    if (m.x <= 0.00001 || c.w / max(length(c.xyz - o), 1.) < .001) 
    {
        vec2 exact = sphereInterval(o, d, c.xyz, c.w);
        return exact.x >= .001 ? exact.x : exact.y >= .001 ? exact.y : FAR;
    }

    float t = start;

    for (int step = 0; step < 160; step++) 
    {
        float sd = shapeDistance(o + d * t, id);
        float epsilon = max(.001, t * .000002);
        
        if (abs(sd) < epsilon)
        {
            return t;
        }

        t += max(abs(sd) * .9, epsilon * .25);
        
        if (t > end)
        {
            break;
        }
    }

    return FAR;
}
vec2 trace(vec3 o, vec3 d, float limit) 
{
    float nearest = limit;
    int hit = -1;

    if (BruteForce != 0) 
    {
        for (int i = 0; i < BodyCount; i++) 
        {
            float t = bodyHit(o, d, i, nearest);
            if (t < nearest) 
            {
                nearest = t;
                hit = i;
            }
        }
    } 
    else if (NodeCount > 0) 
    {
        int stack[16];
        int count = 1;
        stack[0] = 0;
        
        for (int visit = 0; visit < 8192 && count > 0; visit++) 
        {
            int index = stack[--count];
            uvec4 a = NodeBits[index * 2], b = NodeBits[index * 2 + 1];
            
            if (!boxHit(o, d, uintBitsToFloat(a.xyz), uintBitsToFloat(b.xyz), nearest))
            {
                continue;
            }
            
            int left = int(a.w);
            
            if (left < 0) 
            {
                int id = -left - 1;
                float t = bodyHit(o, d, id, nearest);
                if (t < nearest) 
                {
                    nearest = t;
                    hit = id;
                }
            }
            else if (count < 14) 
            {
                stack[count++] = left;
                stack[count++] = int(b.w);
            }
        }
    }

    return vec2(nearest, float(hit));
}

float surfaceSunHeight() 
{
    return LightCount > 0 ? dot(normalize(BodyData[Lights[0] * 5].xyz), SurfaceUp) : -1.;
}

#include "stars.glsl"
float atmosphereStarVisibility();

vec3 sky(vec3 d) 
{
    float dominant = max(max(abs(d.x), abs(d.y)), abs(d.z));
    vec3 p = d / dominant;
    int face = abs(d.z) >= max(abs(d.x), abs(d.y)) ? (d.z > 0. ? 0 : 1)
               : abs(d.y) >= abs(d.x)              ? (d.y > 0. ? 2 : 3)
                                                   : (d.x > 0. ? 4 : 5);
    vec2 uv = face < 2 ? p.xy : face < 4 ? p.xz : p.yz;
    vec2 grid = uv * 450., cell = floor(grid);
    vec3 sum = vec3(.0008, .0014, .003);
    vec3 a = rayAt(vec2(.5)), b = rayAt(vec2(.5) + vec2(0, 1. / float(ViewSize.y)));
    float footprint = max(.02, length(a - b) * 450. / dominant);

    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++) 
        {
            vec2 c = cell + vec2(x, y);
            float random = densityHash(vec3(c, float(face)));
            if (random < .996)
            {
                continue;
            }

            float magnitude = densityHash(vec3(c, float(face + 31)));
            vec2 at = c + vec2(densityHash(vec3(c, float(face + 7))),
                               densityHash(vec3(c, float(face + 19))));
            float radius = .08 + pow(magnitude, 8.) * .3;
            float variance = radius * radius + footprint * footprint / 12.;
            float star =
                exp(-dot(grid - at, grid - at) / (2. * variance)) * radius * radius / variance;
            sum += vec3(.6, .75, 1.) * star * (.6 + pow(magnitude, 6.) * 7.);
        }
    }
    // Extinction alone leaves high-radiance stars visible over the daytime sky. Fade their
    // visibility across twilight on atmospheric surfaces; space and airless moons keep them.
    float angularPixel = length(a - b);
    sum *= 1. - StarFtl * .85;
    if (StarParallax > .001 && StarFtl < .999)
        sum += nearbyStars(d, angularPixel) * StarParallax * (1. - StarFtl);
    if (StarFtl > .001)
        sum += ftlStars(d, angularPixel) * StarFtl;
    return sum * atmosphereStarVisibility();
}

vec3 surface(vec3 d, vec2 hit) 
{
    int id = int(hit.y);
    if (id < 0)
    {
        return sky(d);
    }

    vec4 c = BodyData[id * 5], color = BodyData[id * 5 + 1], meta = BodyData[id * 5 + 3]; 
    vec3 p = d * hit.x, n = normalize(p - c.xyz);
    
    if (meta.x > 0.) 
    {
        float eps = max(.002, c.w * .0001);
        n = normalize(
            vec3(shapeDistance(p + vec3(eps, 0, 0), id) - shapeDistance(p - vec3(eps, 0, 0), id),
                 shapeDistance(p + vec3(0, eps, 0), id) - shapeDistance(p - vec3(0, eps, 0), id),
                 shapeDistance(p + vec3(0, 0, eps), id) - shapeDistance(p - vec3(0, 0, eps), id)));
    }

    vec3 albedo = pow(color.rgb, vec3(2.2));
    int kind = int(BodyData[id * 5 + 2].w);
    vec4 rotation = BodyData[id * 5 + 4];
    rotation.w = -rotation.w;
    vec3 localNormal = rotateAxis(n, rotation);
    float detail = fbm(localNormal * 12. + meta.y);

    if (kind == 1) 
    {
        float land = smoothstep(.46, .53, detail);
        albedo = mix(albedo, vec3(.07, .23, .055), land);
        albedo = mix(albedo, vec3(.8), smoothstep(.83, .96, abs(localNormal.y)));
    } 
    else if (kind >= 2)
    {
        albedo *= .65 + detail * .65;
    }

    if (color.w > 0.)
    {
        return albedo * color.w * (.8 + detail * .4);
    }
    vec3 result = albedo * .025;
    int lights = 0;

    for (int li = 0; li < LightCount; li++) 
    {
        int i = Lights[li];
        lights++;
        vec4 sun = BodyData[i * 5];
        vec3 toLight = sun.xyz - p;
        float distance = length(toLight);
        vec3 l = toLight / distance;
        float ndl = max(0., dot(n, l));
        if (ndl > 0.) 
        {
            vec2 block =
                trace(p + n * max(.01, c.w * .00002), l, max(.01, distance - sun.w * 1.001));
            float visible = block.y < 0. ? 1. : 0.;
            float irradiance =
                min(3., BodyData[i * 5 + 1].w * sun.w * sun.w / max(distance * distance, 1.) * 40.);
            result += albedo * pow(BodyData[i * 5 + 1].rgb, vec3(2.2)) * ndl * visible * irradiance;
        }
    }
    return result;
}

bool cabin(vec3 p) 
{
    for (int i = 0; i < CabinCount; i++) 
    {
        int start = 80 + i * 261;
        mat4 inv = mat4(scene(start), scene(start + 1), scene(start + 2), scene(start + 3));
        vec3 local = (inv * vec4(p, 1.)).xyz;
        if (all(greaterThanEqual(local, vec3(0))) && all(lessThan(local, vec3(32)))) 
        {
            ivec3 q = ivec3(local);
            int cell = q.x + q.z * 32 + q.y * 1024;
            uint bits = SceneBits[start + 5 + cell / 128][(cell / 32) % 4];
            if ((bits & (1u << uint(cell % 32))) != 0u)
            {
                return true;
            }
        }
    }
    return false;
}

float volumeDensity(vec3 p, int i) {
    vec4 center = scene(i * 5), extent = scene(i * 5 + 1), extra = scene(i * 5 + 4);
    vec3 delta = p - center.xyz;
    if (int(center.w) == 2) {
        vec3 axis = normalize(extra.xyz);
        vec3 right = normalize(cross(axis, abs(axis.y) > .9 ? vec3(1, 0, 0) : vec3(0, 1, 0)));
        vec3 up = cross(axis, right);
        delta = vec3(dot(delta, right), dot(delta, up), dot(delta, axis));
    }
    vec3 q = delta / extent.xyz;
    float r = length(q);
    if (r >= 1.)
        return 0.;
    if (int(center.w) == 1) {
        float height = length(delta) - extra.x;
        if (height < 0.)
            return 0.;
        return extent.w * exp(-height / max(1., (extent.x - extra.x) * .25));
    }
    float density = densityFbm(q * 5. + vec3(extra.w, Time * .015, 0));
    return extent.w * smoothstep(.28, .7, density) * pow(max(0., 1. - r * r), 2.);
}
vec2 volumeInterval(vec3 d, int i) {
    vec4 c = scene(i * 5), e = scene(i * 5 + 1);
    if (int(c.w) == 2) {
        float radius = max(e.x, max(e.y, e.z));
        return sphereInterval(vec3(0), d, c.xyz, radius);
    }
    vec3 o = -c.xyz / e.xyz, v = d / e.xyz;
    float a = dot(v, v), b = dot(o, v), h = b * b - a * (dot(o, o) - 1.);
    if (h < 0.)
        return vec2(FAR, -FAR);
    float root = sqrt(h);
    return vec2((-b - root) / a, (-b + root) / a);
}
float sliceDistance(int i) {
    return i == 0 ? 0. : .05 * pow(FAR / .05, float(i) / float(SLICES - 1));
}

#include "atmosphere.glsl"

float atmosphereStarVisibility() 
{
    if (HasAtmosphere == 0) return 1.;
    if (length(AtmoCenter) < .0001) return 0.;
    float altitude = length(AtmoCenter) - AtmoRadius;
    float inside = 1. - smoothstep(AtmoHeight * .5, AtmoHeight, altitude);
    float solarHeight = dot(normalize(-AtmoCenter), atmoSunDirection(-AtmoCenter));
    return mix(1., 1. - smoothstep(-.12, .02, solarHeight), inside);
}

vec3 surfaceAtmosphere(vec3 d, vec3 color, vec2 hit) 
{
    if (HasAtmosphere == 0) return color;
    vec3 L, T;

    if (length(AtmoCenter) > AtmoRadius + AtmoHeight) 
    {
        // Outside the atmosphere its silhouette can be smaller than a sky-LUT texel.
        // Integrate only rays hitting that bounded shell, preserving the planet's crisp edge.
        L = vec3(0); T = vec3(1);
        atmoIntegrate(d, 0., hit.y >= 0. ? hit.x / AtmoDistanceScale : FAR, 24, CloudSteps, L, T);
    } 
    else 
    {
        L = skyLutRead(SkyRadianceLut, d).rgb;
        T = skyLutRead(SkyTransmissionLut, d).rgb;
    }
    // Match the exposed sky's solar radiance without changing airless/celestial emission.
    if (hit.y >= 0. && BodyData[int(hit.y) * 5 + 1].w > 0.)
    {
        color *= mix(4., 1., smoothstep(AtmoHeight * .5, AtmoHeight, length(AtmoCenter) - AtmoRadius));
    }

    return color * T + L;
}
