#version 330

// The night sky over Minecraft's own stars and moon, added on top: the Milky Way - a band of faint light
// with knots of star clouds and a dark dust rift down its middle, brightest towards the galactic centre -
// thousands of finer stars, thickest in the band, of every colour and twinkling near the horizon; now and
// then a shooting star; and on aurora nights the northern lights, green curtains folding and rippling over
// the northern sky with red and violet at their tops. Stars and band turn with the sky; meteors and the
// aurora belong to the air and stay put.

in vec3 relPos;
flat in vec4 info;
flat in vec3 sunDir;
flat in float seconds;

out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

float fbm3(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise3(p);
        p = p * 2.03 + 7.1;
        a *= 0.5;
    }
    return v;
}

float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), f.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise2(p);
        p = p * 2.1 + 3.7;
        a *= 0.5;
    }
    return v;
}

// One layer of stars: a star at a random spot in some of the cells of a grid round the sky.
vec3 stars(vec3 d, float scale, float density, float size, float twinkle) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    float h = hash13(cell);
    if (h < 1.0 - density) {
        return vec3(0.0);
    }
    vec3 r = hash33(cell);
    vec3 at = normalize(cell + 0.5 + (r - 0.5) * 0.7);
    float dist = length(d - at);
    float k = dist / size;
    float glow = exp(-k * k);
    float bright = pow(fract(h * 17.31), 6.0) * 3.0 + 0.25;
    vec3 tint = mix(vec3(0.68, 0.78, 1.0), vec3(1.0, 0.82, 0.6), r.x);
    float flicker = 1.0 - twinkle * (0.5 + 0.5 * sin(seconds * (4.0 + r.y * 7.0) + r.z * 40.0));
    return tint * glow * bright * flicker;
}

