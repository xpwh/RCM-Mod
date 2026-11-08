"""The flyable F-22A Raptor and F-35A Lightning II, built in Blender to their real dimensions (FighterRenderer in
the game). 1 block = 1 m.

Model space here is the renderer's: x right, y forward (nose +y), z up, the fuselage's centreline on z = 0 (it stands
2.3 m above the ground on its gear). The export (structkit) writes block space (x, up, -forward); FighterRenderer turns
it back with a quarter turn about x.

f22   18.9 m long, 13.6 m span. Chined diamond-section fuselage, the long frameless gold-tinted bubble canopy, caret
      intakes, the diamond wing (42 deg leading-edge sweep, trailing edge swept forward 17 deg), flaperons and flaps,
      all-moving stabilators, twin tails canted out 28 deg with rudders, two 2D thrust-vectoring nozzles with upper
      and lower flaps. Two-tone air-superiority grey with the Have Glass sheen.
f35   15.7 m long, 10.7 m span. A deeper, shorter fuselage, the canopy with its bow frame, DSI bump intakes, the
      EOTS window under the nose, a trapezoid wing (34 deg), canted tails, the single round F135 nozzle with its
      serrated petals. Dark grey with the lighter edge treatments.

Parts (each about its own pivot, PIVOTS below - FighterRenderer animates them):
  body          the airframe
  glass         the canopy glazing (drawn see-through), canopy_frame its frame and sill
  pit           the cockpit: tub, consoles, the instrument panel with its display bezels, the glare shield, the
                HUD frame, the ejection seat
  stick         the side-stick (right console), throttle the throttle grip (left console)
  flap_l/r      inboard trailing-edge flaps        aileron_l/r  outboard flaperons
  stab_l/r      the all-moving stabilators         rudder_l/r   the rudders
  nose_gear     nose leg and wheel (retracts forward)   main_l/r  the main legs (fold inward)
  door_nose_l/r, door_main_l/r   the gear doors
  bay_l/r       the main weapons-bay doors (hinged at the outer edge)   bay  the bay inside with its missiles
  nozzle_*      F-22: the upper and lower nozzle flaps per engine (nozzle_lu, nozzle_ld, nozzle_ru, nozzle_rd);
                F-35: the round nozzle (scaled open with the afterburner)

    python fighters.py <out_dir> --model=f22|f35 [--shots] [--export]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from structkit import _coords, _finish, _math, _mix, _mixf, _noise, _ramp  # noqa: E402

MODEL = ARGS.get("model", "f22")
F22 = MODEL == "f22"
GROUND = -2.3  # the ground, below the centreline


def blk(x, y, z):
    """Model space (x right, y forward, z up) to block space (for structkit's pivots)."""
    return (x, z, -y)


# ================================================================== materials


def skin(name, light, dark, sheen=0.25, panel=0.75, edge_light=False):
    """Fighter paint: two greys in soft-edged splinters, panel lines, access panels, the stealth coating's slightly
    uneven finish, a metallic sheen; worn a little where hands and boots go."""
    m, nt, bsdf = node_mat(name)
    pos = _coords(nt)
    split = _ramp(nt, _noise(nt, 0.09, 3, 0.4, pos), 0.47, 0.53)
    base = _mix(nt, split, tuple(light), tuple(dark))
    base = _mix(nt, _math(nt, "MULTIPLY", _noise(nt, 2.5, 6, 0.6, pos), 0.12), base, tuple(c * 0.85 for c in dark))
    # panel lines: a brick grid projected along the fuselage, seen from above/below and from the side
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(pos, sep.inputs[0])
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    nsep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(geo.outputs["Normal"], nsep.inputs[0])

    def grid(a, b, scale, mortar):
        comb = nt.nodes.new("ShaderNodeCombineXYZ")
        nt.links.new(a, comb.inputs[0])
        nt.links.new(b, comb.inputs[1])
        br = nt.nodes.new("ShaderNodeTexBrick")
        br.inputs["Scale"].default_value = scale
        br.inputs["Mortar Size"].default_value = mortar
        br.inputs["Mortar Smooth"].default_value = 0.2
        br.inputs["Brick Width"].default_value = 2.1
        br.inputs["Row Height"].default_value = 0.7
        br.offset = 0.37
        br.squash = 1.0
        nt.links.new(comb.outputs[0], br.inputs["Vector"])
        return br.outputs["Fac"]

    top = grid(sep.outputs["Y"], sep.outputs["X"], panel, 0.006)
    side = grid(sep.outputs["Y"], sep.outputs["Z"], panel, 0.006)
    nz = _math(nt, "ABSOLUTE", nsep.outputs["Z"])
    lines = _mixf(nt, _ramp(nt, nz, 0.45, 0.65), side, top)
    if edge_light:
        # the F-35's lighter edge treatment round its panels
        wide_t = grid(sep.outputs["Y"], sep.outputs["X"], panel, 0.03)
        wide_s = grid(sep.outputs["Y"], sep.outputs["Z"], panel, 0.03)
        wide = _mixf(nt, _ramp(nt, nz, 0.45, 0.65), wide_s, wide_t)
        base = _mix(nt, _math(nt, "MULTIPLY", wide, 0.55), base, tuple(c * 1.32 for c in light))
    base = _mix(nt, _math(nt, "MULTIPLY", lines, 0.45), base, tuple(c * 0.6 for c in dark))
    col = weathered(nt, base, grime=0.25, wear=0.15, streaks=0.25, rust=0.0, wear_col=tuple(c * 1.2 for c in light), scale=0.4)
    rough = _mixf(nt, _noise(nt, 1.6, 5, 0.6, pos), 0.38, 0.55)
    return _finish(m, nt, bsdf, col, sheen, rough, 0.25)


def metal(name, color, rough=0.35, burnt=0.0):
    """Bare or heat-tinted metal (nozzles, gear legs)."""
    m, nt, bsdf = node_mat(name)
    pos = _coords(nt)
    col = _mix(nt, _noise(nt, 6.0, 5, 0.6, pos), tuple(color), tuple(c * 0.75 for c in color))
    if burnt > 0:
        tint = _ramp(nt, _noise(nt, 1.5, 4, 0.5, pos), 0.4, 0.6)
        col = _mix(nt, _math(nt, "MULTIPLY", tint, burnt), col, (0.16, 0.12, 0.16))
    col = weathered(nt, col, 0.35, 0.1, 0.3, 0.0, scale=0.3)
    return _finish(m, nt, bsdf, col, 0.85, rough)


def flat(name, color, rough=0.7, metal_=0.0, grime=0.15):
    m, nt, bsdf = node_mat(name)
    col = _mix(nt, _noise(nt, 30.0, 4, 0.5, _coords(nt)), tuple(color), tuple(c * 0.85 for c in color))
    if grime > 0:
        col = weathered(nt, col, grime, 0.1, 0.0, 0.0, scale=0.1)
    return _finish(m, nt, bsdf, col, metal_, rough)


if F22:
    SKIN = skin("skin", (0.165, 0.175, 0.19), (0.105, 0.112, 0.122), sheen=0.3)
    SKIN_DARK = skin("skin_dark", (0.1, 0.105, 0.115), (0.08, 0.085, 0.09), sheen=0.3)
    GLASS_COL = (0.32, 0.25, 0.08)
else:
    SKIN = skin("skin", (0.095, 0.1, 0.108), (0.08, 0.084, 0.09), sheen=0.12, edge_light=True)
    SKIN_DARK = skin("skin_dark", (0.07, 0.074, 0.08), (0.06, 0.063, 0.068), sheen=0.12)
    GLASS_COL = (0.1, 0.11, 0.1)
EDGE = flat("edge", (0.11, 0.115, 0.12), 0.5, 0.1)
INTAKE = flat("intake", (0.05, 0.05, 0.055), 0.8)
INTAKE_IN = flat("intake_in", (0.55, 0.56, 0.57), 0.6)
NOZZLE = metal("nozzle", (0.32, 0.3, 0.29), 0.45, burnt=0.8)
NOZZLE_IN = metal("nozzle_in", (0.16, 0.14, 0.13), 0.6, burnt=1.0)
GEAR = flat("gear", (0.62, 0.63, 0.64), 0.4, 0.3)
CHROME = metal("chrome", (0.75, 0.76, 0.78), 0.15)
TYRE = rubber("tyre", (0.03, 0.03, 0.032))
WELL = flat("well", (0.5, 0.52, 0.53), 0.65, grime=0.4)
CANOPY = glass("canopy", GLASS_COL)
FRAME = flat("frame", (0.09, 0.095, 0.1), 0.55)
PIT = flat("pit", (0.2, 0.21, 0.22), 0.75, grime=0.3)
PIT_DARK = flat("pit_dark", (0.06, 0.065, 0.07), 0.6, grime=0.2)
SCREEN = flat("screen", (0.01, 0.012, 0.014), 0.2, grime=0.0)
SEAT = flat("seat", (0.12, 0.13, 0.1), 0.85, grime=0.3)
CUSHION = flat("cushion", (0.18, 0.17, 0.12), 0.9, grime=0.2)
HANDLE = flat("handle", (0.7, 0.55, 0.06), 0.5)
MISSILE = flat("missile", (0.78, 0.79, 0.8), 0.45, grime=0.1)
LIGHT_RED = flat("light_red", (0.6, 0.05, 0.04), 0.3, grime=0.0)
LIGHT_GREEN = flat("light_green", (0.05, 0.5, 0.1), 0.3, grime=0.0)


# ================================================================== geometry helpers


def mesh(name, verts, faces, mat, part, smooth=32.0, bevel=0.0):
    bm = bmesh.new()
    vs = [bm.verts.new(Vector(v)) for v in verts]
    for f in faces:
        try:
            bm.faces.new([vs[i] for i in f])
        except ValueError:
            pass
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel, smooth=smooth)


