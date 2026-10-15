#version 330

#moj_import <minecraft:globals.glsl>

// A new-moon night, pitch black: whatever only the faint floor of the light map lights (Minecraft never
// lets an unlit place go fully black) sinks into black, while what gives off light of its own - torches,
// fire, lava, glowing things - keeps it. Where the dynamic lights reach (a weapon light, a muzzle flash,
// a burning wreck) the surface shows in their light, as bright as they make it: the light pass can only
// double the picture, so the light it threw is read from the alpha it collected and the surface's own
// colour from how the floor lit it.

#ifdef CLEAR
out vec4 fragColor;

void main() {
    fragColor = vec4(0.0); // (alpha only: the light pass collects its light there)
}
#else
uniform sampler2D Sampler0; // the picture before the lights
uniform sampler2D Sampler1; // after them; alpha: a quarter of the light they threw

flat in vec4 params;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 scene = texture(Sampler0, uv).rgb;
    vec4 lit = texture(Sampler1, uv);
    float e = params.r;
    float floorLight = params.g;

    // the picture's own light above the floor: torches, fire, lava, glowing eyes
    float cut = floorLight * 0.95;
    vec3 own = max(scene - cut, 0.0) / (1.0 - cut);

    // the light the dynamic lights threw: how strong from the alpha, its colour from how they tinted the picture
    float strength = lit.a * 4.0;
    vec3 tint = max(lit.rgb / max(scene, vec3(1.0 / 255.0)) - 1.0, 0.0);
    float top = max(max(tint.r, tint.g), tint.b);
    tint = top > 0.02 ? tint / top : vec3(1.0);
    // the surface as it looks in full light (where only the floor lights it, the picture is its colour times the floor)
    vec3 surface = min(scene / max(floorLight, 0.02), vec3(1.0));
    vec3 thrown = surface * tint * strength;

    vec3 dark = min(own + thrown, vec3(1.0));
    fragColor = vec4(mix(lit.rgb, dark, e), 1.0);
}
#endif
