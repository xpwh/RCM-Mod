"""C4 plastic explosive and its radio firing device, built in Blender (C4Models in the game).

charge     a bundle of four M112 demolition blocks (each 1 x 2 x 11 in, Composition C-4 in olive-drab Mylar film
           with the yellow stencilled markings), two by two, held with two bands of duct tape; on top a radio
           receiver/initiator (black box, green LCD, keypad, red LED, rubber antenna), two electric blasting caps
           pushed into the end of the top blocks, their red and black leg wires running back to the receiver.
           Local space: the face that lies against the wall at y = 0, out of the wall +y, the long side along x.
           Part: charge.
detonator  the hand-held radio firing device: an olive-drab case with rubber grips, a green LCD, two LEDs
           (power green, armed red), the arming toggle switch, the red FIRE button under a hinged red safety
           cover, a rubber antenna at the front. Item space: origin in the middle of the case where the hand
           holds it, -z forward (the antenna end), +y up (the controls).
           Parts: body, cover (about its hinge), button (pressed down -y), switch (the toggle's lever about its
           pivot; forward/-z = armed).

    python c4.py <out_dir> --model=charge|detonator [--shots] [--export]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from structkit import _coords, _finish, _math, _mix, _mixf, _noise, _ramp  # noqa: E402

MODEL = ARGS.get("model", "charge")

# ---------------------------------------------------------------- the charge
L = 0.279 / 2       # half the length of an M112 block
W = 0.051           # its width
T = 0.0254          # its thickness
TOP = 2 * T         # top of the bundle
RX0, RX1, RZ = -0.034, 0.034, 0.029   # the receiver
RTOP = TOP + 0.024
LED = (0.027, RTOP + 0.002, -0.021)   # C4Models.LED

# ---------------------------------------------------------------- the firing device
BW, BH, BZ0, BZ1 = 0.031, 0.016, -0.07, 0.06
BUTTON = (0.012, BH, -0.03)
HINGE = (0.012, BH + 0.0105, -0.0165)       # C4Models.COVER_HINGE
SWITCH = (-0.015, BH + 0.004, -0.004)       # C4Models.SWITCH_PIVOT
LED_PWR = (0.010, BH, 0.006)                # C4Models.LED_PWR / LED_ARM (on top of the case)
LED_ARM = (0.021, BH, 0.006)


def small_paint(name, color, rough=0.5, grime=0.3, wear=0.25, wear_col=(0.1, 0.1, 0.1), metal=0.0):
    """Painted or moulded plastic at hand size: aged on a centimetre scale, not a building's."""
    m, nt, bsdf = node_mat(name)
    base = _mix(nt, _math(nt, "MULTIPLY", _noise(nt, 40.0, 4, 0.5, _coords(nt)), 0.16), tuple(color), tuple(c * 0.8 for c in color))
    col = weathered(nt, base, grime, wear, 0.0, 0.0, wear_col, scale=0.04)
    return _finish(m, nt, bsdf, col, metal, _mixf(nt, _noise(nt, 80.0, 4, 0.5, _coords(nt)), rough - 0.06, rough + 0.08))


def small_rubber(name, color=(0.03, 0.03, 0.032)):
    m, nt, bsdf = node_mat(name)
    col = _mix(nt, _noise(nt, 200.0, 4, 0.5, _coords(nt)), tuple(color), tuple(c * 1.5 for c in color))
    col = weathered(nt, col, 0.25, 0.0, 0.0, 0.0, scale=0.04)
    return _finish(m, nt, bsdf, col, 0.0, 0.85)


def film():
    """Olive-drab Mylar film over the putty: a slight sheen, creased, finger marks."""
    m, nt, bsdf = node_mat("film")
    pos = _coords(nt)
    base = _mix(nt, _noise(nt, 18.0, 6, 0.6, pos), (0.135, 0.15, 0.075), (0.105, 0.118, 0.058))
    crease = _ramp(nt, _noise(nt, 60.0, 3, 0.5, pos), 0.6, 0.75)
    base = _mix(nt, _math(nt, "MULTIPLY", crease, 0.35), base, (0.19, 0.205, 0.11))
    col = weathered(nt, base, 0.25, 0.0, 0.0, 0.0, scale=0.05)
    rough = _mixf(nt, _noise(nt, 40.0, 4, 0.5, pos), 0.32, 0.48)
    return _finish(m, nt, bsdf, col, 0.0, rough, 0.25)