def mirror_x(pts):
    return [(-x, y, z) for (x, y, z) in pts]


def catmull(p0, p1, p2, p3, t):
    t2 = t * t
    t3 = t2 * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def resample(stations, step):
    """Stations (y, params...) nose first: smooth in between (Catmull-Rom per parameter), about every `step` m."""
    out = []
    n = len(stations)
    for i in range(n - 1):
        a, b = stations[i], stations[i + 1]
        p0 = stations[max(0, i - 1)]
        p3 = stations[min(n - 1, i + 2)]
        k = max(1, int(round(abs(a[0] - b[0]) / step)))
        if abs(a[0] - b[0]) < 0.2:
            # a step (the intake lips): straight across, no overshoot
            out.append(a)
            continue
        for j in range(k):
            t = j / k
            out.append(tuple(catmull(p0[c], a[c], b[c], p3[c], t) for c in range(len(a))))
    out.append(stations[-1])
    return out


def profile(wb, zb, wc, zc, wt, zt, crown=0.04, bulge=0.04):
    """Half a fuselage section, bottom centre to top centre (x, z): the flat belly, the lower flank out to the
    chine (a crisp edge), the upper flank in to the shoulder, the gently crowned top."""
    pts = [(0.0, zb), (wb * 0.55, zb + 0.005), (wb, zb + 0.02)]

    def flank(a, b, k):
        mx, mz = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(mx, mz) or 1.0
        nx, nz = mz / ln, -mx / ln  # outward for the right side going up
        for i in range(1, k):
            t = i / k
            s = math.sin(t * math.pi) * bulge
            pts.append((a[0] + mx * t + nx * s, a[1] + mz * t + nz * s))

    flank((wb, zb + 0.02), (wc, zc), 3)
    pts.append((wc, zc))
    flank((wc, zc), (wt, zt), 3)
    pts.append((wt, zt))
    pts.append((wt * 0.5, zt + crown * 0.7))
    pts.append((0.0, zt + crown))
    return pts


def loft(name, sections, mat, part, open_top=None, cap_front=True, cap_back=True, smooth=32.0, mouths=None):
    """A body through sections [(y, [(x, z) half profile, bottom centre .. top centre])], mirrored to the left.
    open_top: (y0, y1, k) - leave the top between the k-th profile points open there (the cockpit)."""
    verts, faces, rings = [], [], []
    for y, half in sections:
        ring = [(x, y, z) for x, z in half]
        left = [(-x, y, z) for x, z in reversed(half[1:-1])]
        idx = len(verts)
        verts.extend(ring + left)
        rings.append((idx, len(ring) + len(left), y))
    m = len(sections[0][1])
    for (a, n, ya), (b, _, yb) in zip(rings, rings[1:]):
        for i in range(n):
            j = (i + 1) % n
            if open_top:
                y0, y1, k = open_top
                # profile indices k .. (m-1) are the top on the right; mirrored on the left
                in_top = (k <= i <= 2 * m - 2 - k) and (k <= j <= 2 * m - 2 - k) and i != j
                if in_top and min(ya, yb) >= y0 - 1e-6 and max(ya, yb) <= y1 + 1e-6:
                    continue
            faces.append((a + i, a + j, b + j, b + i))
    if cap_front:
        c = len(verts)
        y = sections[0][0]
        verts.append((0.0, y + 0.0, sum(z for _, z in sections[0][1]) / m))
        a, n, _ = rings[0]
        for i in range(n):
            faces.append((a + (i + 1) % n, a + i, c))
    if cap_back:
        c = len(verts)
        y = sections[-1][0]
        verts.append((0.0, y, sum(z for _, z in sections[-1][1]) / m))
        a, n, _ = rings[-1]
        for i in range(n):
            faces.append((a + i, a + (i + 1) % n, c))
    ob = mesh(name, verts, faces, mat, part, smooth=smooth)
    if mouths:
        # the intake mouths: the forward-facing step where the body widens behind them, below the chine - dark
        y0, y1, zmax = mouths
        ob.data.materials.append(INTAKE)
        for poly in ob.data.polygons:
            c = poly.center
            if y0 <= c.y <= y1 and abs(poly.normal.y) > 0.55 and c.z < zmax and abs(c.x) > 0.3:
                poly.material_index = 1
    return ob


