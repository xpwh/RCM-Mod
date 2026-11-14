"""1.7 textures: paint schemes matched to the new per-type missile models, and an inventory icon for
every missile type that shows its real silhouette (penetrator spike, bulbous warhead, HARM wings...).

Reuses the helpers of gen_textures.py / gen_textures_v2.py / gen_textures_v4.py.
"""
import math
import random

import numpy as np
from PIL import Image

from gen_textures import access_panel, missile_texture, out, outline, small_print, stencil_decal, stripe

random.seed(1717)
np.random.seed(1717)


# ---------------------------------------------------------------------------------------------- skins

def bunker_buster():
    missile_texture("bunker_buster", 9.0, [
        (0.0, 1.0, (52, 52, 56)),
        (2.0, 2.12, (220, 120, 30)),
        (5.6, 6.2, (58, 60, 62)),           # adapter cone
        (5.86, 5.98, (220, 120, 30)),        # orange: live penetrator warhead
        (6.2, 9.0, (44, 44, 46)),            # hardened steel penetrator
        (6.2, 6.28, (120, 122, 124)),        # machined shoulder collar
    ], (66, 70, 72), (60, 64, 66), [
        stencil_decal("BX-2 PENETRATOR", 5.3, (40, 168), (230, 150, 50), 9),
        small_print(["HARDENED", "DELAY FUZE"], 8.0, 100, (200, 200, 196)),
        access_panel(3.0, 3.6, 60, 84, (40, 42, 44)),
    ], soot=0.5)


def cluster():
    missile_texture("cluster_missile", 9.0, [
        (0.0, 1.0, (88, 92, 80)),
        (6.48, 6.6, (240, 200, 40)),         # yellow: high explosive submunitions
        (8.8, 9.0, (40, 40, 40)),
    ], (138, 148, 116), (128, 138, 108), [
        stencil_decal("MGM-140", 7.9, (52, 180), (40, 42, 36), 11),
        access_panel(3.2, 5.6, 16, 48, (98, 106, 82)),
        access_panel(3.2, 5.6, 80, 112, (98, 106, 82)),
        access_panel(3.2, 5.6, 144, 176, (98, 106, 82)),
        access_panel(3.2, 5.6, 208, 240, (98, 106, 82)),
        small_print(["950 BOMBLETS", "DO NOT DROP"], 6.2, 100, (40, 42, 36)),
    ])


def thermobaric():
    def flames(pil, d, row):
        cy = (row(7.1) + row(6.4)) // 2
        for cx in (64, 192):
            d.polygon([(cx, cy - 12), (cx + 9, cy + 10), (cx - 9, cy + 10)], fill=(230, 90, 20, 255))
            d.polygon([(cx, cy - 4), (cx + 5, cy + 10), (cx - 5, cy + 10)], fill=(250, 210, 60, 255))

    missile_texture("thermobaric_missile", 9.0, [
        (0.0, 1.0, (60, 56, 50)),
        (5.6, 5.9, (70, 64, 56)),
        (5.9, 8.6, (120, 92, 52)),           # fat fuel-air warhead
        (6.2, 6.3, (190, 30, 20)),
        (7.2, 7.3, (235, 190, 40)),
        (8.55, 9.0, (36, 36, 36)),           # stand-off fuze probe
    ], (94, 80, 54), (86, 74, 50), [
        flames, stencil_decal("TBX-2 FAE", 5.4, (20, 148), (235, 225, 200), 10),
    ], soot=0.45)


def incendiary():
    def flames(pil, d, row):
        cy = (row(7.6) + row(6.8)) // 2
        for cx in (40, 104, 168, 232):
            d.polygon([(cx, cy - 10), (cx + 7, cy + 8), (cx - 7, cy + 8)], fill=(240, 100, 20, 255))
            d.polygon([(cx, cy - 3), (cx + 4, cy + 8), (cx - 4, cy + 8)], fill=(255, 220, 70, 255))

    missile_texture("incendiary_missile", 9.0, [
        (0.0, 1.0, (60, 50, 44)),
        (4.35, 4.45, (40, 32, 28)), (5.0, 5.1, (40, 32, 28)), (5.65, 5.75, (40, 32, 28)),  # vent rings
        (6.0, 6.12, (220, 40, 20)),
        (6.12, 6.22, (240, 200, 40)),
        (8.7, 9.0, (40, 30, 26)),
    ], (128, 52, 36), (112, 46, 32), [
        flames,
        stencil_decal("INCENDIARY", 4.2, (40, 168), (245, 225, 190), 9),
        access_panel(2.8, 3.6, 60, 84, (90, 36, 26)),
    ], soot=0.5)


