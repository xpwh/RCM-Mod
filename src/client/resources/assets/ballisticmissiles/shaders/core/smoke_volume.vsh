#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Volumetric smoke: one quad over the whole view, far out.
//   Color  = the sky's light (rgb) and how strong the sun is (a)
//   UV0    = seconds (for the swirl), number of puffs
//   UV2.x  = samples along each puff
//   Normal = the direction to the sun (or the moon)

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
flat out vec4 ambient;
flat out float seconds;
flat out int count;
flat out int samples;
flat out vec3 sunDir;
flat out mat4 invViewProj;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    relPos = Position;
    ambient = Color;
    seconds = UV0.x;
    count = int(UV0.y + 0.5);
    samples = max(1, UV2.x);
    sunDir = normalize(Normal);
    invViewProj = inverse(ProjMat * ModelViewMat);
}
