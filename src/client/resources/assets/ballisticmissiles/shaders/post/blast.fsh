#version 330

// Blast shock post effect: the picture shudders and refracts in the shock wave, colours split at the
// edges like an overdriven lens, hot highlights bloom and the frame darkens towards the corners.
// Eleven texture reads per pixel, so it costs next to nothing.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlastConfig {
    float Strength;
    float Phase;
};

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float s = Strength;
    vec2 c = texCoord - 0.5;
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 ca = vec2(c.x * aspect, c.y);
    float r = length(ca);
    vec2 dir = r > 1.0e-4 ? c / r : vec2(0.0);

    // shock refraction: concentric ripples rolling outwards plus a slight lens bulge
    float t = Phase * 1.618;
    float ripple = sin(r * 38.0 - t * 2.4) * 0.5 + sin(r * 17.0 + t * 1.3) * 0.5;
    vec2 wobble = vec2(sin(texCoord.y * 23.0 + t * 3.1), cos(texCoord.x * 19.0 - t * 2.7));
    vec2 uv = texCoord + dir * ripple * 0.0045 * s + wobble * 0.0018 * s - c * 0.02 * s * r;

    // chromatic aberration growing towards the edges
    float split = 0.0065 * s * (0.35 + r);
    vec3 col;
    col.r = texture(InSampler, uv + dir * split).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - dir * split).b;

    // cheap bloom: a ring of taps, only the hot parts glow
    vec3 glow = vec3(0.0);
    float radius = 0.012 + 0.012 * s;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398 + t;
        vec2 o = vec2(cos(a) / aspect, sin(a)) * radius;
        vec3 tap = texture(InSampler, uv + o).rgb;
        float lum = dot(tap, vec3(0.299, 0.587, 0.114));
        glow += tap * smoothstep(0.55, 1.0, lum);
    }
    col += glow * (0.16 * s);

    // overexposed, warm grade and a bit more contrast
    col = mix(col, col * vec3(1.12, 0.98, 0.82), 0.6 * s);
    col = mix(col, smoothstep(0.0, 1.0, col), 0.45 * s);

    // vignette closing in, plus dust/grain from the shaken camera
    float vig = 1.0 - smoothstep(0.25, 0.85, r);
    col *= mix(1.0, vig, 0.75 * s);
    col += (hash(texCoord * OutSize + Phase * 17.0) - 0.5) * 0.06 * s;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