def airfoil(chord, thick, n=7):
    """Upper and lower surface points of a thin, sharp-edged section (x along the chord 0..1 from the leading edge)."""
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1 - math.cos(t * math.pi)) / 2
        h = thick * chord * (1.25 * math.sqrt(max(x, 0.0)) * (1 - x) ** 1.1)
        pts.append((x, h))
    return pts


def surface_wing(name, root, tip, mat, part, thick_root, thick_tip, n=7, dihedral_axis="z", cant=0.0, cut=None):
    """A lifting surface from a root chord to a tip chord, each ((x, y_le, z), (x, y_te, z)) in model space, sharp
    leading and trailing edges. cut=(f0, f1): only the chord from fraction f0 to f1 (for control surfaces)."""
    (rx, rle, rz), (_, rte, _) = root
    (tx, tle, tz), (_, tte, _) = tip
    f0, f1 = cut or (0.0, 1.0)
    rows = []
    for (cx, le, te, cz, th) in ((rx, rle, rte, rz, thick_root), (tx, tle, tte, tz, thick_tip)):
        ch = le - te
        pts = airfoil(1.0, th, n)
        sel = [p for p in pts if f0 - 1e-6 <= p[0] <= f1 + 1e-6]
        if sel[0][0] > f0 + 1e-6:
            sel.insert(0, (f0, None))
        if sel[-1][0] < f1 - 1e-6:
            sel.append((f1, None))
        fixed = []
        for x, h in sel:
            if h is None:
                h = th * (1.25 * math.sqrt(max(x, 0.0)) * (1 - x) ** 1.1)
            fixed.append((x, h * ch))
        up = [(le - x * ch, h) for x, h in fixed]
        lo = [(le - x * ch, -h) for x, h in reversed(fixed)]
        rows.append([(cx, y, cz + hh) for y, hh in up] + [(cx, y, cz + hh) for y, hh in lo])
    verts = rows[0] + rows[1]
    k = len(rows[0])
    faces = [(i, (i + 1) % k, k + (i + 1) % k, k + i) for i in range(k)]
    faces.append(tuple(range(k))[::-1])
    faces.append(tuple(range(k, 2 * k)))
    ob = mesh(name, verts, faces, mat, part, smooth=25.0)
    if cant:
        # canted about the root chord line (tails lean outward)
        piv = Vector((rx, (rle + rte) / 2, rz))
        rot = Matrix.Rotation(cant, 4, "Y")
        ob.data.transform(Matrix.Translation(piv) @ rot @ Matrix.Translation(-piv))
    return ob


def tube(name, a, b, r, mat, part, k=12, r1=None):
    """A cylinder in model space."""
    return cyl(name, blk(*a), blk(*b), r, mat, part, k=k, r1=r1)


def boxm(name, x0, y0, z0, x1, y1, z1, mat, part, bevel=0.01):
    """A box in model space."""
    a = blk(x0, y0, z0)
    b = blk(x1, y1, z1)
    return box(name, min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2]), max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2]), mat, part, bevel=bevel)


def wheel(name, centre, r, w, part, axis="x"):
    cx, cy, cz = centre
    tube(name + "_t", (cx - w / 2, cy, cz), (cx + w / 2, cy, cz), r, TYRE, part, k=20)
    tube(name + "_h", (cx - w / 2 - 0.005, cy, cz), (cx + w / 2 + 0.005, cy, cz), r * 0.55, GEAR, part, k=14)


# ================================================================== the airframes

PIVOTS = {}
AXES = {}  # the hinge axis of each moving part (model space, unit)


def axis(name, a, b):
    v = Vector(b) - Vector(a)
    v.normalize()
    AXES[name] = (v.x, v.y, v.z)

if F22:
    LENGTH = 18.9
    EYE_Y, EYE_Z = 5.3, 0.95
    # fuselage: (y, wb, zb, wc, zc, wt, zt, crown, bulge)
    FUS = [
        (9.45, 0.004, -0.004, 0.006, 0.0, 0.004, 0.004, 0.0, 0.0),
        (8.9, 0.07, -0.14, 0.2, -0.01, 0.06, 0.15, 0.015, 0.01),
        (8.0, 0.13, -0.33, 0.42, -0.02, 0.12, 0.34, 0.03, 0.02),
        (6.9, 0.2, -0.52, 0.62, -0.03, 0.22, 0.54, 0.04, 0.03),
        (5.7, 0.25, -0.66, 0.77, -0.04, 0.33, 0.68, 0.04, 0.03),
        (4.5, 0.3, -0.74, 0.88, -0.05, 0.38, 0.75, 0.04, 0.03),
        (3.62, 0.34, -0.77, 0.94, -0.04, 0.42, 0.78, 0.04, 0.03),
        (3.52, 0.6, -0.8, 1.36, -0.02, 0.45, 0.79, 0.05, 0.03),
        (2.0, 0.85, -0.84, 1.66, 0.0, 0.62, 0.83, 0.05, 0.03),
        (0.0, 1.05, -0.85, 1.8, 0.0, 0.88, 0.84, 0.06, 0.03),
        (-3.0, 1.12, -0.82, 1.8, 0.0, 1.0, 0.82, 0.06, 0.03),
        (-5.5, 1.1, -0.74, 1.72, 0.0, 1.06, 0.74, 0.05, 0.03),
        (-7.2, 0.98, -0.6, 1.45, 0.0, 0.98, 0.58, 0.03, 0.02),
        (-8.4, 0.85, -0.44, 1.2, 0.0, 0.86, 0.44, 0.02, 0.02),
    ]
    COCKPIT = (2.7, 7.0)
    MOUTHS = (3.45, 3.7, 0.0)
    # canopy: (y, half width at the sill, height above the centreline)
    CAN = [(7.45, 0.02, 0.5), (7.0, 0.2, 0.75), (6.2, 0.33, 1.05), (5.2, 0.39, 1.2), (4.2, 0.4, 1.17), (3.3, 0.4, 1.06), (2.65, 0.36, 0.92)]
    WING_ROOT = ((1.55, 2.0, 0.0), (1.55, -6.2, 0.0))
    WING_TIP = ((6.78, -2.63, -0.12), (6.78, -4.65, -0.12))
    WING_T = (0.045, 0.035)
    STAB_ROOT = ((1.35, -5.9, 0.02), (1.35, -9.0, 0.02))
    STAB_TIP = ((4.45, -8.15, -0.02), (4.45, -9.25, -0.02))
    STAB_PIVOT_Y = -7.45
    TAIL_ROOT = ((1.45, -4.55, 0.62), (1.45, -8.05, 0.62))
    TAIL_TIP = ((1.45, -7.15, 3.25), (1.45, -8.55, 3.25))
    TAIL_CANT = math.radians(28)
    NOSE_GEAR = (0.0, 5.75, -0.66)
    MAIN_GEAR = (1.5, -1.7, -0.78)
    BAY = (-0.4, -4.3)
