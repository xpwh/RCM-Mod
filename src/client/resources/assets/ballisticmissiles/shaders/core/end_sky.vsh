#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The End's sky: a quad over the whole view at the far plane (see EndSky.java); its position gives the ray.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    relPos = Position;
}
