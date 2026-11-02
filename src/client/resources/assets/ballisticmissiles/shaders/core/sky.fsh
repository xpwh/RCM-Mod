#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <ballisticmissiles:atmosphere.glsl>
#moj_import <ballisticmissiles:bm_wind.glsl>

// The physically based sky (see include/atmosphere.glsl), with the sun's disc. Below the horizon it runs
// into the fog colour, which Sky.java sets to this same sky at the horizon, so the land meets it cleanly.

in vec3 relPos;
flat in vec4 skyInfo;
flat in vec3 sunDir;

out vec4 fragColor;

// The rainbow: sunlight refracted and reflected in the raindrops, a circle round the point opposite the sun -
// red outside at 42 degrees, violet inside at 40.5; the fainter secondary bow at 50-53.5 degrees with its colours
// reversed, the sky between them darker (Alexander's band), brighter inside the primary.
vec3 spectrum(float t) {
    // t 0 violet .. 1 red
    vec3 c;
    c.r = exp(-pow((t - 0.92) / 0.22, 2.0)) + 0.35 * exp(-pow((t - 0.05) / 0.08, 2.0));
    c.g = exp(-pow((t - 0.55) / 0.2, 2.0));
    c.b = exp(-pow((t - 0.16) / 0.2, 2.0));
    return c;
}

vec3 rainbow(vec3 rd, vec3 sunDir, float strength) {
    float ang = degrees(acos(clamp(dot(rd, -sunDir), -1.0, 1.0)));
    vec3 col = vec3(0.0);
    // primary
    float t1 = (ang - 40.4) / 2.1;
    col += spectrum(t1) * smoothstep(-0.25, 0.1, t1) * (1.0 - smoothstep(0.95, 1.25, t1)) * 0.42;
    // a faint supernumerary band just inside
    float ts = (ang - 39.4) / 0.9;
    col += vec3(0.25, 0.55, 0.35) * exp(-ts * ts * 4.0) * 0.08;
    // secondary, reversed and wider
    float t2 = 1.0 - (ang - 50.2) / 3.4;
    col += spectrum(t2) * smoothstep(-0.2, 0.1, t2) * (1.0 - smoothstep(0.95, 1.2, t2)) * 0.16;
    return col * strength;
}

