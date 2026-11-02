#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The new-moon darkness passes: one quad over the whole view (far out, at the far plane); its colour
// carries the parameters (red: how dark, green: the light map's floor).

in vec3 Position;
in vec4 Color;

flat out vec4 params;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    params = Color;
}
