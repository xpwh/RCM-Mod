"""Blender kit for the mod's big structures (launch pad, silos, earth station, submarine): world-scale materials
that age - grime in the corners, worn paint on the edges, rust streaks, stained concrete - steel sections and
the pieces every site has (lattice masts of angle iron, railings, ladders, pipes, cables, bolts), an outdoor
studio for previews, and the export into the game (structure.mesh.gz + base colour and surface maps, the same
format the guns use: AkItemRenderer.load, GunShading).

All placing is done in the mod's block space: x, y up, z, metres = blocks, origin at the bottom centre of the
structure's block. In Blender that is (x, -z, y): Z up.

    from structkit import *
"""
import math
import os
import sys

import bpy  # noqa: I001
import bmesh
from mathutils import Matrix, Vector

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "."
ARGS = {a.split("=", 1)[0][2:]: (a.split("=", 1)[1] if "=" in a else "") for a in sys.argv if a.startswith("--")}

DISPLAY = {}  # material -> (base colour socket or rgb, gain)   (what the bake writes as base colour)
SURFACE = {}  # material -> (metallic, roughness, clear coat)    (socket or value each)
PARTS = {}    # part name -> [objects]


def mc(x, y, z):
    """Block space (x, y up, z) to Blender (x, -z, y)."""
    return Vector((x, -z, y))


def mcv(v):
    return mc(v[0], v[1], v[2])


# ================================================================== materials


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


def _mix(nt, fac, a, b, blend="MIX"):
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


def _mixf(nt, fac, a, b):
    n = nt.nodes.new("ShaderNodeMix")
    n.data_type = "FLOAT"
    for sock, v in (("Factor", fac), ("A", a), ("B", b)):
        if isinstance(v, (int, float)):
            n.inputs[sock].default_value = v
        else:
            nt.links.new(v, n.inputs[sock])
    return n.outputs["Result"]


def _ramp(nt, fac, lo, hi):
    """fac squeezed to 0 below lo and 1 above hi (smooth)."""
    r = nt.nodes.new("ShaderNodeMapRange")
    r.interpolation_type = "SMOOTHSTEP"
    nt.links.new(fac, r.inputs["Value"])
    r.inputs["From Min"].default_value = lo
    r.inputs["From Max"].default_value = hi
    return r.outputs["Result"]


def _noise(nt, scale, detail=6.0, rough=0.55, coord=None, w=None):
    n = nt.nodes.new("ShaderNodeTexNoise")
    n.noise_dimensions = "3D"
    n.inputs["Scale"].default_value = scale
    n.inputs["Detail"].default_value = detail
    n.inputs["Roughness"].default_value = rough
    if coord is not None:
        nt.links.new(coord, n.inputs["Vector"])
    return n.outputs["Fac"]


def _coords(nt):
    """World position (Blender): the same dirt pattern runs across parts that touch."""
    return nt.nodes.new("ShaderNodeNewGeometry").outputs["Position"]


def _height(nt):
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), sep.inputs[0])
    return sep.outputs["Z"]


def _ao(nt, dist, inside=False):
    a = nt.nodes.new("ShaderNodeAmbientOcclusion")
    a.inputs["Distance"].default_value = dist
    a.inside = inside
    a.samples = 16
    return a.outputs["AO"]


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


def _finish(m, nt, bsdf, col, metal, rough, coat=0.0):
    nt.links.new(col, bsdf.inputs["Base Color"])
    for key, v in (("Metallic", metal), ("Roughness", rough)):
        if isinstance(v, (int, float)):
            bsdf.inputs[key].default_value = v
        else:
            nt.links.new(v, bsdf.inputs[key])
    DISPLAY[m.name] = (col, 1.0)
    SURFACE[m.name] = (metal, rough, coat)
    return m


