#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The dynamic-light pass: one quad across the whole view per light (positions relative to the camera,
// so they give the view ray). The light itself rides along in the vertex data:
//   Color   rgb = light colour, a = strength
//   UV0     = light position x, z (relative to the camera, blocks)
//   UV2.x   = light position y * 8 (signed), UV2.y = radius * 16
//   UV1.x   = spotlight: cosine of the cone's half angle * 1000 (0 = a point light shining all round);
//             in the halo pass: how thick the air is (haze, rain, smoke) * 1000
//   UV1.y   = shadow quality: rays (low 4 bits) and steps per ray (next 4 bits); 0 = no shadows
//   Normal  = the spotlight's direction

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
flat out vec4 lightCol;
flat out vec3 lightPos;
flat out float radius;
flat out vec3 spotDir;
flat out float spotCos;
flat out int shadowRays;
flat out int shadowSteps;
// clip space back to camera-relative world space, with all the view carries (the walking bob too)
flat out mat4 invViewProj;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // at the far plane: the quad sits far out (so the walking bob cannot swing it), past where it would clip
    gl_Position = pos.xyww;
    relPos = Position;
    lightCol = Color;
    lightPos = vec3(UV0.x, float(UV2.x) / 8.0, UV0.y);
    radius = float(UV2.y) / 16.0;
    spotDir = length(Normal) > 0.01 ? normalize(Normal) : vec3(0.0, -1.0, 0.0);
    spotCos = float(UV1.x) / 1000.0;
    shadowRays = UV1.y & 15;
    shadowSteps = (UV1.y >> 4) & 15;
    invViewProj = inverse(ProjMat * ModelViewMat);
}