else:
    LENGTH = 15.7
    EYE_Y, EYE_Z = 4.3, 0.95
    FUS = [
        (7.85, 0.004, -0.004, 0.006, 0.0, 0.004, 0.004, 0.0, 0.0),
        (7.3, 0.08, -0.18, 0.22, -0.02, 0.08, 0.17, 0.02, 0.015),
        (6.4, 0.18, -0.45, 0.48, -0.04, 0.16, 0.42, 0.03, 0.03),
        (5.3, 0.28, -0.72, 0.68, -0.06, 0.26, 0.66, 0.05, 0.04),
        (4.3, 0.36, -0.88, 0.8, -0.08, 0.36, 0.76, 0.05, 0.04),
        (3.32, 0.44, -0.95, 0.9, -0.07, 0.42, 0.84, 0.06, 0.05),
        (3.22, 0.66, -1.0, 1.3, -0.05, 0.45, 0.86, 0.06, 0.05),
        (1.8, 0.95, -1.06, 1.72, -0.02, 0.62, 0.94, 0.07, 0.05),
        (0.0, 1.18, -1.06, 1.9, 0.0, 0.9, 0.96, 0.08, 0.05),
        (-2.6, 1.22, -1.0, 1.85, 0.0, 1.05, 0.92, 0.08, 0.05),
        (-4.6, 1.05, -0.88, 1.6, 0.0, 0.98, 0.82, 0.07, 0.04),
        (-6.0, 0.72, -0.68, 1.12, 0.0, 0.68, 0.64, 0.05, 0.04),
        (-6.75, 0.58, -0.58, 0.78, 0.0, 0.56, 0.56, 0.04, 0.03),
    ]
    COCKPIT = (1.75, 5.95)
    MOUTHS = (3.15, 3.4, 0.0)
    CAN = [(6.35, 0.02, 0.5), (5.9, 0.22, 0.83), (5.1, 0.37, 1.15), (4.3, 0.42, 1.27), (3.4, 0.43, 1.25), (2.5, 0.42, 1.18), (1.7, 0.34, 1.03)]
    WING_ROOT = ((1.65, 0.9, 0.05), (1.65, -4.9, 0.05))
    WING_TIP = ((5.35, -1.63, -0.05), (5.35, -3.97, -0.05))
    WING_T = (0.05, 0.04)
    STAB_ROOT = ((1.0, -4.95, 0.05), (1.0, -7.45, 0.05))
    STAB_TIP = ((3.45, -6.65, 0.02), (3.45, -7.65, 0.02))
    STAB_PIVOT_Y = -6.2
    TAIL_ROOT = ((1.02, -3.65, 0.78), (1.02, -6.35, 0.78))
    TAIL_TIP = ((1.02, -5.6, 2.95), (1.02, -6.75, 2.95))
    TAIL_CANT = math.radians(20)
    NOSE_GEAR = (0.0, 5.0, -0.85)
    MAIN_GEAR = (1.45, -1.25, -0.9)
    BAY = (0.7, -2.8)

HINGE = 0.78  # control surfaces from this fraction of the chord aft


def fuselage():
    secs = []
    open_k = 8  # profile points from here up are the top (the cockpit is open there)
    for st in resample(FUS, 0.3):
        y, wb, zb, wc, zc, wt, zt, crown, bulge = st
        secs.append((y, profile(wb, zb, wc, zc, wt, zt, crown, bulge)))
    loft("fuselage", secs, SKIN, "body", open_top=(COCKPIT[0], COCKPIT[1], open_k), mouths=MOUTHS)


def canopy():
    # the glazing: a bubble over the cockpit opening (half ellipses across), and the sill frame round it
    secs = []
    for st in resample([(y, w, h) for y, w, h in CAN], 0.2):
        y, w, h = st
        w = max(0.01, min(w, fus_shoulder(y)))
        base = fus_top(y)
        half = []
        for i in range(9):
            a = i / 8 * math.pi / 2
            half.append((w * math.cos(a), base + (h - base) * math.sin(a)))
        half = [(0.0, base)] + half if False else half
        secs.append((y, half))
    verts, faces, rings = [], [], []
    for y, half in secs:
        ring = [(x, y, z) for x, z in half] + [(-x, y, z) for x, z in reversed(half[:-1])]
        rings.append(len(verts))
        verts.extend(ring)
    n = len(secs[0][1]) * 2 - 1
    for a, b in zip(rings, rings[1:]):
        for i in range(n - 1):
            faces.append((a + i, a + i + 1, b + i + 1, b + i))
    mesh("canopy", verts, faces, CANOPY, "glass", smooth=60.0)
    # the sill: a dark band along the canopy's foot, and (F-35) the bow frame behind the front piece
    for s in (1, -1):
        pts = [(s * max(0.01, min(w, fus_shoulder(y))), y, fus_top(y)) for y, w, _ in resample(CAN, 0.2)]
        verts, faces = [], []
        for x, y, z in pts:
            verts += [(x - s * 0.012, y, z + 0.045), (x + s * 0.03, y, z - 0.008), (x + s * 0.03, y, z + 0.02)]
        for i in range(len(pts) - 1):
            a, b = i * 3, (i + 1) * 3
            faces += [(a, a + 2, b + 2, b), (a + 2, a + 1, b + 1, b + 2)]
        mesh("sill", verts, faces, FRAME, "canopy_frame")
    if not F22:
        # the bow: an arch across the canopy behind the front windscreen
        y = 5.0
        w, h = 0.38, 1.16
        prev = None
        for i in range(13):
            a = i / 12 * math.pi
            p = (w * math.cos(a) * 1.03, y, fus_top(y) + (h - fus_top(y)) * math.sin(a) + 0.02)
            if prev:
                tube("bow", prev, p, 0.03, FRAME, "canopy_frame", k=8)
            prev = p
    # the canopy's rear edge on the spine
    yb = CAN[-1][0]
    boxm("rear", -CAN[-1][1], yb - 0.06, fus_top(yb) - 0.02, CAN[-1][1], yb + 0.02, fus_top(yb) + 0.12, FRAME, "canopy_frame")