def weathered(nt, col, grime=0.5, wear=0.3, streaks=0.4, rust=0.0, wear_col=(0.32, 0.31, 0.3), scale=1.0):
    """Ages a base colour socket: dirt in the corners and low down, streaks run down from the edges, paint worn
    through on the edges (to wear_col), rust blooms."""
    pos = _coords(nt)
    # dirt in crevices and corners (where the occlusion is)
    crevice = _math(nt, "SUBTRACT", 1.0, _ao(nt, 0.35 * scale))
    crevice = _ramp(nt, crevice, 0.05, 0.6)
    blotch = _noise(nt, 1.8 / scale, 8, 0.6, pos)
    dirt = _math(nt, "MULTIPLY", crevice, _mixf(nt, blotch, 0.4, 1.3))
    # splash dirt near the ground
    low = _ramp(nt, _height(nt), 0.9, 0.0)
    dirt = _math(nt, "ADD", dirt, _math(nt, "MULTIPLY", low, 0.55), clamp=True)
    dirt = _math(nt, "MULTIPLY", dirt, grime, clamp=True)
    col = _mix(nt, dirt, col, (0.11, 0.095, 0.075), "MULTIPLY") if grime > 0 else col
    col = _mix(nt, _math(nt, "MULTIPLY", dirt, 0.6), col, (0.16, 0.14, 0.11))
    # streaks running down: noise stretched along the vertical
    if streaks > 0:
        mp = nt.nodes.new("ShaderNodeMapping")
        mp.inputs["Scale"].default_value = (7.0 / scale, 7.0 / scale, 0.35 / scale)
        nt.links.new(pos, mp.inputs["Vector"])
        st = _noise(nt, 2.0, 4, 0.5, mp.outputs[0])
        st = _ramp(nt, st, 0.55, 0.8)
        col = _mix(nt, _math(nt, "MULTIPLY", st, streaks * 0.45), col, (0.13, 0.12, 0.1))
    # worn edges: where the surface bulges out (occlusion seen from inside is low)
    if wear > 0:
        edge = _math(nt, "SUBTRACT", 1.0, _ao(nt, 0.05 * scale, inside=True))
        edge = _ramp(nt, edge, 0.25, 0.7)
        chip = _noise(nt, 24.0 / scale, 6, 0.7, pos)
        edge = _math(nt, "MULTIPLY", edge, _ramp(nt, chip, 0.42, 0.62))
        col = _mix(nt, _math(nt, "MULTIPLY", edge, wear), col, wear_col)
    if rust > 0:
        r = _ramp(nt, _noise(nt, 3.0 / scale, 10, 0.7, pos), 0.58, 0.72)
        r = _math(nt, "MULTIPLY", r, _math(nt, "ADD", crevice, 0.25))
        col = _mix(nt, _math(nt, "MULTIPLY", r, rust, clamp=True), col, (0.2, 0.075, 0.025))
    return col


def paint(name, color, rough=0.55, metal=0.0, grime=0.5, wear=0.35, streaks=0.4, rust=0.0, wear_col=(0.3, 0.29, 0.28), vary=0.08):
    """Painted steel (or anything painted): the colour a little uneven, then aged."""
    m, nt, bsdf = node_mat(name)
    base = _mix(nt, _math(nt, "MULTIPLY", _noise(nt, 0.8, 4, 0.5, _coords(nt)), vary * 2), tuple(color), tuple(c * 0.8 for c in color))
    col = weathered(nt, base, grime, wear, streaks, rust, wear_col)
    r = _mixf(nt, _noise(nt, 6.0, 4, 0.5, _coords(nt)), rough - 0.06, rough + 0.08)
    return _finish(m, nt, bsdf, col, metal, r)


def concrete(name, color=(0.36, 0.355, 0.335), grime=0.7, stains=0.6):
    m, nt, bsdf = node_mat(name)
    pos = _coords(nt)
    big = _noise(nt, 0.35, 6, 0.6, pos)
    fine = _noise(nt, 40.0, 4, 0.6, pos)
    col = _mix(nt, big, tuple(c * 0.86 for c in color), tuple(c * 1.06 for c in color))
    col = _mix(nt, _math(nt, "MULTIPLY", _ramp(nt, fine, 0.35, 0.75), 0.25), col, tuple(c * 0.75 for c in color))
    # dark water stains and lighter efflorescence
    st = _ramp(nt, _noise(nt, 0.9, 8, 0.7, pos), 0.56, 0.7)
    col = _mix(nt, _math(nt, "MULTIPLY", st, stains), col, tuple(c * 0.6 for c in color))
    col = weathered(nt, col, grime, 0.0, 0.25, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, _mixf(nt, fine, 0.82, 0.95))


def galvanized(name, color=(0.56, 0.58, 0.59), rough=0.5, grime=0.35):
    """Hot-dip galvanised steel: dull metal, mottled with the zinc's spangle, darker where it weathers."""
    m, nt, bsdf = node_mat(name)
    pos = _coords(nt)
    sp = _noise(nt, 30.0, 3, 0.6, pos)
    col = _mix(nt, sp, tuple(c * 0.82 for c in color), tuple(color))
    col = weathered(nt, col, grime, 0.0, 0.3, 0.05)
    return _finish(m, nt, bsdf, col, 1.0, _mixf(nt, sp, rough - 0.08, rough + 0.12))


