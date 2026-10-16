#version 330

// Blast post effect, fed every frame from BlastShader through a 4x1 parameter texture:
//  * god rays: light streaming out of the fireball through smoke and dust
//  * a faint anamorphic lens streak while the flash lasts
//  * dust and smoke haze rolling over the picture after the shock wave hits
//  * heat haze over the fireball, motion blur while the camera is shaken
//  * exposure flash in the colour of the blast, bloom on everything hot
//  * shell shock afterwards: washed-out colours, soft focus, tunnel vision while the ears ring
//  * per weapon: nuclear bleach, antimatter violet negative flash, EMP signal glitches
//  * giant blasts: the earthquake rolling the picture, water running down the lens
//  * standing inside thick smoke: the view fades into swirling grey after the tsunami's spray,
//    and the negative afterimage a nuclear flash burns into the eye
//  * shock and blood loss: the colour draining out of the world, the edges going black and throbbing
//    with the heart, the picture doubling and swimming as the eyes stop holding it together
//  * always (as set): bloom round lamps, fire and the sun; the sun's lens flare (hidden when something
//    stands in front of it); the picture blown out white stepping from the dark into daylight
//  * night vision: the world amplified into grainy green, lamps flaring, seen through round eyepieces;
//    thermal: warm bodies and hot engines white on the cold grey land, the sky black
// Expensive parts only run while they are visible.

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;
uniform sampler2D DepthSampler; // the world's depth at the end of the main pass (no hand in it)

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

// ---- water on the lens. A drop on glass is a tiny lens: through it you see a small, upside-down and
// slightly soft picture of what lies around it, framed by a dark rim where the light bends away, with a
// glint of the sky on its upper side and light gathered in its lower edge.
struct Lens {
    vec2 bend;    // where the light through this pixel comes from (screen units, x times aspect)
    float cover;  // how much of the pixel lies under water
    float rim;
    float glint;
};

void bead(inout Lens l, vec2 q, float r, float seed) {
    if (r <= 0.0) return;
    float a = atan(q.y, q.x);
    // no drop is a perfect circle
    float rr = r * (1.0 + 0.06 * sin(2.0 * a + seed * 6.3) + 0.03 * sin(3.0 * a + seed * 17.0));
    float x = length(q) / rr;
    if (x >= 1.0) return;
    float c = smoothstep(1.0, 0.8, x);
    l.bend = mix(l.bend, -q * 3.4, c);
    l.rim = mix(l.rim, smoothstep(0.5, 0.97, x), c);
    vec2 s = q / rr - vec2(-0.3, 0.36);
    float spot = smoothstep(0.22, 0.0, length(s * vec2(1.0, 1.5)));
    float caustic = smoothstep(-0.4, -0.8, q.y / rr) * smoothstep(1.0, 0.7, x);
    l.glint = mix(l.glint, spot + caustic * 0.25, c);
    l.cover = max(l.cover, c);
}

// beads sitting still on the glass, scattered over a grid; as the water runs off they shrink and go one
// by one
void beads(inout Lens l, vec2 P, float rows, float amount, float rmin, float rmax, float salt) {
    vec2 id = floor(P * rows);
    vec2 f = fract(P * rows) - 0.5;
    float h = hash(id + salt);
    float r = mix(rmin, rmax, pow(hash(id + salt + 1.3), 2.0)) * smoothstep(h, h + 0.15, amount);
    vec2 off = (vec2(hash(id + salt + 2.1), hash(id + salt + 3.7)) - 0.5) * max(0.0, 1.0 - 2.4 * rmax);
    bead(l, (f - off) / rows, r / rows, hash(id + salt + 5.3));
}

