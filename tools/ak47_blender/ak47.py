"""AK-47 built in Blender from script, at real size in metres, after a side photograph of a Type II rifle.

Axes: X right, Y forward (towards the muzzle), Z up. Origin: rear of the receiver on the bore axis.
Separate objects for the parts that move in game: body, carrier (bolt carrier + charging handle),
selector, magazine (with rounds, tracer tips and tape as their own parts).

Needs Blender's Python module (pip install bpy, Python 3.13).

    python ak47.py <out_dir> [--blend] [--shot=side,three_quarter,left,detail,compare]   preview renders
    python ak47.py --export=<dir>     bake the texture and write the game meshes (export.py):
                                      copy ak47.png to textures/entity/, ak47.mesh.gz to meshes/
    python3 trace.py <photo.png> profile.json     outlines traced from the reference photo
    python3 check_game.py <mesh.gz> <png> <out.png>   draw the exported mesh as the mod reads it
"""
import math
import sys

import bpy  # noqa: I001 (bpy must come first: it brings bmesh)
import bmesh
from mathutils import Matrix, Vector

OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "."

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
MM = 0.001
HDRI = bpy.utils.resource_path("LOCAL") + "/datafiles/studiolights/world/interior.exr"

# ------------------------------------------------------------------ materials


DISPLAY = {}


def node_mat(name):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    nt.links.new(bsdf.outputs[0], out.inputs[0])
    return m, nt, bsdf


def edge_bevel(nt, bsdf, radius):
    """Rounded-looking edges in the shading (Cycles bevel node) on top of the real bevels."""
    bev = nt.nodes.new("ShaderNodeBevel")
    bev.samples = 6
    bev.inputs["Radius"].default_value = radius
    nt.links.new(bev.outputs[0], bsdf.inputs["Normal"])
    return bev


def _n(nt, kind, **inputs):
    n = nt.nodes.new(kind)
    for k, v in inputs.items():
        n.inputs[k].default_value = v
    return n


def _math(nt, op, a, b=None, clamp=False):
    n = nt.nodes.new("ShaderNodeMath")
    n.operation = op
    n.use_clamp = clamp
    for i, v in enumerate((a, b)):
        if v is None:
            continue
        if isinstance(v, (int, float)):
            n.inputs[i].default_value = v
        else:
            nt.links.new(v, n.inputs[i])
    return n.outputs[0]


def _mix_rgb(nt, fac, a, b, blend="MIX"):
    n = nt.nodes.new("ShaderNodeMix")
    n.data_type = "RGBA"
    n.blend_type = blend
    for sock, v in (("Factor", fac), ("A", a), ("B", b)):
        if isinstance(v, tuple):
            n.inputs[sock].default_value = (*v, 1) if len(v) == 3 else v
        elif isinstance(v, (int, float)):
            n.inputs[sock].default_value = v
        else:
            nt.links.new(v, n.inputs[sock])
    return n.outputs["Result"]


def _mix_f(nt, fac, a, b):
    n = nt.nodes.new("ShaderNodeMix")
    n.data_type = "FLOAT"
    for sock, v in (("Factor", fac), ("A", a), ("B", b)):
        if isinstance(v, (int, float)):
            n.inputs[sock].default_value = v
        else:
            nt.links.new(v, n.inputs[sock])
    return n.outputs["Result"]


def _edge_mask(nt, radius, lo, hi):
    """1 on worn edges: where the rounded (bevel) normal turns away from the true one."""
    bev = _n(nt, "ShaderNodeBevel", Radius=radius)
    bev.samples = 8
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    dot = nt.nodes.new("ShaderNodeVectorMath")
    dot.operation = "DOT_PRODUCT"
    nt.links.new(bev.outputs[0], dot.inputs[0])
    nt.links.new(geo.outputs["Normal"], dot.inputs[1])
    mr = _n(nt, "ShaderNodeMapRange", **{"From Min": lo, "From Max": hi})
    nt.links.new(_math(nt, "SUBTRACT", 1.0, dot.outputs["Value"]), mr.inputs[0])
    return mr.outputs[0], bev.outputs[0]


def blued(name="blued", base=(0.03, 0.033, 0.037), rough=0.36, wear=0.95):
    """Old blued steel: blue-black, worn through to grey bare steel on every edge, grey patina in patches,
    polished where hands rub it, fine scratches along the gun."""
    m, nt, bsdf = node_mat(name)
    bsdf.inputs["Metallic"].default_value = 1.0
    tc = nt.nodes.new("ShaderNodeTexCoord")
    # edge wear, broken up by noise
    edge, bev_n = _edge_mask(nt, 0.0012, 0.01, 0.08)
    splotch = _n(nt, "ShaderNodeTexNoise", Scale=180.0, Detail=10.0, Roughness=0.65)
    nt.links.new(tc.outputs["Object"], splotch.inputs["Vector"])
    sp = _n(nt, "ShaderNodeMapRange", **{"From Min": 0.38, "From Max": 0.62})
    nt.links.new(splotch.outputs["Fac"], sp.inputs[0])
    worn = _math(nt, "MULTIPLY", _math(nt, "MULTIPLY", edge, sp.outputs[0]), wear * 1.6, clamp=True)
    # patches where the bluing has faded to grey
    patina = _n(nt, "ShaderNodeTexNoise", Scale=22.0, Detail=6.0, Roughness=0.6)
    nt.links.new(tc.outputs["Object"], patina.inputs["Vector"])
    pm = _n(nt, "ShaderNodeMapRange", **{"From Min": 0.45, "From Max": 0.75, "To Max": 0.7 * wear})
    nt.links.new(patina.outputs["Fac"], pm.inputs[0])
    col = _mix_rgb(nt, pm.outputs[0], base, (0.075, 0.08, 0.085))
    col = _mix_rgb(nt, worn, col, (0.36, 0.36, 0.35))
    nt.links.new(col, bsdf.inputs["Base Color"])
    DISPLAY[m.name] = (col, 2.6)
    # scratches: noise stretched along the rifle
    mp = _n(nt, "ShaderNodeMapping", Scale=(1.0, 0.04, 1.0))
    nt.links.new(tc.outputs["Object"], mp.inputs[0])
    scr = _n(nt, "ShaderNodeTexNoise", Scale=900.0, Detail=4.0)
    nt.links.new(mp.outputs[0], scr.inputs["Vector"])
    fine = _n(nt, "ShaderNodeTexNoise", Scale=2500.0, Detail=6.0)
    nt.links.new(tc.outputs["Object"], fine.inputs["Vector"])
    rr = _n(nt, "ShaderNodeMapRange", **{"To Min": rough - 0.1, "To Max": rough + 0.14})
    nt.links.new(scr.outputs["Fac"], rr.inputs[0])
    rough_out = _mix_f(nt, worn, rr.outputs[0], 0.2)
    rough_out = _mix_f(nt, pm.outputs[0], rough_out, rough + 0.12)
    nt.links.new(rough_out, bsdf.inputs["Roughness"])
    bump = _n(nt, "ShaderNodeBump", Strength=0.05, Distance=0.0002)
    h = _math(nt, "ADD", scr.outputs["Fac"], _math(nt, "MULTIPLY", fine.outputs["Fac"], 0.4))
    nt.links.new(h, bump.inputs["Height"])
    nt.links.new(bev_n, bump.inputs["Normal"])
    nt.links.new(bump.outputs[0], bsdf.inputs["Normal"])
    return m