def tape():
    """Duct tape: grey cloth-backed, the weave just showing, a little dirty."""
    m, nt, bsdf = node_mat("tape")
    pos = _coords(nt)
    col = _mix(nt, _noise(nt, 300.0, 2, 0.5, pos), (0.31, 0.31, 0.3), (0.25, 0.25, 0.245))
    col = weathered(nt, col, 0.35, 0.0, 0.0, 0.0, scale=0.05)
    return _finish(m, nt, bsdf, col, 0.15, 0.55)


def stencil(text, x, y, z, size, mat, part, ry=0.0):
    """Flat text lying on a +y face, reading along +x (the M112's markings)."""
    cu = bpy.data.curves.new("txt", "FONT")
    cu.body = text
    cu.size = size
    cu.extrude = 0.0
    cu.resolution_u = 1
    cu.fill_mode = "FRONT"
    cu.align_x = "CENTER"
    cu.align_y = "CENTER"
    ob = bpy.data.objects.new("txt", cu)
    scene.collection.objects.link(ob)
    ob.location = mc(x, y + 0.00012, z)
    ob.rotation_euler = (0.0, 0.0, ry)
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
    me.transform(ob.matrix_world)
    bpy.data.objects.remove(ob)
    bpy.data.curves.remove(cu)
    o = bpy.data.objects.new("text", me)
    scene.collection.objects.link(o)
    me.materials.clear()
    me.materials.append(mat)
    PARTS.setdefault(part, []).append(o)
    return o


def build_charge():
    c4 = film()
    yellow = plain("stencil", (0.62, 0.5, 0.06), 0.6)
    tp = tape()
    black = small_paint("receiver", (0.028, 0.028, 0.03), rough=0.45, grime=0.3, wear=0.3, wear_col=(0.13, 0.13, 0.13))
    lcd = glass("lcd", (0.05, 0.11, 0.06))
    keys = small_rubber("keys", (0.07, 0.07, 0.075))
    led = plain("led", (0.5, 0.02, 0.02), 0.2)
    ant = small_rubber("antenna")
    alu = plain("cap", (0.66, 0.66, 0.68), 0.32, 1.0)
    red = plain("wire_red", (0.45, 0.02, 0.015), 0.4)
    blk = plain("wire_black", (0.02, 0.02, 0.02), 0.4)
    g = 0.0006
    for zi, (z0, z1) in enumerate(((-W, -g), (g, W))):
        for yi, (y0, y1) in enumerate(((0.0, T - g), (T + g, TOP))):
            box("m112", -L, y0, z0, L, y1, z1, c4, "charge", bevel=0.0035)
        zc = (z0 + z1) / 2
        stencil("CHARGE DEMOLITION M112", -0.035, TOP + 0.00005, zc - 0.009, 0.0062, yellow, "charge")
        stencil("1-1/4 LB  COMP C-4", -0.035, TOP + 0.00005, zc + 0.001, 0.0062, yellow, "charge")
        stencil("LOT MA-97J012-005", -0.035, TOP + 0.00005, zc + 0.0105, 0.0045, yellow, "charge")
    # two bands of tape round the bundle
    for xc in (-0.095, 0.095):
        box("tape", xc - 0.016, -0.0009, -W - 0.0009, xc + 0.016, TOP + 0.0009, W + 0.0009, tp, "charge", bevel=0.0012)
    # the receiver
    box("receiver", RX0, TOP + 0.0004, -RZ, RX1, RTOP, RZ, black, "charge", bevel=0.003)
    box("lcd", RX0 + 0.007, RTOP, -0.017, 0.002, RTOP + 0.0007, 0.017, lcd, "charge", bevel=0.0004)
    for i in range(3):
        for j in range(2):
            x = 0.010 + j * 0.010
            z = -0.009 + i * 0.010
            box("key", x - 0.0035, RTOP, z - 0.0035, x + 0.0035, RTOP + 0.0018, z + 0.0035, keys, "charge", bevel=0.0008)
    lathe("led", (LED[0], RTOP, LED[2]), (0, 1, 0), [(0, 0.0032), (0.0012, 0.0032), (0.0026, 0.0022), (0.0034, 0)], led, "charge", k=16)
    # the antenna: a collar, then the rubber whip leaning out
    cyl("ant_base", (0.024, RTOP, 0.021), (0.024, RTOP + 0.007, 0.021), 0.0042, black, "charge", k=16)
    cyl("ant", (0.024, RTOP + 0.007, 0.021), (0.040, RTOP + 0.085, 0.034), 0.0028, ant, "charge", k=12, r1=0.0022)
    lathe("ant_tip", (0.040, RTOP + 0.085, 0.034), (0.2, 0.97, 0.16), [(0, 0.0022), (0.003, 0.0018), (0.005, 0)], ant, "charge", k=12)
    # the blasting caps in the end of the top blocks, their leg wires back to the receiver
    for k, z in enumerate((-W / 2, W / 2)):
        y = T + T / 2
        cyl("cap", (L - 0.025, y, z), (L + 0.03, y, z), 0.0036, alu, "charge", k=14)
        cyl("crimp", (L + 0.022, y, z), (L + 0.03, y, z), 0.0039, alu, "charge", k=14)
        for w, (mat, dz) in enumerate(((red, -0.0013), (blk, 0.0013))):
            zz = z + dz
            pts = [(L + 0.03, y, zz), (L + 0.045, y + 0.004, zz), (L + 0.05, TOP + 0.014, zz * 0.8),
                   (L + 0.02, TOP + 0.02, zz * 0.55), (RX1 + 0.012, TOP + 0.016, zz * 0.4), (RX1 - 0.001, TOP + 0.011, zz * 0.35)]
            pipe("wire", pts, 0.0011, mat, "charge", k=8, bend=0.012)


