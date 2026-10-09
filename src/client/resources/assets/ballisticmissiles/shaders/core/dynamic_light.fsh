#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Muzzle flashes, rocket motors and fireballs lighting up the world round them. Sampler0 is a copy of the
// world's depth, so every pixel knows where in the world it is: the light falls off with distance,
// lights surfaces facing it more than those turned away, and a short march through the depth buffer
// towards the light keeps it from shining through walls. The colour already there is brightened by it
// (so a dark night scene lights up in its own colours, as real light would).

uniform sampler2D Sampler0; // world depth

in vec3 relPos;
flat in vec4 lightCol;
flat in vec3 lightPos;
flat in float radius;

out vec4 fragColor;

float viewDepth(float depth) {
    return -ProjMat[3][2] / (depth * 2.0 - 1.0 + ProjMat[2][2]);
}

void main() {
    // everything that needs screen-space derivatives first, while the whole pixel quad is still running
    float depth = textureLod(Sampler0, gl_FragCoord.xy / ScreenSize, 0.0).r;
    vec3 rd = normalize(relPos);
    vec3 fwd = -vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
    float t = -viewDepth(min(depth, 0.99999)) / max(dot(rd, fwd), 0.05);
    vec3 p = rd * t; // the surface, relative to the camera
    // the surface's facing, from how the position changes across neighbouring pixels
    vec3 n = cross(dFdx(p), dFdy(p));

    if (depth >= 1.0) discard;
    vec3 toLight = lightPos - p;
    float d = length(toLight);
    if (d > radius) discard;
    vec3 l = toLight / max(d, 1.0e-3);
    n = dot(n, n) > 1.0e-12 ? normalize(n) : -rd;
    if (dot(n, rd) > 0.0) n = -n;
    float facing = 0.2 + 0.8 * max(dot(n, l), 0.0);

    // smooth falloff to nothing at the radius, brightest close in
    float r = d / radius;
    float att = 1.0 - r * r;
    att = att * att / (1.0 + d * d * 0.08);

    // screen-space shadow: walk from the surface towards the light; if the world in the depth buffer
    // is in front of the walk somewhere along it, something blocks the light
    float lit = 1.0;
    for (int i = 1; i <= 8; i++) {
        vec3 q = p + toLight * (float(i) / 9.0);
        vec4 c = ProjMat * ModelViewMat * vec4(q, 1.0);
        if (c.w <= 0.05) break;
        vec2 uv = c.xy / c.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        float sceneZ = viewDepth(textureLod(Sampler0, uv, 0.0).r);
        float qZ = -c.w;
        // the depth buffer holds something nearer the eye than this point of the walk, by a margin
        if (sceneZ > qZ + 0.25 + 0.02 * c.w && sceneZ - qZ < 6.0) {
            lit = 0.0;
            break;
        }
    }
    float k = lightCol.a * att * facing * lit;
    if (k < 0.003) discard;
    // brightens what is there: result = dst * (1 + light)
    fragColor = vec4(lightCol.rgb * k * 6.0, 1.0);
}
