"""The orbital strike system's satellite earth station (OrbitalUplinkRenderer), built in Blender.

Parts (block space, origin at the bottom centre of the uplink block):
  site   the fixed site, turned to the block's facing: fenced concrete pad, kingpost, shelter, generator, masts
  head   the azimuth head turning on the kingpost: bearing, cable wrap, alidade with railing, yoke, drive
  dish   the reflector with its back structure and the Cassegrain optics, about the elevation axis (0, 3.05, 0),
         looking along +z at elevation 0
  jack   the elevation jackscrew: a unit length along +y (scaled to reach): the barrel ...
  rod    ... and the screw coming out of it

    python uplink.py <out_dir> [--shots] [--export]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from structkit import _coords, _math, _mix, _finish, _noise, node_mat, weathered  # noqa: E402

TOP = 1.02          # the pad's surface
PIVOT_Y = 3.05      # elevation axis
DISH_R = 2.05
FOCUS = 0.85
VERTEX_Z = 0.3
SX0, SX1 = 1.25, 2.9  # the shelter, door on +x
SZ = 1.45

# ---------------------------------------------------------------- materials
M_CONC = concrete("concrete")
M_CONC_D = concrete("concrete_dark", (0.27, 0.265, 0.25), grime=0.75)
M_WHITE = paint("white_paint", (0.78, 0.79, 0.78), rough=0.45, grime=0.55, wear=0.25, streaks=0.5)
M_SHELTER = paint("shelter", (0.72, 0.73, 0.7), rough=0.5, grime=0.65, wear=0.2, streaks=0.7)
M_STEEL = paint("steel", (0.2, 0.21, 0.22), rough=0.5, metal=0.6, grime=0.45, wear=0.4, rust=0.3, wear_col=(0.42, 0.42, 0.42))
M_GALV = galvanized("galvanized")
M_GUN = paint("gunmetal", (0.09, 0.095, 0.1), rough=0.45, metal=0.5, grime=0.4, wear=0.4, rust=0.2, wear_col=(0.35, 0.35, 0.35))
M_OLIVE = paint("olive", (0.13, 0.14, 0.07), rough=0.6, grime=0.6, wear=0.35, streaks=0.5, rust=0.25)
M_RUBBER = rubber()
M_HAZ = hazard()
M_GRATE = grating()
M_RED = paint("red_paint", (0.5, 0.03, 0.02), rough=0.5, grime=0.4, wear=0.3)
M_LENS = glass("lens", (0.25, 0.22, 0.15))
M_RED_LENS = glass("red_lens", (0.35, 0.02, 0.01))
M_GREEN_LENS = glass("green_lens", (0.02, 0.25, 0.04))
M_DARK = plain("void", (0.01, 0.01, 0.01), 0.9)
M_SIGN = paint("sign", (0.65, 0.5, 0.02), rough=0.5, grime=0.3, wear=0.2)


def dish_paint():
    """The reflector's white paint, the seams between the panels drawn in: rings and radial joints."""
    m, nt, bsdf = node_mat("dish_paint")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), sep.inputs[0])
    x = sep.outputs["X"]
    z = _math(nt, "SUBTRACT", sep.outputs["Z"], PIVOT_Y)
    r = _math(nt, "POWER", _math(nt, "ADD", _math(nt, "MULTIPLY", x, x), _math(nt, "MULTIPLY", z, z)), 0.5)
    ang = _math(nt, "ARCTAN2", z, x)
    radial = _math(nt, "FRACT", _math(nt, "MULTIPLY", ang, 24 / (2 * math.pi)))
    radial = _math(nt, "MULTIPLY", _math(nt, "LESS_THAN", radial, 0.03), _math(nt, "GREATER_THAN", r, 0.5))
    ring = _math(nt, "LESS_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", _math(nt, "ADD", r, 0.0), 0.52)), 0.02)
    seam = _math(nt, "MAXIMUM", radial, ring)
    base = _mix(nt, _noise(nt, 0.7, 4, 0.5, _coords(nt)), (0.8, 0.81, 0.8), (0.72, 0.73, 0.71))
    col = _mix(nt, _math(nt, "MULTIPLY", seam, 0.55), base, (0.25, 0.25, 0.24))
    col = weathered(nt, col, 0.45, 0.15, 0.6, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, 0.55)