def emp():
    def bolts(pil, d, row):
        cy = (row(4.6) + row(3.4)) // 2
        for cx in (64, 192):
            d.polygon([(cx + 4, cy - 16), (cx - 6, cy + 2), (cx + 1, cy + 2), (cx - 4, cy + 16), (cx + 8, cy - 4), (cx + 1, cy - 4)],
                      fill=(90, 200, 255, 255))

    missile_texture("emp_missile", 9.0, [
        (0.0, 1.0, (40, 42, 46)),
        (5.78, 5.9, (80, 180, 240)),
        (5.9, 7.25, (176, 112, 56)),         # copper flux-compression coils
        (7.25, 7.4, (40, 42, 46)),
        (7.4, 9.0, (206, 208, 204)),         # dielectric radome
    ], (58, 62, 70), (52, 56, 64), [
        bolts,
        stencil_decal("HEMP-1", 5.6, (100, 228), (170, 220, 250), 11),
        small_print(["SAFE/ARM", "ALT FUZE"], 3.0, 100, (180, 200, 220)),
        stripe(2.4, 2.5, (80, 180, 240)),
    ], soot=0.35)


def anti_radar():
    missile_texture("anti_radar_missile", 6.0, [
        (2.40, 2.48, (60, 62, 66)),
        (4.78, 4.9, (220, 140, 30)),
        (4.9, 6.0, (200, 196, 160)),         # broadband passive seeker radome
    ], (150, 156, 160), (136, 142, 146), [
        stencil_decal("AGM-88X ARM", 4.5, (40, 168), (40, 42, 46), 9),
        small_print(["PASSIVE RF", "SEEKER"], 2.3, 64, (50, 50, 50)),
    ], soot=0.15, steel=(120, 122, 126))


# ---------------------------------------------------------------------------------------------- icons

S = 16


def icon(name, half_width, color_at, fins=(), extras=None):
    """Draws a missile diagonally from bottom-left (tail, u=0) to top-right (nose, u=1).
    half_width(u) -> half width in pixels or None; color_at(u) -> rgb;
    fins: (u0, u1, reach, rgb) triangles sticking out reach pixels at u0, tapering to 0 at u1."""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    p = img.load()
    tx, ty, nx, ny = 2.0, 14.0, 14.4, 1.6
    ax, ay = nx - tx, ny - ty
    length = math.hypot(ax, ay)
    ax, ay = ax / length, ay / length
    px_, py_ = -ay, ax

    def dim(c, f):
        return tuple(max(0, min(255, int(v * f))) for v in c)

    for x in range(S):
        for y in range(S):
            cx, cy = x + 0.5 - tx, y + 0.5 - ty
            u = (cx * ax + cy * ay) / length
            v = cx * px_ + cy * py_
            col = None
            w = half_width(u) if -0.02 <= u <= 1.04 else None
            if w is not None and abs(v) < w:
                col = color_at(u)
            if col is None:
                for (u0, u1, reach, fc) in fins:
                    if u0 <= u < u1:
                        base = half_width(u) or 0.8
                        if abs(v) < base + reach * (u1 - u) / (u1 - u0) + 0.2:
                            col = fc
                            break
            if col is None and -0.05 <= u < -0.0 and abs(v) < 0.9:
                col = (255, 170, 40)
            if col is None:
                continue
            if v > 0.45:
                col = dim(col, 0.72)
            elif v < -0.45:
                col = dim(col, 1.18)
            p[x, y] = col + (255,)
    if extras:
        extras(p)
    outline(img)
    img.save(out(f"item/{name}.png"))


def bands(base, spans):
    def color_at(u):
        for u0, u1, c in spans:
            if u0 <= u < u1:
                return c
        return base
    return color_at


