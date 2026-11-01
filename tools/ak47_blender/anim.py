"""The AK-47's first-person animations, keyframed in Blender and exported for the mod.

The scene is in the mod's item space (x right, y up, z back; origin at the hand on the grip), under a root
that turns it to Blender's Z-up:
  gun_pose      the rifle's pose in the hand (location, rotation YXZ = yaw, pitch, roll): what
                AkFirstPerson applies; everything below moves with it
  body/carrier/selector  the game meshes (ak47.mesh.gz), for the preview renders
  mag, mag2     magazines, their origin at the front lug they rock about
  mag_grip, mag2_grip, handle_grip   where a hand takes hold of them
  hand_L, hand_R  the hands: a free path, blended onto a grip by keyed constraint influences
  arms          two-bone IK chains from fixed shoulders to the hands (preview only)

Each clip's keys are below; Blender's F-curves (Bezier, Back, Elastic, ...) interpolate them, and the
result is sampled four times a tick into ak47.anim.gz:
  per clip: name, length (ticks), samples; per sample 51 floats:
    gun x y z roll pitch yaw (degrees) | mag 3x4 | mag2 3x4 | left hand xyz | right hand xyz | bolt 0..1 |
    mag visible | mag2 visible | mag is the fresh one | left hand turn xyzw | right hand turn xyzw |
    head pitch yaw roll (degrees)
A magazine let fall is simulated (Bullet, animkit.rigid_fall); the reloads come in three takes ("name",
"name@1", "name@2"), the game picks one at random.

    python anim.py <ak47.mesh.gz> <ak47.png> <out_dir> [--preview] [--clips=a,b]
"""
import gzip
import math
import os
import struct
import sys

import bpy  # noqa: I001
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import animkit  # noqa: E402

MESH, TEX, OUT = sys.argv[1:4]
PREVIEW = "--preview" in sys.argv
ONLY = [a.split("=", 1)[1].split(",") for a in sys.argv if a.startswith("--clips=")]
ONLY = ONLY[0] if ONLY else None
SPT = 4  # samples per tick
FPT = 4  # frames per tick

# ---- anchors in item space (see AkAnim)
GRIP = (0.0, -0.04, 0.082)
HANDGUARD = (-0.004, 0.026, -0.335)
HANDLE = (0.086, 0.092, -0.029)
POUCH = (-0.26, -0.46, 0.3)
LUG = Vector((0.0, 0.037, -0.161))          # the magazine's front lug: it rocks about this
MAG_HOLD = Vector((-0.006, -0.105, -0.194))  # where the hand closes on a magazine
BOLT_TRAVEL = 0.115
T_MAG_OUT, T_MAG_DROP, T_MAG_IN, T_CHARGE = 12, 15, 30, 52
T_TAC_OUT, T_TAC_IN = 14, 21

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


def empty(name, parent=None, loc=(0, 0, 0)):
    ob = bpy.data.objects.new(name, None)
    scene.collection.objects.link(ob)
    ob.parent = parent
    ob.location = loc
    ob.empty_display_size = 0.01
    return ob


root = empty("item_space")
root.rotation_euler = (math.pi / 2, 0.0, 0.0)  # item y up -> Blender z, item z back -> Blender -y
gun = empty("gun_pose", root)
gun.rotation_mode = "YXZ"  # matrix = Rz(roll) Rx(pitch) Ry(yaw), as AkFirstPerson turns the pose stack


# ---- the game meshes, textured, for the previews
def load_mesh(path):
    f = gzip.open(path, "rb")
    parts = {}
    for _ in range(struct.unpack(">I", f.read(4))[0]):
        name = f.read(struct.unpack(">H", f.read(2))[0]).decode()
        c = struct.unpack(">I", f.read(4))[0]
        parts[name] = struct.unpack(f">{c}f", f.read(4 * c))
    return parts


PARTS = load_mesh(MESH)
mat = bpy.data.materials.new("ak47")
mat.use_nodes = True
nt = mat.node_tree
bsdf = nt.nodes["Principled BSDF"]
img = nt.nodes.new("ShaderNodeTexImage")
img.image = bpy.data.images.load(os.path.abspath(TEX))
nt.links.new(img.outputs[0], bsdf.inputs["Base Color"])
bsdf.inputs["Roughness"].default_value = 0.45
bsdf.inputs["Metallic"].default_value = 0.3


def mesh_object(name, data, parent, offset=Vector()):
    verts, faces, uvs = [], [], []
    for q in range(0, len(data), 32):
        idx = []
        for k in range(4):
            v = data[q + k * 8:q + k * 8 + 8]
            verts.append(Vector(v[:3]) - offset)
            uvs.append((v[3], 1.0 - v[4]))
            idx.append(len(verts) - 1)
        if verts[idx[2]] == verts[idx[3]]:
            idx = idx[:3]
        faces.append(idx)
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    uvl = me.uv_layers.new()
    for poly in me.polygons:
        for li in poly.loop_indices:
            uvl.data[li].uv = uvs[me.loops[li].vertex_index]
    me.materials.append(mat)
    for p in me.polygons:
        p.use_smooth = True
    ob = bpy.data.objects.new(name, me)
    scene.collection.objects.link(ob)
    ob.parent = parent
    return ob


SEL_PIVOT = Vector((0.0225, 0.0691, 0.0784))
mesh_object("body", PARTS["body"], gun)
carrier = mesh_object("carrier", PARTS["carrier"], gun)
sel = mesh_object("selector", PARTS["selector"], gun)
sel.location = SEL_PIVOT
mag_data = PARTS["magazine"] + PARTS["rounds"] + PARTS["tips"]
mag = mesh_object("mag", mag_data, gun, LUG)
mag2 = mesh_object("mag2", mag_data, gun, LUG)
for m in (mag, mag2):
    m.location = LUG
    m.rotation_mode = "XYZ"
mag_grip = empty("mag_grip", mag, MAG_HOLD - LUG)
mag2_grip = empty("mag2_grip", mag2, MAG_HOLD - LUG)
handle_grip = empty("handle_grip", carrier, HANDLE)

# hands: a free path (hand_X_free) the real hand copies, blended onto the grips by keyed influence
hands = {}
for side in ("L", "R"):
    free = empty(f"hand_{side}_free", gun)
    hand = empty(f"hand_{side}", gun)
    c = hand.constraints.new("COPY_LOCATION")
    c.name = "free"
    c.target = free
    hands[side] = (hand, free)


def grip_constraint(side, target):
    hand = hands[side][0]
    c = hand.constraints.new("COPY_LOCATION")
    c.name = target.name
    c.target = target
    c.influence = 0.0
    return c


C_L_MAG = grip_constraint("L", mag_grip)
C_L_MAG2 = grip_constraint("L", mag2_grip)
C_R_HANDLE = grip_constraint("R", handle_grip)
for p in ("magVis", "mag2Vis", "magFresh"):
    gun[p] = 0.0
# smooth channels: each forearm's twist (degrees), the eyes' extra look down (degrees)
CURVES = ("twistL", "twistR", "look")
for p in CURVES:
    gun[p] = 0.0
