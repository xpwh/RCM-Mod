#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The light pass of the volumetric clouds (shadows and shafts): a quad over the whole view, set far out
// and pushed to the far plane (the pass ignores depth). Positions arrive relative to the camera, so they
// give the ray. Vertex data as in include/clouds_common.glsl.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
out vec4 cloudInfo;
flat out vec2 vWind;
flat out ivec2 vLayer;
flat out vec3 sunDir;
flat out float reach;
flat out float vStorm;
flat out float lightning;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // at the far plane: the quad sits far out (so the walking bob cannot swing it), past where it would clip
    gl_Position = pos.xyww;
    relPos = Position;
    cloudInfo = Color;
    vWind = UV0;
    vLayer = UV1;
    sunDir = normalize(Normal);
    reach = float(UV2.x);
    vStorm = float(UV2.y & 127) / 127.0;
    lightning = float((UV2.y >> 7) & 127) / 127.0;
}