def build_detonator():
    od = small_paint("case", (0.105, 0.115, 0.07), rough=0.62, grime=0.35, wear=0.35, wear_col=(0.05, 0.05, 0.045))
    grip = small_rubber("grip", (0.03, 0.03, 0.03))
    black = small_paint("bezel", (0.025, 0.025, 0.027), rough=0.5, grime=0.2, wear=0.2)
    lcd = glass("lcd", (0.06, 0.13, 0.07))
    steel = plain("steel", (0.75, 0.75, 0.76), 0.25, 1.0)
    red = small_paint("red", (0.42, 0.02, 0.015), rough=0.35, grime=0.15, wear=0.2, wear_col=(0.25, 0.05, 0.04))
    white = plain("label", (0.75, 0.75, 0.72), 0.6)
    ledg = plain("led_green", (0.03, 0.4, 0.05), 0.2)
    ledr = plain("led_red", (0.5, 0.02, 0.02), 0.2)
    ant = small_rubber("antenna")
    # the case, the rubber grips down its sides and over the back end
    box("case", -BW, -BH, BZ0, BW, BH, BZ1, od, "body", bevel=0.007)
    for xs in (-BW, BW):
        box("grip", xs - 0.0022, -BH + 0.003, -0.035, xs + 0.0022, BH - 0.003, 0.052, grip, "body", bevel=0.002)
    box("endcap", -BW + 0.002, -BH + 0.002, BZ1 - 0.002, BW - 0.002, BH - 0.002, BZ1 + 0.005, grip, "body", bevel=0.003)
    # the LCD with its bezel, near the end towards you
    box("bezel", -0.021, BH - 0.001, 0.016, 0.021, BH + 0.0016, 0.047, black, "body", bevel=0.0015)
    box("lcd", -0.017, BH + 0.0016, 0.020, 0.017, BH + 0.0022, 0.043, lcd, "body", bevel=0.0003)
    # LEDs: power (green) and armed (red)
    for (x, mat) in ((LED_PWR[0], ledg), (LED_ARM[0], ledr)):
        lathe("led", (x, BH, LED_PWR[2]), (0, 1, 0), [(0, 0.0028), (0.0012, 0.0028), (0.0024, 0.002), (0.0031, 0)], mat, "body", k=16)
    stencil("PWR", LED_PWR[0], BH + 0.0001, LED_PWR[2] + 0.0055, 0.0035, white, "body")
    stencil("ARM", LED_ARM[0], BH + 0.0001, LED_ARM[2] + 0.0055, 0.0035, white, "body")
    # the arming switch: a threaded bush and nut, the lever is its own part
    cyl("bush", (SWITCH[0], BH, SWITCH[2]), (SWITCH[0], BH + 0.0035, SWITCH[2]), 0.0055, steel, "body", k=6)
    cyl("bush2", (SWITCH[0], BH + 0.0035, SWITCH[2]), (SWITCH[0], SWITCH[1], SWITCH[2]), 0.0035, steel, "body", k=16)
    stencil("SAFE", SWITCH[0], BH + 0.0001, SWITCH[2] + 0.011, 0.0034, white, "body")
    stencil("ARMED", SWITCH[0], BH + 0.0001, SWITCH[2] - 0.011, 0.0034, white, "body")
    sx, sy, sz = SWITCH
    cyl("lever", (sx, sy, sz), (sx, sy + 0.016, sz + 0.0055), 0.0017, steel, "switch", k=10, r1=0.0014)
    lathe("lever_tip", (sx, sy + 0.016, sz + 0.0055), (0, 0.946, 0.325), [(-0.0005, 0.0021), (0.002, 0.0021), (0.003, 0)], steel, "switch", k=12)
    # the fire button in its guard ring, the hinged safety cover over it
    lathe("ring", (BUTTON[0], BH, BUTTON[2]), (0, 1, 0), [(0, 0.0115), (0.0035, 0.0115), (0.0035, 0.0098), (0.0, 0.0098)], black, "body", k=28)
    stencil("FIRE", BUTTON[0], BH + 0.0001, BUTTON[2] - 0.017, 0.0036, white, "body")
    lathe("button", BUTTON, (0, 1, 0), [(0, 0.0088), (0.004, 0.0088), (0.0062, 0.0078), (0.0072, 0.006), (0.0078, 0)], red, "button", k=28)
    # the cover, closed over the button, hinged along its back edge
    cz0, cz1 = -0.0445, HINGE[2]
    hx0, hx1 = BUTTON[0] - 0.0135, BUTTON[0] + 0.0135
    top = BH + 0.011
    box("cover_top", hx0, top - 0.0016, cz0, hx1, top, cz1, red, "cover", bevel=0.0007)
    for x0, x1 in ((hx0, hx0 + 0.0015), (hx1 - 0.0015, hx1)):
        box("cover_side", x0, BH + 0.0006, cz0, x1, top, cz1, red, "cover", bevel=0.0004)
    box("cover_front", hx0, BH + 0.0006, cz0, hx1, top, cz0 + 0.0015, red, "cover", bevel=0.0004)
    cyl("hinge_pin", (hx0 - 0.002, HINGE[1], HINGE[2]), (hx1 + 0.002, HINGE[1], HINGE[2]), 0.0018, steel, "body", k=10)
    # the antenna at the front
    cyl("ant_base", (0.019, 0.004, BZ0 + 0.004), (0.019, 0.004, BZ0 - 0.01), 0.0055, black, "body", k=16)
    cyl("ant", (0.019, 0.004, BZ0 - 0.01), (0.019, 0.016, BZ0 - 0.095), 0.0034, ant, "body", k=12, r1=0.0026)
    lathe("ant_tip", (0.019, 0.016, BZ0 - 0.095), (0, 0.14, -0.99), [(0, 0.0026), (0.003, 0.002), (0.005, 0)], ant, "body", k=12)
    # four screws in the top
    for x in (-BW + 0.006, BW - 0.006):
        for z in (BZ0 + 0.008, BZ1 - 0.008):
            cyl("screw", (x, BH - 0.0005, z), (x, BH + 0.0006, z), 0.0019, steel, "body", k=10)


