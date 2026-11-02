"""What anim.py (AK-47) and glock_anim.py (Glock 19) share beyond the keying helpers:

  rigid_fall   a magazine let go is handed to Blender's rigid-body world (Bullet): until the release it is an
               animated (kinematic) body following the hand, so it leaves with the speed and spin it had; after
               it, it falls, tumbles and bounces off the ground on its own. The result is baked back onto the
               magazine, in the gun's frame, one key a frame.
  hand_quat    which way a hand is turned (gun frame): the turn of what it holds, as far as it holds it, and a
               keyed twist of the forearm.
  camera       the head's motion (pitch, yaw, roll in degrees, pitch + = looking down): the eyes drawn down to
               the well while the gun is in close, the head leaning with the cant, a keyed look (after a dropped
               magazine), and a jolt through it whenever the gun is jolted (the slap, the bolt slamming home).
  Take         a take of a clip: the same events at the same ticks, the motion between them a little different
               (a bias for the whole take and a wobble per key), so no two reloads look quite alike.
"""
import math
import random

import bpy
from mathutils import Matrix, Quaternion, Vector

GROUND = -1.1  # the ground, item space y: the hands are about chest high


# ---------------------------------------------------------------- rigid bodies
def _fcurve(ob, path, index):
    for fc in _fcurves(ob.animation_data.action):
        if fc.data_path == path and fc.array_index == index:
            return fc
    return None


def _fcurves(action):
    if hasattr(action, "fcurves") and len(action.fcurves):
        return list(action.fcurves)
    out = []
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in strip.channelbags:
                out.extend(bag.fcurves)
    return out


def rigid_fall(scene, ob, gun, release, until, fpt, mesh=None, bounce=0.28, friction=0.55, mass=0.35):
    """Let go of `ob` (keyed location / rotation_euler, parented to `gun`) at tick `release`; Bullet takes it to
    tick `until`. Its keys after the release are replaced by the simulation's."""
    rf = int(round(release * fpt))
    ef = int(math.floor(until * fpt))
    # where it is, frame by frame, up to the release (it is an animated body until then)
    path = []
    for f in range(0, rf + 1):
        scene.frame_set(f)
        path.append(ob.matrix_world.copy())
    if bpy.context.scene.rigidbody_world is None:
        bpy.ops.rigidbody.world_add()
    world = scene.rigidbody_world
    scene.render.fps = 20 * fpt  # a frame is a quarter tick: real seconds for Bullet
    scene.render.fps_base = 1.0
    world.time_scale = 1.0
    world.substeps_per_frame = 12
    world.solver_iterations = 20
    world.point_cache.frame_start = 0
    world.point_cache.frame_end = ef + 2
    root_m = gun.parent.matrix_world
    # the ground, a wide slab below the hands
    bpy.ops.mesh.primitive_plane_add(size=20.0)
    ground = bpy.context.active_object
    ground.matrix_world = root_m @ Matrix.Translation((0.0, GROUND, 0.0)) @ Matrix.Rotation(-math.pi / 2, 4, "X")
    bpy.ops.rigidbody.object_add()
    ground.rigid_body.type = "PASSIVE"
    ground.rigid_body.friction = 0.8
    ground.rigid_body.restitution = 0.5
    ground.hide_render = True
    proxy = bpy.data.objects.new(ob.name + "_rb", mesh or ob.data)
    scene.collection.objects.link(proxy)
    proxy.rotation_mode = "QUATERNION"
    proxy.hide_render = True
    bpy.context.view_layer.objects.active = proxy
    proxy.select_set(True)
    bpy.ops.rigidbody.object_add()
    rb = proxy.rigid_body
    rb.type = "ACTIVE"
    rb.collision_shape = "CONVEX_HULL"
    rb.mass = mass
    rb.friction = friction
    rb.restitution = bounce
    rb.linear_damping = 0.02
    rb.angular_damping = 0.05
    rb.use_margin = True
    rb.collision_margin = 0.002
    for f, m in enumerate(path):
        loc, rot, _ = m.decompose()
        proxy.location = loc
        proxy.rotation_quaternion = rot
        proxy.keyframe_insert("location", frame=f)
        proxy.keyframe_insert("rotation_quaternion", frame=f)
    rb.kinematic = True
    rb.keyframe_insert("kinematic", frame=rf)
    rb.kinematic = False
    rb.keyframe_insert("kinematic", frame=rf + 1)
    # run it, frame by frame from the start (the cache wants it in order)
    baked = []
    for f in range(0, ef + 1):
        scene.frame_set(f)
        if f > rf:
            baked.append((f, gun.matrix_world.inverted() @ proxy.matrix_world))
    # back onto the magazine: its keys after the release replaced, one a frame, straight lines between
    curves = [_fcurve(ob, "location", i) for i in range(3)] + [_fcurve(ob, "rotation_euler", i) for i in range(3)]
    for fc in curves:
        for kp in reversed(list(fc.keyframe_points)):
            if rf + 1e-3 < kp.co.x <= ef + 1e-3:
                fc.keyframe_points.remove(kp)
    scene.frame_set(rf)
    prev = ob.matrix_basis.to_euler("XYZ")
    for f, m in baked:
        loc = m.to_translation()
        e = m.to_euler("XYZ", prev)
        prev = e
        for i in range(3):
            curves[i].keyframe_points.insert(f, loc[i], options={"FAST"}).interpolation = "LINEAR"
            curves[3 + i].keyframe_points.insert(f, e[i], options={"FAST"}).interpolation = "LINEAR"
    # the last key before the hand-off runs straight into the simulation
    for fc in curves:
        for kp in fc.keyframe_points:
            if abs(kp.co.x - rf) < 1e-3:
                kp.interpolation = "LINEAR"
        fc.update()
    # tidy up: the next clip starts from an empty world
    for o in (proxy, ground):
        bpy.data.objects.remove(o, do_unlink=True)
    bpy.ops.rigidbody.world_remove()
    return baked