def wood(name, dark=(0.028, 0.0065, 0.003), light=(0.105, 0.024, 0.0095), scale=(1.0, 0.035, 1.0), grain="Y", tilt=0.0):
    """Old shellacked birch: fine straight grain along the part, figure and darker streaks, worn and
    handled, the finish semi-matte with grime in the corners and dents all over."""
    m, nt, bsdf = node_mat(name)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = _n(nt, "ShaderNodeMapping", Scale=scale, Rotation=(tilt, 0, 0))
    nt.links.new(tc.outputs["Object"], mp.inputs[0])
    # long fibres: noise stretched along the grain
    fib = _n(nt, "ShaderNodeTexNoise", Scale=260.0, Detail=12.0, Roughness=0.7, Distortion=0.4)
    nt.links.new(mp.outputs[0], fib.inputs["Vector"])
    # growth rings cutting through the surface: bands, gently warped
    warp = _n(nt, "ShaderNodeTexNoise", Scale=9.0, Detail=3.0)
    nt.links.new(mp.outputs[0], warp.inputs["Vector"])
    vw = nt.nodes.new("ShaderNodeVectorMath")
    vw.operation = "ADD"
    nt.links.new(mp.outputs[0], vw.inputs[0])
    sc = nt.nodes.new("ShaderNodeVectorMath")
    sc.operation = "SCALE"
    sc.inputs["Scale"].default_value = 0.035
    nt.links.new(warp.outputs["Color"], sc.inputs[0])
    nt.links.new(sc.outputs[0], vw.inputs[1])
    rings = nt.nodes.new("ShaderNodeTexWave")
    rings.wave_type = "RINGS"
    rings.rings_direction = grain
    rings.wave_profile = "SAW"
    for k, v in (("Scale", 26.0), ("Distortion", 2.0), ("Detail", 2.0)):
        rings.inputs[k].default_value = v
    nt.links.new(vw.outputs[0], rings.inputs["Vector"])
    rg = _n(nt, "ShaderNodeMapRange", **{"From Min": 0.55, "From Max": 1.0})
    nt.links.new(rings.outputs["Fac"], rg.inputs[0])
    # big slow colour variation over the piece
    big = _n(nt, "ShaderNodeTexNoise", Scale=6.0, Detail=2.0)
    nt.links.new(tc.outputs["Object"], big.inputs["Vector"])
    t = _math(nt, "ADD", _math(nt, "MULTIPLY", fib.outputs["Fac"], 0.55), _math(nt, "MULTIPLY", big.outputs["Fac"], 0.45))
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (*dark, 1)
    ramp.color_ramp.elements[1].color = (*light, 1)
    ramp.color_ramp.elements[0].position = 0.36
    ramp.color_ramp.elements[1].position = 0.64
    nt.links.new(t, ramp.inputs[0])
    col = _mix_rgb(nt, _math(nt, "MULTIPLY", rg.outputs[0], 0.75), ramp.outputs[0], (dark[0] * 0.6, dark[1] * 0.6, dark[2] * 0.6))
    # grime in the hollows, the edges rubbed lighter
    ao = _n(nt, "ShaderNodeAmbientOcclusion", Distance=0.012)
    nt.links.new(col, ao.inputs["Color"])
    col = _mix_rgb(nt, 0.55, col, ao.outputs["AO"], "MULTIPLY")
    edge, bev_n = _edge_mask(nt, 0.002, 0.02, 0.12)
    col = _mix_rgb(nt, _math(nt, "MULTIPLY", edge, 0.5), col, (light[0] * 1.35, light[1] * 1.4, light[2] * 1.4))
    nt.links.new(col, bsdf.inputs["Base Color"])
    DISPLAY[m.name] = (col, 1.35)
    # semi-matte old finish, patchy where worn
    rr = _n(nt, "ShaderNodeMapRange", **{"To Min": 0.32, "To Max": 0.6})
    nt.links.new(big.outputs["Fac"], rr.inputs[0])
    nt.links.new(rr.outputs[0], bsdf.inputs["Roughness"])
    bsdf.inputs["Coat Weight"].default_value = 0.18
    bsdf.inputs["Coat Roughness"].default_value = 0.25
    # dents, dings and the grain in relief
    dents = nt.nodes.new("ShaderNodeTexVoronoi")
    dents.inputs["Scale"].default_value = 140.0
    nt.links.new(tc.outputs["Object"], dents.inputs["Vector"])
    dm = _n(nt, "ShaderNodeMapRange", **{"From Min": 0.0, "From Max": 0.06, "To Min": -1.0, "To Max": 0.0})
    nt.links.new(dents.outputs["Distance"], dm.inputs[0])
    h = _math(nt, "ADD", _math(nt, "MULTIPLY", fib.outputs["Fac"], 0.6), _math(nt, "MULTIPLY", dm.outputs[0], 0.5))
    bump = _n(nt, "ShaderNodeBump", Strength=0.12, Distance=0.0003)
    nt.links.new(h, bump.inputs["Height"])
    nt.links.new(bev_n, bump.inputs["Normal"])
    nt.links.new(bump.outputs[0], bsdf.inputs["Normal"])
    return m


