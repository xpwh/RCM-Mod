#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The light pass of the volumetric clouds, over the whole view. Sampler2 is a copy of the world's depth
// taken at the end of the main pass, so every pixel knows how far away the world is there.
//  without RAYS (drawn before the clouds): cloud shadows - the ground under a cloud darkened by how much
//    cloud lies between it and the sun; they drift across the land with the clouds, and the holes rockets
//    punch let a patch of sunlight through
//  with RAYS (drawn after the clouds): light shafts - sunlight scattered in the air towards the eye,
//    except where the clouds overhead cut it off, so beams fan out through the gaps

#moj_import <ballisticmissiles:clouds_common.glsl>
#moj_import <ballisticmissiles:atmosphere.glsl>

uniform sampler2D Sampler2; // world depth

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
    int level = qualityLevel(cloudInfo.a);
    // only by day, with the sun up: shadows and shafts both need direct sunlight
    float sunUp = smoothstep(0.03, 0.15, sunDir.y) * day * (1.0 - 0.75 * clamp(0.6 * rain + 0.5 * storm, 0.0, 1.0));
    if (sunUp < 0.01) discard;

    vec3 rd = normalize(relPos);
    vec3 cam = wrappedCamera(CameraBlockPos, CameraOffset);
    // distance to the world along this ray, from the depth copy
    float depth = texture(Sampler2, gl_FragCoord.xy / ScreenSize).r;
    vec3 fwd = -vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
    float sceneT = 1.0e6;
    if (depth < 1.0) {
        float viewZ = -ProjMat[3][2] / (depth * 2.0 - 1.0 + ProjMat[2][2]);
        sceneT = -viewZ / max(dot(rd, fwd), 0.05);
    }