_DENSE = []


def fus_param(y, idx):
    if not _DENSE:
        _DENSE.extend(resample(FUS, 0.05))
    best = min(_DENSE, key=lambda s: abs(s[0] - y))
    return best[idx]


def fus_shoulder(y):
    return fus_param(y, 5)


def fus_top(y):
    """Height of the fuselage's upper shoulder (the canopy sill) at y."""
    return fus_param(y, 6)


def fus_chine(y):
    return fus_param(y, 3)


def intakes():
    if F22:
        return
        for s in (1, -1):
            y0, y1 = 3.75, 3.2
            ox, ix = 1.42, 0.82
            top, bot = -0.05, -0.8
            outer = [(s * ix, y0, top), (s * ox, y0 - 0.25, top), (s * (ox - 0.12), y1, bot), (s * ix, y1 + 0.2, bot)]
            # the lip: a thin frame
            for a, b in zip(outer, outer[1:] + outer[:1]):
                tube("lip", a, b, 0.025, EDGE, "body", k=6)
            # the mouth, dark, a little inside
            inner = [(x - s * 0.02, y - 0.25, z) for x, y, z in outer]
            mesh("mouth", outer + inner, [(0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7), (4, 5, 6, 7)], INTAKE, "body")
            # the fairing joining the mouth to the body
            fair = [(s * ix, y0, top), (s * ox, y0 - 0.25, top), (s * (ox - 0.12), y1, bot), (s * ix, y1 + 0.2, bot)]
            back = [(s * 0.7, 2.2, 0.0), (s * 1.62, 2.0, 0.0), (s * 1.5, 2.0, -0.82), (s * 0.85, 2.0, -0.84)]
            mesh("duct", fair + back, [(0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)], SKIN, "body")
    else:
        # DSI: a smooth bump on the fuselage side ahead of each mouth
        for s in (1, -1):
            lathe("bump", blk(s * 0.86, 3.75, -0.4), (1, 0, 0) if s > 0 else (-1, 0, 0),
                  [(0.0, 0.34), (0.06, 0.26), (0.11, 0.0)], SKIN, "body", k=16)
        # the EOTS window under the nose: faceted sapphire
        lathe("eots", blk(0, 6.3, -0.55), (0, -1, 0), [(0.0, 0.18), (0.08, 0.16), (0.14, 0.0)], CANOPY, "body", k=7)
        # the gun fairing on the left shoulder
        tube("gun", (-0.62, 1.5, 0.7), (-0.62, 3.6, 0.62), 0.12, SKIN, "body", k=10, r1=0.05)


def wings():
    (rx, rle, rz), (_, rte, _) = WING_ROOT
    (tx, tle, tz), (_, tte, _) = WING_TIP
    t0, t1 = WING_T
    for s, side in ((1, "r"), (-1, "l")):
        root = ((s * rx, rle, rz), (s * rx, rte, rz))
        tip = ((s * tx, tle, tz), (s * tx, tte, tz))
        surface_wing("wing", root, tip, SKIN, "body", t0, t1, cut=(0.0, HINGE))
        # inboard flap (root to mid span) and outboard flaperon (mid span to near the tip): split the trailing part
        mid = 0.48
        mx = rx + (tx - rx) * mid
        mle = rle + (tle - rle) * mid
        mte = rte + (tte - rte) * mid
        mz = rz + (tz - rz) * mid
        o = 0.93
        ox = rx + (tx - rx) * o
        ole = rle + (tle - rle) * o
        ote = rte + (tte - rte) * o
        oz = rz + (tz - rz) * o
        hz = (rz + mz) / 2
        surface_wing("flap", ((s * rx, rle, rz), (s * rx, rte, rz)), ((s * mx, mle, mz), (s * mx, mte, mz)), SKIN, "flap_" + side, t0,
                     t0 + (t1 - t0) * mid, cut=(HINGE + 0.005, 1.0))
        surface_wing("flaperon", ((s * mx, mle, mz), (s * mx, mte, mz)), ((s * ox, ole, oz), (s * ox, ote, oz)), SKIN, "aileron_" + side,
                     t0 + (t1 - t0) * mid, t1, cut=(HINGE + 0.005, 1.0))
        # the outermost trailing piece stays fixed
        surface_wing("tipte", ((s * ox, ole, oz), (s * ox, ote, oz)), tip, SKIN, "body", t1, t1, cut=(HINGE, 1.0))
        # pivots: on the hinge line, at mid span of each surface
        hy_f = rle - (rle - rte) * HINGE
        PIVOTS["flap_" + side] = (s * (rx + mx) / 2, (hy_f + (mle - (mle - mte) * HINGE)) / 2, hz)
        PIVOTS["aileron_" + side] = (s * (mx + ox) / 2, ((mle - (mle - mte) * HINGE) + (ole - (ole - ote) * HINGE)) / 2, (mz + oz) / 2)
        h_root = (s * rx, rle - (rle - rte) * HINGE, rz)
        h_mid = (s * mx, mle - (mle - mte) * HINGE, mz)
        h_out = (s * ox, ole - (ole - ote) * HINGE, oz)
        # axes pointing outboard: a positive turn about them (right-hand) puts the trailing edge down on the right
        axis("flap_" + side, h_root, h_mid)
        axis("aileron_" + side, h_mid, h_out)
        # nav light on the tip
        boxm("nav", s * tx - 0.04, tle - 0.6, tz - 0.03, s * tx + 0.04, tle - 0.3, tz + 0.03, LIGHT_GREEN if s > 0 else LIGHT_RED, "body")


def stabs():
    (rx, rle, rz), (_, rte, _) = STAB_ROOT
    (tx, tle, tz), (_, tte, _) = STAB_TIP
    for s, side in ((1, "r"), (-1, "l")):
        surface_wing("stab", ((s * rx, rle, rz), (s * rx, rte, rz)), ((s * tx, tle, tz), (s * tx, tte, tz)), SKIN, "stab_" + side, 0.04, 0.03)
        PIVOTS["stab_" + side] = (s * (rx + 0.3), STAB_PIVOT_Y, rz)
        AXES["stab_" + side] = (1.0, 0.0, 0.0)


