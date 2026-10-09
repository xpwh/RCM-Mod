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
    int level = int(cloudInfo.a * 3.0 + 0.5);
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
    int samples = level == 0 ? 2 : level == 1 ? 3 : level == 2 ? 4 : 6;
    float od = cloudBetween(p, sunDir, samples);
    float shade = 1.0 - exp(-od * 0.07);
    // soft with distance: far off the shadows fade into the haze
    float fade = 1.0 - smoothstep(0.55, 1.0, sceneT / max(FogRenderDistanceEnd, 64.0));
    float shadow = shade * 0.5 * sunUp * fade;
    if (shadow < 0.004) discard;
    fragColor = vec4(0.0, 0.0, 0.0, shadow);
#else
    if ((vLayer.y & 512) == 0 || level == 0) discard;
    // the shafts live below the cloud base, in the air between the eye and the world (or the clouds)
    if (cam.y > bottom) discard;
    float tEnd = min(sceneT, rd.y > 0.01 ? (bottom - cam.y) / rd.y : 1.0e6);
    tEnd = min(tEnd, 320.0);
    int steps = level == 1 ? 8 : level == 2 ? 14 : 22;
    int samples = level == 3 ? 3 : 2;
    float stepLen = tEnd / float(steps);
    // interleaved gradient noise: an even dither the eye smooths over, not grain
    float t = stepLen * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float lit = 0.0;
    for (int i = 0; i < 22; i++) {
        if (i >= steps) break;
        vec3 p = cam + rd * t;
        lit += exp(-cloudBetween(p, sunDir, samples) * 0.07) * stepLen;
        t += stepLen;
    }
    // forward scattering: the shafts show looking towards the sun, and only where light and shadow alternate
    float cosTheta = dot(rd, sunDir);
    float phase = henyeyGreenstein(cosTheta, 0.7) * 4.0 * 3.14159;
    float low = smoothstep(0.0, 0.35, sunDir.y);
    vec3 sunCol = mix(vec3(1.2, 0.62, 0.3), vec3(1.05, 1.0, 0.92), low);
    float lightAmount = lit / 320.0;
    // what makes a shaft is the contrast with the shadowed air round it: strongest where the clouds break
    // the light up (part of the way lit, part in shadow), faint under a clear sky or a closed deck
    float v = lit / max(tEnd, 1.0);
    float broken = 4.0 * v * (1.0 - v);
    vec3 rays = sunCol * phase * lightAmount * 0.055 * (0.25 + 0.75 * broken) * sunUp;
    if (dot(rays, vec3(1.0)) < 0.002) discard;
    fragColor = vec4(rays, 1.0);
#endif
}