#ifndef RAYS
    if ((vLayer.y & 256) == 0 || depth >= 1.0) discard;
    vec3 p = cam + rd * sceneT;
    if (p.y >= bottom) discard;
    // looking down at the ground through the cloud layer from above: the clouds cover it anyway
    if (cam.y > bottom) discard;
    // a soft-edged shadow: several rays towards the sun from points spread round this one, as wide as the
    // penumbra a cloud this high casts (the sun is a disc, and the cloud's edges let light through), so
    // the shadow's edge is a gentle gradient that glides over the land rather than a hard line
    int samples = level <= 1 ? 2 : 3;
    int rays = level == 0 ? 1 : level == 1 ? 3 : level == 2 ? 4 : 6;
    vec3 side = normalize(cross(sunDir, abs(sunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 side2 = cross(sunDir, side);
    float above = max(bottom - p.y, 0.0) / max(sunDir.y, 0.1);
    float spread = clamp(above * 0.045, 3.0, 18.0);
    float lightIn = 0.0;
    for (int k = 0; k < 6; k++) {
        if (k >= rays) break;
        float a = 6.2831853 * (float(k) + 0.5) / float(rays);
        vec3 off = rays == 1 ? vec3(0.0) : (side * cos(a) + side2 * sin(a)) * spread;
        lightIn += exp(-cloudBetween(p + off, sunDir, samples) * 0.07);
    }
    float shade = 1.0 - lightIn / float(rays);
    // soft with distance: far off the shadows fade into the haze
    float fade = 1.0 - smoothstep(0.55, 1.0, sceneT / max(FogRenderDistanceEnd, 64.0));
    float shadow = shade * 0.5 * sunUp * fade;
    if (shadow < 0.004) discard;
    fragColor = vec4(0.0, 0.0, 0.0, shadow);
#else
    if ((vLayer.y & 512) == 0 || level == 0) discard;
    float cosTheta = dot(rd, sunDir);
    vec3 sunLight = atSunlight(sunDir, 1.0 + 5.0 * rain);
    vec3 sunCol = sunLight / max(pow(dot(sunLight, vec3(0.2126, 0.7152, 0.0722)), 0.45), 0.02) * 1.05;
    // interleaved gradient noise: an even dither the eye smooths over, not grain
    float dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 rays = vec3(0.0);

    // 1. shafts from the clouds: sunlight scattered in the air below the cloud base, cut off where the
    //    clouds overhead shade it, so beams fan out through the gaps
    if (cam.y < bottom) {
        float tEnd = min(sceneT, rd.y > 0.01 ? (bottom - cam.y) / rd.y : 1.0e6);
        tEnd = min(tEnd, 320.0);
        int steps = level == 1 ? 8 : level == 2 ? 14 : 22;
        int samples = level == 3 ? 3 : 2;
        float stepLen = tEnd / float(steps);
        float t = stepLen * dither;
        float lit = 0.0;
        for (int i = 0; i < 22; i++) {
            if (i >= steps) break;
            vec3 p = cam + rd * t;
            lit += exp(-cloudBetween(p, sunDir, samples) * 0.07) * stepLen;
            t += stepLen;
        }
        // forward scattering: the shafts show looking towards the sun, and only where light and shadow alternate
        float phase = henyeyGreenstein(cosTheta, 0.7) * 4.0 * 3.14159;
        float v = lit / max(tEnd, 1.0);
        float broken = 4.0 * v * (1.0 - v);
        rays += sunCol * phase * (lit / 320.0) * 0.055 * (0.25 + 0.75 * broken);
    }

    // 2. shafts from everything in the way: trees, hills, buildings, smoke-free sky between them. From each
    //    pixel a short walk across the depth copy towards the sun's place on screen counts how much open sky
    //    it passes (the light that reaches the air there); occluders leave dark streaks behind them, the gaps
    //    between them bright beams - all radiating from the sun. The sun's screen position comes from the
    //    same matrices the sky quad uses, so the beams stay locked to the sun.
    vec4 sc = ProjMat * ModelViewMat * vec4(sunDir * 1000.0, 1.0);
    float facing = smoothstep(-0.1, 0.35, cosTheta);
    if (sc.w > 0.0 && facing > 0.0) {
        vec2 uv = gl_FragCoord.xy / ScreenSize;
        vec2 sunUv = sc.xy / sc.w * 0.5 + 0.5;
        vec2 delta = sunUv - uv;
        float aspect = ScreenSize.x / ScreenSize.y;
        float dist = length(delta * vec2(aspect, 1.0));
        // the walk is at most this long (in screen heights): the beams fade out over that length
        delta *= min(1.0, 0.85 / max(dist, 1.0e-4));
        int n = level == 1 ? 16 : level == 2 ? 28 : 40;
        vec2 stepUv = delta / float(n);
        vec2 suv = uv + stepUv * dither;
        float open = 0.0;
        float wsum = 0.0;
        float decay = 1.0;
        for (int i = 0; i < 40; i++) {
            if (i >= n) break;
            vec2 c = clamp(suv, vec2(0.0005), vec2(0.9995));
            open += (texture(Sampler2, c).r >= 1.0 ? 1.0 : 0.0) * decay;
            wsum += decay;
            decay *= 0.955;
            suv += stepUv;
        }
        float vis = open / max(wsum, 1.0e-4);
        // the pixel's own sky shows the open sky's glow already (the sky shader draws it), so there the
        // beams are kept faint; over the world they light the air in front of it
        bool skyHere = depth >= 1.0;
        float ang = max(cosTheta, 0.0);
        float lobe = 0.55 * pow(ang, 6.0) + 0.9 * pow(ang, 48.0) + 0.12 * ang;
        // the beams are the contrast: strongest where the walk crossed both sky and occluders
        float mixed = 4.0 * vis * (1.0 - vis);
        float amount = vis * (skyHere ? 0.25 : 1.0) * (0.55 + 0.45 * mixed);
        // haze: a low sun through thick air throws the strongest, warmest beams
        float haze = 1.0 + 0.8 * (1.0 - smoothstep(0.05, 0.5, sunDir.y)) + 0.6 * rain;
        rays += sunCol * lobe * amount * facing * haze * 0.32;
    }
    rays *= sunUp;
    if (dot(rays, vec3(1.0)) < 0.002) discard;
    fragColor = vec4(rays, 1.0);
#endif
}