def tails():
    (rx, rle, rz), (_, rte, _) = TAIL_ROOT
    (tx, tle, tz), (_, tte, _) = TAIL_TIP
    for s, side in ((1, "r"), (-1, "l")):
        # a vertical surface: root and tip at x = rx, the section in y-x; built as a "wing" lying in the y-z plane
        def vert(name, part, f0, f1):
            rows = []
            for (le, te, z, th) in ((rle, rte, rz, 0.05), (tle, tte, tz, 0.035)):
                ch = le - te
                pts = airfoil(1.0, th, 7)
                sel = [(x, h) for x, h in pts if f0 - 1e-6 <= x <= f1 + 1e-6]
                if sel[0][0] > f0 + 1e-6:
                    sel.insert(0, (f0, th * (1.25 * math.sqrt(f0) * (1 - f0) ** 1.1)))
                if sel[-1][0] < f1 - 1e-6:
                    sel.append((f1, th * (1.25 * math.sqrt(f1) * (1 - f1) ** 1.1)))
                up = [(s * rx + h * ch, le - x * ch, z) for x, h in sel]
                lo = [(s * rx - h * ch, le - x * ch, z) for x, h in reversed(sel)]
                rows.append(up + lo)
            verts = rows[0] + rows[1]
            k = len(rows[0])
            faces = [(i, (i + 1) % k, k + (i + 1) % k, k + i) for i in range(k)]
            faces.append(tuple(range(k, 2 * k)))
            ob = mesh(name, verts, faces, SKIN, part, smooth=25.0)
            piv = Vector((s * rx, (rle + rte) / 2, rz))
            ob.data.transform(Matrix.Translation(piv) @ Matrix.Rotation(s * TAIL_CANT, 4, "Y") @ Matrix.Translation(-piv))
            return ob
        vert("fin", "body", 0.0, 0.72)
        vert("rudder", "rudder_" + side, 0.725, 1.0)
        # the rudder's pivot: on its hinge line, half way up, canted with the fin
        hz = (rz + tz) / 2
        hy = ((rle - (rle - rte) * 0.72) + (tle - (tle - tte) * 0.72)) / 2
        dz = hz - rz
        PIVOTS["rudder_" + side] = (s * (rx + math.sin(TAIL_CANT) * dz), hy, rz + math.cos(TAIL_CANT) * dz)
        hb = (s * rx, rle - (rle - rte) * 0.72, rz)
        dt = tz - rz
        ht = (s * (rx + math.sin(TAIL_CANT) * dt), tle - (tle - tte) * 0.72, rz + math.cos(TAIL_CANT) * dt)
        axis("rudder_" + side, hb, ht)
        # strobe at the fin tip
        tip = (s * (rx + math.sin(TAIL_CANT) * (tz - rz)), tte + 0.1, rz + math.cos(TAIL_CANT) * (tz - rz))
        boxm("strobe", tip[0] - 0.03, tip[1] - 0.05, tip[2] - 0.05, tip[0] + 0.03, tip[1] + 0.05, tip[2] + 0.02, LIGHT_RED, "body")


def nozzles():
    if F22:
        for s, side in ((1, "r"), (-1, "l")):
            cx = s * 0.78
            y0, y1 = -7.9, -9.0
            w, h = 0.56, 0.42
            # the housing: a rectangular sleeve from the round engine to the 2D nozzle
            boxm("housing", cx - w, y1 + 0.35, -h, cx + w, y0 + 0.4, h, NOZZLE, "body", bevel=0.04)
            boxm("throat", cx - w + 0.04, y1 + 0.3, -h + 0.04, cx + w - 0.04, y1 + 0.36, h - 0.04, NOZZLE_IN, "body", bevel=0.0)
            # sidewalls past the flaps
            for sx in (-1, 1):
                boxm("sidewall", cx + sx * w - 0.03, y1 - 0.25, -h + 0.02, cx + sx * w + 0.03, y1 + 0.4, h - 0.02, NOZZLE, "body", bevel=0.01)
            # upper and lower flaps, each about its hinge at the throat
            for part, z, sz in (("nozzle_" + side[0] + "u", h - 0.03, 1), ("nozzle_" + side[0] + "d", -h + 0.03, -1)):
                boxm("flap", cx - w + 0.04, y1 - 0.25, z - 0.03, cx + w - 0.04, y1 + 0.38, z + 0.03, NOZZLE, part, bevel=0.01)
                PIVOTS[part] = (cx, y1 + 0.35, z)
                AXES[part] = (1.0, 0.0, 0.0)
    else:
        cy = -6.6
        lathe("can", blk(0, cy + 0.6, 0.0), (0, 0, 1), [(0.0, 0.66), (0.4, 0.62), (0.62, 0.0)], NOZZLE, "body", k=24, caps=False)
        # serrated petals (the nozzle part, scaled open by the renderer)
        k = 24
        verts, faces = [], []
        for i in range(k):
            a0 = i / k * 2 * math.pi
            a1 = (i + 1) / k * 2 * math.pi
            am = (a0 + a1) / 2
            for (a, r, y) in ((a0, 0.6, cy), (a1, 0.6, cy), (am, 0.5, cy - 0.65)):
                verts.append((r * math.cos(a), y, r * math.sin(a)))
            base = i * 3
            faces.append((base, base + 1, base + 2))
        mesh("petals", verts, faces, NOZZLE, "nozzle", smooth=10)
        tube("petal_in", (0, cy + 0.05, 0), (0, cy - 0.5, 0), 0.5, NOZZLE_IN, "nozzle", k=24, r1=0.42)
        PIVOTS["nozzle"] = (0.0, cy, 0.0)
        AXES["nozzle"] = (0.0, 1.0, 0.0)