TAKE = animkit.Take(0)

# ---- keying helpers
INTERP = {"B": ("BEZIER", "AUTO"), "L": ("LINEAR", "AUTO"), "C": ("CONSTANT", "AUTO"), "BACK": ("BACK", "EASE_OUT"),
          "EL": ("ELASTIC", "EASE_OUT"), "IN": ("BEZIER", "EASE_IN"), "OUT": ("BEZIER", "EASE_OUT"), "SINE": ("SINE", "EASE_IN_OUT"),
          "QUAD": ("QUAD", "EASE_IN"), "QUADO": ("QUAD", "EASE_OUT"), "EXPO": ("EXPO", "EASE_OUT")}


def key(ob, path, tick, _value=None, mode="B"):
    """Key ob's current value at path; the segment after the key gets the given interpolation."""
    ob.keyframe_insert(data_path=path, frame=tick * FPT)
    interp, easing = INTERP[mode]
    for fc in fcurves(ob.animation_data.action):
        if fc.data_path == path:
            for kp in fc.keyframe_points:
                if abs(kp.co.x - tick * FPT) < 1e-3:
                    kp.interpolation = interp
                    kp.easing = easing
                    if interp == "BEZIER":
                        kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"


def fcurves(action):
    """All F-curves of an action (Blender 4.4+ keeps them in layered channelbags)."""
    if hasattr(action, "fcurves") and len(action.fcurves):
        return list(action.fcurves)
    out = []
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in strip.channelbags:
                out.extend(bag.fcurves)
    return out


def gun_keys(keys):
    """{tick: (x, y, z, roll, pitch, yaw, mode)}"""
    for t, (x, y, z, roll, pitch, yaw, mode) in sorted(TAKE.gun(keys).items()):
        gun.location = (x, y, z)
        gun.rotation_euler = (math.radians(pitch), math.radians(yaw), math.radians(roll))
        key(gun, "location", t, None, mode)
        key(gun, "rotation_euler", t, None, mode)


def mag_keys(ob, keys):
    """{tick: (dx, dy, dz, rock, roll, yaw, mode)}: rocked about the front lug (bottom forward), rolled, turned."""
    for t, (dx, dy, dz, rock, roll, yaw, mode) in sorted(keys.items()):
        if TAKE.n and mode != "C" and math.sqrt(dx * dx + dy * dy + dz * dz) > 0.1:
            # on its way from the pouch: another path each take
            dx, dy, dz = TAKE.point((dx, dy, dz), 0.012)
            rock, roll, yaw = TAKE.angle(rock, 4.0), TAKE.angle(roll, 4.0), TAKE.angle(yaw, 2.0)
        ob.location = LUG + Vector((dx, dy, dz))
        ob.rotation_euler = (math.radians(rock), math.radians(yaw), math.radians(roll))
        key(ob, "location", t, None, mode)
        key(ob, "rotation_euler", t, None, mode)


def free_keys(side, keys):
    """{tick: ((x, y, z), mode)}"""
    free = hands[side][1]
    ticks = sorted(keys)
    for t, (p, mode) in sorted(keys.items()):
        if t not in (ticks[0], ticks[-1]):
            p = TAKE.point(p, 0.008)
        free.location = p
        key(free, "location", t, None, mode)


def influence_keys(con, keys):
    for t, (v, mode) in sorted(keys.items()):
        con.influence = v
        con.keyframe_insert("influence", frame=t * FPT)
        for fc in fcurves(con.id_data.animation_data.action):
            if fc.data_path.endswith(f'constraints["{con.name}"].influence'):
                for kp in fc.keyframe_points:
                    if abs(kp.co.x - t * FPT) < 1e-3:
                        kp.interpolation, kp.easing = INTERP[mode]


def prop_keys(name, keys):
    for t, v in sorted(keys.items()):
        gun[name] = float(v)
        gun.keyframe_insert(data_path=f'["{name}"]', frame=t * FPT)
        for fc in fcurves(gun.animation_data.action):
            if fc.data_path == f'["{name}"]':
                for kp in fc.keyframe_points:
                    kp.interpolation = "CONSTANT"


def curve_keys(name, keys):
    """A smooth channel (twistL, twistR, look): {tick: degrees}."""
    for t, v in sorted(keys.items()):
        gun[name] = float(v)
        key(gun, f'["{name}"]', t, None, "B")


def bolt_keys(keys):
    for t, (b, mode) in sorted(keys.items()):
        carrier.location = (0.0, 0.0, BOLT_TRAVEL * b)
        key(carrier, "location", t, None, mode)


def clear():
    for ob in scene.objects:
        ob.animation_data_clear()
    gun.location = (0, 0, 0)
    gun.rotation_euler = (0, 0, 0)
    carrier.location = (0, 0, 0)
    for m in (mag, mag2):
        m.location = LUG
        m.rotation_euler = (0, 0, 0)
    for c in (C_L_MAG, C_L_MAG2, C_R_HANDLE):
        c.influence = 0.0
    hands["L"][1].location = HANDGUARD
    hands["R"][1].location = GRIP
    for p in ("magVis", "mag2Vis", "magFresh") + CURVES:
        gun[p] = 0.0


def fall(ob, release, until):
    """Let go of a magazine at `release`: Bullet has it until `until` (it is out of sight by then)."""
    animkit.rigid_fall(scene, ob, gun, release, until, FPT)


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


SEATED = (0, 0, 0, 0, 0, 0)


