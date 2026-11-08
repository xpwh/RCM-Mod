#version 330

#moj_import <minecraft:globals.glsl>

// The exhaust plume, drawn as light (added to what is behind it): a white-hot core out of the nozzle,
// cooling through yellow and orange to a dull red at the ragged tip, boiling with turbulence that streams
// away down the plume. Its edges are soft - each shell fades where it is seen edge-on - so it reads as a
// glowing volume of gas, not a cone. A jet's afterburner adds the blue-white core and the bright
// shock diamonds of supersonic exhaust; re-entry plasma burns orange-pink.

in vec3 relPos;
in vec3 normal;
in vec2 uv;
flat in vec4 params;
flat in vec2 size;

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    return noise(p) * 0.55 + noise(p * 2.1 + 3.7) * 0.3 + noise(p * 4.3 + 7.1) * 0.15;
}

// colour of gas this hot (0 cold .. 1+ white-hot)
vec3 solidRamp(float h) {
    vec3 c = mix(vec3(0.45, 0.06, 0.02), vec3(1.0, 0.36, 0.06), smoothstep(0.0, 0.35, h));
    c = mix(c, vec3(1.0, 0.72, 0.28), smoothstep(0.3, 0.65, h));
    return mix(c, vec3(1.0, 0.97, 0.88), smoothstep(0.62, 1.0, h));
}

vec3 jetRamp(float h) {
    vec3 c = mix(vec3(0.5, 0.12, 0.05), vec3(1.0, 0.45, 0.12), smoothstep(0.0, 0.4, h));
    c = mix(c, vec3(0.75, 0.6, 0.95), smoothstep(0.45, 0.75, h));
    return mix(c, vec3(0.85, 0.92, 1.0), smoothstep(0.75, 1.0, h));
}

vec3 plasmaRamp(float h) {
    vec3 c = mix(vec3(0.6, 0.12, 0.08), vec3(1.0, 0.45, 0.3), smoothstep(0.0, 0.5, h));
    return mix(c, vec3(1.0, 0.9, 0.85), smoothstep(0.55, 1.0, h));
}

void main() {
    float along = clamp(uv.y, 0.0, 1.0);
    float heat0 = params.r;
    int style = int(params.g * 255.0 / 80.0 + 0.5);
    float seed = params.b * 37.0;
    float alpha = params.a;
    float len = max(size.x, 0.1);
    float rad = max(size.y, 0.01);
    // seconds of game time (GameTime runs 0-1 over a day)
    float t = GameTime * 1200.0;

    // soft edges: a shell seen edge-on is thin gas, seen face-on the full depth of the plume
    vec3 v = normalize(-relPos);
    float facing = abs(dot(normalize(normal), v));
    float body = smoothstep(0.0, 0.75, facing);

    // turbulence streaming down the plume, faster in the core; coarser cells further out
    float flow = t * (10.0 + 14.0 * heat0);
    vec2 q = vec2(uv.x * (5.0 + rad * 6.0), along * len * 1.3 / max(rad * 2.0, 0.25) - flow * 0.35) + seed;
    float n = fbm(q);
    float n2 = fbm(q * 0.5 + vec2(seed, -flow * 0.08));

    // the tip frays: eaten away in ragged tongues that flicker
    float tip = smoothstep(1.0, 0.25 + 0.5 * n2, along);
    // the gas cools as it goes, the core hottest close to the nozzle
    float heat = heat0 * (1.0 - along * 0.85) * (0.7 + 0.6 * n);
    heat += heat0 * exp(-along * 7.0) * 0.6;

    vec3 col;
    float diamonds = 0.0;
    if (style == 1) {
        // supersonic jet: shock diamonds standing in the exhaust, fading downstream
        float k = along * len / max(rad * 2.4, 0.2);
        diamonds = pow(max(0.0, cos(k * 3.14159)), 6.0) * exp(-along * 2.0) * heat0 * 1.6;
        col = jetRamp(heat + diamonds * 0.5);
    } else if (style == 2) {
        col = plasmaRamp(heat);
    } else {
        col = solidRamp(heat);
    }
    // bright where hot, faint where it has cooled; boiling brightness
    float glow = (0.25 + 0.6 * min(heat, 1.2)) * (0.6 + 0.6 * n) + diamonds * 1.2;
    float a = alpha * body * tip * pow(1.0 - along, 0.5) * glow;
    if (a < 0.003) discard;
    fragColor = vec4(col * a, 1.0);
}
