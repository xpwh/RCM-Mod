#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <ballisticmissiles:bm_wind.glsl>

// Vanilla's terrain shader, with the wind (Ballistic Missiles, see Wind.java): the mesher marks plants and leaves
// in the vertex alpha - 252 leaves, 210-250 plants (250 at the foot, lower = further up the stalk) - and BmWind
// carries the wind and up to four blast waves sweeping out over the land.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;


out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

// how far a marked vertex moves, relative to the camera block (rel) and in the world (world, wrapped)
vec3 sway(vec3 rel, vec3 world, float amp, bool leaf) {
    float t = WindInfo.w;
    vec2 dir = WindInfo.xy;
    vec2 side = vec2(-dir.y, dir.x);
    float s = WindInfo.z;
    float phase = dot(world.xz, vec2(0.37, 0.53)) + world.y * 0.21;
    // gusts: waves of stronger wind running over the land with the wind
    float gust = 0.5 + 0.5 * sin(dot(world.xz, dir) * 0.09 - t * 1.7) * sin(dot(world.xz, side) * 0.031 + t * 0.23);
    gust *= gust;
    vec3 off = vec3(0.0);
    if (leaf) {
        // leaves: a rustle, a little more in the gusts
        off.xz = vec2(sin(t * 3.1 + phase * 2.0), cos(t * 2.6 + phase * 1.3)) * 0.03 * s * (0.6 + gust);
        off.y = sin(t * 2.3 + phase * 1.7) * 0.015 * s;
    } else {
        float lean = s * (0.1 + 0.4 * gust);
        float wave = sin(t * 2.2 + phase) * 0.6 + sin(t * 3.9 + phase * 1.7) * 0.3;
        off.xz = dir * (lean + wave * 0.08 * s) + side * sin(t * 2.9 + phase * 1.3) * 0.04 * s;
        off.xz *= amp;
        off.y = -length(off.xz) * 0.35 * amp;
    }
    // blast waves: pressed flat as the front passes, springing back behind it
    for (int i = 0; i < 4; i++) {
        if (BlastPos[i].w < 0.0) {
            continue;
        }
        vec2 d = rel.xz - BlastPos[i].xz;
        float dist = length(d) + 0.001;
        float front = BlastShape[i].x * BlastPos[i].w;
        if (dist > BlastShape[i].y || dist > front) {
            continue;
        }
        float since = (front - dist) / BlastShape[i].x;
        float fall = 1.0 - dist / BlastShape[i].y;
        float push = BlastShape[i].z * fall * exp(-since * 1.1) * cos(since * 4.5) * smoothstep(0.0, 1.5, front - dist);
        float a = leaf ? 0.3 : amp;
        off.xz += d / dist * push * a;
        off.y -= abs(push) * a * (leaf ? 0.05 : 0.45);
    }
    return off;
}

void main() {
    vec3 rel = Position + vec3(ChunkPosition - CameraBlockPos);
    vec3 pos = rel + CameraOffset;
    vec4 color = Color;
    int mark = int(Color.a * 255.0 + 0.5);
    if (mark < 253 && mark >= 205) {
        bool leaf = mark == 252;
        float amp = leaf ? 1.0 : clamp((250.0 - float(mark)) / 40.0, 0.0, 1.0);
        vec3 world = rel + vec3(CameraBlockPos & ivec3(4095));
        pos += sway(rel, world, amp, leaf);
        color.a = 1.0;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
