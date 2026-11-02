"""The round-lid silo (RoundHatchModel), after the Soviet/Russian silo launchers, built in Blender.

Parts (block space, origin at the bottom centre of the silo block, ground at y = 1):
  collar  the concrete headworks: square pad, the raised round collar with its armoured seat, bolt housings,
          the hinge abutment with brackets and ram trunnions, hydraulic power unit, beacons, sensor, antenna
  lid     the domed armoured lid, closed, about its hinge (2.06, 1.4, 0) - the game turns it about +z
  bolt    one locking bolt along +x, engaged (the game turns copies round the rim and draws them back along x)
  ram     a hydraulic cylinder along +y from its trunnion pin at the origin
  rod     its piston rod along +y from the lid's eye at the origin

    python silo_hatch.py <out_dir> [--shots] [--export]
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from silokit import *  # noqa: E402,F401,F403

GROUND = 1.0
SEAT = GROUND + 0.1      # top of the collar, where the lid seats
BORE = 1.45              # the launch tube's bore (silo_tube)
COLLAR_R = 2.3
LID_R = 1.74
HINGE_X, HINGE_Y = 2.06, SEAT + 0.3
RAM_Z = 1.34
RAM_BASE = (2.34, SEAT + 0.12)
RAM_EYE = (1.42, SEAT + 0.56)
CYL_LENGTH = 0.62
ROD_LENGTH = 0.6
BOLTS = 8
BOLT_TRAVEL = 0.3
BEACONS = ((-1.7, 1.62), (-1.7, -1.62))
PAD = 3.2


def bolt_angle(i):
    """Bolts spaced round the rim, clear of the hinge side (RoundHatchModel.boltAngle)."""
    return math.pi / 4 + i * (1.5 * math.pi) / (BOLTS - 1)


# ---------------------------------------------------------------- materials
M_CONC = concrete("concrete", (0.3, 0.295, 0.28), grime=0.65, stains=0.6)
M_CONC_D = concrete("concrete_dark", (0.22, 0.215, 0.205), grime=0.7)
M_PAD = concrete("pad", (0.27, 0.265, 0.25), grime=0.55, stains=0.75)
M_OLIVE = paint("lid_olive", (0.105, 0.115, 0.06), rough=0.6, metal=0.2, grime=0.6, wear=0.45, streaks=0.65, rust=0.45, wear_col=(0.3, 0.29, 0.26))
M_OLIVE_D = paint("olive_dark", (0.07, 0.08, 0.045), rough=0.6, metal=0.3, grime=0.6, wear=0.4, streaks=0.5, rust=0.5, wear_col=(0.3, 0.29, 0.26))
M_STEEL = paint("steel", (0.18, 0.185, 0.18), rough=0.5, metal=0.6, grime=0.5, wear=0.4, rust=0.5, wear_col=(0.42, 0.42, 0.42))
M_RUST = paint("rusty", (0.16, 0.08, 0.04), rough=0.75, metal=0.4, grime=0.5, wear=0.2, rust=1.0, wear_col=(0.3, 0.3, 0.3))
M_CHROME = galvanized("chrome", (0.7, 0.7, 0.7), rough=0.15, grime=0.15)
M_GALV = galvanized("galvanized")
M_RUBBER = rubber()
M_HAZ = hazard()
M_RED_LENS = glass("red_lens", (0.35, 0.02, 0.01))
M_DARK = plain("void", (0.012, 0.012, 0.012), 0.9)
M_YELLOW = paint("yellow", (0.6, 0.42, 0.03), rough=0.55, grime=0.5, wear=0.45, streaks=0.3)
M_RED = paint("red_paint", (0.45, 0.03, 0.02), rough=0.5, grime=0.4, wear=0.35)
M_WHITE = paint("white_paint", (0.7, 0.7, 0.67), rough=0.5, grime=0.45, wear=0.3)

C = "collar"
L = "lid"

# ================================================================= collar
# a square pad flush with the ground, its edge chamfered, expansion joints
loft("pad", [square(PAD, GROUND + 0.04), square(PAD + 0.06, GROUND - 0.0), square(PAD + 0.06, GROUND - 0.1)], M_PAD, C, smooth=20)
o, i = square_circle(PAD, BORE, 32)
frame_plate("pad_top", o, i, M_PAD, C, GROUND - 0.1, GROUND + 0.04)
for x in (-2.55, 2.55):
    box("joint", x - 0.012, GROUND + 0.036, -PAD, x + 0.012, GROUND + 0.042, PAD, M_DARK, C, bevel=0)
    box("joint", -PAD, GROUND + 0.036, x - 0.012, PAD, GROUND + 0.042, x + 0.012, M_DARK, C, bevel=0)
# the raised collar: a thick concrete ring with a chamfered outer edge, its throat lined with steel
lathe("collar", (0, GROUND, 0), (0, 1, 0), [(0.0, COLLAR_R + 0.22), (0.04, COLLAR_R + 0.2), (SEAT - GROUND - 0.02, COLLAR_R), (SEAT - GROUND, COLLAR_R - 0.03),
                                             (SEAT - GROUND, BORE + 0.12), (SEAT - GROUND - 0.03, BORE)], M_CONC, C, k=48, smooth=30, caps=False)
inner_tube("throat", BORE, GROUND - 0.15, SEAT - 0.03, 40, M_STEEL, C)
# the armoured seat ring the lid closes on, the hazard band round the sweep of the lid
lathe("seat_ring", (0, SEAT, 0), (0, 1, 0), [(-0.03, BORE), (0.012, BORE), (0.012, 1.8), (-0.005, 1.84)], M_STEEL, C, k=48, smooth=30, caps=False)
lathe("seal_seat", (0, SEAT + 0.012, 0), (0, 1, 0), [(0.0, 1.5), (0.006, 1.5), (0.006, 1.62), (0.0, 1.62)], M_RUBBER, C, k=48, loop=True)
lathe("haz_band", (0, SEAT - 0.003, 0), (0, 1, 0), [(0.0, 1.98), (0.006, 1.98), (0.006, 2.22), (0.0, 2.22)], M_HAZ, C, k=48, loop=True)
bolts("seat_bolts", (0, SEAT + 0.012, 0), (0, 1, 0), 1.76, 32, 0.02, 0.018, M_STEEL, C)

# bolt housings: a steel box with an open slot towards the lid, the actuator on its outer end
for k in range(BOLTS):
    a = bolt_angle(k)
    ybox("housing_base", 0, 0, a, 1.84, GROUND + 0.02, -0.17, 2.44, SEAT, 0.17, M_CONC_D, C, bevel=0.02)
    ybox("housing_l", 0, 0, a, 1.84, SEAT, -0.16, 2.42, SEAT + 0.17, -0.09, M_STEEL, C, bevel=0.008)
    ybox("housing_r", 0, 0, a, 1.84, SEAT, 0.09, 2.42, SEAT + 0.17, 0.16, M_STEEL, C, bevel=0.008)
    ybox("housing_top", 0, 0, a, 2.12, SEAT + 0.17, -0.16, 2.42, SEAT + 0.19, 0.16, M_STEEL, C, bevel=0.005)
    ybox("actuator", 0, 0, a, 2.42, SEAT + 0.02, -0.08, 2.6, SEAT + 0.15, 0.08, M_OLIVE_D, C, bevel=0.01)
    p0 = rot_y((2.6, SEAT + 0.085, 0.0), a)
    p1 = rot_y((2.66, SEAT + 0.085, 0.0), a)
    cyl("actuator_cap", p0, p1, 0.05, M_STEEL, C, k=8)
    h0 = rot_y((2.5, SEAT + 0.15, 0.04), a)
    h1 = rot_y((2.52, SEAT + 0.2, 0.25), a)
    h2 = rot_y((2.62, GROUND + 0.05, 0.4), a)
    pipe("actuator_hose", [h0, h1, h2], 0.014, M_RUBBER, C, k=6, bend=0.08)

# the hinge abutment on +x: a concrete block carrying the hinge brackets and the ram trunnions
wedge("abutment", [(1.95, GROUND, -1.7), (2.8, GROUND, -1.7), (2.8, GROUND, 1.7), (1.95, GROUND, 1.7),
                   (1.95, SEAT + 0.04, -1.55), (2.68, SEAT + 0.04, -1.55), (2.68, SEAT + 0.04, 1.55), (1.95, SEAT + 0.04, 1.55)], M_CONC_D, C, bevel=0.03)
box("abutment_plate", 1.98, SEAT + 0.04, -1.5, 2.62, SEAT + 0.07, 1.5, M_STEEL, C, bevel=0.008)
bolts("abut_bolts", (2.3, SEAT + 0.07, 0.0), (0, 1, 0), 0.0, 1, 0.025, 0.02, M_STEEL, C)
for z in (-1.4, -0.4, 0.4, 1.4):
    for x in (2.04, 2.56):
        bolts("plate_bolts", (x, SEAT + 0.07, z), (0, 1, 0), 0.0, 1, 0.02, 0.018, M_STEEL, C)
for z in (-0.95, 0.95):
    for side in (-1, 1):
        z0 = z + side * 0.2
        za, zb = sorted((z0, z0 + side * 0.09))
        wedge("hinge_bracket", [(1.9, SEAT + 0.07, za), (2.32, SEAT + 0.07, za), (2.32, SEAT + 0.07, zb), (1.9, SEAT + 0.07, zb),
                                (1.96, HINGE_Y + 0.12, za), (2.18, HINGE_Y + 0.12, za), (2.18, HINGE_Y + 0.12, zb), (1.96, HINGE_Y + 0.12, zb)],
              M_OLIVE_D, C, bevel=0.01)
        lathe("bracket_boss", (HINGE_X, HINGE_Y, z0 + side * 0.09), (0, 0, side), [(0.0, 0.0), (0.0, 0.11), (0.03, 0.11), (0.03, 0.0)], M_OLIVE_D, C, k=10)
    cyl("hinge_pin", (HINGE_X, HINGE_Y, z - 0.33), (HINGE_X, HINGE_Y, z + 0.33), 0.055, M_RUST, C, k=8)
    for side in (-1, 1):
        lathe("pin_nut", (HINGE_X, HINGE_Y, z + side * 0.32), (0, 0, side), [(0.0, 0.0), (0.0, 0.07), (0.04, 0.07), (0.04, 0.0)], M_STEEL, C, k=6)
# ram trunnions: a pedestal and a pin through the cylinder's clevis
for s in (-1, 1):
    z = s * RAM_Z
    box("trunnion_base", RAM_BASE[0] - 0.17, SEAT + 0.04, z - 0.2, RAM_BASE[0] + 0.17, SEAT + 0.08, z + 0.2, M_STEEL, C, bevel=0.008)
    for side in (-1, 1):
        za, zb = sorted((z + side * 0.07, z + side * 0.13))
        wedge("trunnion_cheek", [(RAM_BASE[0] - 0.14, SEAT + 0.08, za), (RAM_BASE[0] + 0.14, SEAT + 0.08, za), (RAM_BASE[0] + 0.14, SEAT + 0.08, zb),
                                 (RAM_BASE[0] - 0.14, SEAT + 0.08, zb),
                                 (RAM_BASE[0] - 0.07, RAM_BASE[1] + 0.07, za), (RAM_BASE[0] + 0.07, RAM_BASE[1] + 0.07, za), (RAM_BASE[0] + 0.07, RAM_BASE[1] + 0.07, zb),
                                 (RAM_BASE[0] - 0.07, RAM_BASE[1] + 0.07, zb)], M_OLIVE_D, C, bevel=0.006)
    cyl("trunnion_pin", (RAM_BASE[0], RAM_BASE[1], z - 0.17), (RAM_BASE[0], RAM_BASE[1], z + 0.17), 0.035, M_RUST, C, k=8)
# the hydraulic power unit beside the abutment, its lines to the rams
box("hpu_skid", 2.85, GROUND + 0.04, -0.45, 3.15, GROUND + 0.1, 0.45, M_STEEL, C, bevel=0.008)
box("hpu", 2.88, GROUND + 0.1, -0.4, 3.12, GROUND + 0.6, 0.4, M_OLIVE, C, bevel=0.02)
box("hpu_lid", 2.86, GROUND + 0.6, -0.42, 3.14, GROUND + 0.64, 0.42, M_OLIVE_D, C, bevel=0.01)
for k in range(4):
    y = GROUND + 0.2 + k * 0.08
    box("hpu_louvre", 3.12, y, -0.3, 3.14, y + 0.035, 0.3, M_OLIVE_D, C, bevel=0.004)
cyl("hpu_gauge", (2.87, GROUND + 0.45, 0.2), (2.86, GROUND + 0.45, 0.2), 0.04, M_WHITE, C, k=10)
for s in (-1, 1):
    pipe("ram_line", [(2.9, GROUND + 0.3, s * 0.38), (2.82, GROUND + 0.12, s * 0.6), (2.6, SEAT + 0.12, s * 1.0), (RAM_BASE[0] + 0.1, SEAT + 0.12, s * (RAM_Z - 0.2))],
         0.02, M_RUBBER, C, k=6, bend=0.12)
# warning beacons on posts, on the side away from the swing (the game lights 0.24 square, SEAT + 0.74 .. 0.97)
for bx, bz in BEACONS:
    box("beacon_footing", bx - 0.12, GROUND, bz - 0.12, bx + 0.12, SEAT + 0.02, bz + 0.12, M_CONC_D, C, bevel=0.015)
    cyl("beacon_post", (bx, SEAT + 0.02, bz), (bx, SEAT + 0.76, bz), 0.035, M_GALV, C, k=8)
    lathe("beacon_seat", (bx, SEAT + 0.72, bz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.1), (0.04, 0.1), (0.04, 0.0)], M_STEEL, C, k=10)
    lathe("beacon_lens", (bx, SEAT + 0.76, bz), (0, 1, 0), [(0.0, 0.09), (0.12, 0.09), (0.18, 0.06), (0.2, 0.0)], M_RED_LENS, C, k=10)
# seismic/intrusion sensor and the whip antenna of the launch control link
lathe("sensor_base", (-2.22, SEAT - 0.06, 0.0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.14), (0.08, 0.14), (0.08, 0.0)], M_CONC_D, C, k=10)
box("sensor", -2.34, SEAT + 0.02, -0.13, -2.1, SEAT + 0.36, 0.13, M_OLIVE, C, bevel=0.015)
box("sensor_window", -2.1, SEAT + 0.16, -0.08, -2.09, SEAT + 0.3, 0.08, M_DARK, C, bevel=0.003)
cyl("whip_base", (-2.22, SEAT + 0.36, 0.0), (-2.22, SEAT + 0.5, 0.0), 0.04, M_RUBBER, C, k=8)
lathe("whip_spring", (-2.22, SEAT + 0.5, 0.0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.025), (0.12, 0.02), (0.12, 0.0)], M_STEEL, C, k=6)
cyl("whip", (-2.22, SEAT + 0.62, 0.0), (-2.22, SEAT + 2.6, 0.0), 0.012, M_DARK, C, k=6, r1=0.006)
# the small round personnel hatch on the collar, with its handwheel
phx, _, phz = rot_y((2.02, 0, 0), math.radians(103))
lathe("ph_collar", (phx, SEAT - 0.02, phz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.28), (0.07, 0.26), (0.08, 0.0)], M_CONC_D, C, k=14, smooth=30)
lathe("ph_lid", (phx, SEAT + 0.06, phz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.21), (0.025, 0.21), (0.035, 0.18), (0.035, 0.0)], M_OLIVE, C, k=14, smooth=30)
lathe("ph_wheel", (phx, SEAT + 0.13, phz), (0, 1, 0), [(0.0, 0.13), (0.0, 0.11), (0.018, 0.11), (0.018, 0.13)], M_YELLOW, C, k=12, loop=True)
cyl("ph_spindle", (phx, SEAT + 0.09, phz), (phx, SEAT + 0.15, phz), 0.02, M_STEEL, C, k=6)
beam("ph_spoke", (phx - 0.12, SEAT + 0.14, phz), (phx + 0.12, SEAT + 0.14, phz), 0.014, 0.01, M_YELLOW, C)
beam("ph_spoke", (phx, SEAT + 0.14, phz - 0.12), (phx, SEAT + 0.14, phz + 0.12), 0.014, 0.01, M_YELLOW, C)
# drainage channel round the collar's foot and a few stencilled marks
lathe("drain", (0, GROUND + 0.041, 0), (0, 1, 0), [(0.0, COLLAR_R + 0.24), (0.002, COLLAR_R + 0.24), (0.002, COLLAR_R + 0.36), (0.0, COLLAR_R + 0.36)],
      M_DARK, C, k=48, loop=True)
for k in range(12):
    a = k * math.tau / 12 + 0.13
    p0 = (math.cos(a) * (COLLAR_R + 0.25), GROUND + 0.044, math.sin(a) * (COLLAR_R + 0.25))
    p1 = (math.cos(a) * (COLLAR_R + 0.35), GROUND + 0.044, math.sin(a) * (COLLAR_R + 0.35))
    beam("drain_bar", p0, p1, 0.03, 0.004, M_STEEL, C)

# ================================================================= the lid (closed)
Y0 = SEAT + 0.02


def dome(r):
    """Height of the lid's top above Y0 at radius r."""
    if r >= 1.66:
        return 0.2
    t = r / 1.66
    return 0.2 + 0.31 * (1 - t * t) ** 0.8


