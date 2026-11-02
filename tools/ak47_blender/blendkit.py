"""Shared Blender building blocks for the gun models (materials, solids, booleans, studio and renders).

    from blendkit import *
"""
import math
import sys

import bpy  # noqa: I001 (bpy must come first: it brings bmesh)
import bmesh
from mathutils import Matrix, Vector

OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "."  # renders go here

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
MM = 0.001
HDRI = bpy.utils.resource_path("LOCAL") + "/datafiles/studiolights/world/interior.exr"

# ------------------------------------------------------------------ materials


DISPLAY = {}
# per material for the game shader: (metallic, roughness socket or value, clear coat)
SURFACE = {}


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
    col = _mix_rgb(nt, worn, col, (0.27, 0.27, 0.265))
    nt.links.new(col, bsdf.inputs["Base Color"])
    DISPLAY[m.name] = (col, 2.0)
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
    SURFACE[m.name] = (1.0, rough_out, 0.0)  # (the game adds a little roughness to metals)
    bump = _n(nt, "ShaderNodeBump", Strength=0.05, Distance=0.0002)
    h = _math(nt, "ADD", scr.outputs["Fac"], _math(nt, "MULTIPLY", fine.outputs["Fac"], 0.4))
    nt.links.new(h, bump.inputs["Height"])
    nt.links.new(bev_n, bump.inputs["Normal"])
    nt.links.new(bump.outputs[0], bsdf.inputs["Normal"])
    return m


def wood(name, dark=(0.03, 0.0085, 0.0038), light=(0.11, 0.034, 0.0135), scale=(1.0, 0.035, 1.0), grain="Y", tilt=0.0):
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
    DISPLAY[m.name] = (col, 1.45)
    # semi-matte old finish, patchy where worn
    rr = _n(nt, "ShaderNodeMapRange", **{"To Min": 0.32, "To Max": 0.6})
    nt.links.new(big.outputs["Fac"], rr.inputs[0])
    nt.links.new(rr.outputs[0], bsdf.inputs["Roughness"])
    SURFACE[m.name] = (0.0, rr.outputs[0], 0.3)
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
    SURFACE[m.name] = (metal, rough, 0.0)
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
    mats = [m for m in old.materials]
    target.data = me
    bpy.data.meshes.remove(old)
    me.materials.clear()
    for m in mats:
        me.materials.append(m)
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


