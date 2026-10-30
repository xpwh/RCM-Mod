"""Glock 19 Gen 5 built in Blender from script, at real size in metres, after a side photograph (glock_trace.py).

Axes: X right, Y forward (towards the muzzle), Z up; origin at the middle of the grip, the bore 58 mm above it
(the mod's item space turned: item = (x, z, -y)). Parts for the game: frame, slide, barrel, mag (15 rounds,
flush), mag_ext (33 rounds), round (a cartridge, nose along -item Z from its base), case (a spent case).

    python glock.py <out_dir> [--shot=side,three_quarter,left,detail] [--export=<dir>]
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blendkit import *  # noqa: E402,F401,F403
from blendkit import _edge_mask, _math, _mix_f, _mix_rgb, _n  # noqa: E402
import bmesh  # noqa: E402
import bpy  # noqa: E402
from mathutils import Matrix, Vector  # noqa: E402

P = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "glock_profile.json")))
MK = P["marks"]
BORE = P["bore"]
S_BOT, S_TOP = P["slide_bottom"], P["slide_top"]
S_REAR = min(p[0] for p in P["slide"])
S_FRONT = max(p[0] for p in P["slide"])
SW = 0.01275  # half the slide's width (25.5 mm)
FW = 0.0150   # half the grip's width
GRIP_ANGLE = math.radians(22.0)
DOWN = Vector((0.0, -math.sin(GRIP_ANGLE), -math.cos(GRIP_ANGLE)))  # down the grip (and the magazine)
MAG_SEAT = Vector((0.0, 0.034 * math.tan(GRIP_ANGLE), 0.034))


# ------------------------------------------------------------------ materials

def polymer(name, base=(0.022, 0.022, 0.024), rough=0.6, stipple=0.0):
    """Glock polymer: near-black, a fine moulded texture all over, the grip's stippling where it is."""
    m, nt, bsdf = node_mat(name)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    fine = _n(nt, "ShaderNodeTexNoise", Scale=3000.0, Detail=4.0)
    nt.links.new(tc.outputs["Object"], fine.inputs["Vector"])
    col = _mix_rgb(nt, _math(nt, "MULTIPLY", fine.outputs["Fac"], 0.25), base, (base[0] * 1.6, base[1] * 1.6, base[2] * 1.7))
    edge, bev_n = _edge_mask(nt, 0.0015, 0.02, 0.1)
    col = _mix_rgb(nt, _math(nt, "MULTIPLY", edge, 0.6), col, (0.075, 0.075, 0.08))  # rubbed shiny-grey on the edges
    nt.links.new(col, bsdf.inputs["Base Color"])
    DISPLAY[m.name] = (col, 1.4)
    rr = _n(nt, "ShaderNodeMapRange", **{"To Min": rough - 0.08, "To Max": rough + 0.08})
    nt.links.new(fine.outputs["Fac"], rr.inputs[0])
    rough_out = _mix_f(nt, edge, rr.outputs[0], rough - 0.25)
    nt.links.new(rough_out, bsdf.inputs["Roughness"])
    SURFACE[m.name] = (0.0, rough_out, 0.0)
    h = fine.outputs["Fac"]
    if stipple > 0.0:
        # the grip's stippling: a field of little pyramids, only below the slide and behind the trigger guard
        vor = nt.nodes.new("ShaderNodeTexVoronoi")
        vor.inputs["Scale"].default_value = 900.0
        nt.links.new(tc.outputs["Object"], vor.inputs["Vector"])
        sep = nt.nodes.new("ShaderNodeSeparateXYZ")
        nt.links.new(tc.outputs["Object"], sep.inputs[0])
        below = _math(nt, "LESS_THAN", sep.outputs["Z"], 0.028)
        behind = _math(nt, "LESS_THAN", _math(nt, "SUBTRACT", sep.outputs["Y"], _math(nt, "MULTIPLY", sep.outputs["Z"], 0.4)), 0.02)
        mask = _math(nt, "MULTIPLY", below, behind)
        h = _math(nt, "ADD", h, _math(nt, "MULTIPLY", _math(nt, "MULTIPLY", vor.outputs["Distance"], 6.0 * stipple), mask))
    bump = _n(nt, "ShaderNodeBump", Strength=0.35, Distance=0.0002)
    nt.links.new(h, bump.inputs["Height"])
    nt.links.new(bev_n, bump.inputs["Normal"])
    nt.links.new(bump.outputs[0], bsdf.inputs["Normal"])
    return m