def plain(name, color, rough=0.6, metal=0.0):
    m, nt, bsdf = node_mat(name)
    rgb = nt.nodes.new("ShaderNodeRGB")
    rgb.outputs[0].default_value = (*color, 1)
    return _finish(m, nt, bsdf, rgb.outputs[0], metal, rough)


def rubber(name="rubber", color=(0.025, 0.025, 0.027)):
    m, nt, bsdf = node_mat(name)
    col = _mix(nt, _noise(nt, 10.0, 4, 0.5, _coords(nt)), tuple(color), tuple(c * 1.6 for c in color))
    col = weathered(nt, col, 0.3, 0.0, 0.0, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, 0.85)


def hazard(name="hazard", width=0.25, grime=0.6):
    """Yellow and black diagonal stripes (in world space, so they run on across parts)."""
    m, nt, bsdf = node_mat(name)
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), sep.inputs[0])
    s = _math(nt, "ADD", _math(nt, "ADD", sep.outputs["X"], sep.outputs["Y"]), _math(nt, "MULTIPLY", sep.outputs["Z"], 0.6))
    s = _math(nt, "FRACT", _math(nt, "DIVIDE", s, width * 2))
    band = _math(nt, "GREATER_THAN", s, 0.5)
    col = _mix(nt, band, (0.62, 0.42, 0.02), (0.02, 0.02, 0.02))
    col = weathered(nt, col, grime, 0.5, 0.3, 0.0, (0.25, 0.24, 0.22))
    return _finish(m, nt, bsdf, col, 0.0, 0.6)


def grating(name="grating", color=(0.2, 0.2, 0.19), pitch=0.04):
    """Steel bar grating seen from above: bright bars, dark gaps between, scuffed."""
    m, nt, bsdf = node_mat(name)
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), sep.inputs[0])
    bx = _math(nt, "LESS_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", sep.outputs["X"], pitch)), 0.28)
    by = _math(nt, "LESS_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", sep.outputs["Y"], pitch * 2.5)), 0.18)
    bar = _math(nt, "MAXIMUM", bx, by)
    col = _mix(nt, bar, (0.015, 0.015, 0.015), tuple(color))
    col = weathered(nt, col, 0.4, 0.0, 0.0, 0.15)
    return _finish(m, nt, bsdf, col, _mixf(nt, bar, 0.0, 0.85), 0.55)


def glass(name="glass", color=(0.02, 0.03, 0.035)):
    m, nt, bsdf = node_mat(name)
    rgb = nt.nodes.new("ShaderNodeRGB")
    rgb.outputs[0].default_value = (*color, 1)
    return _finish(m, nt, bsdf, rgb.outputs[0], 0.0, 0.08, 0.6)


def stripes(name, a, b, period, axis="Z", grime=0.45, wear=0.3):
    """Two colours in bands along an axis (a mast's red and white, in block metres)."""
    m, nt, bsdf = node_mat(name)
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(_coords(nt), sep.inputs[0])
    s = _math(nt, "GREATER_THAN", _math(nt, "FRACT", _math(nt, "DIVIDE", sep.outputs[axis], period * 2)), 0.5)
    col = _mix(nt, s, tuple(a), tuple(b))
    col = weathered(nt, col, grime, wear, 0.4, 0.0)
    return _finish(m, nt, bsdf, col, 0.0, 0.5)


# ================================================================== geometry (block space)


def new_obj(name, bm, mat, part, bevel=0.0, seg=1, smooth=35.0):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    scene.collection.objects.link(ob)
    me.materials.append(mat)
    for p in me.polygons:
        p.use_smooth = True
    me.set_sharp_from_angle(angle=math.radians(smooth))
    if bevel > 0:
        b = ob.modifiers.new("bevel", "BEVEL")
        b.width = bevel
        b.segments = seg
        b.limit_method = "ANGLE"
        b.angle_limit = math.radians(30)
        b.harden_normals = True
        b.use_clamp_overlap = True
    PARTS.setdefault(part, []).append(ob)
    return ob


def _cuboid(bm, c):
    """Eight corners: 0-3 round one end, 4-7 round the other in the same order."""
    v = [bm.verts.new(p) for p in c]
    for f in ((0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)):
        bm.faces.new([v[i] for i in f])


