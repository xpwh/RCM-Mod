#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// A rocket's (or jet's) exhaust plume: layered shells of revolution down the nozzle's axis, coloured and
// shaped per pixel by exhaust_plume.fsh. Vertex data (see ExhaustPlume):
//   UV0     x round the plume (0-1), y along it (0 at the nozzle, 1 at the tip)
//   Color   r how hot this shell burns (the core 255), g style (0 solid motor, 1 jet / afterburner,
//           2 re-entry plasma), b a random phase, a opacity
//   UV1     x plume length * 16, y nozzle radius * 256

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 relPos;
out vec3 normal;
out vec2 uv;
flat out vec4 params;
flat out vec2 size;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    relPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    normal = mat3(ModelViewMat) * Normal;
    uv = UV0;
    params = Color;
    size = vec2(float(UV1.x) / 16.0, float(UV1.y) / 256.0);
}