# ---------------------------------------------------------------- the clips
def clip_reload_tactical():
    """Magazine to magazine: the fresh one fetched from the pouch, brought up behind the old one and struck
    forward against the paddle - the old one knocked out and away - straight on into the well nose first,
    rocked back until it clicks, a tug; the rifle canted towards you all the while, jolted by the strike and
    the seating, then back on target with a little overshoot."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        5: (-0.05, 0.085, -0.05, -16, 5, 17, "B"),
        10: (-0.08, 0.145, -0.085, -24, 7, 28, "B"),
        13.4: (-0.081, 0.15, -0.083, -24.5, 7.5, 29, "IN"),       # the swing back before the strike
        14.3: (-0.084, 0.134, -0.094, -28, 3.5, 31, "OUT"),       # the strike knocks the rifle
        17: (-0.085, 0.16, -0.095, -25, 8, 31, "B"),
        20.6: (-0.085, 0.162, -0.095, -24, 8, 31, "QUADO"),
        21.4: (-0.085, 0.178, -0.093, -23, 11.5, 31, "OUT"),      # the seating slap
        23: (-0.084, 0.166, -0.094, -24, 8.5, 31, "B"),
        27: (-0.06, 0.12, -0.07, -17, 5.5, 22, "B"),
        33: (0.006, -0.008, 0.004, 1.8, -1.2, -1.8, "B"),          # overshoot past the target ...
        38: (0, 0, 0, 0, 0, 0, "B"),                               # ... and settle
    })
    # the old magazine: knocked out at T_TAC_OUT, flying away forward and down, tumbling (simulated below)
    mag_keys(mag, old_flight())
    # the fresh one, from the pouch: up behind the old one, drawn back, struck forward, in nose first, rocked home
    pouch = add(POUCH, (0.0, 0.05, 0.06))
    mag_keys(mag2, {
        5: (*pouch, 30, -35, 0, "B"),
        8: (-0.12, -0.26, 0.13, 22, -22, 0, "B"),
        11: (-0.006, -0.055, 0.075, 4, -5, 0, "B"),
        12.8: (-0.006, -0.062, 0.092, 6, -5, 0, "IN"),
        T_TAC_OUT: (-0.003, -0.032, 0.04, 10, -2, 0, "OUT"),
        15.6: (-0.002, -0.034, 0.012, 20, 0, 0, "B"),
        17.6: (0.0, -0.012, 0.0, 17, 0, 0, "B"),
        19.6: (0.0, -0.002, 0.0, 6, 0, 0, "QUAD"),
        T_TAC_IN: (*SEATED, "C"),
    })
    mag_keys(mag, {T_TAC_IN: (*SEATED, "QUADO"), T_TAC_IN + 1.5: (0, -0.006, 0, 0, 0, 0, "B"), T_TAC_IN + 3: (*SEATED, "C")})
    # the old one is gone (hidden) just before the fresh one, seated, takes its place
    prop_keys("magVis", {0: 1, T_TAC_IN - 0.75: 0, T_TAC_IN: 1})
    prop_keys("mag2Vis", {0: 0, 5.5: 1, T_TAC_IN: 0})
    prop_keys("magFresh", {0: 0, T_TAC_IN: 1})
    # the support hand: off the handguard down to the pouch, with the fresh magazine until it is seated, back
    free_keys("L", {
        0: (HANDGUARD, "B"),
        2.5: ((-0.06, -0.06, -0.24), "B"),
        5: ((-0.21, -0.48, 0.15), "B"),
        T_TAC_IN + 3: ((-0.03, -0.09, -0.21), "B"),
        28: ((-0.05, -0.02, -0.3), "B"),
        32: (HANDGUARD, "B"),
    })
    influence_keys(C_L_MAG2, {0: (0, "C"), 4.6: (0, "QUAD"), 5.6: (1, "C"), T_TAC_IN: (0, "C")})
    influence_keys(C_L_MAG, {0: (0, "C"), T_TAC_IN: (1, "C"), T_TAC_IN + 3: (1, "SINE"), 29: (0, "C")})
    free_keys("R", {0: (GRIP, "C")})
    bolt_keys({0: (0, "C")})
    fall(mag, 14.6, T_TAC_IN - 0.5)
    return 38


T_FUMBLE_HIT, FUMBLE_DELAY, T_SLIP, DROP_DELAY = 17, 10, 9, 16
G = 0.0245  # gravity, metres a tick squared


def gun_down(t):
    """The world's down, seen in the rifle's frame at tick t (the rifle is canted and turned while reloading)."""
    scene.frame_set(int(t * FPT), subframe=(t * FPT) % 1.0)
    return gun.matrix_local.to_3x3().inverted() @ Vector((0, -1, 0))


def grip_at(ob, t):
    """Where a grip point is at tick t, in the rifle's frame."""
    scene.frame_set(int(t * FPT), subframe=(t * FPT) % 1.0)
    return (gun.matrix_world.inverted() @ ob.matrix_world).translation.copy()


def old_flight():
    """The old magazine knocked out by the strike at T_TAC_OUT: away forward and down, tumbling. The strike is
    keyed (the fresh one drives it out forward); the flight after 14.6 is the simulation's (fall)."""
    old = {0: (*SEATED, "C"), 13.6: (*SEATED, "QUAD"), 14.6: (-0.004, -0.014, -0.022, 24, 3, 0, "L")}
    for i in range(1, 7):
        f = i * 1.0
        old[14.6 + f] = (-0.006 * f, -0.01 + 0.012 * f - 0.0163 * f * f, -0.004 - 0.05 * f, 24 + 21 * f, 3 + 5 * f, 2 * f, "L")
    return old


def clip_reload_tactical_fumble():
    """Under fire, hands shaking: the fresh magazine knocks the old one out as always, but its nose comes in a
    little crooked and catches the front lip of the well - a hard clack, the rifle jolted - it bounces back off;
    the shooter's eyes drop to the well, the magazine is drawn back and straightened, lined up again and goes in
    the second time, ten ticks late."""
    d = FUMBLE_DELAY
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        5: (-0.05, 0.085, -0.05, -16, 5, 17, "B"),
        10: (-0.08, 0.145, -0.085, -24, 7, 28, "B"),
        13.4: (-0.081, 0.15, -0.083, -24.5, 7.5, 29, "IN"),
        14.3: (-0.084, 0.134, -0.094, -28, 3.5, 31, "OUT"),
        16.8: (-0.085, 0.16, -0.095, -25, 8, 31, "IN"),
        T_FUMBLE_HIT + 0.2: (-0.087, 0.152, -0.099, -27, 5.5, 32.5, "OUT"),   # the clack jolts the rifle
        19.5: (-0.086, 0.152, -0.097, -26, 10, 32, "B"),                     # eyes down at the well
        24: (-0.085, 0.158, -0.095, -25, 9, 31, "B"),
        20.6 + d: (-0.085, 0.162, -0.095, -24, 8, 31, "QUADO"),
        21.4 + d: (-0.085, 0.178, -0.093, -23, 11.5, 31, "OUT"),
        23 + d: (-0.084, 0.166, -0.094, -24, 8.5, 31, "B"),
        27 + d: (-0.06, 0.12, -0.07, -17, 5.5, 22, "B"),
        33 + d: (0.006, -0.008, 0.004, 1.8, -1.2, -1.8, "B"),
        38 + d: (0, 0, 0, 0, 0, 0, "B"),
    })
    mag_keys(mag, old_flight())
    pouch = add(POUCH, (0.0, 0.05, 0.06))
    mag_keys(mag2, {
        5: (*pouch, 30, -35, 0, "B"),
        8: (-0.12, -0.26, 0.13, 22, -22, 0, "B"),
        11: (-0.006, -0.055, 0.075, 4, -5, 0, "B"),
        12.8: (-0.006, -0.062, 0.092, 6, -5, 0, "IN"),
        T_TAC_OUT: (-0.003, -0.032, 0.04, 10, -2, 0, "OUT"),
        15.6: (-0.009, -0.044, 0.026, 28, -7, 8, "IN"),                       # crooked, nose a touch forward
        T_FUMBLE_HIT: (-0.006, -0.029, 0.006, 32, -6, 9, "OUT"),             # on the lip: stopped dead
        17.7: (-0.018, -0.08, 0.05, 40, -12, 14, "B"),                        # bounced back off it, twisting
        20: (-0.011, -0.1, 0.075, 13, -3, 2, "B"),                            # drawn back, straightened
        22.5: (-0.005, -0.066, 0.046, 8, -1, 0, "B"),                         # lined up
        15.6 + d: (-0.002, -0.034, 0.012, 20, 0, 0, "B"),                     # and in, nose first
        17.6 + d: (0.0, -0.012, 0.0, 17, 0, 0, "B"),
        19.6 + d: (0.0, -0.002, 0.0, 6, 0, 0, "QUAD"),
        T_TAC_IN + d: (*SEATED, "C"),
    })
    mag_keys(mag, {T_TAC_IN + d: (*SEATED, "QUADO"), T_TAC_IN + d + 1.5: (0, -0.006, 0, 0, 0, 0, "B"), T_TAC_IN + d + 3: (*SEATED, "C")})
    prop_keys("magVis", {0: 1, 20.8: 0, T_TAC_IN + d: 1})
    prop_keys("mag2Vis", {0: 0, 5.5: 1, T_TAC_IN + d: 0})
    prop_keys("magFresh", {0: 0, T_TAC_IN + d: 1})
    free_keys("L", {
        0: (HANDGUARD, "B"),
        2.5: ((-0.06, -0.06, -0.24), "B"),
        5: ((-0.21, -0.48, 0.15), "B"),
        T_TAC_IN + d + 3: ((-0.03, -0.09, -0.21), "B"),
        28 + d: ((-0.05, -0.02, -0.3), "B"),
        32 + d: (HANDGUARD, "B"),
    })
    influence_keys(C_L_MAG2, {0: (0, "C"), 4.6: (0, "QUAD"), 5.6: (1, "C"), T_TAC_IN + d: (0, "C")})
    influence_keys(C_L_MAG, {0: (0, "C"), T_TAC_IN + d: (1, "C"), T_TAC_IN + d + 3: (1, "SINE"), 29 + d: (0, "C")})
    free_keys("R", {0: (GRIP, "C")})
    bolt_keys({0: (0, "C")})
    # the eyes go down to the well after the clack, and back up as it goes in
    curve_keys("look", {0: 0, T_FUMBLE_HIT: 0, T_FUMBLE_HIT + 1.5: 6, 24: 5, 20.6 + d: 1, 23 + d: 0})
    fall(mag, 14.6, 20.6)
    return 38 + d


