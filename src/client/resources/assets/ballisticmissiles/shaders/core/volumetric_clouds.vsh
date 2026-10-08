#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Volumetric clouds: the geometry is just the base and/or top plane of the cloud layer; the fragment
// shader marches through the layer along each view ray. Positions arrive relative to the camera.
//  Color   r coverage, g daylight, b rain, a quality (1 fancy, 0.5 fast)
//  UV0     wind offset (blocks)
//  UV1     base height, thickness (blocks)
//  UV2     x: how far out the planes reach (blocks): the clouds fade out before their edge
//          y: thunderstorm (low 7 bits) and a lightning flash (next 7 bits), 0-127 each
//  Normal  direction to the sun (or the moon at night)

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
out vec4 cloudInfo;
flat out vec2 wind;
flat out ivec2 layer;
flat out vec3 sunDir;
flat out float reach;
flat out float storm;
flat out float lightning;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    relPos = Position;
    cloudInfo = Color;
    wind = UV0;
    layer = UV1;
    sunDir = normalize(Normal);
    reach = float(UV2.x);
    storm = float(UV2.y & 127) / 127.0;
    lightning = float((UV2.y >> 7) & 127) / 127.0;
}