M_FRAME = polymer("frame", base=(0.016, 0.016, 0.018), stipple=1.0)
M_MAGP = polymer("magazine_polymer", base=(0.02, 0.02, 0.021), rough=0.5)
M_SLIDE = blued("slide_ndlc", base=(0.022, 0.022, 0.024), rough=0.42, wear=0.35)
M_BARREL = blued("barrel_ndlc", base=(0.03, 0.03, 0.032), rough=0.32, wear=0.5)
M_STEEL = blued("small_steel", base=(0.06, 0.06, 0.062), rough=0.35, wear=0.8)
M_WHITE = flat("sight_white", (0.8, 0.8, 0.78), 0.4)
M_ORANGE = flat("follower", (0.75, 0.22, 0.03), 0.5)
M_VOID = M_BLACK


# ------------------------------------------------------------------ helpers


def yprism(name, profile_xz, y0, y1, mat, part="frame", bevel=0.0, **kw):
    """A cross-section [(x, z), ...] extruded along Y from y0 to y1."""
    bm = bmesh.new()
    a = [bm.verts.new((x, y0, z)) for x, z in profile_xz]
    b = [bm.verts.new((x, y1, z)) for x, z in profile_xz]
    n = len(profile_xz)
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((a[i], b[i], b[j], a[j]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel, **kw)


def boxes(name, bxs, mat=None, part="cutters"):
    """Many boxes as one object (a single boolean cutter): (x0, y0, z0, x1, y1, z1) each."""
    bm = bmesh.new()
    for x0, y0, z0, x1, y1, z1 in bxs:
        geom = bmesh.ops.create_cube(bm, size=1.0)
        for v in geom["verts"]:
            v.co = Vector(((x0 + x1) / 2 + v.co.x * (x1 - x0), (y0 + y1) / 2 + v.co.y * (y1 - y0), (z0 + z1) / 2 + v.co.z * (z1 - z0)))
    return new_obj(name, bm, mat or M_VOID, part)


def mark(name):
    return MK[name]


# ------------------------------------------------------------------ slide

slide = prism("slide", [tuple(p) for p in P["slide"]], -SW, SW, M_SLIDE, part="slide", bevel=0.0009, seg=2)
# the long chamfers along the top edges, Glock's slide
for sx in (-1, 1):
    cut(slide, yprism(f"cut_chamfer{sx}", [(sx * (SW - 0.0034), S_TOP + 0.002), (sx * (SW + 0.002), S_TOP + 0.002), (sx * (SW + 0.002), S_TOP - 0.0036)][::sx],
                      S_REAR - 0.01, S_FRONT + 0.01, M_VOID, part="cutters"))
# the nose: the front of the slide rounded off below and chamfered on top
cut(slide, yprism("cut_nose", [(-0.03, S_TOP - 0.004), (0.03, S_TOP - 0.004), (0.03, S_TOP + 0.01), (-0.03, S_TOP + 0.01)], S_FRONT - 0.003, S_FRONT + 0.01,
                  M_VOID, part="cutters"))
# serrations, front and rear, on both sides
serr = []
for (y0, z0, y1, z1), count in ((mark("rear_serrations"), 9), (mark("front_serrations"), 8)):
    step = (y1 - y0) / count
    for i in range(count):
        yc = y0 + step * (i + 0.5)
        for sx in (-1, 1):
            xa, xb = (SW - 0.0007, SW + 0.003) if sx > 0 else (-SW - 0.003, -SW + 0.0007)
            serr.append((xa, yc - 0.0006, S_BOT + 0.003, xb, yc + 0.0006, S_TOP - 0.0042))
cut(slide, boxes("cut_serrations", serr))
# the ejection port: on top and down the right side
PORT0, PORT1 = S_REAR + 0.047, S_REAR + 0.083
cut(slide, boxes("cut_port", [(-0.0045, PORT0, BORE - 0.0035, SW + 0.01, PORT1, S_TOP + 0.01)]))
# the muzzle opening the barrel shows in, and the recoil spring guide's hole below it
mz = lathe("cut_muzzle", (0, S_FRONT - 0.012, BORE), (0, 1, 0), [(0, 0.0072), (0.03, 0.0072)], M_VOID, part="cutters", k=32)
cut(slide, mz)
GUIDE_Z = BORE - 0.0118
cut(slide, lathe("cut_guide", (0, S_FRONT - 0.006, GUIDE_Z), (0, 1, 0), [(0, 0.0046), (0.03, 0.0046)], M_VOID, part="cutters", k=24))
lathe("guide_rod", (0, S_FRONT - 0.07, GUIDE_Z), (0, 1, 0), [(0, 0.0038), (0.066, 0.0038), (0.0672, 0.0026), (0.068, 0)], M_STEEL, part="frame", k=20)
# the extractor beside the port (right), the loaded-chamber bump
box("extractor", SW - 0.0004, PORT0 - 0.012, BORE + 0.0035, SW + 0.0004, PORT0 + 0.002, BORE + 0.0075, M_STEEL, part="slide", bevel=0.0003)
# sights: the rear U-notch with its white outline, the front post with its dot
ry0, rz0, ry1, rz1 = mark("rear_sight")
rear = box("rear_sight", -0.0037, ry0, S_TOP - 0.0004, 0.0037, ry1, rz1, M_STEEL, part="slide", bevel=0.0004)
cut(rear, boxes("cut_notch", [(-0.0016, ry0 - 0.01, rz1 - 0.0024, 0.0016, ry1 + 0.01, rz1 + 0.01)]))
for sx in (-1, 1):
    box(f"rear_white{sx}", sx * 0.0016 if sx > 0 else -0.0034, ry0 - 0.0002, rz1 - 0.0024, 0.0034 if sx > 0 else -0.0016, ry0 + 0.0001, rz1 - 0.0004,
        M_WHITE, part="slide", bevel=0)
fy0, fz0, fy1, fz1 = mark("front_sight")
box("front_sight", -0.0016, fy0 + 0.0012, S_TOP - 0.0004, 0.0016, fy1 - 0.0012, fz1, M_STEEL, part="slide", bevel=0.0004)
lathe("front_dot", (0, fy0 + 0.0012 - 0.0002, fz1 - 0.0016), (0, -1, 0), [(0, 0.0011), (0.0003, 0.0011), (0.0003, 0)], M_WHITE, part="slide", k=16)
# the markings on the left side of the slide, engraved
try:
    for text, y, z, size in (("GLOCK", 0.088, S_BOT + 0.0105, 0.0045), ("19 Gen5", 0.062, S_BOT + 0.0105, 0.0045),
                             ("AUSTRIA  9x19", 0.025, S_BOT + 0.0105, 0.0036)):
        cu = bpy.data.curves.new(text, "FONT")
        cu.body = text
        cu.size = size
        cu.extrude = 0.0006
        cu.align_x = "CENTER"
        cu.align_y = "CENTER"
        tob = bpy.data.objects.new("txt_" + text, cu)
        scene.collection.objects.link(tob)
        tob.rotation_euler = (math.radians(90), 0, math.radians(-90))
        tob.location = (-SW, y, z)
        dg = bpy.context.evaluated_depsgraph_get()
        me = bpy.data.meshes.new_from_object(tob.evaluated_get(dg))
        me.transform(tob.matrix_world)
        bpy.data.objects.remove(tob)
        cob = bpy.data.objects.new("cut_" + text, me)
        scene.collection.objects.link(cob)
        PARTS.setdefault("cutters", []).append(cob)
        cut(slide, cob)
except Exception as e:  # the markings are a nicety; a font problem must not stop the model
    print("markings skipped:", e)

# ------------------------------------------------------------------ barrel: the tube, the chamber hood that fills the port
BARREL_REAR = PORT0 - 0.004
lathe("barrel_tube", (0, BARREL_REAR, BORE), (0, 1, 0), [(0, 0.0068), (S_FRONT - BARREL_REAR - 0.0012, 0.0068), (S_FRONT - BARREL_REAR - 0.0008, 0.0062)],
      M_BARREL, part="barrel", k=40)
lathe("barrel_bore", (0, S_FRONT - 0.0005, BORE), (0, -1, 0), [(0, 0.0045), (0.02, 0.0045), (0.0201, 0)], M_VOID, part="barrel", k=24)
box("barrel_hood", -0.0058, BARREL_REAR, BORE - 0.004, 0.0058, PORT1 + 0.0005, S_TOP - 0.0008, M_BARREL, part="barrel", bevel=0.0006)
lathe("chamber_mouth", (0, PORT0 + 0.001, BORE), (0, 1, 0), [(0, 0.0049), (0.003, 0.0049), (0.0031, 0)], M_VOID, part="barrel", k=20)

# ------------------------------------------------------------------ frame
frame = prism("frame", [tuple(p) for p in P["frame"]], -FW, FW, M_FRAME, part="frame", bevel=0.0028, seg=3)
cut(frame, prism("cut_guard", [tuple(p) for p in P["guard"]], -0.03, 0.03, M_VOID, part="cutters"))
# the dust cover and rail narrower than the grip, the trigger guard a thin loop
gy_front = max(p[0] for p in P["frame"])
cut(frame, boxes("cut_dustcover", [(0.0105, 0.036, 0.0, 0.03, gy_front + 0.01, S_BOT - 0.0005),
                                   (-0.03, 0.036, 0.0, -0.0105, gy_front + 0.01, S_BOT - 0.0005)]))
# above the grip, under the slide, the frame is as wide as the slide (the grip flares out below)
cut(frame, boxes("cut_upper", [(SW - 0.0003, S_REAR - 0.03, 0.03, 0.03, 0.0365, S_BOT + 0.01), (-0.03, S_REAR - 0.03, 0.03, -SW + 0.0003, 0.0365, S_BOT + 0.01)]))
ty0, tz0, ty1, tz1 = mark("trigger")
cut(frame, boxes("cut_guardloop", [(0.0045, 0.036, -0.03, 0.03, gy_front, 0.0305), (-0.03, 0.036, -0.03, -0.0045, gy_front, 0.0305)]))
# the rail's cross slot under the dust cover
r0, rz0, r1, rz1 = mark("rail_slot")
cut(frame, boxes("cut_rail", [(-0.02, r0 + 0.004, rz0 - 0.004, 0.02, r0 + 0.0085, rz0 + 0.0015), (-0.02, r1 - 0.012, rz0 - 0.004, 0.02, r1 - 0.0075, rz0 + 0.0015)]))
# the magazine well's mouth, opened up under the grip
cut(frame, boxes("cut_well", [(-0.0115, -0.03, -0.07, 0.0115, 0.02, -0.044)]))
# controls: the slide stop (left), the magazine release (left, behind the guard), the takedown lever (both sides), pins
sy0, sz0, sy1, sz1 = mark("slide_stop")
prism("slide_stop", [(sy0, sz0 + 0.002), (sy1, sz0 + 0.003), (sy1, sz1 - 0.001), (sy0 + 0.002, sz1)], -FW - 0.0012, -FW + 0.002, M_STEEL, part="frame",
      bevel=0.0005)
my0, mz0, my1, mz1 = mark("mag_release")
yloft_pts = []
mr = prism("mag_release", [(my0 + 0.002, mz0 + 0.001), (my1 - 0.002, mz0 + 0.001), (my1 - 0.001, mz1 - 0.001), (my0 + 0.001, mz1 - 0.001)],
           -FW - 0.0016, -FW + 0.003, M_FRAME, part="frame", bevel=0.0012)
ky0, kz0, ky1, kz1 = mark("takedown")
for sx in (-1, 1):
    box(f"takedown{sx}", sx * 0.0106 if sx < 0 else 0.0098, ky0 + 0.002, kz0 + 0.002, sx * 0.0098 if sx < 0 else 0.0106, ky1 - 0.002, kz1 - 0.001,
        M_STEEL, part="frame", bevel=0.0004)
py0, pz0, py1, pz1 = mark("pin")
for sx in (-1, 1):
    pin(f"pin_trigger{sx}", sx * 0.0105, (py0 + py1) / 2, (pz0 + pz1) / 2, r=0.0013, side=sx, mat=M_STEEL, part="frame")
    pin(f"pin_locking{sx}", sx * 0.0105, (py0 + py1) / 2 + 0.012, (pz0 + pz1) / 2 + 0.006, r=0.0013, side=sx, mat=M_STEEL, part="frame")
# the trigger: a curved blade with the safety lever in its face
tr = arc_points((0, ty1 - 0.006, tz1 - 0.0005), (-0.25, -1.0), -14.0, 0.022, 10)
loft("trigger", [ring(p, X, Vector((0, 1, 0)), 0.0033, 0.0022, 3.0, 16) for p in tr], M_FRAME, part="frame")
loft("trigger_safety", [ring(p + Vector((0, 0.0018, 0)), X, Vector((0, 1, 0)), 0.0011, 0.0012, 3.0, 12) for p in tr[2:9]], M_STEEL, part="frame")
# the beavertail's steel insert behind the slide (the striker channel cover), the rear of the frame rails
box("rear_plate", -0.0095, S_REAR - 0.0012, S_BOT + 0.004, 0.0095, S_REAR + 0.0004, S_TOP - 0.004, M_SLIDE, part="slide", bevel=0.0004)


# ------------------------------------------------------------------ magazines


def magazine(name, length, part):
    """Down the grip from the seat: a steel tube in polymer, the orange follower and a round at the lips, the floor plate."""
    top = MAG_SEAT
    bot = top + DOWN * length
    fwd = Vector((0, math.cos(GRIP_ANGLE), -math.sin(GRIP_ANGLE)))  # across the magazine, forward
    rings = []
    for f in (0.0, 1.0):
        c = top.lerp(bot, f)
        rings.append(ring(c, X, fwd, 0.0108, 0.0158, 4.0, 32))
    loft(name + "_body", rings, M_MAGP, part=part, bevel=0.0004)
    # the floor plate: wider, overhanging front and back, with the finger lip at the front
    fp = bot + DOWN * 0.0025
    loft(name + "_plate", [ring(fp - DOWN * 0.003 + fwd * 0.001, X, fwd, 0.0122, 0.0172, 5.0, 32), ring(fp + DOWN * 0.003 + fwd * 0.002, X, fwd, 0.0122, 0.0176, 5.0, 32)],
         M_MAGP, part=part, bevel=0.0008)
    # feed lips, the follower showing orange between them, a round on top
    loft(name + "_follower", [ring(top - DOWN * 0.0005, X, fwd, 0.006, 0.012, 3.0, 20), ring(top + DOWN * 0.002, X, fwd, 0.006, 0.012, 3.0, 20)], M_ORANGE,
         part=part)
    axis = fwd
    base = top - DOWN * 0.0002 - axis * 0.012
    lathe(name + "_case", base, axis, [(0, 0.0048), (0.0012, 0.0048), (0.0015, 0.0041), (0.0019, 0.0049), (0.0158, 0.0049), (0.0164, 0.0047)], M_BRASS,
          part=part, k=16)
    lathe(name + "_bullet", base + axis * 0.0164, axis, [(0, 0.0045), (0.0035, 0.0045), (0.0085, 0.0032), (0.0108, 0.0012), (0.0112, 0)], M_COPPER, part=part,
          k=16)


magazine("mag", 0.0965, "mag")
magazine("mag_ext", 0.205, "mag_ext")

# a cartridge (for the chamber) and a spent case, along -item Z (= +Y here) from their base
lathe("round_case", (0, 0, 0), (0, 1, 0), [(0, 0.0048), (0.0012, 0.0048), (0.0015, 0.0041), (0.0019, 0.0049), (0.0158, 0.0049), (0.0164, 0.0047)], M_BRASS,
      part="round", k=16)
lathe("round_bullet", (0, 0.0164, 0), (0, 1, 0), [(0, 0.0045), (0.0035, 0.0045), (0.0085, 0.0032), (0.0108, 0.0012), (0.0112, 0)], M_COPPER, part="round", k=16)
lathe("spent", (0, -0.0082, 0), (0, 1, 0), [(0, 0.0048), (0.0012, 0.0048), (0.0015, 0.0041), (0.0019, 0.0049), (0.0158, 0.0049), (0.0164, 0.0047),
                                            (0.0164, 0.0043), (0.0005, 0.0043)], M_BRASS, part="case", k=16)

# ------------------------------------------------------------------ renders / export

ORDER_GLOCK = ["frame", "slide", "barrel", "mag", "mag_ext", "round", "case"]

if __name__ == "__main__":
    if any(a.startswith("--export") for a in sys.argv):
        EXPORT_NAME = "glock19"
        ORDER = ORDER_GLOCK

        def to_item(v):
            return Vector((v.x, v.z, -v.y))

        def nrm_item(n):
            return Vector((n.x, n.z, -n.y)).normalized()

        PIVOTS = {}
        SPT_BAND = None
        exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "export.py")).read())
        sys.exit(0)
    setup_render()
    CENTRE_G = Vector((0, 0.06, 0.02))
    area("key", (0.35, 0.25, 0.45), CENTRE_G, 0.5, 7, (1.0, 0.95, 0.88))
    area("fill", (-0.45, 0.0, 0.15), CENTRE_G, 0.6, 2.5, (0.85, 0.9, 1.0))
    area("rim", (0.05, 0.6, 0.25), CENTRE_G, 0.3, 6)
    area("rim2", (-0.1, -0.4, 0.25), CENTRE_G, 0.3, 4)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.35
    bm = bmesh.new()
    bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=30)
    fl = new_obj("floor", bm, flat("floor", (0.02, 0.02, 0.022), 0.75), part="floor")
    fl.location = (0, 0, -0.08)
    fl.is_shadow_catcher = True
    for o in PARTS.get("mag_ext", []) + PARTS.get("round", []) + PARTS.get("case", []):
        o.hide_render = True
    shots = [a for a in sys.argv if a.startswith("--shot=")]
    want = shots[0][7:].split(",") if shots else ["side", "three_quarter", "left", "detail"]
    if "side" in want:
        shoot("side", (0.6, 0.06, 0.03), (0, 0.06, 0.01), lens=50)
    if "three_quarter" in want:
        shoot("three_quarter", (0.36, 0.42, 0.18), (0, 0.06, 0.01), lens=50)
    if "left" in want:
        shoot("left", (-0.5, -0.12, 0.12), (0, 0.06, 0.01), lens=50)
    if "detail" in want:
        shoot("detail", (0.16, 0.16, 0.1), (0.0, 0.02, 0.05), lens=55, dof=4.0)
    if "compare" in want:
        cam = camera("compare", (-1.0, 0.0, 0.0), (0.0, 0.0, 0.0))
        cam.data.type = "ORTHO"
        k = P["metres_per_pixel"]
        cam.data.ortho_scale = 1280 * k
        scene.camera = cam
        scene.render.resolution_x, scene.render.resolution_y = 1280, 819
        scene.cycles.samples = 16
        scene.render.filepath = f"{OUT}/compare.png"
        bpy.ops.render.render(write_still=True)
