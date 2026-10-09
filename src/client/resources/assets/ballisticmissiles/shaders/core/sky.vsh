#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The physically based sky: a quad over the whole view, pushed to the far plane so it only shows where
// nothing has been drawn (the sky). Color: r rain, g thunder, b quality, a how much it replaces the
// vanilla sky (fades out at night, for the stars and the moon). Normal: the true sun direction.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
flat out vec4 skyInfo;
flat out vec3 sunDir;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    relPos = Position;
    skyInfo = Color;
    sunDir = normalize(Normal);
}