# ---------------------------------------------------------------- hands
def hand_quat(hand, gi, twist):
    """The hand's turn in the gun's frame (x y z w): blended onto what it grips by the grip's influence, then
    twisted `twist` degrees about the gun's long axis (a forearm rolled over to reach over the top)."""
    q = Quaternion()
    for c in hand.constraints:
        if c.name == "free" or c.influence <= 1e-4:
            continue
        held = (gi @ c.target.matrix_world).to_quaternion()
        q = q.slerp(held, c.influence)
    q = Quaternion((0.0, 0.0, 1.0), math.radians(twist)) @ q
    q.normalize()
    return [q.x, q.y, q.z, q.w]


# ---------------------------------------------------------------- the head
def camera(samples, spt, look, side_m=22.0, lean=0.1, down=4.5, kick=1.0):
    """Pitch, yaw, roll (degrees) of the head for each sample of a clip (gun channels 0-5), `look` the keyed
    extra look down per sample."""
    n = len(samples)
    dt = 1.0 / spt
    # the jolt: a spring kicked by the gun's sudden changes of motion (degrees and metres per tick, changing)
    w, zeta = 1.7, 0.32
    jp = jv = 0.0
    rp = rv = 0.0
    out = []
    for i in range(n):
        s = samples[i]
        a = samples[max(i - 1, 0)]
        b = samples[max(i - 2, 0)]
        # the change in velocity (per tick) over this sample: pitch and the lift
        acc_p = ((s[4] - a[4]) - (a[4] - b[4])) * spt
        acc_y = ((s[1] - a[1]) - (a[1] - b[1])) * spt
        acc_r = ((s[3] - a[3]) - (a[3] - b[3])) * spt
        drive = max(-8.0, min(8.0, 0.22 * acc_p + 35.0 * acc_y))
        jv += drive * kick
        jv += (-w * w * jp - 2 * zeta * w * jv) * dt
        jp += jv * dt
        rv += max(-6.0, min(6.0, 0.12 * acc_r)) * kick
        rv += (-w * w * rp - 2 * zeta * w * rv) * dt
        rp += rv * dt
        dist = math.sqrt(s[0] ** 2 + s[1] ** 2 + s[2] ** 2)
        eng = min(1.0, max(abs(s[3]) / 25.0, dist / 0.12))
        eng = eng * eng * (3 - 2 * eng)
        pitch = down * eng + look[i] + jp
        yaw = s[0] * side_m * (1.0 if eng > 0 else 0.0)
        roll = lean * s[3] + rp
        out.append([pitch, yaw, roll])
    # into and out of the clip without a step
    fade = 2 * spt
    for i in range(n):
        f = min(1.0, i / fade, (n - 1 - i) / fade)
        f = f * f * (3 - 2 * f)
        out[i] = [v * f for v in out[i]]
    return out


# ---------------------------------------------------------------- takes
class Take:
    """Take 0 is the clip as keyed; takes 1, 2 move it a little: `gun` a bias for the whole take and a wobble
    per key (not the first and last key: in and out of the clip stays the same), `point` a wobble for a path
    point (hand, magazine on its way)."""

    def __init__(self, n):
        self.n = n
        self.rng = random.Random(1000 + n * 7919)
        r = self.rng
        self.bias = [r.gauss(0, 0.005), r.gauss(0, 0.006), r.gauss(0, 0.005), r.gauss(0, 2.6), r.gauss(0, 1.4), r.gauss(0, 2.6)]

    def gun(self, keys):
        if self.n == 0:
            return keys
        ticks = sorted(keys)
        out = {}
        for t in ticks:
            v = keys[t]
            if t in (ticks[0], ticks[-1]) or all(abs(x) < 1e-9 for x in v[:6]):
                out[t] = v
                continue
            # the bias fades where the gun is near its rest (the overshoot keys stay small)
            size = min(1.0, max(abs(v[3]) / 15.0, math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2) / 0.06))
            r = self.rng
            wob = [r.gauss(0, 0.0018), r.gauss(0, 0.0022), r.gauss(0, 0.0018), r.gauss(0, 0.9), r.gauss(0, 0.6), r.gauss(0, 0.9)]
            out[t] = tuple(v[k] + (self.bias[k] + wob[k]) * size for k in range(6)) + tuple(v[6:])
        return out

    def point(self, p, sigma=0.01):
        if self.n == 0:
            return p
        r = self.rng
        return tuple(x + r.gauss(0, sigma) for x in p)

    def angle(self, a, sigma=3.0):
        return a if self.n == 0 else a + self.rng.gauss(0, sigma)