def flat(name, color, rough=0.5, metal=0.0):
    m, nt, bsdf = node_mat(name)
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    DISPLAY[m.name] = (tuple(color), 1.6 if metal else 1.0)
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Metallic"].default_value = metal
    return m


M_BLUED = blued()
M_SLAB = blued("magazine", base=(0.03, 0.032, 0.034), rough=0.45, wear=0.8)
M_WORN = blued("worn", base=(0.09, 0.09, 0.09), rough=0.3, wear=1.0)
M_WOOD = wood("stock")
M_STOCK = wood("buttstock", tilt=0.2)
M_GRIP = wood("grip", dark=(0.022, 0.005, 0.0025), light=(0.085, 0.019, 0.008), scale=(1.0, 1.0, 0.035), grain="Z", tilt=-0.35)
M_BLACK = flat("void", (0.004, 0.004, 0.004), 0.9)
M_BRASS = flat("case", (0.32, 0.28, 0.2), 0.35, 1.0)
M_COPPER = flat("bullet", (0.55, 0.3, 0.17), 0.28, 1.0)
M_SLING = flat("sling", (0.16, 0.15, 0.09), 0.9)

# ------------------------------------------------------------------ geometry helpers

PARTS = {}


def new_obj(name, bm, mat, part="body", smooth_angle=35.0, bevel=0.0, seg=3, subsurf=0):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    scene.collection.objects.link(ob)
    me.materials.append(mat)
    for p in me.polygons:
        p.use_smooth = True
    me.set_sharp_from_angle(angle=math.radians(smooth_angle))
    if bevel > 0:
        b = ob.modifiers.new("bevel", "BEVEL")
        b.width = bevel
        b.segments = seg
        b.limit_method = "ANGLE"
        b.angle_limit = math.radians(30)
        b.harden_normals = True
        b.use_clamp_overlap = True
    if subsurf:
        s = ob.modifiers.new("sub", "SUBSURF")
        s.levels = subsurf
        s.render_levels = subsurf
    PARTS.setdefault(part, []).append(ob)
    return ob