M_DISH = dish_paint()

# ================================================================= site
S = "site"
# ---- the pad: a slab with chamfered edges, expansion joints, the antenna's octagonal plinth
box("pad", -2.3, 0.0, -2.3, 3.3, TOP, 2.3, M_CONC, S, bevel=0.04)
for x in (-0.6, 1.02):
    box("joint", x - 0.012, TOP - 0.004, -2.3, x + 0.012, TOP + 0.002, 2.3, M_DARK, S, bevel=0)
box("joint", -2.3, TOP - 0.004, -0.012, 3.3, TOP + 0.002, 0.012, M_DARK, S, bevel=0)
lathe("plinth", (0, TOP, 0), (0, 1, 0), [(0.0, 0.98), (0.3, 0.92), (0.32, 0.9)], M_CONC_D, S, k=8, smooth=10)
lathe("plinth_band", (0, TOP + 0.3, 0), (0, 1, 0), [(0.0, 0.93), (0.025, 0.93)], M_HAZ, S, k=8, smooth=10)
PLINTH = TOP + 0.32
# ---- the kingpost: a tapered steel pedestal on a bolted base flange, an access hatch
lathe("flange", (0, PLINTH, 0), (0, 1, 0), [(0.0, 0.62), (0.06, 0.62), (0.06, 0.48)], M_WHITE, S, k=32)
bolts("anchor", (0, PLINTH + 0.06, 0), (0, 1, 0), 0.56, 16, 0.028, 0.035, M_GALV, S)
lathe("kingpost", (0, PLINTH + 0.06, 0), (0, 1, 0), [(0.0, 0.46), (0.5, 0.42), (2.11 - PLINTH - 0.06, 0.38)], M_WHITE, S, k=32)
box("hatch", 0.395, PLINTH + 0.25, -0.16, 0.43, PLINTH + 0.62, 0.16, M_WHITE, S, bevel=0.01)
box("hatch_lock", 0.425, PLINTH + 0.42, 0.08, 0.445, PLINTH + 0.47, 0.12, M_DARK, S, bevel=0.003)
# ---- the equipment shelter: a frame of posts, corrugated walls, the roof with its overhang
box("shelter_sill", SX0 - 0.03, TOP, -SZ - 0.03, SX1 + 0.03, TOP + 0.08, SZ + 0.03, M_STEEL, S, bevel=0.01)
box("shelter_core", SX0, TOP + 0.08, -SZ, SX1, 2.3, SZ, M_SHELTER, S, bevel=0.01)
# vertical corrugation on the long walls (z sides) and the back wall (-x)
z = -SZ + 0.08
while z < SZ - 0.06:
    for x0, x1 in ((SX0 - 0.025, SX0), (SX1, SX1 + 0.025)):
        if x0 > SX1 - 0.1 and -0.5 < z < 0.5:
            pass  # the door
        else:
            box("rib", x0, TOP + 0.1, z, x1, 2.28, z + 0.045, M_SHELTER, S, bevel=0.004)
    z += 0.16
x = SX0 + 0.08
while x < SX1 - 0.06:
    for z0, z1 in ((-SZ - 0.025, -SZ), (SZ, SZ + 0.025)):
        box("rib", x, TOP + 0.1, z0, x + 0.045, 2.28, z1, M_SHELTER, S, bevel=0.004)
    x += 0.16
for cx in (SX0, SX1):
    for cz in (-SZ, SZ):
        box("corner_post", cx - 0.05, TOP + 0.08, cz - 0.05, cx + 0.05, 2.32, cz + 0.05, M_WHITE, S, bevel=0.01)
box("roof", SX0 - 0.08, 2.3, -SZ - 0.08, SX1 + 0.08, 2.38, SZ + 0.08, M_WHITE, S, bevel=0.012)
box("roof_cap", SX0 - 0.02, 2.38, -SZ - 0.02, SX1 + 0.02, 2.4, SZ + 0.02, M_GUN, S, bevel=0.005)
# the door on +x: frame, leaf, hinges, handle, a placard, the step in front
box("door_frame", SX1 + 0.0, TOP + 0.08, -0.47, SX1 + 0.04, 2.12, 0.47, M_WHITE, S, bevel=0.008)
box("door", SX1 + 0.02, TOP + 0.1, -0.42, SX1 + 0.05, 2.08, 0.42, M_SHELTER, S, bevel=0.008)
for y in (TOP + 0.3, TOP + 0.85, 1.85):
    cyl("hinge", (SX1 + 0.05, y, -0.43), (SX1 + 0.05, y + 0.12, -0.43), 0.018, M_GUN, S, k=8)