def clip_reload_tactical_drop():
    """Under fire, hands shaking: the fresh magazine, coming up from the pouch, slips out of the fingers - the hand
    snatches after it and misses, it falls tumbling out of sight - the eyes follow it down; back to the pouch for
    another, and the reload carries on from there, sixteen ticks late."""
    d = DROP_DELAY
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        5: (-0.05, 0.085, -0.05, -16, 5, 17, "B"),
        8.6: (-0.066, 0.118, -0.07, -20, 6, 23, "B"),
        T_SLIP + 1.4: (-0.07, 0.11, -0.075, -22, 1.5, 25, "OUT"),             # a start: the eyes go down after it
        13: (-0.075, 0.125, -0.08, -23, 2, 26, "B"),
        18: (-0.078, 0.138, -0.083, -23, 5.5, 27, "B"),
        10 + d: (-0.08, 0.145, -0.085, -24, 7, 28, "B"),
        13.4 + d: (-0.081, 0.15, -0.083, -24.5, 7.5, 29, "IN"),
        14.3 + d: (-0.084, 0.134, -0.094, -28, 3.5, 31, "OUT"),
        17 + d: (-0.085, 0.16, -0.095, -25, 8, 31, "B"),
        20.6 + d: (-0.085, 0.162, -0.095, -24, 8, 31, "QUADO"),
        21.4 + d: (-0.085, 0.178, -0.093, -23, 11.5, 31, "OUT"),
        23 + d: (-0.084, 0.166, -0.094, -24, 8.5, 31, "B"),
        27 + d: (-0.06, 0.12, -0.07, -17, 5.5, 22, "B"),
        33 + d: (0.006, -0.008, 0.004, 1.8, -1.2, -1.8, "B"),
        38 + d: (0, 0, 0, 0, 0, 0, "B"),
    })
    # the old one: knocked out by the second fresh magazine, sixteen ticks late
    mag_keys(mag, {t + (d if t > 0 else 0): v for t, v in old_flight().items()})
    # the fresh one: up from the pouch, slipping at T_SLIP and falling under gravity, tumbling
    pouch = add(POUCH, (0.0, 0.05, 0.06))
    slip = Vector((-0.1, -0.215, 0.12))
    keys = {
        5: (*pouch, 30, -35, 0, "B"),
        8: (-0.12, -0.26, 0.13, 22, -22, 0, "B"),
        T_SLIP: (*slip, 19, -19, 0, "L"),
    }
    # it falls about its middle (where the hand had it), not about the lug: the lug's path follows from the turn
    from mathutils import Euler

    def rot(rock, roll, yaw):
        return Euler((math.radians(rock), math.radians(yaw), math.radians(roll)), "XYZ").to_matrix()

    arm = MAG_HOLD - LUG
    centre = LUG + slip + rot(19, -19, 0) @ arm
    vel = Vector((0.004, 0.012, -0.004))  # it was still moving up a little as it went
    for i in range(1, 17):
        dt = i * 0.5
        c = centre + vel * dt + gun_down(T_SLIP + dt) * (0.5 * G * dt * dt)
        rk, rl, yw = 19 - 34 * dt, -19 + 21 * dt, 9 * dt
        off = c - LUG - rot(rk, rl, yw) @ arm
        keys[T_SLIP + dt] = (*off, rk, rl, yw, "L")
    last = keys[T_SLIP + 8.0]
    keys[T_SLIP + 8.0] = (*last[:6], "C")                                    # gone: it waits out of sight
    keys.update({
        5 + d: (*pouch, 30, -35, 0, "B"),                                     # the second one, from the pouch
        8 + d: (-0.12, -0.26, 0.13, 22, -22, 0, "B"),
        11 + d: (-0.006, -0.055, 0.075, 4, -5, 0, "B"),
        12.8 + d: (-0.006, -0.062, 0.092, 6, -5, 0, "IN"),
        T_TAC_OUT + d: (-0.003, -0.032, 0.04, 10, -2, 0, "OUT"),
        15.6 + d: (-0.002, -0.034, 0.012, 20, 0, 0, "B"),
        17.6 + d: (0.0, -0.012, 0.0, 17, 0, 0, "B"),
        19.6 + d: (0.0, -0.002, 0.0, 6, 0, 0, "QUAD"),
        T_TAC_IN + d: (*SEATED, "C"),
    })
    mag_keys(mag2, keys)
    mag_keys(mag, {T_TAC_IN + d: (*SEATED, "QUADO"), T_TAC_IN + d + 1.5: (0, -0.006, 0, 0, 0, 0, "B"), T_TAC_IN + d + 3: (*SEATED, "C")})
    prop_keys("magVis", {0: 1, T_TAC_IN + d - 0.75: 0, T_TAC_IN + d: 1})
    prop_keys("mag2Vis", {0: 0, 5.5: 1, T_SLIP + 8.0: 0, 5 + d + 0.4: 1, T_TAC_IN + d: 0})
    prop_keys("magFresh", {0: 0, T_TAC_IN + d: 1})
    # the hand: with it to the slip, then a snatch after it that misses, back down to the pouch for another
    influence_keys(C_L_MAG2, {0: (0, "C"), 4.6: (0, "QUAD"), 5.6: (1, "C"), T_SLIP: (1, "QUAD"), T_SLIP + 0.4: (0, "C"),
                              4.6 + d: (0, "QUAD"), 5.6 + d: (1, "C"), T_TAC_IN + d: (0, "C")})
    at_slip = grip_at(mag2_grip, T_SLIP)
    free_keys("L", {
        0: (HANDGUARD, "B"),
        2.5: ((-0.06, -0.06, -0.24), "B"),
        5: ((-0.21, -0.48, 0.15), "B"),
        T_SLIP: (tuple(at_slip), "OUT"),
        T_SLIP + 1.2: (tuple(at_slip + Vector((0.01, -0.07, 0.02))), "B"),    # the snatch after it
        T_SLIP + 3: (tuple(at_slip + Vector((0.0, -0.13, 0.03))), "B"),
        5 + d: ((-0.21, -0.48, 0.15), "B"),
        T_TAC_IN + d + 3: ((-0.03, -0.09, -0.21), "B"),
        28 + d: ((-0.05, -0.02, -0.3), "B"),
        32 + d: (HANDGUARD, "B"),
    })
    influence_keys(C_L_MAG, {0: (0, "C"), T_TAC_IN + d: (1, "C"), T_TAC_IN + d + 3: (1, "SINE"), 29 + d: (0, "C")})
    free_keys("R", {0: (GRIP, "C")})
    bolt_keys({0: (0, "C")})
    # the eyes follow it down, then come back to the pouch and the well
    curve_keys("look", {0: 0, T_SLIP: 0, T_SLIP + 1.5: 9, T_SLIP + 4: 13, T_SLIP + 8: 7, 5 + d: 3, 10 + d: 0})
    fall(mag2, T_SLIP, T_SLIP + 8.0)
    fall(mag, 14.6 + d, T_TAC_IN + d - 0.5)
    return 38 + d