def gear():
    # nose gear: a leg from the bay to the wheel, the wheel's bottom on the ground; it folds forward
    nx, ny, nz = NOSE_GEAR
    wr = 0.3 if F22 else 0.28
    wy = ny + 0.15
    tube("strut", (nx, ny, nz), (nx, wy, GROUND + wr), 0.07, GEAR, "nose_gear", k=10)
    tube("oleo", (nx, wy - 0.02, GROUND + wr + 0.65), (nx, wy, GROUND + wr + 0.05), 0.055, CHROME, "nose_gear", k=10)
    tube("drag", (nx, ny - 0.5, nz + 0.05), (nx, wy - 0.05, GROUND + wr + 0.8), 0.035, GEAR, "nose_gear", k=8)
    wheel("nosewheel", (nx, wy, GROUND + wr), wr, 0.2, "nose_gear")
    boxm("taxi", -0.06, wy + 0.05, GROUND + wr + 0.9, 0.06, wy + 0.15, GROUND + wr + 1.0, CHROME, "nose_gear")
    PIVOTS["nose_gear"] = (nx, ny, nz)
    AXES["nose_gear"] = (1.0, 0.0, 0.0)
    # its doors, hinged along the bay's sides
    for s, side in ((1, "r"), (-1, "l")):
        boxm("ndoor", s * 0.02, ny - 0.75, nz - 0.03, s * 0.24, ny + 0.65, nz + 0.0, SKIN, "door_nose_" + side, bevel=0.005)
        PIVOTS["door_nose_" + side] = (s * 0.24, ny, nz)
        AXES["door_nose_" + side] = (0.0, 1.0, 0.0)
    # main gear: legs folding inward into the fuselage
    mx, my, mz = MAIN_GEAR
    mr = 0.46 if F22 else 0.42
    for s, side in ((1, "r"), (-1, "l")):
        wx = s * (mx + 0.12)
        tube("mstrut", (s * mx, my, mz), (wx, my, GROUND + mr + 0.1), 0.1, GEAR, "main_" + side, k=12)
        tube("moleo", (wx, my, GROUND + mr + 0.75), (wx, my, GROUND + mr), 0.075, CHROME, "main_" + side, k=12)
        tube("brace", (s * (mx - 0.6), my + 0.1, mz + 0.05), (wx, my, GROUND + mr + 0.9), 0.045, GEAR, "main_" + side, k=8)
        wheel("mainwheel", (wx + s * 0.12, my, GROUND + mr), mr, 0.32, "main_" + side)
        PIVOTS["main_" + side] = (s * mx, my, mz)
        AXES["main_" + side] = (0.0, 1.0, 0.0)
        # its door: hinged at the outer edge, covering the well when up
        boxm("mdoor", s * (mx - 1.05), my - 0.75, mz - 0.03, s * (mx + 0.05), my + 0.75, mz, SKIN, "door_main_" + side, bevel=0.005)
        PIVOTS["door_main_" + side] = (s * (mx + 0.05), my, mz)
        AXES["door_main_" + side] = (0.0, 1.0, 0.0)
        # the well: a plate just under the skin, drawn while the doors are open
        zb = fus_param(my, 2)
        boxm("well", s * (mx - 1.0), my - 0.7, zb - 0.012, s * mx, my + 0.7, zb - 0.006, WELL, "wells", bevel=0.0)
    zb = fus_param(ny, 2)
    boxm("nwell", -0.22, ny - 0.7, zb - 0.012, 0.22, ny + 0.6, zb - 0.006, WELL, "wells", bevel=0.0)


def bays():
    y0, y1 = BAY
    zb = fus_param((y0 + y1) / 2, 2)
    wb = fus_param((y0 + y1) / 2, 1)
    for s, side in ((1, "r"), (-1, "l")):
        boxm("baydoor", s * 0.02, y1, zb - 0.025, s * (wb - 0.05), y0, zb + 0.005, SKIN, "bay_" + side, bevel=0.005)
        PIVOTS["bay_" + side] = (s * (wb - 0.05), (y0 + y1) / 2, zb)
        AXES["bay_" + side] = (0.0, 1.0, 0.0)
    # open: the dark bay, and the AMRAAMs let down on their trapeze launchers
    boxm("bay_box", -wb + 0.05, y1 + 0.05, zb - 0.014, wb - 0.05, y0 - 0.05, zb - 0.008, INTAKE, "bay", bevel=0.0)
    zb = zb - 0.3
    n = 3 if F22 else 2
    for i in range(n):
        x = (i - (n - 1) / 2) * (2 * (wb - 0.2) / max(1, n))
        tube("amraam", (x, y0 - 0.15, zb + 0.22), (x, y1 + 0.2, zb + 0.22), 0.09, MISSILE, "bay", k=10)
        tube("seeker", (x, y0 - 0.15, zb + 0.22), (x, y0 + 0.1, zb + 0.22), 0.09, MISSILE, "bay", k=10, r1=0.01)
        for fy in (y1 + 0.35, y0 - 1.0):
            boxm("fins", x - 0.2, fy - 0.12, zb + 0.21, x + 0.2, fy + 0.12, zb + 0.23, MISSILE, "bay", bevel=0.0)