prof = [(0.0, 0.0), (0.0, LID_R - 0.02), (0.02, LID_R), (0.18, LID_R), (0.2, LID_R - 0.04), (0.2, 1.66)]
for r in (1.5, 1.3, 1.05, 0.8, 0.55, 0.3):
    prof.append((dome(r), r))
prof.append((dome(0.0), 0.0))
lathe("lid_body", (0, Y0, 0), (0, 1, 0), prof, M_OLIVE, L, k=48, smooth=40, caps=False)
# the plug that sits in the throat, the seal ring on the underside
lathe("plug", (0, Y0, 0), (0, 1, 0), [(0.0, 1.62), (-0.01, 1.5), (-0.012, BORE - 0.03), (-0.07, BORE - 0.05), (-0.08, 1.1), (-0.08, 0.0)], M_STEEL, L, k=40,
      smooth=30, caps=False)
lathe("lid_seal", (0, Y0 - 0.013, 0), (0, 1, 0), [(0.0, 1.5), (0.008, 1.5), (0.008, 1.62), (0.0, 1.62)], M_RUBBER, L, k=40, loop=True)
lathe("plug_ring", (0, Y0 - 0.081, 0), (0, 1, 0), [(0.0, 0.5), (-0.004, 0.5), (-0.004, 0.62), (0.0, 0.62)], M_DARK, L, k=24, loop=True)
# the underside of the plug: radial ribs and a boss, seen once the lid stands open
for k in range(8):
    a = math.tau * k / 8
    c, s_ = math.cos(a), math.sin(a)
    beam("under_rib", (c * 0.32, Y0 - 0.11, s_ * 0.32), (c * 1.3, Y0 - 0.11, s_ * 1.3), 0.05, 0.06, M_STEEL, L)