def reload_empty(with_mag):
    """An empty rifle: the left hand takes the magazine, thumbs the paddle and rocks it out - it is let fall
    (with a flick of the rifle) - fetches a full one, in nose first, rocked home, a tug; then the rifle is
    rolled over to the right, the right hand reaches over and racks the handle, lets it fly home - the jolt -
    and the rifle comes back on target."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        7: (-0.08, 0.15, -0.09, -24, 7, 30, "B"),
        11.5: (-0.092, 0.18, -0.105, -29, 8, 37, "B"),
        13: (-0.097, 0.175, -0.112, -32, 6, 39, "OUT"),           # the wrench as the magazine comes out
        T_MAG_DROP: (-0.1, 0.185, -0.112, -38, 4, 40, "OUT"),     # the flick to let it fall clear
        18: (-0.1, 0.19, -0.115, -33, 6, 42, "B"),
        25: (-0.1, 0.19, -0.115, -31, 7, 40, "B"),
        29.6: (-0.1, 0.195, -0.115, -29, 8, 38, "QUADO"),
        30.5: (-0.1, 0.21, -0.113, -28, 12, 38, "OUT"),           # seated: the slap
        32.5: (-0.098, 0.196, -0.114, -29, 8.5, 37, "B"),
        36: (-0.095, 0.19, -0.11, -28, 7, 36, "B"),
        42: (-0.07, 0.19, -0.115, -6, 6, 42, "B"),
        48: (-0.04, 0.2, -0.12, 19, 5, 48, "B"),
        T_CHARGE: (-0.04, 0.2, -0.12, 21, 6, 48, "B"),
        T_CHARGE + 2.6: (-0.045, 0.205, -0.104, 23, 7, 48, "B"),  # the pull drags the rifle back a little
        T_CHARGE + 3.2: (-0.042, 0.196, -0.128, 20, 2.5, 47, "OUT"),  # let go: the carrier slams home
        T_CHARGE + 6: (-0.045, 0.19, -0.115, 18, 4, 44, "B"),
        61: (-0.05, 0.16, -0.09, 7, 3, 28, "B"),
        66.5: (0.005, -0.007, 0.004, -1.5, -1, -2, "B"),
        72: (0, 0, 0, 0, 0, 0, "B"),
    })
    pouch = add(POUCH, (0.0, 0.05, 0.06))
    keys = {}
    if with_mag:
        keys.update({0: (*SEATED, "C"), 11: (*SEATED, "IN"), 13: (0, -0.004, 0, 12, 0, 0, "OUT"), 15: (0, -0.03, 0, 28, 0, 0, "L")})
        # let go: it falls free, tumbling forward
        for i in range(1, 8):
            f = float(i)
            keys[15 + f] = (0.002 * f, -0.03 - 0.0163 * f * f - 0.006 * f, -0.012 * f, 28 + 14 * f, 4 * f, 0, "L")
    keys.update({
        23: (*pouch, 30, -35, 0, "B"),
        25.5: (-0.12, -0.26, 0.1, 30, -20, 0, "B"),
        27.8: (-0.004, -0.055, -0.012, 28, -3, 0, "B"),          # nose first into the well
        28.6: (0, -0.025, 0, 28, 0, 0, "IN"),                      # hooked on the front lug ...
        T_MAG_IN: (*SEATED, "QUADO"),                              # ... rocked back: click
        T_MAG_IN + 3: (*SEATED, "B"),
        T_MAG_IN + 4.5: (0, -0.006, 0, 0, 0, 0, "B"),             # the tug
        T_MAG_IN + 6: (*SEATED, "C"),
    })
    mag_keys(mag, keys)
    prop_keys("magVis", {0: 1 if with_mag else 0, 22: 0, 23.2: 1})
    prop_keys("mag2Vis", {0: 0})
    prop_keys("magFresh", {0: 0, 23: 1})
    seated_hold = tuple(MAG_HOLD)
    if with_mag:
        free_keys("L", {
            0: (HANDGUARD, "B"),
            4.5: ((-0.05, -0.07, -0.26), "B"),
            9: (seated_hold, "B"),
            T_MAG_DROP + 1: ((-0.04, -0.17, -0.12), "B"),
            19: ((-0.15, -0.36, 0.06), "B"),
            23: ((-0.21, -0.48, 0.15), "B"),
            36: ((-0.03, -0.09, -0.21), "B"),
            40: ((-0.05, -0.02, -0.3), "B"),
            44: (HANDGUARD, "B"),
        })
        influence_keys(C_L_MAG, {0: (0, "C"), 8.2: (0, "QUAD"), 9: (1, "C"), T_MAG_DROP: (1, "QUAD"), 16: (0, "C"),
                                 23: (0, "QUAD"), 23.6: (1, "C"), 36: (1, "SINE"), 41: (0, "C")})
    else:
        free_keys("L", {
            0: (HANDGUARD, "B"),
            5: ((-0.06, -0.12, -0.2), "B"),
            12: ((-0.15, -0.36, 0.06), "B"),
            23: ((-0.21, -0.48, 0.15), "B"),
            36: ((-0.03, -0.09, -0.21), "B"),
            40: ((-0.05, -0.02, -0.3), "B"),
            44: (HANDGUARD, "B"),
        })
        influence_keys(C_L_MAG, {0: (0, "C"), 23: (0, "QUAD"), 23.6: (1, "C"), 36: (1, "SINE"), 41: (0, "C")})
    # the right hand: over the top to the charging handle, racks it, lets go, back to the grip
    free_keys("R", {
        0: (GRIP, "B"),
        41: (GRIP, "B"),
        45: ((0.07, 0.11, 0.02), "B"),
        49: (add(HANDLE, (0.012, 0.03, 0.006)), "B"),
        T_CHARGE + 3.2: (add(HANDLE, (0.02, 0.01, BOLT_TRAVEL + 0.01)), "B"),
        T_CHARGE + 5.5: (add(HANDLE, (0.04, 0.025, BOLT_TRAVEL + 0.01)), "B"),
        60: ((0.06, 0.02, 0.1), "B"),
        66: (GRIP, "B"),
    })
    influence_keys(C_R_HANDLE, {0: (0, "C"), 49: (0, "QUAD"), 50.5: (1, "C"), T_CHARGE + 3: (1, "QUADO"), T_CHARGE + 3.6: (0, "C")})
    bolt_keys({0: (0, "C"), T_CHARGE: (0, "IN"), T_CHARGE + 2.6: (1, "C"), T_CHARGE + 3.0: (1, "QUAD"), T_CHARGE + 3.9: (0, "QUADO"),
               T_CHARGE + 4.3: (0.035, "QUAD"), T_CHARGE + 4.8: (0, "C")})
    # the right forearm rolled over to reach across the top for the handle; a glance after the magazine let fall
    curve_keys("twistR", {0: 0, 44: 0, 49: 60, T_CHARGE + 4: 55, 60: 0})
    curve_keys("look", {0: 0, T_MAG_DROP: 0, T_MAG_DROP + 2: 3, 21: 0} if with_mag else {0: 0})
    if with_mag:
        fall(mag, T_MAG_DROP, 22)
    return 72


def clip_check():
    """Checking the magazine: the rifle canted in, the hand thumbs the paddle, rocks it out and tips it back
    so the rounds in the lips show, holds it there, rocks it home with a click and a tug."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        7: (-0.06, 0.13, -0.06, -24, 5, 28, "B"),
        16: (-0.062, 0.135, -0.062, -26, 6, 29, "B"),
        25: (-0.06, 0.132, -0.06, -25, 5, 28, "B"),
        28.6: (-0.06, 0.128, -0.06, -24, 4.5, 28, "QUADO"),
        29.4: (-0.06, 0.142, -0.058, -23, 8, 28, "OUT"),         # rocked home
        31: (-0.06, 0.13, -0.06, -24, 5, 28, "B"),
        36: (0.004, -0.005, 0.003, 1, -0.8, -1.2, "B"),
        40: (0, 0, 0, 0, 0, 0, "B"),
    })
    mag_keys(mag, {
        0: (*SEATED, "C"),
        11: (*SEATED, "IN"),
        13: (0, -0.006, 0, 14, 0, 0, "OUT"),
        16.5: (0, -0.05, 0.03, 34, -10, 0, "B"),                 # pulled clear and tipped back towards the eye
        20.5: (0, -0.053, 0.032, 36, -12, 0, "B"),
        24.5: (0, -0.05, 0.03, 34, -10, 0, "B"),
        27.5: (0, -0.012, 0, 22, 0, 0, "B"),
        28.6: (0, -0.002, 0, 8, 0, 0, "QUADO"),
        29.4: (*SEATED, "B"),
        31: (0, -0.005, 0, 0, 0, 0, "B"),
        32.5: (*SEATED, "C"),
    })
    prop_keys("magVis", {0: 1})
    prop_keys("mag2Vis", {0: 0})
    prop_keys("magFresh", {0: 0})
    free_keys("L", {0: (HANDGUARD, "B"), 3.5: ((-0.05, -0.07, -0.26), "B"), 7: (tuple(MAG_HOLD), "B"),
                    31: ((-0.03, -0.09, -0.21), "B"), 35: ((-0.05, -0.02, -0.3), "B"), 39: (HANDGUARD, "B")})
    influence_keys(C_L_MAG, {0: (0, "C"), 6.3: (0, "QUAD"), 7.2: (1, "C"), 31: (1, "SINE"), 35.5: (0, "C")})
    free_keys("R", {0: (GRIP, "C")})
    bolt_keys({0: (0, "C")})
    return 40


