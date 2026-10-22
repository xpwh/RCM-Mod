#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The Sun Destroyer's star.
//
// Mode 0, its surface: the view ray is cut against the sphere; where it hits, the boiling photosphere is drawn -
// convection cells (bright granules parted by dark lanes) drifting over larger, slower currents, dark spots,
// bright faculae towards the limb, the limb darkened as on the real sun, all coloured by the gas's temperature as
// a black body - and the depth of the hit is written, so the ground, smoke and clouds sit right in front of and
// behind it. Before it ignites the collapsing core is drawn instead: black, ringed by light bent round it.
//
// Mode 1, its light in the air, drawn over the whole screen: the light the air scatters towards the eye along
// the view ray up to whatever the ray meets (the scene's depth, Sampler0, or the star) - worked out exactly, so
// the glow wraps round hills and buildings in front of it - the corona's streamers and the prominences' arcs at
// its limb, and the shock front: a shell of glowing air racing out after the nova (or, before ignition, light
// falling in towards the core).

uniform sampler2D Sampler0;

flat in vec3 centre;
flat in vec4 look;
flat in float radius;
flat in float shock;
flat in float seconds;
flat in int mode;
flat in mat4 invProj;

out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float vnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

float fbm(vec3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return s;
}

// distance to the nearest and the second nearest cell centre
vec2 cells(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    float d1 = 8.0;
    float d2 = 8.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 g = vec3(x, y, z);
                vec3 o = vec3(hash13(i + g), hash13(i + g + 17.1), hash13(i + g + 41.7));
                vec3 r = g + o - f;
                float d = dot(r, r);
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                } else if (d < d2) {
                    d2 = d;
                }
            }
        }
    }
    return sqrt(vec2(d1, d2));
}

// a black body's colour, t 0-1 for about 1800 K to 14000 K
vec3 blackbody(float t) {
    float k = mix(18.0, 140.0, clamp(t, 0.0, 1.0));
    vec3 c;
    c.r = k <= 66.0 ? 1.0 : clamp(1.2929 * pow(k - 60.0, -0.1332), 0.0, 1.0);
    c.g = k <= 66.0 ? clamp(0.3901 * log(k) - 0.6318, 0.0, 1.0) : clamp(1.1299 * pow(k - 60.0, -0.0755), 0.0, 1.0);
    c.b = k >= 66.0 ? 1.0 : (k <= 19.0 ? 0.0 : clamp(0.5432 * log(k - 10.0) - 1.1960, 0.0, 1.0));
    return c;
}

// length of the ray (from the eye, out to tEnd) inside a sphere of radius s about the centre
float inside(float tc, float cc, float s, float tEnd) {
    float h = tc * tc - (cc - s * s);
    if (h <= 0.0) {
        return 0.0;
    }
    float q = sqrt(h);
    return max(min(tc + q, tEnd) - max(tc - q, 0.0), 0.0);
}

// burning gas by heat 0-1: dull red, orange, yellow, and white only at the very hottest
vec3 fire(float h) {
    vec3 c = mix(vec3(0.35, 0.03, 0.0), vec3(1.0, 0.28, 0.03), smoothstep(0.0, 0.4, h));
    c = mix(c, vec3(1.0, 0.72, 0.25), smoothstep(0.35, 0.75, h));
    return mix(c, vec3(1.0, 0.96, 0.88), smoothstep(0.75, 1.0, h));
}

// cheaper noise for the volume: three octaves
float fbm3(vec3 p) {
    float s = 0.5 * vnoise(p);
    s += 0.25 * vnoise(p * 2.03 + vec3(1.7, 9.2, 3.1));
    s += 0.125 * vnoise(p * 4.1 + vec3(5.3, 2.8, 7.7));
    return s / 0.875;
}