lathe("under_boss", (0, Y0 - 0.08, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.34), (-0.07, 0.32), (-0.08, 0.0)], M_STEEL, L, k=16)
bolts("under_bolts", (0, Y0 - 0.08, 0), (0, -1, 0), 1.2, 16, 0.02, 0.015, M_STEEL, L)
# a band of weld seam where the dome meets the rim band
lathe("rim_weld", (0, Y0 + 0.2, 0), (0, 1, 0), [(0.0, 1.62), (0.012, 1.62), (0.012, 1.7), (0.0, 1.7)], M_OLIVE_D, L, k=48, loop=True)
# radial gussets over the dome, following its curve
for k in range(8):
    a = math.tau * k / 8 + math.tau / 16
    c, s = math.cos(a), math.sin(a)
    rs = [0.42, 0.75, 1.1, 1.4, 1.66]
    for r0, r1 in zip(rs, rs[1:]):
        p0 = (c * r0, Y0 + dome(r0) + 0.035, s * r0)
        p1 = (c * r1, Y0 + dome(r1) + 0.035, s * r1)
        beam("gusset", p0, p1, 0.06, 0.09, M_OLIVE_D, L, roll=0.0)
# a hub plate over the centre with a ring of bolts, lifting lugs
lathe("hub", (0, Y0 + dome(0.0) - 0.02, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.45), (0.06, 0.44), (0.07, 0.0)], M_OLIVE_D, L, k=24, smooth=30)
bolts("hub_bolts", (0, Y0 + dome(0.0) + 0.05, 0), (0, 1, 0), 0.38, 12, 0.022, 0.02, M_STEEL, L)
lathe("hub_cap", (0, Y0 + dome(0.0) + 0.05, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.16), (0.04, 0.14), (0.05, 0.0)], M_STEEL, L, k=12)
for k in range(3):
    a = math.tau * k / 3 + 0.5
    c, s = math.cos(a), math.sin(a)
    r = 1.0
    p = (c * r, Y0 + dome(r), s * r)
    ybox("lug", p[0], p[2], -a + math.pi / 2, -0.08, p[1] - 0.04, -0.025, 0.08, p[1] + 0.12, 0.025, M_YELLOW, L, bevel=0.01)
    lathe("lug_eye", (p[0], p[1] + 0.07, p[2]), (-s, 0, c), [(-0.03, 0.045), (0.03, 0.045), (0.03, 0.025), (-0.03, 0.025)], M_DARK, L, k=8, loop=True)
