#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Shared by the volumetric clouds and the light pass (see include/clouds_common.glsl for the vertex
// data). For the clouds the geometry is the base and/or top plane of the cloud layer; for the light pass
// a quad over the whole view. Either way positions arrive relative to the camera, so they give the ray.

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
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    relPos = Position;
    cloudInfo = Color;
    vWind = UV0;
    vLayer = UV1;
    sunDir = normalize(Normal);
    reach = float(UV2.x);
    vStorm = float(UV2.y & 127) / 127.0;
    lightning = float((UV2.y >> 7) & 127) / 127.0;
}
