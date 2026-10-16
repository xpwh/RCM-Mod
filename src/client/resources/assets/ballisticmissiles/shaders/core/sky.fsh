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

void main() {
    vec3 rd = normalize(relPos);
    float rain = skyInfo.r;
    float thunder = skyInfo.g;
    int level = int(skyInfo.b * 3.0 + 0.5);
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
    // into the fog towards and below the horizon
    float below = 1.0 - smoothstep(-0.02, 0.06, rd.y);
    col = mix(col, FogColor.rgb, below);
    fragColor = vec4(col, skyInfo.a);
}
