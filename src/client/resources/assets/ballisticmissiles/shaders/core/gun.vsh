#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Guns modelled in Blender (see GunShading): physically based shading per pixel in gun.fsh.
// Position is relative to the camera (the eye at the origin), Normal in the same space, as for entities.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 eyePos;
out vec3 normal;
out vec2 texCoord0;
out vec4 tint;
out vec4 lightMapColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    eyePos = Position;
    normal = Normal;
    texCoord0 = UV0;
    tint = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
}
