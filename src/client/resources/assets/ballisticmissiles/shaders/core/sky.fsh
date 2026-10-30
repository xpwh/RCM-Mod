#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <ballisticmissiles:atmosphere.glsl>

// The physically based sky (see include/atmosphere.glsl), with the sun's disc. Below the horizon it runs
// into the fog colour, which Sky.java sets to this same sky at the horizon, so the land meets it cleanly.

in vec3 relPos;
flat in vec4 skyInfo;
flat in vec3 sunDir;

out vec4 fragColor;

// The rainbow: sunlight refracted and reflected in the raindrops, a circle round the point opposite the sun -
// red outside at 42 degrees, violet inside at 40.5; the fainter secondary bow at 50-53.5 degrees with its colours
// reversed, the sky between them darker (Alexander's band), brighter inside the primary.
vec3 spectrum(float t) {
    // t 0 violet .. 1 red
    vec3 c;
    c.r = exp(-pow((t - 0.92) / 0.22, 2.0)) + 0.35 * exp(-pow((t - 0.05) / 0.08, 2.0));
    c.g = exp(-pow((t - 0.55) / 0.2, 2.0));
    c.b = exp(-pow((t - 0.16) / 0.2, 2.0));
    return c;
}

vec3 rainbow(vec3 rd, vec3 sunDir, float strength) {
    float ang = degrees(acos(clamp(dot(rd, -sunDir), -1.0, 1.0)));
    vec3 col = vec3(0.0);
    // primary
    float t1 = (ang - 40.4) / 2.1;
    col += spectrum(t1) * smoothstep(-0.25, 0.1, t1) * (1.0 - smoothstep(0.95, 1.25, t1)) * 0.42;
    // a faint supernumerary band just inside
    float ts = (ang - 39.4) / 0.9;
    col += vec3(0.25, 0.55, 0.35) * exp(-ts * ts * 4.0) * 0.08;
    // secondary, reversed and wider
    float t2 = 1.0 - (ang - 50.2) / 3.4;
    col += spectrum(t2) * smoothstep(-0.2, 0.1, t2) * (1.0 - smoothstep(0.95, 1.2, t2)) * 0.16;
    return col * strength;
}

void main() {
    vec3 rd = normalize(relPos);
    float rain = skyInfo.r;
    float thunder = skyInfo.g;
    int packed = int(skyInfo.b * 255.0 + 0.5);
    int level = packed >> 6;
    float bow = float(packed & 63) / 63.0;
    float haze = 1.0 + 5.0 * rain;
    int viewSteps = level == 0 ? 8 : level == 1 ? 12 : level == 2 ? 16 : 20;
    int lightSteps = level <= 1 ? 4 : 6;
    // just above the horizon the view ray runs through the most air; keep it a hair above to spare the ground test
    vec3 r = normalize(vec3(rd.x, max(rd.y, 0.004), rd.z));
    vec3 L = atmosphere(r, sunDir, haze, viewSteps, lightSteps);
    // the sun's disc, darker towards its rim, coloured by the air it shines through
    float c = dot(rd, sunDir);
    float disc = smoothstep(0.99975, 0.99988, c);
    if (disc > 0.0) {
        vec3 sunlight = atSunlight(sunDir, haze);
        L += sunlight * disc * 60.0 * (0.6 + 0.4 * smoothstep(0.99975, 1.0, c)) * (1.0 - 0.9 * rain);
    }
    vec3 col = atToneMap(L);
    // rain: an overcast grey of the same brightness; a thunderstorm: darker still
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(lum * 0.85) * vec3(0.95, 0.97, 1.0), clamp(rain * 0.85, 0.0, 0.85));
    col *= 1.0 - 0.45 * thunder;
    if (bow > 0.0) {
        float ang = degrees(acos(clamp(dot(rd, -sunDir), -1.0, 1.0)));
        // brighter inside the primary bow, darker between the two (Alexander's band)
        col *= 1.0 + bow * (0.07 * (1.0 - smoothstep(30.0, 40.5, ang)) - 0.06 * smoothstep(41.5, 43.0, ang) * (1.0 - smoothstep(49.5, 51.0, ang)));
        // fades towards the ground, where the rain curtain ends
        col += rainbow(rd, sunDir, bow) * smoothstep(-0.03, 0.08, rd.y);
    }
    // into the fog towards and below the horizon
    float below = 1.0 - smoothstep(-0.02, 0.06, rd.y);
    col = mix(col, FogColor.rgb, below);
    fragColor = vec4(col, skyInfo.a);
}
