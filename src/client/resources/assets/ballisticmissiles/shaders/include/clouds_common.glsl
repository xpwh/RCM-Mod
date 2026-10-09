#version 330

// Shared by the volumetric clouds and the light pass (cloud shadows and light shafts): the cloud layer's
// shape, from tiling 3D noise and the rocket disturbance map. The caller sets the globals below first.
//
// Vertex data of both passes (see VolumetricClouds):
//  Color   r coverage, g daylight, b rain, a quality (0 low, 1/3 medium, 2/3 high, 1 ultra)
//  UV0     wind offset (blocks)
//  UV1     x base height; y thickness (low 8 bits), bit 8 cloud shadows, bit 9 light shafts
//  UV2     x how far out the cloud planes reach (blocks); y thunderstorm (low 7 bits), lightning (next 7)
//  Normal  direction to the sun (or the moon at night)

uniform sampler2D Sampler0; // disturbance map: R holes, G exhaust smoke, B cloud heaped round holes, A engine glow
uniform sampler2D Sampler1; // 3D noise atlas: 64 slices of 66 x 66 (1 texel wrapped border) in an 8 x 8 grid

const float ATLAS = 528.0;

float bottom;
float thick;
float coverage;
float storm;
vec2 cloudWind;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// trilinear fetch from the slice atlas; p in noise periods (wraps every 1.0)
vec4 cloudNoise(vec3 p) {
    p = fract(p) * 64.0;
    float z = p.z - 0.5;
    float z0 = floor(z);
    float f = z - z0;
    float s0 = mod(z0, 64.0);
    float s1 = mod(z0 + 1.0, 64.0);
    vec2 uv0 = (vec2(mod(s0, 8.0), floor(s0 / 8.0)) * 66.0 + 1.0 + p.xy) / ATLAS;
    vec2 uv1 = (vec2(mod(s1, 8.0), floor(s1 / 8.0)) * 66.0 + 1.0 + p.xy) / ATLAS;
    return mix(texture(Sampler1, uv0), texture(Sampler1, uv1), f);
}

float remap(float v, float a, float b, float c, float d) {
    return c + (v - a) / (b - a) * (d - c);
}

// the camera, x and z wrapped to 4096 blocks (all the noise and the map tile within that)
vec3 wrappedCamera(ivec3 cb, vec3 offset) {
    return vec3(float(((cb.x % 4096) + 4096) % 4096), float(cb.y), float(((cb.z % 4096) + 4096) % 4096)) - offset;
}

// cloud density at a point (x and z wrapped to 4096 blocks, y absolute)
//  detail  0: base shape only (shadows), 1: + finer outline octave, 2: + cauliflower erosion
//  smoke   how much of it is exhaust; glow: rocket engine or fireball light there
float density(vec3 p, int detail, out float smoke, out float glow) {
    smoke = 0.0;
    glow = 0.0;
    float h = (p.y - bottom) / thick;
    if (h <= 0.0 || h >= 1.0) {
        return 0.0;
    }
    vec4 dist = texture(Sampler0, p.xz / 1024.0);
    vec3 q = vec3(p.x + cloudWind.x, p.z + cloudWind.y, p.y * 2.5);
    vec4 n = cloudNoise(q / 1024.0);
    float cov = clamp(coverage + (n.b - 0.5) * 0.5, 0.0, 1.0);
    // where the noise clears the coverage threshold there is cloud; the denser it is, the taller it towers;
    // a second, finer octave breaks up the outlines
    float shape = n.r;
    if (detail >= 1) {
        shape = n.r * 0.72 + cloudNoise(q / 512.0 + vec3(0.21, 0.63, 0.11)).r * 0.28;
    }
    float d = clamp(remap(shape, 1.0 - cov, 1.0, 0.0, 1.0), 0.0, 1.0);
    float tall = min(1.0, 0.2 + 0.8 * d + storm * 0.35);
    float profile = smoothstep(0.0, 0.07, h) * (1.0 - smoothstep(tall - 0.3, tall, h));
    d *= profile;
    float e = 0.5;
    if (detail >= 2 && (d > 0.0 || dist.g > 0.0 || dist.b > 0.0)) {
        // the edges eaten into cauliflower lumps, wispier towards the base
        e = cloudNoise(q / 256.0 + vec3(0.37)).g;
        d = remap(d, e * mix(0.55, 0.3, h), 1.0, 0.0, 1.0);
    }
    d = max(d, 0.0) * (1.0 - dist.r);
    // the cloud a rocket shoved aside, heaped up in a lumpy ring round the hole
    float heap = 0.35 + 0.45 * n.r;
    d += dist.b * smoothstep(0.0, 0.08, h) * (1.0 - smoothstep(heap - 0.2, heap, h)) * smoothstep(0.25, 0.75, e) * 0.75;
    glow = dist.a;
    // exhaust smoke: a column right through the layer where the rocket went, heaviest low down, frayed
    // (it thins out in the hole, where the blast of the passage has carried it on up)
    float s = dist.g * smoothstep(0.0, 0.05, h) * (1.0 - 0.55 * h) * smoothstep(0.12, 0.8, e * 0.65 + n.r * 0.55) * 0.9 * (1.0 - 0.75 * dist.r);
    if (s > 0.0) {
        smoke = s / max(d + s, 1e-4);
        d += s;
    }
    return clamp(d, 0.0, 1.0);
}

float density(vec3 p, int detail) {
    float s;
    float g;
    return density(p, detail, s, g);
}

// how much cloud (optical depth, density x blocks) lies between p and the sun, in a few coarse samples
float cloudBetween(vec3 p, vec3 toSun, int samples) {
    if (toSun.y <= 0.02 || p.y >= bottom + thick) {
        return 0.0;
    }
    float tIn = max(0.0, (bottom - p.y) / toSun.y);
    float tOut = (bottom + thick - p.y) / toSun.y;
    float seg = (tOut - tIn) / float(samples);
    float od = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= samples) break;
        od += density(p + toSun * (tIn + seg * (float(i) + 0.5)), 0) * seg;
    }
    return od;
}

float henyeyGreenstein(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159 * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}
