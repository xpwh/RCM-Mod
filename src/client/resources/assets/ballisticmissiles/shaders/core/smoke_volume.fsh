#version 330

// Volumetric smoke, raymarched through the puffs of the smoke field near the camera.
//
// Every puff is a soft ball of smoke (densest in the middle, thinning to nothing at its edge) whose inside is
// broken up by drifting, swirling noise, so a cloud of them reads as one billowing mass, not as balls. For
// each pixel the view ray is cut against every puff in front of what the pixel shows (the scene's depth, so
// smoke wraps round the ground, walls and everything standing in it, and swallows you when you walk into it);
// within each it is sampled a few times, and the light is added front to back: the sky's light everywhere,
// the sun's on the side facing it and through thin edges in back-light (forward scattering), less of it the
// more smoke lies between the puff and the sun (worked out per puff on the CPU), and the glow of fresh hot
// exhaust or a fire's light it carries.
//
// Sampler0: the scene's depth. Sampler1: the puffs, one column each, nearest first:
//   row 0: x (16 bit), y (16 bit)       relative to the camera, +128, * 256
//   row 1: z (16 bit), radius (16 bit)  radius * 256
//   row 2: colour (rgb, already lit by the day and nearby lights), density (a)
//   row 3: sunlight reaching it (r), seed (g), glow (b), age (a)
//   row 4: light it gives off or carries from lamps, fires and flashes near it (rgb)
// Column 255 holds the camera's position in the world (wrapped), for the swirl to stay put in the world.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec3 relPos;
flat in vec4 ambient;
flat in float seconds;
flat in int count;
flat in int samples;
flat in vec3 sunDir;
flat in mat4 invViewProj;

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

float word(vec2 hl) {
    return floor(hl.x * 255.0 + 0.5) * 256.0 + floor(hl.y * 255.0 + 0.5);
}

