"""The Glock 19's first-person animations, keyframed in Blender and exported for the mod.

The scene is in the mod's item space (x right, y up, z back; origin at the middle of the grip, one unit a
metre), under a root that turns it to Blender's Z-up:
  gun_pose      the pistol's pose in the hands (location, rotation YXZ = yaw, pitch, roll): what GlockMotion
                applies about the grip; everything below moves with it
  frame/slide/barrel  the game meshes (glock19.mesh.gz), for the preview renders
  old, new      the magazine coming out and the one going in, their origin at the seat in the grip
  *_grip        where a hand takes hold (a magazine's base, the slide's serrations)
  hand_L, hand_R  the hands: a free path, blended onto a grip by keyed constraint influences

The clips are sampled four times a tick into glock19.anim.gz, the same layout as the AK's (AkClips):
  per clip: name, length (ticks), samples; per sample 51 floats:
    gun x y z roll pitch yaw (degrees) | old magazine 3x4 | new magazine 3x4 | left hand xyz | right hand xyz |
    slide 0..1.12 | old shown | new shown | new seated | left hand turn xyzw | right hand turn xyzw |
    head pitch yaw roll (degrees)
A magazine let fall is simulated (Bullet, animkit.rigid_fall); the reloads come in three takes ("name",
"name@1", "name@2"), the game picks one at random.
  a magazine's matrix moves it from its seat (GlockItemRenderer.submitMagazine's "from").

    python glock_anim.py <glock19.mesh.gz> <glock19.png> <out_dir> [--preview] [--clips=a,b]
"""
import gzip
import math
import os
import struct
import sys

import bpy  # noqa: I001
from mathutils import Euler, Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import animkit  # noqa: E402

MESH, TEX, OUT = sys.argv[1:4]
PREVIEW = "--preview" in sys.argv
STEP = int(([a.split("=", 1)[1] for a in sys.argv if a.startswith("--step=")] or ["1"])[0])  # preview: every n-th tick
ONLY = [a.split("=", 1)[1].split(",") for a in sys.argv if a.startswith("--clips=")]
ONLY = ONLY[0] if ONLY else None
SPT = 4  # samples per tick
FPT = 4  # frames per tick

# ---- the gun (item space, see GlockItemRenderer)
SLIDE_TOP = 0.0689
SLIDE_TRAVEL = 0.03
GRIP_ANGLE = math.radians(20.0)  # the magazine well's rake
SEAT = Vector((0.0, 0.034, -0.02415))
MAG_DOWN = Vector((0.0, -math.cos(GRIP_ANGLE), math.sin(GRIP_ANGLE)))
MAG_TILT = Matrix.Rotation(-GRIP_ANGLE, 4, "X")  # a magazine's own frame (down -Y) to the grip's slope
LENGTH = {"std": 0.0925, "ext": 0.2015}             # top of the lips to the bottom of the floor plate
BARREL_PIVOT = Vector((0.0, 0.058, -0.146))
# ---- the hands (see GlockMotion)
GRIP = (0.0, -0.004, 0.006)
SUPPORT = (-0.022, -0.016, -0.006)
POUCH = (-0.12, -0.32, -0.14)
PRESS = Vector((-0.006, SLIDE_TOP + 0.014, -0.127))    # pinching the front serrations
OVERHAND = Vector((-0.008, SLIDE_TOP + 0.016, -0.006))  # the palm over the rear serrations
T_RELEASE, T_DROP, T_IN, RELOAD_TACTICAL = 4, 6, 19, 27
T_RACK_BACK, T_RACK_FORWARD, RELOAD_EMPTY = 25, 28, 35
PRESS_BACK, PRESS_HOME, INSPECT = 26, 32, 62

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
gun.rotation_mode = "YXZ"  # matrix = Rz(roll) Rx(pitch) Ry(yaw), as GlockMotion turns the pose stack


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
mat = bpy.data.materials.new("glock19")
mat.use_nodes = True
nt = mat.node_tree
bsdf = nt.nodes["Principled BSDF"]
img = nt.nodes.new("ShaderNodeTexImage")
img.image = bpy.data.images.load(os.path.abspath(TEX))
nt.links.new(img.outputs[0], bsdf.inputs["Base Color"])
bsdf.inputs["Roughness"].default_value = 0.4
bsdf.inputs["Metallic"].default_value = 0.2


def mesh_object(name, data, parent, xf=Matrix()):
    verts, faces, uvs = [], [], []
    for q in range(0, len(data), 32):
        idx = []
        for k in range(4):
            v = data[q + k * 8:q + k * 8 + 8]
            verts.append(xf @ Vector(v[:3]))
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


