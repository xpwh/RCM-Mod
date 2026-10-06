#version 330

// Blast post effect, fed every frame from BlastShader through a 4x1 parameter texture:
//  * the shock wave as a refracting ring racing outwards from the explosion, with the white
//    condensation (Wilson cloud) haze riding on it and colour fringing at its edge
//  * god rays: light streaming out of the fireball through smoke and dust
//  * anamorphic lens streak, flare ghosts and lit-up dirt on the lens while the flash lasts
//  * heat shimmer rising over the fireball, motion blur while the camera is shaken
//  * exposure flash in the colour of the blast, bloom on everything hot
//  * shell shock afterwards: washed-out colours, soft focus, tunnel vision while the ears ring
//  * per weapon: nuclear bleach, antimatter violet negative flash, EMP signal glitches
// Expensive parts only run while they are visible; idle the shader is not even active.

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

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 hot(vec3 c, float threshold) {
    return c * smoothstep(threshold, 1.0, luma(c));
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
    float rays = p1.a;       // fireball light streaming out
    vec3 tint = p2.rgb;
    vec2 blast = p3.rg;      // explosion position on screen
    float onScreen = step(0.5, p3.b);
    float ring = p3.a;       // shock ring progress (0 = none)

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 px = 1.0 / OutSize;
    vec2 uv = texCoord;
    vec2 toBlast = (uv - blast) * vec2(aspect, 1.0);
    float dBlast = length(toBlast);

    // ---- shock ring: a lens of compressed air sweeping outwards from the blast
    float ringBand = 0.0;
    if (ring > 0.0) {
        float r = ring * 2.4;
        float band = (dBlast - r) / (0.03 + 0.05 * ring);
        ringBand = exp(-band * band) * (1.0 - ring) * (0.45 + 0.55 * onScreen);
        vec2 n = dBlast > 1.0e-4 ? toBlast / dBlast : vec2(0.0);
        uv -= n / vec2(aspect, 1.0) * ringBand * 0.03 * sign(band + 0.0001);
    }

    // ---- heat shimmer over the fireball (and everywhere, weaker, while the shock rattles the lens)
    if (heat > 0.0 || strength > 0.0) {
        vec2 rel = toBlast * vec2(2.0, 1.0) - vec2(0.0, 0.18);
        float column = onScreen * heat * exp(-dot(rel, rel) * 2.5);
        float amount = column * 0.008 + strength * 0.002;
        vec2 n = vec2(noise(uv * 42.0 + vec2(0.0, -time * 5.0)), noise(uv * 42.0 + vec2(5.2, -time * 5.5))) - 0.5;
        uv += n * amount * 2.0;
    }

    // ---- camera shudder
    vec2 jolt = vec2(sin(time * 61.0) + sin(time * 23.0) * 0.5, cos(time * 53.0) + cos(time * 31.0) * 0.5) * 0.0018 * strength;
    uv += jolt;

    // ---- EMP: the picture tears into shifted bands and rolls
    if (kind == 3 && strength > 0.0) {
        float line = floor(uv.y * 48.0 + floor(time * 18.0) * 7.0);
        float tear = step(0.8, hash(vec2(line, floor(time * 24.0))));
        uv.x += (hash(vec2(line, 3.1)) - 0.5) * 0.09 * tear * strength;
        uv.y += fract(time * 0.7) * 0.02 * strength * step(0.9, hash(vec2(floor(time * 6.0), 1.0)));
    }

    // ---- base image with lens fringing (stronger on the shock ring)
    vec2 c = uv - 0.5;
    float split = (0.002 + (kind == 3 ? 0.012 : 0.0)) * strength * (0.4 + length(c) * 1.6) + ringBand * 0.006;
    vec3 col;
    col.r = texture(InSampler, uv + c * split * 2.0).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - c * split * 2.0).b;

    // ---- motion blur along the shake
    if (strength > 0.15) {
        vec2 dir = normalize(jolt + 1.0e-5) * 0.006 * strength;
        vec3 smear = texture(InSampler, uv + dir).rgb + texture(InSampler, uv - dir).rgb
            + texture(InSampler, uv + dir * 2.0).rgb + texture(InSampler, uv - dir * 2.0).rgb;
        col = mix(col, smear * 0.25, 0.5 * smoothstep(0.15, 0.8, strength));
    }

    // ---- condensation haze riding on the shock front
    col = mix(col, vec3(0.92, 0.93, 0.95), ringBand * 0.22 * (0.6 + 0.4 * noise(uv * 30.0 + time)));

    // ---- bloom: a ring of taps, only the hot parts glow, tinted by the blast
    float glowAmount = max(exposure, max(strength * 0.5, rays * 0.6));
    if (glowAmount > 0.0) {
        vec3 glow = vec3(0.0);
        float radius = 0.008 + 0.022 * glowAmount;
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398 + 0.3;
            glow += hot(texture(InSampler, uv + vec2(cos(a) / aspect, sin(a)) * radius).rgb, 0.5);
        }
        col += glow * 0.12 * glowAmount * mix(vec3(1.0), tint * 1.4, 0.5);
    }

    // ---- god rays: march towards the fireball, collecting the light that leaks out of it
    if (rays > 0.01) {
        vec2 stepv = (blast - uv) / 20.0 * min(1.0, 0.5 / max(length(blast - uv), 1.0e-3) + 0.5);
        vec2 p = uv;
        float decay = 1.0;
        vec3 shafts = vec3(0.0);
        float jitter = hash(texCoord * OutSize + time);
        p += stepv * jitter;
        for (int i = 0; i < 20; i++) {
            p += stepv;
            shafts += hot(texture(InSampler, p).rgb, 0.55) * decay;
            decay *= 0.93;
        }
        col += shafts * 0.09 * rays * mix(vec3(1.0), tint * 1.3, 0.6);
        // the fireball's own glow wrapping the frame around it
        col += tint * exp(-dBlast * 5.0) * 0.35 * rays * onScreen;
    }

    // ---- anamorphic streak and flare ghosts while the flash lasts
    float flare = max(exposure, rays * 0.7) * onScreen;
    if (flare > 0.01) {
        vec3 streak = vec3(0.0);
        for (int i = 1; i <= 6; i++) {
            float o = float(i) * 0.035;
            streak += hot(texture(InSampler, vec2(uv.x + o, uv.y)).rgb, 0.7) + hot(texture(InSampler, vec2(uv.x - o, uv.y)).rgb, 0.7);
        }
        col += streak * vec3(0.55, 0.7, 1.0) * 0.08 * flare;
        // ghosts: discs mirrored through the centre of the lens
        vec2 axis = vec2(0.5) - blast;
        for (int i = 0; i < 4; i++) {
            float k = 0.5 + float(i) * 0.45;
            vec2 g = blast + axis * k * 2.0;
            float size = 0.03 + 0.025 * float(i);
            float dGhost = length((texCoord - g) * vec2(aspect, 1.0));
            float disc = smoothstep(size, size * 0.6, dGhost) * (0.6 - 0.1 * float(i));
            vec3 ghostCol = i == 1 ? vec3(0.4, 1.0, 0.6) : i == 2 ? vec3(0.6, 0.5, 1.0) : tint;
            col += ghostCol * disc * 0.12 * flare;
        }
        // grime on the lens catching the light
        float dirt = smoothstep(0.62, 0.9, noise(texCoord * vec2(aspect, 1.0) * 9.0)) * 0.6
            + smoothstep(0.75, 0.95, noise(texCoord * vec2(aspect, 1.0) * 23.0 + 7.0)) * 0.4;
        col += tint * dirt * 0.25 * flare * (0.4 + 0.6 * exp(-dBlast * 1.5));
    }

    // ---- shell shock: soft focus and washed-out colour while the ears ring
    if (shock > 0.0) {
        vec3 soft = vec3(0.0);
        float r = 0.005 * shock;
        soft += texture(InSampler, uv + vec2(r, 0.0)).rgb;
        soft += texture(InSampler, uv - vec2(r, 0.0)).rgb;
        soft += texture(InSampler, uv + vec2(0.0, r * aspect)).rgb;
        soft += texture(InSampler, uv - vec2(0.0, r * aspect)).rgb;
        col = mix(col, soft * 0.25, 0.6 * shock);
        col = mix(col, vec3(luma(col)) * vec3(1.02, 1.0, 0.96), 0.55 * shock);
    }

    // ---- exposure flash: blown highlights in the blast's colour
    if (exposure > 0.0) {
        vec3 flashCol = kind == 1 ? vec3(1.0, 0.97, 0.9) : tint;
        col = 1.0 - (1.0 - col) * (1.0 - flashCol * exposure * 0.85);
        if (kind == 1) {
            col = mix(col, vec3(luma(col)), exposure * 0.5); // nuclear bleach
        }
        if (kind == 2) {
            col = mix(col, vec3(0.75, 0.55, 1.0) - col * 0.6, exposure * 0.45); // antimatter negative
        }
    }

    // ---- grade: warm, smoky and contrasty in the fire, cold and sick after an EMP
    float grade = max(strength, max(exposure * 0.6, rays * 0.5));
    if (kind == 3) {
        col = mix(col, col * vec3(0.8, 1.05, 1.15), grade * 0.6);
        col += (step(0.5, fract(uv.y * OutSize.y * 0.5)) - 0.5) * 0.05 * strength; // scanlines
    } else {
        col = mix(col, col * mix(vec3(1.0), tint * 1.25 + 0.15, 0.35), grade);
        col = mix(col, smoothstep(0.0, 1.0, col), 0.35 * grade);
    }

    // ---- tunnel vision and grain
    float vig = 1.0 - smoothstep(0.35, 0.95, length(c * vec2(aspect * 0.8, 1.0)));
    col *= mix(1.0, vig, 0.45 * strength + 0.5 * shock + 0.15 * rays);
    col += (hash(texCoord * OutSize + fract(time) * 91.0) - 0.5) * (0.045 * strength + 0.03 * shock + 0.015 * rays);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