// big drops that run down the glass in fits and starts, wandering a little, wiping the beads off their
// path and leaving a wet streak and a string of tiny beads behind
void runners(inout Lens l, vec2 P, float t, float amount, float salt) {
    const float COLS = 7.0;
    float col = floor(P.x * COLS);
    float h = hash(vec2(col, salt));
    float present = smoothstep(h, h + 0.12, amount);
    if (present <= 0.0) return;
    float speed = 0.12 + 0.16 * hash(vec2(col, salt + 1.0));
    float u = t * speed * 6.0 + h * 6.0;
    float stepped = floor(u) + smoothstep(0.45, 1.0, fract(u)); // stick, then slip
    float y = 1.2 - fract(stepped / 6.0) * 1.65;
    float cx = (col + 0.3 + 0.4 * hash(vec2(col, salt + 2.0))) / COLS;
    float wig = 0.18 / COLS;
    float ph = h * 6.283;
    float r = (0.011 + 0.012 * hash(vec2(col, salt + 3.0))) * present;

    // the streak above it, narrowing and drying out with distance
    float above = P.y - y;
    float trail = 0.0;
    if (above > 0.0 && above < 0.32) {
        float fade = 1.0 - above / 0.32;
        float dx = P.x - (cx + wig * sin(P.y * 9.0 + ph));
        float w = r * (0.25 + 0.35 * fade);
        trail = smoothstep(w, w * 0.4, abs(dx)) * fade;
        float keep = 1.0 - smoothstep(0.0, 0.3, trail);
        l.cover *= keep;
        l.rim *= keep;
        l.glint *= keep;
        l.bend *= keep;
        // the film itself: a thin, faint smear of refraction
        l.bend += vec2(-dx * 0.6, 0.0) * trail;
        l.cover = max(l.cover, trail * 0.35);
        // tiny beads strung along it
        const float GAP = 0.016;
        float row = floor(P.y / GAP);
        float bh = hash(vec2(col + salt, row));
        float by = (row + 0.5) * GAP;
        vec2 bc = vec2(cx + wig * sin(by * 9.0 + ph) + (hash(vec2(row, col + salt)) - 0.5) * r * 0.6, by);
        float br = step(0.35, bh) * r * (0.18 + 0.22 * bh) * smoothstep(0.0, 0.25, fade);
        if (by > y + r) bead(l, P - bc, br, bh);
    }
    vec2 c = vec2(cx + wig * sin(y * 9.0 + ph), y);
    vec2 q = P - c;
    q.y *= q.y > 0.0 ? 0.78 : 1.0; // round and heavy below, drawn out where it pulls the streak
    bead(l, q, r, h);
}