mesh_object("frame", PARTS["frame"], gun)
slide = mesh_object("slide", PARTS["slide"], gun)
barrel = mesh_object("barrel", PARTS["barrel"], gun, Matrix.Translation(-BARREL_PIVOT))
barrel.location = BARREL_PIVOT
mags = {}
for name in ("old", "new"):
    m = mesh_object(name, PARTS["mag"], gun, MAG_TILT)
    m_ext = mesh_object(name + "_ext", PARTS["mag_ext"], m, MAG_TILT)
    m.location = SEAT
    m.rotation_mode = "XYZ"
    mags[name] = (m, m_ext)
OLD, NEW = mags["old"][0], mags["new"][0]
new_grip = empty("new_grip", NEW)
press_grip = empty("press_grip", slide, PRESS)
rack_grip = empty("rack_grip", slide, OVERHAND)


def barrel_follow(_scene=None, _dg=None):
    """The barrel rides back with the slide for 3 mm, then its rear cams down (the Browning tilt)."""
    back = slide.location.z
    barrel.location = BARREL_PIVOT + Vector((0.0, 0.0, min(back, 0.003)))
    barrel.rotation_euler = (math.radians(1.5) * min(1.0, max(0.0, (back - 0.002) / 0.004)), 0.0, 0.0)


bpy.app.handlers.frame_change_pre.append(barrel_follow)

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


C_L_NEW = grip_constraint("L", new_grip)
C_L_PRESS = grip_constraint("L", press_grip)
C_L_RACK = grip_constraint("L", rack_grip)
FLAGS = ("oldVis", "newVis", "newIn")
for p in FLAGS:
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


def key(ob, path, tick, mode="B"):
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


def gun_keys(keys):
    """{tick: (x, y, z, roll, pitch, yaw, mode)}"""
    for t, (x, y, z, roll, pitch, yaw, mode) in sorted(TAKE.gun(keys).items()):
        gun.location = (x, y, z)
        gun.rotation_euler = (math.radians(pitch), math.radians(yaw), math.radians(roll))
        key(gun, "location", t, mode)
        key(gun, "rotation_euler", t, mode)


def gun_matrix(t):
    """The pistol's pose (item space) at tick t, from the keys already set."""
    scene.frame_set(int(t * FPT), subframe=(t * FPT) % 1.0)
    return gun.matrix_local.copy()


def mag_keys(ob, keys):
    """{tick: (dx, dy, dz, pitch, yaw, roll, mode)}: moved from the seat and turned about it."""
    for t, (dx, dy, dz, rx, ry, rz, mode) in sorted(keys.items()):
        if TAKE.n and mode != "C" and math.sqrt(dx * dx + dy * dy + dz * dz) > 0.12:
            # on its way from the pouch: another path each take
            dx, dy, dz = TAKE.point((dx, dy, dz), 0.008)
            rx, ry, rz = TAKE.angle(rx, 4.0), TAKE.angle(ry, 2.0), TAKE.angle(rz, 4.0)
        ob.location = SEAT + Vector((dx, dy, dz))
        ob.rotation_euler = (math.radians(rx), math.radians(ry), math.radians(rz))
        key(ob, "location", t, mode)
        key(ob, "rotation_euler", t, mode)


def held(hand, rx, ry, rz, kind):
    """The magazine's offset from the seat that puts its base in the hand at `hand`, turned by rx, ry, rz."""
    r = Euler((math.radians(rx), math.radians(ry), math.radians(rz)), "XYZ").to_matrix()
    grip = SEAT + MAG_TILT.to_3x3() @ hold_local(kind)  # the hold point, seated, in item space
    off = Vector(hand) - SEAT - r @ (grip - SEAT)
    return (off.x, off.y, off.z, rx, ry, rz)


def hold_local(kind):
    """Where the support hand's fist sits on a magazine (its own frame): round its lower end, a little to the left."""
    return Vector((-0.009, -(LENGTH[kind] - 0.016), 0.0))


def free_keys(side, keys):
    """{tick: ((x, y, z), mode)}"""
    free = hands[side][1]
    ticks = sorted(keys)
    for t, (p, mode) in sorted(keys.items()):
        if t not in (ticks[0], ticks[-1]):
            p = TAKE.point(p, 0.006)
        free.location = p
        key(free, "location", t, mode)


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
        key(gun, f'["{name}"]', t, "B")


def slide_keys(keys):
    for t, (b, mode) in sorted(keys.items()):
        slide.location = (0.0, 0.0, SLIDE_TRAVEL * b)
        key(slide, "location", t, mode)