// The thunderstorm coming in (StormFront): from far off, beyond where the clouds are drawn, a dark tower on the
// horizon - billowing up into a dome, the anvil spreading out flat on top, a grey curtain of rain hanging under
// it - and the sheet lightning flickering inside it. Returns its colour (rgb) and how much of the sky it covers (a).
float wallHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float wallNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(wallHash(i), wallHash(i + vec2(1.0, 0.0)), f.x), mix(wallHash(i + vec2(0.0, 1.0)), wallHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float wallFbm(vec2 p) {
    return wallNoise(p) * 0.5 + wallNoise(p * 2.03 + 3.1) * 0.27 + wallNoise(p * 4.1 + 7.7) * 0.15 + wallNoise(p * 8.3 + 1.3) * 0.08;
}

// a jagged line: straight runs between random kinks
float boltKink(float t, float seed) {
    float i = floor(t);
    return mix(wallHash(vec2(i, seed)), wallHash(vec2(i + 1.0, seed)), fract(t));
}

vec4 stormWall(vec3 rd, vec3 skyCol, float lum, float dayLight, vec3 sunDir) {
    if (StormCell.w <= 0.0) return vec4(0.0);
    vec2 rel = StormCell.xy;
    float dist = max(length(rel), 1.0);
    vec2 toward = rel / dist;
    vec2 across = vec2(-toward.y, toward.x);     // the +x side of the tower
    float h = max(length(rd.xz), 1e-3);
    vec2 v = rd.xz / h;
    float az = atan(toward.x * v.y - toward.y * v.x, dot(toward, v));
    float elev = rd.y / h;
    float baseE = StormInfo.w / dist;
    float topE = (StormInfo.w + 820.0) / dist;
    float halfW = atan(StormCell.z / dist);
    // in units of the tower: x across (-1..1 its width), y up from the base (0) to the top of the dome (1)
    float x = az / halfW;
    float y = (elev - baseE) / max(topE - baseE, 1e-4);

    // ---- the shape. Turrets: cauliflower lumps on lumps, the outline warped by the noise itself
    vec2 q = vec2(x * 2.2, y * 2.6);
    vec2 warp = vec2(wallFbm(q + 11.0), wallFbm(q + 47.0)) - 0.5;
    float n = wallFbm(q + warp * 1.4 + 11.0);
    float n2 = wallFbm(vec2(x * 6.0, y * 7.0) + warp * 2.0 + 37.0);
    float lumps = wallFbm(vec2(sign(x) * 4.0, y * 3.4) + warp.y + 23.0);
    // bubbles bulging out of the flanks, big and small
    float bub = 1.0 - abs(2.0 * wallNoise(vec2(sign(x) * 5.0, y * 9.0) + warp * 2.0) - 1.0);
    float bub2 = 1.0 - abs(2.0 * wallNoise(vec2(sign(x) * 9.0, y * 19.0) + warp * 3.0) - 1.0);
    float width = 0.52 + 0.06 * sin(y * 2.4) + 0.24 * (lumps - 0.5) + 0.1 * bub + 0.05 * bub2 + 0.05 * (n2 - 0.5);
    float xw = x / max(width, 0.05);
    // the overshooting top: the strongest updraft punching through the anvil
    float dome = 0.8 + 0.19 * (1.0 - xw * xw) + 0.06 * (n2 - 0.5);
    float tower = (1.0 - smoothstep(width - 0.07, width + 0.04, abs(x))) * (1.0 - smoothstep(dome - 0.05, dome + 0.015, y))
        * smoothstep(-0.02, 0.025, y + 0.025 * (n2 - 0.5));
    // the anvil: a wedge spread flat under the tropopause, sheared out further downwind (towards us, so it reaches
    // higher up the sky the closer the storm comes), its underside hanging in mammatus pouches
    float spread = 2.1 + 0.5 * (n - 0.5);
    float edge = clamp(abs(x) / spread, 0.0, 1.0);
    float anvilTop = 0.87 - 0.02 * edge + 0.015 * (n2 - 0.5);
    float pu = x * 7.0 + warp.x * 1.5;
    float lobe = sqrt(max(sin(3.14159 * fract(pu)), 0.0)) * (0.4 + 0.6 * wallHash(vec2(floor(pu), 3.0)));
    float pouches = lobe * smoothstep(0.22, 0.45, edge) * (1.0 - smoothstep(0.7, 0.92, edge));
    float anvilBottom = anvilTop - (0.045 + 0.2 * (1.0 - edge)) + 0.03 * (n - 0.5) - 0.05 * pouches;
    float anvil = (1.0 - smoothstep(0.78, 1.0, edge + 0.08 * (n2 - 0.5))) * smoothstep(anvilBottom - 0.025, anvilBottom + 0.015, y)
        * (1.0 - smoothstep(anvilTop - 0.015, anvilTop + 0.01, y)) * (0.72 + 0.28 * n2);
    // the shelf cloud: a low, dark, lumpy roll along the gust front ahead of the storm, once it is near
    float near = 1.0 - smoothstep(1200.0, 2800.0, dist);
    float roll = wallFbm(vec2(x * 3.0, y * 1.5) + 61.0);
    float rollTop = 0.035 + 0.05 * roll * (1.0 - smoothstep(0.6, 1.3, abs(x)));
    float shelf = near * (1.0 - smoothstep(1.0, 1.35 + 0.2 * roll, abs(x))) * smoothstep(-0.055, -0.03, y + 0.02 * (roll - 0.5))
        * (1.0 - smoothstep(rollTop - 0.02, rollTop, y));
    float cloud = clamp(max(max(tower, anvil), shelf), 0.0, 1.0);

    // rain shafts under the base: blown slanting forward, in uneven columns, thinning to the sides and down
    float below = clamp(-y / max(baseE / max(topE - baseE, 1e-4), 1e-3), 0.0, 1.0);     // 0 at the base, 1 at the ground
    float xs = x - 0.22 * below;
    float shafts = smoothstep(0.3, 0.7, wallFbm(vec2(xs * 3.2, 0.7) + 4.0)) * 0.7 + 0.3;
    float streak = 0.75 + 0.25 * wallNoise(vec2(xs * 40.0, below * 2.0));
    float curtain = (1.0 - smoothstep(0.3, 0.7, abs(xs) + 0.15 * (n - 0.5))) * (1.0 - smoothstep(-0.015, 0.015, y)) * shafts * streak
        * (0.9 - 0.4 * below) * smoothstep(-0.005, 0.0, elev);

    // ---- the light. A rough normal: the tower a lumpy column, its top and the anvil facing up
    float nx = clamp(xw, -1.0, 1.0) * 0.9 + (n2 - 0.5) * 0.5;
    vec3 side = vec3(-toward.x, 0.0, -toward.y) * sqrt(max(1.0 - nx * nx, 0.0)) + vec3(across.x, 0.0, across.y) * nx;
    float topness = max(smoothstep(dome - 0.3, dome, y) * tower, anvil * 0.8 * (1.0 - tower));
    vec3 normal = normalize(mix(side, vec3(0.0, 1.0, 0.0), topness * 0.7) + vec3(0.0, (n - 0.5) * 0.6, 0.0));
    // sunlight, its colour from the atmosphere (golden low down, orange-pink at sunset)
    vec3 sunLight = atSunlight(sunDir, 1.0);
    vec3 tint = sunLight / max(max(sunLight.r, max(sunLight.g, sunLight.b)), 1e-4);
    float sunUp = smoothstep(-0.03, 0.05, sunDir.y);
    float diffuse = clamp(dot(normal, sunDir) * 0.8 + 0.2, 0.0, 1.0) * sunUp;
    // deep inside the cloud little gets through: the lower tower and the base in their own shadow
    float depth = mix(0.35, 1.0, smoothstep(0.05, 0.75, y)) * (0.75 + 0.25 * n2);
    vec3 ambient = vec3(lum * 0.36) * vec3(0.82, 0.87, 1.0) * (0.55 + 0.45 * smoothstep(0.0, 0.6, y));
    vec3 lit = ambient + tint * diffuse * depth * (0.55 + 0.75 * lum);
    // with the sun behind it: the body dark, the edges glowing silver
    vec2 sunH = normalize(sunDir.xz + vec2(1e-4));
    float behind = max(dot(sunH, toward), 0.0) * sunUp * (1.0 - smoothstep(0.35, 0.8, sunDir.y));
    float rim = cloud * (1.0 - cloud) * 4.0 + (1.0 - smoothstep(0.0, 0.08, abs(abs(x) - width))) * tower * 0.5;
    lit += tint * rim * behind * 0.9;
    // the base, the shelf and the underside of the anvil: slate dark
    float under = max(1.0 - smoothstep(0.0, 0.18, y), shelf);
    under = max(under, anvil * (1.0 - tower) * (1.0 - smoothstep(anvilBottom, anvilBottom + 0.05, y)) * 0.7);
    lit *= 1.0 - 0.6 * under;
    vec3 col = mix(vec3(0.010, 0.012, 0.018), lit, dayLight);
    // the rain shafts: grey, lighter where the sun shines through them from behind
    vec3 rainCol = mix(vec3(0.016, 0.018, 0.024), vec3(lum * 0.5) * vec3(0.86, 0.9, 1.0) + tint * behind * 0.12, dayLight);
    col = mix(rainCol, col, cloud);
    float a = max(cloud * (0.86 + 0.14 * n), curtain);

    // ---- lightning: a glow inside the tower round where it flashed, a halo on the sky round it
    float fx = dot(StormInfo.yz, across) / dist / halfW;
    float fy = 0.2 + 0.4 * wallHash(StormInfo.yz);
    vec3 flashCol = vec3(0.8, 0.85, 1.0) * StormInfo.x;
    float glow = exp(-pow((x - fx) / 0.45, 2.0) - pow((y - fy) / 0.4, 2.0));
    col += flashCol * glow * (0.35 + 0.65 * n2) * 1.4 * max(cloud, curtain);
    float halo = StormInfo.x * exp(-pow((x - fx) / 1.4, 2.0) - pow((y - fy) / 1.1, 2.0)) * 0.2 * (1.0 - cloud);
    // and now and then a bolt to the ground: a thin, jagged channel from the base down through the rain
    if (StormBolt.x > 0.0 && y < 0.05) {
        float seed = StormBolt.y;
        float bx = StormBolt.z / dist / halfW;
        // how far down from the base (0) to the ground (1)
        float down = clamp(-y / max(baseE / max(topE - baseE, 1e-4), 1e-3), 0.0, 1.2);
        float w = 0.0011 / halfW;
        float ground = smoothstep(-0.002, 0.0, elev);
        // the channel: sharp kinks at every scale (straight runs between them), not a smooth wave
        float path = bx + 0.11 * (boltKink(down * 5.0, seed) - 0.5) + 0.05 * (boltKink(down * 13.0, seed + 3.0) - 0.5)
            + 0.02 * (boltKink(down * 37.0, seed + 7.0) - 0.5);
        float d = abs(x - path);
        float bolt = exp(-pow(d / w, 2.0)) + 0.22 * exp(-pow(d / (w * 10.0), 2.0));
        // branches: forking off the upper part, down and out, fading towards their tips
        for (int i = 0; i < 4; i++) {
            float fi = float(i);
            float from = 0.05 + 0.4 * wallHash(vec2(seed, fi));
            float len = 0.15 + 0.3 * wallHash(vec2(seed + 1.0, fi));
            float along = (down - from) / len;
            if (along < 0.0 || along > 1.0) continue;
            float slope = (wallHash(vec2(seed + 2.0, fi)) - 0.5) * 0.9;
            float start = bx + 0.11 * (boltKink(from * 5.0, seed) - 0.5) + 0.05 * (boltKink(from * 13.0, seed + 3.0) - 0.5);
            float bpath = start + slope * (down - from) * 2.0 + 0.04 * (boltKink(down * 21.0, seed + 11.0 + fi) - 0.5) * along;
            float bd = abs(x - bpath);
            float fadeTip = (1.0 - along) * 0.55;
            bolt += fadeTip * (exp(-pow(bd / (w * 0.7), 2.0)) + 0.2 * exp(-pow(bd / (w * 7.0), 2.0)));
        }
        bolt *= StormBolt.x * ground * step(down, 1.0);
        col += vec3(0.85, 0.9, 1.0) * bolt * 2.5;
        a = max(a, clamp(bolt, 0.0, 1.0));
        halo += StormBolt.x * 0.25 * exp(-pow((x - bx) / 0.8, 2.0) - pow((y + 0.1) / 0.25, 2.0)) * (1.0 - cloud);
    }
    col += flashCol * halo;
    a = max(a, halo);

    // far off, the air between takes it: paler, bluer, lower in contrast (by day)
    float haze = (0.12 + 0.55 * smoothstep(900.0, 6500.0, dist)) * dayLight;
    col = mix(col, skyCol, haze);
    a *= StormCell.w * (1.0 - 0.35 * smoothstep(3000.0, 7000.0, dist));
    return vec4(col, clamp(a, 0.0, 1.0));
}

void main() {
    vec3 rd = normalize(relPos);
    float rain = skyInfo.r;
    float thunder = skyInfo.g;
    int packed = int(skyInfo.b * 255.0 + 0.5);
    int level = packed >> 6;
    float bow = float(packed & 63) / 63.0;
    float haze = 1.0 + 5.0 * rain;
    int viewSteps = level == 0 ? 8 : level == 1 ? 12 : level == 2 ? 16 : 20;
    int lightSteps = level <= 1 ? 4 : 6;
    // just above the horizon the view ray runs through the most air; keep it a hair above to spare the ground test
    vec3 r = normalize(vec3(rd.x, max(rd.y, 0.004), rd.z));
    vec3 L = atmosphere(r, sunDir, haze, viewSteps, lightSteps);
    // the sun's disc, darker towards its rim, coloured by the air it shines through
    float c = dot(rd, sunDir);
    float disc = smoothstep(0.99975, 0.99988, c);
    if (disc > 0.0) {
        vec3 sunlight = atSunlight(sunDir, haze);
        L += sunlight * disc * 60.0 * (0.6 + 0.4 * smoothstep(0.99975, 1.0, c)) * (1.0 - 0.9 * rain);
    }
    vec3 col = atToneMap(L);
    // rain: an overcast grey of the same brightness; a thunderstorm: darker still
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(lum * 0.85) * vec3(0.95, 0.97, 1.0), clamp(rain * 0.85, 0.0, 0.85));
    col *= 1.0 - 0.45 * thunder;
    if (bow > 0.0) {
        float ang = degrees(acos(clamp(dot(rd, -sunDir), -1.0, 1.0)));
        // brighter inside the primary bow, darker between the two (Alexander's band)
        col *= 1.0 + bow * (0.07 * (1.0 - smoothstep(30.0, 40.5, ang)) - 0.06 * smoothstep(41.5, 43.0, ang) * (1.0 - smoothstep(49.5, 51.0, ang)));
        // fades towards the ground, where the rain curtain ends
        col += rainbow(rd, sunDir, bow) * smoothstep(-0.03, 0.08, rd.y);
    }
    // into the fog towards and below the horizon
    float below = 1.0 - smoothstep(-0.02, 0.06, rd.y);
    col = mix(col, FogColor.rgb, below);
    float alpha = skyInfo.a;
    vec4 wall = stormWall(rd, col, lum, skyInfo.a, sunDir);
    if (wall.a > 0.0) {
        col = mix(col, wall.rgb, clamp(wall.a, 0.0, 1.0));
        // at night the sky is Minecraft's: the storm hides its stars, its flashes light up in it
        alpha = max(alpha, wall.a * 0.92);
    }
    fragColor = vec4(col, alpha);
}