def prism(name, profile, x0, x1, mat, part="body", bevel=0.0012, seg=3, **kw):
    """A side profile [(y, z), ...] (counter-clockwise seen from +X) extruded from x0 to x1."""
    bm = bmesh.new()
    a = [bm.verts.new((x0, y, z)) for y, z in profile]
    b = [bm.verts.new((x1, y, z)) for y, z in profile]
    n = len(profile)
    bm.faces.new(list(reversed(a)))
    bm.faces.new(b)
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((a[i], a[j], b[j], b[i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel, seg=seg, **kw)


def box(name, x0, y0, z0, x1, y1, z1, mat, part="body", bevel=0.0008, **kw):
    return prism(name, [(y0, z0), (y1, z0), (y1, z1), (y0, z1)], x0, x1, mat, part, bevel=bevel, **kw)


def ring(c, u, v, a, b, n=2.6, k=32):
    """A superellipse ring: centre c, axes u (half size a) and v (half size b), exponent n."""
    pts = []
    for i in range(k):
        t = 2 * math.pi * i / k
        ct, st = math.cos(t), math.sin(t)
        x = a * math.copysign(abs(ct) ** (2 / n), ct)
        y = b * math.copysign(abs(st) ** (2 / n), st)
        pts.append(c + u * x + v * y)
    return pts


def loft(name, rings, mat, part="body", caps=True, **kw):
    """Bridge a list of rings (same vertex count) into a closed solid."""
    bm = bmesh.new()
    vs = [[bm.verts.new(p) for p in r] for r in rings]
    k = len(rings[0])
    for r0, r1 in zip(vs, vs[1:]):
        for i in range(k):
            j = (i + 1) % k
            bm.faces.new((r0[i], r0[j], r1[j], r1[i]))
    if caps:
        bm.faces.new(list(reversed(vs[0])))
        bm.faces.new(vs[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, **kw)


X = Vector((1, 0, 0))
Y = Vector((0, 1, 0))
Z = Vector((0, 0, 1))


def yloft(name, stations, mat, part="body", n=2.6, k=32, **kw):
    """Loft along Y: stations (y, z_top, z_bottom, half_width[, exponent])."""
    rings = []
    for s in stations:
        y, top, bot, hw = s[:4]
        e = s[4] if len(s) > 4 else n
        rings.append(ring(Vector((0, y, (top + bot) / 2)), X, Z, hw, (top - bot) / 2, e, k))
    return loft(name, rings, mat, part, **kw)


def lathe(name, origin, axis, profile, mat, part="body", k=32, **kw):
    """Revolve [(t, r), ...] around axis from origin. r=0 closes the end."""
    axis = Vector(axis).normalized()
    u = axis.orthogonal().normalized()
    v = axis.cross(u)
    o = Vector(origin)
    bm = bmesh.new()
    rings = []
    for t, r in profile:
        c = o + axis * t
        if r == 0:
            rings.append([bm.verts.new(c)])
        else:
            rings.append([bm.verts.new(c + (u * math.cos(2 * math.pi * i / k) + v * math.sin(2 * math.pi * i / k)) * r) for i in range(k)])
    for r0, r1 in zip(rings, rings[1:]):
        if len(r0) == 1 and len(r1) == 1:
            continue
        if len(r0) == 1:
            for i in range(k):
                bm.faces.new((r0[0], r1[i], r1[(i + 1) % k]))
        elif len(r1) == 1:
            for i in range(k):
                bm.faces.new((r0[i], r1[0], r0[(i + 1) % k]))
        else:
            for i in range(k):
                j = (i + 1) % k
                bm.faces.new((r0[i], r0[j], r1[j], r1[i]))
    if len(rings[0]) > 1:
        bm.faces.new(list(reversed(rings[0])))
    if len(rings[-1]) > 1:
        bm.faces.new(rings[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, **kw)


def pin(name, x, y, z, r=0.0025, side=1, mat=None, part="body", h=0.0012):
    return lathe(name, (x - side * 0.001, y, z), (side, 0, 0), [(0, r), (0.001 + h * 0.4, r), (0.001 + h, r * 0.6), (0.001 + h * 1.15, 0)],
                 mat or M_WORN, part, k=20)


def cut(target, cutter):
    bev = [(md.width, md.segments) for md in target.modifiers if md.type == "BEVEL"]
    for md in list(target.modifiers):
        target.modifiers.remove(md)
    m = target.modifiers.new("cut", "BOOLEAN")
    m.operation = "DIFFERENCE"
    m.solver = "EXACT"
    m.object = cutter
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(target.evaluated_get(dg))
    target.modifiers.remove(m)
    old = target.data
    target.data = me
    bpy.data.meshes.remove(old)
    me.materials.clear()
    me.materials.append(M_BLUED)
    for p in me.polygons:
        p.use_smooth = True
    me.set_sharp_from_angle(angle=math.radians(35))
    for w, sg in bev:
        b = target.modifiers.new("bevel", "BEVEL")
        b.width, b.segments = w, sg
        b.limit_method = "ANGLE"
        b.angle_limit = math.radians(30)
        b.harden_normals = True
        b.use_clamp_overlap = True
    bpy.data.objects.remove(cutter)
    PARTS["cutters"].remove(cutter)


def cutter_box(name, x0, y0, z0, x1, y1, z1):
    return box(name, x0, y0, z0, x1, y1, z1, M_BLACK, part="cutters", bevel=0)


def arc_points(start, dir_yz, curvature, length, n):
    """Points along a circular arc in the YZ plane."""
    ang = math.atan2(dir_yz[1], dir_yz[0])  # angle of (y, z)
    p = Vector(start)
    step = length / n
    out = []
    for _ in range(n + 1):
        out.append(p.copy())
        p = p + Vector((0, math.cos(ang), math.sin(ang))) * step
        ang += curvature * step
    return out


# ------------------------------------------------------------------ the rifle (all in metres)
# Laid out from a side photograph of an AK-47 (Wikimedia Commons, "AK-47 type II noBG.png"):
# outlines of the magazine, pistol grip, stock and lower handguard traced from it (profile.json),
# everything else measured off it. y: forward from the rear of the receiver, z: up from the bore.

import json
import os

PROFILE = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "profile.json")))


def smooth(points, n=4):
    """Moving average over a traced pixel outline."""
    out = []
    for i in range(len(points)):
        win = points[max(0, i - n):i + n + 1]
        out.append(tuple(sum(p[k] for p in win) / len(win) for k in range(len(points[0]))))
    return out


W = 15.5 * MM  # half width of the milled receiver

# ===== milled receiver
rec = prism("receiver", [
    (0.000, -0.040), (0.075, -0.040), (0.080, -0.037), (0.141, -0.037), (0.145, -0.043), (0.215, -0.043),
    (0.222, -0.031), (0.265, -0.028), (0.265, 0.012), (0.000, 0.012)], -W, W, M_BLUED, bevel=0.0012)
for sx in (-1, 1):
    # the long lightening cut, milled 1.6 mm deep, ahead of and above the magazine well
    cut(rec, prism(f"cut_light{sx}", [(0.181, -0.0245), (0.252, -0.0245), (0.258, -0.019), (0.258, -0.014), (0.252, -0.0085),
                                      (0.181, -0.0085), (0.176, -0.014), (0.176, -0.019)],
                   sx * (W - 0.0016), sx * (W + 0.01), M_BLACK, part="cutters", bevel=0))
# ejection port and the charging handle slot on the right
cut(rec, cutter_box("cut_port", W - 0.006, 0.128, -0.004, W + 0.01, 0.212, 0.0125))
cut(rec, cutter_box("cut_slot", W - 0.004, 0.02, 0.002, W + 0.01, 0.128, 0.0085))
box("port_inside", -W + 0.004, 0.125, -0.012, W - 0.005, 0.215, 0.002, M_BLACK, bevel=0)
for sx in (-1, 1):
    pin(f"pin_trigger{sx}", sx * W, 0.1418, -0.0237, r=0.0028, side=sx)
    pin(f"pin_hammer{sx}", sx * W, 0.1275, -0.0237, r=0.0028, side=sx)
    pin(f"pin_sear{sx}", sx * W, 0.088, -0.031, r=0.0022, side=sx)
    pin(f"pin_rear{sx}", sx * W, 0.064, -0.0104, r=0.0022, side=sx)
    pin(f"pin_trunnion{sx}", sx * W, 0.258, -0.004, r=0.0026, side=sx)

# ===== dust cover: rounded sheet steel, dropping at the back to the recoil spring guide button
yloft("dust_cover", [(0.005, 0.0125, 0.0095, W - 0.001, 3.0), (0.009, 0.0145, 0.0095, W, 3.0), (0.016, 0.0185, 0.0095, W, 3.0),
                     (0.024, 0.029, 0.0095, W, 3.0), (0.031, 0.0335, 0.0095, W, 3.0),
                     (0.236, 0.0345, 0.0095, W, 3.0), (0.242, 0.034, 0.0095, W - 0.0008, 3.0)], M_BLUED, k=48, bevel=0.0006, seg=2)
# the stiffening crease along each side of the cover
for sx in (-1, 1):
    box(f"cover_crease{sx}", sx * W - 0.0004, 0.03, 0.0205, sx * W + 0.0004, 0.232, 0.0222, M_BLUED, bevel=0.0003)
lathe("spring_button", (0, 0.005, 0.011), (0, -1, 0), [(0, 0.0052), (0.004, 0.0052), (0.006, 0.0038), (0.0065, 0)], M_WORN, k=24)

# ===== rear sight block on the barrel ahead of the receiver: tangent leaf, range slider, notch
yloft("sight_block", [(0.239, 0.0345, 0.006, 0.0128, 4), (0.252, 0.040, 0.006, 0.0128, 4), (0.298, 0.040, 0.006, 0.0128, 4),
                      (0.307, 0.033, 0.006, 0.0118, 4)], M_BLUED, k=40, bevel=0.0006)
prism("sight_leaf", [(0.243, 0.0395), (0.296, 0.0395), (0.296, 0.0415), (0.246, 0.0435)], -0.0085, 0.0085, M_BLUED, bevel=0.0004)
for sx in (-1, 1):
    prism(f"notch{sx}", [(0.242, 0.043), (0.249, 0.043), (0.249, 0.047), (0.242, 0.047)], 0.0032 if sx > 0 else -0.0085,
          0.0085 if sx > 0 else -0.0032, M_BLUED, bevel=0.0003)
box("slider", -0.011, 0.268, 0.041, 0.011, 0.278, 0.046, M_WORN, bevel=0.0006)
for sx in (-1, 1):
    lathe(f"slider_button{sx}", (sx * 0.011, 0.273, 0.0435), (sx, 0, 0), [(0, 0.0032), (0.003, 0.0032), (0.0034, 0)], M_WORN, k=16)
for i in range(1, 9):
    box(f"range_mark{i}", -0.0087, 0.249 + i * 0.0052, 0.0423 - i * 0.0002, -0.0084, 0.2497 + i * 0.0052, 0.0427 - i * 0.0002, M_WORN, bevel=0)
# gas tube lock lever on the right
prism("tube_lever", [(0.296, 0.026), (0.309, 0.031), (0.318, 0.016), (0.312, 0.012)], 0.0122, 0.0152, M_WORN, bevel=0.0006)

# ===== trigger guard, trigger, magazine catch
prism("trigger_guard", [
    (0.075, -0.039), (0.081, -0.039), (0.086, -0.066), (0.134, -0.067), (0.137, -0.039), (0.143, -0.039),
    (0.141, -0.071), (0.08, -0.07)], -0.0055, 0.0055, M_BLUED, bevel=0.0012)
prism("trigger", [(0.1, -0.039), (0.106, -0.039), (0.102, -0.05), (0.094, -0.06), (0.087, -0.059), (0.096, -0.05)],
      -0.0032, 0.0032, M_BLUED, bevel=0.0008)
prism("mag_catch", [(0.083, -0.039), (0.093, -0.039), (0.093, -0.048), (0.089, -0.053), (0.083, -0.052), (0.087, -0.046)],
      -0.0065, 0.0065, M_BLUED, bevel=0.0008)

# ===== fire selector: the long lever on the right, pivoting at the rear, blocking the handle when safe
SELECTOR_PIVOT = Vector((W + 0.0015, 0.034, -0.012))
prism("selector", [(0.024, -0.016), (0.034, -0.019), (0.07, -0.006), (0.15, 0.002), (0.166, 0.002), (0.166, 0.0105), (0.15, 0.0105),
                   (0.07, 0.003), (0.034, -0.005), (0.024, -0.008)], W + 0.0002, W + 0.0024, M_BLUED, part="selector", bevel=0.0006)
prism("selector_tab", [(0.157, -0.002), (0.166, -0.002), (0.166, 0.0105), (0.157, 0.0105)], W + 0.0024, W + 0.0068, M_BLUED,
      part="selector", bevel=0.0008)
pin("selector_axis", W + 0.0024, 0.032, -0.012, r=0.0048, side=1, part="selector", mat=M_BLUED, h=0.002)

# ===== bolt carrier with the charging handle (moves back with every shot)
box("carrier_body", -0.009, 0.125, -0.004, W - 0.004, 0.214, 0.0085, M_WORN, part="carrier", bevel=0.0008)
box("bolt_lug", 0.0, 0.18, -0.01, 0.009, 0.208, -0.002, M_WORN, part="carrier", bevel=0.0006)
prism("handle_stem", [(0.106, 0.002), (0.126, 0.002), (0.126, 0.0085), (0.106, 0.0085)], W - 0.004, W + 0.004, M_WORN, part="carrier",
      bevel=0.0006)
lathe("handle_knob", (W + 0.002, 0.115, 0.005), (1, 0, 0),
      [(0, 0.0042), (0.006, 0.0045), (0.012, 0.0062), (0.0185, 0.0058), (0.0205, 0.0038), (0.021, 0)], M_WORN, part="carrier", k=24)

# ===== pistol grip, traced: horizontal sections between its front and back outline
gf = smooth(PROFILE["grip_front"], 5)
gb = smooth(PROFILE["grip_back"], 5)
(yf, z), (yb, _) = gf[0], gb[0]
rings = [ring(Vector((0, (yf + yb) / 2, -0.036)), X, Y, 0.0118, (yf - yb) / 2, 2.4, 40)]
step = 4
for i in range(0, len(gf), step):
    (yf, z), (yb, _) = gf[i], gb[i]
    f = i / (len(gf) - 1)
    hw = 0.0118 + 0.0016 * math.sin(f * math.pi)
    rings.append(ring(Vector((0, (yf + yb) / 2, z)), X, Y, hw, max(0.004, (yf - yb) / 2), 2.4, 40))
# round the bottom off
(yf, z), (yb, _) = gf[-1], gb[-1]
for k, (dz, s) in enumerate(((0.002, 0.85), (0.0035, 0.55))):
    rings.append(ring(Vector((0, (yf + yb) / 2, z - dz)), X, Y, 0.0128 * s, (yf - yb) / 2 * s, 2.4, 40))
loft("grip", rings, M_GRIP, subsurf=1)

# ===== stock socket on the receiver, and the wooden stock traced from the photo
yloft("stock_socket", [(0.004, 0.0085, -0.041, W, 3.4), (-0.006, 0.0045, -0.0435, W - 0.0003, 3.4), (-0.021, 0.0012, -0.0495, 0.0146, 3.4)],
      M_BLUED, k=40, bevel=0.0006)
st = smooth([s for s in PROFILE["stock"] if s[0] < -0.004], 2)
srings = []
for i, (y, top, bot) in enumerate(st):
    f = max(0.0, (-0.018 - y) / 0.222)
    hw = 0.0136 + 0.0064 * min(1.0, f * 1.4)
    if bot - top > -0.006:  # the very end of the slanted butt: too thin to be a section
        continue
    srings.append(ring(Vector((0, y, (top + bot) / 2)), X, Z, hw, (top - bot) / 2, 2.7 + 0.4 * f, 44))
loft("stock", srings, M_STOCK, subsurf=1)
# the steel butt plate over the slanted end
heel, toe = Vector((0, -0.2386, -0.0304)), Vector((0, -0.2195, -0.1358))
along = (heel - toe).normalized()
out_n = Vector((0, -along.z, along.y)) * -1  # pointing backwards out of the butt
if out_n.y > 0:
    out_n = -out_n
mid = (heel + toe) / 2
plate = [ring(mid - out_n * 0.003, X, along, 0.0195, (heel - toe).length / 2 + 0.001, 3.6, 44),
         ring(mid + out_n * 0.0012, X, along, 0.0195, (heel - toe).length / 2 + 0.001, 3.6, 44),
         ring(mid + out_n * 0.002, X, along, 0.0185, (heel - toe).length / 2 - 0.0005, 3.6, 44)]
loft("butt_plate", plate, M_BLUED, bevel=0.0004)
door = mid + along * 0.012
loft("trap_door", [ring(door + out_n * 0.0019, X, along, 0.0105, 0.021, 3, 32), ring(door + out_n * 0.0029, X, along, 0.0098, 0.0202, 3, 32)],
     M_WORN)
for s in (0.042, -0.042):
    p = mid + along * s + out_n * 0.002
    lathe(f"butt_screw{s}", p, out_n, [(0, 0.0034), (0.0008, 0.003), (0.0011, 0)], M_WORN, k=20)
# the sling swivel plate on the left of the stock
box("swivel_plate", -0.0212, -0.068, -0.040, -0.018, -0.034, -0.016, M_BLUED, bevel=0.0007)
for y in (-0.06, -0.042):
    lathe(f"swivel_screw{y}", (-0.0212, y, -0.028), (-1, 0, 0), [(0, 0.0028), (0.0008, 0.0024), (0.0011, 0)], M_WORN, k=16)
bpy.ops.mesh.primitive_torus_add(major_radius=0.0098, minor_radius=0.0016, major_segments=40, minor_segments=12,
                                 location=(-0.0232, -0.051, -0.028), rotation=(0, math.radians(90), 0))
_sw = bpy.context.active_object
_sw.name = "swivel_ring"
_sw.data.materials.append(M_WORN)
_sw.data.shade_smooth()
PARTS["body"].append(_sw)

# ===== lower handguard, traced: deeper at the back where it meets the receiver
lb = smooth(PROFILE["lower_bottom"], 2)
lrings = []
for y, bot in sorted(lb):
    lrings.append(ring(Vector((0, y, (0.0105 + bot) / 2)), X, Z, 0.0205 + 0.0012 * (0.418 - y) / 0.15, (0.0105 - bot) / 2, 3.0, 44))
lrings.insert(0, ring(Vector((0, lb[-1][0] - 0.003, (0.0105 + lb[-1][1]) / 2)), X, Z, 0.0215, (0.0105 - lb[-1][1]) / 2 - 0.002, 3.0, 44))
loft("lower_handguard", lrings, M_WOOD, subsurf=1)
# the ferrule at its front end
yloft("ferrule", [(0.418, 0.0115, -0.0215, 0.0205, 3.6), (0.433, 0.0115, -0.0098, 0.0165, 3.2)], M_BLUED, k=44, bevel=0.0005)

# ===== gas tube and the upper handguard over it, vent holes ahead of it
GT_Z = 0.024
lathe("gas_tube", (0, 0.305, GT_Z), (0, 1, 0), [(0, 0.0093), (0.183, 0.0093)], M_BLUED, k=40)
yloft("upper_handguard", [(0.307, 0.0395, 0.0135, 0.0148, 2.2), (0.316, 0.0415, 0.0128, 0.0156, 2.2), (0.405, 0.0395, 0.0128, 0.0156, 2.2),
                          (0.416, 0.0375, 0.0135, 0.0148, 2.2)], M_WOOD, k=44, subsurf=1)
yloft("upper_cap", [(0.416, 0.0362, 0.0142, 0.0132, 2.4), (0.421, 0.0352, 0.0145, 0.0122, 2.4)], M_BLUED, k=40)
for i, y in enumerate((0.441, 0.452, 0.463)):
    for sx in (-1, 1):
        box(f"vent{i}{sx}", sx * 0.0094 - 0.0012, y, GT_Z + 0.0005, sx * 0.0094 + 0.0012, y + 0.0062, GT_Z + 0.0045, M_BLACK, bevel=0.0003)

# ===== barrel
lathe("barrel", (0, 0.26, 0), (0, 1, 0), [(0, 0.0118), (0.012, 0.0118), (0.016, 0.0082), (0.357, 0.0074)], M_BLUED, k=40)

# ===== the 45-degree gas block of the early rifles, with its front sling loop
prism("gas_block", [(0.487, -0.0165), (0.523, -0.0165), (0.523, 0.0085), (0.497, 0.0334), (0.487, 0.0334)], -0.0108, 0.0108, M_BLUED,
      bevel=0.0012)
lathe("gas_plug", (0, 0.487, GT_Z), (0, 1, 0), [(0, 0.0098), (0.003, 0.0098), (0.003, 0.0091)], M_BLUED, k=32)
lathe("front_loop", (-0.0126, 0.508, -0.0172), (0, 1, 0), [(-0.0016, 0.0068), (0.0016, 0.0068), (0.0016, 0.0044), (-0.0016, 0.0044)], M_WORN,
      k=24)

# ===== front sight base: post between the protective ears, bayonet lug, cleaning rod stop
yloft("sight_base", [(0.586, 0.0105, -0.0152, 0.0106, 4), (0.617, 0.0105, -0.0152, 0.0106, 4)], M_BLUED, k=40, bevel=0.0007)
prism("sight_tower", [(0.589, 0.0095), (0.615, 0.0095), (0.613, 0.022), (0.595, 0.022)], -0.0082, 0.0082, M_BLUED, bevel=0.0008)
for sx in (-1, 1):
    prism(f"ear{sx}", [(0.591, 0.0095), (0.614, 0.0095), (0.6125, 0.0496), (0.6005, 0.0496), (0.5935, 0.031)],
          0.0062 if sx > 0 else -0.0102, 0.0102 if sx > 0 else -0.0062, M_BLUED, bevel=0.0009)
lathe("front_post", (0, 0.6055, 0.021), (0, 0, 1), [(0, 0.002), (0.0235, 0.0016), (0.0245, 0)], M_WORN, k=16)
box("bayonet_lug", -0.0035, 0.6, -0.021, 0.0035, 0.616, -0.0145, M_BLUED, bevel=0.0008)
# cleaning rod under the barrel, its notched head at the front
lathe("cleaning_rod", (0, 0.43, -0.0133), (0, 1, 0), [(0, 0.0024), (0.156, 0.0024), (0.156, 0.0031), (0.168, 0.0031), (0.17, 0)], M_WORN, k=16)

# ===== the muzzle nut over the thread, knurled at the back
lathe("muzzle_nut", (0, 0.617, 0), (0, 1, 0),
      [(0, 0.0074), (0.001, 0.0079), (0.005, 0.0079), (0.0055, 0.0075), (0.011, 0.0074), (0.0128, 0.0058), (0.0128, 0.0042),
       (0.0118, 0.0042), (0.0118, 0.0)], M_BLUED, k=40)
for i in range(16):
    a = 2 * math.pi * i / 16
    p = Vector((math.cos(a) * 0.0079, 0.6195, math.sin(a) * 0.0079))
    box(f"knurl{i}", p.x - 0.0005, p.y - 0.0022, p.z - 0.0005, p.x + 0.0005, p.y + 0.0022, p.z + 0.0005, M_BLUED, bevel=0.0002)
lathe("bore", (0, 0.6305, 0), (0, -1, 0), [(0, 0.0039), (0.03, 0.0039), (0.0301, 0)], M_BLACK, k=24)

# ===== the 30-round ribbed slab-sided steel magazine, traced from the photo (flat sides, so an extruded outline)
mf = smooth(PROFILE["mag_front"], 4)[::3]
mb = smooth(PROFILE["mag_back"], 4)[::3]
outline = [(y, z) for y, z in mf] + [(y, z) for y, z in reversed(mb)]
# reach up into the magazine well
outline = [(mf[0][0], 0.0 - 0.032)] + outline + [(mb[0][0], -0.032)]
MW = 0.0132
prism("magazine", outline, -MW, MW, M_SLAB, part="magazine", bevel=0.0012, seg=3)
# the pressed ribs running down each side, following the curve
for f in (0.2, 0.42, 0.64, 0.86):
    pts = []
    for (yf, zf), (yb, zb) in list(zip(mf, mb))[9:-7]:
        pts.append(Vector((0, yf + (yb - yf) * f, zf + (zb - zf) * f)))
    for sx in (-1, 1):
        bm = bmesh.new()
        rows = []
        for i, p in enumerate(pts):
            t = (pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]).normalized()
            nrm = Vector((0, -t.z, t.y))
            w = 0.0016 * min(1.0, i / 3, (len(pts) - 1 - i) / 3)
            rows.append([bm.verts.new(p + nrm * w + Vector((sx * MW, 0, 0))),
                         bm.verts.new(p + Vector((sx * (MW + 0.0009 * (w / 0.0016 if w else 0)), 0, 0))),
                         bm.verts.new(p - nrm * w + Vector((sx * MW, 0, 0)))])
        for r0, r1 in zip(rows, rows[1:]):
            bm.faces.new((r0[0], r0[1], r1[1], r1[0]))
            bm.faces.new((r0[1], r0[2], r1[2], r1[1]))
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        new_obj(f"mag_rib{f}{sx}", bm, M_SLAB, part="magazine", smooth_angle=80)
# floor plate tab, front lug and the rear catch lug
box("mag_front_lug", -0.008, 0.211, -0.036, 0.008, 0.219, -0.031, M_SLAB, part="magazine", bevel=0.0005)
box("mag_rear_lug", -0.007, 0.142, -0.038, 0.007, 0.149, -0.032, M_SLAB, part="magazine", bevel=0.0005)
for sx in (-1, 1):
    box(f"feed_lip{sx}", sx * 0.0128 if sx < 0 else 0.0078, 0.15, -0.035, sx * 0.0078 if sx < 0 else 0.0128, 0.205, -0.03, M_SLAB,
        part="magazine", bevel=0.0005)