def clear(kind="std"):
    for ob in scene.objects:
        ob.animation_data_clear()
    for c in hands["L"][0].constraints:
        if c.name != "free":
            c.influence = 0.0
    gun.location = (0, 0, 0)
    gun.rotation_euler = (0, 0, 0)
    slide.location = (0, 0, 0)
    for m in (OLD, NEW):
        m.location = SEAT
        m.rotation_euler = (0, 0, 0)
    new_grip.location = MAG_TILT.to_3x3() @ hold_local(kind)
    hands["L"][1].location = SUPPORT
    hands["R"][1].location = GRIP
    for p in FLAGS + CURVES:
        gun[p] = 0.0
    for name in ("old", "new"):
        mags[name][0].hide_render = kind == "ext" and name == "new"
        mags[name][1].hide_render = not (kind == "ext" and name == "new")
    # the magazine's own mesh is drawn tilted: its children need the seat taken off again
    for name in ("old", "new"):
        mags[name][1].location = (0, 0, 0)


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


SEATED = (0, 0, 0, 0, 0, 0)


def drop_old(t0, until):
    """The old magazine, released at t0: it slides down out of the grip under its own weight and falls free,
    tumbling; keyed in the pistol's frame from where the ground is at each moment."""
    g = 0.0245  # metres a tick squared
    keys = {0: (*SEATED, "C"), t0: (*SEATED, "QUAD"), t0 + 0.5: (*(MAG_DOWN * 0.002), 0, 0, 0, "L")}
    t = 0.5
    p0 = SEAT + MAG_DOWN * 0.002
    while t0 + t <= until:
        t += 0.5
        d = 0.002 + 0.006 * t + 0.5 * g * t * t * 0.85
        if d < 0.11:
            p = SEAT + MAG_DOWN * d
            rot = (0, 0, 0)
        else:
            # free of the well: falling straight down (the world's down, seen from the pistol), turning over
            tf = t - math.sqrt(0.11 / (0.5 * g * 0.85))
            gm = gun_matrix(t0 + t).to_3x3()
            down = gm.inverted() @ (root.matrix_world.to_3x3().inverted() @ Vector((0, 0, -1)))
            p = SEAT + MAG_DOWN * 0.11 + down * (0.035 * tf + 0.5 * g * tf * tf) + Vector((-0.004, 0, -0.006)) * tf
            rot = (-28 * tf, 6 * tf, 12 * tf)
        off = p - SEAT
        keys[t0 + t] = (off.x, off.y, off.z, *rot, "L")
        p0 = p
    mag_keys(OLD, keys)
    # clear of the well (about three ticks out): from there Bullet has it
    animkit.rigid_fall(scene, OLD, gun, t0 + 3.0, until, FPT)
    return p0


def fall_new(kind, release, until):
    """The fresh magazine slipping from the fingers at `release`: Bullet has it until `until`."""
    animkit.rigid_fall(scene, NEW, gun, release, until, FPT, mesh=mags["new"][1 if kind == "ext" else 0].data)


