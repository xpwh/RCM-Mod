"""The ballistic missile submarine (SubmarineModel), after an Ohio-class SSBN, built in Blender.

Sub space (block space turned to the block's facing): +z is the bow, origin at the bottom centre of the silo block,
which lies in the keel; the hull axis runs along z at y = AXIS_Y. The 24 missile tubes stand in two rows under the
turtleback; the live tube (the block's) is the starboard-aft one of the fourth pair... at the origin's x/z, so the
hull's centreline lies HX to the side.

Parts:
  hull   the whole boat: pressure hull under its anechoic tiles, the turtleback with 23 closed hatches and the
         live tube's open mouth, sail with fairwater planes and masts, cruciform stern, seven-blade propeller
  hatch  the live tube's hatch, closed, about its hinge at the aft edge (0, DECK_Y, -0.45) - the game turns it
         about x

    python submarine.py <out_dir> [--shots] [--export]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from silokit import *  # noqa: E402,F401,F403
from mathutils import Vector  # noqa: E402
from structkit import _coords, _finish, _math, _mix, _mixf, _noise, _ramp, node_mat, weathered  # noqa: E402

HULL_R = 2.1
AXIS_Y = 2.2
DECK_Y = AXIS_Y + HULL_R + 0.35
HX = -0.55              # the hull's centreline (the live tube row is at x = 0, the other at x = -1.1)
ROWS = (0.0, -1.1)
HATCH_R = 0.44
PITCH = 1.1
HATCH_Z = [PITCH * k for k in range(-8, 4)]   # -8.8 .. 3.3: twelve pairs
DECK_W = 1.15           # half-width of the turtleback's flat top
SAIL_LE, SAIL_TE = 10.4, 5.6
SAIL_TOP = DECK_Y + 3.4
MAST_LIGHT = (HX, DECK_Y + 4.55, 8.2)

# ---------------------------------------------------------------- materials


def anechoic(name, base=(0.02, 0.021, 0.023), period=0.55):
    """The anechoic coating: rubber tiles in a faint grid, each a shade apart, a few missing, limber holes along the
    turtleback's flanks with rust running down from them, silt and growth low on the hull, a faint old waterline."""
    m, nt, bsdf = node_mat(name)
    pos = _coords(nt)
    sc = nt.nodes.new("ShaderNodeVectorMath")
    sc.operation = "SCALE"
    nt.links.new(pos, sc.inputs[0])
    sc.inputs["Scale"].default_value = 1.0 / period
    fr = nt.nodes.new("ShaderNodeVectorMath")
    fr.operation = "FRACTION"
    nt.links.new(sc.outputs[0], fr.inputs[0])
    fl = nt.nodes.new("ShaderNodeVectorMath")
    fl.operation = "FLOOR"
    nt.links.new(sc.outputs[0], fl.inputs[0])
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(fr.outputs[0], sep.inputs[0])
    seam = None
    for k in range(3):
        s = _math(nt, "LESS_THAN", sep.outputs[k], 0.022)
        seam = s if seam is None else _math(nt, "MAXIMUM", seam, s)
    tint = _noise(nt, 3.7, 1, 0.5, fl.outputs[0])
    miss = _math(nt, "GREATER_THAN", _noise(nt, 1.3, 1, 0.5, fl.outputs[0]), 0.77)
    col = _mix(nt, tint, tuple(c * 0.7 for c in base), tuple(c * 1.5 for c in base))
    col = _mix(nt, _math(nt, "MULTIPLY", seam, 0.6), col, (0.036, 0.036, 0.038))
    col = _mix(nt, miss, col, (0.05, 0.04, 0.034))
    wp = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(pos, wp.inputs[0])
    by, bz = wp.outputs["Z"], _math(nt, "MULTIPLY", wp.outputs["Y"], -1.0)   # block y, block z
    # limber holes: a row of slots on both flanks of the turtleback
    band = _math(nt, "MULTIPLY", _math(nt, "GREATER_THAN", by, AXIS_Y + 1.42), _math(nt, "LESS_THAN", by, AXIS_Y + 1.54))
    span = _math(nt, "MULTIPLY", _math(nt, "GREATER_THAN", bz, -9.9), _math(nt, "LESS_THAN", bz, 4.1))
    slot = _math(nt, "LESS_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", bz, 0.7)), 0.55)
    holes = _math(nt, "MULTIPLY", _math(nt, "MULTIPLY", band, span), slot)
    # rust and silt running down from them
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = (9.0, 9.0, 0.5)
    nt.links.new(pos, mp.inputs["Vector"])
    st = _ramp(nt, _noise(nt, 2.0, 4, 0.5, mp.outputs[0]), 0.5, 0.75)
    below = _math(nt, "MULTIPLY", _math(nt, "LESS_THAN", by, AXIS_Y + 1.42), _ramp(nt, by, AXIS_Y + 0.2, AXIS_Y + 1.4))
    col = _mix(nt, _math(nt, "MULTIPLY", _math(nt, "MULTIPLY", _math(nt, "MULTIPLY", st, below), span), 0.55), col, (0.1, 0.05, 0.025))
    # old waterline and the growth and silt low down
    wl = _math(nt, "MULTIPLY", _ramp(nt, by, AXIS_Y + 0.9, AXIS_Y + 1.05), _ramp(nt, by, AXIS_Y + 1.25, AXIS_Y + 1.1))
    col = _mix(nt, _math(nt, "MULTIPLY", wl, 0.35), col, (0.08, 0.075, 0.05))
    low = _math(nt, "MULTIPLY", _ramp(nt, by, 1.6, 0.25), _mixf(nt, _noise(nt, 1.5, 6, 0.65, pos), 0.3, 1.2))
    col = _mix(nt, _math(nt, "MINIMUM", low, 1.0), col, (0.085, 0.08, 0.055))
    alg = _math(nt, "MULTIPLY", _ramp(nt, by, 2.6, 0.8), _ramp(nt, _noise(nt, 4.0, 6, 0.7, pos), 0.55, 0.75))
    col = _mix(nt, _math(nt, "MULTIPLY", alg, 0.5), col, (0.05, 0.07, 0.035))
    col = _mix(nt, holes, col, (0.004, 0.004, 0.004))
    col = weathered(nt, col, 0.3, 0.0, 0.3, 0.0)
    rough = _mixf(nt, seam, _mixf(nt, tint, 0.68, 0.82), 0.6)
    return _finish(m, nt, bsdf, col, 0.0, rough)