for k, (x, z) in enumerate(((0.0035, -0.029), (-0.0035, -0.033))):
    lathe(f"case{k}", (x, 0.154, z), (0, 1, -0.06), [(0, 0.0055), (0.002, 0.0055), (0.003, 0.0048), (0.0035, 0.0056), (0.033, 0.0052),
                                                    (0.037, 0.0043), (0.039, 0.0041)], M_BRASS, part="rounds", k=16)
    ax = Vector((0, 1, -0.06)).normalized()
    lathe(f"bullet{k}", (x, 0.193, z - 0.0023), ax, [(0, 0.0039), (0.008, 0.0039), (0.016, 0.0031), (0.0165, 0.0029)], M_COPPER,
          part="rounds", k=16)
    # the nose on its own: painted green or red on tracers
    tip = Vector((x, 0.193, z - 0.0023)) + ax * 0.0165
    lathe(f"tip{k}", tip, ax, [(0, 0.0029), (0.0045, 0.0012), (0.006, 0)], M_COPPER, part="tips", k=16)
# the strip of coloured tape round a magazine loaded with tracers
band_f = [p for p in mf if -0.1 < p[1] < -0.086]
band_b = [p for p in mb if -0.1 < p[1] < -0.086]
prism("band", [(y - 0.0008, z) for y, z in band_f] + [(y + 0.0008, z) for y, z in reversed(band_b)], -MW - 0.0008, MW + 0.0008, M_COPPER,
      part="band", bevel=0)