# ---------------------------------------------------------------- the clips
def reload(kind, empty_gun):
    """The speed reload. The pistol canted over and drawn in, the magwell turned to the support hand; the
    thumb stabs the release and the old magazine drops out of the grip - the support hand already on its way
    to the belt. The fresh one comes up tipped, is straightened on the way, indexed into the well (finger along
    its front) and slapped home - a jolt through the gun. With rounds left, straight back on target; from slide
    lock the support hand comes over the top, grips the rear serrations and racks - the pistol pushed forward
    as the hand pulls back - lets go, and the slide slams home."""
    end = RELOAD_EMPTY if empty_gun else RELOAD_TACTICAL
    keys = {
        0: (0, 0, 0, 0, 0, 0, "B"),
        3.4: (-0.04, 0.095, 0.022, -24, 10, 9, "QUADO"),
        T_RELEASE + 0.6: (-0.045, 0.104, 0.024, -29, 13.5, 10.5, "OUT"),  # the thumb's stab
        T_DROP + 0.4: (-0.054, 0.122, 0.028, -35, 11, 12, "OUT"),         # the flick that clears it
        10: (-0.058, 0.13, 0.03, -36, 12, 13, "B"),
        15: (-0.06, 0.134, 0.031, -37, 12.5, 13, "B"),
        T_IN - 0.7: (-0.06, 0.133, 0.031, -37, 12, 13, "QUADO"),
        T_IN + 0.2: (-0.059, 0.15, 0.028, -34, 7.5, 12.5, "OUT"),         # the slap jolts it up
        T_IN + 2: (-0.06, 0.137, 0.03, -36, 11.5, 13, "B"),
    }
    if not empty_gun:
        keys.update({
            T_IN + 4.5: (-0.03, 0.07, 0.016, -18, 6, 6, "B"),
            end - 2.6: (0.004, -0.006, 0.003, 1.4, -0.9, -1.2, "B"),  # a hair past the target ...
            end: (0, 0, 0, 0, 0, 0, "B"),                            # ... and settled
        })
    else:
        keys.update({
            22: (-0.02, 0.1, 0.0, -8, 8, 4, "B"),                        # upright, for the hand over the top
            T_RACK_BACK - 1.6: (-0.012, 0.098, -0.012, -4, 7, 2, "IN"),
            T_RACK_BACK: (-0.008, 0.094, -0.04, -2, 4.5, 1, "B"),        # pushed out as the hand pulls back
            T_RACK_FORWARD - 0.4: (-0.008, 0.095, -0.038, -2, 5, 1, "QUADO"),
            T_RACK_FORWARD + 0.3: (-0.006, 0.09, -0.03, -1, 2, 0.5, "OUT"),  # the slide slams home
            31: (-0.004, 0.04, -0.012, 0, 2, 0, "B"),
            end - 2: (0.003, -0.005, 0.002, 0.8, -0.6, -0.8, "B"),
            end: (0, 0, 0, 0, 0, 0, "B"),
        })
    gun_keys(keys)
    drop_old(T_RELEASE, T_RELEASE + 9)
    # the fresh one: out of the pouch tipped over, straightened coming up, in, slapped home
    pouch = held(POUCH, -50, 0, -30, kind)
    below = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.05) + Vector((-0.004, 0.0, -0.01)), 8, 0, -4, kind)
    entry = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.012) + Vector((0, 0, 0)), 3, 0, 0, kind)
    mag_keys(NEW, {
        6.5: (*pouch, "B"),
        9: (*held(add(POUCH, (0.03, 0.08, 0.01)), -35, 0, -18, kind), "B"),
        13.5: (*below, "B"),
        15.6: (*entry, "IN"),
        T_IN - 0.5: (*MAG_DOWN * 0.002, 0, 0, 0, "QUADO"),
        T_IN: (*SEATED, "C"),
    })
    prop_keys("oldVis", {0: 1, T_RELEASE + 9: 0})
    prop_keys("newVis", {0: 0, 6.5: 1})
    prop_keys("newIn", {0: 0, T_IN: 1})
    # the support hand: off the grip and down to the pouch, with the fresh magazine until it is home
    hand = [
        (0, SUPPORT, "B"),
        (2.5, (-0.07, -0.08, 0.0), "B"),
        (6.5, POUCH, "B"),
        (T_IN + 0.4, tuple(SEAT + MAG_DOWN * (LENGTH[kind] - 0.004) + Vector((-0.012, 0, 0))), "B"),
    ]
    if not empty_gun:
        hand += [(T_IN + 3.5, (-0.05, -0.08, 0.02), "B"), (end - 3, SUPPORT, "B")]
        influence_keys(C_L_NEW, {0: (0, "C"), 6.0: (0, "QUAD"), 6.6: (1, "C"), T_IN + 0.2: (1, "QUADO"), T_IN + 1.2: (0, "C")})
    else:
        hand += [(21, (-0.06, 0.06, 0.03), "B"), (T_RACK_FORWARD + 1.2, tuple(OVERHAND + Vector((-0.04, 0.04, 0.07))), "B"),
                 (31.5, (-0.06, -0.03, 0.02), "B"), (end - 1, SUPPORT, "B")]
        influence_keys(C_L_NEW, {0: (0, "C"), 6.0: (0, "QUAD"), 6.6: (1, "C"), T_IN + 0.2: (1, "QUADO"), T_IN + 1.2: (0, "C")})
        influence_keys(C_L_RACK, {0: (0, "C"), 21.6: (0, "QUAD"), 23: (1, "C"), T_RACK_FORWARD - 0.5: (1, "QUADO"), T_RACK_FORWARD + 0.2: (0, "C")})
    free_keys("L", {t: (p, m) for t, p, m in hand})
    free_keys("R", {0: (GRIP, "C")})
    if empty_gun:
        # the support hand's forearm rolled over the top for the rack
        curve_keys("twistL", {0: 0, 21: 0, 23: -70, T_RACK_FORWARD + 1.2: -60, 31.5: 0})
    if empty_gun:
        # locked back until the hand takes it: pulled to the stop, held, let go - it slams home
        slide_keys({0: (1.0, "C"), 23.2: (1.0, "SINE"), T_RACK_BACK: (1.12, "C"), T_RACK_FORWARD - 0.5: (1.12, "QUAD"),
                    T_RACK_FORWARD: (0.0, "C")})
    else:
        slide_keys({0: (0.0, "C")})
    return end


T_FUMBLE_HIT, FUMBLE_DELAY, T_SLIP, DROP_DELAY = 17, 9, 10, 15
G = 0.0245  # gravity, metres a tick squared


def down_at(t):
    """The world's down in the pistol's frame at tick t."""
    gm = gun_matrix(t).to_3x3()
    return gm.inverted() @ (root.matrix_world.to_3x3().inverted() @ Vector((0, 0, -1)))