void main() {
    if (count <= 0) {
        discard;
    }
    ivec2 px = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(Sampler0, px, 0).r;
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec3 ndc = vec3(gl_FragCoord.xy / size * 2.0 - 1.0, depth * 2.0 - 1.0);
    vec4 wp = invViewProj * vec4(ndc, 1.0);
    vec3 scene = wp.xyz / wp.w;
    vec3 rd = depth >= 1.0 ? normalize(relPos) : normalize(scene);
    float far = depth >= 1.0 ? 1.0e6 : length(scene);

    // the sun's colour: warm and weak low down, white high up; how it scatters towards the eye
    float sunUp = clamp(sunDir.y * 3.0, 0.0, 1.0);
    vec3 sunCol = mix(vec3(1.0, 0.55, 0.3), vec3(1.0, 0.96, 0.9), sunUp) * ambient.a * 0.85;
    float cosA = dot(rd, sunDir);
    float g = 0.45;
    float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosA, 1.5) / 12.566;
    float phase = 0.7 + 3.0 * hg;

    // the camera in the world (wrapped), so the swirl stays put in the world while the camera moves
    vec4 cw0 = texelFetch(Sampler1, ivec2(255, 0), 0);
    vec4 cw1 = texelFetch(Sampler1, ivec2(255, 1), 0);
    vec3 camW = vec3(word(cw0.rg), word(cw0.ba), word(cw1.rg)) / 128.0;

    // ---- the puffs this ray passes through (up to 16, nearest first), and the stretch of ray they cover
    const int MAX_HIT = 16;
    vec4 hitC[MAX_HIT];      // centre, radius
    vec4 hitLook[MAX_HIT];   // colour, extinction
    vec3 hitLight[MAX_HIT];  // sunlight reaching it, glow, age
    vec3 hitEmit[MAX_HIT];   // light from lamps and fires round it
    vec2 hitT[MAX_HIT];
    int hits = 0;
    float tMin = 1.0e9;
    float tMax = 0.0;
    for (int i = 0; i < 255; i++) {
        if (i >= count || hits >= MAX_HIT) {
            break;
        }
        vec4 r0 = texelFetch(Sampler1, ivec2(i, 0), 0);
        vec4 r1 = texelFetch(Sampler1, ivec2(i, 1), 0);
        vec3 c = vec3(word(r0.rg), word(r0.ba), word(r1.rg)) / 256.0 - 128.0;
        float rad = word(r1.ba) / 256.0;
        float b = dot(rd, c);
        float h = b * b - (dot(c, c) - rad * rad);
        if (h <= 0.0) {
            continue;
        }
        float s = sqrt(h);
        float t0 = max(b - s, 0.0);
        float t1 = min(b + s, far);
        if (t1 <= t0) {
            continue;
        }
        vec4 r2 = texelFetch(Sampler1, ivec2(i, 2), 0);
        vec4 r3 = texelFetch(Sampler1, ivec2(i, 3), 0);
        hitC[hits] = vec4(c, rad);
        hitLook[hits] = vec4(r2.rgb, r2.a * 3.0 / max(rad, 0.25));
        hitLight[hits] = r3.rba;
        hitEmit[hits] = texelFetch(Sampler1, ivec2(i, 4), 0).rgb;
        hitT[hits] = vec2(t0, t1);
        tMin = min(tMin, t0);
        tMax = max(tMax, t1);
        hits++;
    }
    if (hits == 0) {
        discard;
    }

    // ---- march through all of them at once: their densities add up into one body of smoke
    // fine steps through the front, where the smoke is seen (big even steps showed as shells round the camera),
    // coarser ones through what lies behind
    int steps = samples * 6;
    float range = tMax - tMin;
    float dtFine = min(range / float(steps), 0.45);
    float dtCoarse = max(dtFine, range / float(steps)) * 1.5;
    // interleaved gradient noise: a fine, even dither of where the steps fall, so no slices show
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 light = vec3(0.0);
    float trans = 1.0;
    float t = seconds;
    vec3 drift = vec3(t * 0.35, t * 0.12, -t * 0.28);
    float tt = tMin + jitter * dtFine;
    for (int k = 0; k < 72; k++) {
        if (tt > tMax || trans < 0.01) {
            break;
        }
        float dt = k < steps ? dtFine : dtCoarse;
        float tNext = tt + dt;
        vec3 p = rd * tt;
        float cover = 0.0;
        float sigma = 0.0;
        vec3 base = vec3(0.0);
        float sun = 0.0;
        float glow = 0.0;
        vec3 emit = vec3(0.0);
        float age = 0.0;
        float toSun = 0.0;
        float up = 0.0;
        for (int j = 0; j < MAX_HIT; j++) {
            if (j >= hits) {
                break;
            }
            if (tt < hitT[j].x || tt > hitT[j].y) {
                continue;
            }
            vec3 q = (p - hitC[j].xyz) / hitC[j].w;
            float d2 = dot(q, q);
            if (d2 >= 1.0) {
                continue;
            }
            float sh = 1.0 - d2;
            sh *= sh;
            float w = sh * hitLook[j].a;
            cover += sh;
            sigma += w;
            base += hitLook[j].rgb * w;
            sun += hitLight[j].x * w;
            glow += hitLight[j].y * w * (1.0 - d2);
            emit += hitEmit[j] * w;
            age += hitLight[j].z * w;
            // the smoke of this puff between here and the sun
            toSun += sh * max(0.0, 1.0 - dot(q, sunDir)) * hitC[j].w * hitLook[j].a;
            up += q.y * w;
        }
        if (sigma <= 0.0) {
            tt = tNext;
            continue;
        }
        base /= sigma;
        sun /= sigma;
        glow /= sigma;
        emit /= sigma;
        age /= sigma;
        up /= sigma;
        toSun /= max(cover, 1.0e-3);
        // the swirl, anchored in the world, boiling slowly: billows where the smoke is thick, wisps where it thins
        vec3 wq = (p + camW) * 0.55 + drift * 0.12;
        vec3 warp = vec3(vnoise(wq * 0.5 + 3.1), vnoise(wq * 0.5 + 7.3), vnoise(wq * 0.5 + 1.9)) - 0.5;
        vec3 nq = wq + warp * 1.6;
        float n = vnoise(nq) * 0.55 + vnoise(nq * 2.1 + 5.2) * 0.3 + vnoise(nq * 4.6 + 9.1) * 0.15;
        // and the big, slow billows of a large cloud
        n = mix(n, vnoise(nq * 0.27 + 2.7), 0.35);
        n = smoothstep(0.22, 0.78, n);
        float erode = mix(0.8, 1.0, age);
        // eaten away hardest at its edges, so they fray into wisps instead of ending as a smooth dome
        float shape = min(cover, 1.0);
        float d = clamp((shape * 0.95 + 0.06 - (1.0 - n) * erode * (1.2 - 0.5 * shape)) / 0.35, 0.0, 1.0);
        if (d <= 0.0) {
            tt = tNext;
            continue;
        }
        float ext = sigma / max(cover, 1.0e-3) * d;
        float a = 1.0 - exp(-ext * dt);
        // light: the sun, thinned by the smoke between here and it and brightened by the light scattered just
        // under the surface ("powder"); the sky, more on top than underneath; the glow of what is hot
        float sunHere = exp(-toSun * 0.55) * (0.35 + 0.65 * (1.0 - exp(-d * 3.0))) * 1.35;
        float sky = 0.35 + 0.45 * clamp(up * 0.5 + 0.5, 0.0, 1.0);
        vec3 col = base * (ambient.rgb * sky + sunCol * sun * phase * sunHere * 1.7);
        col += vec3(1.0, 0.5, 0.18) * glow * 1.6 + base * emit * 1.4;
        light += trans * a * col;
        trans *= 1.0 - a;
        tt = tNext;
    }
    float alpha = 1.0 - trans;
    if (alpha < 0.003) {
        discard;
    }
    fragColor = vec4(light, alpha);
}
