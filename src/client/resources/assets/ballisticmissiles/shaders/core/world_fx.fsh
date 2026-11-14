#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Screen-space light on the world, from copies of the world's depth (and picture).
//
// SHADE (before the translucent terrain, multiplied into the picture):
//   sun shadows - every pixel walks towards the sun over a heightmap of the world round the camera (the
//   top of every column: ground, roofs, walls, trees): where the line passes beneath a column top it is
//   in shadow. That does not depend on what is on screen - a roof shades the room under it wherever you
//   look, the sun falls in through the doorway, hills and houses throw long shadows at evening. A few
//   short steps through the depth of the picture add what the map does not hold: entities, small edges.
//   Several rays over the sun's disc give soft half-shadows; shadows are cool, lit by the blue sky.
//   ambient occlusion - points round each pixel, in the half-sphere over its surface, are tested against
//   the depth: the more of them are buried, the deeper the corner, the darker it is.
//
// UNDER (looking from under water, replacing the picture): sunlight through the waves - the bright, wobbling
// net of caustics on the bottom and on whatever lies there, shafts of light slanting down from the surface,
// the distance blurring into the water's blue.
//
// WATER (after it, replacing what the water drew): the surface's waves roll with the time; a ray mirrored
// off them is walked through the picture to find what it sees (the land, trees, lights round it), the sky
// where it leaves the screen; the mirror counts for more the flatter you look (Fresnel); the sun glitters;
// below, the bottom is seen through the water - shifted by the waves, fading into the water's colour the
// deeper it lies.
//
// WET (before the translucent terrain, replacing the picture where it is wet): after rain the ground, roofs and
// the walls the rain is driven against are wet - only where the sky is open over them (the heightmap): darker,
// a glossy sheen that mirrors the light at a glancing look; on flat ground water stands in puddles, more and
// larger the longer and harder it rains, mirror-flat but for the rings the drops throw while it still rains.
// Grass and leaves soak it up: puddles keep to stone, earth, sand, paths and concrete. Paved ground - asphalt,
// flagstones, kerbs (dark or grey, without colour) - goes mirror-like: smooth, glossier, more and longer puddles,
// the lights of the street (lamps, lit windows, neon) drawn out in it as bright, shimmering streaks.

uniform sampler2D Sampler0; // depth of the opaque world (before the translucent terrain)
#ifdef SHADE
uniform sampler2D Sampler1; // heightmap of the world round the camera: per column the first free block
#endif
#ifdef UNDER
uniform sampler2D Sampler1; // the heightmap: per column the first free block (the water's surface, or what is over it)
uniform sampler2D Sampler2; // the picture
#endif
#ifdef WET
uniform sampler2D Sampler1; // the heightmap: per column the first free block (what the sky reaches down to)
uniform sampler2D Sampler2; // picture of the opaque world
#endif
#ifdef WATER
uniform sampler2D Sampler1; // depth with the water
uniform sampler2D Sampler2; // picture of the opaque world
uniform sampler2D Sampler3; // picture with the water as Minecraft drew it
#endif

