#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Volumetric clouds. Each view ray is marched through the cloud layer: the shape comes from tiling 3D
// noise (billowing Perlin-Worley base, eroded at the edges by finer Worley detail), with a flat base and
// rounded tops; light is marched a few steps towards the sun, so tops are bright, undersides dark, thin
// edges glow when you look towards the sun, and the light reddens at sunrise and sunset.
// Rain and thunderstorms darken and thicken the layer (storm clouds tower right up), and lightning lights
// the clouds up from inside, brightest low down where the bolts come out.
// Rockets disturb the layer: Sampler0 is a wrapping 1024 x 1024 block map, R = holes punched through
// (they widen, then fill in again), G = exhaust smoke spreading out along the layer, B = cloud pushed
// aside and piled up round the rims of the holes, A = rocket engines and fireballs lighting the cloud.

uniform sampler2D Sampler0; // disturbance map
uniform sampler2D Sampler1; // 3D noise atlas: 64 slices of 66 x 66 (1 texel wrapped border) in an 8 x 8 grid

in vec3 relPos;
in vec4 cloudInfo;
flat in vec2 wind;
flat in ivec2 layer;
flat in vec3 sunDir;
flat in float reach;
flat in float storm;
flat in float lightning;

out vec4 fragColor;

const float ATLAS = 528.0;
const int STEPS = 56;

float bottom;
float thick;
float coverage;

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

// cloud density at a point (x and z wrapped to 4096 blocks, y absolute); smoke = how much of it is exhaust
float density(vec3 p, bool detail, out float smoke, out float glow) {
    smoke = 0.0;
    glow = 0.0;
    float h = (p.y - bottom) / thick;
    if (h <= 0.0 || h >= 1.0) {
        return 0.0;
    }
    vec4 dist = texture(Sampler0, p.xz / 1024.0);
    vec3 q = vec3(p.x + wind.x, p.z + wind.y, p.y * 2.5);
    vec4 n = cloudNoise(q / 1024.0);
    float cov = clamp(coverage + (n.b - 0.5) * 0.5, 0.0, 1.0);
    // where the noise clears the coverage threshold there is cloud; the denser it is, the taller it towers
    // a second, finer octave breaks up the outlines
    float shape = n.r * 0.72 + cloudNoise(q / 512.0 + vec3(0.21, 0.63, 0.11)).r * 0.28;
    float d = clamp(remap(shape, 1.0 - cov, 1.0, 0.0, 1.0), 0.0, 1.0);
    float tall = min(1.0, 0.2 + 0.8 * d + storm * 0.35);
    float profile = smoothstep(0.0, 0.07, h) * (1.0 - smoothstep(tall - 0.3, tall, h));
    d *= profile;
    float e = 0.5;
    if (detail && (d > 0.0 || dist.g > 0.0 || dist.b > 0.0)) {
        // the edges eaten into cauliflower lumps, wispier towards the base
        e = cloudNoise(q / 256.0 + vec3(0.37)).g;
        d = remap(d, e * mix(0.55, 0.3, h), 1.0, 0.0, 1.0);
    }
    d = max(d, 0.0) * (1.0 - dist.r);
    // the cloud the rocket shoved aside, heaped up in a lumpy ring round the hole
    float heap = 0.35 + 0.45 * n.r;
    d += dist.b * smoothstep(0.0, 0.08, h) * (1.0 - smoothstep(heap - 0.2, heap, h)) * smoothstep(0.25, 0.75, e) * 0.75;
    glow = dist.a;
    // exhaust smoke flattened into a sheet low in the layer, frayed by the same noise
    // exhaust smoke: a column right through the layer where the rocket went, heaviest low down, frayed
    // (it thins out in the hole, where the blast of the passage has carried it on up)
    float s = dist.g * smoothstep(0.0, 0.05, h) * (1.0 - 0.55 * h) * smoothstep(0.12, 0.8, e * 0.65 + n.r * 0.55) * 0.9 * (1.0 - 0.75 * dist.r);
    if (s > 0.0) {
        smoke = s / max(d + s, 1e-4);
        d += s;
    }
    return clamp(d, 0.0, 1.0);
}