# hinge arms from the dome out to the knuckles on the hinge line, the knuckles round the pin
for z in (-0.95, 0.95):
    za, zb = z - 0.1, z + 0.1
    pts = [(0.65, Y0 + dome(0.65) - 0.05, za), (HINGE_X - 0.08, HINGE_Y - 0.13, za), (HINGE_X - 0.08, HINGE_Y - 0.13, zb), (0.65, Y0 + dome(0.65) - 0.05, zb),
           (0.65, Y0 + dome(0.65) + 0.13, za), (HINGE_X + 0.02, HINGE_Y + 0.12, za), (HINGE_X + 0.02, HINGE_Y + 0.12, zb), (0.65, Y0 + dome(0.65) + 0.13, zb)]
    wedge("hinge_arm", [pts[0], pts[1], pts[5], pts[4], pts[3], pts[2], pts[6], pts[7]], M_OLIVE_D, L, bevel=0.015)
    lathe("knuckle", (HINGE_X, HINGE_Y, z - 0.11), (0, 0, 1), [(0.0, 0.0), (0.0, 0.14), (0.22, 0.14), (0.22, 0.0)], M_OLIVE_D, L, k=12)
# the ram lugs, bridging out from the arms to the eye pins
for s in (-1, 1):
    z = s * RAM_Z
    za, zb = sorted((s * 0.85, s * (RAM_Z + 0.09)))
    box("ram_lug", RAM_EYE[0] - 0.12, RAM_EYE[1] - 0.14, za, RAM_EYE[0] + 0.12, RAM_EYE[1] + 0.06, zb, M_OLIVE_D, L, bevel=0.015)
    wedge("ram_lug_web", [(RAM_EYE[0] - 0.1, Y0 + 0.2, s * 0.85), (RAM_EYE[0] + 0.1, Y0 + 0.2, s * 0.85), (RAM_EYE[0] + 0.1, Y0 + 0.2, s * 1.0),
                          (RAM_EYE[0] - 0.1, Y0 + 0.2, s * 1.0),
                          (RAM_EYE[0] - 0.1, RAM_EYE[1] - 0.14, s * 0.85), (RAM_EYE[0] + 0.1, RAM_EYE[1] - 0.14, s * 0.85),
                          (RAM_EYE[0] + 0.1, RAM_EYE[1] - 0.14, s * 1.0), (RAM_EYE[0] - 0.1, RAM_EYE[1] - 0.14, s * 1.0)], M_OLIVE_D, L, bevel=0.01)
    cyl("eye_pin", (RAM_EYE[0], RAM_EYE[1], z - 0.12), (RAM_EYE[0], RAM_EYE[1], z + 0.12), 0.035, M_RUST, L, k=8)
