#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The Sun Destroyer's star: its surface, and its light in the air round it, about one centre.
//   Position = the centre, relative to the camera (the same for all four corners: the quad is spread here)
//   Color    = temperature (r), dark core (g), brightness (b), seed (a)
//   UV0      = how far the quad must reach (x, 0: the whole screen), seconds (y)
//   UV1      = the star's radius * 64 (x), the shock front's radius * 16 (y)
//   UV2      = corner (x, 0-3), what is drawn (y: 0 the star's surface, 1 its light in the air and the shock,
//              2 the same for a light with no surface of its own)

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

flat out vec3 centre;
flat out vec4 look;
flat out float radius;
flat out float shock;
flat out float seconds;
flat out int mode;
flat out mat4 invProj;

void main() {
    vec4 c = ModelViewMat * vec4(Position, 1.0);
    centre = c.xyz;
    look = Color;
    radius = float(UV1.x) / 64.0;
    shock = float(UV1.y) / 16.0;
    seconds = UV0.y;
    mode = UV2.y;
    invProj = inverse(ProjMat);
    vec2 corner = vec2(float(UV2.x & 1), float((UV2.x >> 1) & 1)) * 2.0 - 1.0;
    float reach = UV0.x;
    float dist = length(c.xyz);
    if (reach <= 0.0 || dist < reach * 1.2 || c.z > -reach) {
        // close by (or asked to): the whole screen
        gl_Position = vec4(corner, 1.0, 1.0);
        return;
    }
    // a quad round the centre, wide enough for the sphere's outline seen in perspective
    float s = 1.3 * reach / sqrt(max(1.0 - reach * reach / (dist * dist), 0.05));
    vec4 p = ProjMat * vec4(c.xyz + vec3(corner * s, 0.0), 1.0);
    gl_Position = p.xyww;
}
