#version 330

// A physically based sky: single scattering of sunlight in a spherical atmosphere around a spherical
// Earth (units: km). Air molecules scatter blue light most (Rayleigh: the blue sky, the red sunset),
// haze scatters all colours forwards (Mie: the bright glow round the sun), and the ozone layer absorbs
// orange, which keeps the sky blue after sunset. Light reaching a point has crossed the air towards the
// sun first, and where the Earth stands in the way there is none: low in the east after sunset the
// Earth's shadow rises as a dark blue band, the pink Belt of Venus above it.
// Mirrored on the CPU by Sky.java (fog colour), keep the two in step.

const float AT_RE = 6360.0;
const float AT_RA = 6460.0;
const float AT_EYE = AT_RE + 0.2;
const vec3 AT_BR = vec3(5.8e-3, 13.5e-3, 33.1e-3);
const float AT_BM = 4.0e-3;
const vec3 AT_BO = vec3(0.65e-3, 1.881e-3, 0.085e-3);
const float AT_HR = 8.0;
const float AT_HM = 1.2;
const float AT_SUN = 20.0;

float atOzone(float h) {
    return max(0.0, 1.0 - abs(h - 25.0) / 15.0);
}

// distance along d from o to the far side of a sphere of radius r round the centre (o inside it)
float atExit(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    return -b + sqrt(max(b * b - c, 0.0));
}

// distance to where d meets the ground, or -1 if it misses
float atGround(vec3 o, vec3 d) {
    float b = dot(o, d);
    float c = dot(o, o) - AT_RE * AT_RE;
    float disc = b * b - c;
    if (disc < 0.0) return -1.0;
    float t = -b - sqrt(disc);
    return t > 0.0 ? t : -1.0;
}

// optical depth (Rayleigh, Mie, ozone) from p towards the sun
vec3 atLightDepth(vec3 p, vec3 sun, int steps) {
    float len = atExit(p, sun, AT_RA);
    float seg = len / float(steps);
    vec3 od = vec3(0.0);
    for (int j = 0; j < 8; j++) {
        if (j >= steps) break;
        float h = length(p + sun * (seg * (float(j) + 0.5))) - AT_RE;
        od += vec3(exp(-h / AT_HR), exp(-h / AT_HM), atOzone(h)) * seg;
    }
    return od;
}

vec3 atExtinction(vec3 od, float haze) {
    return AT_BR * od.x + AT_BM * haze * 1.1 * od.y + AT_BO * od.z;
}

// sunlight arriving at the eye (colour of the light falling on things): 0 once the sun is down
vec3 atSunlight(vec3 sun, float haze) {
    vec3 o = vec3(0.0, AT_EYE, 0.0);
    if (atGround(o, sun) > 0.0) return vec3(0.0);
    return exp(-atExtinction(atLightDepth(o, sun, 8), haze));
}

// radiance of the sky along rd (linear, before tone mapping); haze 1 clear, more in rain
vec3 atmosphere(vec3 rd, vec3 sun, float haze, int viewSteps, int lightSteps) {
    vec3 o = vec3(0.0, AT_EYE, 0.0);
    float tMax = atExit(o, rd, AT_RA);
    float tg = atGround(o, rd);
    if (tg > 0.0) tMax = tg;
    float seg = tMax / float(viewSteps);
    vec3 od = vec3(0.0);
    vec3 sumR = vec3(0.0);
    vec3 sumM = vec3(0.0);
    for (int i = 0; i < 24; i++) {
        if (i >= viewSteps) break;
        vec3 p = o + rd * (seg * (float(i) + 0.5));
        float h = length(p) - AT_RE;
        vec3 d = vec3(exp(-h / AT_HR), exp(-h / AT_HM), atOzone(h)) * seg;
        od += d;
        if (atGround(p, sun) > 0.0) continue; // in the Earth's shadow
        vec3 att = exp(-atExtinction(od + atLightDepth(p, sun, lightSteps), haze));
        sumR += att * d.x;
        sumM += att * d.y;
    }
    float mu = dot(rd, sun);
    float pR = 3.0 / (16.0 * 3.14159265) * (1.0 + mu * mu);
    const float g = 0.76;
    float pM = 3.0 / (8.0 * 3.14159265) * ((1.0 - g * g) * (1.0 + mu * mu)) / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    return AT_SUN * (sumR * AT_BR * pR + sumM * AT_BM * haze * pM);
}

// linear radiance to screen colour: soft exposure curve, gamma, a touch more saturation
vec3 atToneMap(vec3 L) {
    vec3 c = pow(1.0 - exp(-L * 2.5), vec3(1.0 / 2.2));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(l), c, 1.1), 0.0, 1.0);
}