# bolt pockets in the rim band where the locking bolts engage
for k in range(BOLTS):
    a = bolt_angle(k)
    ybox("pocket", 0, 0, a, LID_R - 0.005, Y0 + 0.03, -0.075, LID_R + 0.002, Y0 + 0.13, 0.075, M_DARK, L, bevel=0)

# ================================================================= moving pieces
# one locking bolt along +x, engaged: its head in the lid's pocket, the flag on top
box("bolt", 1.62, SEAT + 0.04, -0.07, 2.02, SEAT + 0.13, 0.07, M_CHROME, "bolt", bevel=0.01)
box("bolt_tail", 2.02, SEAT + 0.05, -0.05, 2.1, SEAT + 0.12, 0.05, M_STEEL, "bolt", bevel=0.006)
box("bolt_flag", 2.03, SEAT + 0.12, -0.03, 2.07, SEAT + 0.16, 0.03, M_YELLOW, "bolt", bevel=0.005)
# the ram: clevis at the trunnion pin, the barrel, gland, hose port
R_ = "ram"
box("clevis", -0.05, -0.06, -0.06, 0.05, 0.08, 0.06, M_STEEL, R_, bevel=0.01)
lathe("barrel", (0, 0.06, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.08), (0.03, 0.1), (CYL_LENGTH - 0.12, 0.1), (CYL_LENGTH - 0.1, 0.09), (CYL_LENGTH - 0.06, 0.09), (CYL_LENGTH - 0.06, 0.0)],
      M_OLIVE, R_, k=10, smooth=40)
