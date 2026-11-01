"""The launch complex round the launch pad block (LaunchPadRenderer), built in Blender: two models, so the texture
atlas keeps its detail over a complex about 25 x 30 x 30 blocks.

launchpad (the pad and its tower):
  pad       apron with expansion joints and soot, the table's girders and deluge ring, the flame trench (refractory
            lined walls, curved steel deflector, spillway), the fixed service tower (I-beam columns, angle bracing,
            grated decks with railings and toe boards, switch-back stairs, elevator shaft and guide rails, machine
            house, hammerhead crane, lightning rod), the umbilical risers and the arms' hinge knuckles
  arm0..3   the umbilical swing arms, built extended, about their hinge (ARM_HINGE_X, 0, 0) (turned about y)
  clamp     one hold-down clamp at the (+x, +z) table corner, about its pivot (0.42, TABLE + 0.32, 0.42)
            (the renderer draws it four times, turned by quarter turns about the block's centre)
  elevator  the elevator car, floor at y = 0, about (TOWER_X, 0, 0)
launchpad_site (everything round it):
  site      lightning masts with catenary and guy wires, floodlight masts, the LOX and fuel spheres and their lines,
            the water tower and main, rainbirds, cameras, bunker hatch, equipment cabinet, cable trench covers,
            signs, bollards, hydrants

    python launchpad.py <out_dir> [--shots] [--export=pad|site]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from structkit import _coords, _cuboid, _finish, _math, _mix, _mixf, _noise, _ramp, node_mat, weathered  # noqa: E402
import bmesh  # noqa: E402
from mathutils import Vector  # noqa: E402

# ---------------------------------------------------------------- layout (LaunchPadRenderer)
TABLE = 6.0 / 16.0
TX = -3.2                      # TOWER_X
TOP = 22.0                     # TOWER_TOP
ARM_HEIGHTS = (4.6, 8.6, 12.6, 16.6)
PLATFORMS = (4.4, 8.4, 12.4, 16.4, 20.4, 22.0)
HINGE_X = TX + 0.65            # ARM_HINGE_X
ARM_TIP = -1.05
CLAMP_PIVOT = 0.42
CLAMP_Y = TABLE + 0.32
FLOODLIGHTS = ((-4.2, -4.2), (4.2, -4.2), (-4.2, 4.2), (4.2, 4.2))
FLOOD_H = 6.0
MAST_H = 30.0
MASTS = ((0.5, -10.0), (0.5, 10.0))
WATER = (-9.5, 8.5)
LOX = (-9.5, -8.5)
FUEL = (9.0, -9.0)
STATUS_Y = (2.2, 6.4, 10.4, 14.4)
# the tower: core (elevator shaft) and the outer frame, the +x face towards the pad
XF, XB, XO = TX + 0.55, TX - 0.55, TX - 1.75
ZC, ZO = 0.55, 1.15
Y0 = 0.3                       # footing top
DT = 0.06                      # deck grating
# where the site's lines meet the pad's
LOX_J = (-2.2, 0.5, -5.5)
FUEL_J = (-1.85, 0.3, -5.5)
WATER_J = (-1.6, 0.25, 5.5)

# ---------------------------------------------------------------- materials
M_CONC = concrete("concrete")
M_CONC_D = concrete("concrete_dark", (0.27, 0.265, 0.25), grime=0.75)
M_WHITE = paint("white_paint", (0.78, 0.79, 0.78), rough=0.45, grime=0.55, wear=0.25, streaks=0.6)
M_INSUL = paint("insulation", (0.74, 0.73, 0.69), rough=0.75, grime=0.6, wear=0.0, streaks=0.7)
M_GREY = paint("grey_paint", (0.5, 0.52, 0.52), rough=0.5, grime=0.55, wear=0.3, streaks=0.6, rust=0.15)
M_STEEL = paint("steel", (0.2, 0.21, 0.22), rough=0.5, metal=0.6, grime=0.45, wear=0.4, rust=0.3, wear_col=(0.42, 0.42, 0.42))
M_GUN = paint("gunmetal", (0.09, 0.095, 0.1), rough=0.45, metal=0.5, grime=0.4, wear=0.4, rust=0.2, wear_col=(0.35, 0.35, 0.35))
M_GALV = galvanized("galvanized")
M_RED = paint("red_paint", (0.5, 0.045, 0.025), rough=0.5, grime=0.5, wear=0.35, streaks=0.5, rust=0.2)
M_YELLOW = paint("safety_yellow", (0.72, 0.5, 0.03), rough=0.5, grime=0.5, wear=0.4, streaks=0.3, wear_col=(0.25, 0.24, 0.22))
M_SHELTER = paint("shelter", (0.68, 0.69, 0.66), rough=0.5, grime=0.65, wear=0.2, streaks=0.7)
M_TOWER = stripes("tower_paint", (0.55, 0.05, 0.025), (0.78, 0.78, 0.76), 2.0, grime=0.55, wear=0.35)
M_MAST = stripes("mast_paint", (0.55, 0.05, 0.025), (0.8, 0.8, 0.78), MAST_H / 7.0, grime=0.45, wear=0.3)
M_RUBBER = rubber()
M_HAZ = hazard()
M_GRATE = grating()
M_LENS = glass("lens", (0.25, 0.22, 0.15))
M_RED_LENS = glass("red_lens", (0.35, 0.02, 0.01))
M_GREEN_LENS = glass("green_lens", (0.02, 0.25, 0.04))
M_DARK = plain("void", (0.01, 0.01, 0.01), 0.9)
M_SIGN = paint("sign_white", (0.8, 0.8, 0.77), rough=0.45, grime=0.3, wear=0.15)
M_SIGN_RED = paint("sign_red", (0.6, 0.03, 0.02), rough=0.45, grime=0.3, wear=0.15)
M_BLUE = paint("lox_blue", (0.03, 0.12, 0.38), rough=0.5, grime=0.4, wear=0.3)


def _sep(nt):
    s = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), s.inputs[0])
    return s.outputs["X"], s.outputs["Y"], s.outputs["Z"]


def apron_mat():
    """The pad's concrete: slabs with sealed joints, oil stains, the yellow keep-out square, soot blown out round the
    table and down the trench onto the spillway (Blender x = block x, y = -block z)."""
    m, nt, bsdf = node_mat("apron")
    pos = _coords(nt)
    X, Y, Z = _sep(nt)
    c = (0.4, 0.395, 0.37)
    big = _noise(nt, 0.3, 6, 0.6, pos)
    fine = _noise(nt, 40.0, 4, 0.6, pos)
    col = _mix(nt, big, tuple(k * 0.85 for k in c), tuple(k * 1.06 for k in c))
    col = _mix(nt, _math(nt, "MULTIPLY", _ramp(nt, fine, 0.35, 0.75), 0.25), col, tuple(k * 0.75 for k in c))
    st = _ramp(nt, _noise(nt, 0.9, 8, 0.7, pos), 0.56, 0.7)
    col = _mix(nt, _math(nt, "MULTIPLY", st, 0.5), col, tuple(k * 0.62 for k in c))
    oil = _ramp(nt, _noise(nt, 0.45, 4, 0.5, pos), 0.66, 0.74)
    col = _mix(nt, _math(nt, "MULTIPLY", oil, 0.45), col, (0.07, 0.065, 0.06))
    ax, ay = _math(nt, "ABSOLUTE", X), _math(nt, "ABSOLUTE", Y)
    # joints between the slabs (every 3 blocks) and round the table
    jx = _math(nt, "LESS_THAN", _math(nt, "ABSOLUTE", _math(nt, "SUBTRACT", ax, 3.0)), 0.018)
    jy = _math(nt, "LESS_THAN", _math(nt, "ABSOLUTE", _math(nt, "SUBTRACT", ay, 3.0)), 0.018)
    mx = _math(nt, "MAXIMUM", ax, ay)
    jt = _math(nt, "LESS_THAN", _math(nt, "ABSOLUTE", _math(nt, "SUBTRACT", mx, 1.05)), 0.016)
    joint = _math(nt, "MAXIMUM", _math(nt, "MAXIMUM", jx, jy), jt)
    col = _mix(nt, _math(nt, "MULTIPLY", joint, 0.85), col, (0.035, 0.033, 0.03))
    # keep-out square, paint worn through in places, broken where the trench runs out
    ring = _math(nt, "MULTIPLY", _math(nt, "GREATER_THAN", mx, 2.06), _math(nt, "LESS_THAN", mx, 2.2))
    trench = _math(nt, "MULTIPLY", _math(nt, "GREATER_THAN", X, 0.4), _math(nt, "LESS_THAN", ay, 1.5))
    ring = _math(nt, "MULTIPLY", ring, _math(nt, "SUBTRACT", 1.0, trench))
    chip = _ramp(nt, _noise(nt, 14.0, 6, 0.7, pos), 0.3, 0.5)
    col = _mix(nt, _math(nt, "MULTIPLY", ring, chip), col, (0.6, 0.42, 0.03))
    # soot: round the table, down the trench and fanning out over the spillway
    r = _math(nt, "POWER", _math(nt, "ADD", _math(nt, "MULTIPLY", X, X), _math(nt, "MULTIPLY", Y, Y)), 0.5)
    soot = _ramp(nt, r, 3.3, 0.7)
    width = _math(nt, "ADD", 1.7, _math(nt, "MULTIPLY", _math(nt, "MAXIMUM", _math(nt, "SUBTRACT", X, 5.5), 0.0), 0.4))
    across = _ramp(nt, _math(nt, "DIVIDE", ay, width), 1.0, 0.45)
    along = _math(nt, "MULTIPLY", _ramp(nt, X, 0.0, 1.0), _ramp(nt, X, 14.0, 6.5))
    soot = _math(nt, "MAXIMUM", soot, _math(nt, "MULTIPLY", across, along))
    soot = _math(nt, "MULTIPLY", soot, _mixf(nt, _noise(nt, 1.4, 6, 0.65, pos), 0.45, 1.25), clamp=True)
    col = _mix(nt, soot, col, (0.022, 0.02, 0.019))
    col = weathered(nt, col, 0.35, 0.0, 0.15, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, _mixf(nt, fine, 0.8, 0.95))


def refractory_mat():
    """The trench's refractory lining: big fire bricks, the mortar eaten out, black with soot low down."""
    m, nt, bsdf = node_mat("refractory")
    pos = _coords(nt)
    X, Y, Z = _sep(nt)
    comb = nt.nodes.new("ShaderNodeCombineXYZ")
    nt.links.new(X, comb.inputs[0])
    nt.links.new(Z, comb.inputs[1])
    br = nt.nodes.new("ShaderNodeTexBrick")
    nt.links.new(comb.outputs[0], br.inputs["Vector"])
    br.inputs["Color1"].default_value = (0.24, 0.17, 0.12, 1)
    br.inputs["Color2"].default_value = (0.32, 0.25, 0.18, 1)
    br.inputs["Mortar"].default_value = (0.08, 0.075, 0.07, 1)
    br.inputs["Scale"].default_value = 1.0
    br.inputs["Mortar Size"].default_value = 0.012
    br.inputs["Brick Width"].default_value = 0.36
    br.inputs["Row Height"].default_value = 0.14
    col = br.outputs["Color"]
    soot = _ramp(nt, Z, 0.85, 0.05)
    soot = _math(nt, "MULTIPLY", _math(nt, "ADD", soot, 0.35), _mixf(nt, _noise(nt, 2.0, 6, 0.6, pos), 0.4, 1.3), clamp=True)
    col = _mix(nt, soot, col, (0.025, 0.022, 0.02))
    col = weathered(nt, col, 0.4, 0.0, 0.5, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, 0.9)


