#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Screen-space light on the world, from copies of the world's depth (and picture).
//
// SHADE (before the translucent terrain, multiplied into the picture):
//   sun shadows - every pixel walks towards the sun through the depth of the picture; where something
//   on screen stands between it and the sun it is in shadow (trees, houses, entities, a missile on its
//   pad all throw them, and they move with the sun); faces turned from the sun darken too.
//   ambient occlusion - points round each pixel, in the half-sphere over its surface, are tested against
//   the depth: the more of them are buried, the deeper the corner, the darker it is.
//
// WATER (after it, replacing what the water drew): the surface's waves roll with the time; a ray mirrored
// off them is walked through the picture to find what it sees (the land, trees, lights round it), the sky
// where it leaves the screen; the mirror counts for more the flatter you look (Fresnel); the sun glitters;
// below, the bottom is seen through the water - shifted by the waves, fading into the water's colour the
// deeper it lies.

uniform sampler2D Sampler0; // depth of the opaque world (before the translucent terrain)
#ifdef WATER
uniform sampler2D Sampler1; // depth with the water
uniform sampler2D Sampler2; // picture of the opaque world
uniform sampler2D Sampler3; // picture with the water as Minecraft drew it
#endif

in vec3 relPos;
flat in vec4 params;
flat in vec2 cameraXZ;
flat in ivec2 data;
flat in vec3 sunDir;
flat in mat4 invViewProj;
flat in mat4 viewProj;

out vec4 fragColor;

vec3 unproject(vec2 uv, float depth) {
    vec4 w = invViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return w.xyz / w.w;
}