void main() {
    vec3 rd = normalize(relPos);
    float night = info.r;
    float aurora = info.g;
    int level = int(info.b * 3.0 + 0.5);
    float above = smoothstep(-0.01, 0.12, rd.y);
    if (above <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 col = vec3(0.0);
    if (night > 0.0) {
        // into the sky's own frame: everything below turns with the sun
        float a = atan(-sunDir.x, sunDir.y);
        float c = cos(a);
        float s = sin(a);
        vec3 cd = vec3(c * rd.x + s * rd.y, -s * rd.x + c * rd.y, rd.z);

        // the Milky Way
        vec3 n = normalize(vec3(0.35, 0.25, 0.9));
        float b = dot(cd, n);
        float band = exp(-b * b / 0.014);
        vec3 centre = normalize(cross(n, vec3(0.0, 1.0, 0.0)));
        float core = pow(max(dot(cd, centre), 0.0), 3.0);
        float clouds = fbm3(cd * 6.0);
        float knots = fbm3(cd * 22.0 + 1.7);
        float dust = fbm3(cd * 9.0 + 3.1);
        // the dark rift: a wandering lane of dust along the band, patchy, wider towards the centre
        float lane = b + 0.06 * (dust - 0.5);
        float rift = exp(-lane * lane / (0.0009 + 0.0016 * core)) * smoothstep(0.3, 0.6, dust + 0.15 * knots);
        float milky = band * (0.25 + 1.1 * clouds * clouds) * (0.7 + 0.6 * knots) * (1.0 - 0.75 * rift) * (0.5 + 1.6 * core);
        vec3 milkyCol = mix(vec3(0.55, 0.62, 0.85), vec3(1.0, 0.86, 0.66), clamp(core * 1.4, 0.0, 1.0));
        col += milkyCol * milky * 0.085;

        // fine stars, thicker in the band; they twinkle more low down, through more air
        float twinkle = 0.25 + 0.5 * (1.0 - smoothstep(0.05, 0.5, rd.y));
        col += stars(cd, 110.0, 0.08 + 0.2 * band, 0.0016, twinkle) * 0.55;
        col += stars(cd + 0.37, 230.0, 0.05 + 0.35 * band, 0.0011, twinkle) * 0.35;
        if (level >= 2) {
            col += stars(cd + 0.71, 480.0, 0.6 * band, 0.0008, 0.2) * 0.25;
        }
        col *= night;

        // shooting stars: now and then a streak across the upper sky, head bright, tail fading
        for (int k = 0; k < 3; k++) {
            float period = 7.0 + float(k) * 3.3;
            float t = seconds + float(k) * 2.9;
            float slot = floor(t / period);
            float local = t - slot * period;
            vec2 seed = vec2(slot, float(k) * 13.7);
            if (hash12(seed) > 0.5) {
                continue;
            }
            float dur = 0.5 + hash12(seed + 1.3) * 0.7;
            if (local > dur) {
                continue;
            }
            vec3 start = normalize(vec3(hash12(seed + 2.1) * 2.0 - 1.0, 0.35 + hash12(seed + 3.7) * 0.7, hash12(seed + 4.9) * 2.0 - 1.0));
            vec3 across = normalize(cross(start, normalize(vec3(hash12(seed + 5.3) - 0.5, 0.2, hash12(seed + 6.1) - 0.5))));
            if (across.y > 0.0) {
                across = -across;
            }
            float path = (0.2 + hash12(seed + 7.7) * 0.3) * local / dur;
            vec3 head = normalize(start + across * path);
            vec3 tail = normalize(start + across * max(0.0, path - 0.14));
            vec3 ab = head - tail;
            float f = clamp(dot(rd - tail, ab) / max(dot(ab, ab), 1.0e-6), 0.0, 1.0);
            float dist = length(rd - (tail + ab * f));
            float life = sin(3.14159 * local / dur);
            float streak = exp(-pow(dist / 0.0011, 2.0)) * f * f * life;
            float bolide = exp(-pow(length(rd - head) / 0.0025, 2.0)) * life;
            col += (vec3(0.85, 0.92, 1.0) * streak * 2.2 + vec3(1.0, 0.95, 0.85) * bolide * 1.5) * night;
        }
    }

    // the northern lights: curtains hanging in a layer of the upper air over the north (-Z)
    if (aurora > 0.0 && rd.y > 0.0) {
        int steps = level == 0 ? 10 : level == 1 ? 16 : 24;
        vec3 acc = vec3(0.0);
        float t = seconds * 0.04;
        for (int i = 0; i < 24; i++) {
            if (i >= steps) {
                break;
            }
            float u = float(i) / float(steps);
            // each pixel starts its walk a little further up: no visible layering
            float height = 0.6 + (u + hash12(gl_FragCoord.xy + float(i)) / float(steps)) * 1.1;
            vec2 p = rd.xz / rd.y * height;
            float warp = fbm2(p * 0.5 + vec2(t * 1.5, t * 0.4));
            float fold = p.y + 1.3 + 0.45 * sin(p.x * 1.1 + t * 3.0 + warp * 4.0) + 0.3 * (warp - 0.5);
            float curtain = exp(-fold * fold * 9.0);
            float second = exp(-pow(p.y + 2.6 + 0.5 * sin(p.x * 0.8 - t * 2.0 + warp * 3.0), 2.0) * 7.0) * 0.6;
            // the rays: fine vertical folds that shimmer along the curtain, and brightenings drifting through it
            float rays = pow(vnoise2(vec2(p.x * 9.0 + warp * 6.0, t * 4.0)), 2.0) * 1.4 + 0.15;
            rays *= 0.6 + 0.8 * vnoise2(vec2(p.x * 1.5 - t * 3.0, 4.0));
            float lit = (curtain + second) * rays * (1.0 - u) * (1.0 - u * 0.3);
            vec3 hue = mix(vec3(0.15, 1.0, 0.45), vec3(0.35, 0.9, 0.7), u);
            hue = mix(hue, vec3(0.75, 0.25, 0.85), smoothstep(0.55, 0.85, u));
            hue = mix(hue, vec3(1.0, 0.25, 0.3), smoothstep(0.8, 1.0, u) * 0.6);
            acc += hue * lit;
        }
        acc /= float(steps);
        col += acc * aurora * 1.9 * smoothstep(0.02, 0.3, rd.y);
    }
    fragColor = vec4(col * above, 1.0);
}