def scorched_mat():
    """Steel that the exhaust plays on: black soot over heat-tinted, rusting plate, the welds between the plates."""
    m, nt, bsdf = node_mat("scorched_steel")
    pos = _coords(nt)
    X, Y, Z = _sep(nt)
    tint = _noise(nt, 1.2, 6, 0.6, pos)
    col = _mix(nt, tint, (0.16, 0.09, 0.05), (0.06, 0.065, 0.085))
    rust = _ramp(nt, _noise(nt, 3.0, 8, 0.7, pos), 0.55, 0.7)
    col = _mix(nt, rust, col, (0.2, 0.08, 0.03))
    seam = _math(nt, "LESS_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", X, 0.5)), 0.03)
    col = _mix(nt, _math(nt, "MULTIPLY", seam, 0.7), col, (0.03, 0.03, 0.03))
    soot = _ramp(nt, _noise(nt, 0.8, 6, 0.6, pos), 0.25, 0.65)
    col = _mix(nt, _math(nt, "MULTIPLY", soot, 0.85), col, (0.018, 0.017, 0.016))
    col = weathered(nt, col, 0.4, 0.0, 0.3, 0.2)
    return _finish(m, nt, bsdf, col, _mixf(nt, soot, 0.6, 0.15), _mixf(nt, soot, 0.55, 0.9))


M_APRON = apron_mat()
M_REFR = refractory_mat()
M_SCORCH = scorched_mat()

# ---------------------------------------------------------------- helpers


def bx(name, x0, y0, z0, x1, y1, z1, mat, part, bevel=0.0):
    return box(name, min(x0, x1), min(y0, y1), min(z0, z1), max(x0, x1), max(y0, y1), max(z0, z1), mat, part, bevel=bevel)