def box(name, x0, y0, z0, x1, y1, z1, mat, part, bevel=0.015):
    """An axis-aligned box in block space."""
    bm = bmesh.new()
    c = [mc(x, y, z) for y in (y0, y1) for (x, z) in ((x0, z0), (x1, z0), (x1, z1), (x0, z1))]
    _cuboid(bm, c)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def _frame(a, b):
    """An orthonormal frame along a -> b (Blender space): axis, side, up."""
    axis = (b - a).normalized()
    side = axis.cross(Vector((0, 0, 1)))
    if side.length < 1e-4:
        side = Vector((1, 0, 0))
    side.normalize()
    up = side.cross(axis).normalized()
    return axis, side, up


def section(name, a, b, profile, mat, part, roll=0.0, bevel=0.0):
    """A straight member from a to b (block space) with a cross-section [(s, u), ...] (metres, counter-clockwise)."""
    A, B = mcv(a), mcv(b)
    axis, side, up = _frame(A, B)
    if roll:
        q = Matrix.Rotation(roll, 3, axis)
        side, up = q @ side, q @ up
    bm = bmesh.new()
    r0 = [bm.verts.new(A + side * s + up * u) for s, u in profile]
    r1 = [bm.verts.new(B + side * s + up * u) for s, u in profile]
    n = len(profile)
    bm.faces.new(list(reversed(r0)))
    bm.faces.new(r1)
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((r0[i], r0[j], r1[j], r1[i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def beam(name, a, b, w, h, mat, part, roll=0.0):
    return section(name, a, b, [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)], mat, part, roll)


def angle_iron(name, a, b, size, thick, mat, part, roll=0.0):
    """An L-section (equal angle) member, its heel along the line a-b."""
    s, t = size, thick
    prof = [(0, 0), (s, 0), (s, t), (t, t), (t, s), (0, s)]
    return section(name, a, b, [(x - s * 0.3, y - s * 0.3) for x, y in prof], mat, part, roll)


def ibeam(name, a, b, w, h, tf, tw, mat, part, roll=0.0):
    prof = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, -h / 2 + tf), (tw / 2, -h / 2 + tf), (tw / 2, h / 2 - tf), (w / 2, h / 2 - tf),
            (w / 2, h / 2), (-w / 2, h / 2), (-w / 2, h / 2 - tf), (-tw / 2, h / 2 - tf), (-tw / 2, -h / 2 + tf), (-w / 2, -h / 2 + tf)]
    return section(name, a, b, prof, mat, part, roll)


