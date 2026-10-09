#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The dynamic-light pass: one quad across the whole view per light (positions relative to the camera,
// so they give the view ray). The light itself rides along in the vertex data:
//   Color   rgb = light colour, a = strength
//   UV0     = light position x, z (relative to the camera, blocks)
//   UV2.x   = light position y * 8 (signed), UV2.y = radius * 16
//   UV1.x   = how far it can cast shadows (0 = none)

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

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    relPos = Position;
    lightCol = Color;
    lightPos = vec3(UV0.x, float(UV2.x) / 8.0, UV0.y);
    radius = float(UV2.y) / 16.0;
}