// Mode 3, the nova: the star blown apart. A boiling fireball - white-hot at first, cooling through yellow and orange
// to a dull red glow inside a shell of dark smoke as it rises and swells - raymarched up to whatever stands in front
// of it; and round its foot the flat ring of plasma the star's equator throws out, racing outwards, torn into
// streaks. Drawn over the scene with ordinary blending: the smoke darkens what is behind it.
//   centre: where the star was (the ring's middle); radius: the fireball's; shock: the ring's; temp: age 0-1.
vec4 nova(vec3 rd, mat3 toWorld, float tScene, float age, float bright, float seed) {
    vec3 up = normalize(mat3(ModelViewMat) * vec3(0.0, 1.0, 0.0));
    float rise = 1.7 * age * (0.6 + 0.4 * age);
    vec3 fc = centre + up * radius * rise; // the fireball rising off the ground, faster as it goes
    float R = max(radius, 1.0);
    vec3 col = vec3(0.0);
    float T = 1.0;

    // ---- the ring, in the horizontal plane through the star's centre
    float ring = shock;
    float dn = dot(rd, up);
    if (ring > 1.0 && abs(dn) > 1.0e-4) {
        float tp = dot(centre, up) / dn;
        if (tp > 0.0 && tp < tScene) {
            vec3 q = rd * tp - centre;
            float rr = length(q);
            float w = 5.0 + ring * 0.06;
            float band = exp(-pow((rr - ring) / w, 2.0));
            float inner = exp(-pow((rr - ring * 0.93) / (w * 1.6), 2.0)) * 0.25 * step(rr, ring);
            if (band + inner > 0.002) {
                vec3 qw = toWorld * (q / max(rr, 1.0e-3));
                float ang = atan(qw.z, qw.x);
                // streaks combed outwards, flickering along the front
                float streak = fbm3(vec3(ang * 9.0, rr / ring * 3.0 - seconds * 0.6, seed));
                float rag = 0.35 + 1.3 * streak * streak;
                float fade = pow(1.0 - age, 1.6);
                vec3 hot = mix(vec3(0.75, 0.88, 1.0), vec3(1.0, 0.55, 0.22), smoothstep(0.0, 0.7, age));
                // seen edge on, a thin plate looks brighter: more of it along the ray
                float edgeOn = 1.0 / max(abs(dn), 0.12);
                vec3 e = hot * (band * rag + inner * streak) * fade * 1.4 * min(edgeOn, 4.0) * bright;
                col += e;
            }
        }
    }

    // ---- the fireball
    // bounds: the head and the stem of smoke it draws up under it
    vec3 bc = centre + up * radius * rise * 0.5;
    float tc = dot(bc, rd);
    float cc = dot(bc, bc);
    float B = R * (1.35 + rise * 0.5);
    float h = tc * tc - (cc - B * B);
    if (h > 0.0) {
        float q = sqrt(h);
        float t0 = max(tc - q, 0.0);
        float t1 = min(tc + q, tScene);
        if (t1 > t0) {
            const int STEPS = 30;
            float dt = (t1 - t0) / float(STEPS);
            float t = t0 + dt * hash13(vec3(gl_FragCoord.xy, seconds)); // dithered start: no banding
            // it thins out and is gone at the end of its life
            float density = (0.9 + 2.6 * age) * (1.0 - smoothstep(0.75, 1.0, age)) / R;
            for (int i = 0; i < STEPS; i++) {
                vec3 p = rd * t - fc;
                vec3 pw = toWorld * p / R;
                // billows rolling up and out of it
                vec3 flow = vec3(0.0, -seconds * 0.22, 0.0) + pw * (0.15 * age);
                float n = fbm3(pw * 2.4 + flow + vec3(seed));
                float n2 = fbm3(pw * 6.0 - flow * 1.7 + vec3(seed * 1.7));
                float r = length(pw);
                float shape = r + (n - 0.5) * 0.7 + (n2 - 0.5) * 0.22;
                float d = smoothstep(1.0, 0.72, shape);
                // the stem: a column of dust and smoke from the ground up into the head
                if (pw.y < 0.0 && pw.y > -rise - 0.4) {
                    float rh = length(pw.xz) / (0.28 + 0.12 * (1.0 - pw.y / (rise + 0.4)));
                    float stem = smoothstep(1.0, 0.6, rh + (n - 0.5) * 0.8) * smoothstep(0.0, 0.25, age);
                    if (stem > d) {
                        d = stem;
                        shape = 1.0; // no fire in it: smoke
                    }
                }
                if (d > 0.002) {
                    // heat: the middle hottest, the whole thing cooling with age; the billows' tops cooler
                    float heat = clamp(((1.1 - shape) * 1.5 + (n2 - 0.5) * 0.35) * pow(max(1.0 - age, 0.0), 1.3), 0.0, 1.0);
                    // deep red at the edges through orange and yellow; white only in the first instants' core
                    vec3 glow = fire(clamp(heat * (0.8 + (bright - 1.0) * 0.4), 0.0, 1.0)) * (heat * 1.5 + heat * heat * 1.2) * bright;
                    float a = 1.0 - exp(-d * density * dt * 4.0);
                    // what is not glowing is smoke: grey-brown, lit from inside by what glows
                    vec3 smoke = vec3(0.16, 0.13, 0.11) * (0.5 + 0.5 * n) + glow * 0.3;
                    vec3 c = mix(smoke, glow, smoothstep(0.02, 0.3, heat));
                    col += T * a * c;
                    T *= 1.0 - a;
                    if (T < 0.02) {
                        break;
                    }
                }
                t += dt;
            }
        }
    }
    col = 1.0 - exp(-col * 1.2);
    return vec4(col, 1.0 - T);
}

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 ndc = gl_FragCoord.xy / size * 2.0 - 1.0;
    vec4 v = invProj * vec4(ndc, 1.0, 1.0);
    vec3 rd = normalize(v.xyz / v.w);
    mat3 toWorld = transpose(mat3(ModelViewMat));

    float temp = look.r;
    float dark = look.g;
    float bright = look.b * 2.0;
    float seed = look.a * 97.0;

    float tc = dot(centre, rd);
    float cc = dot(centre, centre);
    float h = tc * tc - (cc - radius * radius);
    // mode 2: a light with no body of its own (the round in flight, the nova's flash) - nothing to hide behind it
    bool star = mode != 2 && radius > 0.05 && h >= 0.0;
    float tHit = star ? tc - sqrt(h) : 1.0e7;

    if (mode == 0) {
        // ---------------------------------------------------------------- the surface
        if (!star || tHit <= 0.05) {
            discard;
        }
        vec3 hit = rd * tHit;
        vec3 n = (hit - centre) / radius;
        vec3 nw = toWorld * n;
        float mu = clamp(dot(n, -rd), 0.0, 1.0);
        vec3 col;
        if (dark > 0.98) {
            // the collapsing core: black, the light round it bent into a thin bright ring at its edge
            float rim = pow(1.0 - mu, 5.0);
            col = vec3(1.0, 0.82, 0.6) * rim * 1.4 * (0.7 + 0.3 * vnoise(nw * 9.0 + seconds * 3.0));
        } else {
            float flow = seconds * 0.35;
            vec3 q = nw * 6.5 * sqrt(max(radius, 1.0) / 34.0) + vec3(seed); // more, not bigger, cells on a bigger star
            vec3 warp = vec3(fbm(q * 0.45 + flow * 0.3), fbm(q * 0.45 + 7.3 - flow * 0.2), fbm(q * 0.45 + 13.1 + flow * 0.25));
            vec2 cl = cells(q * 1.7 + warp * 2.2 + vec3(0.0, flow, 0.0));
            float gran = smoothstep(0.0, 0.7, cl.y - cl.x);           // bright granules, dark lanes between them
            vec2 cl2 = cells(q * 4.1 + warp * 3.0 - vec3(flow * 1.3));
            float fine = smoothstep(0.0, 0.6, cl2.y - cl2.x);
            float big = fbm(nw * 2.2 + vec3(seed * 0.3, flow * 0.1, 0.0));
            float spotN = fbm(nw * 3.1 + vec3(3.7, seed, flow * 0.04));
            float umbra = smoothstep(0.66, 0.72, spotN);
            float penumbra = smoothstep(0.6, 0.68, spotN) - umbra;
            float faculae = smoothstep(0.55, 0.75, big) * pow(1.0 - mu, 1.5);
            // the limb darkened: the eye sees into cooler, higher gas there
            float limb = 0.3 + 0.7 * pow(mu, 0.5);
            float t = temp * (0.84 + 0.1 * gran + 0.06 * fine) - umbra * 0.35 - penumbra * 0.15 - (1.0 - limb) * 0.22 + faculae * 0.1;
            float lum = bright * limb * (0.62 + 0.3 * gran + 0.12 * fine) * (1.0 - 0.75 * umbra - 0.35 * penumbra) + faculae * 0.5;
            // the surface heaves: long slow waves sweep across it
            lum *= 0.9 + 0.1 * sin(dot(nw, vec3(3.0, 5.0, 4.0)) * 2.0 - seconds * 1.7 + big * 4.0);
            col = blackbody(t) * lum * 1.25;
            col = 1.0 - exp(-col);
            col = mix(col, vec3(0.0), dark);
        }
        vec4 clip = ProjMat * vec4(hit, 1.0);
        gl_FragDepth = clip.z / clip.w * 0.5 + 0.5;
        fragColor = vec4(col, 1.0);
        return;
    }

    // -------------------------------------------------------------------- its light in the air
    float depth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    float tScene = 1.0e7;
    if (depth < 1.0) {
        vec4 sv = invProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        tScene = length(sv.xyz / sv.w);
    }
    if (mode == 3) {
        fragColor = nova(rd, toWorld, tScene, temp, bright, seed);
        return;
    }
    if (mode != 2 && radius > 0.05 && cc < radius * radius) {
        // inside it: nothing but light
        fragColor = vec4(blackbody(temp) * 0.95 + 0.05, 1.0);
        return;
    }
    float tEnd = min(tScene, tHit);
    float b = sqrt(max(cc - tc * tc, 1.0e-4));
    vec3 sunCol = blackbody(temp * 0.9);
    vec3 col = vec3(0.0);

    // the light the air between the eye and what the ray meets scatters towards the eye: R^2 / d^2 along the ray
    if (radius > 0.05) {
        float scatter = radius * radius / b * (atan((tEnd - tc) / b) - atan(-tc / b));
        float far = exp(-max(tc, 0.0) / 3500.0);
        col += sunCol * scatter * (0.22 / max(radius, 1.0)) * bright * far; // the same glow at the limb whatever its size
        // a tighter, brighter halo hugging it
        float above = (b - radius) / radius;
        if (above > 0.0 && tc > 0.0 && tc < tScene + radius) {
            vec3 perp = normalize(rd * tc - centre);
            vec3 pw = toWorld * perp;
            float halo = exp(-above * 3.2);
            // the corona's streamers, combed outwards, slowly changing
            float streak = fbm(pw * 5.0 + vec3(seed, -seconds * 0.12, seconds * 0.05));
            float fine = fbm(pw * 17.0 + vec3(seconds * 0.2, seed, 0.0));
            float corona = halo * (0.35 + 1.4 * streak * streak + 0.4 * fine * streak) * exp(-above * 0.6);
            // prominences: thin bright arcs of gas standing on the limb
            float ridge = 1.0 - abs(vnoise(pw * 7.0 + vec3(0.0, seconds * 0.15, seed)) * 2.0 - 1.0);
            float ridge2 = 1.0 - abs(vnoise(pw * 13.0 - vec3(seconds * 0.1, 0.0, seed)) * 2.0 - 1.0);
            float prom = pow(ridge, 10.0) * (0.6 + 0.6 * ridge2) * exp(-above * 7.0) * smoothstep(0.0, 0.02, above);
            col += sunCol * corona * 0.55 * bright * (1.0 - dark);
            col += vec3(1.0, 0.32, 0.08) * prom * 1.8 * bright * (1.0 - dark);
        }
    }

    // the shock front: a shell of glowing air racing outwards (before ignition: light falling inwards)
    if (shock > 0.5) {
        float w = 4.0 + shock * 0.06;
        float len = inside(tc, cc, shock, tEnd) - inside(tc, cc, max(shock - w, 0.0), tEnd);
        if (len > 0.0) {
            // where on the shell the ray passes, for the front's ragged, turbulent face
            float tm = clamp(tc - sqrt(max(shock * shock - b * b, 0.0)), 0.0, tEnd);
            vec3 sp = toWorld * normalize(rd * tm - centre);
            float rag = 0.55 + 0.9 * fbm(sp * 6.0 + vec3(seed, seconds * 0.6, 0.0));
            float edge = len / (w * 2.2);
            vec3 hot;
            float k;
            if (dark > 0.0) {
                hot = vec3(0.65, 0.8, 1.0);
                k = 0.7 * dark;
            } else {
                float age = clamp(shock / 650.0, 0.0, 1.0);
                hot = mix(vec3(1.0, 0.95, 0.85), vec3(1.0, 0.45, 0.15), smoothstep(0.0, 0.6, age));
                k = 1.6 * pow(1.0 - age, 1.4);
            }
            col += hot * edge * rag * k;
        }
    }
    col = 1.0 - exp(-col);
    fragColor = vec4(col, 0.0);
}
