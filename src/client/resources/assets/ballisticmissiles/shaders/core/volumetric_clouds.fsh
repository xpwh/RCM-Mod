#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Volumetric clouds (the shape is in include/clouds_common.glsl). Each view ray is marched through the cloud layer: the shape comes from tiling 3D
// noise (billowing Perlin-Worley base, eroded at the edges by finer Worley detail), with a flat base and
// rounded tops; light is marched a few steps towards the sun, so tops are bright, undersides dark, thin
// edges glow when you look towards the sun, and the light reddens at sunrise and sunset.
// Rain and thunderstorms darken and thicken the layer (storm clouds tower right up), and lightning lights
// the clouds up from inside, brightest low down where the bolts come out.
// Rockets disturb the layer: Sampler0 is a wrapping 1024 x 1024 block map, R = holes punched through
// (they widen, then fill in again), G = exhaust smoke spreading out along the layer, B = cloud pushed
// aside and piled up round the rims of the holes, A = rocket engines and fireballs lighting the cloud.

#moj_import <ballisticmissiles:clouds_common.glsl>

in vec3 relPos;
in vec4 cloudInfo;
flat in vec2 vWind;
flat in ivec2 vLayer;
flat in vec3 sunDir;
flat in float reach;
flat in float vStorm;
flat in float lightning;

out vec4 fragColor;

void main() {
    bottom = float(vLayer.x);
    thick = float(vLayer.y & 255);
    coverage = cloudInfo.r;
    storm = vStorm;
    cloudWind = vWind;
    float day = cloudInfo.g;
    float rain = cloudInfo.b;

    vec3 rd = normalize(relPos);
    vec3 cam = wrappedCamera(CameraBlockPos, CameraOffset);
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

    // quality: steps along the ray, how much detail, how many steps towards the sun
    int level = int(cloudInfo.a * 3.0 + 0.5);
    int steps = level == 0 ? 24 : level == 1 ? 40 : level == 2 ? 56 : 84;
    int detail = level == 0 ? 0 : level == 1 ? 1 : 2;
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
    for (int i = 0; i < 84; i++) {
        if (i >= steps || t > t1) break;
        vec3 p = cam + rd * t;
        float smoke;
        float glow;
        float d = density(p, detail, smoke, glow);
        if (d > 0.002) {
            if (firstHit < 0.0) firstHit = t;
            // how much cloud lies between here and the sun
            float od = density(p + sunDir * 6.0, 0) * 8.0 + density(p + sunDir * 16.0, 0) * 12.0;
            if (level >= 1) od += density(p + sunDir * 32.0, 0) * 18.0;
            if (level >= 2) od += density(p + sunDir * 58.0, 0) * 30.0;
            if (level >= 3) od += density(p + sunDir * 96.0, 0) * 40.0;
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