M_HULL = anechoic("anechoic")
M_HATCH = anechoic("hatch_coat", base=(0.026, 0.027, 0.03), period=0.9)
M_MAST = paint("mast", (0.03, 0.032, 0.035), rough=0.45, metal=0.4, grime=0.4, wear=0.3, streaks=0.4, rust=0.2, wear_col=(0.25, 0.25, 0.25))
M_STEEL = paint("steel", (0.12, 0.12, 0.12), rough=0.45, metal=0.7, grime=0.5, wear=0.3, rust=0.4, wear_col=(0.35, 0.35, 0.35))
M_BRONZE = paint("bronze", (0.08, 0.06, 0.035), rough=0.55, metal=0.55, grime=0.7, wear=0.25, streaks=0.4, rust=0.0, wear_col=(0.3, 0.22, 0.12))
M_DARK = plain("void", (0.006, 0.006, 0.006), 0.95)
M_WHITE = paint("draft_white", (0.62, 0.62, 0.6), rough=0.55, grime=0.5, wear=0.4, streaks=0.4)
M_GLASS = glass("glass", (0.02, 0.03, 0.035))
M_RED_LENS = glass("red_lens", (0.3, 0.02, 0.01))
M_RUBBER = rubber()

H = "hull"

# ================================================================= the hull
def radius(z):
    if z >= 11.0:
        u = min(1.0, (z - 11.0) / 6.0)
        return HULL_R * max(0.0, 1.0 - u ** 2.2) ** (1 / 2.2)
    if z >= -9.0:
        return HULL_R
    u = max(0.0, (z + 16.4) / 7.4)
    return 0.45 + (HULL_R - 0.45) * (1.0 - (1.0 - u) ** 2)


