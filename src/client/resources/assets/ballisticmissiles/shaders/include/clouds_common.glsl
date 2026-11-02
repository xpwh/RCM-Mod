#version 330

// Shared by the volumetric clouds and the light pass (cloud shadows and light shafts): the cloud layer's
// shape, from tiling 3D noise and the rocket disturbance map. The caller sets the globals below first.
//
// Vertex data of both passes (see VolumetricClouds):
//  Color   r coverage, g daylight, b rain, a quality (64 per level) + 32 when the moon lights them + 16 for the
//          cumulus style
//  UV0     wind offset (blocks)
//  UV1     x base height; y thickness (low 8 bits), bit 8 cloud shadows, bit 9 light shafts
//  UV2     x how far out the cloud planes reach (blocks); y thunderstorm (low 7 bits), lightning (next 7)
//  Normal  direction to the sun (or the moon at night)

uniform sampler2D Sampler0; // disturbance map: R holes, G exhaust smoke, B cloud heaped round holes, A engine glow
uniform sampler2D Sampler1; // 3D noise atlas: 64 slices of 66 x 66 (1 texel wrapped border) in an 8 x 8 grid

const float ATLAS = 528.0;

float bottom;
float thick;
// the style: false the layered clouds, true separate cumulus heaps with flat bases and cauliflower tops
bool cumulusStyle;
float coverage;
float storm;
vec2 cloudWind;
// the thunderstorm coming in from the horizon (StormFront; needs bm_wind.glsl imported first): set by stormSetup
vec2 cellCenter;
float cellRadius;
float cellStrength;

void stormSetup(vec3 cam) {
    cellCenter = cam.xz + StormCell.xy;
    cellRadius = max(StormCell.z, 1.0);
    cellStrength = StormCell.w;
}

// how much of the storm cell's core is over (x, z), 0-1
float stormCore(vec2 xz) {
    if (cellStrength <= 0.0) return 0.0;
    float r = length(xz - cellCenter);
    return cellStrength * (1.0 - smoothstep(cellRadius * 0.45, cellRadius, r));
}

// how high the cell towers above the layer's base, as a multiple of the layer's thickness
const float TOWER = 2.4;

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

float layered(vec3 p, float h, int detail, vec4 dist, out float e, out float nr);
float cumulus(vec3 p, float h, int detail, out float e, out float nr);

// cloud density at a point (x and z wrapped to 4096 blocks, y absolute)
//  detail  0: base shape only (shadows), 1: + finer outline octave, 2: + cauliflower erosion
//  smoke   how much of it is exhaust; glow: rocket engine or fireball light there
float density(vec3 p, int detail, out float smoke, out float glow) {
    smoke = 0.0;
    glow = 0.0;
    // the storm cell: a cumulonimbus towering far above the layer, flattening out into an anvil at the top
    float core = stormCore(p.xz);
    float anvil = 0.0;
    float hTower = (p.y - bottom) / (thick * TOWER);
    if (cellStrength > 0.0 && hTower > 0.6 && hTower < 1.0) {
        float r = length(p.xz - cellCenter);
        float band = smoothstep(0.66, 0.8, hTower) * (1.0 - smoothstep(0.9, 1.0, hTower));
        // ragged at its edge, thinning out downwind
        float rag = cloudNoise(vec3((p.xz + cloudWind) / 900.0, p.y / 900.0)).r;
        anvil = cellStrength * band * (1.0 - smoothstep(cellRadius * (1.1 + 0.5 * rag), cellRadius * 2.0, r)) * (0.55 + 0.45 * rag);
    }
    float layerThick = thick * mix(1.0, TOWER, core);
    float h = (p.y - bottom) / layerThick;
    if (h <= 0.0 || h >= 1.0) {
        return anvil > 0.0 ? clamp(anvil * 1.6 - 0.3, 0.0, 1.0) : 0.0;
    }
    float coverage0 = coverage;
    float storm0 = storm;
    coverage = mix(coverage, 0.97, core);
    storm = max(storm, core);
    vec4 dist = texture(Sampler0, p.xz / 1024.0);
    float d;
    float e = 0.5;
    float nr;
    if (cumulusStyle) {
        d = cumulus(p, h, detail, e, nr);
    } else {
        d = layered(p, h, detail, dist, e, nr);
    }
    coverage = coverage0;
    storm = storm0;
    d = max(d, clamp(anvil * 1.6 - 0.3, 0.0, 1.0));
    float base = max(d, 0.0);
    d = base * (1.0 - dist.r);
    // a rocket only stirs the cloud that is there: heaped edges and exhaust show within the real cloud
    // (as it was before the hole), never as new cloud in clear air - its trail out there is the smoke itself
    float here = smoothstep(0.02, 0.3, base);
    // the cloud a rocket shoved aside, heaped up in a lumpy ring round the hole
    float heap = 0.35 + 0.45 * nr;
    d += here * dist.b * smoothstep(0.0, 0.08, h) * (1.0 - smoothstep(heap - 0.2, heap, h)) * smoothstep(0.25, 0.75, e) * 0.75;
    glow = dist.a;
    // exhaust smoke: a column right through the layer where the rocket went, heaviest low down, frayed
    // (it thins out in the hole, where the blast of the passage has carried it on up)
    float s = here * dist.g * smoothstep(0.0, 0.05, h) * (1.0 - 0.55 * h) * smoothstep(0.12, 0.8, e * 0.65 + nr * 0.55) * 0.9 * (1.0 - 0.75 * dist.r);
    if (s > 0.0) {
        smoke = s / max(d + s, 1e-4);
        d += s;
    }
    return clamp(d, 0.0, 1.0);
}