# ------------------------------------------------------------------ scene, light, camera


def setup_render(res=(1800, 900), samples=96):
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 8
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.look = "AgX - Base Contrast"
    world = bpy.data.worlds.new("w")
    scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    bg = nt.nodes["Background"]
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image = bpy.data.images.load(HDRI)
    nt.links.new(env.outputs[0], bg.inputs[0])
    bg.inputs[1].default_value = 0.6
    # what the camera sees behind the gun: a dark studio sweep, not the HDRI
    lp = nt.nodes.new("ShaderNodeLightPath")
    mix = nt.nodes.new("ShaderNodeMixShader")
    plain = nt.nodes.new("ShaderNodeBackground")
    plain.inputs[0].default_value = (0.018, 0.019, 0.021, 1)
    nt.links.new(lp.outputs["Is Camera Ray"], mix.inputs[0])
    nt.links.new(bg.outputs[0], mix.inputs[1])
    nt.links.new(plain.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], nt.nodes["World Output"].inputs[0])


def area(name, loc, target, size, power, color=(1, 1, 1)):
    l = bpy.data.lights.new(name, "AREA")
    l.size = size
    l.energy = power
    l.color = color
    ob = bpy.data.objects.new(name, l)
    scene.collection.objects.link(ob)
    ob.location = loc
    d = Vector(target) - Vector(loc)
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    return ob


