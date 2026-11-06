uniform vec3 StarEye, StarForward;
uniform float StarFtl, StarParallax, StarTime;

// Sparse, fixed points on a world-space lattice. Translation, including automatic docking,
// changes their sight lines; neither the player's look direction nor time moves the lattice.
vec3 nearbyStars(vec3 d, float angularPixel)
{
    vec3 sum = vec3(0);
    for (int axis = 0; axis < 3; axis++)
    {
        if (abs(d[axis]) < .2)
            continue;
        for (int layer = 1; layer <= 2; layer++)
        {
            float plane = floor(StarEye[axis] / 192.) + (d[axis] > 0. ? float(layer) : float(1 - layer));
            float t = (plane * 192. - StarEye[axis]) / d[axis];
            if (t <= 4. || t >= 640.)
                continue;
            vec3 point = StarEye + d * t;
            vec2 grid = (axis == 0 ? point.yz : axis == 1 ? point.xz : point.xy) / 12.;
            vec2 cell = floor(grid);
            vec3 key = vec3(cell, plane * 13. + float(axis) * 997.);
            if (densityHash(key) < .96)
                continue;
            float magnitude = densityHash(key + 31.);
            vec2 at = cell + .25 + .5 * vec2(densityHash(key + 7.), densityHash(key + 19.));
            vec2 delta = (grid - at) * 12.;
            float radius = .09 + .12 * pow(magnitude, 6.);
            float footprint = angularPixel * t / abs(d[axis]);
            float variance = radius * radius + footprint * footprint / 12.;
            float light = exp(-dot(delta, delta) / (2. * variance)) * radius * radius / variance;
            light *= smoothstep(4., 24., t) * (1. - smoothstep(512., 640., t));
            sum += vec3(.85, .91, 1.) * light * (1. + magnitude * 3.);
        }
    }
    return sum;
}

// Meridians converge on the actual travel vector, so looking sideways or backwards preserves
// the direction of motion. Each point flows from the forward pole toward the rear pole.
vec3 ftlStars(vec3 d, float angularPixel)
{
    vec3 forward = normalize(StarForward);
    vec3 reference = abs(forward.y) < .9 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 right = normalize(cross(forward, reference));
    vec3 up = cross(right, forward);
    float theta = acos(clamp(dot(d, forward), -1., 1.));
    float longitude = atan(dot(d, up), dot(d, right));
    float lane = (longitude / 6.28318530718 + .5) * 192.;
    vec3 sum = vec3(0);
    for (int offset = -1; offset <= 1; offset++)
    {
        float rawLane = floor(lane) + float(offset);
        float wrappedLane = mod(rawLane, 192.);
        for (int index = 0; index < 6; index++)
        {
            vec3 key = vec3(wrappedLane, float(index), 713.);
            float center = rawLane + .25 + .5 * densityHash(key);
            float across = (lane - center) * (6.28318530718 / 192.) * max(.03, sin(theta));
            float head = 3.14159265359 * fract(densityHash(key + 11.) + StarTime * .25);
            float tail = .025 + .19 * densityHash(key + 23.);
            float along = theta - head;
            float width = .00045 + angularPixel * .6;
            float side = exp(-across * across / (2. * width * width));
            float streak = smoothstep(-tail, -tail * .65, along) * (1. - smoothstep(0., width * 2., along));
            streak *= .35 + .65 * smoothstep(-tail, 0., along);
            streak *= smoothstep(.02, .13, head) * (1. - smoothstep(3., 3.14, head));
            sum += mix(vec3(.85, .9, 1.), vec3(1., .93, .8), densityHash(key + 43.)) * side * streak;
        }
    }
    return sum * 2.;
}
