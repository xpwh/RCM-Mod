#version 330

// Blast post effect, fed every frame from BlastShader through a 4x1 parameter texture:
//  * the shock wave as a refracting ring racing outwards from the explosion on screen
//  * heat shimmer rising over the fireball
//  * exposure flash in the colour of the blast, bloom on everything hot
//  * subtle lens colour fringing and camera grain while the shock hits
//  * shell shock afterwards: washed-out colours, soft focus, tunnel vision while the ears ring
//  * per weapon: nuclear bleach, antimatter violet negative flash, EMP signal glitches
// About 14 texture reads per pixel - cheap on any GPU.

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

vec4 param(int i) {
    return texelFetch(ParamsSampler, ivec2(i, 0), 0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec4 p0 = param(0);
    vec4 p1 = param(1);
    vec4 p2 = param(2);
    vec4 p3 = param(3);
    float strength = p0.r;   // shock hitting the camera
    float exposure = p0.g;   // flash
    float shock = p0.b;      // shell shock / deafness
    int kind = int(p0.a * 3.0 + 0.5); // 0 fire, 1 nuclear, 2 antimatter, 3 emp
    float time = (p1.r + p1.g * 256.0) * 255.0 / 100.0;
    float heat = p1.b;
    vec3 tint = p2.rgb;
    vec2 blast = p3.rg;      // explosion position on screen
    float onScreen = step(0.5, p3.b);
    float ring = p3.a;       // shock ring radius (0 = none)

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 uv = texCoord;

    // ---- shock ring: a thin lens of compressed air sweeping outwards from the blast
    if (ring > 0.0) {
        vec2 rel = (uv - blast) * vec2(aspect, 1.0);
        float d = length(rel);
        float r = ring * 2.2;
        float band = (d - r) / 0.045;
        float w = exp(-band * band) * (1.0 - ring) * (0.4 + 0.6 * onScreen);
        uv -= (d > 1.0e-4 ? rel / d : vec2(0.0)) / vec2(aspect, 1.0) * w * 0.022 * sign(band + 0.0001);
    }

    // ---- heat shimmer over the fireball (and everywhere, weaker, while the shock rattles the lens)
    if (heat > 0.0 || strength > 0.0) {
        vec2 rel = (uv - blast) * vec2(aspect, 1.0);
        float column = onScreen * heat * exp(-dot(rel * vec2(2.2, 1.0) - vec2(0.0, 0.15), rel * vec2(2.2, 1.0) - vec2(0.0, 0.15)) * 3.0);
        float amount = column * 0.006 + strength * 0.0025;
        vec2 n = vec2(noise(uv * 38.0 + vec2(0.0, -time * 4.0)), noise(uv * 38.0 + vec2(5.2, -time * 4.3))) - 0.5;
        uv += n * amount * 2.0;
    }

    // ---- camera shudder from the shock
    uv += vec2(sin(time * 61.0), cos(time * 53.0)) * 0.0025 * strength;

    // ---- EMP: the picture tears into shifted bands and rolls
    if (kind == 3 && strength > 0.0) {
        float line = floor(uv.y * 48.0 + floor(time * 18.0) * 7.0);
        float tear = step(0.82, hash(vec2(line, floor(time * 24.0))));
        uv.x += (hash(vec2(line, 3.1)) - 0.5) * 0.08 * tear * strength;
        uv.y += fract(time * 0.7) * 0.02 * strength * step(0.9, hash(vec2(floor(time * 6.0), 1.0)));
    }

    // ---- lens fringing grows towards the corners
    vec2 c = uv - 0.5;
    float split = (0.0025 + (kind == 3 ? 0.01 : 0.0)) * strength * (0.4 + length(c) * 1.6);
    vec3 col;
    col.r = texture(InSampler, uv + c * split * 2.0).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - c * split * 2.0).b;

    // ---- bloom: a ring of taps, only the hot parts glow, tinted by the blast
    float glowAmount = max(exposure, strength * 0.5);
    if (glowAmount > 0.0) {
        vec3 glow = vec3(0.0);
        float radius = 0.008 + 0.02 * glowAmount;
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398 + 0.3;
            vec3 tap = texture(InSampler, uv + vec2(cos(a) / aspect, sin(a)) * radius).rgb;
            float lum = dot(tap, vec3(0.299, 0.587, 0.114));
            glow += tap * smoothstep(0.5, 1.0, lum);
        }
        col += glow * 0.11 * glowAmount * mix(vec3(1.0), tint * 1.4, 0.5);
    }

    // ---- shell shock: soft focus and washed-out colour while the ears ring
    if (shock > 0.0) {
        vec3 soft = vec3(0.0);
        float r = 0.004 * shock;
        soft += texture(InSampler, uv + vec2(r, 0.0)).rgb;
        soft += texture(InSampler, uv - vec2(r, 0.0)).rgb;
        soft += texture(InSampler, uv + vec2(0.0, r * aspect)).rgb;
        soft += texture(InSampler, uv - vec2(0.0, r * aspect)).rgb;
        col = mix(col, soft * 0.25, 0.6 * shock);
        float grey = dot(col, vec3(0.299, 0.587, 0.114));
        col = mix(col, vec3(grey) * vec3(1.02, 1.0, 0.96), 0.55 * shock);
    }

    // ---- exposure flash: blown highlights in the blast's colour
    if (exposure > 0.0) {
        vec3 flashCol = kind == 1 ? vec3(1.0, 0.97, 0.9) : tint;
        col = mix(col, 1.0 - (1.0 - col) * (1.0 - flashCol * exposure * 0.85), 1.0);
        if (kind == 1) {
            col = mix(col, vec3(dot(col, vec3(0.333))), exposure * 0.5); // nuclear bleach
        }
        if (kind == 2) {
            col = mix(col, vec3(0.75, 0.55, 1.0) - col * 0.6, exposure * 0.45); // antimatter negative
        }
    }

    // ---- grade: warm and contrasty in the fire, cold and sick after an EMP
    float grade = max(strength, exposure * 0.6);
    if (kind == 3) {
        col = mix(col, col * vec3(0.8, 1.05, 1.15), grade * 0.6);
        col += (step(0.5, fract(uv.y * OutSize.y * 0.5)) - 0.5) * 0.05 * strength; // scanlines
    } else {
        col = mix(col, col * mix(vec3(1.0), tint * 1.25 + 0.15, 0.35), grade);
        col = mix(col, smoothstep(0.0, 1.0, col), 0.35 * grade);
    }

    // ---- tunnel vision and grain
    float vig = 1.0 - smoothstep(0.35, 0.95, length(c * vec2(aspect * 0.8, 1.0)));
    col *= mix(1.0, vig, 0.45 * strength + 0.5 * shock);
    col += (hash(texCoord * OutSize + fract(time) * 91.0) - 0.5) * (0.045 * strength + 0.03 * shock);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
