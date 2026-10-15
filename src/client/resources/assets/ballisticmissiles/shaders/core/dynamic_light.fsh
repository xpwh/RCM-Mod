#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Muzzle flashes, rocket motors, fireballs and torch beams lighting up the world round them. Sampler0 is
// a copy of the world's depth, so every pixel knows where in the world it is: the light falls off with
// distance, lights surfaces facing it more than those turned away, and short marches through the depth
// buffer towards it cast shadows - from several points across the light, so a big fireball throws soft
// shadows with a penumbra while a muzzle flash throws crisp ones. A spotlight (a weapon light) only
// lights inside its cone, brightest in the middle with a soft rim. The colour already there is
// brightened by it, so a dark night scene lights up in its own colours, as real light would.

uniform sampler2D Sampler0; // world depth

in vec3 relPos;
flat in vec4 lightCol;
flat in vec3 lightPos;
flat in float radius;
flat in vec3 spotDir;
flat in float spotCos;
flat in int shadowRays;
flat in int shadowSteps;
flat in mat4 invViewProj;

out vec4 fragColor;

// a point (relative to the camera) from the depth at a screen position
vec3 unproject(vec2 uv, float depth) {
    vec4 w = invViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return w.xyz / w.w;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// how much of the light reaches p (0-1) along one ray towards the light point lp
float shadowRay(vec3 p, vec3 lp, int steps, float jitter) {
    vec3 toLight = lp - p;
    for (int i = 0; i < 15; i++) {
        if (i >= steps) break;
        vec3 q = p + toLight * ((float(i) + jitter) / float(steps + 1));
        vec4 c = ProjMat * ModelViewMat * vec4(q, 1.0);
        if (c.w <= 0.05) break;
        vec3 ndc = c.xyz / c.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        // distances from the eye along this pixel's ray: the world there, and this point of the walk
        float scene = length(unproject(uv, textureLod(Sampler0, uv, 0.0).r));
        float here = length(q);
        // something in the depth buffer lies in front of this point of the walk (but not so far in front
        // that it is a separate thing far nearer the eye)
        float gap = here - scene;
        if (gap > 0.2 + 0.015 * here && gap < 5.0) {
            return 0.0;
        }
    }
    return 1.0;
}

#ifdef HALO
// the glow of the light in the air between the eye and the world: light scattered towards the eye by
// haze, rain and smoke along the view ray, summed in closed form (the falloff 1 / (1 + k d^2) along a
// line integrates to an arctangent), strongest where the ray passes the light closest
void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = textureLod(Sampler0, uv, 0.0).r;
    vec3 rd = normalize(relPos);
    float sceneT = depth >= 1.0 ? 1.0e5 : length(unproject(uv, depth));
    float t0 = dot(lightPos, rd);
    float h2 = max(dot(lightPos, lightPos) - t0 * t0, 0.0);
    float h = sqrt(h2);
    if (h > radius) discard;
    const float K = 0.08;
    float a = sqrt(1.0 + K * h2);
    float sk = sqrt(K);
    float tEnd = min(sceneT, t0 + radius);
    float tStart = max(0.0, t0 - radius);
    if (tEnd <= tStart) discard;
    float glow = (atan(sk * (tEnd - t0) / a) - atan(sk * (tStart - t0) / a)) / (sk * a);
    // fades out to the edge of the light's reach; a light hidden behind the world only glows faintly round it
    glow *= 1.0 - smoothstep(radius * 0.4, radius, h);
    if (t0 > sceneT + 1.0) glow *= 0.25;
    // how thick the air is (haze, rain, smoke) rides in the spotlight slot, which a halo does not use
    float density = spotCos;
    float k = lightCol.a * glow * density * 0.06;
    if (k < 0.002) discard;
    fragColor = vec4(lightCol.rgb * k, 1.0);
}
#else
void main() {
    // everything that needs screen-space derivatives first, while the whole pixel quad is still running
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = textureLod(Sampler0, uv, 0.0).r;
    vec3 p = unproject(uv, min(depth, 0.99999)); // the surface, relative to the camera
    vec3 rd = normalize(relPos);
    // the surface's facing, from how the position changes across neighbouring pixels
    vec3 n = cross(dFdx(p), dFdy(p));

    if (depth >= 1.0) discard;
    vec3 toLight = lightPos - p;
    float d = length(toLight);
    if (d > radius) discard;
    vec3 l = toLight / max(d, 1.0e-3);
    n = dot(n, n) > 1.0e-12 ? normalize(n) : -rd;
    if (dot(n, rd) > 0.0) n = -n;
    float facing = 0.15 + 0.85 * max(dot(n, l), 0.0);

    // the cone of a spotlight: a bright core fading softly to its rim
    float spot = 1.0;
    if (spotCos > 0.0) {
        float c = dot(-l, spotDir);
        spot = smoothstep(spotCos, mix(spotCos, 1.0, 0.45), c);
        spot *= 0.75 + 0.25 * smoothstep(mix(spotCos, 1.0, 0.7), 1.0, c);
        if (spot <= 0.001) discard;
    }

    // falls off with the square of the distance near the light, smoothly to nothing at the radius
    float r = d / radius;
    float att = 1.0 - r * r;
    att = att * att / (1.0 + d * d * 0.08);

    // shadows: rays to points spread over the light's size (a fireball is wide, a muzzle flash small),
    // each with its own dithered start so the penumbra is smooth rather than banded
    float lit = 1.0;
    if (shadowRays > 0 && shadowSteps > 0) {
        float size = clamp(radius * 0.06, 0.1, 3.0);
        vec3 side = normalize(cross(l, abs(l.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 side2 = cross(l, side);
        float jitter = hash(gl_FragCoord.xy);
        float sum = 0.0;
        for (int k = 0; k < 8; k++) {
            if (k >= shadowRays) break;
            float a = 6.2831853 * (float(k) + jitter) / float(shadowRays);
            vec3 lp = shadowRays == 1 ? lightPos : lightPos + (side * cos(a) + side2 * sin(a)) * size;
            sum += shadowRay(p, lp, shadowSteps, fract(jitter + float(k) * 0.618));
        }
        lit = sum / float(shadowRays);
    }

    float k = lightCol.a * att * facing * lit * spot;
    if (k < 0.003) discard;
    // brightens what is there: result = dst * (1 + light)
    fragColor = vec4(lightCol.rgb * k * 6.0, 1.0);
}
#endif