def icons():
    # bunker buster: booster, adapter, long thin penetrator spike with canards
    icon("bunker_buster",
         lambda u: 1.35 if u < 0.6 else 1.35 - (u - 0.6) * 8 if u < 0.66 else 0.85 if u < 0.84 else max(0.0, 0.85 * (1.04 - u) / 0.2),
         bands((70, 74, 78), [(0.6, 0.66, (220, 120, 30)), (0.66, 1.1, (44, 44, 48))]),
         fins=[(0.02, 0.24, 1.9, (60, 64, 68)), (0.7, 0.78, 0.9, (90, 92, 96))])
    # cluster (ATACMS): blunt round nose, panel seams, tail fins
    icon("cluster_missile",
         lambda u: 1.4 if u < 0.74 else 1.4 * math.sqrt(max(0.0, 1 - ((u - 0.74) / 0.27) ** 2)) + 0.1,
         bands((180, 160, 114), [(0.34, 0.38, (120, 106, 76)), (0.58, 0.62, (120, 106, 76)), (0.7, 0.74, (240, 200, 40)),
                                 (0.95, 1.1, (48, 50, 52))]),
         fins=[(0.02, 0.17, 1.6, (150, 134, 96))])
    # thermobaric: fat fuel-air warhead bulging past the motor, round nose and fuze probe
    icon("thermobaric_missile",
         lambda u: 1.25 if u < 0.6 else 1.25 + (u - 0.6) * 15 if u < 0.64 else 1.85 if u < 0.82
         else (1.85 * math.sqrt(max(0.0, 1 - ((u - 0.82) / 0.15) ** 2)) if u < 0.96 else 0.35),
         bands((98, 82, 54), [(0.6, 0.97, (130, 98, 56)), (0.66, 0.7, (190, 30, 20)), (0.97, 1.1, (40, 38, 36))]),
         fins=[(0.04, 0.28, 1.6, (84, 72, 48))])
    # incendiary: conical canister warhead with vent rings
    icon("incendiary_missile",
         lambda u: 1.35 if u < 0.66 else max(0.0, 1.35 * (1.02 - u) / 0.36),
         bands((140, 56, 38), [(0.44, 0.48, (60, 32, 26)), (0.52, 0.56, (60, 32, 26)), (0.6, 0.64, (60, 32, 26)),
                               (0.66, 0.7, (240, 200, 40)), (0.94, 1.1, (44, 32, 28))]),
         fins=[(0.04, 0.28, 1.7, (112, 46, 32))])
    # EMP: copper coils and a white radome
    icon("emp_missile",
         lambda u: 1.35 if u < 0.82 else 1.35 * max(0.0, 1 - ((u - 0.82) / 0.2) ** 2) ** 0.6 + 0.1,
         bands((60, 64, 72), [(0.64, 0.82, (184, 118, 60)), (0.82, 1.1, (214, 216, 212)), (0.62, 0.64, (80, 180, 240))]),
         fins=[(0.04, 0.28, 1.6, (52, 56, 64))],
         extras=lambda p: [p.__setitem__(xy, (120, 76, 40, 255)) for xy in ((10, 6), (11, 5)) if p[xy][3] > 0])
    # antimatter: slim warhead inside three glowing containment rings
    icon("antimatter_missile",
         lambda u: (2.2 if any(abs(u - r) < 0.025 for r in (0.67, 0.74, 0.81)) else
                    1.35 if u < 0.62 else 0.95 if u < 0.84 else max(0.0, 0.95 * (1.03 - u) / 0.19)),
         lambda u: ((40, 230, 255) if any(abs(u - r) < 0.025 for r in (0.67, 0.74, 0.81)) else
                    (40, 38, 52) if u < 0.62 else (236, 236, 244)))
    # anti-radar (HARM): slim, double-delta mid-body wings, tail fins, light radome
    icon("anti_radar_missile",
         lambda u: 0.8 if u < 0.82 else max(0.0, 0.8 * (1.04 - u) / 0.22),
         bands((160, 164, 168), [(0.78, 0.82, (220, 140, 30)), (0.82, 1.1, (210, 204, 166))]),
         fins=[(0.42, 0.7, 2.6, (130, 136, 140)), (0.02, 0.14, 1.5, (130, 136, 140))])


if __name__ == "__main__":
    bunker_buster()
    cluster()
    thermobaric()
    incendiary()
    emp()
    anti_radar()
    icons()
    print("done")