def studio():
    """A grey backdrop and soft lights for looking at small things."""
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.use_denoising = True
    scene.view_settings.view_transform = "AgX"
    world = bpy.data.worlds.new("w")
    scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.32, 0.33, 0.35, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.6
    for name, loc, energy in (("key", (0.6, -0.5, 0.8), 30.0), ("fill", (-0.7, -0.3, 0.4), 10.0), ("rim", (0.0, 0.8, 0.5), 14.0)):
        li = bpy.data.lights.new(name, "AREA")
        li.energy = energy
        li.size = 0.5
        ob = bpy.data.objects.new(name, li)
        scene.collection.objects.link(ob)
        ob.location = loc
        ob.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    bm = bmesh.new()
    bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=3)
    m, nt, bsdf = node_mat("floor")
    bsdf.inputs["Base Color"].default_value = (0.25, 0.25, 0.26, 1)
    bsdf.inputs["Roughness"].default_value = 0.8
    me = bpy.data.meshes.new("floor")
    bm.to_mesh(me)
    bm.free()
    g = bpy.data.objects.new("floor", me)
    scene.collection.objects.link(g)
    me.materials.append(m)
    g.location = (0, 0, -0.0005 if MODEL == "charge" else -BH - 0.0005)


def shoot_close(name, eye, target, lens=50, res=(1200, 800), samples=64):
    cam = bpy.data.cameras.new(name)
    cam.lens = lens
    cam.clip_start = 0.005
    ob = bpy.data.objects.new(name, cam)
    scene.collection.objects.link(ob)
    ob.location = mcv(eye)
    ob.rotation_euler = (mcv(target) - mcv(eye)).to_track_quat("-Z", "Y").to_euler()
    scene.camera = ob
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.cycles.samples = samples
    scene.render.filepath = os.path.join(OUT, name + ".png")
    bpy.ops.render.render(write_still=True)
    print("rendered", scene.render.filepath, flush=True)