lathe("gland", (0, CYL_LENGTH - 0.02, 0), (0, 1, 0), [(-0.02, 0.0), (-0.02, 0.11), (0.05, 0.11), (0.06, 0.06), (0.06, 0.0)], M_STEEL, R_, k=10)
cyl("port", (0.0, 0.18, 0.08), (0.0, 0.18, 0.14), 0.025, M_STEEL, R_, k=6)
pipe("ram_hose", [(0.0, 0.18, 0.14), (0.0, 0.25, 0.16), (0.0, CYL_LENGTH - 0.12, 0.13), (0.0, CYL_LENGTH - 0.08, 0.09)], 0.015, M_RUBBER, R_, k=6, bend=0.04)
# the rod: eye at the lid's pin, the chromed rod
D_ = "rod"
box("rod_eye", -0.05, -0.06, -0.05, 0.05, 0.07, 0.05, M_STEEL, D_, bevel=0.012)
cyl("rod", (0, 0.05, 0), (0, ROD_LENGTH, 0), 0.04, M_CHROME, D_, k=8)

for p in ("collar", "lid", "bolt", "ram", "rod"):
    join_part(p)
count_quads(["collar", "lid", "bolt", "ram", "rod"])


def lid_angle(t):
    """HatchSiloBlock.lidAngle"""
    def sm(a, b, x):
        u = max(0.0, min(1.0, (x - a) / (b - a)))
        return u * u * (3 - 2 * u)
    return 0.06 * sm(0.14, 0.24, t) + (1.86 - 0.06) * sm(0.24, 1.0, t)