void main() {
    vec4 p0 = param(0);
    vec4 p1 = param(1);
    vec4 p2 = param(2);
    vec4 p3 = param(3);
    vec4 p4 = param(4);
    vec4 p5 = param(5);
    vec4 p6 = param(6);
    vec4 p7 = param(7);
    float strength = p0.r;   // shock hitting the camera
    float exposure = p0.g;   // flash
    float shock = p0.b;      // shell shock / deafness
    int kind = int(p0.a * 255.0 / 36.0 + 0.5); // 0 fire, 1 nuclear, 2 antimatter, 3 emp, 4 underground, 5 kinetic, 6 water
    float quake = p4.b;          // earthquake
    float suppress = p4.r;       // under fire: rounds cracking past
    float drench = p4.a;         // water on the lens
    vec2 afterPos = p6.rg;
    float afterimage = p6.b;
    float smoke = p5.a;          // standing inside dense smoke
    vec3 smokeCol = p5.rgb;
    float time = (p1.r + p1.g * 256.0) * 255.0 / 100.0;
    float heat = p1.b;
    float rays = p1.a;       // fireball light streaming out
    vec3 tint = p2.rgb;
    vec2 blast = p3.rg;      // explosion position on screen
    float onScreen = step(0.5, p3.b);
    float dust = p3.a;       // dust / smoke haze after the shock
    float winter = p2.a;     // nuclear winter: soot in the stratosphere
    float bloodLoss = p7.r;  // shock and blood loss
    float pulse = p7.g;      // the heartbeat's throb, 0..1
    float doubled = p7.b;    // double vision

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

    // ---- a star's core collapsing: the light bent round it as round a black hole (the picture behind it
    // pulled into a ring at the edge of its reach), and a burning star's hot air boiling round it
    vec4 p11 = param(11);
    if (p11.b > 0.0 || p11.a > 0.0) {
        vec2 core = (p11.rg - 0.25) * 2.0;
        float reach = p11.b * 0.5;
        vec2 d = (uv - core) * vec2(aspect, 1.0);
        float r2 = max(dot(d, d), reach * reach * 0.09);
        // the thin-lens deflection: what is seen at d comes from d - reach^2 d / |d|^2
        vec2 bend = -d * reach * reach / r2 * smoothstep(reach * 6.0, reach * 1.5, sqrt(r2));
        uv += bend / vec2(aspect, 1.0);
        float shimmer = p11.a;
        if (shimmer > 0.0) {
            float rr = length(d);
            float ring = exp(-rr * rr * 7.0);
            vec2 n = vec2(noise(uv * vec2(14.0, 26.0) + vec2(0.0, -time * 3.1)), noise(uv * vec2(14.0, 26.0) + vec2(5.3, -time * 3.4))) - 0.5;
            uv += n * ring * shimmer * 0.012;
        }
    }

    // ---- earthquake: the ground (and the camera on it) bounces and rolls
    if (quake > 0.0) {
        float bounce = sin(time * 31.0) * 0.6 + sin(time * 17.0 + 1.3) * 0.4;
        float sway = sin(time * 9.0) * 0.7 + sin(time * 4.3) * 0.3;
        uv += vec2(sway * 0.004, bounce * 0.007) * quake;
    }

    // ---- water on the lens: beads of every size refracting the picture, and drops running down
    Lens lens = Lens(vec2(0.0), 0.0, 0.0, 0.0);
    if (drench > 0.0) {
        vec2 P = vec2(uv.x * aspect, uv.y);
        beads(lens, P, 28.0, drench * 0.65, 0.1, 0.3, 3.7);
        beads(lens, P, 11.0, drench * 0.45, 0.1, 0.27, 11.3);
        runners(lens, P, time, drench * 1.3 - 0.05, 21.0);
        uv += lens.bend / vec2(aspect, 1.0) * lens.cover;
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

    // ---- inside a smoke cloud: the world fades into the billowing grey, swirling past
    if (smoke > 0.0) {
        float swirl = noise(uv * vec2(4.0, 6.0) + vec2(time * 0.21, time * 0.13)) * 0.55
            + noise(uv * vec2(11.0, 9.0) - vec2(time * 0.37, time * 0.05)) * 0.45;
        vec3 soft = (texture(InSampler, uv + vec2(0.004, 0.0)).rgb + texture(InSampler, uv - vec2(0.004, 0.0)).rgb
            + texture(InSampler, uv + vec2(0.0, 0.005)).rgb + texture(InSampler, uv - vec2(0.0, 0.005)).rgb) * 0.25;
        col = mix(col, soft, 0.5 * smoke);
        float thick = clamp(smoke * (0.75 + 0.5 * swirl), 0.0, 0.97);
        col = mix(col, smokeCol * (0.8 + 0.4 * swirl), thick);
    }

    // ---- water on the lens: soft focus inside the water, the rims dark, the glints bright
    if (drench > 0.0) {
        vec3 smear = (texture(InSampler, uv + vec2(0.0025, 0.0)).rgb + texture(InSampler, uv - vec2(0.0025, 0.0)).rgb
            + texture(InSampler, uv + vec2(0.0, 0.0035)).rgb + texture(InSampler, uv - vec2(0.0, 0.0035)).rgb) * 0.25;
        float w = lens.cover;
        col = mix(col, smear, 0.04 * drench + 0.55 * w);
        col *= 1.0 - 0.42 * lens.rim * w;
        col *= 1.0 + 0.06 * w;
        col += vec3(0.92, 0.96, 1.0) * lens.glint * w * (0.12 + 0.45 * luma(smear));
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

    // ---- under fire: the edges of the picture go soft and dark, the colour drains, the eye
    // narrows onto the middle
    if (suppress > 0.0) {
        float edge = smoothstep(0.12, 0.7, length(c * vec2(aspect * 0.8, 1.0)));
        float rad = (1.5 + 5.5 * edge) * suppress;
        vec3 soft = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7854 + time * 0.3;
            soft += texture(InSampler, uv + vec2(cos(a), sin(a)) * px * rad).rgb;
            soft += texture(InSampler, uv + vec2(cos(a + 0.39), sin(a + 0.39)) * px * rad * 2.0).rgb;
        }
        soft /= 16.0;
        col = mix(col, soft, clamp(suppress * (0.35 + 0.65 * edge), 0.0, 1.0));
        col = mix(col, vec3(luma(col)), 0.3 * suppress);
        float tunnel = 1.0 - smoothstep(0.18, 0.85, length(c * vec2(aspect * 0.8, 1.0)));
        col *= mix(1.0, tunnel, 0.6 * suppress);
    }

    // ---- shock and blood loss: the picture doubles and swims, the colour drains away, the edges go
    // black and close in, throbbing with every beat of the heart
    if (bloodLoss > 0.0) {
        if (doubled > 0.0) {
            vec2 drift = vec2(sin(time * 0.9) + 0.4 * sin(time * 2.3), 0.6 * cos(time * 0.7)) * 0.016 * doubled;
            vec3 ghost = texture(InSampler, uv + drift).rgb;
            vec3 ghost2 = texture(InSampler, uv - drift * 0.45).rgb;
            col = mix(col, (ghost + ghost2) * 0.5, 0.45 * doubled);
            vec3 soft = vec3(0.0);
            float r = 0.0045 * doubled;
            for (int i = 0; i < 6; i++) {
                float a = float(i) * 1.0472 + time * 0.2;
                soft += texture(InSampler, uv + vec2(cos(a) / aspect, sin(a)) * r).rgb;
            }
            col = mix(col, soft / 6.0, 0.4 * doubled);
        }
        float l = luma(col);
        col = mix(col, vec3(l) * vec3(0.95, 0.97, 1.03), clamp(bloodLoss * 1.05, 0.0, 0.95));
        col *= 1.0 - 0.22 * bloodLoss;
        float rr = length(c * vec2(aspect * 0.8, 1.0));
        float inner = mix(0.95, 0.1, bloodLoss) - pulse * 0.1 * (0.4 + bloodLoss);
        float edge = smoothstep(inner, inner + 0.45, rr);
        col *= 1.0 - edge * clamp(0.55 + 0.45 * bloodLoss + pulse * 0.25, 0.0, 1.0);
        col *= 1.0 - pulse * 0.12 * bloodLoss;
    }

    vec4 p8 = param(8);
    vec4 p9 = param(9);
    vec4 p10 = param(10);

    // ---- bloom: the bright glowing over what is round them (lamps, fire, the sun)
    float bloomAmt = p10.r;
    if (bloomAmt > 0.0) {
        float threshold = p10.b;
        vec3 glow = vec3(0.0);
        float weight = 0.0;
        for (int ring = 1; ring <= 3; ring++) {
            float rad = float(ring) * float(ring) * 0.0035 + 0.002;
            float w = 1.0 / float(ring);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398 + float(ring) * 0.39;
                glow += hot(texture(InSampler, uv + vec2(cos(a) / aspect, sin(a)) * rad).rgb, threshold) * w;
                weight += w;
            }
        }
        col += glow / weight * bloomAmt * 1.1;
    }

    // ---- the sun's lens flare: only while it is in clear view (the world's depth round it is the sky's)
    float flareAmt = p9.b;
    if (flareAmt > 0.0) {
        vec2 sunPos = p9.rg;
        float vis = 0.0;
        for (int i = 0; i < 9; i++) {
            vec2 s = sunPos + vec2(float(i % 3) - 1.0, float(i / 3) - 1.0) * vec2(0.005 / aspect, 0.005);
            if (s.x > 0.0 && s.y > 0.0 && s.x < 1.0 && s.y < 1.0) {
                vis += step(0.99999, texture(DepthSampler, s).r);
            }
        }
        float k = flareAmt * vis / 9.0;
        if (k > 0.0) {
            vec2 d = (texCoord - sunPos) * vec2(aspect, 1.0);
            float r = length(d);
            // the glare round the sun, and a faint six-pointed starburst from the iris
            col += vec3(1.0, 0.93, 0.8) * exp(-r * 7.0) * 0.3 * k;
            float ang = atan(d.y, d.x);
            col += vec3(1.0, 0.96, 0.88) * pow(abs(cos(ang * 3.0)), 40.0) * exp(-r * 4.0) * 0.1 * k;
            // ghosts of the aperture strung along the line through the middle of the frame
            vec2 axis = vec2(0.5) - sunPos;
            for (int i = 0; i < 6; i++) {
                float f = 0.3 + float(i) * 0.38;
                float size = 0.02 + 0.03 * fract(float(i) * 0.618 + 0.2);
                vec2 g = (texCoord - (sunPos + axis * f * 2.0)) * vec2(aspect, 1.0);
                float disc = smoothstep(size, size * 0.6, length(g));
                vec3 tint = vec3(0.55 + 0.45 * sin(float(i) * 1.7), 0.6 + 0.4 * sin(float(i) * 1.7 + 2.1), 0.7 + 0.3 * sin(float(i) * 1.7 + 4.2));
                col += tint * disc * 0.05 * k;
            }
            // a pale ring round the middle of the frame
            float ring = length((texCoord - (sunPos + axis * 2.0 * 0.5)) * vec2(aspect, 1.0));
            col += vec3(0.6, 0.8, 1.0) * smoothstep(0.012, 0.0, abs(ring - 0.32)) * 0.03 * k;
        }
    }

    // ---- out of the dark into daylight: blown out white, settling as the eye closes down
    float over = p10.g;
    if (over > 0.0) {
        col = 1.0 - pow(1.0 - clamp(col, 0.0, 1.0), vec3(1.0 + 4.0 * over));
        col = mix(col, vec3(1.0), over * 0.3);
    }

    // ---- tunnel vision and grain
    float vig = 1.0 - smoothstep(0.35, 0.95, length(c * vec2(aspect * 0.8, 1.0)));
    col *= mix(1.0, vig, 0.45 * strength + 0.5 * shock + 0.15 * rays);
    col += (hash(texCoord * OutSize + fract(time) * 91.0) - 0.5) * (0.045 * strength + 0.03 * shock + 0.015 * rays);

    // ---- night vision: an image intensifier - what little light there is amplified into green, the
    // brightest flaring out, a fine boiling grain over everything, seen through round eyepieces
    float nv = p8.r;
    if (nv > 0.0) {
        float gain = 2.0 + 16.0 * p8.a;
        float l = luma(col);
        float lit = 1.0 - exp(-l * gain);
        lit = pow(lit, 1.5); // the tube's contrast: dark stays dark, the lit stands out
        vec3 halo = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398;
            halo += hot(texture(InSampler, uv + vec2(cos(a) / aspect, sin(a)) * 0.012).rgb, 0.25);
        }
        lit += luma(halo) / 8.0 * gain * 0.05;
        float grain = hash(texCoord * OutSize + fract(time * 7.31) * 113.0);
        lit = lit * (0.85 + 0.3 * grain) + (grain - 0.5) * 0.04;
        lit += p8.b * 0.7; // a fresh tube warming up
        vec3 phosphor = vec3(0.3, 1.0, 0.42) * clamp(lit, 0.0, 1.2) + vec3(0.015, 0.04, 0.02);
        float rr = length((texCoord - 0.5) * vec2(aspect, 1.0));
        phosphor *= 1.0 - smoothstep(0.43, 0.5, rr);
        phosphor *= 1.0 - 0.25 * smoothstep(0.2, 0.45, rr);
        col = mix(col, phosphor, nv);
    }

    // ---- thermal: brightness is heat - the cold land a flat grey, bodies and engines white, the sky black
    float thermalView = p8.g;
    if (thermalView > 0.0) {
        vec3 soft = (texture(InSampler, uv + vec2(px.x, 0.0)).rgb + texture(InSampler, uv - vec2(px.x, 0.0)).rgb
            + texture(InSampler, uv + vec2(0.0, px.y)).rgb + texture(InSampler, uv - vec2(0.0, px.y)).rgb) * 0.25;
        float l = luma(mix(col, soft, 0.5));
        float h = l < 0.72 ? 0.07 + l * 0.42 : mix(0.37, 1.0, smoothstep(0.72, 0.95, l));
        float skyPx = step(0.99999, texture(DepthSampler, uv).r);
        h = mix(h, 0.03, skyPx);
        h += (hash(texCoord * OutSize + fract(time * 5.7) * 71.0) - 0.5) * 0.035;
        col = mix(col, vec3(clamp(h, 0.0, 1.0)), thermalView);
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