def place(part, pivot, rot_x=0.0, dy=0.0):
    """Shots only: turn a moving part about its pivot (about block +x; positive lifts its -z end) or push it along y."""
    from mathutils import Matrix
    p = mcv(pivot)
    m = Matrix.Translation(p + mcv((0, dy, 0))) @ Matrix.Rotation(rot_x, 4, "X") @ Matrix.Translation(-p)
    for ob in PARTS.get(part, []):
        ob.matrix_world = m @ ob.matrix_world


if MODEL == "charge":
    build_charge()
else:
    build_detonator()

if "shots" in ARGS:
    studio()
    if MODEL == "charge":
        shoot_close("c4_charge", (0.32, 0.26, 0.3), (0.0, 0.03, 0.0), lens=45)
        shoot_close("c4_charge_end", (0.36, 0.12, -0.12), (0.08, 0.04, 0.0), lens=55)
    else:
        place("cover", HINGE, rot_x=math.radians(float(os.environ.get("COVER", "105"))))
        place("switch", SWITCH, rot_x=math.radians(float(os.environ.get("SWITCH_ANGLE", "-40"))))
        shoot_close("c4_detonator", (0.17, 0.2, 0.18), (0.0, 0.0, -0.03), lens=45)
        shoot_close("c4_detonator_top", (0.03, 0.3, 0.06), (0.0, 0.0, -0.02), lens=50)
if "export" in ARGS:
    if MODEL == "charge":
        export("c4_charge", ["charge"], tex=1024, ao=0.03)
    else:
        export("c4_detonator", ["body", "cover", "button", "switch"], pivots={"cover": HINGE, "button": BUTTON, "switch": SWITCH}, tex=1024, ao=0.03)

if "icon" in ARGS:
    # the inventory icon: lit from the top left, on transparent, rendered big and brought down to 16 x 16 (see gen_c4_icons)
    studio()
    for ob in list(scene.objects):
        if ob.name == "floor":
            bpy.data.objects.remove(ob)
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "Standard"
    if MODEL == "charge":
        shoot_close("icon_c4", (0.34, 0.42, 0.34), (0.0, 0.03, 0.0), lens=60, res=(256, 256), samples=48)
    else:
        shoot_close("icon_c4_detonator", (0.2, 0.26, 0.2), (0.0, 0.0, -0.03), lens=60, res=(256, 256), samples=48)
