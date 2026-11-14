#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The world light passes (sun shadows and ambient occlusion; water): one quad over the whole view, far out.
//   Color   = the pass's parameters
//   UV0     = the camera's x, z: water, wrapped every 2048 blocks (for the waves); shade, within the heightmap
//   UV1.x   = shade: the camera's height (+256, *16)
//   UV2     = two 16-bit words of data (water: the zenith sky colour; shade: the quality, the highest
//             column top in the heightmap + 64)
//   Normal  = the direction to the sun

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
flat out vec4 params;
flat out vec2 cameraXZ;
flat out float cameraY;
flat out ivec2 data;
flat out vec3 sunDir;
// clip space back to camera-relative world space, with all the view carries (the walking bob too)
flat out mat4 invViewProj;
flat out mat4 viewProj;

void main() {
    vec4 pos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = pos.xyww;
    relPos = Position;
    params = Color;
    cameraXZ = UV0;
    cameraY = float(UV1.x) / 16.0 - 256.0;
    data = UV2;
    sunDir = normalize(Normal);
    viewProj = ProjMat * ModelViewMat;
    invViewProj = inverse(viewProj);
}