def obox(name, c, ax, ay, az, hx, hy, hz, mat, part, bevel=0.0):
    """A box turned any way: centre c, unit axes ax ay az (block space) and the half sizes along them."""
    c, ax, ay, az = Vector(c), Vector(ax), Vector(ay), Vector(az)
    pts = [mcv(c + ax * sx * hx + ay * sy * hy + az * sz * hz) for sy in (-1, 1) for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    bm = bmesh.new()
    _cuboid(bm, pts)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def prism(name, poly, z0, z1, mat, part, bevel=0.0):
    """A polygon [(x, y), ...] in the block's x-y plane, extruded along z from z0 to z1."""
    bm = bmesh.new()
    a = [bm.verts.new(mc(x, y, z0)) for x, y in poly]
    b = [bm.verts.new(mc(x, y, z1)) for x, y in poly]
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))
    for i in range(len(poly)):
        j = (i + 1) % len(poly)
        bm.faces.new((a[i], a[j], b[j], b[i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def rod(name, a, b, r, mat, part, k=6, caps=False):
    return cyl(name, a, b, r, mat, part, k=k, caps=caps)


def wire(name, pts, r, mat, part, k=5):
    for p, q in zip(pts, pts[1:]):
        cyl(name, p, q, r, mat, part, k=k, caps=False)


def polyline_tube(name, pts, r, mat, part, k=6):
    """A thin tube along a polyline, straight pieces (cheap: hoops, rails round a tank)."""
    wire(name, pts, r, mat, part, k)


def rail(name, pts, mat, part, h=1.0, post=1.1, toe=True, mid=True, first=True, last=True):
    """A light handrail along a path at floor level: posts, top rail, knee rail and a toe board."""
    for i in range(len(pts) - 1):
        a, b = Vector(pts[i]), Vector(pts[i + 1])
        d = b - a
        n = max(1, round(d.length / post))
        for k in range(n + 1):
            if k == 0 and (i > 0 or not first):
                continue
            if k == n and i == len(pts) - 2 and not last:
                continue
            p = a + d * (k / n)
            rod(f"{name}_post", tuple(p), (p.x, p.y + h, p.z), 0.02, mat, part)
        cyl(f"{name}_top", (a.x, a.y + h, a.z), (b.x, b.y + h, b.z), 0.024, mat, part, k=6, caps=True)
        if mid:
            rod(f"{name}_mid", (a.x, a.y + h * 0.5, a.z), (b.x, b.y + h * 0.5, b.z), 0.015, mat, part)
        if toe:
            beam(f"{name}_toe", (a.x, a.y + 0.05, a.z), (b.x, b.y + 0.05, b.z), 0.008, 0.1, mat, part)


def ladder2(name, x, z, y0, y1, facing, mat, part, width=0.42, hoops=0.0):
    """A fixed ladder at (x, z), the climber's side towards facing (dx, dz): flat-bar stiles, round rungs, a cage
    of hoops every `hoops` metres above head height (0: none)."""
    fx, fz = facing
    sx, sz = -fz, fx
    for s in (-1, 1):
        beam(f"{name}_stile", (x + sx * s * width / 2, y0, z + sz * s * width / 2), (x + sx * s * width / 2, y1 + 0.95, z + sz * s * width / 2),
             0.05 if fx else 0.016, 0.016 if fx else 0.05, mat, part)
    y = y0 + 0.3
    while y < y1 + 0.01:
        rod(f"{name}_rung", (x - sx * width / 2, y, z - sz * width / 2), (x + sx * width / 2, y, z + sz * width / 2), 0.012, mat, part)
        y += 0.3
    if hoops > 0 and y1 - y0 > 3.0:
        y = y0 + 2.3
        tops = []
        while y < y1 + 0.95:
            pts = []
            for i in range(7):
                a = math.pi * i / 6
                pts.append((x + sx * math.cos(a) * width * 0.85 + fx * (0.04 + math.sin(a) * 0.62), y,
                            z + sz * math.cos(a) * width * 0.85 + fz * (0.04 + math.sin(a) * 0.62)))
            polyline_tube(f"{name}_hoop", pts, 0.011, mat, part, k=4)
            tops.append(y)
            y += hoops
        for i in (1, 3, 5):
            a = math.pi * i / 6
            px = x + sx * math.cos(a) * width * 0.85 + fx * (0.04 + math.sin(a) * 0.62)
            pz = z + sz * math.cos(a) * width * 0.85 + fz * (0.04 + math.sin(a) * 0.62)
            rod(f"{name}_cagebar", (px, tops[0], pz), (px, tops[-1], pz), 0.01, mat, part, k=4)


def flange(name, centre, axis, r, mat, part, k=12, w=0.03):
    lathe(name, centre, axis, [(-w / 2, r * 0.6), (-w / 2, r), (w / 2, r), (w / 2, r * 0.6)], mat, part, k=k, loop=True)


def valve(name, centre, axis, r, mat, part, wheel_up=True):
    """A gate valve on a pipe of radius r: the body, bonnet, stem and handwheel."""
    c = Vector(centre)
    lathe(f"{name}_body", tuple(c - Vector(axis) * r * 1.6), axis, [(0.0, r * 1.1), (0.0, r * 1.7), (r * 3.2, r * 1.7), (r * 3.2, r * 1.1)],
          mat, part, k=10, loop=True)
    cyl(f"{name}_bonnet", tuple(c), tuple(c + Vector((0, r * 3.2, 0))), r * 0.9, mat, part, k=8)
    rod(f"{name}_stem", tuple(c + Vector((0, r * 3.2, 0))), tuple(c + Vector((0, r * 4.6, 0))), 0.012, M_GALV, part)
    lathe(f"{name}_wheel", tuple(c + Vector((0, r * 4.5, 0))), (0, 1, 0), [(-0.008, r * 2.2), (-0.008, r * 2.5), (0.008, r * 2.5), (0.008, r * 2.2)],
          M_RED, part, k=12, loop=True)


def sleepers(name, pts, r, part, step=1.6):
    """Pipe supports under the low horizontal runs of a line: a concrete block and a steel saddle."""
    for p, q in zip(pts, pts[1:]):
        p, q = Vector(p), Vector(q)
        if abs(p.y - q.y) > 1e-3 or p.y > 1.0:
            continue
        d = q - p
        n = int(d.length / step)
        for k in range(1, n + 1):
            c = p + d * (k / (n + 1))
            bot = c.y - r
            bx(f"{name}_sleeper", c.x - 0.14, 0.0, c.z - 0.14, c.x + 0.14, max(0.12, bot - 0.03), c.z + 0.14, M_CONC_D, part)
            if bot - 0.03 > 0.12:
                bx(f"{name}_saddle", c.x - 0.1, bot - 0.03, c.z - 0.1, c.x + 0.1, bot, c.z + 0.1, M_STEEL, part)


def line(name, pts, r, mat, part, k=10, bend=0.25, supports=True):
    pipe(name, pts, r, mat, part, k=k, bend=bend)
    if supports:
        sleepers(name, pts, r, part)


def sign(name, x, y, z, facing, part, w=0.55, h=0.4, post=True):
    """A warning sign: white plate, red header, lines of black print, on two posts. facing (dx, dz)."""
    fx, fz = facing
    sx, sz = -fz, fx
    f = Vector((fx, 0, fz))
    s = Vector((sx, 0, sz))
    up = Vector((0, 1, 0))
    c = Vector((x, y, z))
    obox(f"{name}_plate", c, s, up, f, w / 2, h / 2, 0.008, M_SIGN, part)
    obox(f"{name}_head", c + up * (h * 0.36) + f * 0.009, s, up, f, w / 2 - 0.02, h * 0.1, 0.002, M_SIGN_RED, part)
    for i in range(3):
        obox(f"{name}_print", c + up * (h * (0.08 - i * 0.16)) + f * 0.009, s, up, f, w * (0.38 - 0.06 * i), 0.018, 0.002, M_DARK, part)
    if post:
        for k in (-1, 1):
            p = c - f * 0.02 + s * k * w * 0.32
            rod(f"{name}_post", (p.x, 0.0, p.z), (p.x, y + h / 2, p.z), 0.022, M_GALV, part)


# ================================================================= the pad (static)
P = "pad"
# ---- apron, spillway
box("apron", -5.5, 0.0, -5.5, 5.5, 0.1, 5.5, M_APRON, P, bevel=0.03)
box("spillway", 5.5, 0.0, -2.6, 9.2, 0.06, 2.6, M_APRON, P, bevel=0.02)
for s in (-1, 1):
    prism("spill_wing", [(5.5, 0.0), (8.6, 0.0), (8.6, 0.12), (5.5, 0.55)], s * 2.6, s * 2.85, M_CONC_D, P, bevel=0.02)
# drain channels along the apron's edges
for s in (-1, 1):
    bx("drain", -4.6, 0.1, s * 4.95, 4.6, 0.104, s * 5.15, M_GRATE, P)

# ---- the flame trench to +x: walls lined with refractory brick, a curved steel deflector, the spillway beyond
WALL = [(0.5, 0.1), (5.5, 0.1), (5.5, 1.45), (4.5, 0.8), (0.5, 0.8)]
LINER = [(0.5, 0.1), (5.5, 0.1), (5.5, 1.41), (4.52, 0.76), (0.5, 0.76)]
for s in (-1, 1):
    prism("trench_wall", WALL, s * 1.0, s * 1.45, M_CONC_D, P, bevel=0.02)
    prism("refractory", LINER, s * 0.94, s * 1.0, M_REFR, P)
    bx("wall_cap", 0.5, 0.8, s * 1.0, 4.5, 0.83, s * 1.45, M_HAZ, P)
    prism("wall_cap", [(4.5, 0.8), (5.5, 1.45), (5.5, 1.48), (4.5, 0.83)], s * 1.0, s * 1.45, M_HAZ, P)
    # end pilaster of the wall, scorched
    bx("wall_end", 5.5, 0.1, s * 0.94, 5.65, 1.5, s * 1.5, M_CONC_D, P, bevel=0.015)


def curve(t):
    return 3.0 + 2.5 * t, 0.1 + 1.15 * t * t


ts = [i / 12 for i in range(13)]
top = [curve(t) for t in ts]
prism("deflector", top + [(x, y - 0.06) for x, y in reversed(top)], -0.94, 0.94, M_SCORCH, P)
under = [(x, y - 0.06) for x, y in top if y - 0.06 > 0.1]
prism("deflector_base", [(3.25, 0.1)] + under + [(5.5, 0.1)], -0.94, 0.94, M_CONC_D, P)
# stiffening ribs across the deflector's face
for t in (0.35, 0.6, 0.82):
    x, y = curve(t)
    dx, dy = 2.5, 2.3 * t
    ln = math.hypot(dx, dy)
    nx, ny = -dy / ln, dx / ln
    beam("defl_rib", (x + nx * 0.02, y + ny * 0.02, -0.94), (x + nx * 0.02, y + ny * 0.02, 0.94), 0.05, 0.04, M_SCORCH, P)
# water spray headers along the walls, dropping to the deluge ring
for s in (-1, 1):
    pts = [(4.3, 0.87, s * 0.9), (0.62, 0.87, s * 0.9), (0.62, 0.25, s * 0.9), (0.72, 0.25, s * 0.85)]
    pipe("trench_spray", pts, 0.035, M_GALV, P, k=8, bend=0.08)
    for k in range(7):
        x = 0.9 + k * 0.55
        rod("spray_nozzle", (x, 0.87, s * 0.9), (x, 0.8, s * 0.82), 0.014, M_GALV, P)
    for x in (1.2, 2.6, 4.0):
        bx("spray_bracket", x - 0.02, 0.8, s * 0.86, x + 0.02, 0.84, s * 1.0, M_STEEL, P)

# ---- the table: steel girders round the block, the deluge ring and its nozzles
for s in (-1, 1):
    bx("table_girder", -0.64, 0.1, s * 0.5, 0.64, 0.36, s * 0.64, M_SCORCH, P, bevel=0.012)
    bx("table_girder", s * 0.5, 0.1, -0.5, s * 0.64, 0.36, 0.5, M_SCORCH, P, bevel=0.012)
    for z in (-0.3, 0.3):
        bx("table_stiffener", s * 0.64, 0.1, z - 0.012, s * 0.66, 0.34, z + 0.012, M_SCORCH, P)
        bx("table_stiffener", z - 0.012, 0.1, s * 0.64, z + 0.012, 0.34, s * 0.66, M_SCORCH, P)
R = 0.85
pipe("deluge_ring", [(-R, 0.25, 0.0), (-R, 0.25, -R), (R, 0.25, -R), (R, 0.25, R), (-R, 0.25, R), (-R, 0.25, 0.0)], 0.045, M_GALV, P, k=8, bend=0.15)
for s in (-1, 1):
    for t in (-0.45, 0.0, 0.45):
        for p, d in (((s * R, 0.25, t), (-s, 0.6, 0.0)), ((t, 0.25, s * R), (0.0, 0.6, -s))):
            dv = Vector(d).normalized() * 0.09
            rod("deluge_nozzle", p, tuple(Vector(p) + dv), 0.016, M_GALV, P)
    for t in (-R, R):
        bx("ring_stand", s * R - 0.04, 0.1, t - 0.04, s * R + 0.04, 0.21, t + 0.04, M_STEEL, P)
# the water main from the site's main to the ring, a branch to the tower riser
line("water_main", [WATER_J, (-1.6, 0.25, 0.5), (-0.9, 0.25, 0.5)], 0.08, M_GREY, P, bend=0.25)
flange("main_flange", WATER_J, (0, 0, 1), 0.11, M_GREY, P)
pipe("water_riser", [(-1.6, 0.25, 0.72), (-2.3, 0.25, 0.72), (-2.3, 0.45, 0.72), (-2.54, 0.45, 0.72), (-2.54, 16.2, 0.72), (-2.54, 16.2, 0.55)],
     0.045, M_GALV, P, k=8, bend=0.1)
valve("water_valve", (-2.0, 0.25, 0.72), (1, 0, 0), 0.045, M_GREY, P)
# cable tray from the tower to the table, the junction box and the umbilical cables to the table
bx("tray_floor", -2.45, 0.2, -0.42, -1.15, 0.215, -0.18, M_GALV, P)
for z in (-0.42, -0.18):
    bx("tray_side", -2.45, 0.2, z - 0.006, -1.15, 0.32, z + 0.006, M_GALV, P)
for x in (-2.2, -1.5):
    bx("tray_stand", x - 0.04, 0.1, -0.4, x + 0.04, 0.2, -0.2, M_STEEL, P)
for z in (-0.36, -0.3, -0.24):
    rod("tray_cable", (-2.45, 0.24, z), (-1.15, 0.24, z), 0.025, M_RUBBER, P, k=6)
bx("junction_box", -1.15, 0.1, -0.46, -0.95, 0.56, -0.14, M_SHELTER, P, bevel=0.012)
bx("junction_door", -0.952, 0.16, -0.42, -0.94, 0.5, -0.18, M_SHELTER, P)
for z in (-0.34, -0.26):
    pipe("table_cable", [(-1.0, 0.56, z), (-0.92, 0.66, z), (-0.68, 0.6, z), (-0.56, 0.32, z), (-0.5, 0.26, z)], 0.028, M_RUBBER, P, k=8, bend=0.08)

# ---- the service tower
bx("footing", XO - 0.2, 0.0, -ZO - 0.2, XF + 0.2, Y0, ZO + 0.2, M_CONC_D, P, bevel=0.03)
COLS = [(XO, -ZO), (XO, ZO), (XF, -ZO), (XF, ZO), (XB, -ZC), (XB, ZC), (XF, -ZC), (XF, ZC)]
for x, z in COLS:
    ibeam("column", (x, Y0, z), (x, TOP + DT, z), 0.14, 0.14, 0.018, 0.012, M_TOWER, P)
    bx("base_plate", x - 0.14, Y0, z - 0.14, x + 0.14, Y0 + 0.03, z + 0.14, M_STEEL, P)


def girder(name, a, b, y):
    beam(name, (a[0], y - 0.07, a[1]), (b[0], y - 0.07, b[1]), 0.08, 0.14, M_TOWER, P)


for L in PLATFORMS:
    for a, b in (((XO, -ZO), (XF, -ZO)), ((XO, ZO), (XF, ZO)), ((XO, -ZO), (XO, ZO)), ((XF, ZC), (XF, ZO)), ((XF, -ZO), (XF, -ZC)),
                 ((XB, -ZC), (XB, ZC)), ((XF, -ZC), (XF, ZC)), ((XB, -ZC), (XF, -ZC)), ((XB, ZC), (XF, ZC)),
                 ((XO, -0.6), (XB, -0.6)), ((XO, 0.6), (XB, 0.6))):
        girder("girder", a, b, L)
# bracing: X braces of angle iron on the outer faces, storey by storey
STOREYS = [(Y0 if i == 0 else PLATFORMS[i - 1], PLATFORMS[i]) for i in range(5)]
for ya, yb in STOREYS:
    ya, yb = ya + 0.05, yb - 0.15
    faces = [((XO, -ZO), (XO, ZO)), ((XO, -ZO), (XF, -ZO)), ((XO, ZO), (XF, ZO)), ((XF, ZC), (XF, ZO)), ((XF, -ZO), (XF, -ZC))]
    for (ax_, az), (bx_, bz) in faces:
        angle_iron("brace", (ax_, ya, az), (bx_, yb, bz), 0.06, 0.008, M_TOWER, P)
        angle_iron("brace", (bx_, ya, bz), (ax_, yb, az), 0.06, 0.008, M_TOWER, P)
# decks: grating on the girders round the shaft, the stairwell left open
for i, L in enumerate(PLATFORMS):
    for s in (-1, 1):
        bx("deck", XO - 0.05, L, s * 0.6, XF + 0.04, L + DT, s * (ZO + 0.05), M_GRATE, P)
    if L == TOP:
        bx("deck", XO - 0.05, L, -0.6, XB, L + DT, 0.0, M_GRATE, P)
        continue
    y = L + DT
    for s in (-1, 1):
        rail("deck_rail", [(XF - 0.02, y, s * 0.64), (XF - 0.02, y, s * (ZO - 0.06)), (XO + 0.06, y, s * (ZO - 0.06)), (XO + 0.06, y, s * 0.62)],
             M_YELLOW, P)
    # work lights under the decks (lit by the game)
    for s in (-1, 1):
        bx("worklight", TX - 0.15, L - 0.06, s * 0.83, TX + 0.15, L, s * 0.93, M_GUN, P)
        bx("worklight_lens", TX - 0.13, L - 0.072, s * 0.845, TX + 0.13, L - 0.06, s * 0.915, M_LENS, P)
for s in (-1, 1):
    bx("worklight", TX - 0.15, TOP - 0.06, s * 0.83, TX + 0.15, TOP, s * 0.93, M_GUN, P)
    bx("worklight_lens", TX - 0.13, TOP - 0.072, s * 0.845, TX + 0.13, TOP - 0.06, s * 0.915, M_LENS, P)
# roof railing all round, the hatch and its ladder
y = TOP + DT
rail("roof_rail", [(XO + 0.06, y, -ZO + 0.06), (XF - 0.02, y, -ZO + 0.06), (XF - 0.02, y, ZO - 0.06), (XO + 0.06, y, ZO - 0.06), (XO + 0.06, y, -ZO + 0.06)],
     M_YELLOW, P)
rail("hatch_rail", [(XB - 0.02, y, 0.6), (XB - 0.02, y, 0.04), (XO + 0.75, y, 0.04)], M_YELLOW, P, toe=False, post=0.6)
# the top storey: a floor over the outer stair lane, the ladder up through the roof hatch
bx("deck", XO - 0.05, PLATFORMS[4], -0.6, XO + 0.62, PLATFORMS[4] + DT, 0.6, M_GRATE, P)
rail("stairwell_rail", [(XO + 0.62, PLATFORMS[4] + DT, -0.58), (XO + 0.62, PLATFORMS[4] + DT, 0.58)], M_YELLOW, P, post=0.6)
ladder2("roof_ladder", XO + 0.35, 0.3, PLATFORMS[4] + DT, TOP + DT, (0, -1), M_GALV, P)

# switch-back stairs on the -x side: up along +z in the outer lane, a landing, back along -z in the inner lane
LANE_A = (XO + 0.09, XO + 0.59)
LANE_B = (XO + 0.65, XB - 0.06)


def flight(name, lane, z0, z1, y0, y1):
    n = max(4, round((y1 - y0) / 0.25))
    xa, xb = lane
    for j in range(n):
        t = (j + 0.5) / n
        zc = z0 + (z1 - z0) * t
        yt = y0 + (y1 - y0) * (j + 1) / n
        bx(f"{name}_tread", xa + 0.02, yt - 0.03, zc - 0.07, xb - 0.02, yt, zc + 0.07, M_GRATE, P)
    for x in (xa, xb):
        beam(f"{name}_stringer", (x, y0 - 0.05, z0), (x, y1 - 0.05, z1), 0.03, 0.16, M_TOWER, P)
        rod(f"{name}_post", (x, y0, z0), (x, y0 + 0.95, z0), 0.02, M_YELLOW, P)
        rod(f"{name}_post", (x, y1, z1), (x, y1 + 0.95, z1), 0.02, M_YELLOW, P)
        cyl(f"{name}_handrail", (x, y0 + 0.95, z0), (x, y1 + 0.95, z1), 0.022, M_YELLOW, P, k=6)
        rod(f"{name}_knee", (x, y0 + 0.48, z0), (x, y1 + 0.48, z1), 0.015, M_YELLOW, P)


for i, (ya, yb) in enumerate(STOREYS):
    y0 = Y0 if i == 0 else ya + DT
    y1 = yb + DT
    mid = (y0 + y1) / 2
    flight("stair", LANE_A, -0.6, 0.6, y0, mid)
    flight("stair", LANE_B, 0.6, -0.6, mid, y1)
    bx("landing", XO - 0.05, mid - DT, 0.6, XB, mid, ZO + 0.05, M_GRATE, P)
    for z in (0.6, ZO):
        girder("landing_girder", (XO, z), (XB, z), mid - DT)
    rail("landing_rail", [(XO + 0.06, mid, 0.62), (XO + 0.06, mid, ZO - 0.06), (XB - 0.02, mid, ZO - 0.06)], M_YELLOW, P)

# elevator shaft: T guide rails on the core's x faces with their brackets, landing gates on the -z side
for s in (-1, 1):
    x = TX + s * 0.45
    beam("guide_rail", (x, Y0, 0.0), (x, TOP - 0.02, 0.0), 0.05, 0.06, M_STEEL, P)
    for L in (2.3,) + PLATFORMS[:5] + tuple(p + 2.0 for p in PLATFORMS[:4]):
        bx("rail_bracket", min(x, TX + s * 0.55), L - 0.1, -0.035, max(x, TX + s * 0.55), L - 0.03, 0.035, M_STEEL, P)
for L in (Y0,) + PLATFORMS[:5]:
    y = L + (DT if L != Y0 else 0.0)
    for s in (-1, 1):
        bx("gate_post", TX + s * 0.42 - 0.025, y, -ZC - 0.05, TX + s * 0.42 + 0.025, y + 1.25, -ZC - 0.0, M_YELLOW, P)
    bx("gate_head", TX - 0.45, y + 1.25, -ZC - 0.05, TX + 0.45, y + 1.33, -ZC, M_YELLOW, P)
    for h in (0.5, 1.0):
        bx("gate_bar", TX - 0.4, y + h - 0.02, -ZC - 0.04, TX + 0.4, y + h + 0.02, -ZC - 0.01, M_YELLOW, P)

# machine house on the core, the hammerhead crane on it, the beacon and the lightning rod on top
MH = TOP + DT
bx("machine_house", XB - 0.05, MH, -ZC - 0.07, XF + 0.05, MH + 0.95, ZC + 0.07, M_SHELTER, P, bevel=0.02)
bx("mh_roof", XB - 0.09, MH + 0.95, -ZC - 0.11, XF + 0.09, MH + 1.0, ZC + 0.11, M_GUN, P, bevel=0.01)
bx("mh_door", TX - 0.22, MH + 0.03, -ZC - 0.085, TX + 0.22, MH + 0.8, -ZC - 0.07, M_GREY, P, bevel=0.006)
bx("mh_louvre", TX - 0.3, MH + 0.45, ZC + 0.07, TX + 0.3, MH + 0.75, ZC + 0.09, M_GUN, P)
CB = MH + 1.0  # crane base
cyl("slew_pedestal", (TX, CB, 0.0), (TX, CB + 0.18, 0.0), 0.3, M_RED, P, k=16)
lathe("slew_ring", (TX, CB + 0.18, 0.0), (0, 1, 0), [(0.0, 0.26), (0.0, 0.34), (0.08, 0.34), (0.08, 0.26)], M_GUN, P, k=16, loop=True)
JX0, JX1, JY0, JY1, JZ = TX - 1.5, TX + 2.9, CB + 0.3, CB + 0.68, 0.16
for z in (-JZ, JZ):
    for yy in (JY0, JY1):
        beam("jib_chord", (JX0, yy, z), (JX1, yy, z), 0.05, 0.05, M_RED, P)
nj = 10
for k in range(nj + 1):
    x = JX0 + (JX1 - JX0) * k / nj
    for z in (-JZ, JZ):
        beam("jib_vert", (x, JY0, z), (x, JY1, z), 0.035, 0.035, M_RED, P)
        if k < nj:
            x1 = JX0 + (JX1 - JX0) * (k + 1) / nj
            a, b = ((x, JY0), (x1, JY1)) if k % 2 == 0 else ((x, JY1), (x1, JY0))
            beam("jib_diag", (a[0], a[1], z), (b[0], b[1], z), 0.03, 0.03, M_RED, P)
    for yy in (JY0, JY1):
        beam("jib_cross", (x, yy, -JZ), (x, yy, JZ), 0.03, 0.03, M_RED, P)
bx("jib_cab", TX - 0.3, CB + 0.18, -0.2, TX + 0.3, JY0 - 0.03, 0.2, M_RED, P, bevel=0.01)
bx("counterweight", JX0 + 0.02, CB - 0.1, -0.22, JX0 + 0.48, JY0 - 0.025, 0.22, M_CONC_D, P, bevel=0.02)
# the kingpost carrying the pendants, the obstruction beacon and the lightning rod
bx("kingpost", TX - 0.07, JY1, -0.07, TX + 0.07, TOP + 2.9, 0.07, M_RED, P)
for xe in (JX0 + 0.05, JX1 - 0.05):
    wire("pendant", [(TX, TOP + 2.85, 0.0), (xe, JY1 + 0.02, 0.0)], 0.012, M_GALV, P, k=4)
cyl("beacon_mount", (TX, TOP + 2.9, 0.0), (TX, TOP + 3.0, 0.0), 0.05, M_GUN, P, k=10)
cyl("beacon", (TX, TOP + 3.0, 0.0), (TX, TOP + 3.22, 0.0), 0.095, M_RED_LENS, P, k=12)
cyl("beacon_cap", (TX, TOP + 3.22, 0.0), (TX, TOP + 3.255, 0.0), 0.1, M_GUN, P, k=12)
cyl("air_terminal", (TX, TOP + 3.255, 0.0), (TX, TOP + 4.2, 0.0), 0.022, M_GALV, P, k=6, r1=0.004)
# trolley and hook out over the pad
bx("trolley", TX + 2.35, JY0 - 0.1, -0.13, TX + 2.65, JY0 - 0.02, 0.13, M_STEEL, P, bevel=0.01)
for dz in (-0.04, 0.04):
    rod("hoist_rope", (TX + 2.5, JY0 - 0.1, dz), (TX + 2.5, TOP - 0.8, dz), 0.009, M_STEEL, P, k=4)
bx("hook_block", TX + 2.42, TOP - 1.1, -0.07, TX + 2.58, TOP - 0.8, 0.07, M_YELLOW, P, bevel=0.012)
pipe("hook", [(TX + 2.5, TOP - 1.1, 0.0), (TX + 2.5, TOP - 1.25, 0.0), (TX + 2.56, TOP - 1.32, 0.0), (TX + 2.62, TOP - 1.25, 0.0)], 0.018, M_STEEL, P, k=6, bend=0.05)

# arm hinges on the tower face: a backing plate and the knuckles above and below the arm's hinge tube
for y in ARM_HEIGHTS:
    bx("hinge_plate", XF + 0.04, y - 0.8, -0.12, XF + 0.07, y + 0.36, 0.12, M_STEEL, P)
    for yy in (y - 0.68, y + 0.22):
        bx("hinge_knuckle", XF + 0.07, yy, -0.06, HINGE_X + 0.06, yy + 0.06, 0.06, M_STEEL, P)
    rod("hinge_pin", (HINGE_X, y - 0.7, 0.0), (HINGE_X, y + 0.3, 0.0), 0.022, M_GALV, P, k=8, caps=True)
# status signal heads on the +x face (red over green, lit by the game)
for s in STATUS_Y:
    bx("signal", XF + 0.03, s - 0.03, 0.92, XF + 0.12, s + 0.33, 1.08, M_GUN, P, bevel=0.008)
    for y0_, m in ((s + 0.17, M_RED_LENS), (s + 0.03, M_GREEN_LENS)):
        bx("signal_lens", XF + 0.12, y0_, 0.95, XF + 0.128, y0_ + 0.1, 1.05, m, P)
        bx("signal_visor", XF + 0.12, y0_ + 0.105, 0.94, XF + 0.19, y0_ + 0.115, 1.06, M_GUN, P)
# umbilical risers up the +x face: liquid oxygen (insulated), fuel, conduits; brackets at every deck
line("lox_line", [LOX_J, (-2.2, 0.5, -0.72), (-2.54, 0.5, -0.72), (-2.54, 4.0, -0.72), (-2.54, 4.0, -0.45)], 0.07, M_INSUL, P, bend=0.18)
line("fuel_line", [FUEL_J, (-1.85, 0.3, -1.02), (-2.54, 0.3, -1.02), (-2.54, 8.0, -1.02), (-2.54, 8.0, -0.45)], 0.055, M_GREY, P, bend=0.15)
flange("lox_end", (-2.54, 4.0, -0.45), (0, 0, 1), 0.1, M_STEEL, P)
flange("fuel_end", (-2.54, 8.0, -0.45), (0, 0, 1), 0.085, M_STEEL, P)
bx("lox_band", -2.62, 1.2, -0.8, -2.46, 1.35, -0.64, M_BLUE, P)
valve("lox_valve", (-2.2, 0.5, -2.4), (0, 0, 1), 0.07, M_STEEL, P)
valve("fuel_valve", (-1.85, 0.3, -3.2), (0, 0, 1), 0.055, M_STEEL, P)
for z in (0.8, 0.86):
    pipe("conduit", [(-2.3, 0.1, z), (-2.4, 0.3, z), (-2.57, 0.45, z), (-2.57, 16.3, z), (-2.57, 16.3, 0.5)], 0.022, M_RUBBER, P, k=6, bend=0.08)
for L in PLATFORMS[:4]:
    for z, top_ in ((-0.72, 4.0), (-1.02, 8.0), (0.72, 16.2), (0.83, 16.3)):
        if L + 1.0 < top_:
            bx("riser_clamp", XF + 0.03, L + 1.0, z - 0.03, -2.5, L + 1.06, z + 0.03, M_STEEL, P)

# ================================================================= umbilical swing arms (built extended)
XR, XT = HINGE_X + 0.05, ARM_TIP - 0.13
ZA, ZB = -0.02, 0.42
for i, y in enumerate(ARM_HEIGHTS):
    A = f"arm{i}"

    def bot(x):
        return y - 0.45 + 0.15 * (x - XR) / (XT - XR)

    for z in (ZA, ZB):
        beam("arm_chord", (XR, y - 0.03, z), (XT, y - 0.03, z), 0.05, 0.05, M_RED, A)
        beam("arm_chord", (XR, bot(XR), z), (XT, bot(XT), z), 0.05, 0.05, M_RED, A)
    xs = [XR + (XT - XR) * k / 4 for k in range(5)]
    for k, x in enumerate(xs):
        for z in (ZA, ZB):
            beam("arm_vert", (x, bot(x), z), (x, y - 0.03, z), 0.035, 0.035, M_RED, A)
        beam("arm_floorbeam", (x, bot(x), ZA), (x, bot(x), ZB), 0.03, 0.03, M_RED, A)
        if k < 4:
            x1 = xs[k + 1]
            for z in (ZA, ZB):
                a, b = ((x, bot(x)), (x1, y - 0.03)) if k % 2 == 0 else ((x, y - 0.03), (x1, bot(x1)))
                beam("arm_diag", (a[0], a[1], z), (b[0], b[1], z), 0.03, 0.03, M_RED, A)
            beam("arm_lateral", (x, bot(x), ZA if k % 2 == 0 else ZB), (x1, bot(x1), ZB if k % 2 == 0 else ZA), 0.025, 0.025, M_RED, A)
    bx("arm_walkway", XR - 0.02, y, ZA - 0.04, XT + 0.02, y + 0.04, ZB + 0.04, M_GRATE, A)
    for z in (ZA - 0.03, ZB + 0.03):
        rail("arm_rail", [(XR + 0.13, y + 0.04, z), (XT, y + 0.04, z)], M_YELLOW, A, h=0.85, post=0.65)
    # the hinge tube and the root frame, a strut down to the tube's foot
    cyl("arm_hinge", (HINGE_X, y - 0.6, 0.0), (HINGE_X, y + 0.2, 0.0), 0.05, M_STEEL, A, k=10)
    bx("arm_root", HINGE_X + 0.02, y - 0.48, ZA - 0.03, HINGE_X + 0.07, y, ZB + 0.03, M_RED, A)
    beam("arm_strut", (HINGE_X, y - 0.56, 0.02), (xs[1], bot(xs[1]), 0.2), 0.04, 0.04, M_RED, A)
    # the umbilical carrier at the tip, its quick-disconnect plate against the missile, the hoses drooping back
    bx("arm_carrier", XT - 0.02, y - 0.5, -0.16, XT + 0.06, y + 0.04, ZB + 0.03, M_RED, A, bevel=0.01)
    bx("arm_qd", XT + 0.06, y - 0.42, -0.1, ARM_TIP, y - 0.12, 0.22, M_GUN, A, bevel=0.008)
    for dz in (-0.03, 0.08):
        cyl("qd_port", (ARM_TIP, y - 0.27, dz), (ARM_TIP + 0.01, y - 0.27, dz), 0.04, M_GALV, A, k=8)
    pipe("arm_hose", catenary((XT - 0.02, y - 0.38, 0.0), (XR + 0.12, y - 0.48, 0.06), 0.45, 9), 0.035, M_RUBBER, A, k=8)
    pipe("arm_hose", catenary((XT - 0.02, y - 0.3, 0.14), (XR + 0.12, y - 0.44, 0.2), 0.36, 9), 0.028, M_RUBBER, A, k=8)
    pipe("arm_cable", catenary((XT - 0.02, y - 0.22, 0.32), (XR + 0.12, y - 0.4, 0.36), 0.28, 9), 0.022, M_RUBBER, A, k=8)
    if i == 0:
        pipe("arm_lox", catenary((XT - 0.02, y - 0.2, -0.08), (XR + 0.25, y - 0.5, -0.02), 0.5, 9), 0.06, M_INSUL, A, k=8)
    if i == 1:
        pipe("arm_fuel", catenary((XT - 0.02, y - 0.2, -0.08), (XR + 0.25, y - 0.5, -0.02), 0.45, 9), 0.05, M_GREY, A, k=8)

# ================================================================= hold-down clamp (the +x +z corner)


def clamp(part):
    piv = Vector((CLAMP_PIVOT, CLAMP_Y, CLAMP_PIVOT))
    u = Vector((-1.0, 0.0, -1.0)).normalized()
    a = Vector((-1.0, 0.0, 1.0)).normalized()
    up = Vector((0.0, 1.0, 0.0))

    def L(pu, py, pa=0.0):
        return tuple(piv + u * pu + up * py + a * pa)

    cyl("clamp_hub", L(0, 0, -0.04), L(0, 0, 0.04), 0.045, M_STEEL, part, k=10)
    for pa in (-0.04, 0.04):
        cyl("clamp_pin", L(0, 0, pa), L(0, 0, pa * 1.45), 0.022, M_GALV, part, k=8)
    section("clamp_lever", L(-0.06, -0.005), L(0.23, 0.085), [(-0.03, -0.035), (0.03, -0.035), (0.03, 0.035), (-0.03, 0.035)], M_YELLOW, part,
            bevel=0.006)
    obox("clamp_jaw", L(0.255, 0.035), u, up, a, 0.04, 0.075, 0.042, M_STEEL, part, bevel=0.008)
    obox("clamp_pad", L(0.298, 0.01), u, up, a, 0.005, 0.045, 0.034, M_DARK, part)
    obox("clamp_tail", L(-0.095, -0.015), u, up, a, 0.035, 0.045, 0.038, M_GUN, part, bevel=0.006)


clamp("clamp")

# ================================================================= elevator car (floor at y = 0 about the shaft axis)
E = "elevator"
CW, CD = 0.36, 0.38
bx("car_floor", TX - CW, 0.0, -CD, TX + CW, 0.06, CD, M_STEEL, E, bevel=0.008)
bx("car_roof", TX - CW, 1.14, -CD, TX + CW, 1.2, CD, M_STEEL, E, bevel=0.008)
for sx in (-1, 1):
    for sz in (-1, 1):
        bx("car_post", TX + sx * CW - 0.025, 0.06, sz * CD - 0.025, TX + sx * CW + 0.025, 1.14, sz * CD + 0.025, M_YELLOW, E)
    bx("car_side", TX + sx * (CW - 0.015) - 0.01, 0.06, -CD + 0.02, TX + sx * (CW - 0.015) + 0.01, 1.14, CD - 0.02, M_SHELTER, E)
    for yy in (0.1, 1.05):
        bx("car_shoe", TX + sx * CW, yy, -0.05, TX + sx * 0.42, yy + 0.1, 0.05, M_GUN, E)
bx("car_back", TX - CW + 0.02, 0.06, CD - 0.025, TX + CW - 0.02, 1.14, CD - 0.005, M_SHELTER, E)
bx("car_kick", TX - CW + 0.02, 0.06, -CD - 0.005, TX + CW - 0.02, 0.32, -CD + 0.015, M_YELLOW, E)
for yy in (0.6, 1.0):
    bx("car_gate", TX - CW + 0.02, yy - 0.02, -CD - 0.005, TX + CW - 0.02, yy + 0.02, -CD + 0.015, M_YELLOW, E)
bx("car_panel", TX + CW - 0.06, 0.7, CD - 0.2, TX + CW - 0.03, 1.0, CD - 0.06, M_GUN, E)
beam("car_crosshead", (TX - CW, 1.25, 0.0), (TX + CW, 1.25, 0.0), 0.08, 0.1, M_STEEL, E)
cyl("car_sheave", (TX, 1.25, -0.06), (TX, 1.25, 0.06), 0.09, M_GUN, E, k=12)
for dx in (-0.03, 0.03):
    rod("car_rope", (TX + dx, 1.3, 0.0), (TX + dx, 1.8, 0.0), 0.009, M_STEEL, E, k=4)

# ================================================================= the site
S = "site"
# ---- lightning masts: red and white lattice towers, the catenary over the pad, guys to anchors


def mast(name, x, z, y0, y1, w0, w1, panels, mat, part, leg=0.09, brace=0.045):
    h = y1 - y0

    def corner(cx, cz, y):
        w = (w0 + (w1 - w0) * (y - y0) / h) / 2
        return (x + cx * w, y, z + cz * w)

    cs = [(-1, -1), (1, -1), (1, 1), (-1, 1)]
    for i, (cx, cz) in enumerate(cs):
        angle_iron(f"{name}_leg", corner(cx, cz, y0), corner(cx, cz, y1), leg, leg * 0.12, mat, part, roll=math.pi / 4 + i * math.pi / 2)
    for p in range(panels):
        ya = y0 + h * p / panels
        yb = y0 + h * (p + 1) / panels
        for i in range(4):
            c0, c1 = cs[i], cs[(i + 1) % 4]
            beam(f"{name}_br", corner(*c0, ya), corner(*c1, yb), brace, brace, mat, part)
            beam(f"{name}_br", corner(*c1, ya), corner(*c0, yb), brace, brace, mat, part)
            beam(f"{name}_hz", corner(*c0, yb), corner(*c1, yb), brace, brace, mat, part)


for mx, mz in MASTS:
    box("mast_found", mx - 0.85, 0.0, mz - 0.85, mx + 0.85, 0.3, mz + 0.85, M_CONC_D, S, bevel=0.03)
    mast("lmast", mx, mz, 0.3, MAST_H, 1.2, 0.5, 14, M_MAST, S)
    box("mast_cap", mx - 0.3, MAST_H, mz - 0.3, mx + 0.3, MAST_H + 0.04, mz + 0.3, M_GALV, S, bevel=0)
    cyl("mast_beacon", (mx, MAST_H + 0.04, mz), (mx, MAST_H + 0.26, mz), 0.11, M_RED_LENS, S, k=12)
    cyl("mast_beacon_cap", (mx, MAST_H + 0.26, mz), (mx, MAST_H + 0.29, mz), 0.12, M_GUN, S, k=12)
    cyl("mast_spike", (mx, MAST_H + 0.29, mz), (mx, MAST_H + 2.0, mz), 0.035, M_GALV, S, k=6, r1=0.006)
    for dx in (-9.0, 9.0):
        az = mz + math.copysign(5.0, mz)
        ax_ = mx + dx
        d = Vector((mx - ax_, MAST_H - 0.4, mz - az)).normalized()
        foot = Vector((ax_, 0.42, az))
        wire("guy", catenary((mx + math.copysign(0.25, dx), MAST_H - 0.4, mz + math.copysign(0.25, az - mz)), tuple(foot + d * 0.55), 0.6, 12),
             0.018, M_STEEL, S)
        box("guy_anchor", ax_ - 0.4, 0.0, az - 0.4, ax_ + 0.4, 0.42, az + 0.4, M_CONC_D, S, bevel=0.03)
        cyl("guy_turnbuckle", tuple(foot + d * 0.1), tuple(foot + d * 0.55), 0.03, M_GALV, S, k=6)
wire("catenary", catenary((MASTS[0][0], MAST_H - 0.2, MASTS[0][1] + 0.28), (MASTS[1][0], MAST_H - 0.2, MASTS[1][1] - 0.28), 3.0, 20), 0.02, M_STEEL, S)

# ---- floodlight masts: tapered poles, a bar of four lamps aimed down at the pad (glow: LaunchPadRenderer)
TAU = 0.35


def flood_frame(x, z):
    ln = math.hypot(x, z)
    d = Vector((-x / ln, 0.0, -z / ln))
    p = Vector((-d.z, 0.0, d.x))
    f = Vector((d.x * math.cos(TAU), -math.sin(TAU), d.z * math.cos(TAU)))
    h = Vector((d.x * math.sin(TAU), math.cos(TAU), d.z * math.sin(TAU)))
    return d, p, f, h


for x, z in FLOODLIGHTS:
    d, p, f, h = flood_frame(x, z)
    box("flood_base", x - 0.28, 0.0, z - 0.28, x + 0.28, 0.25, z + 0.28, M_CONC_D, S, bevel=0.02)
    box("flood_plate", x - 0.16, 0.25, z - 0.16, x + 0.16, 0.28, z + 0.16, M_GALV, S, bevel=0)
    cyl("flood_pole", (x, 0.28, z), (x, FLOOD_H + 0.14, z), 0.1, M_GALV, S, k=10, r1=0.065)
    obox("flood_handhole", Vector((x, 0.75, z)) + d * 0.095, p, Vector((0, 1, 0)), d, 0.05, 0.12, 0.01, M_GALV, S)
    obox("flood_jbox", Vector((x, 1.3, z)) - d * 0.15, p, Vector((0, 1, 0)), d, 0.12, 0.16, 0.06, M_GREY, S, bevel=0.008)
    c = Vector((x, FLOOD_H + 0.1, z)) + d * 0.12
    obox("flood_bar", c, p, Vector((0, 1, 0)), d, 0.52, 0.03, 0.025, M_GALV, S)
    obox("flood_arm", Vector((x, FLOOD_H + 0.1, z)) + d * 0.06, d, Vector((0, 1, 0)), p, 0.06, 0.025, 0.02, M_GALV, S)
    for t in (-0.35, -0.12, 0.12, 0.35):
        hc = Vector((x, FLOOD_H - 0.06, z)) + p * t + d * 0.16
        obox("flood_head", hc - f * 0.01, p, h, f, 0.095, 0.08, 0.06, M_GUN, S, bevel=0.01)
        obox("flood_lens", hc + f * 0.054, p, h, f, 0.08, 0.065, 0.004, M_LENS, S)
        top_ = hc + h * 0.08
        obox("flood_yoke", Vector((top_.x, (top_.y + FLOOD_H + 0.07) / 2, top_.z)), p, Vector((0, 1, 0)), d, 0.012, (FLOOD_H + 0.07 - top_.y) / 2 + 0.01, 0.012,
             M_GALV, S)
    # bollards on the pad side
    sx, sz = -math.copysign(1, x), -math.copysign(1, z)
    for bx_, bz_ in ((x + sx * 0.6, z), (x, z + sz * 0.6)):
        cyl("bollard", (bx_, 0.1, bz_), (bx_, 0.95, bz_), 0.075, M_YELLOW, S, k=8)
        lathe("bollard_band", (bx_, 0.75, bz_), (0, 1, 0), [(0.0, 0.074), (0.0, 0.079), (0.08, 0.079), (0.08, 0.074)], M_DARK, S, k=8, loop=True)

# ---- propellant farm: the LOX and fuel spheres on legs, ladders, catwalks, platforms, valves; their lines


def sphere_tank(name, x, z, r, mat, legs, phase, ladder_dir, vent=None):
    cy = r + 1.2
    prof = []
    n = 16
    for i in range(n + 1):
        a = -math.pi / 2 + math.pi * i / n
        prof.append((r * math.sin(a), 0.0 if i in (0, n) else r * math.cos(a)))
    lathe(f"{name}_shell", (x, cy, z), (0, 1, 0), prof, mat, S, k=28, smooth=60)
    lathe(f"{name}_ring", (x, cy, z), (0, 1, 0), [(-0.08, r - 0.01), (-0.08, r + 0.05), (0.08, r + 0.05), (0.08, r - 0.01)], M_STEEL, S, k=28, loop=True)
    # legs tangent to the equator, braced with tension rods below the bulge
    R = r + 0.03
    pts = []
    for i in range(legs):
        a = phase + 2 * math.pi * i / legs
        lx, lz = x + math.cos(a) * R, z + math.sin(a) * R
        pts.append((lx, lz))
        cyl(f"{name}_leg", (lx, 0.2, lz), (lx, cy + 0.06, lz), 0.11, M_STEEL, S, k=8)
        box(f"{name}_footing", lx - 0.25, 0.0, lz - 0.25, lx + 0.25, 0.2, lz + 0.25, M_CONC_D, S, bevel=0.015)
    yb = cy - r * 0.5
    for i in range(legs):
        (ax_, az), (bx_, bz) = pts[i], pts[(i + 1) % legs]
        rod(f"{name}_xrod", (ax_, 0.45, az), (bx_, yb, bz), 0.022, M_GALV, S, k=4)
        rod(f"{name}_xrod", (bx_, 0.45, bz), (ax_, yb, az), 0.022, M_GALV, S, k=4)
        beam(f"{name}_strut", (ax_, yb, az), (bx_, yb, bz), 0.07, 0.07, M_STEEL, S)
    # top platform, the catwalk out to the ladder, the ladder down
    yt = cy + r
    lathe(f"{name}_platform", (x, yt, z), (0, 1, 0), [(0.0, 0.0), (0.0, 0.7), (0.06, 0.7), (0.06, 0.0)], M_GRATE, S, k=16)
    dx, dz = ladder_dir
    sx, sz = -dz, dx
    ring = []
    for i in range(13):
        a = 2 * math.pi * i / 12
        ring.append((x + math.cos(a) * 0.66, yt + 0.06, z + math.sin(a) * 0.66))
    for i in range(12):
        a = 2 * math.pi * i / 12
        if math.cos(a) * dx + math.sin(a) * dz > 0.8:
            continue
        rod(f"{name}_ppost", ring[i], (ring[i][0], yt + 1.06, ring[i][2]), 0.02, M_YELLOW, S)
    wire(f"{name}_prail", [(px, yt + 1.06, pz) for px, _, pz in ring], 0.022, M_YELLOW, S, k=6)
    wire(f"{name}_prail", [(px, yt + 0.56, pz) for px, _, pz in ring], 0.015, M_YELLOW, S, k=6)
    reach = r + 0.45
    cw = Vector((x + dx * (0.6 + reach) / 2, yt + 0.03, z + dz * (0.6 + reach) / 2))
    obox(f"{name}_catwalk", cw, Vector((dx, 0, dz)), Vector((0, 1, 0)), Vector((sx, 0, sz)), (reach - 0.6) / 2, 0.03, 0.26, M_GRATE, S)
    for k in (-1, 1):
        a = (x + dx * 0.68 + sx * k * 0.26, yt + 0.06, z + dz * 0.68 + sz * k * 0.26)
        b = (x + dx * reach + sx * k * 0.26, yt + 0.06, z + dz * reach + sz * k * 0.26)
        rail(f"{name}_crail", [a, b], M_YELLOW, S, post=0.9)
    # catwalk supports down onto the shell
    for t in (0.45, 0.8):
        rr = 0.6 + (reach - 0.6) * t
        hy = cy + math.sqrt(max(0.0, r * r - rr * rr))
        for k in (-1, 1):
            rod(f"{name}_cwleg", (x + dx * rr + sx * k * 0.2, hy - 0.05, z + dz * rr + sz * k * 0.2), (x + dx * rr + sx * k * 0.2, yt, z + dz * rr + sz * k * 0.2),
                0.025, M_STEEL, S)
    ladder2(f"{name}_ladder", x + dx * (r + 0.6), z + dz * (r + 0.6), 0.0, yt + 0.06, (-dx, -dz), M_GALV, S, hoops=1.2)
    # relief valves and a gauge on the platform
    for k in (-1, 1):
        cyl(f"{name}_relief", (x + sx * 0.35 * k - dx * 0.3, yt + 0.06, z + sz * 0.35 * k - dz * 0.3),
            (x + sx * 0.35 * k - dx * 0.3, yt + 0.3, z + sz * 0.35 * k - dz * 0.3), 0.045, M_STEEL, S, k=8)
    if vent:
        cyl(f"{name}_vent", (x, yt - 0.05, z), (x, vent, z), 0.075, M_STEEL, S, k=10, caps=False)
        flange(f"{name}_vent_flange", (x, vent - 0.3, z), (0, 1, 0), 0.11, M_STEEL, S, k=10)
        box(f"{name}_vent_dark", x - 0.05, vent - 0.08, z - 0.05, x + 0.05, vent - 0.06, z + 0.05, M_DARK, S, bevel=0)
    # the outlet from the bottom, its valve
    cyl(f"{name}_outlet", (x, cy - r + 0.05, z), (x, 0.65, z), 0.09, M_STEEL, S, k=10)
    return cy


sphere_tank("lox", LOX[0], LOX[1], 2.4, M_WHITE, 8, math.pi / 8, (-1.0, 0.0), vent=7.4)
sphere_tank("fuel", FUEL[0], FUEL[1], 2.0, M_GREY, 6, 0.0, (0.0, -1.0))
lathe("lox_band", (LOX[0], 3.6 + 1.0, LOX[1]), (0, 1, 0), [(0.0, 2.172), (0.0, 2.192), (0.35, 1.994), (0.35, 1.974)], M_BLUE, S, k=28, loop=True)
lathe("fuel_band", (FUEL[0], 3.2 + 0.8, FUEL[1]), (0, 1, 0), [(0.0, 1.823), (0.0, 1.843), (0.3, 1.68), (0.3, 1.66)], M_RED, S, k=28, loop=True)
line("lox_line", [(LOX[0], 0.66, LOX[1]), (LOX[0], 0.5, LOX[1] + 0.3), (LOX[0], 0.5, -5.5), LOX_J], 0.07, M_INSUL, S, bend=0.25)
flange("lox_j", LOX_J, (1, 0, 0), 0.1, M_STEEL, S)
valve("lox_main_valve", (LOX[0], 0.5, LOX[1] + 3.2), (0, 0, 1), 0.07, M_STEEL, S)
line("fuel_line", [(FUEL[0], 0.66, FUEL[1]), (FUEL[0], 0.3, FUEL[1] + 0.3), (FUEL[0], 0.3, -6.2), (FUEL_J[0], 0.3, -6.2), FUEL_J], 0.055, M_GREY, S, bend=0.25)
flange("fuel_j", FUEL_J, (0, 0, 1), 0.085, M_STEEL, S)
valve("fuel_main_valve", (6.5, 0.3, -6.2), (1, 0, 0), 0.055, M_STEEL, S)
for (x, z), rr in ((LOX, 2.4), (FUEL, 2.0)):
    sign("tank_sign", x + 0.9, 1.2, z + rr + 1.3, (0.0, 1.0), S)

# ---- the water tower: four battered legs with struts and rods, the tank, its balcony, riser and ladder
WX, WZ = WATER
LEG_TOP = 14.0
legs = []
for cx, cz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
    b = (WX + cx * 2.0, 0.3, WZ + cz * 2.0)
    t = (WX + cx * 1.6, LEG_TOP, WZ + cz * 1.6)
    legs.append((b, t))
    cyl("wt_leg", b, t, 0.15, M_GREY, S, k=10)
    box("wt_footing", b[0] - 0.4, 0.0, b[2] - 0.4, b[0] + 0.4, 0.3, b[2] + 0.4, M_CONC_D, S, bevel=0.02)


def on_leg(i, y):
    (b, t) = legs[i]
    k = (y - b[1]) / (t[1] - b[1])
    return tuple(b[j] + (t[j] - b[j]) * k for j in range(3))


levels = (0.4, 4.9, 9.4, 13.6)
for li, y in enumerate(levels):
    for i in range(4):
        if li > 0:
            beam("wt_strut", on_leg(i, y), on_leg((i + 1) % 4, y), 0.12, 0.12, M_GREY, S)
        if li < 3:
            rod("wt_rod", on_leg(i, y + 0.1), on_leg((i + 1) % 4, levels[li + 1] - 0.1), 0.025, M_GALV, S, k=4)
            rod("wt_rod", on_leg((i + 1) % 4, y + 0.1), on_leg(i, levels[li + 1] - 0.1), 0.025, M_GALV, S, k=4)
lathe("wt_tank", (WX, LEG_TOP, WZ), (0, 1, 0), [(0.0, 0.0), (0.0, 2.25), (0.1, 2.3), (3.6, 2.3), (3.65, 2.22), (4.6, 0.45), (4.75, 0.0)], M_WHITE, S, k=28,
      smooth=40)
lathe("wt_band", (WX, LEG_TOP, WZ), (0, 1, 0), [(2.55, 2.3), (2.55, 2.312), (3.05, 2.312), (3.05, 2.3)], M_RED, S, k=28, loop=True)
lathe("wt_girder", (WX, LEG_TOP, WZ), (0, 1, 0), [(-0.22, 2.0), (-0.22, 2.32), (0.0, 2.32), (0.0, 2.0)], M_GREY, S, k=28, loop=True)
lathe("wt_balcony", (WX, LEG_TOP, WZ), (0, 1, 0), [(-0.05, 2.3), (-0.05, 2.9), (0.0, 2.9), (0.0, 2.3)], M_GRATE, S, k=28, loop=True)
for i in range(20):
    a = 2 * math.pi * (i + 0.5) / 20
    px, pz = WX + math.cos(a) * 2.86, WZ + math.sin(a) * 2.86
    rod("wt_post", (px, LEG_TOP, pz), (px, LEG_TOP + 1.0, pz), 0.02, M_GREY, S)
    beam("wt_bracket", (WX + math.cos(a) * 2.3, LEG_TOP - 0.45, WZ + math.sin(a) * 2.3), (px, LEG_TOP - 0.05, pz), 0.04, 0.04, M_GREY, S)
for hh, rr in ((1.0, 0.022), (0.5, 0.016)):
    wire("wt_rail", [(WX + math.cos(2 * math.pi * i / 24) * 2.86, LEG_TOP + hh, WZ + math.sin(2 * math.pi * i / 24) * 2.86) for i in range(25)], rr, M_GREY, S,
         k=6)
cyl("wt_finial", (WX, LEG_TOP + 4.7, WZ), (WX, LEG_TOP + 5.05, WZ), 0.12, M_GREY, S, k=10)
lathe("wt_finial_cap", (WX, LEG_TOP + 5.05, WZ), (0, 1, 0), [(0.0, 0.0), (0.0, 0.22), (0.12, 0.0)], M_GREY, S, k=10)
bx("wt_hatch", WX - 0.3, LEG_TOP + 4.3, WZ + 0.5, WX + 0.3, LEG_TOP + 4.42, WZ + 0.9, M_GREY, S, bevel=0.01)
cyl("wt_riser", (WX, 0.3, WZ), (WX, LEG_TOP, WZ), 0.25, M_GREY, S, k=12, caps=False)
for y in (3.0, 7.0, 11.0):
    flange("wt_riser_flange", (WX, y, WZ), (0, 1, 0), 0.31, M_GREY, S, k=12, w=0.05)
box("wt_frostbox", WX - 0.45, 0.0, WZ - 0.45, WX + 0.45, 0.9, WZ + 0.45, M_CONC, S, bevel=0.03)
ladder2("wt_ladder", WX + 2.6, WZ, 0.0, LEG_TOP, (-1.0, 0.0), M_GALV, S, hoops=1.5)
pipe("wt_overflow", [(WX - 2.2, LEG_TOP + 3.3, WZ), (WX - 2.45, LEG_TOP + 3.3, WZ), (WX - 2.45, 0.6, WZ), (WX - 2.7, 0.2, WZ)], 0.05, M_GREY, S, k=8, bend=0.2)
line("water_main", [(WX, 0.25, WZ), (WX, 0.25, 6.0), (WATER_J[0], 0.25, 6.0), WATER_J], 0.1, M_GREY, S, bend=0.3)
valve("water_main_valve", (-6.0, 0.25, 6.0), (1, 0, 0), 0.1, M_STEEL, S)
sign("wt_sign", WX + 2.4, 1.4, WZ - 2.4, (0.7071, -0.7071), S)

# ---- rainbirds: water cannons on the deck, aimed at the table, fed from underneath
for s in (-1, 1):
    rx, rz = 2.4, s * 3.3
    box("rb_pit", rx - 0.4, 0.1, rz - 0.4, rx + 0.4, 0.115, rz + 0.4, M_STEEL, S, bevel=0)
    cyl("rb_riser", (rx, 0.115, rz), (rx, 0.62, rz), 0.15, M_GREY, S, k=12)
    flange("rb_flange", (rx, 0.5, rz), (0, 1, 0), 0.2, M_GREY, S)
    cyl("rb_swivel", (rx, 0.62, rz), (rx, 0.8, rz), 0.11, M_STEEL, S, k=12)
    aim = Vector((-0.7, 0.35, -s * 0.9)).normalized()
    o = Vector((rx, 0.86, rz))
    lathe("rb_barrel", tuple(o), tuple(aim), [(-0.08, 0.0), (-0.08, 0.1), (0.15, 0.1), (0.22, 0.075), (0.85, 0.05), (0.92, 0.06), (0.95, 0.06), (0.95, 0.04)],
          M_YELLOW, S, k=12, caps=False)
    cyl("rb_bore", tuple(o + aim * 0.94), tuple(o + aim * 0.945), 0.04, M_DARK, S, k=10)
    rod("rb_handle", tuple(o + aim * 0.3 + Vector((0, 0.08, 0))), tuple(o + aim * 0.3 - aim.cross(Vector((0, 1, 0))) * 0.3 + Vector((0, 0.1, 0))), 0.015, M_STEEL, S)

# ---- cameras on posts, aimed at the table
for cx, cz in ((6.0, 3.5), (-6.0, -3.0), (5.5, -5.5)):
    box("cam_base", cx - 0.2, 0.0, cz - 0.2, cx + 0.2, 0.12, cz + 0.2, M_CONC_D, S, bevel=0.01)
    cyl("cam_post", (cx, 0.12, cz), (cx, 1.55, cz), 0.045, M_GALV, S, k=8)
    d = Vector((-cx, 2.5, -cz)).normalized()
    side = d.cross(Vector((0, 1, 0))).normalized()
    up = side.cross(d).normalized()
    c = Vector((cx, 1.68, cz))
    obox("cam_housing", c, d, up, side, 0.17, 0.08, 0.08, M_SHELTER, S, bevel=0.01)
    obox("cam_hood", c + up * 0.09 + d * 0.03, d, up, side, 0.21, 0.006, 0.1, M_SHELTER, S)
    cyl("cam_lens", tuple(c + d * 0.17), tuple(c + d * 0.19), 0.05, M_LENS, S, k=10)
    obox("cam_mount", c - up * 0.1, d, up, side, 0.05, 0.03, 0.04, M_GUN, S)

# ---- the control bunker's hatch: a concrete head, steel hatch, vent, railing, steps
box("bunker", 2.6, 0.0, -4.2, 4.2, 0.6, -2.6, M_CONC, S, bevel=0.03)
box("bunker_hatch", 3.0, 0.6, -3.8, 3.8, 0.65, -3.0, M_STEEL, S, bevel=0.01)
for z in (-3.65, -3.15):
    cyl("hatch_hinge", (2.97, 0.63, z - 0.08), (2.97, 0.63, z + 0.08), 0.03, M_GUN, S, k=8)
beam("hatch_handle", (3.75, 0.69, -3.5), (3.75, 0.69, -3.3), 0.025, 0.025, M_GUN, S)
cyl("bunker_vent", (3.85, 0.6, -2.85), (3.85, 1.3, -2.85), 0.08, M_GALV, S, k=10)
lathe("vent_hood", (3.85, 1.3, -2.85), (0, 1, 0), [(0.0, 0.0), (0.0, 0.17), (0.04, 0.17), (0.12, 0.0)], M_GALV, S, k=10)
rail("bunker_rail", [(2.65, 0.6, -2.65), (4.15, 0.6, -2.65), (4.15, 0.6, -4.15), (2.65, 0.6, -4.15)], M_YELLOW, S, h=0.95, post=0.75)
for k in range(3):
    box("bunker_step", 2.6 - 0.3 * (3 - k), 0.0, -3.55, 2.6 - 0.3 * (2 - k), 0.2 * (k + 1), -3.05, M_CONC_D, S, bevel=0.01)
sign("bunker_sign", 3.4, 0.85, -2.58, (0.0, 1.0), S, w=0.5, h=0.32, post=False)

# ---- equipment cabinet beside the tower
box("cab_plinth", -4.35, 0.0, 2.35, -3.45, 0.12, 3.25, M_CONC_D, S, bevel=0.01)
box("cabinet", -4.3, 0.12, 2.4, -3.5, 1.6, 3.2, M_SHELTER, S, bevel=0.015)
box("cab_roof", -4.36, 1.6, 2.34, -3.44, 1.65, 3.26, M_GUN, S, bevel=0.008)
box("cab_door", -3.505, 0.2, 2.48, -3.49, 1.5, 3.12, M_SHELTER, S, bevel=0.005)
box("cab_handle", -3.49, 0.8, 3.0, -3.47, 0.95, 3.04, M_GUN, S, bevel=0)
for k in range(5):
    box("cab_louvre", -4.31, 1.0 + k * 0.08, 2.6, -4.29, 1.04 + k * 0.08, 3.0, M_GUN, S, bevel=0)
sign("cab_sign", -3.48, 1.3, 2.8, (1.0, 0.0), S, w=0.3, h=0.2, post=False)

# ---- cable trench covers from the bunker to the tower, hydrants, perimeter signs
for (ax_, az), (bx_, bz) in (((2.6, -3.0), (-1.2, -3.0)), ((-1.2, -3.0), (-1.2, -1.6)), ((-1.2, -1.6), (-2.45, -1.6))):
    x0, x1 = sorted((ax_, bx_))
    z0, z1 = sorted((az, bz))
    box("trench_cover", x0 - 0.2, 0.1, z0 - 0.2, x1 + 0.2, 0.112, z1 + 0.2, M_GALV, S, bevel=0)
for hx, hz in ((-5.1, -2.0), (5.0, 4.6)):
    lathe("hydrant", (hx, 0.1, hz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.11), (0.05, 0.11), (0.05, 0.08), (0.55, 0.08), (0.6, 0.1), (0.66, 0.1), (0.76, 0.05), (0.8, 0.0)],
          M_RED, S, k=10)
    for dx, dz in ((1, 0), (-1, 0)):
        cyl("hydrant_nozzle", (hx, 0.48, hz), (hx + dx * 0.15, 0.48, hz + dz * 0.15), 0.04, M_RED, S, k=8)
sign("sign", -5.9, 1.2, 1.8, (-1.0, 0.0), S)
sign("sign", 1.8, 1.2, 5.9, (0.0, 1.0), S)
sign("sign", 6.0, 1.2, -1.9, (1.0, 0.0), S)

# ================================================================= preview helpers (not exported)
V = "_preview"
box("pv_block", -0.5, 0.0, -0.5, 0.5, TABLE, 0.5, M_CONC_D, V, bevel=0)
for sx in (-1, 1):
    for sz in (-1, 1):
        box("pv_post", min(sx * 0.3125, sx * 0.5), TABLE, min(sz * 0.3125, sz * 0.5), max(sx * 0.3125, sx * 0.5), 0.75, max(sz * 0.3125, sz * 0.5), M_GUN, V, bevel=0)
cyl("pv_missile", (0, TABLE + 0.3, 0), (0, TABLE + 9.0, 0), 0.6, M_WHITE, V, k=24)
lathe("pv_nose", (0, TABLE + 9.0, 0), (0, 1, 0), [(0.0, 0.6), (1.5, 0.45), (2.6, 0.18), (3.0, 0.0)], M_WHITE, V, k=24)
cyl("pv_nozzle", (0, TABLE, 0), (0, TABLE + 0.3, 0), 0.4, M_GUN, V, k=16, r1=0.5)

PAD_PARTS = ["pad", "arm0", "arm1", "arm2", "arm3", "clamp", "elevator"]
for part in PAD_PARTS + ["site", V]:
    join_part(part)

if "shots" in ARGS:
    import bpy  # noqa: E402
    # the other three clamps and the elevator up the shaft, for the pictures
    src = PARTS["clamp"][0]
    for c in (1, 2, 3):
        ob = src.copy()
        bpy.context.scene.collection.objects.link(ob)
        ob.rotation_euler = (0.0, 0.0, c * math.pi / 2)
    PARTS["elevator"][0].location = mc(0.0, 10.5, 0.0)
    if os.environ.get("NOMISSILE"):
        for ob in PARTS[V]:
            ob.hide_render = True
    outdoor(sun_elevation=34.0, sun_rotation=140.0)
    which = os.environ.get("SHOTS", "all")
    shots = {
        "lp_overview": ((24.0, 15.0, 22.0), (-1.0, 7.0, 0.0), 26),
        "lp_pad": ((6.5, 3.2, 5.5), (-0.5, 1.2, 0.0), 30),
        "lp_tower": ((4.0, 12.0, 7.0), (-3.2, 11.0, 0.0), 26),
        "lp_trench": ((12.5, 3.0, 3.5), (3.0, 0.6, 0.0), 32),
        "lp_tank_farm": ((-1.0, 7.0, -21.0), (-6.0, 4.0, -7.0), 26),
        "lp_back": ((-24.0, 12.0, 14.0), (-4.0, 8.0, 1.0), 28),
        "lp_top": ((4.0, 27.0, 7.0), (-3.0, 23.0, 0.0), 28),
        "lp_stairs": ((-10.0, 6.0, -5.0), (-4.2, 5.0, 0.0), 30),
    }
    for name, (eye, target, lens) in shots.items():
        if which == "all" or name in which.split(","):
            shoot(name, eye, target, lens=lens)
if "export" in ARGS:
    for ob in PARTS[V]:
        ob.hide_render = True
    what = ARGS["export"] or "pad"
    if what == "pad":
        export("launchpad", PAD_PARTS,
               pivots={**{f"arm{i}": (HINGE_X, 0.0, 0.0) for i in range(4)}, "clamp": (CLAMP_PIVOT, CLAMP_Y, CLAMP_PIVOT), "elevator": (TX, 0.0, 0.0)},
               tex=2048, ao=0.8)
    else:
        export("launchpad_site", ["site"], tex=2048, ao=0.8)