box("handle_plate", SX1 + 0.05, TOP + 0.95, 0.27, SX1 + 0.06, TOP + 1.15, 0.33, M_GUN, S, bevel=0.003)
beam("handle", (SX1 + 0.085, TOP + 1.04, 0.2), (SX1 + 0.085, TOP + 1.04, 0.33), 0.02, 0.02, M_GUN, S)
box("placard", SX1 + 0.05, TOP + 1.45, -0.2, SX1 + 0.055, TOP + 1.7, 0.12, M_SIGN, S, bevel=0.002)
box("step", SX1 + 0.04, TOP, -0.55, SX1 + 0.4, TOP + 0.14, 0.55, M_GRATE, S, bevel=0.01)
# the ready lamp beside the door (lit by the game: 2.92-2.94, 2.05-2.15, -0.62 .. -0.52)
box("ready_housing", SX1 + 0.0, 2.02, -0.65, SX1 + 0.035, 2.18, -0.49, M_GUN, S, bevel=0.005)
box("ready_lens", SX1 + 0.02, 2.05, -0.62, SX1 + 0.04, 2.15, -0.52, M_GREEN_LENS, S, bevel=0.004)
# air conditioners on the back wall: a cabinet, a round fan guard, fins
for z in (-1.0, 0.55):
    box("ac", SX0 - 0.48, TOP + 0.5, z - 0.32, SX0 - 0.025, TOP + 1.2, z + 0.32, M_SHELTER, S, bevel=0.015)
    lathe("ac_fan", (SX0 - 0.485, TOP + 0.85, z), (-1, 0, 0), [(0.0, 0.25), (0.01, 0.25), (0.01, 0.0)], M_DARK, S, k=20)
    for k in range(5):
        r = 0.05 + k * 0.045
        lathe("ac_guard", (SX0 - 0.5, TOP + 0.85, z), (-1, 0, 0), [(0.0, r), (0.008, r), (0.008, r - 0.008), (0.0, r - 0.008)], M_GALV, S, k=20, loop=True)
    beam("ac_bar", (SX0 - 0.5, TOP + 0.6, z), (SX0 - 0.5, TOP + 1.1, z), 0.012, 0.012, M_GALV, S)
    beam("ac_bar", (SX0 - 0.5, TOP + 0.85, z - 0.25), (SX0 - 0.5, TOP + 0.85, z + 0.25), 0.012, 0.012, M_GALV, S)
    for k in range(4):
        box("ac_leg", SX0 - 0.46 + (k % 2) * 0.38, TOP, z - 0.3 + (k // 2) * 0.56, SX0 - 0.42 + (k % 2) * 0.38, TOP + 0.5, z - 0.26 + (k // 2) * 0.56, M_GALV, S, bevel=0.004)
    pipe("ac_line", [(SX0 - 0.25, TOP + 1.2, z + 0.2), (SX0 - 0.25, TOP + 1.38, z + 0.2), (SX0 - 0.02, TOP + 1.38, z + 0.2)], 0.018, M_RUBBER, S, bend=0.06)
# the cable entry panel and conduits to the trench
box("entry_panel", SX0 - 0.06, TOP + 0.3, -0.25, SX0, TOP + 0.75, 0.2, M_GUN, S, bevel=0.008)
for k, dz in enumerate((-0.15, -0.05, 0.05)):
    pipe("conduit", [(SX0 - 0.03, TOP + 0.4 + k * 0.08, dz), (SX0 - 0.35, TOP + 0.4 + k * 0.08, dz), (SX0 - 0.45, TOP + 0.03, dz), (0.6, TOP + 0.03, dz)],
         0.025, M_RUBBER, S, bend=0.15)
box("trench_cover", 0.55, TOP, -0.2, SX0 - 0.1, TOP + 0.03, 0.2, M_GRATE, S, bevel=0.005)
# ---- on the roof: a small telemetry dish on its mount, GPS pucks, a cable tray, the beacon
dish_c = (2.3, 2.62, 0.2)
lathe("tm_mount", (2.3, 2.4, 0.2), (0, 1, 0), [(0.0, 0.07), (0.18, 0.05), (0.2, 0.0)], M_GALV, S, k=12)
tm_axis = (0.0, 0.6, 0.8)
lathe("tm_dish", dish_c, tm_axis, [(0.0, 0.02), (0.02, 0.12), (0.06, 0.22), (0.11, 0.32), (0.125, 0.33), (0.1, 0.32), (0.02, 0.0)], M_WHITE, S, k=24)
beam("tm_feed", (2.3, 2.62, 0.2), (2.3, 2.82, 0.47), 0.018, 0.018, M_GALV, S)
lathe("tm_lnb", (2.3, 2.82, 0.47), (0.0, -0.6, -0.8), [(0.0, 0.035), (0.08, 0.03), (0.09, 0.0)], M_GUN, S, k=10)
for z in (-0.45, -0.2):
    lathe("gps", (1.6, 2.4, z), (0, 1, 0), [(0.0, 0.07), (0.04, 0.065), (0.07, 0.035), (0.08, 0.0)], M_WHITE, S, k=14)
box("beacon_base", 2.0, 2.4, 0.9, 2.2, 2.44, 1.1, M_GUN, S, bevel=0.005)
lathe("beacon_dome", (2.1, 2.42, 1.0), (0, 1, 0), [(0.0, 0.09), (0.12, 0.09), (0.19, 0.06), (0.21, 0.0)], M_RED_LENS, S, k=16)
# ---- the aviation warning mast at the shelter's back corner: a tapering lattice, red and white bands
M_MAST = stripes("mast_paint", (0.55, 0.04, 0.02), (0.8, 0.8, 0.78), 0.6)
lattice("mast", 2.75, -1.25, 2.4, 5.6, 0.36, 0.12, M_MAST, S, panels=5, leg=0.045, brace=0.022)
box("mast_cap", 2.68, 5.58, -1.32, 2.82, 5.62, -1.18, M_GUN, S, bevel=0.005)
lathe("aviation_lens", (2.75, 5.6, -1.25), (0, 1, 0), [(0.0, 0.08), (0.12, 0.08), (0.18, 0.04), (0.19, 0.0)], M_RED_LENS, S, k=14)
cyl("lightning_rod", (2.75, 5.78, -1.25), (2.75, 6.25, -1.25), 0.008, M_GALV, S, k=6)
pipe("mast_cable", [(2.68, 5.5, -1.25), (2.62, 2.45, -1.25), (2.55, 2.41, -1.2)], 0.01, M_RUBBER, S)
# ---- the diesel generator in its enclosure, its exhaust, and the fuel tank on saddles
gx0, gx1, gz0, gz1 = -2.05, -0.95, 1.15, 2.05
box("genset", gx0, TOP + 0.08, gz0, gx1, TOP + 0.85, gz1, M_OLIVE, S, bevel=0.02)
box("genset_skid", gx0 - 0.03, TOP, gz0 - 0.03, gx1 + 0.03, TOP + 0.08, gz1 + 0.03, M_STEEL, S, bevel=0.008)
for k in range(9):
    y = TOP + 0.2 + k * 0.06
    box("louvre", gx0 - 0.015, y, gz0 + 0.2, gx0 + 0.01, y + 0.025, gz1 - 0.2, M_OLIVE, S, bevel=0.004)
    box("louvre", gx1 - 0.01, y, gz0 + 0.2, gx1 + 0.015, y + 0.025, gz1 - 0.2, M_OLIVE, S, bevel=0.004)
box("genset_door", gx0 + 0.15, TOP + 0.15, gz0 - 0.01, gx1 - 0.15, TOP + 0.78, gz0, M_OLIVE, S, bevel=0.006)
box("genset_panel", -1.65, TOP + 0.45, gz0 - 0.02, -1.35, TOP + 0.65, gz0 - 0.005, M_DARK, S, bevel=0.003)
cyl("exhaust", (-1.15, TOP + 0.85, 1.3), (-1.15, TOP + 1.45, 1.3), 0.045, M_STEEL, S, k=12)
cyl("exhaust_cap", (-1.15, TOP + 1.47, 1.26), (-1.15, TOP + 1.5, 1.38), 0.055, M_STEEL, S, k=12)
lathe("fuel_tank", (-2.15, TOP + 0.36, -1.6), (1, 0, 0), [(0.0, 0.0), (0.0, 0.25), (0.05, 0.31), (1.0, 0.31), (1.05, 0.25), (1.05, 0.0)], M_WHITE, S, k=20)
for x in (-1.95, -1.3):
    box("saddle", x - 0.04, TOP, -1.85, x + 0.04, TOP + 0.12, -1.35, M_STEEL, S, bevel=0.006)
    lathe("strap", (x, TOP + 0.36, -1.6), (1, 0, 0), [(0.0, 0.312), (0.0, 0.322), (0.03, 0.322), (0.03, 0.312)], M_GUN, S, k=20, loop=True)
cyl("fill_neck", (-1.6, TOP + 0.66, -1.6), (-1.6, TOP + 0.75, -1.6), 0.05, M_GUN, S, k=10)
pipe("fuel_line", [(-1.1, TOP + 0.2, -1.6), (-0.9, TOP + 0.05, -1.6), (-0.9, TOP + 0.05, 1.1), (-1.0, TOP + 0.25, 1.2)], 0.015, M_GUN, S, bend=0.08)
# ---- floodlight poles at the back corners, their heads aimed at the antenna
for z in (-2.15, 2.15):
    cyl("flood_pole", (-2.15, TOP, z), (-2.15, TOP + 2.8, z), 0.045, M_GALV, S, k=10)
    box("flood_base", -2.25, TOP, z - 0.1, -2.05, TOP + 0.03, z + 0.1, M_GALV, S, bevel=0.005)
    box("flood_bracket", -2.15, TOP + 2.7, z - 0.03, -1.98, TOP + 2.74, z + 0.03, M_GALV, S, bevel=0.004)
    box("flood_head", -2.05, TOP + 2.62, z - 0.16, -1.9, TOP + 2.86, z + 0.16, M_GUN, S, bevel=0.012)
    box("flood_glass", -1.905, TOP + 2.65, z - 0.13, -1.89, TOP + 2.83, z + 0.13, M_LENS, S, bevel=0.004)
    pipe("flood_cable", [(-2.12, TOP + 2.65, z), (-2.12, TOP + 0.1, z), (-2.0, TOP + 0.02, z)], 0.01, M_RUBBER, S)
# ---- signs
for x, z in ((-1.0, -2.25), (2.0, 2.25)):
    box("sign", x - 0.2, TOP + 0.75, z - 0.01, x + 0.2, TOP + 1.05, z + 0.01, M_SIGN, S, bevel=0.002)
# ---- the fence: galvanised posts, top rail, tension wire, a diamond wire mesh, barbed wire on outriggers
FX0, FX1, FZ, FH = -2.25, 3.25, 2.25, 1.45
runs = [((FX0, -FZ), (FX1, -FZ)), ((FX0, FZ), (FX1, FZ)), ((FX0, -FZ), (FX0, FZ)), ((FX1, -FZ), (FX1, -0.8)), ((FX1, 0.8), (FX1, FZ))]
for (ax, az), (bx, bz) in runs:
    length = math.hypot(bx - ax, bz - az)
    n = max(1, round(length / 1.0))
    for k in range(n + 1):
        t = k / n
        px, pz = ax + (bx - ax) * t, az + (bz - az) * t
        cyl("fence_post", (px, TOP, pz), (px, TOP + FH + 0.02, pz), 0.028, M_GALV, S, k=8)
        out = (0.0, 0.14) if az == bz and az > 0 else (0.0, -0.14) if az == bz else (-0.14, 0.0) if ax < 0 else (0.14, 0.0)
        cyl("outrigger", (px, TOP + FH, pz), (px + out[0], TOP + FH + 0.2, pz + out[1]), 0.012, M_GALV, S, k=6)
    cyl("top_rail", (ax, TOP + FH, az), (bx, TOP + FH, bz), 0.02, M_GALV, S, k=8)
    cyl("tension", (ax, TOP + 0.05, az), (bx, TOP + 0.05, bz), 0.005, M_GALV, S, k=4)
    out = (0.0, 0.14) if az == bz and az > 0 else (0.0, -0.14) if az == bz else (-0.14, 0.0) if ax < 0 else (0.14, 0.0)
    for k in range(3):
        f = (k + 1) / 3.2
        cyl("barbed", (ax + out[0] * f, TOP + FH + 0.2 * f, az + out[1] * f), (bx + out[0] * f, TOP + FH + 0.2 * f, bz + out[1] * f), 0.004, M_GALV, S, k=4)
    # the mesh: two sets of diagonals, 0.22 m apart along the run
    step = 0.22
    m = int(length / step)
    dx, dz = (bx - ax) / length, (bz - az) / length
    for k in range(-int(FH / step), m + 1):
        for sgn in (1, -1):
            s0 = k * step
            s1 = s0 + sgn * FH
            lo, hi = 0.0, length
            # clip the diagonal from (s0, 0) to (s1, FH) to the run
            p0, p1 = (s0, 0.06), (s1, FH)
            def clip(p, q):
                (a0, h0), (a1, h1) = p, q
                if a1 == a0:
                    return p, q
                tlo = (lo - a0) / (a1 - a0)
                thi = (hi - a0) / (a1 - a0)
                t0, t1 = max(0.0, min(tlo, thi)), min(1.0, max(tlo, thi))
                if t0 >= t1:
                    return None
                return (a0 + (a1 - a0) * t0, h0 + (h1 - h0) * t0), (a0 + (a1 - a0) * t1, h0 + (h1 - h0) * t1)
            c = clip(p0, p1)
            if c is None:
                continue
            (sa, ha), (sb, hb) = c
            cyl("mesh", (ax + dx * sa, TOP + ha, az + dz * sa), (ax + dx * sb, TOP + hb, az + dz * sb), 0.0035, M_GALV, S, k=3, caps=False)

# ================================================================= azimuth head
H = "head"
lathe("bearing", (0, 2.11, 0), (0, 1, 0), [(0.0, 0.6), (0.2, 0.62), (0.24, 0.52), (0.26, 0.5)], M_WHITE, H, k=32)
lathe("seal", (0, 2.13, 0), (0, 1, 0), [(0.0, 0.6), (0.0, 0.625), (0.035, 0.625), (0.035, 0.6)], M_RUBBER, H, k=32, loop=True)
bolts("bearing_bolts", (0, 2.31, 0), (0, 1, 0), 0.56, 24, 0.016, 0.02, M_GALV, H)
# cable wrap drum below the alidade
lathe("cable_wrap", (0, 2.0, 0), (0, 1, 0), [(0.0, 0.42), (0.11, 0.42)], M_DARK, H, k=24)
for k in range(10):
    a = k * math.tau / 10
    pipe("wrap_cable", [(math.cos(a) * 0.43, 2.02, math.sin(a) * 0.43), (math.cos(a + 0.6) * 0.43, 2.1, math.sin(a + 0.6) * 0.43)], 0.012, M_RUBBER, H)
# alidade: a box girder deck with grating, railings, the yoke arms
box("alidade", -0.78, 2.35, -0.88, 0.78, 2.47, 0.68, M_WHITE, H, bevel=0.015)
box("alidade_grate", -0.74, 2.47, -0.84, 0.74, 2.48, 0.64, M_GRATE, H, bevel=0)
railing("alidade_rail", [(-0.74, 2.48, 0.62), (-0.74, 2.48, -0.84), (0.74, 2.48, -0.84), (0.74, 2.48, 0.62)], M_GALV, H, h=0.5, post=0.5, toe=False)
for x in (-0.64, 0.52):
    # tapering plate arms, a flange of bolts round each elevation bearing
    from mathutils import Vector  # noqa: E402
    b = bmesh.new()
    pts = [(x, 2.47, -0.44), (x + 0.12, 2.47, -0.44), (x + 0.12, 2.47, 0.44), (x, 2.47, 0.44),
           (x, PIVOT_Y + 0.22, -0.18), (x + 0.12, PIVOT_Y + 0.22, -0.18), (x + 0.12, PIVOT_Y + 0.22, 0.18), (x, PIVOT_Y + 0.22, 0.18)]
    from structkit import _cuboid  # noqa: E402
    _cuboid(b, [mc(*p) for p in pts])
    bmesh.ops.recalc_face_normals(b, faces=b.faces)
    new_obj("yoke_arm", b, M_WHITE, H, bevel=0.012)
    side = -1 if x < 0 else 1
    xb = x + (0.12 if side > 0 else 0.0)
    lathe("el_bearing", (xb, PIVOT_Y, 0), (side, 0, 0), [(0.0, 0.17), (0.08, 0.17), (0.09, 0.12)], M_GUN, H, k=20)
    bolts("el_bolts", (xb + side * 0.08, PIVOT_Y, 0), (side, 0, 0), 0.145, 8, 0.012, 0.012, M_GALV, H)
# elevation drive: gearbox and motor on the -x arm
box("gearbox", -0.86, PIVOT_Y - 0.22, -0.2, -0.66, PIVOT_Y + 0.18, 0.2, M_GUN, H, bevel=0.015)
lathe("motor", (-0.86, PIVOT_Y - 0.04, 0.0), (-1, 0, 0), [(0.0, 0.0), (0.0, 0.11), (0.26, 0.11), (0.3, 0.07), (0.3, 0.0)], M_STEEL, H, k=16)
for k in range(8):
    a = k * math.tau / 8
    beam("motor_fin", (-0.9, PIVOT_Y - 0.04 + math.cos(a) * 0.115, math.sin(a) * 0.115), (-1.1, PIVOT_Y - 0.04 + math.cos(a) * 0.115, math.sin(a) * 0.115),
         0.012, 0.02, M_STEEL, H, roll=a)
pipe("motor_cable", [(-1.0, PIVOT_Y - 0.15, 0.08), (-0.95, 2.6, 0.3), (-0.7, 2.49, 0.45)], 0.014, M_RUBBER, H, bend=0.1)
# the jackscrew's anchor bracket at the back of the deck
box("jack_bracket", -0.11, 2.48, -0.87, 0.11, 2.7, -0.65, M_STEEL, H, bevel=0.01)
cyl("jack_pin", (-0.13, 2.6, -0.76), (0.13, 2.6, -0.76), 0.03, M_GALV, H, k=10)
# a junction box and a little ladder up to the deck
box("junction", 0.3, 2.48, 0.3, 0.6, 2.75, 0.5, M_SHELTER, H, bevel=0.01)

# ================================================================= the dish (elevation 0, looking along +z)
D = "dish"
P = PIVOT_Y
cyl("axle", (-0.6, P, 0), (0.6, P, 0), 0.08, M_GALV, D, k=16)
# the hub: a cylinder housing behind the reflector, the lug for the jack below it
lathe("hub", (0, P, -0.3), (0, 0, 1), [(0.0, 0.0), (0.0, 0.44), (0.6, 0.44), (0.6, 0.0)], M_WHITE, D, k=24)
box("lug", -0.07, P + 0.35, -0.58, 0.07, P + 0.7, -0.28, M_STEEL, D, bevel=0.01)
cyl("lug_pin", (-0.1, P + 0.62, -0.5), (0.1, P + 0.62, -0.5), 0.03, M_GALV, D, k=10)
# the reflector: a paraboloid shell (front and back surfaces), a rolled rim
rings = 14
prof = []
for i in range(rings + 1):
    r = max(0.0, DISH_R * i / rings)
    prof.append((r * r / (4 * FOCUS), r))
back = [(t - 0.035, r) for t, r in reversed(prof)]
lathe("reflector", (0, P, VERTEX_Z), (0, 0, 1), prof + [(prof[-1][0] + 0.02, DISH_R + 0.03)] + back, M_DISH, D, k=48, smooth=60)
depth = DISH_R ** 2 / (4 * FOCUS)
lathe("rim", (0, P, VERTEX_Z), (0, 0, 1), [(depth - 0.06, DISH_R + 0.0), (depth - 0.06, DISH_R + 0.05), (depth + 0.03, DISH_R + 0.05), (depth + 0.03, DISH_R)],
      M_WHITE, D, k=48, loop=True)
# back structure: radial trusses of steel and two rings
for i in range(12):
    a = i * math.tau / 12 + 0.13
    ca, sa = math.cos(a), math.sin(a)

    def on(r, dz=0.0):
        return (ca * r, P + sa * r, VERTEX_Z + r * r / (4 * FOCUS) - 0.06 + dz)
    # the rib follows the curve of the reflector, just behind it, in four straight pieces
    rs = [0.44 + (DISH_R * 0.95 - 0.44) * k / 4 for k in range(5)]
    for r0, r1 in zip(rs, rs[1:]):
        ibeam("rib", on(r0, -0.07), on(r1, -0.07), 0.05, 0.1, 0.01, 0.008, M_STEEL, D, roll=a)
    beam("rib_strut", (ca * 0.3, P + sa * 0.3, -0.25), on(DISH_R * 0.6, -0.14), 0.04, 0.04, M_STEEL, D)
for rr in (0.9, 1.6):
    pts = []
    for k in range(33):
        a = k * math.tau / 32
        pts.append((math.cos(a) * rr, P + math.sin(a) * rr, VERTEX_Z + rr * rr / (4 * FOCUS) - 0.16))
    pipe("ring_truss", pts, 0.03, M_STEEL, D, k=8)
# the counterweight on its arm below the hub
beam("cw_arm", (0, P - 0.35, -0.25), (0, P - 1.05, -0.58), 0.1, 0.1, M_STEEL, D)
box("counterweight", -0.32, P - 1.38, -0.82, 0.32, P - 0.95, -0.38, M_GUN, D, bevel=0.03)
# Cassegrain optics: the feed horn out of the hub, the sub-reflector on a quadripod
lathe("feed", (0, P, VERTEX_Z), (0, 0, 1), [(-0.05, 0.3), (0.0, 0.3), (0.08, 0.2), (0.55, 0.13), (0.62, 0.13), (0.62, 0.11)], M_WHITE, D, k=24)
lathe("feed_window", (0, P, VERTEX_Z + 0.62), (0, 0, 1), [(0.0, 0.11), (0.005, 0.0)], M_DARK, D, k=16)
sub_z = VERTEX_Z + FOCUS * 1.1
lathe("subreflector", (0, P, sub_z), (0, 0, 1), [(-0.09, 0.0), (-0.07, 0.15), (-0.03, 0.3), (0.0, 0.35), (0.05, 0.35), (0.07, 0.2), (0.08, 0.0)], M_WHITE, D, k=24)
for i in range(4):
    a = i * math.pi / 2 + math.pi / 4
    r = DISH_R * 0.72
    foot = (math.cos(a) * r, P + math.sin(a) * r, VERTEX_Z + r * r / (4 * FOCUS) + 0.01)
    head = (math.cos(a) * 0.28, P + math.sin(a) * 0.28, sub_z + 0.03)
    section("quadripod", foot, head, [(-0.035, -0.02), (0.035, -0.02), (0.045, 0.0), (0.035, 0.02), (-0.035, 0.02), (-0.045, 0.0)], M_WHITE, D)
# the waveguide from the hub down the back
pipe("waveguide", [(0.3, P - 0.44, -0.2), (0.3, P - 0.44, 0.25)], 0.03, M_GUN, D)

# ================================================================= the jackscrew (unit length along +y)
cyl("jack_barrel", (0, 0, 0), (0, 1, 0), 0.085, M_WHITE, "jack", k=16)
lathe("jack_ends", (0, 0, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.1), (0.04, 0.1), (0.04, 0.0)], M_GUN, "jack", k=16)
cyl("jack_screw", (0, 0, 0), (0, 1, 0), 0.04, M_GALV, "rod", k=12)

for part in ("site", "head", "dish", "jack", "rod"):
    join_part(part)

if "shots" in ARGS:
    for part in ("jack", "rod"):
        for ob in PARTS[part]:
            ob.hide_render = True
    outdoor()
    shoot("uplink_front", (9.5, 4.5, 7.5), (0.4, 2.0, 0.0), lens=30)
    shoot("uplink_back", (-8.0, 5.5, -7.0), (0.4, 2.2, 0.0), lens=30)
    shoot("uplink_dish", (3.5, 3.5, 4.5), (0.0, 3.2, 0.8), lens=35)
if "export" in ARGS:
    export("uplink", ["site", "head", "dish", "jack", "rod"], pivots={"dish": (0.0, PIVOT_Y, 0.0)}, tex=2048, ao=0.5)