CENTRE = Vector((0, 0.19, -0.03))


def lights():
    area("key", (0.9, 0.6, 0.9), CENTRE, 0.9, 90, (1.0, 0.95, 0.88))
    area("fill", (-0.9, -0.1, 0.35), CENTRE, 1.2, 25, (0.85, 0.9, 1.0))
    area("rim", (0.1, 1.4, 0.6), CENTRE, 0.5, 70, (1.0, 1.0, 1.0))
    area("rim2", (-0.3, -1.0, 0.5), CENTRE, 0.5, 40, (1.0, 0.97, 0.92))
    # floor catching a soft shadow
    bm = bmesh.new()
    bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=60)
    fl = new_obj("floor", bm, flat("floor", (0.02, 0.02, 0.022), 0.75), part="floor")
    fl.location = (0, 0, -0.32)
    fl.is_shadow_catcher = True


def camera(name, loc, target, lens=50):
    cam = bpy.data.cameras.new(name)
    cam.lens = lens
    cam.dof.use_dof = False
    ob = bpy.data.objects.new(name, cam)
    scene.collection.objects.link(ob)
    ob.location = loc
    d = Vector(target) - Vector(loc)
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    return ob


def shoot(name, loc, target, lens=50, res=(1800, 900), samples=64, dof=None):
    import os
    if os.environ.get("QUICK"):
        res, samples = (900, 450), 16
    cam = camera(name, loc, target, lens)
    if dof:
        cam.data.dof.use_dof = True
        cam.data.dof.focus_distance = (Vector(target) - Vector(loc)).length
        cam.data.dof.aperture_fstop = dof
    scene.camera = cam
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.cycles.samples = samples
    scene.render.filepath = f"{OUT}/{name}.png"
    bpy.ops.render.render(write_still=True)
    print("rendered", scene.render.filepath, flush=True)


