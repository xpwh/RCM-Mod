#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The night sky's additions: a quad over the whole view at the far plane (only where nothing was drawn).
// Color: r how much night it is (with the weather and the new moon), g the northern lights, b quality.
// Normal: the sun's direction (the stars turn with it). UV0.x: seconds, for the twinkle and the motion.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
flat out vec4 info;
flat out vec3 sunDir;
flat out float seconds;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    relPos = Position;
    info = Color;
    sunDir = normalize(Normal);
    seconds = UV0.x;
}