float henyeyGreenstein(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159 * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

void main() {
    bottom = float(layer.x);
    thick = float(layer.y);
    coverage = cloudInfo.r;
    float day = cloudInfo.g;
    float rain = cloudInfo.b;

    vec3 rd = normalize(relPos);
    // camera, x and z wrapped to 4096 blocks (all the noise and the map tile within that)
    ivec3 cb = CameraBlockPos;
    vec3 cam = vec3(float(((cb.x % 4096) + 4096) % 4096), float(cb.y), float(((cb.z % 4096) + 4096) % 4096)) - CameraOffset;
    float top = bottom + thick;

    float t0;
    float t1;
    if (cam.y < bottom) {
        if (rd.y <= 0.0) discard;
        t0 = (bottom - cam.y) / rd.y;
        t1 = (top - cam.y) / rd.y;
    } else if (cam.y > top) {
        if (rd.y >= 0.0) discard;
        t0 = (top - cam.y) / rd.y;
        t1 = (bottom - cam.y) / rd.y;
    } else {
        t0 = 0.0;
        t1 = rd.y > 0.001 ? (top - cam.y) / rd.y : (rd.y < -0.001 ? (bottom - cam.y) / rd.y : 4000.0);
    }
    // as far as the cloud distance setting allows, and short of the edge of the planes
    float horizontal = max(length(rd.xz), 1e-3);
    float range = max(min(FogCloudsEnd, reach * 0.92 / horizontal), 64.0);
    if (t0 > range) discard;
    t1 = min(t1, min(t0 + 1400.0, range));

    bool fancy = cloudInfo.a > 0.75;
    int steps = fancy ? STEPS : STEPS / 2;
    float len = t1 - t0;
    float stepLen = max(len / float(steps), 3.0);
    float t = t0 + stepLen * hash(gl_FragCoord.xy);

    // light: the sun (orange low in the sky), the moon at night, the sky from above, darker in rain
    float low = smoothstep(0.0, 0.35, sunDir.y);
    // rain and above all a thunderstorm: much less sun gets through, the undersides turn slate grey
    float gloom = clamp(0.5 * rain + 0.4 * storm, 0.0, 0.85);
    vec3 sunCol = mix(vec3(0.16, 0.18, 0.26), mix(vec3(1.25, 0.62, 0.32), vec3(1.15, 1.1, 1.02), low), day) * (1.0 - 1.1 * gloom);
    vec3 skyTop = mix(vec3(0.035, 0.04, 0.065), vec3(0.62, 0.7, 0.84), day) * (1.0 - 0.9 * gloom);
    vec3 skyBottom = skyTop * mix(0.55, 0.32, storm) * mix(vec3(1.0), vec3(0.92, 0.95, 1.05), storm);
    // the flash of lightning inside the cloud
    vec3 boltCol = vec3(0.75, 0.8, 1.0) * lightning * 0.9;
    // storm clouds are thicker: they swallow more light
    float sigma = 0.11 * (1.0 + 0.6 * rain + 0.9 * storm);
    float cosTheta = dot(rd, sunDir);
    float phase = mix(henyeyGreenstein(cosTheta, 0.65), henyeyGreenstein(cosTheta, -0.15), 0.35) * 4.0 * 3.14159;

    float trans = 1.0;
    vec3 col = vec3(0.0);
    float firstHit = -1.0;
    for (int i = 0; i < STEPS; i++) {
        if (i >= steps || t > t1) break;
        vec3 p = cam + rd * t;
        float smoke;
        float glow;
        float d = density(p, fancy, smoke, glow);
        if (d > 0.002) {
            if (firstHit < 0.0) firstHit = t;
            // how much cloud lies between here and the sun
            float od = 0.0;
            float s0;
            float g0;
            od += density(p + sunDir * 6.0, false, s0, g0) * 8.0;
            od += density(p + sunDir * 16.0, false, s0, g0) * 12.0;
            od += density(p + sunDir * 32.0, false, s0, g0) * 18.0;
            od += density(p + sunDir * 58.0, false, s0, g0) * 30.0;
            float sunT = exp(-od * 0.06) * 0.85 + exp(-od * 0.015) * 0.15;
            float powder = 1.0 - exp(-d * 6.0);
            float h = (p.y - bottom) / thick;
            vec3 ambient = mix(skyBottom, skyTop, clamp(h * 1.2, 0.0, 1.0));
            // dark edges (the "powder" effect) only show on the side away from the sun
            float away = 0.5 - 0.5 * cosTheta;
            vec3 lit = ambient + sunCol * sunT * mix(1.0, powder, 0.5 * away) * phase * 0.9;
            lit += boltCol * (0.25 + 0.75 * (1.0 - h) * (1.0 - h)) * (0.4 + 0.6 * d);
            // a rocket engine (or a fireball) inside or under the cloud: it glows orange round it
            lit += vec3(1.0, 0.45, 0.16) * glow * glow * 1.5 * (0.3 + 0.7 * d);
            // exhaust smoke: dingy grey-brown
            lit *= mix(vec3(1.0), vec3(0.62, 0.6, 0.58), smoke);
            float a = 1.0 - exp(-d * sigma * stepLen);
            col += trans * a * lit;
            trans *= 1.0 - a;
            if (trans < 0.02) break;
        }
        t += stepLen;
    }
    float alpha = 1.0 - trans;
    if (alpha < 0.004) discard;
    vec3 color = col / alpha;
    // aerial perspective: far clouds fade into the haze, and out at the cloud range
    float dist = firstHit < 0.0 ? t0 : firstHit;
    color = mix(color, FogColor.rgb, clamp(dist / range, 0.0, 1.0) * 0.55);
    alpha *= 1.0 - linear_fog_value(dist, range * 0.65, range);
    fragColor = vec4(color, alpha);
}
