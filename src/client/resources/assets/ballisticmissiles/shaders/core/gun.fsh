#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:light.glsl>

// Physically based shading for guns, so they look in game as they do in the Blender renders:
//   Sampler0  base colour (sRGB), baked from the Blender materials
//   Sampler3  r metallic, g roughness, b ambient occlusion, a clear coat (the lacquer on the wood)
// Lit by Minecraft's two fixed entity lights as sun-like keys (GGX highlights on the steel, a sheen on the
// lacquer), a sky/ground hemisphere for the soft light, and a studio-like surrounding that the metal
// mirrors - bright horizon, blue sky above, dark ground below - blurred by roughness. Then the light
// level (lightmap), a filmic curve and the fog.

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 eyePos;
in vec3 normal;
in vec2 texCoord0;
in vec4 tint;
in vec4 lightMapColor;

out vec4 fragColor;

const float PI = 3.14159265;

vec3 toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

// the surroundings the metal reflects, by direction; rough surfaces see a blurred version of it
vec3 environment(vec3 dir, float rough) {
    float y = dir.y;
    vec3 sky = mix(vec3(0.95, 0.96, 0.98) * 1.35, vec3(0.38, 0.47, 0.62), smoothstep(0.0, 0.7, y));
    vec3 ground = mix(vec3(0.42, 0.38, 0.33), vec3(0.12, 0.11, 0.1), smoothstep(0.0, 0.5, -y));
    // the bright band at the horizon, sharp on polished steel, spread out on rough
    float band = exp(-abs(y - 0.06) * mix(28.0, 3.0, rough)) * mix(1.1, 0.3, rough);
    vec3 env = (y > 0.0 ? sky : ground) + vec3(band);
    vec3 average = vec3(0.55, 0.58, 0.62);
    return mix(env, average, rough * rough * 0.85);
}

float ggx(float nh, float a) {
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float smith(float nv, float nl, float rough) {
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    return nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
}

vec3 fresnel(vec3 f0, float vh) {
    return f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
}

// one sun-like light: diffuse and GGX highlight, plus the clear coat's own sharper highlight
vec3 keyLight(vec3 n, vec3 v, vec3 l, vec3 colour, vec3 base, float metal, float rough, float coat) {
    float nl = max(dot(n, l), 0.0);
    if (nl <= 0.0) {
        return vec3(0.0);
    }
    float nv = max(dot(n, v), 1.0e-3);
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 0.0);
    float vh = max(dot(v, h), 0.0);
    vec3 f0 = mix(vec3(0.04), base, metal);
    vec3 f = fresnel(f0, vh);
    float a = max(rough * rough, 0.002);
    vec3 spec = ggx(nh, a) * smith(nv, nl, rough) * f / (4.0 * nv * nl + 1.0e-4);
    vec3 diffuse = (1.0 - f) * (1.0 - metal) * base / PI;
    vec3 c = (diffuse + spec) * nl;
    if (coat > 0.0) {
        float fc = 0.04 + 0.96 * pow(1.0 - vh, 5.0);
        c += coat * ggx(nh, 0.05) * smith(nv, nl, 0.22) * fc / (4.0 * nv * nl + 1.0e-4) * nl;
    }
    return c * colour;
}

void main() {
    vec4 albedo = texture(Sampler0, texCoord0) * tint;
    vec4 mat = texture(Sampler3, texCoord0);
    vec3 base = toLinear(albedo.rgb);
    float metal = mat.r;
    // blued steel is satin, not chrome
    float rough = clamp(mat.g + 0.14 * mat.r, 0.04, 1.0);
    float ao = mat.b;
    float coat = mat.a;

    vec3 n = normalize(normal);
    vec3 v = normalize(-eyePos);
    if (dot(n, v) < 0.0) {
        n = -n; // seen from behind (thin parts)
    }
    float nv = max(dot(n, v), 1.0e-3);

    // the two entity lights as keys: the first warm and strong, the second a cooler fill
    vec3 col = keyLight(n, v, normalize(Light0_Direction), vec3(1.0, 0.96, 0.9) * 3.4, base, metal, rough, coat);
    col += keyLight(n, v, normalize(Light1_Direction), vec3(0.85, 0.9, 1.0) * 1.5, base, metal, rough, coat);

    // soft light from the sky above and the ground below
    vec3 hemi = mix(vec3(0.32, 0.29, 0.25), vec3(0.66, 0.69, 0.74), n.y * 0.5 + 0.5) * 0.9;
    vec3 f0 = mix(vec3(0.04), base, metal);
    vec3 fr = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - nv, 5.0);
    col += (1.0 - fr) * (1.0 - metal) * base * hemi * ao;

    // what the surface mirrors
    vec3 r = reflect(-v, n);
    vec3 env = environment(r, rough);
    col += env * fr * ao * mix(0.6, 0.85, metal);
    if (coat > 0.0) {
        float fc = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        col += coat * fc * environment(r, 0.22) * ao * 0.7;
    }

    // the light where it is: daylight, torchlight, night
    col *= lightMapColor.rgb * ColorModulator.rgb;

    // filmic curve (ACES fit), back to display colours
    col = clamp((col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14), 0.0, 1.0);
    col = pow(col, vec3(1.0 / 2.2));
    fragColor = apply_fog(vec4(col, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
        FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