// the layered clouds: a sheet of 3D noise squashed flat, the denser parts towering higher
float layered(vec3 p, float h, int detail, vec4 dist, out float e, out float nr) {
    vec3 q = vec3(p.x + cloudWind.x, p.z + cloudWind.y, p.y * 2.5);
    vec4 n = cloudNoise(q / 1024.0);
    nr = n.r;
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
    e = 0.5;
    if (detail >= 2 && (d > 0.0 || dist.g > 0.0 || dist.b > 0.0)) {
        // the edges eaten into cauliflower lumps, wispier towards the base
        e = cloudNoise(q / 256.0 + vec3(0.37)).g;
        d = remap(d, e * mix(0.55, 0.3, h), 1.0, 0.0, 1.0);
    }
    return d;
}

// cumulus: separate heaps, each on a flat, sharp base (the condensation level), rising into a rounded,
// billowing dome - the stronger the updraft under it, the higher it towers; the noise is not squashed,
// so the lumps are as tall as they are wide
float cumulus(vec3 p, float h, int detail, out float e, out float nr) {
    vec3 q = vec3(p.x + cloudWind.x, p.z + cloudWind.y, p.y);
    // where the clouds stand: a slow, broad field (barely changing with height, so each is one column)
    vec4 big = cloudNoise(vec3(q.xy / 1600.0, q.z / 6000.0 + 0.3));
    vec4 n = cloudNoise(q / 700.0);
    nr = n.r;
    float cov = clamp(coverage * 0.95 + (big.b - 0.5) * 0.55, 0.0, 1.0);
    float cell = big.r * 0.62 + n.r * 0.38;
    float s = clamp(remap(cell, 1.0 - cov, 1.0, 0.0, 1.0), 0.0, 1.0);
    if (s <= 0.0) {
        e = 0.5;
        return 0.0;
    }
    // how tall this one grows: fair-weather puffs low, the strong ones (and storms) climbing the whole layer
    float tall = min(1.0, 0.12 + 1.15 * pow(s, 1.3) + storm * 0.45);
    float hf = h / tall;
    if (hf >= 1.0) {
        e = 0.5;
        return 0.0;
    }
    // the dome: the cloud narrows towards its top; the base cut flat and crisp
    float d = s * 1.35 - 0.62 * hf * hf - 0.08;
    d *= smoothstep(0.0, 0.02, h);
    e = 0.5;
    if (detail >= 1) {
        // billows: the lumps of the turrets, bigger and harder up high, softer and wispy at the base
        e = cloudNoise(q / 210.0 + vec3(0.37)).g;
        d -= (1.0 - e) * mix(0.18, 0.42, hf);
        if (detail >= 2) {
            float fine = cloudNoise(q / 90.0 + vec3(0.71, 0.13, 0.5)).g;
            d -= (1.0 - fine) * 0.12 * (0.3 + hf);
        }
    }
    return clamp(d * 2.2, 0.0, 1.0);
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

// the quality level (0-3) and whether the moon (not the sun) lights the clouds, packed in the alpha
int qualityLevel(float a) {
    return int(floor(a * 255.0 / 64.0 + 0.01));
}

bool isMoonlit(float a) {
    return mod(floor(a * 255.0 + 0.5), 64.0) >= 32.0;
}

bool isCumulus(float a) {
    return mod(floor(a * 255.0 + 0.5), 32.0) >= 16.0;
}

float henyeyGreenstein(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159 * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}