in vec3 relPos;
flat in vec4 params;
flat in vec2 cameraXZ;
flat in float cameraY;
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
            float aoBias = 0.03 + here * 0.006;
            occluded += smoothstep(aoBias, aoBias + 0.1, gap) * (1.0 - smoothstep(radius, radius * 2.5, gap));
        }
        float ao = 1.0 - occluded / float(samples);
        shade *= 1.0 - aoStrength * (1.0 - ao) * (1.0 - smoothstep(48.0, 96.0, dist));
    }

    // ---- sun shadows (see the top)
    vec3 tint = vec3(1.0);
    if (shadowStrength > 0.0 && dist < 160.0) {
        float facing = dot(n, sunDir);
        float lit = 1.0;
        if (facing > 0.0) {
            int rays = quality >= 3 ? 5 : quality == 2 ? 3 : 1;
            vec3 su = normalize(cross(sunDir, abs(sunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
            vec3 sv = cross(sunDir, su);
            const float SPREAD = 0.02; // radians: the disc the rays sample
            vec3 start = p + n * 0.06;
            float maxTop = float(data.y) - 64.0;
            float through = 0.0;
            for (int r = 0; r < 5; r++) {
                if (r >= rays) break;
                vec3 dir = sunDir;
                if (r > 0) {
                    float a = float(r - 1) * 6.2831853 / float(rays - 1) + 0.4;
                    dir = normalize(sunDir + (su * cos(a) + sv * sin(a)) * SPREAD);
                }
                float hit = 0.0;
                // over the heightmap: steps of a third of a block near, longer further out
                float t = 0.0;
                float stepLen = 0.3;
                for (int i = 0; i < 90; i++) {
                    t += stepLen;
                    stepLen = min(stepLen * 1.025, 1.0);
                    vec3 q = start + dir * t;
                    float y = q.y + cameraY;
                    if (y > maxTop) break; // above everything round here: nothing left to hit
                    vec2 hm = q.xz + cameraXZ;
                    ivec2 c = ivec2(floor(hm));
                    if (c.x < 0 || c.y < 0 || c.x >= 256 || c.y >= 256) break;
                    vec4 h = texelFetch(Sampler1, c, 0);
                    float top = floor(h.r * 255.0 + 0.5) + floor(h.g * 255.0 + 0.5) * 256.0 - 64.0;
                    if (y < top - 0.02) {
                        hit = 1.0;
                        break;
                    }
                    if (t > 56.0) break;
                }
                // a few short steps through the picture: entities and the small things the map misses
                if (hit < 0.5) {
                    float travelled = 0.0;
                    float stepS = 0.06;
                    for (int i = 0; i < 12; i++) {
                        travelled += stepS;
                        stepS *= 1.3;
                        vec3 q = start + dir * travelled;
                        vec3 sp = project(q);
                        if (sp.z < 0.0 || sp.x < 0.0 || sp.y < 0.0 || sp.x > 1.0 || sp.y > 1.0) break;
                        float here = length(q);
                        float gap = here - sceneDistance(sp.xy);
                        if (gap > 0.08 + here * 0.006 && gap < 1.2) {
                            hit = 1.0;
                            break;
                        }
                        if (travelled > 3.0) break;
                    }
                }
                through += 1.0 - hit;
            }
            lit = through / float(rays);
        }
        // faces turned from the sun are in their own shade, softer than a cast shadow (the sky lights them)
        float face = mix(0.45, 1.0, smoothstep(-0.05, 0.25, facing));
        lit = min(lit, face);
        float k = shadowStrength * (1.0 - lit) * (1.0 - smoothstep(110.0, 160.0, dist));
        // what lights a shadow is the blue sky, not the sun: shadows are a little cool
        tint = vec3(1.0) - k * vec3(1.0, 0.95, 0.82);
    }
    vec3 result = vec3(shade) * tint;
    if (min(result.r, min(result.g, result.b)) > 0.995) discard;
    fragColor = vec4(result, 1.0);
}
#endif

#if defined(UNDER) || defined(WATER)
// the net of light the waves focus onto the bottom (a few warped sine fields)
float caustic(vec2 x, float t) {
    vec2 p = mod(x * 6.28318, 6.28318) - 250.0;
    vec2 i = p;
    float c = 1.0;
    const float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * (1.0 - 3.5 / float(n + 1));
        i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 1.0);
}
#endif

#ifdef UNDER
float columnTop(vec2 hm) {
    ivec2 c = ivec2(floor(hm));
    if (c.x < 0 || c.y < 0 || c.x >= 256 || c.y >= 256) return -1.0e4;
    vec4 h = texelFetch(Sampler1, c, 0);
    return floor(h.r * 255.0 + 0.5) + floor(h.g * 255.0 + 0.5) * 256.0 - 64.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = textureLod(Sampler0, uv, 0.0).r;
    vec3 p = unproject(uv, min(depth, 0.99999));
    float dist = depth >= 1.0 ? 96.0 : length(p);
    vec3 rd = normalize(p);
    vec3 col = textureLod(Sampler2, uv, 0.0).rgb;
    float sun = params.r;
    float t = GameTime * 1200.0;
    vec2 slant = sunDir.xz / max(sunDir.y, 0.25);

    // ---- caustics on what lies under the water, where the sun reaches through the surface above it
    if (depth < 1.0 && sun > 0.0) {
        float top = columnTop(p.xz + cameraXZ);
        float below = top - (p.y + cameraY);
        if (below > 0.0 && below < 40.0) {
            vec2 at = p.xz + cameraXZ + slant * below;
            float c = caustic(at * 0.3, t * 0.6);
            float k = sun * exp(-below * 0.1) * (1.0 - smoothstep(20.0, 40.0, dist));
            col *= 1.0 + c * 1.6 * k;
        }
    }

    // ---- shafts of light slanting down from the surface, along the view
    if (sun > 0.0) {
        float tEnd = min(dist, 36.0);
        const int N = 20;
        float shafts = 0.0;
        for (int i = 0; i < N; i++) {
            vec3 q = rd * tEnd * (float(i) + 0.5) / float(N);
            float below = columnTop(q.xz + cameraXZ) - (q.y + cameraY);
            if (below <= 0.0 || below > 60.0) continue;
            vec2 at = q.xz + cameraXZ + slant * below;
            float beam = caustic(at * 0.08, t * 0.25);
            shafts += beam * exp(-below * 0.06) * exp(-length(q) * 0.045);
        }
        col += vec3(0.5, 0.78, 0.86) * shafts / float(N) * tEnd * 0.05 * sun;
    }

    // ---- the distance blurring into the water's blue
    float haze = 1.0 - exp(-dist * 0.04);
    vec2 o = vec2(0.006 * haze) * vec2(ScreenSize.y / ScreenSize.x, 1.0);
    vec3 soft = (textureLod(Sampler2, uv + vec2(o.x, 0.0), 0.0).rgb + textureLod(Sampler2, uv - vec2(o.x, 0.0), 0.0).rgb
        + textureLod(Sampler2, uv + vec2(0.0, o.y), 0.0).rgb + textureLod(Sampler2, uv - vec2(0.0, o.y), 0.0).rgb) * 0.25;
    col = mix(col, soft, haze * 0.7);
    col = mix(col, FogColor.rgb, haze * 0.3);
    fragColor = vec4(col, 1.0);
}
#endif

#if defined(WATER) || defined(WET)
// rings spreading where raindrops strike: a little push of the surface normal outwards from each ring
vec2 rainRings(vec2 x, float t, float density) {
    vec2 sum = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 g = x * 1.7 + float(layer) * vec2(0.37, 0.71);
        vec2 base = floor(g);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 cell = base + vec2(float(i), float(j));
                float rnd = hash(cell + float(layer) * 13.1);
                if (rnd > density) continue;
                vec2 centre = cell + vec2(hash(cell + 1.7), hash(cell + 3.1));
                float phase = fract(t * 0.9 + rnd * 7.3);
                float radius = phase * 0.75;
                vec2 d = g - centre;
                float len = length(d);
                float ring = exp(-pow((len - radius) * 12.0, 2.0)) * (1.0 - phase) * sin((len - radius) * 40.0);
                sum += d / max(len, 1.0e-3) * ring;
            }
        }
    }
    return sum;
}

