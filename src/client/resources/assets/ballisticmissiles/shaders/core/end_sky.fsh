#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// The sky of the End: no air, no sun - the black of space, thick with stars; drifting veils of nebula in violet,
// magenta and teal with dark lanes of dust across them; a few far galaxies, their spiral arms tilted to us; and,
// slowly folding and unfolding along the sky, ribbons of violet light like an aurora with nothing to cause it.

in vec3 relPos;

out vec4 fragColor;

float esHash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float esNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(esHash(i), esHash(i + vec3(1, 0, 0)), f.x), mix(esHash(i + vec3(0, 1, 0)), esHash(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(esHash(i + vec3(0, 0, 1)), esHash(i + vec3(1, 0, 1)), f.x), mix(esHash(i + vec3(0, 1, 1)), esHash(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

float esFbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * esNoise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

// stars: one at a random spot in each cell of a grid laid over the directions, most faint, a few bright
vec3 stars(vec3 rd, float scale, float time) {
    vec3 p = rd * scale;
    vec3 cell = floor(p);
    vec3 col = vec3(0.0);
    for (int dx = 0; dx <= 1; dx++) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = 0; dz <= 1; dz++) {
                vec3 c = cell + vec3(dx, dy, dz) - 0.5;
                vec3 c0 = floor(c);
                float h = esHash(c0);
                if (h > 0.55) continue;
                vec3 star = c0 + vec3(esHash(c0 + 1.3), esHash(c0 + 2.7), esHash(c0 + 4.1));
                vec3 dir = normalize(star);
                float d = length(rd - dir) * scale;
                float mag = pow(esHash(c0 + 7.7), 6.0);
                float temp = esHash(c0 + 9.1);
                vec3 tint = mix(vec3(1.0, 0.75, 0.6), vec3(0.65, 0.78, 1.0), temp);
                float tw = 0.85 + 0.15 * sin(time * (3.0 + 7.0 * h) + h * 50.0);
                col += tint * (0.25 + 3.0 * mag) * exp(-d * d * 900.0) * tw;
            }
        }
    }
    return col;
}

// a galaxy round direction c, its disc tilted (axis n), spiral arms, a bright core
vec3 galaxy(vec3 rd, vec3 c, vec3 n, float size, vec3 tint, float seed) {
    float cosA = dot(rd, c);
    if (cosA < 0.9) return vec3(0.0);
    vec3 t1 = normalize(cross(c, n));
    vec3 t2 = cross(c, t1);
    vec2 q = vec2(dot(rd, t1), dot(rd, t2)) / size;
    q.y /= 0.35 + 0.5 * fract(seed * 7.3);          // tilted: an ellipse
    float r = length(q);
    float th = atan(q.y, q.x);
    float arms = 0.5 + 0.5 * cos(2.0 * th - 6.0 * log(r + 0.05) + seed * 10.0);
    float disc = exp(-r * 3.2) * (0.35 + 0.65 * pow(arms, 2.0));
    float core = exp(-r * r * 60.0);
    return tint * disc * 0.35 + vec3(1.0, 0.92, 0.8) * core * 0.6;
}

void main() {
    vec3 rd = normalize(relPos);
    // GameTime runs 0-1 over 20 minutes; whole cycles only, so nothing jumps when it wraps
    float t = GameTime * 6.28318;

    vec3 col = vec3(0.004, 0.003, 0.009);
    // the nebula: veils of glowing gas, dark dust lanes cutting across
    vec3 q = rd * 2.2;
    vec3 warp = vec3(esFbm(q + 3.0), esFbm(q + 11.0), esFbm(q + 19.0)) - 0.5;
    float gas = esFbm(q * 1.4 + warp * 1.6 + vec3(sin(t), 0.0, cos(t)) * 0.08);
    float gas2 = esFbm(q * 3.1 - warp * 2.0 + 7.0);
    float dust = smoothstep(0.45, 0.7, esFbm(q * 2.6 + warp * 2.5 + 31.0));
    float veil = smoothstep(0.36, 0.78, gas) * (0.5 + 0.5 * gas2);
    vec3 neb = mix(vec3(0.32, 0.08, 0.45), vec3(0.6, 0.12, 0.42), smoothstep(0.3, 0.7, gas2));
    neb = mix(neb, vec3(0.05, 0.32, 0.36), smoothstep(0.62, 0.8, esFbm(q * 1.1 + 53.0)));
    col += neb * veil * 0.62 * (1.0 - 0.8 * dust) + neb * pow(veil, 3.0) * 0.35;

    // stars: fine and many, a few bright ones; fewer behind the dust
    col += stars(rd, 140.0, t * 40.0) * (1.0 - 0.75 * dust) * 0.55;
    col += stars(rd, 55.0, t * 40.0) * (1.0 - 0.6 * dust) * 0.7;

    // far galaxies
    col += galaxy(rd, normalize(vec3(0.55, 0.42, -0.72)), normalize(vec3(0.2, 1.0, 0.3)), 0.08, vec3(0.75, 0.7, 1.0), 0.31);
    col += galaxy(rd, normalize(vec3(-0.68, 0.25, 0.69)), normalize(vec3(1.0, 0.4, 0.1)), 0.05, vec3(1.0, 0.8, 0.7), 0.77);
    col += galaxy(rd, normalize(vec3(0.1, 0.86, 0.5)), normalize(vec3(0.3, 0.2, 1.0)), 0.11, vec3(0.8, 0.75, 1.0), 0.53);

    // the ribbons: curtains of violet light folding slowly along the sky, rayed, brightest at their lower edge
    float az = atan(rd.z, rd.x);
    float elev = asin(clamp(rd.y, -1.0, 1.0));
    vec3 ribbons = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float base = 0.25 + 0.22 * fi + 0.07 * sin(az * (2.0 + fi) + t * (1.0 + fi) + fi * 2.1) + 0.04 * sin(az * 7.0 - t * 3.0 + fi);
        float above = elev - base;
        float curtain = smoothstep(-0.015, 0.0, above) * exp(-max(above, 0.0) * (9.0 + 4.0 * fi));
        float rays = 0.45 + 0.55 * esNoise(vec3(az * 38.0, t * 6.0 + fi * 10.0, fi));
        float fold = 0.4 + 0.6 * smoothstep(0.2, 0.8, esNoise(vec3(az * 3.0 + t * 2.0, fi * 5.0, 0.0)));
        vec3 rc = mix(vec3(0.55, 0.2, 1.0), vec3(1.0, 0.35, 0.8), smoothstep(0.0, 0.25, above));
        ribbons += rc * curtain * rays * fold * (0.4 - 0.09 * fi);
    }
    col += ribbons;

    // below the horizon: the void, into the End's own fog
    float below = 1.0 - smoothstep(-0.12, 0.02, rd.y);
    col = mix(col, FogColor.rgb * 0.6, below);
    fragColor = vec4(col, 1.0);
}