// a camera-relative point on screen: uv and whether it is in front of the eye
vec3 project(vec3 p) {
    vec4 c = viewProj * vec4(p, 1.0);
    if (c.w <= 0.05) return vec3(-1.0);
    return vec3(c.xy / c.w * 0.5 + 0.5, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// distance from the eye to the opaque world at a screen position
float sceneDistance(vec2 uv) {
    return length(unproject(uv, textureLod(Sampler0, uv, 0.0).r));
}

#ifdef SHADE
void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = textureLod(Sampler0, uv, 0.0).r;
    vec3 p = unproject(uv, min(depth, 0.99999));
    vec3 n = cross(dFdx(p), dFdy(p));
    if (depth >= 1.0) discard;
    float dist = length(p);
    vec3 rd = p / max(dist, 1.0e-4);
    n = dot(n, n) > 1.0e-12 ? normalize(n) : -rd;
    if (dot(n, rd) > 0.0) n = -n;
    float shadowStrength = params.r;
    float aoStrength = params.g;
    int quality = data.x;
    // the same pattern for every pixel: a random one would leave a speckle of dots everywhere (there is no
    // blur pass to smooth it out), this one gives clean, even shading
    float jitter = 0.5;
    float shade = 1.0;

    // ---- ambient occlusion
    if (aoStrength > 0.0 && dist < 96.0) {
        int samples = quality >= 3 ? 16 : quality == 2 ? 14 : 10;
        float radius = 0.7;
        vec3 t = normalize(cross(n, abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 b = cross(n, t);
        float occluded = 0.0;
        for (int i = 0; i < 16; i++) {
            if (i >= samples) break;
            // spread over the half-sphere, denser near the surface; turned per pixel
            float fi = float(i) + jitter;
            float a = fi * 2.399963 + jitter * 6.283;
            float h = fract(fi * 0.618034 + jitter);
            float r = sqrt(1.0 - h * h);
            float len = radius * mix(0.15, 1.0, fract(fi * 0.7548777) * fract(fi * 0.7548777));
            vec3 q = p + n * 0.04 + (t * cos(a) * r + b * sin(a) * r + n * h) * len;
            vec3 s = project(q);
            if (s.z < 0.0 || s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0) continue;
            float scene = sceneDistance(s.xy);
            float here = length(q);
            float gap = here - scene;
            // buried - but only by what is close, not by something far in front of it
            occluded += smoothstep(0.02, 0.12, gap) * (1.0 - smoothstep(radius, radius * 2.5, gap));
        }
        float ao = 1.0 - occluded / float(samples);
        shade *= 1.0 - aoStrength * (1.0 - ao) * (1.0 - smoothstep(48.0, 96.0, dist));
    }

    // ---- sun shadows: several rays to points spread over the sun's disc (a little wider than the real
    // one, so it shows), each a clean walk with no random start - the fraction that gets through gives a
    // soft half-shadow that widens the further the shadow lies from what casts it, and no speckle
    vec3 tint = vec3(1.0);
    if (shadowStrength > 0.0 && dist < 128.0) {
        float facing = dot(n, sunDir);
        float lit = 1.0;
        if (facing > 0.0) {
            int rays = quality >= 3 ? 5 : quality == 2 ? 3 : 1;
            int steps = quality >= 3 ? 30 : quality == 2 ? 26 : 22;
            float grow = quality >= 3 ? 1.15 : quality == 2 ? 1.16 : 1.2;
            vec3 su = normalize(cross(sunDir, abs(sunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
            vec3 sv = cross(sunDir, su);
            const float SPREAD = 0.018; // radians: the disc the rays sample
            vec3 start = p + n * (0.05 + dist * 0.002);
            float through = 0.0;
            for (int r = 0; r < 5; r++) {
                if (r >= rays) break;
                vec3 dir = sunDir;
                if (r > 0) {
                    float a = float(r - 1) * 6.2831853 / float(rays - 1) + 0.4;
                    dir = normalize(sunDir + (su * cos(a) + sv * sin(a)) * SPREAD);
                }
                float travelled = 0.0;
                float stepLen = 0.05;
                float hit = 0.0;
                for (int i = 0; i < 30; i++) {
                    if (i >= steps) break;
                    travelled += stepLen;
                    stepLen *= grow;
                    vec3 q = start + dir * travelled;
                    vec3 sp = project(q);
                    if (sp.z < 0.0 || sp.x < 0.0 || sp.y < 0.0 || sp.x > 1.0 || sp.y > 1.0) break;
                    float scene = sceneDistance(sp.xy);
                    float here = length(q);
                    float gap = here - scene;
                    // something solid in front of this point of the walk, and not far in front of it (how
                    // thick what casts the shadow is can only be guessed: up to a couple of blocks)
                    if (gap > 0.06 + here * 0.004 && gap < 2.5 + travelled * 0.15) {
                        hit = 1.0;
                        break;
                    }
                    if (travelled > 48.0) break;
                }
                through += 1.0 - hit;
            }
            lit = through / float(rays);
        }
        // faces turned from the sun are in their own shade, softer than a cast shadow (the sky lights them)
        float face = mix(0.45, 1.0, smoothstep(-0.05, 0.25, facing));
        lit = min(lit, face);
        float k = shadowStrength * (1.0 - lit) * (1.0 - smoothstep(80.0, 128.0, dist));
        // what lights a shadow is the blue sky, not the sun: shadows are cool, more so the stronger they are
        tint = vec3(1.0) - k * vec3(1.0, 0.9, 0.68);
    }
    vec3 result = vec3(shade) * tint;
    if (min(result.r, min(result.g, result.b)) > 0.995) discard;
    fragColor = vec4(result, 1.0);
}
#endif

#ifdef WATER
float waveHeight(vec2 x, float t) {
    float h = 0.0;
    h += sin(dot(x, vec2(0.8, 0.6)) * 1.1 + t * 1.3) * 0.5;
    h += sin(dot(x, vec2(-0.5, 0.86)) * 1.9 + t * 1.9) * 0.28;
    h += sin(dot(x, vec2(0.97, -0.25)) * 3.3 + t * 2.7) * 0.14;
    h += sin(dot(x, vec2(-0.2, -0.98)) * 5.1 + t * 3.6) * 0.07;
    h += sin(dot(x, vec2(0.6, -0.8)) * 8.7 + t * 4.9) * 0.035;
    return h;
}

vec3 waveNormal(vec2 x, float t, float strength) {
    float e = 0.05;
    float hx = waveHeight(x + vec2(e, 0.0), t) - waveHeight(x - vec2(e, 0.0), t);
    float hz = waveHeight(x + vec2(0.0, e), t) - waveHeight(x - vec2(0.0, e), t);
    return normalize(vec3(-hx / (2.0 * e) * strength, 1.0, -hz / (2.0 * e) * strength));
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float dOpaque = textureLod(Sampler0, uv, 0.0).r;
    float dWater = textureLod(Sampler1, uv, 0.0).r;
    vec3 pw = unproject(uv, min(dWater, 0.99999));
    vec3 n = cross(dFdx(pw), dFdy(pw));
    // only where something translucent lies in front of the opaque world, and lies flat (water, not glass)
    if (dWater >= dOpaque - 1.0e-7 || dWater >= 1.0) discard;
    n = dot(n, n) > 1.0e-12 ? normalize(n) : vec3(0.0, 1.0, 0.0);
    if (abs(n.y) < 0.85) discard;
    vec3 drawn = textureLod(Sampler3, uv, 0.0).rgb;
    vec3 under = textureLod(Sampler2, uv, 0.0).rgb;
    // what Minecraft drew there is water if it tints what lies below blue-green (glass and ice mostly don't)
    float tintShift = (drawn.b + drawn.g * 0.5) - (drawn.r * 1.5);
    if (tintShift < 0.02 && length(drawn - under) < 0.04) discard;

    float dist = length(pw);
    vec3 v = pw / max(dist, 1.0e-4);
    float t = GameTime * 1200.0;
    vec2 world = pw.xz + cameraXZ;
    float rain = params.g;
    // the waves flatten out into the distance (they would only shimmer there)
    float waves = mix(0.22, 0.05, smoothstep(8.0, 64.0, dist)) * (1.0 + rain * 0.8);
    vec3 nw = waveNormal(world, t, waves);
    if (n.y < 0.0) nw.y = -nw.y;

    // ---- reflection: walk the mirrored ray through the picture
    vec3 r = reflect(v, nw);
    if (r.y < 0.02) r.y = 0.02;
    r = normalize(r);
    vec3 zenith = vec3(float(data.x & 255), float((data.x >> 8) & 255), float(data.y & 255)) / 255.0;
    vec3 horizon = FogColor.rgb;
    vec3 sky = mix(horizon, zenith, smoothstep(0.0, 0.6, r.y));
    vec3 reflected = sky;
    float jitter = hash(gl_FragCoord.xy);
    vec3 q = pw + r * (0.2 + jitter * 0.3);
    float stepLen = 0.4 + dist * 0.01;
    for (int i = 0; i < 32; i++) {
        q += r * stepLen;
        stepLen *= 1.12;
        vec3 s = project(q);
        if (s.z < 0.0 || s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0) break;
        float d = textureLod(Sampler0, s.xy, 0.0).r;
        if (d >= 1.0) continue;
        float scene = length(unproject(s.xy, d));
        float here = length(q);
        float gap = here - scene;
        if (gap > 0.0 && gap < stepLen * 2.5 + 0.5) {
            // found: fade out towards the edges of the screen, where the walk runs out
            vec2 edge = min(s.xy, 1.0 - s.xy);
            float fade = smoothstep(0.0, 0.08, min(edge.x, edge.y));
            reflected = mix(sky, textureLod(Sampler2, s.xy, 0.0).rgb, fade);
            break;
        }
    }

    // ---- the sun glittering on the waves
    float glitter = params.r;
    float spec = pow(max(dot(r, sunDir), 0.0), 900.0) * 6.0 + pow(max(dot(r, sunDir), 0.0), 60.0) * 0.25;
    vec3 sunGlint = vec3(1.0, 0.95, 0.85) * spec * glitter;

    // ---- refraction: the bottom, seen through the water and shifted by the waves
    vec2 bent = uv + nw.xz * 0.03 / max(1.0, dist * 0.15);
    float dBent = textureLod(Sampler0, bent, 0.0).r;
    if (dBent < dWater) bent = uv; // what lies there is above the water: don't bend onto it
    vec3 bottomPos = unproject(bent, textureLod(Sampler0, bent, 0.0).r);
    float depthBelow = max(0.0, length(bottomPos) - dist);
    vec3 bottom = textureLod(Sampler2, bent, 0.0).rgb;
    // the water's own colour, as Minecraft tinted it (biome by biome), brought out of its blend
    vec3 waterCol = clamp(drawn * 1.1, 0.0, 1.0);
    vec3 absorb = exp(-depthBelow * vec3(0.45, 0.16, 0.1));
    vec3 seenBelow = mix(waterCol * 0.55, bottom * mix(vec3(1.0), waterCol * 1.6, 0.35), absorb);

    // ---- the mirror against the window: Fresnel
    float cosv = clamp(dot(-v, nw), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
    fresnel = clamp(fresnel * (1.0 - 0.3 * rain), 0.0, 1.0);
    vec3 col = mix(seenBelow, reflected, fresnel) + sunGlint;

    // into the distance it becomes what Minecraft drew (with its fog)
    float far = smoothstep(FogRenderDistanceStart * 0.8, FogRenderDistanceEnd, length(pw.xz));
    col = mix(col, drawn, far);
    fragColor = vec4(col, 1.0);
}
#endif