def reload_shaken(kind, variant):
    """A speed reload with rounds left, gone wrong under fire. "fumble": the fresh magazine comes up crooked, its
    front edge catches the rim of the well - a clack, a jolt through the gun - it skids off, is pulled down and
    squared up, and goes home the second time. "drop": it slips from the fingers on its way up and falls - the
    hand snatches after it, misses - and goes back to the belt for another."""
    d = FUMBLE_DELAY if variant == "fumble" else DROP_DELAY
    end = RELOAD_TACTICAL + d
    keys = {
        0: (0, 0, 0, 0, 0, 0, "B"),
        3.4: (-0.04, 0.095, 0.022, -24, 10, 9, "QUADO"),
        T_RELEASE + 0.6: (-0.045, 0.104, 0.024, -29, 13.5, 10.5, "OUT"),
        T_DROP + 0.4: (-0.054, 0.122, 0.028, -35, 11, 12, "OUT"),
        10: (-0.058, 0.13, 0.03, -36, 12, 13, "B"),
    }
    if variant == "fumble":
        keys.update({
            15: (-0.06, 0.134, 0.031, -37, 12.5, 13, "IN"),
            T_FUMBLE_HIT + 0.15: (-0.062, 0.127, 0.034, -39, 10, 14.5, "OUT"),   # the clack jolts it
            19.5: (-0.061, 0.131, 0.032, -37.5, 14, 13.2, "B"),                  # looked at, closer
            15 + d: (-0.06, 0.134, 0.031, -37, 12.5, 13, "B"),
        })
    else:
        keys.update({
            T_SLIP + 0.8: (-0.057, 0.124, 0.03, -34, 7, 12.5, "OUT"),            # a start; eyes down after it
            13.5: (-0.058, 0.127, 0.03, -35, 9, 13, "B"),
            15 + d: (-0.06, 0.134, 0.031, -37, 12.5, 13, "B"),
        })
    keys.update({
        T_IN + d - 0.7: (-0.06, 0.133, 0.031, -37, 12, 13, "QUADO"),
        T_IN + d + 0.2: (-0.059, 0.15, 0.028, -34, 7.5, 12.5, "OUT"),
        T_IN + d + 2: (-0.06, 0.137, 0.03, -36, 11.5, 13, "B"),
        T_IN + d + 4.5: (-0.03, 0.07, 0.016, -18, 6, 6, "B"),
        end - 2.6: (0.004, -0.006, 0.003, 1.4, -0.9, -1.2, "B"),
        end: (0, 0, 0, 0, 0, 0, "B"),
    })
    gun_keys(keys)
    drop_old(T_RELEASE, T_RELEASE + 9)
    pouch = held(POUCH, -50, 0, -30, kind)
    up = held(add(POUCH, (0.03, 0.08, 0.01)), -35, 0, -18, kind)
    below = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.05) + Vector((-0.004, 0.0, -0.01)), 8, 0, -4, kind)
    entry = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.012), 3, 0, 0, kind)
    new = {6.5: (*pouch, "B"), 9: (*up, "B")}
    hand = [(0, SUPPORT, "B"), (2.5, (-0.07, -0.08, 0.0), "B"), (6.5, POUCH, "B")]
    if variant == "fumble":
        rim = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.004) + Vector((-0.006, 0.0, -0.007)), 10, 5, -7, kind)
        skid = held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.034) + Vector((-0.016, 0.0, -0.02)), 16, -6, -11, kind)
        new.update({
            13.5: (*held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.05) + Vector((-0.008, 0.0, -0.014)), 11, 4, -7, kind), "B"),
            15.6: (*held(SEAT + MAG_DOWN * (LENGTH[kind] + 0.02) + Vector((-0.007, 0.0, -0.01)), 9, 5, -6, kind), "IN"),
            T_FUMBLE_HIT: (*rim, "OUT"),                                     # the front edge on the rim: stopped
            17.8: (*skid, "B"),                                              # skidded off, turned
            20.5: (*below, "B"),                                             # pulled down, squared up
            15.6 + d: (*entry, "IN"),
            T_IN + d - 0.5: (*MAG_DOWN * 0.002, 0, 0, 0, "QUADO"),
            T_IN + d: (*SEATED, "C"),
        })
        prop_keys("newVis", {0: 0, 6.5: 1})
        influence_keys(C_L_NEW, {0: (0, "C"), 6.0: (0, "QUAD"), 6.6: (1, "C"), T_IN + d + 0.2: (1, "QUADO"), T_IN + d + 1.2: (0, "C")})
    else:
        slip_hand = Vector(POUCH).lerp(SEAT + MAG_DOWN * (LENGTH[kind] + 0.05), 0.35) + Vector((0.01, 0.03, 0.0))
        slip = held(slip_hand, -26, 0, -14, kind)
        new[T_SLIP] = (*slip, "L")
        # the point it was held by falls under gravity; the magazine tumbles about it
        vel = Vector((0.002, 0.008, 0.0))
        for i in range(1, 15):
            dt = i * 0.5
            h = slip_hand + vel * dt + down_at(T_SLIP + dt) * (0.5 * G * dt * dt)
            new[T_SLIP + dt] = (*held(h, -26 - 40 * dt, 14 * dt, -14 + 25 * dt, kind), "L")
        last = new[T_SLIP + 7.0]
        new[T_SLIP + 7.0] = (*last[:6], "C")                                 # out of sight: it waits there
        new.update({
            6.5 + d: (*pouch, "B"),                                          # the second one off the belt
            9 + d: (*up, "B"),
            13.5 + d: (*below, "B"),
            15.6 + d: (*entry, "IN"),
            T_IN + d - 0.5: (*MAG_DOWN * 0.002, 0, 0, 0, "QUADO"),
            T_IN + d: (*SEATED, "C"),
        })
        prop_keys("newVis", {0: 0, 6.5: 1, T_SLIP + 7.0: 0, 6.5 + d: 1})
        influence_keys(C_L_NEW, {0: (0, "C"), 6.0: (0, "QUAD"), 6.6: (1, "C"), T_SLIP: (1, "QUAD"), T_SLIP + 0.3: (0, "C"),
                                 6.0 + d: (0, "QUAD"), 6.6 + d: (1, "C"), T_IN + d + 0.2: (1, "QUADO"), T_IN + d + 1.2: (0, "C")})
        hand += [(T_SLIP, tuple(slip_hand), "OUT"),
                 (T_SLIP + 1.0, tuple(slip_hand + Vector((0.006, -0.05, 0.015))), "B"),   # the snatch after it
                 (T_SLIP + 2.6, tuple(slip_hand + Vector((0.0, -0.09, 0.02))), "B"),
                 (6.5 + d, POUCH, "B")]
    mag_keys(NEW, new)
    prop_keys("oldVis", {0: 1, T_RELEASE + 9: 0})
    prop_keys("newIn", {0: 0, T_IN + d: 1})
    if variant == "fumble":
        curve_keys("look", {0: 0, T_FUMBLE_HIT: 0, T_FUMBLE_HIT + 1.5: 6, 20.5: 5, 15.6 + d: 1, T_IN + d + 2: 0})
    else:
        curve_keys("look", {0: 0, T_SLIP: 0, T_SLIP + 1.5: 9, T_SLIP + 4: 13, T_SLIP + 7: 7, 6.5 + d: 3, 13.5 + d: 0})
    hand += [(T_IN + d + 0.4, tuple(SEAT + MAG_DOWN * (LENGTH[kind] - 0.004) + Vector((-0.012, 0, 0))), "B"),
             (T_IN + d + 3.5, (-0.05, -0.08, 0.02), "B"), (end - 3, SUPPORT, "B")]
    free_keys("L", {t: (p, m) for t, p, m in hand})
    free_keys("R", {0: (GRIP, "C")})
    slide_keys({0: (0.0, "C")})
    if variant == "drop":
        fall_new(kind, T_SLIP, T_SLIP + 7.0)
    return end