def smooth(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def deck(z):
    """(height of the turtleback's top, half-width of its flat) at z, or None where there is none."""
    top = AXIS_Y + radius(z)
    if -9.6 <= z <= 4.2:
        t = 1.0
    elif -12.8 < z < -9.6:
        t = smooth((z + 12.8) / 3.2)
    elif 4.2 < z < 6.9:
        t = smooth((6.9 - z) / 2.7)
    else:
        return None
    return top + (DECK_Y - top) * t, DECK_W * (0.35 + 0.65 * t)


def hull2d(z):
    """The hull's outline at z (x, y relative to the axis): the circle, with the turtleback's convex hull on top."""
    r = radius(z)
    pts = [(math.cos(2 * math.pi * i / 96) * r, math.sin(2 * math.pi * i / 96) * r) for i in range(96)]
    d = deck(z)
    if d is not None and d[0] - AXIS_Y > r + 1e-3:
        h, w = d[0] - AXIS_Y, d[1]
        pts += [(-w, h), (w, h), (-w - 0.1, h - 0.04), (w + 0.1, h - 0.04), (-w - 0.17, h - 0.12), (w + 0.17, h - 0.12)]
    pts = sorted(set(pts))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def ray(poly, ang):
    dx, dy = math.cos(ang), math.sin(ang)
    best = None
    n = len(poly)
    for i in range(n):
        (ax, ay), (bx, by) = poly[i], poly[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        den = dx * ey - dy * ex
        if abs(den) < 1e-12:
            continue
        t = (ax * ey - ay * ex) / den
        u = (ax * dy - ay * dx) / den
        if t > 0 and -1e-9 <= u <= 1 + 1e-9:
            best = t if best is None else min(best, t)
    return best


N_RING = 48
# ring angles: every 7.5 degrees, and the turtleback's corners exactly
ANGLES = [-math.pi / 2 + 2 * math.pi * i / N_RING for i in range(N_RING)]
Z_STATIONS = [-16.4, -16.1, -15.6, -14.9, -14.0, -13.0, -12.8, -12.0, -11.2, -10.4, -9.6, -9.0, -7.0, -4.6, -2.2, -0.6, 0.6, 2.2, 4.2, 4.9,
              5.6, 6.3, 6.9, 8.5, 11.0, 12.4, 13.6, 14.6, 15.4, 16.0, 16.5, 16.85, 17.0]
rings = []
for z in Z_STATIONS:
    poly = hull2d(z)
    ring = []
    for a in ANGLES:
        d = ray(poly, a)
        if d is None or radius(z) < 1e-3:
            d = 0.0
        ring.append((HX + math.cos(a) * d, AXIS_Y + math.sin(a) * d, z))
    rings.append(ring)
rings[-1] = [(HX, AXIS_Y, 17.0)] * N_RING
hull_ob = loft("pressure_hull", rings[:-1], M_HULL, H, cap0=True, cap1=False, smooth=50)
# close the bow with a fan to its tip
import bmesh as _bm  # noqa: E402
bm = _bm.new()
bm.from_mesh(hull_ob.data)
bm.verts.ensure_lookup_table()
last = [v for v in bm.verts if abs(-v.co.y - Z_STATIONS[-2]) < 1e-4]
last.sort(key=lambda v: math.atan2(v.co.z - AXIS_Y, v.co.x - HX))
tip = bm.verts.new(mc(HX, AXIS_Y, 17.0))
for i in range(len(last)):
    bm.faces.new((last[i], last[(i + 1) % len(last)], tip))
_bm.ops.recalc_face_normals(bm, faces=bm.faces)
bm.to_mesh(hull_ob.data)
bm.free()
for p in hull_ob.data.polygons:
    p.use_smooth = True
hull_ob.data.set_sharp_from_angle(angle=math.radians(50))

# the live tube's mouth: cut through the deck, a dark-walled tube below with a floor
cutter = cyl("cutter", (0.0, DECK_Y - 1.0, 0.0), (0.0, DECK_Y + 0.6, 0.0), HATCH_R + 0.01, M_DARK, "_cut", k=20)
bo = hull_ob.modifiers.new("hole", "BOOLEAN")
bo.operation = "DIFFERENCE"
bo.solver = "EXACT"
bo.object = cutter
cutter.hide_render = True
cutter.hide_viewport = True
inner_tube("tube_wall", HATCH_R + 0.01, DECK_Y - 2.6, DECK_Y - 0.005, 20, M_STEEL, H)
lathe("tube_floor", (0.0, DECK_Y - 2.6, 0.0), (0, 1, 0), [(0.0, 0.0), (0.0, HATCH_R + 0.02), (0.01, HATCH_R + 0.02), (0.01, 0.0)], M_DARK, H, k=20)
for y in (DECK_Y - 0.4, DECK_Y - 1.2):
    lathe("tube_ring", (0.0, y, 0.0), (0, 1, 0), [(0.0, HATCH_R + 0.01), (0.0, HATCH_R - 0.03), (0.06, HATCH_R - 0.03), (0.06, HATCH_R + 0.01)], M_STEEL, H, k=20,
          loop=True)
# the membrane seal ring round the mouth
lathe("mouth_ring", (0.0, DECK_Y - 0.004, 0.0), (0, 1, 0), [(0.0, HATCH_R + 0.012), (0.0, HATCH_R + 0.055), (0.005, HATCH_R + 0.055), (0.005, HATCH_R + 0.012)],
      M_STEEL, H, k=20, loop=True)

# the 23 closed hatches, flush in the deck: a slightly domed cover, the seam round it, the hinge fairing aft
for z in HATCH_Z:
    for x in ROWS:
        if x == 0.0 and z == 0.0:
            continue
        lathe("hatch", (x, DECK_Y - 0.01, z), (0, 1, 0), [(0.0, 0.0), (0.0, HATCH_R), (0.018, HATCH_R - 0.006), (0.024, HATCH_R * 0.6), (0.027, 0.0)],
              M_HATCH, H, k=18, smooth=40)
        lathe("hatch_seam", (x, DECK_Y + 0.0005, z), (0, 1, 0), [(0.0, HATCH_R), (0.0015, HATCH_R), (0.0015, HATCH_R + 0.022), (0.0, HATCH_R + 0.022)],
              M_DARK, H, k=18, loop=True)
        box("hatch_hinge", x - 0.16, DECK_Y - 0.01, z - HATCH_R - 0.06, x + 0.16, DECK_Y + 0.025, z - HATCH_R + 0.05, M_HATCH, H, bevel=0.01)

# rescue/escape trunks fore and aft
for z in (-11.4, 11.6):
    top = AXIS_Y + radius(z)
    lathe("trunk", (HX, top - 0.12, z), (0, 1, 0), [(0.0, 0.0), (0.0, 0.62), (0.13, 0.6), (0.16, 0.5), (0.16, 0.0)], M_HATCH, H, k=16, smooth=35)
    lathe("trunk_seam", (HX, top + 0.042, z), (0, 1, 0), [(0.0, 0.42), (0.002, 0.42), (0.002, 0.45), (0.0, 0.45)], M_DARK, H, k=16, loop=True)
# retractable cleats and the safety track down the middle of the deck
for z in (-10.2, 4.0, 12.6, 13.6):
    top = deck(z)[0] if deck(z) else AXIS_Y + radius(z)
    for s in (-1, 1):
        box("cleat", HX + s * 0.75 - 0.12, top - 0.02, z - 0.04, HX + s * 0.75 + 0.12, top + 0.012, z + 0.04, M_STEEL, H, bevel=0.005)
box("track", HX - 0.025, DECK_Y - 0.003, -9.4, HX + 0.025, DECK_Y + 0.004, 4.0, M_STEEL, H, bevel=0)
# flank sonar arrays near the bow, the towed array fairing down the starboard side
for s in (-1, 1):
    rings_fa = []
    for z in (8.0, 8.06, 10.74, 10.8):
        lift = 0.0 if z in (8.0, 10.8) else 0.018
        ring = []
        for k in range(7):
            a = -0.34 + 0.68 * k / 6
            rr = HULL_R + (0.0 if k in (0, 6) else lift) - 0.002
            ring.append((HX + s * math.cos(a) * rr, AXIS_Y + math.sin(a) * rr, z))
        rings_fa.append(ring)
    loft("flank_array", rings_fa, M_HATCH, H, closed=False, smooth=30, outward=lambda p: Vector((p.x - HX, 0.0, p.z - AXIS_Y)))
pipe("towed_array", [(HX + 1.25, AXIS_Y + 0.55, -13.2), (HX + 1.82, AXIS_Y + 0.78, -10.6), (HX + 1.95, AXIS_Y + 0.8, -9.0), (HX + 1.95, AXIS_Y + 0.8, 9.0)],
     0.13, M_HULL, H, k=10, bend=1.0)
# draft marks at bow and stern, both sides: a ladder of marks and a numeral block beside each
for zc in (14.2, -13.4):
    for s in (-1, 1):
        for k in range(4):
            y = AXIS_Y - 1.6 + k * 0.5
            r = radius(zc)
            dy = y - AXIS_Y
            if abs(dy) >= r:
                continue
            xs = HX + s * (math.sqrt(r * r - dy * dy) - 0.012)
            box("draft", min(xs, xs + s * 0.03), y, zc - 0.03, max(xs, xs + s * 0.03), y + 0.07, zc + 0.03, M_WHITE, H, bevel=0)
            box("draft_num", min(xs, xs + s * 0.03), y, zc + 0.08, max(xs, xs + s * 0.03), y + 0.16, zc + 0.12, M_WHITE, H, bevel=0)
            box("draft_num", min(xs, xs + s * 0.03), y + 0.12, zc + 0.12, max(xs, xs + s * 0.03), y + 0.16, zc + 0.2, M_WHITE, H, bevel=0)


# ================================================================= foils
def naca(t, n=8):
    """Half-thickness distribution (chord 1) of a symmetric NACA section, n stations from the nose."""
    out = []
    for i in range(n + 1):
        s = 0.5 * (1 - math.cos(math.pi * i / n))
        y = 5 * t * (0.2969 * math.sqrt(s) - 0.126 * s - 0.3516 * s * s + 0.2843 * s ** 3 - 0.1036 * s ** 4)
        out.append((s, y))
    return out


def section_pts(le, chord_dir, thick_dir, chord, t, n=8):
    """A closed loop round an airfoil section (block space), nose at le."""
    up, lo = [], []
    for s, y in naca(t, n):
        base = [le[k] + chord_dir[k] * s * chord for k in range(3)]
        up.append(tuple(base[k] + thick_dir[k] * y * chord for k in range(3)))
        lo.append(tuple(base[k] - thick_dir[k] * y * chord for k in range(3)))
    return up + lo[-2:0:-1]


def foil(name, sections, mat, part, cap0=True, cap1=True, smooth=40.0):
    loft(name, sections, mat, part, cap0=cap0, cap1=cap1, smooth=smooth)


# the sail: a tall streamlined fin, rounded nose forward
sail = []
for y, le, chord, w in ((DECK_Y - 0.65, SAIL_LE + 0.35, 5.6, 1.5), (DECK_Y - 0.2, SAIL_LE + 0.1, 5.0, 1.28), (DECK_Y + 0.3, SAIL_LE, 4.8, 1.2),
                        (DECK_Y + 2.0, SAIL_LE - 0.05, 4.75, 1.17), (SAIL_TOP - 0.12, SAIL_LE - 0.1, 4.6, 1.12), (SAIL_TOP, SAIL_LE - 0.16, 4.45, 1.0)):
    sail.append(section_pts((HX, y, le), (0, 0, -1), (1, 0, 0), chord, w / chord, n=10))
foil("sail", sail, M_HULL, H, cap0=False, cap1=True, smooth=50)
# the bridge cockpit at the top front, the windows round it
box("bridge", HX - 0.26, SAIL_TOP - 0.02, SAIL_LE - 1.15, HX + 0.26, SAIL_TOP + 0.004, SAIL_LE - 0.55, M_DARK, H, bevel=0)
for s in (-1, 1):
    for k in range(3):
        z = SAIL_LE - 0.55 - k * 0.32
        xw = HX + s * 0.47
        box("window", min(xw, xw + s * 0.06), SAIL_TOP - 0.3, z - 0.11, max(xw, xw + s * 0.06), SAIL_TOP - 0.18, z + 0.11, M_GLASS, H, bevel=0)
# fairwater planes on the sail's flanks
for s in (-1, 1):
    secs = []
    for xr, le, chord, t in ((0.3, 8.95, 1.4, 0.11), (0.6, 8.92, 1.35, 0.1), (1.6, 8.8, 1.05, 0.09), (2.45, 8.66, 0.75, 0.08)):
        secs.append(section_pts((HX + s * xr, DECK_Y + 2.05, le), (0, 0, -1), (0, 1, 0), chord, t, n=6))
    foil("fairwater_plane", secs, M_HULL, H)
    box("plane_hinge", min(HX + s * 0.52, HX + s * 0.62), DECK_Y + 1.93, 8.1, max(HX + s * 0.52, HX + s * 0.62), DECK_Y + 2.17, 8.55, M_HULL, H, bevel=0.03)
# masts out of the sail top: two periscopes, the radar, an ESM mast, the communications mast with its light
for z, r, h, mat in ((9.2, 0.075, 1.25, M_MAST), (8.75, 0.09, 0.95, M_MAST), (7.6, 0.06, 0.7, M_MAST), (7.05, 0.07, 1.05, M_MAST)):
    cyl("mast", (HX, SAIL_TOP - 0.05, z), (HX, SAIL_TOP + h, z), r, mat, H, k=8)
    lathe("mast_head", (HX, SAIL_TOP + h, z), (0, 1, 0), [(0.0, r + 0.02), (0.12, r + 0.02), (0.18, r * 0.7), (0.2, 0.0)], mat, H, k=8)
lathe("periscope_window", (HX, SAIL_TOP + 1.33, 9.2 + 0.085), (0, 0, 1), [(0.0, 0.035), (0.01, 0.035), (0.01, 0.0)], M_GLASS, H, k=8)
# the communications mast the game's light sits on: (HX +- 0.08, DECK_Y + 4.55 .. 4.7, 8.12 .. 8.28)
cyl("comm_mast", (HX, SAIL_TOP - 0.05, MAST_LIGHT[2]), (HX, MAST_LIGHT[1] - 0.02, MAST_LIGHT[2]), 0.05, M_MAST, H, k=8)
lathe("comm_light_seat", (HX, MAST_LIGHT[1] - 0.03, MAST_LIGHT[2]), (0, 1, 0), [(0.0, 0.0), (0.0, 0.085), (0.03, 0.085), (0.03, 0.0)], M_MAST, H, k=8)
lathe("comm_lens", (HX, MAST_LIGHT[1], MAST_LIGHT[2]), (0, 1, 0), [(0.0, 0.06), (0.09, 0.06), (0.13, 0.035), (0.14, 0.0)], M_RED_LENS, H, k=8)
box("radar_head", HX - 0.18, SAIL_TOP + 0.7, 7.5, HX + 0.18, SAIL_TOP + 0.82, 7.7, M_MAST, H, bevel=0.03)
box("esm_head", HX - 0.09, SAIL_TOP + 1.05, 6.96, HX + 0.09, SAIL_TOP + 1.25, 7.14, M_MAST, H, bevel=0.03)

# the cruciform stern: rudders up and down, stern planes with their end plates
for k, (ang, tip) in enumerate(((0.0, 3.3), (math.pi / 2, 2.9), (math.pi, 3.3), (-math.pi / 2, 2.1))):
    ca, sa = math.cos(ang), math.sin(ang)
    span = (ca, sa, 0.0)
    thick = (-sa, ca, 0.0)
    secs = []
    for rr, le, chord, t in ((0.5, -12.4, 3.4, 0.12), (1.2, -12.75, 3.0, 0.11), (2.0, -13.3, 2.25, 0.1), (tip, -13.75, 1.75, 0.09)):
        secs.append(section_pts((HX + ca * rr, AXIS_Y + sa * rr, le), (0, 0, -1), thick, chord, t, n=6))
    foil("stern_fin", secs, M_HULL, H)
    if abs(sa) < 0.5:
        # end plates on the stern planes
        xe = HX + ca * (tip + 0.02)
        eps = []
        for yy in (-0.65, 0.0, 0.65):
            f = 1.0 - abs(yy) * 0.35
            eps.append(section_pts((xe, AXIS_Y + yy, -13.55 - (1 - f) * 0.4), (0, 0, -1), (1, 0, 0), 2.0 * f, 0.06, n=6))
        foil("end_plate", eps, M_HULL, H)
# the propeller: a hub cone and seven skewed blades of weathered bronze
lathe("hub", (HX, AXIS_Y, -16.4), (0, 0, -1), [(0.0, 0.46), (0.3, 0.44), (0.6, 0.38), (0.9, 0.3), (1.1, 0.18), (1.2, 0.0)], M_BRONZE, H, k=16, smooth=50)
ZP = -16.85
for b in range(7):
    a0 = b * 2 * math.pi / 7
    secs = []
    for rr, chord, t in ((0.3, 0.34, 0.12), (0.55, 0.44, 0.08), (0.85, 0.42, 0.06), (1.08, 0.3, 0.05), (1.2, 0.12, 0.05)):
        a = a0 + 0.42 * ((rr - 0.3) / 0.9) ** 1.5
        beta = math.atan(1.25 / (2 * math.pi * rr))
        tx, ty = -math.sin(a), math.cos(a)
        cd = (tx * math.cos(beta), ty * math.cos(beta), math.sin(beta))
        radial = (math.cos(a), math.sin(a), 0.0)
        td = (radial[1] * cd[2] - radial[2] * cd[1], radial[2] * cd[0] - radial[0] * cd[2], radial[0] * cd[1] - radial[1] * cd[0])
        c = (HX + math.cos(a) * rr, AXIS_Y + math.sin(a) * rr, ZP)
        le = tuple(c[k] - cd[k] * chord * 0.45 for k in range(3))
        secs.append(section_pts(le, cd, td, chord, t, n=5))
    foil("blade", secs, M_BRONZE, H)

# ================================================================= the live hatch (closed)
P = "hatch"
lathe("live_hatch", (0.0, DECK_Y - 0.07, 0.0), (0, 1, 0), [(0.0, 0.0), (0.0, HATCH_R - 0.02), (0.02, HATCH_R), (0.07, HATCH_R), (0.088, HATCH_R - 0.006),
                                                         (0.094, HATCH_R * 0.6), (0.097, 0.0)], M_HATCH, P, k=20, smooth=40)
lathe("live_seal", (0.0, DECK_Y - 0.075, 0.0), (0, 1, 0), [(0.0, HATCH_R - 0.1), (0.006, HATCH_R - 0.1), (0.006, HATCH_R - 0.04), (0.0, HATCH_R - 0.04)],
      M_RUBBER, P, k=20, loop=True)
for k in range(3):
    xx = (k - 1) * 0.2
    beam("strongback", (xx, DECK_Y - 0.1, -0.36), (xx, DECK_Y - 0.1, 0.36), 0.05, 0.06, M_STEEL, P)
beam("strongback", (-0.3, DECK_Y - 0.1, 0.0), (0.3, DECK_Y - 0.1, 0.0), 0.05, 0.06, M_STEEL, P)
box("live_hinge", -0.16, DECK_Y - 0.06, -HATCH_R - 0.05, 0.16, DECK_Y + 0.025, -HATCH_R + 0.06, M_HATCH, P, bevel=0.01)
cyl("hinge_pin", (-0.2, DECK_Y, -HATCH_R - 0.01), (0.2, DECK_Y, -HATCH_R - 0.01), 0.025, M_STEEL, P, k=8)

for p in ("hull", "hatch"):
    join_part(p)
count_quads(["hull", "hatch"])

if "shots" in ARGS:
    outdoor(sun_elevation=40, sun_rotation=150, ground=(0.25, 0.22, 0.16))
    scene.view_settings.exposure = -1.0
    ground_plane(0.0)
    TAG = os.environ.get("TAG", "")
    which = os.environ.get("SHOTS", "side,top,stern,deck")
    made = []
    for name, ang, eye, target, lens in (("side", 0.0, (16.0, 9.0, 14.0), (HX, 3.0, 0.0), 28), ("stern", 0.0, (-6.0, 4.5, -24.0), (HX, 2.5, -14.0), 35),
                                         ("top", 1.75, (6.0, 9.5, -6.5), (0.0, 4.6, 0.5), 30), ("deck", 1.75, (2.6, 6.2, -3.4), (-0.3, 4.7, 0.6), 28),
                                         ("bow", 0.0, (-10.0, 5.0, 26.0), (HX, 3.5, 10.0), 32)):
        if name not in which:
            continue
        for ob in made:
            bpy.data.objects.remove(ob)
        hide_part("hatch")
        made = [pose_copy("hatch", block_matrix((0.0, DECK_Y, -0.45), ((1, 0, 0), -ang)), pivot=(0.0, DECK_Y, -0.45))]
        shoot("sub_" + name + TAG, eye, target, lens=lens)
if "export" in ARGS:
    export("submarine", ["hull", "hatch"], pivots={"hatch": (0.0, DECK_Y, -0.45)}, tex=2048, ao=0.6)