// a mirrored ray walked through the picture of the opaque world (Sampler0 depth, Sampler2 colour); the sky where it
// leaves the screen
vec3 mirror(vec3 from, vec3 r, float dist, vec3 sky) {
    vec3 reflected = sky;
    float jitter = hash(gl_FragCoord.xy);
    vec3 q = from + r * (0.2 + jitter * 0.3);
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
    return reflected;
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
    // rain: rings spreading over the surface (more and bigger in a thunderstorm)
    float thunder = params.b;
    if (rain > 0.01 && dist < 48.0) {
        vec2 rings = rainRings(world, t, rain * 0.35 + thunder * 0.4);
        nw = normalize(nw + vec3(rings.x, 0.0, rings.y) * (0.35 + 0.35 * thunder) * (1.0 - smoothstep(24.0, 48.0, dist)));
    }
    if (n.y < 0.0) nw.y = -nw.y;

    // ---- reflection: walk the mirrored ray through the picture
    vec3 r = reflect(v, nw);
    if (r.y < 0.02) r.y = 0.02;
    r = normalize(r);
    vec3 zenith = vec3(float(data.x & 255), float((data.x >> 8) & 255), float(data.y & 255)) / 255.0;
    vec3 horizon = FogColor.rgb;
    vec3 sky = mix(horizon, zenith, smoothstep(0.0, 0.6, r.y));
    vec3 reflected = mirror(pw, r, dist, sky);

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

#ifdef WET
float columnTop(vec2 hm) {
    ivec2 c = ivec2(floor(hm));
    if (c.x < 0 || c.y < 0 || c.x >= 256 || c.y >= 256) return -1.0e4;
    vec4 h = texelFetch(Sampler1, c, 0);
    return floor(h.r * 255.0 + 0.5) + floor(h.g * 255.0 + 0.5) * 256.0 - 64.0;
}

// value noise that repeats every 256 blocks (so it stays put when the heightmap moves on)
float wrapNoise(vec2 x, float cells) {
    vec2 g = x * cells / 256.0;
    vec2 i = floor(g);
    vec2 f = fract(g);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(mod(i, cells));
    float b = hash(mod(i + vec2(1.0, 0.0), cells));
    float c = hash(mod(i + vec2(0.0, 1.0), cells));
    float d = hash(mod(i + vec2(1.0, 1.0), cells));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = textureLod(Sampler0, uv, 0.0).r;
    if (depth >= 1.0) discard;
    vec3 p = unproject(uv, depth);
    float dist = length(p);
    if (dist > 128.0) discard;
    vec3 n = cross(dFdx(p), dFdy(p));
    vec3 v = p / max(dist, 1.0e-4);
    n = dot(n, n) > 1.0e-12 ? normalize(n) : -v;
    if (dot(n, v) > 0.0) n = -n;
    float wetness = params.r;
    float puddles = params.g;
    float rain = params.b;

    // ---- does the rain reach it? the top of an open column, or a wall the rain is driven against
    vec2 hm = p.xz + cameraXZ;
    float y = p.y + cameraY;
    float exposed = 0.0;
    if (n.y > 0.7) {
        exposed = y >= columnTop(hm) - 0.35 ? 1.0 : 0.0;
    } else if (abs(n.y) < 0.3) {
        // a wall: wet where the column just in front of it lies below this point
        float front = columnTop(hm + n.xz * 0.7);
        exposed = y > front + 0.1 ? 0.45 : 0.0;
    }
    if (exposed <= 0.0) discard;
    float fade = 1.0 - smoothstep(80.0, 128.0, dist);
    float wet = wetness * exposed * fade;
    if (wet < 0.01) discard;

    vec3 col = textureLod(Sampler2, uv, 0.0).rgb;
    // grass and leaves (green and saturated) drink the water: a little darker, no standing water
    float green = clamp((col.g - max(col.r, col.b)) * 4.0, 0.0, 1.0);
    // wet is darker and a little richer in colour
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    vec3 soaked = mix(vec3(lum), col, 1.15) * mix(1.0, 0.62, wet * (1.0 - 0.4 * green));
    // paved: grey without colour, the darker (asphalt) the more so; only flat ground
    float sat = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
    float paved = (1.0 - green) * (1.0 - smoothstep(0.06, 0.16, sat)) * (1.0 - smoothstep(0.55, 0.75, lum)) * step(0.9, n.y);

    // ---- puddles on flat ground, more and larger the more it has rained
    vec2 world = p.xz + cameraXZ + vec2(float(data.y >> 8 & 15), float(data.y >> 12 & 15)) * 16.0;
    float pool = 0.0;
    if (n.y > 0.9) {
        float nz = wrapNoise(world, 32.0) * 0.6 + wrapNoise(world, 64.0) * 0.28 + wrapNoise(world, 128.0) * 0.12;
        float level = 1.0 - puddles * 0.55 * (1.0 + 0.7 * paved);
        pool = smoothstep(level, level + 0.06, nz) * (1.0 - green) * exposed * fade;
    }

    // ---- the sheen and the mirror
    float t = GameTime * 1200.0;
    vec3 nw = n;
    if (pool > 0.0) {
        nw = vec3(0.0, 1.0, 0.0);
        if (rain > 0.01 && dist < 40.0) {
            vec2 rings = rainRings(world, t, rain * 0.5);
            nw = normalize(nw + vec3(rings.x, 0.0, rings.y) * 0.45 * (1.0 - smoothstep(20.0, 40.0, dist)));
        }
    } else {
        // a wet surface is rough: the reflection is broken up
        vec2 j = vec2(hash(floor(world * 8.0)), hash(floor(world * 8.0) + 7.1)) - 0.5;
        nw = normalize(n + vec3(j.x, 0.0, j.y) * 0.12 * (1.0 - 0.75 * paved));
    }
    vec3 r = reflect(v, nw);
    if (r.y < 0.02 && n.y > 0.7) r.y = 0.02;
    r = normalize(r);
    vec3 zenith = vec3(float(data.x & 255), float((data.x >> 8) & 255), float(data.y & 255)) / 255.0;
    vec3 sky = mix(FogColor.rgb, zenith, smoothstep(0.0, 0.6, r.y));
    vec3 reflected = mirror(p + n * 0.05, r, dist, sky);
    if (paved > 0.05 && dist < 60.0) {
        // wet asphalt draws a light out into a streak towards you: a second look a little further along the
        // mirrored ray, the bright parts of it added, shimmering with the rain
        float shimmer = 0.85 + 0.15 * sin(t * 0.7 + world.x * 3.1 + world.y * 2.3);
        vec3 r2 = normalize(r + vec3(0.0, 0.06, 0.0));
        vec3 far = mirror(p + n * 0.05, r2, dist, sky);
        vec3 glow = max(far - 0.55, 0.0) + max(reflected - 0.55, 0.0);
        reflected += glow * paved * shimmer * 1.4 * (1.0 - smoothstep(30.0, 60.0, dist));
    }
    float cosv = clamp(dot(-v, nw), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
    float sheen = mix(fresnel * 0.55, 0.06 + 0.9 * fresnel, paved * 0.85);
    float gloss = mix(sheen * wet * (1.0 - 0.7 * green), 0.08 + 0.92 * fresnel, pool);
    // standing water darkens what lies under it a little more, and mirrors it
    vec3 base = mix(soaked, soaked * 0.7, pool);
    vec3 result = mix(base, reflected, clamp(gloss, 0.0, 1.0));
    fragColor = vec4(result, 1.0);
}
#endif