if __name__ == "__main__":
    if any(a.startswith("--export") for a in sys.argv):
        exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "export.py")).read())
        sys.exit(0)
    setup_render()
    lights()
    if "--blend" in sys.argv:
        bpy.ops.wm.save_as_mainfile(filepath=f"{OUT}/ak47.blend")
    shots = [a for a in sys.argv if a.startswith("--shot=")]
    want = shots[0][7:].split(",") if shots else ["side", "three_quarter", "left", "detail"]
    if "side" in want:
        shoot("side", (1.55, 0.21, -0.01), (0, 0.21, -0.03), lens=50)
    if "three_quarter" in want:
        shoot("three_quarter", (0.95, 1.15, 0.38), (0, 0.17, -0.03), lens=50)
    if "left" in want:
        shoot("left", (-1.2, -0.35, 0.22), (0, 0.2, -0.03), lens=50)
    if "compare" in want:
        # orthographic from the left at the photo's scale: 1280 px = 1280 * MM_PX mm
        cam = camera("compare", (-1.0, 0.0, 0.0), (0.0, 0.0, 0.0))
        cam.data.type = "ORTHO"
        cam.data.ortho_scale = 1280 * (870.0 / 1174) / 1000
        # the photo's centre (640, 248) in rifle coordinates
        cam.location = (-1.0, (902 - 640) * (870.0 / 1174) / 1000, (158 - 248) * (870.0 / 1174) / 1000)
        scene.camera = cam
        scene.render.resolution_x, scene.render.resolution_y = 1280, 496
        scene.cycles.samples = 16 if __import__("os").environ.get("QUICK") else 64
        scene.render.filepath = f"{OUT}/compare.png"
        bpy.ops.render.render(write_still=True)
    if "detail" in want:
        shoot("detail", (0.32, 0.36, 0.06), (0.0, 0.14, -0.02), lens=55, dof=4.0)