def swing(p, angle):
    x, y = p[0] - HINGE_X, p[1] - HINGE_Y
    c, s = math.cos(-angle), math.sin(-angle)
    return (HINGE_X + x * c - y * s, HINGE_Y + x * s + y * c)


def pose(t):
    """Linked copies of the moving parts as the game poses them at travel t."""
    from mathutils import Vector
    for part in ("lid", "bolt", "ram", "rod"):
        hide_part(part)
    made = []
    bolt = min(1.0, t / 0.14)
    bolt = bolt * bolt * (3 - 2 * bolt)
    for k in range(BOLTS):
        m = block_matrix((0, 0, 0), ((0, 1, 0), bolt_angle(k))) @ block_matrix((bolt * BOLT_TRAVEL, 0, 0))
        made.append(pose_copy("bolt", m))
    ang = lid_angle(t)
    made.append(pose_copy("lid", block_matrix((HINGE_X, HINGE_Y, 0), ((0, 0, 1), -ang)), pivot=(HINGE_X, HINGE_Y, 0)))
    eye = swing(RAM_EYE, ang)
    d = Vector((eye[0] - RAM_BASE[0], eye[1] - RAM_BASE[1], 0)).normalized()
    for s in (-1, 1):
        z = s * RAM_Z
        q = Vector((0, 1, 0)).rotation_difference(d)
        qm = mcv_rot(q)
        made.append(pose_copy("ram", Matrix_T((RAM_BASE[0], RAM_BASE[1], z)) @ qm))
        q2 = Vector((0, 1, 0)).rotation_difference(-d)
        made.append(pose_copy("rod", Matrix_T((eye[0], eye[1], z)) @ mcv_rot(q2)))
    return made