def clip_inspect():
    """Looking it over: brought up and rolled to show the right side and the port; the support hand pinches the
    front serrations and eases the slide back - brass in the chamber - and lets it snap home; rolled over to look
    at the left side and the markings; back on line."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        8: (-0.05, 0.1, 0.06, 52, 8, -14, "B"),
        15: (-0.056, 0.106, 0.064, 60, 10, -18, "B"),
        21: (-0.052, 0.1, 0.058, 48, 9, -10, "B"),
        PRESS_BACK - 0.8: (-0.052, 0.1, 0.058, 47, 8.5, -10, "IN"),
        PRESS_BACK + 0.5: (-0.05, 0.098, 0.052, 46, 7, -11, "B"),    # the tug
        PRESS_HOME - 0.3: (-0.05, 0.1, 0.055, 47, 8, -10, "QUADO"),
        PRESS_HOME + 0.4: (-0.05, 0.104, 0.05, 45, 10.5, -10, "OUT"),  # the slide snaps home
        36: (-0.05, 0.1, 0.058, 46, 8, -10, "B"),
        43: (-0.044, 0.11, 0.064, -62, 12, 16, "B"),
        50: (-0.046, 0.112, 0.066, -68, 14, 20, "B"),
        56: (-0.02, 0.05, 0.03, -20, 5, 6, "B"),
        INSPECT: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("oldVis", 1), ("newVis", 0), ("newIn", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {
        0: (SUPPORT, "B"),
        5: ((-0.09, -0.08, 0.05), "B"),
        18: ((-0.07, 0.02, -0.06), "B"),
        PRESS_HOME + 2: ((-0.06, 0.04, -0.08), "B"),
        40: ((-0.1, -0.08, 0.05), "B"),
        52: ((-0.1, -0.08, 0.05), "B"),
        59: (SUPPORT, "B"),
    })
    influence_keys(C_L_PRESS, {0: (0, "C"), 20.5: (0, "QUAD"), 22.5: (1, "C"), PRESS_HOME - 0.4: (1, "QUADO"), PRESS_HOME + 1.5: (0, "C")})
    curve_keys("twistL", {0: 0, 18: 0, 22.5: -35, PRESS_HOME + 2: 0})
    free_keys("R", {0: (GRIP, "C")})
    slide_keys({0: (0.0, "C"), PRESS_BACK - 1.6: (0.0, "SINE"), PRESS_BACK + 0.6: (0.38, "B"), PRESS_HOME - 0.3: (0.4, "QUAD"),
                PRESS_HOME + 0.1: (0.0, "C")})
    return INSPECT


def clip_dry():
    """The click instead of a bang: the trigger finger's twitch, then the pistol rolled to look at the slide."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "QUADO"),
        1.2: (0, -0.003, -0.004, 0, -1.6, 0, "B"),
        3: (0, 0, 0, 0, 0, 0, "B"),
        8: (-0.012, 0.03, 0.02, 32, 6, -7, "B"),
        13: (-0.013, 0.031, 0.021, 34, 6.5, -8, "B"),
        22: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("oldVis", 1), ("newVis", 0), ("newIn", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {0: (SUPPORT, "C")})
    free_keys("R", {0: (GRIP, "C")})
    slide_keys({0: (0.0, "C")})
    return 22