def cockpit():
    """The cockpit around the pilot's eye (0, EYE_Y, EYE_Z): tub, consoles, the panel (its face at EYE_Y + 0.8,
    CockpitDisplays draws the screens on it), glare shield and HUD, the seat; the stick and throttle apart."""
    ey, ez = EYE_Y, EYE_Z
    panel_y = ey + 0.8
    top = 0.6 if F22 else 0.64
    w = 0.42
    floor = -0.35
    # the tub: floor, sides, the rear bulkhead behind the seat
    boxm("floor", -w, ey - 0.8, floor - 0.04, w, panel_y + 0.3, floor, PIT_DARK, "pit", bevel=0.0)
    for s in (1, -1):
        boxm("side", s * w - 0.02, COCKPIT[0], floor, s * w + 0.02, COCKPIT[1] - 0.3, fus_top(ey) - 0.02, PIT, "pit", bevel=0.0)
        # side consoles at hip height, their dark tops with switch rows
        boxm("console", s * (w - 0.17), ey - 0.65, 0.18, s * w, panel_y - 0.05, 0.36, PIT, "pit")
        boxm("console_top", s * (w - 0.16), ey - 0.6, 0.36, s * (w - 0.01), panel_y - 0.1, 0.375, PIT_DARK, "pit", bevel=0.0)
        for i in range(6):
            yy = ey - 0.5 + i * 0.16
            for j in range(3):
                xx = s * (w - 0.14 + j * 0.045)
                tube("sw", (xx, yy, 0.375), (xx, yy, 0.40), 0.006, CHROME, "pit", k=5)
    boxm("bulkhead", -w, ey - 0.85, floor, w, ey - 0.75, fus_top(ey - 0.8) + 0.05, PIT, "pit", bevel=0.0)
    # the instrument panel: a face across the cockpit, the screens' bezels on it
    boxm("panel", -w + 0.02, panel_y, floor + 0.15, w - 0.02, panel_y + 0.12, top, PIT, "pit")
    if F22:
        for cx, cz, h in ((-0.25, top - 0.13, 0.09), (0.0, top - 0.16, 0.1), (0.25, top - 0.13, 0.09), (0.0, top - 0.33, 0.07)):
            boxm("bezel", cx - h - 0.02, panel_y - 0.012, cz - h - 0.02, cx + h + 0.02, panel_y, cz + h + 0.02, PIT_DARK, "pit", bevel=0.004)
            boxm("screen", cx - h, panel_y - 0.016, cz - h, cx + h, panel_y - 0.012, cz + h, SCREEN, "pit", bevel=0.0)
        # the up-front controls under the glare shield
        boxm("icp", -0.1, panel_y - 0.12, top - 0.04, 0.1, panel_y - 0.02, top + 0.02, PIT_DARK, "pit")
    else:
        boxm("bezel", -0.34, panel_y - 0.012, top - 0.32, 0.34, panel_y, top - 0.01, PIT_DARK, "pit", bevel=0.004)
        boxm("screen", -0.32, panel_y - 0.016, top - 0.3, 0.32, panel_y - 0.012, top - 0.03, SCREEN, "pit", bevel=0.0)
    # glare shield: a hood over the panel, the HUD's frame and combiner glass on it
    boxm("shield", -w + 0.03, panel_y - 0.12, top, w - 0.03, panel_y + 0.35, top + 0.06, PIT_DARK, "pit", bevel=0.02)
    hz = top + 0.06
    hy = panel_y - 0.05
    boxm("hud_base", -0.1, hy - 0.06, hz, 0.1, hy + 0.06, hz + 0.05, PIT_DARK, "pit")
    for sx in (-1, 1):
        tube("hud_post", (sx * 0.095, hy, hz + 0.05), (sx * 0.095, hy - 0.04, hz + 0.24), 0.008, PIT_DARK, "pit", k=6)
    tube("hud_top", (-0.095, hy - 0.04, hz + 0.24), (0.095, hy - 0.04, hz + 0.24), 0.008, PIT_DARK, "pit", k=6)
    # the ejection seat (ACES II / Martin-Baker US16E): bucket, cushions, the headbox, the yellow-black handle
    sy = ey - 0.3
    boxm("seat_pan", -0.24, sy - 0.15, -0.05, 0.24, sy + 0.38, 0.05, SEAT, "pit")
    boxm("cushion", -0.21, sy - 0.1, 0.05, 0.21, sy + 0.34, 0.11, CUSHION, "pit")
    boxm("back", -0.25, sy - 0.32, -0.05, 0.25, sy - 0.15, 0.85, SEAT, "pit")
    boxm("back_cush", -0.2, sy - 0.17, 0.12, 0.2, sy - 0.11, 0.78, CUSHION, "pit")
    boxm("headbox", -0.17, sy - 0.36, 0.85, 0.17, sy - 0.1, 1.2, SEAT, "pit")
    for s in (1, -1):
        boxm("rail", s * 0.24, sy - 0.33, -0.05, s * 0.29, sy - 0.15, 1.15, PIT_DARK, "pit")
    tube("handle", (-0.1, sy + 0.37, 0.07), (0.1, sy + 0.37, 0.07), 0.016, HANDLE, "pit", k=6)
    # the side-stick on the right console, the throttle on the left
    sx = w - 0.09
    tube("stick_base", (sx, ey + 0.05, 0.36), (sx, ey + 0.05, 0.41), 0.035, PIT_DARK, "stick", k=10)
    tube("stick_grip", (sx, ey + 0.05, 0.41), (sx, ey + 0.08, 0.55), 0.022, PIT_DARK, "stick", k=10)
    boxm("stick_top", sx - 0.02, ey + 0.06, 0.54, sx + 0.02, ey + 0.11, 0.58, PIT_DARK, "stick")
    boxm("trigger", sx - 0.006, ey + 0.1, 0.47, sx + 0.006, ey + 0.12, 0.5, CHROME, "stick")
    PIVOTS["stick"] = (sx, ey + 0.05, 0.38)
    AXES["stick"] = (1.0, 0.0, 0.0)
    tx = -(w - 0.09)
    boxm("thr_slot", tx - 0.015, ey - 0.25, 0.375, tx + 0.015, ey + 0.25, 0.38, PIT_DARK, "pit", bevel=0.0)
    tube("thr_arm", (tx, ey, 0.38), (tx, ey + 0.02, 0.47), 0.018, PIT_DARK, "throttle", k=8)
    boxm("thr_grip", tx - 0.04, ey - 0.03, 0.46, tx + 0.02, ey + 0.07, 0.53, PIT_DARK, "throttle")
    PIVOTS["throttle"] = (tx, ey, 0.38)
    AXES["throttle"] = (1.0, 0.0, 0.0)


def build():
    fuselage()
    canopy()
    intakes()
    wings()
    stabs()
    tails()
    nozzles()
    gear()
    bays()
    cockpit()
    # the spine: the airbrake-free top between the tails, and the dorsal hump of the F-35
    if not F22:
        tube("hump", (0.0, 1.5, 0.98), (0.0, -4.0, 0.88), 0.3, SKIN, "body", k=14, r1=0.25)


build()

ORDER = ["body", "glass", "canopy_frame", "pit", "stick", "throttle", "flap_l", "flap_r", "aileron_l", "aileron_r", "stab_l", "stab_r",
         "rudder_l", "rudder_r", "nose_gear", "main_l", "main_r", "door_nose_l", "door_nose_r", "door_main_l", "door_main_r", "bay_l", "bay_r",
         "bay", "wells"] + (["nozzle_lu", "nozzle_ld", "nozzle_ru", "nozzle_rd"] if F22 else ["nozzle"])

if __name__ == "__main__" or True:
    for p in ORDER:
        join_part(p)
    if "pivots" in ARGS:
        for k, v in sorted(PIVOTS.items()):
            print("PIVOT", k, "%.4f %.4f %.4f" % v)
    if "shots" in ARGS:
        outdoor(40, 210)
        for ob in scene.objects:
            if ob.type == "MESH" and ob.name not in ("ground",):
                ob.location.z += -GROUND
        name = MODEL
        shoot(name + "_front", (11, 6.5, -14), (0, 2.2, -1), lens=40)
        shoot(name + "_side", (-16, 4, 2), (0, 2.0, 0), lens=30)
        shoot(name + "_top", (0.01, 30, 0), (0, 0, 0), lens=35)
        shoot(name + "_rear", (-15, 5.5, 21), (0, 2.3, 0), lens=40)
        for ob in PARTS.get("glass", []):
            ob.hide_render = True
        shoot(name + "_pit", (0, 2.3 + EYE_Z + 0.05, -(EYE_Y - 0.1)), (0, 2.3 + 0.55, -(EYE_Y + 1.5)), lens=18)
    if "export" in ARGS:
        import json
        piv = {k: blk(*v) for k, v in PIVOTS.items()}
        export(MODEL, ORDER, pivots=piv, tex=int(ARGS.get("tex", "2048")), ao=0.25)
        # the rig for the game: every moving part's pivot and hinge axis (model space), the pilot's eye
        rig = {"pivots": {k: [round(c, 5) for c in v] for k, v in PIVOTS.items()},
               "axes": {k: [round(c, 5) for c in v] for k, v in AXES.items()},
               "eye": [0.0, EYE_Y, EYE_Z]}
        with open(os.path.join(ARGS.get("rig", OUT), MODEL + ".rig.json"), "w") as f:
            json.dump(rig, f, indent=1)