def Matrix_T(p):
    from mathutils import Matrix
    return Matrix.Translation(mcv(p))


def mcv_rot(q):
    """A block-space rotation (quaternion over block axes) as a Blender matrix: B = P^-1 R P with P block->Blender."""
    from mathutils import Matrix
    P = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))  # block (x, y, z) -> Blender (x, -z, y)
    R = q.to_matrix()
    return (P @ R @ P.inverted()).to_4x4()


if "shots" in ARGS:
    outdoor(sun_elevation=34, sun_rotation=140)
    scene.view_settings.exposure = -1.3
    ground_plane(GROUND - 0.004, hole=1.5)
    TAG = os.environ.get("TAG", "")
    which = os.environ.get("SHOTS", "closed,open,half,detail")
    made = []
    for name, t, eye, target, lens in (("closed", 0.0, (6.5, 4.5, 5.5), (0.3, 1.2, 0.0), 32), ("half", 0.55, (-2.5, 5.0, 7.0), (1.0, 1.6, 0.0), 30),
                                       ("open", 1.0, (-5.5, 4.5, -5.0), (1.0, 1.8, 0.0), 30), ("detail", 0.0, (3.6, 2.2, 2.6), (2.0, 1.3, 0.6), 30)):
        if name not in which:
            continue
        for ob in made:
            bpy.data.objects.remove(ob)
        made = pose(t)
        shoot("hatch_" + name + TAG, eye, target, lens=lens)
if "export" in ARGS:
    export("silo_hatch", ["collar", "lid", "bolt", "ram", "rod"], pivots={"lid": (HINGE_X, HINGE_Y, 0.0)}, tex=2048, ao=0.5)