def clip_draw():
    """Drawn from the holster at the right hip: up, the muzzle swinging from the ground onto the line, the
    support hand meeting it in front of the chest, punched out to the ready with a little overshoot."""
    gun_keys({
        0: (0.12, -0.36, 0.14, -20, -75, 4, "B"),
        3.5: (0.06, -0.14, 0.07, -12, -35, 2, "B"),
        6.5: (0.01, -0.01, 0.012, -2, -3, 0, "B"),
        8.5: (-0.003, 0.008, -0.01, 1.2, 2.2, -0.6, "B"),
        11: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("oldVis", 1), ("newVis", 0), ("newIn", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {0: (add(SUPPORT, (-0.12, -0.16, 0.1)), "B"), 4.5: (add(SUPPORT, (-0.08, -0.06, 0.05)), "B"), 8: (SUPPORT, "B")})
    free_keys("R", {0: (GRIP, "C")})
    slide_keys({0: (0.0, "C")})
    return 11


def clip_idle():
    """A long while doing nothing: a turn of the wrists to look the pistol over, then back."""
    gun_keys({
        0: (0, 0, 0, 0, 0, 0, "B"),
        10: (-0.01, 0.03, 0.03, 26, 4, -24, "B"),
        24: (-0.012, 0.033, 0.03, 30, 5, -28, "B"),
        33: (-0.008, 0.028, 0.026, 14, 3, -12, "B"),
        50: (0, 0, 0, 0, 0, 0, "B"),
    })
    for p, v in (("oldVis", 1), ("newVis", 0), ("newIn", 0)):
        prop_keys(p, {0: v})
    free_keys("L", {0: (SUPPORT, "C")})
    free_keys("R", {0: (GRIP, "C")})
    slide_keys({0: (0.0, "C")})
    return 50


CLIPS = {
    "reload_tac_std": lambda: reload("std", False),
    "reload_tac_ext": lambda: reload("ext", False),
    "reload_empty_std": lambda: reload("std", True),
    "reload_empty_ext": lambda: reload("ext", True),
    "reload_tac_std_fumble": lambda: reload_shaken("std", "fumble"),
    "reload_tac_ext_fumble": lambda: reload_shaken("ext", "fumble"),
    "reload_tac_std_drop": lambda: reload_shaken("std", "drop"),
    "reload_tac_ext_drop": lambda: reload_shaken("ext", "drop"),
    "inspect": clip_inspect,
    "dry": clip_dry,
    "draw": clip_draw,
    "idle": clip_idle,
}


# ---------------------------------------------------------------- sampling
def m34(m):
    return [m[r][c] for r in range(3) for c in range(4)]


def sample():
    gi = gun.matrix_world.inverted()
    e = gun.rotation_euler
    out = [gun.location.x, gun.location.y, gun.location.z, math.degrees(e.z), math.degrees(e.x), math.degrees(e.y)]
    for m in (OLD, NEW):
        out += m34(Matrix.Translation(-SEAT) @ gi @ m.matrix_world)
    for side in ("L", "R"):
        out += list((gi @ hands[side][0].matrix_world).translation)
    out.append(slide.location.z / SLIDE_TRAVEL)
    out += [gun["oldVis"], gun["newVis"], gun["newIn"]]
    out += animkit.hand_quat(hands["L"][0], gi, gun["twistL"])
    out += animkit.hand_quat(hands["R"][0], gi, gun["twistR"])
    out.append(gun["look"])  # replaced by the head's pitch, yaw, roll (animkit.camera)
    return out


def jobs():
    """Every clip, and the takes of the reloads: (name, build, take)."""
    for name, build in CLIPS.items():
        yield name, build, 0
        if name.startswith("reload_"):
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
    h = root.matrix_world.inverted() @ hand.matrix_world.translation
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
    sleeve = bpy.data.materials.new(name + "_sleeve")
    sleeve.use_nodes = True
    sleeve.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.09, 0.1, 0.07, 1)
    glove = bpy.data.materials.new(name + "_hand")
    glove.use_nodes = True
    glove.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.05, 1)
    for bone, length, r in (("upper", upper, 0.04), ("fore", fore, 0.03)):
        bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=length, vertices=16)
        cyl = bpy.context.active_object
        cyl.data.materials.append(sleeve)
        cyl.data.transform(Matrix.Rotation(-math.pi / 2, 4, "X") @ Matrix.Translation((0, 0, length / 2)))
        cyl.parent = ob
        cyl.parent_type = "BONE"
        cyl.parent_bone = bone
        cyl.location = (0, -length, 0)
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.03)
    fist = bpy.context.active_object
    fist.scale = (0.8, 1.0, 1.15)
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
    cam.angle = math.radians(42)
    co = bpy.data.objects.new("eye", cam)
    scene.collection.objects.link(co)
    co.parent = root
    # beside the shooter, off the left shoulder, looking at the pistol and the hands working it
    co.location = (-0.36, 0.2, 0.24)
    look = Vector((0.0, 0.04, -0.05)) - Vector(co.location)
    f = look.normalized()
    right = f.cross(Vector((0, 1, 0))).normalized()
    up = right.cross(f)
    co.rotation_euler = Matrix((right, up, -f)).transposed().to_euler()  # item space: y is up
    scene.camera = co
    # the hands as fists with a stub of forearm running back towards the shoulders
    for side, shoulder in (("R", (0.16, -0.25, 0.4)), ("L", (-0.2, -0.25, 0.38))):
        hand = hands[side][0]
        bpy.ops.mesh.primitive_uv_sphere_add(radius=0.022)
        fist = bpy.context.active_object
        fist.scale = (0.9, 1.0, 1.25)
        glove = bpy.data.materials.new("glove" + side)
        glove.use_nodes = True
        glove.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.045, 1)
        glove.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = 0.85
        fist.data.materials.append(glove)
        fist.parent = hand
        aim = empty("shoulder_" + side, root, shoulder)
        bpy.ops.mesh.primitive_cylinder_add(radius=0.018, depth=0.12, vertices=12)
        stub = bpy.context.active_object
        stub.data.transform(Matrix.Translation((0, 0, -0.06)))
        stub.data.materials.append(glove)
        stub.parent = hand
        c = stub.constraints.new("TRACK_TO")
        c.target = aim
        c.track_axis = "TRACK_NEGATIVE_Z"
        c.up_axis = "UP_Y"


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    if PREVIEW:
        preview_setup()
    blob = []
    for name, build, take in jobs():
        if ONLY and name not in ONLY:
            continue
        TAKE = animkit.Take(take)
        clear("ext" if "_ext" in name else "std")
        length = build()
        n = length * SPT + 1
        samples = []
        for i in range(n):
            scene.frame_set(int(i * FPT / SPT), subframe=(i * FPT / SPT) % 1.0)
            samples.append(sample())
        # the eyes on the pistol while it is in close; not for the draw and the dry click (on target)
        head = animkit.camera(samples, SPT, [s[-1] for s in samples], down=0.0 if name in ("draw", "dry", "idle") else 4.0)
        samples = [s[:-1] + h for s, h in zip(samples, head)]
        blob.append((name, length, samples))
        print(name, length, "ticks", len(samples), "samples", flush=True)
        if PREVIEW:
            from PIL import Image  # noqa: E402
            frames = []
            for t in range(0, length + 1, STEP):
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
        with gzip.open(os.path.join(OUT, "glock19.anim.gz"), "wb") as f:
            f.write(struct.pack(">I", len(blob)))
            for name, length, samples in blob:
                nb = name.encode()
                f.write(struct.pack(">H", len(nb)) + nb)
                f.write(struct.pack(">III", length, SPT, len(samples)))
                for s in samples:
                    f.write(struct.pack(">51f", *s))