def clip_inspect():
    """Looking it over: turned in to show the right side, a press check (the bolt eased back to see brass in
    the chamber, let snap home), rolled over to the left to look at the magazine and slap it home, back."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        10: (-0.11, 0.1, -0.09, 12, 6, 52, "B"),
        20: (-0.115, 0.11, -0.095, 16, 9, 56, "B"),
        28: (-0.11, 0.115, -0.1, 18, 7, 54, "B"),
        34.6: (-0.108, 0.112, -0.098, 16, 7, 51, "QUADO"),
        35.4: (-0.106, 0.106, -0.104, 14, 8.8, 50, "OUT"),       # the bolt snaps home
        38: (-0.105, 0.11, -0.095, 14, 7, 50, "B"),
        45: (-0.06, 0.13, -0.08, -48, 10, -18, "B"),
        49.2: (-0.056, 0.128, -0.08, -46, 9, -17, "QUADO"),
        50.2: (-0.056, 0.142, -0.079, -44, 5, -16, "OUT"),       # the slap
        53: (-0.055, 0.13, -0.08, -44, 8, -16, "B"),
        58: (-0.03, 0.07, -0.04, -20, 4, -8, "B"),
        64: (0, 0, 0, 0, 0, 0, "B"),
    })
    mag_keys(mag, {0: (*SEATED, "C"), 49.6: (*SEATED, "QUADO"), 50.2: (0, 0.003, 0, 0, 0, 0, "B"), 51.5: (*SEATED, "C")})
    prop_keys("magVis", {0: 1})
    prop_keys("mag2Vis", {0: 0})
    prop_keys("magFresh", {0: 0})
    free_keys("R", {0: (GRIP, "B"), 20: (GRIP, "B"), 23: ((0.07, 0.1, 0.03), "B"), 26: (add(HANDLE, (0.01, 0.025, 0.004)), "B"),
                    35.6: (add(HANDLE, (0.02, 0.012, BOLT_TRAVEL * 0.42)), "B"), 37: (add(HANDLE, (0.03, 0.02, 0.04)), "B"), 41: (GRIP, "B")})
    influence_keys(C_R_HANDLE, {0: (0, "C"), 26: (0, "QUAD"), 27.2: (1, "C"), 35.1: (1, "QUADO"), 35.6: (0, "C")})
    bolt_keys({0: (0, "C"), 29: (0, "SINE"), 32: (0.4, "B"), 35: (0.4, "QUAD"), 35.5: (0, "C")})
    curve_keys("twistR", {0: 0, 20: 0, 26: 50, 36: 45, 41: 0})
    hold = tuple(MAG_HOLD + Vector((0, -0.03, 0)))
    free_keys("L", {0: (HANDGUARD, "B"), 44: (HANDGUARD, "B"), 47: ((-0.06, -0.12, -0.22), "B"), 49.2: (hold, "QUADO"),
                    50.2: (add(hold, (0, 0.03, 0)), "OUT"), 52: (add(hold, (0, -0.02, 0)), "B"), 57: ((-0.05, -0.03, -0.3), "B"),
                    60: (HANDGUARD, "B")})
    return 64


def clip_dry():
    """The click of an empty gun: the trigger finger's twitch, then a quick cant to look at the rifle."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "QUADO"),
        1.5: (0, -0.004, -0.006, 0, -1.2, 0, "B"),
        4: (0, 0, 0, 0, 0, 0, "B"),
        9: (-0.03, 0.035, 0, -16, 4, 10, "B"),
        13: (-0.032, 0.036, 0, -17, 4.5, 11, "B"),
        22: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("magVis", 1), ("mag2Vis", 0), ("magFresh", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {0: (HANDGUARD, "C")})
    free_keys("R", {0: (GRIP, "C")})
    return 22


