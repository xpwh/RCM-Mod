#version 330

// Blast post effect, fed every frame from BlastShader through a 4x1 parameter texture:
//  * god rays: light streaming out of the fireball through smoke and dust
//  * a faint anamorphic lens streak while the flash lasts
//  * dust and smoke haze rolling over the picture after the shock wave hits
//  * heat haze over the fireball, motion blur while the camera is shaken
//  * exposure flash in the colour of the blast, bloom on everything hot
//  * shell shock afterwards: washed-out colours, soft focus, tunnel vision while the ears ring
//  * per weapon: nuclear bleach, antimatter violet negative flash, EMP signal glitches
//  * giant blasts: the shock front as a ring of refracting air, the earthquake rolling the picture,
//    water running down the lens after the tsunami's spray,
//    and the negative afterimage a nuclear flash burns into the eye
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
    vec4 p4 = param(4);
    vec4 p5 = param(5);
    vec4 p6 = param(6);
    float strength = p0.r;   // shock hitting the camera
    float exposure = p0.g;   // flash
    float shock = p0.b;      // shell shock / deafness
    int kind = int(p0.a * 255.0 / 36.0 + 0.5); // 0 fire, 1 nuclear, 2 antimatter, 3 emp, 4 underground, 5 kinetic, 6 water
    float ringR = p4.r * 2.0;    // shock front radius on screen (screen heights)
    float ringStrength = p4.g;
    float quake = p4.b;          // earthquake
    float drench = p4.a;         // water on the lens
    vec2 shockC = p5.rg * 3.0 - 1.0;
    vec2 afterPos = p6.rg;
    float afterimage = p6.b;
    float time = (p1.r + p1.g * 256.0) * 255.0 / 100.0;
    float heat = p1.b;
    float rays = p1.a;       // fireball light streaming out
    vec3 tint = p2.rgb;
    vec2 blast = p3.rg;      // explosion position on screen
    float onScreen = step(0.5, p3.b);
    float dust = p3.a;       // dust / smoke haze after the shock
    float winter = p2.a;     // nuclear winter: soot in the stratosphere

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 px = 1.0 / OutSize;
    vec2 uv = texCoord;
    vec2 toBlast = (uv - blast) * vec2(aspect, 1.0);
    float dBlast = length(toBlast);

    // ---- heat haze: slow, wavy refraction in the hot air above the fireball
    if (heat > 0.0 && onScreen > 0.0) {
        vec2 rel = toBlast * vec2(1.6, 1.0) - vec2(0.0, 0.2);
        float column = heat * exp(-dot(rel, rel) * 3.0);
        vec2 n = vec2(noise(uv * vec2(10.0, 22.0) + vec2(0.0, -time * 2.2)), noise(uv * vec2(10.0, 22.0) + vec2(4.7, -time * 2.5))) - 0.5;
        uv += n * column * 0.006;
    }

    // ---- the shock front: a thin shell of compressed air bending the light as it races outwards
    float ringBand = 0.0;
    if (ringStrength > 0.0 && ringR > 0.0) {
        vec2 rel = (uv - shockC) * vec2(aspect, 1.0);
        float d = length(rel);
        float w = 0.008 + ringR * 0.035;
        float k = (d - ringR) / w;
        ringBand = exp(-k * k) * ringStrength;
        uv -= rel / max(d, 1.0e-4) * vec2(1.0 / aspect, 1.0) * ringBand * 0.018 * sign(d - ringR + 1.0e-4);
    }

    // ---- earthquake: the ground (and the camera on it) bounces and rolls
    if (quake > 0.0) {
        float bounce = sin(time * 31.0) * 0.6 + sin(time * 17.0 + 1.3) * 0.4;
        float sway = sin(time * 9.0) * 0.7 + sin(time * 4.3) * 0.3;
        uv += vec2(sway * 0.004, bounce * 0.007) * quake;
    }

    // ---- water on the lens: drops that refract the picture, and runnels sliding down
    float wet = 0.0;
    if (drench > 0.0) {
        vec2 grid = vec2(26.0 * aspect, 26.0);
        vec2 cell = floor(uv * grid);
        float slide = hash(vec2(cell.x, 7.0)) * 0.6 + 0.2;
        vec2 g = uv * grid + vec2(0.0, time * slide * (1.0 - drench) * 6.0);
        vec2 gc = floor(g);
        vec2 f = fract(g) - 0.5;
        float has = step(1.0 - drench * 0.85, hash(gc + 3.7));
        vec2 off = (vec2(hash(gc), hash(gc + 1.3)) - 0.5) * 0.5;
        float r = 0.18 + 0.2 * hash(gc + 9.1);
        vec2 q = f - off;
        float drop = has * smoothstep(r, r * 0.6, length(q * vec2(1.0, 0.8)));
        uv += q / grid * drop * 2.4;
        wet = drop;
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

    // ---- base image with slight lens fringing
    vec2 c = uv - 0.5;
    float split = (0.0012 + (kind == 3 ? 0.012 : 0.0)) * strength * (0.4 + length(c) * 1.6);
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

    // ---- faint anamorphic streak while the flash lasts
    float flare = max(exposure, rays * 0.7) * onScreen;
    if (flare > 0.01) {
        vec3 streak = vec3(0.0);
        for (int i = 1; i <= 6; i++) {
            float o = float(i) * 0.035;
            streak += hot(texture(InSampler, vec2(uv.x + o, uv.y)).rgb, 0.7) + hot(texture(InSampler, vec2(uv.x - o, uv.y)).rgb, 0.7);
        }
        col += streak * vec3(0.6, 0.72, 1.0) * 0.05 * flare;
    }

    // ---- dust and smoke: the shock wave kicks up a brown haze that flattens the picture
    if (dust > 0.0) {
        float drift = noise(uv * vec2(3.0, 5.0) + vec2(time * 0.15, time * 0.05)) * 0.6 + 0.4;
        vec3 dustCol = mix(vec3(0.52, 0.46, 0.38), tint * 0.6 + 0.2, 0.25);
        float thick = 0.45;
        if (kind == 4) {
            dustCol = vec3(0.44, 0.36, 0.27); // the buried burst throws up earth, not smoke
            thick = 0.65;
        } else if (kind == 6) {
            dustCol = vec3(0.86, 0.9, 0.92); // radioactive mist from the column of spray
            thick = 0.5;
        }
        col = mix(col, dustCol * (0.7 + 0.5 * luma(col)), dust * thick * drift);
    }

    // ---- the shock front catches the light: a faint bright edge of condensation
    col += vec3(0.9, 0.93, 1.0) * ringBand * 0.12;

    // ---- water on the lens: a cool, smeared look inside the drops
    if (drench > 0.0) {
        vec3 smear = (texture(InSampler, uv + vec2(0.003, 0.0)).rgb + texture(InSampler, uv - vec2(0.003, 0.0)).rgb
            + texture(InSampler, uv + vec2(0.0, 0.004)).rgb + texture(InSampler, uv - vec2(0.0, 0.004)).rgb) * 0.25;
        col = mix(col, smear * vec3(0.92, 1.0, 1.04), 0.35 * drench);
        col += wet * 0.06 * drench;
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
    if (kind == 4) {
        col = mix(col, col * vec3(1.06, 0.95, 0.8), grade * 0.7); // earth-brown
        col = mix(col, smoothstep(0.0, 1.0, col), 0.3 * grade);
    } else if (kind == 5) {
        col = mix(col, col * vec3(1.04, 0.98, 0.9), grade * 0.5); // dust-dry
        col = mix(col, smoothstep(0.0, 1.0, col), 0.3 * grade);
    } else if (kind == 6) {
        col = mix(col, col * vec3(0.88, 1.0, 1.08), grade * 0.6); // sea-cold
    } else if (kind == 3) {
        col = mix(col, col * vec3(0.8, 1.05, 1.15), grade * 0.6);
        col += (step(0.5, fract(uv.y * OutSize.y * 0.5)) - 0.5) * 0.05 * strength; // scanlines
    } else {
        col = mix(col, col * mix(vec3(1.0), tint * 1.25 + 0.15, 0.35), grade);
        col = mix(col, smoothstep(0.0, 1.0, col), 0.35 * grade);
    }

    // ---- nuclear winter: the sun dimmed behind a grey-brown soot veil, colours drained, the bright
    // sky turned into a flat overcast
    if (winter > 0.0) {
        float l = luma(col);
        col = mix(col, vec3(l), 0.6 * winter);
        col *= 1.0 - 0.42 * winter;
        col *= mix(vec3(1.0), vec3(0.96, 0.93, 0.88), winter);
        col = mix(col, vec3(0.36, 0.35, 0.34), 0.35 * winter * smoothstep(0.45, 0.95, l));
    }

    // ---- the flash's negative afterimage, burnt into the eye: a dark, purple-green blot that stays
    // where the fireball was on screen, fading over many seconds
    if (afterimage > 0.0) {
        vec2 ar = (texCoord - afterPos) * vec2(aspect, 1.0);
        float blot = exp(-dot(ar, ar) / (0.0025 + 0.004 * afterimage));
        float halo = exp(-dot(ar, ar) / 0.02) - blot;
        float pulse = 0.85 + 0.15 * sin(time * 1.7);
        col *= 1.0 - 0.6 * afterimage * blot;
        col += vec3(0.16, 0.02, 0.22) * afterimage * blot * pulse;
        col += vec3(0.0, 0.08, 0.04) * afterimage * max(halo, 0.0) * 0.6;
    }

    // ---- tunnel vision and grain
    float vig = 1.0 - smoothstep(0.35, 0.95, length(c * vec2(aspect * 0.8, 1.0)));
    col *= mix(1.0, vig, 0.45 * strength + 0.5 * shock + 0.15 * rays);
    col += (hash(texCoord * OutSize + fract(time) * 91.0) - 0.5) * (0.045 * strength + 0.03 * shock + 0.015 * rays);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