def cyl(name, a, b, r, mat, part, k=16, caps=True, bevel=0.0, r1=None):
    """A cylinder (or a cone, r1 the far radius) from a to b."""
    A, B = mcv(a), mcv(b)
    axis, side, up = _frame(A, B)
    r1 = r if r1 is None else r1
    bm = bmesh.new()
    c0 = [bm.verts.new(A + (side * math.cos(2 * math.pi * i / k) + up * math.sin(2 * math.pi * i / k)) * r) for i in range(k)]
    c1 = [bm.verts.new(B + (side * math.cos(2 * math.pi * i / k) + up * math.sin(2 * math.pi * i / k)) * r1) for i in range(k)]
    for i in range(k):
        j = (i + 1) % k
        bm.faces.new((c0[i], c0[j], c1[j], c1[i]))
    if caps:
        bm.faces.new(list(reversed(c0)))
        bm.faces.new(c1)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def lathe(name, origin, axis, profile, mat, part, k=32, bevel=0.0, smooth=50.0, caps=True, loop=False):
    """Revolve [(t, r), ...] (along the axis, radius) about axis through origin (block space). r = 0 closes; caps
    close the ends with flat discs; loop joins the last point back to the first (a ring's section)."""
    if loop:
        profile = list(profile) + [profile[0]]
        caps = False
    o = mcv(origin)
    ax = mcv(axis).normalized()
    u = ax.orthogonal().normalized()
    v = ax.cross(u)
    bm = bmesh.new()
    rings = []
    for t, r in profile:
        c = o + ax * t
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
    if loop:
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    if caps and len(rings[0]) > 1:
        bm.faces.new(list(reversed(rings[0])))
    if caps and len(rings[-1]) > 1:
        bm.faces.new(rings[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel, smooth=smooth)


def pipe(name, points, r, mat, part, k=10, bend=0.0):
    """A pipe or cable along a polyline (block space), the corners bent round with radius `bend`."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = r
    cu.bevel_resolution = max(1, k // 4 - 1)
    cu.use_fill_caps = True
    sp = cu.splines.new("POLY")
    pts = [mcv(p) for p in points]
    if bend > 0 and len(pts) > 2:
        out = [pts[0]]
        for i in range(1, len(pts) - 1):
            a, b, c = pts[i - 1], pts[i], pts[i + 1]
            d0 = (a - b)
            d1 = (c - b)
            e = min(bend, d0.length * 0.45, d1.length * 0.45)
            p0 = b + d0.normalized() * e
            p1 = b + d1.normalized() * e
            for s in range(5):
                t = s / 4
                out.append((1 - t) ** 2 * p0 + 2 * (1 - t) * t * b + t * t * p1)
        out.append(pts[-1])
        pts = out
    sp.points.add(len(pts) - 1)
    for i, p in enumerate(pts):
        sp.points[i].co = (*p, 1)
    ob = bpy.data.objects.new(name, cu)
    scene.collection.objects.link(ob)
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
    bpy.data.objects.remove(ob)
    bpy.data.curves.remove(cu)
    ob = bpy.data.objects.new(name, me)
    scene.collection.objects.link(ob)
    me.materials.clear()
    me.materials.append(mat)
    for p in me.polygons:
        p.use_smooth = True
    PARTS.setdefault(part, []).append(ob)
    return ob


def catenary(a, b, sag, n=16):
    """Points of a wire hung from a to b, sagging `sag` metres in the middle."""
    out = []
    for i in range(n + 1):
        t = i / n
        p = [a[k] + (b[k] - a[k]) * t for k in range(3)]
        p[1] -= sag * 4 * t * (1 - t)
        out.append(tuple(p))
    return out


def bolts(name, centre, axis, radius, count, r, h, mat, part, phase=0.0):
    """A ring of hex bolt heads on a flange."""
    o = mcv(centre)
    ax = mcv(axis).normalized()
    u = ax.orthogonal().normalized()
    v = ax.cross(u)
    bm = bmesh.new()
    for i in range(count):
        a = phase + 2 * math.pi * i / count
        c = o + (u * math.cos(a) + v * math.sin(a)) * radius
        r0 = [bm.verts.new(c + (u * math.cos(2 * math.pi * j / 6) + v * math.sin(2 * math.pi * j / 6)) * r) for j in range(6)]
        r1 = [bm.verts.new(p + ax * h) for p in [x.co for x in r0]]
        bm.faces.new(list(reversed(r0)))
        bm.faces.new(r1)
        for j in range(6):
            k = (j + 1) % 6
            bm.faces.new((r0[j], r0[k], r1[k], r1[j]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, smooth=20)


def railing(name, points, mat, part, h=1.05, post=1.2, closed=False, toe=True):
    """A handrail along a path of points at floor level: posts, top and knee rails, a toe board."""
    pts = list(points) + ([points[0]] if closed else [])
    for i in range(len(pts) - 1):
        a, b = Vector(pts[i]), Vector(pts[i + 1])
        d = b - a
        n = max(1, round(d.length / post))
        for k in range(n + (1 if i == len(pts) - 2 and not closed else 0)):
            p = a + d * (k / n)
            cyl(f"{name}_post", (p.x, p.y, p.z), (p.x, p.y + h, p.z), 0.022, mat, part, k=8)
        for y, r in ((h, 0.025), (h * 0.5, 0.018)):
            cyl(f"{name}_rail", (a.x, a.y + y, a.z), (b.x, b.y + y, b.z), r, mat, part, k=8)
        if toe:
            side = Vector((-d.z, 0, d.x)).normalized() * 0.004
            section(f"{name}_toe", (a.x, a.y + 0.06, a.z), (b.x, b.y + 0.06, b.z),
                    [(-0.004, -0.05), (0.004, -0.05), (0.004, 0.05), (-0.004, 0.05)], mat, part)


def ladder(name, x, z, y0, y1, facing, mat, part, width=0.45, cage=True):
    """A fixed steel ladder at (x, z) from y0 to y1, rungs every 0.3 m, the climber's side towards `facing` (dx, dz)."""
    fx, fz = facing
    sx, sz = -fz, fx
    for s in (-1, 1):
        cyl(f"{name}_rail", (x + sx * s * width / 2, y0, z + sz * s * width / 2), (x + sx * s * width / 2, y1 + 1.0, z + sz * s * width / 2), 0.025,
            mat, part, k=8)
    y = y0 + 0.3
    while y < y1 + 0.01:
        cyl(f"{name}_rung", (x - sx * width / 2, y, z - sz * width / 2), (x + sx * width / 2, y, z + sz * width / 2), 0.014, mat, part, k=6)
        y += 0.3
    if cage and y1 - y0 > 3.0:
        y = y0 + 2.3
        while y < y1 + 0.9:
            pts = []
            for i in range(9):
                a = math.pi * i / 8
                pts.append((x + sx * math.cos(a) * 0.36 + fx * (0.05 + math.sin(a) * 0.62), y, z + sz * math.cos(a) * 0.36 + fz * (0.05 + math.sin(a) * 0.62)))
            pipe(f"{name}_hoop", pts, 0.012, mat, part, k=6)
            y += 0.9
        for i in range(5):
            a = math.pi * i / 4
            p = (x + sx * math.cos(a) * 0.36 + fx * (0.05 + math.sin(a) * 0.62), z + sz * math.cos(a) * 0.36 + fz * (0.05 + math.sin(a) * 0.62))
            cyl(f"{name}_cagebar", (p[0], y0 + 2.3, p[1]), (p[0], y - 0.9, p[1]), 0.01, mat, part, k=6)


def lattice(name, x, z, y0, y1, w0, w1, mat, part, panels=None, leg=0.09, brace=0.05, horizontals=True):
    """A square lattice mast of angle iron, w0 wide at y0 tapering to w1 at y1: corner legs, X-bracing in every
    panel and a horizontal at each panel joint."""
    h = y1 - y0
    panels = panels or max(2, round(h / max(w0, w1, 1.0)))

    def corner(cx, cz, y):
        t = (y - y0) / h
        w = (w0 + (w1 - w0) * t) / 2
        return (x + cx * w, y, z + cz * w)

    cs = [(-1, -1), (1, -1), (1, 1), (-1, 1)]
    for i, (cx, cz) in enumerate(cs):
        angle_iron(f"{name}_leg", corner(cx, cz, y0), corner(cx, cz, y1), leg, leg * 0.12, mat, part, roll=math.pi / 4 + i * math.pi / 2)
    for p in range(panels):
        ya = y0 + h * p / panels
        yb = y0 + h * (p + 1) / panels
        for i in range(4):
            c0, c1 = cs[i], cs[(i + 1) % 4]
            angle_iron(f"{name}_br", corner(*c0, ya), corner(*c1, yb), brace, brace * 0.14, mat, part)
            angle_iron(f"{name}_br", corner(*c1, ya), corner(*c0, yb), brace, brace * 0.14, mat, part)
            if horizontals:
                angle_iron(f"{name}_hz", corner(*c0, yb), corner(*c1, yb), brace, brace * 0.14, mat, part)


def join_part(part):
    """All the objects of a part as one (faster to export, one unwrap)."""
    obs = PARTS.get(part, [])
    if len(obs) < 2:
        return
    dg = bpy.context.evaluated_depsgraph_get()
    bm = bmesh.new()
    mats = []
    for ob in obs:
        me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
        me.transform(ob.matrix_world)
        remap = {}
        for i, m in enumerate(me.materials):
            if m not in mats:
                mats.append(m)
            remap[i] = mats.index(m)
        for p in me.polygons:
            p.material_index = remap.get(p.material_index, 0)
        n0 = len(bm.faces)
        bm.from_mesh(me)
        bpy.data.meshes.remove(me)
    me = bpy.data.meshes.new(part)
    bm.to_mesh(me)
    bm.free()
    for m in mats:
        me.materials.append(m)
    for ob in obs:
        bpy.data.objects.remove(ob)
    ob = bpy.data.objects.new(part, me)
    scene.collection.objects.link(ob)
    PARTS[part] = [ob]
    return ob


# ================================================================== preview


def outdoor(sun_elevation=38.0, sun_rotation=150.0, ground=(0.12, 0.2, 0.07)):
    """A clear day: the physical sky, a sun, a grass-coloured ground that catches the shadows."""
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.use_denoising = True
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.look = "AgX - Base Contrast"
    world = bpy.data.worlds.new("sky")
    scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    sky = nt.nodes.new("ShaderNodeTexSky")
    try:
        sky.sky_type = "NISHITA"
        sky.sun_elevation = math.radians(sun_elevation)
        sky.sun_rotation = math.radians(sun_rotation)
    except Exception:
        pass
    bg = nt.nodes["Background"]
    nt.links.new(sky.outputs[0], bg.inputs[0])
    bg.inputs[1].default_value = 0.35
    sun = bpy.data.lights.new("sun", "SUN")
    sun.energy = 4.0
    sun.angle = math.radians(1.0)
    so = bpy.data.objects.new("sun", sun)
    scene.collection.objects.link(so)
    so.rotation_euler = (math.radians(90 - sun_elevation), 0, math.radians(sun_rotation))
    bm = bmesh.new()
    bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=120)
    m, nt2, bsdf = node_mat("ground")
    col = _mix(nt2, _noise(nt2, 0.5, 6, 0.6, _coords(nt2)), tuple(ground), tuple(c * 0.7 for c in ground))
    nt2.links.new(col, bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.95
    me = bpy.data.meshes.new("ground")
    bm.to_mesh(me)
    bm.free()
    g = bpy.data.objects.new("ground", me)
    scene.collection.objects.link(g)
    me.materials.append(m)
    g.location = (0, 0, -0.002)


def shoot(name, eye, target, lens=35, res=(1600, 1000), samples=48):
    """A preview render from eye to target (block space) into OUT/name.png."""
    if os.environ.get("QUICK"):
        res, samples = (800, 500), 12
    cam = bpy.data.cameras.new(name)
    cam.lens = lens
    cam.clip_end = 1000
    ob = bpy.data.objects.new(name, cam)
    scene.collection.objects.link(ob)
    ob.location = mcv(eye)
    ob.rotation_euler = (mcv(target) - mcv(eye)).to_track_quat("-Z", "Y").to_euler()
    scene.camera = ob
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.cycles.samples = samples
    scene.render.film_transparent = False
    scene.render.filepath = os.path.join(OUT, name + ".png")
    bpy.ops.render.render(write_still=True)
    print("rendered", scene.render.filepath, flush=True)


# ================================================================== export


def export(name, order, pivots=None, tex=2048, ao=0.6, out_dir=None):
    """Bake every part's look into one texture and write the parts as quad meshes in block space, each about its
    pivot (block space): <out>/<name>.mesh.gz, <name>.png (base colour + a swatch strip) and <name>_mat.png
    (metallic, roughness, occlusion, coat) - the guns' format (AkItemRenderer.load, GunShading)."""
    import gzip
    import struct

    import numpy as np

    out_dir = out_dir or OUT
    pivots = {k: mcv(v) for k, v in (pivots or {}).items()}
    # light meshes for the game: bevels at most one segment
    for part in order:
        for ob in PARTS.get(part, []):
            for md in ob.modifiers:
                if md.type == "BEVEL":
                    md.segments = 1
    dg = bpy.context.evaluated_depsgraph_get()
    bm_all = bmesh.new()
    part_layer = bm_all.faces.layers.int.new("part")
    mats = []
    for pi, part in enumerate(order):
        for ob in PARTS.get(part, []):
            me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
            me.transform(ob.matrix_world)
            bm = bmesh.new()
            bm.from_mesh(me)
            remap = {}
            for i, m in enumerate(me.materials):
                if m.name not in [x.name for x in mats]:
                    mats.append(m)
                remap[i] = [x.name for x in mats].index(m.name)
            for f in bm.faces:
                f.material_index = remap.get(f.material_index, 0)
            tmp = bpy.data.meshes.new("tmp")
            bm.to_mesh(tmp)
            bm.free()
            n0 = len(bm_all.faces)
            bm_all.from_mesh(tmp)
            bm_all.faces.ensure_lookup_table()
            for f in bm_all.faces[n0:]:
                f[part_layer] = pi
            bpy.data.meshes.remove(tmp)
            bpy.data.meshes.remove(me)
            ob.hide_render = True
            ob.hide_viewport = True
    me_all = bpy.data.meshes.new(name + "_game")
    bm_all.to_mesh(me_all)
    bm_all.free()
    for m in mats:
        me_all.materials.append(m)
    for p in me_all.polygons:
        p.use_smooth = True
    me_all.set_sharp_from_angle(angle=math.radians(35))
    game = bpy.data.objects.new(name + "_game", me_all)
    scene.collection.objects.link(game)
    print("faces", len(me_all.polygons), "verts", len(me_all.vertices), flush=True)

    bpy.context.view_layer.objects.active = game
    for o in scene.objects:
        o.select_set(False)
    game.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.002, area_weight=0.0, scale_to_bounds=True)
    bpy.ops.uv.pack_islands(margin=0.002, rotate=True)
    bpy.ops.object.mode_set(mode="OBJECT")

    for m in mats:
        nt = m.node_tree
        tn = nt.nodes.new("ShaderNodeTexImage")
        tn.name = "bake_target"
        nt.nodes.active = tn
    scene.render.engine = "CYCLES"
    scene.render.bake.margin = 6

    def emit_bake(img_name, feed, samples):
        im = bpy.data.images.new(img_name, tex, tex, alpha=False, float_buffer=True)
        for m in mats:
            nt = m.node_tree
            outn = [n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"][0]
            em = nt.nodes.new("ShaderNodeEmission")
            nt.links.new(feed(m, nt), em.inputs["Color"])
            nt.links.new(em.outputs[0], outn.inputs["Surface"])
            nt.nodes["bake_target"].image = im
        scene.cycles.samples = samples
        bpy.ops.object.bake(type="EMIT", use_clear=True)
        return np.array(im.pixels[:], dtype=np.float32).reshape(tex, tex, 4)[::-1, :, :3]

    def value(nt, v):
        if isinstance(v, (int, float)):
            n = nt.nodes.new("ShaderNodeValue")
            n.outputs[0].default_value = v
            return n.outputs[0]
        return v

    def feed_albedo(m, nt):
        return DISPLAY[m.name][0]

    def feed_surface(m, nt):
        metal, rough, coat = SURFACE[m.name]
        comb = nt.nodes.new("ShaderNodeCombineColor")
        nt.links.new(value(nt, metal), comb.inputs[0])
        nt.links.new(value(nt, rough), comb.inputs[1])
        a = nt.nodes.new("ShaderNodeAmbientOcclusion")
        a.inputs["Distance"].default_value = ao
        a.samples = 16
        nt.links.new(a.outputs["AO"], comb.inputs[2])
        return comb.outputs[0]

    def feed_coat(m, nt):
        comb = nt.nodes.new("ShaderNodeCombineColor")
        v = value(nt, SURFACE[m.name][2])
        for k in range(3):
            nt.links.new(v, comb.inputs[k])
        return comb.outputs[0]

    albedo = emit_bake("bake_albedo", feed_albedo, 8)
    surface = emit_bake("bake_surface", feed_surface, 48)
    coat = emit_bake("bake_coat", feed_coat, 1)[..., 0]

    strip = 32
    height = tex + strip

    def save_png(fname, rgba):
        im = bpy.data.images.new(fname, tex, height, alpha=True)
        im.pixels[:] = (rgba[::-1].astype(np.float32) / 255.0).ravel()
        im.filepath_raw = os.path.join(out_dir, fname)
        im.file_format = "PNG"
        im.save()

    px = np.clip(albedo, 0.0, 1.0)
    srgb = np.where(px <= 0.0031308, px * 12.92, 1.055 * np.power(px, 1 / 2.4) - 0.055)
    full = np.zeros((height, tex, 4), dtype=np.uint8)
    full[:tex, :, :3] = (srgb * 255 + 0.5).astype(np.uint8)
    full[:, :, 3] = 255
    save_png(name + ".png", full)
    mat = np.zeros((height, tex, 4), dtype=np.uint8)
    mat[:tex, :, :3] = (np.clip(surface, 0.0, 1.0) * 255 + 0.5).astype(np.uint8)
    mat[:tex, :, 3] = (np.clip(coat, 0.0, 1.0) * 255 + 0.5).astype(np.uint8)
    mat[tex:] = (0, 160, 255, 0)
    save_png(name + "_mat.png", mat)

    uv = me_all.uv_layers.active.data
    cn = me_all.corner_normals
    pl = me_all.attributes["part"].data
    me_all.calc_loop_triangles()
    tris = {}
    for t in me_all.loop_triangles:
        tris.setdefault(t.polygon_index, []).append(t.loops)
    data = {p: [] for p in order}

    def corner(li, part):
        v = me_all.vertices[me_all.loops[li].vertex_index].co - pivots.get(part, Vector())
        n = cn[li].vector
        u, w = uv[li].uv
        return (v.x, v.z, -v.y, u, (1.0 - w) * tex / height, n.x, n.z, -n.y)

    for poly in me_all.polygons:
        part = order[pl[poly.index].value]
        loops = list(poly.loop_indices)
        quads = [loops] if len(loops) == 4 else [list(t) + [t[2]] for t in tris[poly.index]]
        for q in quads:
            for li in q:
                data[part].extend(corner(li, part))
    with gzip.open(os.path.join(out_dir, name + ".mesh.gz"), "wb") as f:
        f.write(struct.pack(">I", len(order)))
        for part in order:
            nm = part.encode()
            f.write(struct.pack(">H", len(nm)) + nm)
            f.write(struct.pack(">I", len(data[part])))
            f.write(struct.pack(f">{len(data[part])}f", *data[part]))
    for part in order:
        print(part, len(data[part]) // 32, "quads", flush=True)