def clip_draw():
    """Drawn: up from low and canted into the shoulder, overshooting a hair and settling."""
    gun_keys({
        0: (0.04, -0.25, 0.1, -35, -30, 0, "B"),
        5: (0.02, -0.08, 0.03, -14, -8, 0, "B"),
        9: (-0.003, 0.008, -0.004, 2, 1.5, -1, "B"),
        12: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("magVis", 1), ("mag2Vis", 0), ("magFresh", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {0: (add(HANDGUARD, (-0.02, -0.06, 0.05)), "B"), 8: (HANDGUARD, "B")})
    free_keys("R", {0: (GRIP, "C")})
    return 12


CLEAR_TAP, CLEAR_RACK, CLEAR_RELEASE, CLEAR_LENGTH = 6, 15, 17, 28  # AkItem.CLEAR_*


def clip_clear_jam():
    """Tap, rack: a stovepipe cleared the drilled way. The rifle is brought in and rolled a little to show the port,
    the support hand drops under the magazine and slaps it home from below (a jolt through the rifle), comes back to
    the handguard; the rifle rolls further over, the right hand reaches across, grabs the charging handle and rips it
    back to the stop - the stuck case spins out - lets it fly; the carrier slams home and the rifle comes back up."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        3: (-0.03, 0.06, -0.03, 8, 4, 10, "B"),
        CLEAR_TAP - 0.6: (-0.04, 0.08, -0.04, 10, 5, 14, "IN"),
        CLEAR_TAP: (-0.041, 0.096, -0.04, 9, 9, 14, "OUT"),          # the slap from below lifts the rifle
        8: (-0.04, 0.086, -0.045, 14, 6, 20, "B"),
        11.5: (-0.04, 0.17, -0.1, 21, 6, 44, "B"),                   # rolled over for the handle
        CLEAR_RACK: (-0.045, 0.18, -0.11, 22, 7, 46, "IN"),
        CLEAR_RACK + 1.6: (-0.05, 0.186, -0.095, 24, 8, 46, "B"),    # the hard pull drags the rifle back
        CLEAR_RELEASE: (-0.047, 0.176, -0.122, 21, 3, 45, "OUT"),    # let fly: the carrier slams home
        19.5: (-0.045, 0.17, -0.11, 19, 5, 42, "B"),
        23: (-0.03, 0.09, -0.06, 8, 3, 22, "B"),
        26: (0.004, -0.006, 0.003, -1.2, -0.8, -1.5, "B"),
        CLEAR_LENGTH: (0, 0, 0, 0, 0, 0, "B"),
    })
    mag_keys(mag, {0: (*SEATED, "C"), CLEAR_TAP - 0.3: (*SEATED, "QUAD"), CLEAR_TAP: (0, 0.0015, 0, 0, 0, 0, "OUT"), CLEAR_TAP + 1: (*SEATED, "C")})
    for p, v in (("magVis", 1), ("mag2Vis", 0), ("magFresh", 0)):
        prop_keys(p, {0: v})
    # the support hand: down under the magazine, the slap, back to the handguard for the rack
    free_keys("L", {
        0: (HANDGUARD, "B"),
        3.5: ((-0.03, -0.13, -0.25), "B"),
        CLEAR_TAP - 0.6: ((-0.004, -0.255, -0.24), "IN"),
        CLEAR_TAP: ((-0.004, -0.205, -0.24), "OUT"),
        CLEAR_TAP + 1.5: ((-0.012, -0.235, -0.25), "B"),
        10: ((-0.03, -0.06, -0.3), "B"),
        12.5: (HANDGUARD, "B"),
    })
    # the right hand: over the top to the handle, rips it back, lets go, back to the grip
    free_keys("R", {
        0: (GRIP, "B"),
        9: (GRIP, "B"),
        12: ((0.07, 0.11, 0.02), "B"),
        14: (add(HANDLE, (0.012, 0.03, 0.006)), "B"),
        CLEAR_RELEASE: (add(HANDLE, (0.02, 0.01, BOLT_TRAVEL + 0.01)), "B"),
        19: (add(HANDLE, (0.04, 0.025, BOLT_TRAVEL + 0.01)), "B"),
        22: ((0.06, 0.02, 0.1), "B"),
        25: (GRIP, "B"),
    })
    influence_keys(C_R_HANDLE, {0: (0, "C"), 14: (0, "QUAD"), 14.8: (1, "C"), CLEAR_RACK + 1.9: (1, "QUADO"), CLEAR_RELEASE + 0.3: (0, "C")})
    bolt_keys({0: (0, "C"), CLEAR_RACK: (0, "IN"), CLEAR_RACK + 1.6: (1, "C"), CLEAR_RACK + 1.9: (1, "QUAD"), CLEAR_RELEASE + 0.7: (0, "QUADO"),
               CLEAR_RELEASE + 1.1: (0.035, "QUAD"), CLEAR_RELEASE + 1.6: (0, "C")})
    curve_keys("twistR", {0: 0, 10: 0, 14: 60, 18: 55, 24: 0})
    curve_keys("look", {0: 0, 4: 2, 8: 4, 13: 5, 18: 2, 24: 0})
    return CLEAR_LENGTH


CLIPS = {
    "reload_tactical": clip_reload_tactical,
    "reload_tactical_fumble": clip_reload_tactical_fumble,
    "reload_tactical_drop": clip_reload_tactical_drop,
    "reload_empty": lambda: reload_empty(True),
    "reload_nomag": lambda: reload_empty(False),
    "check": clip_check,
    "inspect": clip_inspect,
    "dry": clip_dry,
    "draw": clip_draw,
    "clear_jam": clip_clear_jam,
}


# ---------------------------------------------------------------- sampling
def m34(m):
    return [m[r][c] for r in range(3) for c in range(4)]


def sample():
    gi = gun.matrix_world.inverted()
    e = gun.rotation_euler
    out = [gun.location.x, gun.location.y, gun.location.z, math.degrees(e.z), math.degrees(e.x), math.degrees(e.y)]
    for m in (mag, mag2):
        out += m34(gi @ m.matrix_world @ Matrix.Translation(-LUG))
    for side in ("L", "R"):
        out += list((gi @ hands[side][0].matrix_world).translation)
    out.append(carrier.location.z / BOLT_TRAVEL)
    out += [gun["magVis"], gun["mag2Vis"], gun["magFresh"]]
    out += animkit.hand_quat(hands["L"][0], gi, gun["twistL"])
    out += animkit.hand_quat(hands["R"][0], gi, gun["twistR"])
    out.append(gun["look"])  # replaced by the head's pitch, yaw, roll (animkit.camera)
    return out


TAKES = ("reload_tactical", "reload_tactical_fumble", "reload_tactical_drop", "reload_empty", "reload_nomag", "clear_jam")


def jobs():
    """Every clip, and the takes of the reloads: (name, build, take)."""
    for name, build in CLIPS.items():
        yield name, build, 0
        if name in TAKES:
            for n in (1, 2):
                yield f"{name}@{n}", build, n


# ---------------------------------------------------------------- preview: arms with IK, a first-person camera
def arm(name, shoulder, hand, pole, upper, fore):
    arm_data = bpy.data.armatures.new(name)
    ob = bpy.data.objects.new(name, arm_data)
    scene.collection.objects.link(ob)
    ob.parent = root
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.mode_set(mode="EDIT")
    s = Vector(shoulder)
    h = hand.matrix_world.translation.copy()
    h = (root.matrix_world.inverted() @ h)
    mid = (s + h) / 2 + Vector(pole).normalized() * 0.1
    b1 = arm_data.edit_bones.new("upper")
    b1.head, b1.tail = s, s + (mid - s).normalized() * upper
    b2 = arm_data.edit_bones.new("fore")
    b2.head, b2.tail = b1.tail, b1.tail + (h - b1.tail).normalized() * fore
    b2.parent = b1
    b2.use_connect = True
    bpy.ops.object.mode_set(mode="OBJECT")
    pole_ob = empty(name + "_pole", root, Vector(shoulder) + Vector(pole))
    ik = ob.pose.bones["fore"].constraints.new("IK")
    ik.target = hand
    ik.pole_target = pole_ob
    ik.chain_count = 2
    skin = bpy.data.materials.new(name + "_sleeve")
    skin.use_nodes = True
    skin.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.09, 0.1, 0.07, 1)
    glove = bpy.data.materials.new(name + "_hand")
    glove.use_nodes = True
    glove.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.05, 1)
    for bone, length, r, m in (("upper", upper, 0.055, skin), ("fore", fore, 0.045, skin)):
        bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=length, vertices=16)
        cyl = bpy.context.active_object
        cyl.data.materials.append(m)
        cyl.data.transform(Matrix.Rotation(-math.pi / 2, 4, "X") @ Matrix.Translation((0, 0, length / 2)))
        cyl.parent = ob
        cyl.parent_type = "BONE"
        cyl.parent_bone = bone
        cyl.location = (0, -length, 0)
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.05)
    fist = bpy.context.active_object
    fist.scale = (0.8, 1.0, 1.2)
    fist.data.materials.append(glove)
    fist.parent = hand
    return ob


def preview_setup():
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 12
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = 640, 360
    scene.view_settings.view_transform = "AgX"
    world = bpy.data.worlds.new("w")
    scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.42, 0.55, 0.72, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 1.0
    sun = bpy.data.lights.new("sun", "SUN")
    sun.energy = 3.0
    so = bpy.data.objects.new("sun", sun)
    scene.collection.objects.link(so)
    so.rotation_euler = (math.radians(40), math.radians(-20), math.radians(30))
    cam = bpy.data.cameras.new("eye")
    cam.sensor_fit = "VERTICAL"
    cam.angle = math.radians(70)
    co = bpy.data.objects.new("eye", cam)
    scene.collection.objects.link(co)
    co.parent = root
    # an eye behind and above the rifle, looking down its length, as a shooter sees it
    co.location = (-0.24, 0.3, 0.72)
    look = Vector((0.0, -0.04, -0.3)) - Vector(co.location)
    f = look.normalized()
    right = f.cross(Vector((0, 1, 0))).normalized()
    up = right.cross(f)
    co.rotation_euler = Matrix((right, up, -f)).transposed().to_euler()  # item space: y is up
    scene.camera = co
    arm("arm_R", (0.54, -0.63, 0.49), hands["R"][0], (0.65, -1.0, 0.25), 0.36 * 0.9, 0.656)
    arm("arm_L", (-0.9, -0.6, 0.07), hands["L"][0], (-0.65, -1.0, 0.2), 0.6 * 0.9, 0.656)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    if PREVIEW:
        preview_setup()
    blob = []
    for name, build, take in jobs():
        if ONLY and name not in ONLY:
            continue
        TAKE = animkit.Take(take)
        clear()
        length = build()
        n = length * SPT + 1
        samples = []
        for i in range(n):
            scene.frame_set(int(i * FPT / SPT), subframe=(i * FPT / SPT) % 1.0)
            samples.append(sample())
        # the eyes on the rifle while it is in close; not for the draw and the dry click (on target)
        head = animkit.camera(samples, SPT, [s[-1] for s in samples], down=0.0 if name in ("draw", "dry") else 4.5)
        samples = [s[:-1] + h for s, h in zip(samples, head)]
        blob.append((name, length, samples))
        print(name, length, "ticks", len(samples), "samples", flush=True)
        blend = [x.split("=", 1)[1] for x in sys.argv if x.startswith("--save-blend=")]
        if blend and ONLY:
            # the scene as keyed, to open in Blender and scrub through (the timeline runs FPT frames a tick)
            scene.frame_start, scene.frame_end = 0, length * FPT
            bpy.ops.wm.save_as_mainfile(filepath=os.path.abspath(blend[0]))
        if PREVIEW:
            from PIL import Image  # noqa: E402 (system Python's PIL, see the venv)
            frames = []
            for t in range(length + 1):
                scene.frame_set(t * FPT)
                path = os.path.join(OUT, f"_{name}_{t:03d}.png")
                scene.render.filepath = path
                bpy.ops.render.render(write_still=True)
                frames.append(path)
            imgs = [Image.open(p).convert("RGB") for p in frames]
            imgs[0].save(os.path.join(OUT, f"{name}.gif"), save_all=True, append_images=imgs[1:], duration=50, loop=0)
            for p in frames:
                os.remove(p)
    if not ONLY:
        with gzip.open(os.path.join(OUT, "ak47.anim.gz"), "wb") as f:
            f.write(struct.pack(">I", len(blob)))
            for name, length, samples in blob:
                nb = name.encode()
                f.write(struct.pack(">H", len(nb)) + nb)
                f.write(struct.pack(">III", length, SPT, len(samples)))
                for s in samples:
                    f.write(struct.pack(">51f", *s))
