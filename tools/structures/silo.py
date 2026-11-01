"""The standard missile silo (SiloRenderer), after a Minuteman III launch facility, built in Blender.

Two exports from this script (block space, origin at the bottom centre of the silo block, ground at y = 1):

  silo        site     the hardened concrete apron round the tube's mouth, the door track, buffers, vent, personnel
                       access hatch, electrical cabinet, UHF antenna, beacons, sensors and the security fence
              door_l   the launcher closure's -x half, closed (the game slides it along -x)
              door_r   the +x half, closed (slides along +x)
  silo_tube   tube     the launch tube dug below the silo block: round steel liner, rings, cable trays, ladder,
                       folded work platforms, lamp housings, umbilical and the launch platform

    python silo.py <out_dir> [--shots] [--export] [--tube]      (--tube: the tube instead of the headworks)
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from structkit import *  # noqa: E402,F401,F403
from silokit import *  # noqa: E402,F401,F403

GROUND = 1.0
DECK = 1.45           # top of the apron
HOLE_R = 1.45         # the tube's bore
APRON = 3.8           # half-size of the flat apron
BERM = 4.65           # foot of the sloped edge
DOOR_X = 1.6          # each door half reaches this far from the meeting line
DOOR_Z = 1.9
RAIL_Z = 1.75
RAIL_TOP = DECK + 0.09
WHEEL_R = 0.075
DOOR_TRAVEL = 1.9
FLOOR = -21.0         # bottom of the tube (MissileSiloBlock.SHAFT_DEPTH)
PLATFORM = FLOOR + 0.3
FENCE = 4.8
BEACONS = ((-3.2, -3.2), (3.2, 3.2))

TUBE = "tube" in ARGS
SHOTS = "shots" in ARGS
BUILD_SITE = SHOTS or not TUBE
BUILD_TUBE = SHOTS or TUBE

# ---------------------------------------------------------------- materials
M_CONC = concrete("apron", (0.3, 0.295, 0.28), grime=0.6, stains=0.55)
M_CONC_D = concrete("concrete_dark", (0.22, 0.215, 0.205), grime=0.7)
M_GRAVEL = concrete("gravel", (0.2, 0.185, 0.16), grime=0.45, stains=0.9)
M_DOOR = concrete("door_concrete", (0.33, 0.325, 0.31), grime=0.65, stains=0.45)
M_STEEL = paint("steel", (0.2, 0.21, 0.2), rough=0.5, metal=0.55, grime=0.5, wear=0.4, rust=0.35, wear_col=(0.4, 0.4, 0.4))
M_DSTEEL = paint("door_steel", (0.2, 0.21, 0.195), rough=0.55, metal=0.35, grime=0.55, wear=0.45, streaks=0.7, rust=0.4,
                 wear_col=(0.38, 0.37, 0.35))
M_RAIL = paint("rail", (0.13, 0.11, 0.095), rough=0.65, metal=0.7, grime=0.4, wear=0.2, rust=0.9, wear_col=(0.4, 0.4, 0.4))
M_BRIGHT = galvanized("rail_head", (0.45, 0.45, 0.44), rough=0.3, grime=0.1)
M_GALV = galvanized("galvanized")
M_OLIVE = paint("olive", (0.12, 0.13, 0.065), rough=0.6, grime=0.6, wear=0.35, streaks=0.6, rust=0.3)
M_RUBBER = rubber()
M_HAZ = hazard()
M_GRATE = grating()
M_RED_LENS = glass("red_lens", (0.35, 0.02, 0.01))
M_DARK = plain("void", (0.012, 0.012, 0.012), 0.9)
M_YELLOW = paint("yellow", (0.6, 0.42, 0.03), rough=0.55, grime=0.5, wear=0.45, streaks=0.3)
M_WHITE = paint("sign_white", (0.75, 0.75, 0.72), rough=0.5, grime=0.35, wear=0.2)
M_RED = paint("red_paint", (0.5, 0.03, 0.02), rough=0.5, grime=0.4, wear=0.3)
M_LINER = paint("liner", (0.3, 0.32, 0.3), rough=0.5, metal=0.3, grime=0.7, wear=0.25, streaks=0.8, rust=0.25)
M_SOOT = concrete("soot", (0.07, 0.065, 0.06), grime=0.3, stains=0.3)

S = "site"


def sx(side, a, b):
    """The x range from a to b out from the meeting line on a door's side, ordered."""
    return min(side * a, side * b), max(side * a, side * b)


# ================================================================= site
def build_site():
    # ---- the apron: a slab round the round bore, chamfered edge, a gravel slope down to the ground
    outer, inner = square_circle(APRON, HOLE_R, 32)
    frame_plate("apron", outer, inner, M_CONC, S, GROUND - 0.1, DECK)
    loft("apron_edge", [square(APRON, DECK), square(APRON + 0.06, DECK - 0.06), square(APRON + 0.06, DECK - 0.16)], M_CONC, S, smooth=20)
    loft("berm", [square(APRON + 0.06, DECK - 0.16), square(APRON + 0.3, DECK - 0.2), square(BERM, GROUND - 0.03)], M_GRAVEL, S, smooth=20)
    inner_tube("bore", HOLE_R, GROUND - 0.12, DECK, 32, M_CONC_D, S)
    # the steel seat the doors close on and the lip of the bore
    o2, i2 = square_circle(1.62, HOLE_R, 32)
    frame_plate("seat", o2, i2, M_STEEL, S, DECK, DECK + 0.012)
    lathe("lip", (0, DECK - 0.1, 0), (0, 1, 0), [(0.0, HOLE_R - 0.04), (0.112, HOLE_R - 0.04), (0.112, HOLE_R + 0.01), (0.0, HOLE_R + 0.01)],
          M_STEEL, S, k=32, loop=True)
    # expansion joints in the slab, kept clear of the seat
    for x in (-2.55, 2.55):
        box("joint", x - 0.012, DECK - 0.004, -APRON, x + 0.012, DECK + 0.002, APRON, M_DARK, S, bevel=0)
    for z in (-2.7, 2.7):
        box("joint", -APRON, DECK - 0.004, z - 0.012, APRON, DECK + 0.002, z + 0.012, M_DARK, S, bevel=0)
    # yellow keep-clear lines either side of the door track
    for z in (-2.06, 2.06):
        box("track_line", -APRON + 0.15, DECK, z - 0.04, APRON - 0.15, DECK + 0.004, z + 0.04, M_YELLOW, S, bevel=0)

    # ---- the door track: crane rails on sole plates, clamps and anchors, buffers at the ends
    rail = [(-0.06, 0.0), (0.06, 0.0), (0.06, 0.015), (0.016, 0.025), (0.016, 0.045), (0.033, 0.05), (0.033, 0.07),
            (-0.033, 0.07), (-0.033, 0.05), (-0.016, 0.045), (-0.016, 0.025), (-0.06, 0.015)]
    for z in (-RAIL_Z, RAIL_Z):
        box("sole", -APRON + 0.02, DECK, z - 0.13, APRON - 0.02, DECK + 0.02, z + 0.13, M_RAIL, S, bevel=0.004)
        section("rail", (-APRON + 0.05, DECK + 0.02, z), (APRON - 0.05, DECK + 0.02, z), rail, M_RAIL, S)
        box("rail_head", -APRON + 0.05, RAIL_TOP - 0.002, z - 0.03, APRON - 0.05, RAIL_TOP + 0.001, z + 0.03, M_BRIGHT, S, bevel=0)
        x = -APRON + 0.25
        while x < APRON - 0.1:
            for s in (-1, 1):
                box("clamp", x - 0.05, DECK + 0.02, z + s * 0.055, x + 0.05, DECK + 0.045, z + s * 0.1, M_RAIL, S, bevel=0.004)
            bolts("anchor", (x, DECK + 0.02, z), (0, 1, 0), 0.105, 2, 0.014, 0.025, M_STEEL, S, phase=math.pi / 2)
            x += 0.55
        for s in (-1, 1):
            # buffer stops: a steel block on a plinth, a rubber pad facing the door
            bx0, bx1 = sx(s, 3.6, 3.8)
            box("buffer_plinth", bx0 - 0.02, DECK - 0.02, z - 0.18, bx1 + 0.02, DECK + 0.08, z + 0.18, M_CONC_D, S, bevel=0.02)
            box("buffer", bx0, DECK + 0.08, z - 0.13, bx1, DECK + 0.36, z + 0.13, M_YELLOW, S, bevel=0.015)
            px0, px1 = sx(s, 3.53, 3.6)
            cyl("buffer_pad", (s * 3.6, DECK + 0.25, z), (s * 3.53, DECK + 0.25, z), 0.07, M_RUBBER, S, k=10)
            bolts("buffer_bolts", (s * 3.7, DECK + 0.36, z), (0, 1, 0), 0.08, 4, 0.012, 0.015, M_STEEL, S, phase=math.pi / 4)

    # ---- personnel access hatch: a concrete collar, the round steel plug lid, its hinge and lifting eye
    hx, hz = -2.0, -3.0
    lathe("pa_collar", (hx, DECK, hz), (0, 1, 0), [(0.0, 0.55), (0.14, 0.52), (0.16, 0.5), (0.16, 0.0)], M_CONC_D, S, k=20, smooth=30)
    lathe("pa_lid", (hx, DECK + 0.16, hz), (0, 1, 0), [(0.0, 0.43), (0.05, 0.43), (0.07, 0.4), (0.07, 0.0)], M_DSTEEL, S, k=20, smooth=30)
    lathe("pa_ring", (hx, DECK + 0.159, hz), (0, 1, 0), [(0.0, 0.5), (0.003, 0.5), (0.003, 0.445), (0.0, 0.445)], M_HAZ, S, k=20, loop=True)
    box("pa_hinge", hx - 0.18, DECK + 0.16, hz - 0.5, hx + 0.18, DECK + 0.26, hz - 0.4, M_STEEL, S, bevel=0.01)
    cyl("pa_pin", (hx - 0.22, DECK + 0.21, hz - 0.45), (hx + 0.22, DECK + 0.21, hz - 0.45), 0.03, M_GALV, S, k=8)
    lathe("pa_eye", (hx, DECK + 0.25, hz + 0.05), (1, 0, 0), [(-0.01, 0.05), (0.01, 0.05), (0.01, 0.035), (-0.01, 0.035)], M_STEEL, S, k=10, loop=True)
    lathe("pa_wheel", (hx, DECK + 0.26, hz - 0.1), (0, 1, 0), [(0.0, 0.14), (0.0, 0.12), (0.02, 0.12), (0.02, 0.14)], M_YELLOW, S, k=14, loop=True)
    cyl("pa_spindle", (hx, DECK + 0.23, hz - 0.1), (hx, DECK + 0.27, hz - 0.1), 0.025, M_STEEL, S, k=8)
    beam("pa_spoke", (hx - 0.13, DECK + 0.27, hz - 0.1), (hx + 0.13, DECK + 0.27, hz - 0.1), 0.015, 0.012, M_YELLOW, S)
    beam("pa_spoke", (hx, DECK + 0.27, hz - 0.23), (hx, DECK + 0.27, hz + 0.03), 0.015, 0.012, M_YELLOW, S)

    # ---- the electrical cabinet: twin doors facing the track, louvres, rain hood, conduits into the apron
    cx0, cx1, cz0, cz1 = -1.0, 0.35, -3.5, -2.85
    box("cab_plinth", cx0 - 0.08, DECK, cz0 - 0.08, cx1 + 0.08, DECK + 0.1, cz1 + 0.08, M_CONC_D, S, bevel=0.015)
    box("cabinet", cx0, DECK + 0.1, cz0, cx1, DECK + 1.15, cz1, M_OLIVE, S, bevel=0.015)
    box("cab_hood", cx0 - 0.05, DECK + 1.15, cz0 - 0.04, cx1 + 0.05, DECK + 1.2, cz1 + 0.09, M_OLIVE, S, bevel=0.01)
    xm = (cx0 + cx1) / 2
    for a, b in ((cx0 + 0.04, xm - 0.01), (xm + 0.01, cx1 - 0.04)):
        box("cab_door", a, DECK + 0.15, cz1, b, DECK + 1.1, cz1 + 0.015, M_OLIVE, S, bevel=0.006)
        for k in range(5):
            y = DECK + 0.25 + k * 0.05
            box("cab_louvre", a + 0.08, y, cz1 + 0.015, b - 0.08, y + 0.025, cz1 + 0.03, M_OLIVE, S, bevel=0.004)
    for x in (xm - 0.06, xm + 0.06):
        box("cab_handle", x - 0.012, DECK + 0.6, cz1 + 0.015, x + 0.012, DECK + 0.75, cz1 + 0.04, M_STEEL, S, bevel=0.004)
    for x in (cx0 + 0.04, cx1 - 0.04):
        for y in (DECK + 0.3, DECK + 0.9):
            cyl("cab_hinge", (x, y, cz1 + 0.02), (x, y + 0.08, cz1 + 0.02), 0.012, M_STEEL, S, k=6)
    box("cab_plate", xm + 0.12, DECK + 0.85, cz1 + 0.015, xm + 0.42, DECK + 1.0, cz1 + 0.02, M_WHITE, S, bevel=0.002)
    box("cab_plate_band", xm + 0.12, DECK + 0.96, cz1 + 0.02, xm + 0.42, DECK + 1.0, cz1 + 0.022, M_RED, S, bevel=0)
    for k, x in enumerate((cx0 + 0.15, cx0 + 0.3, cx0 + 0.45)):
        pipe("conduit", [(x, DECK + 0.4, cz0), (x, DECK + 0.4, cz0 - 0.15), (x, DECK - 0.05, cz0 - 0.15)], 0.03, M_GALV, S, k=8, bend=0.08)
    pipe("cab_cable", [(cx0, DECK + 0.3, cz0 + 0.2), (cx0 - 0.2, DECK + 0.3, cz0 + 0.2), (cx0 - 0.3, DECK + 0.01, cz0 + 0.2), (-1.55, DECK + 0.01, cz0 + 0.3)],
         0.02, M_RUBBER, S, bend=0.1)

    # ---- the ballistic-protected vent: a concrete housing, a steel hood on posts, baffles all round
    vx0, vx1, vz0, vz1 = 2.45, 3.35, -3.45, -2.55
    box("vent_base", vx0, DECK, vz0, vx1, DECK + 0.5, vz1, M_CONC_D, S, bevel=0.03)
    box("vent_throat", vx0 + 0.15, DECK + 0.5, vz0 + 0.15, vx1 - 0.15, DECK + 0.52, vz1 - 0.15, M_DARK, S, bevel=0)
    for px in (vx0 + 0.06, vx1 - 0.06):
        for pz in (vz0 + 0.06, vz1 - 0.06):
            box("vent_post", px - 0.04, DECK + 0.5, pz - 0.04, px + 0.04, DECK + 0.95, pz + 0.04, M_STEEL, S, bevel=0.006)
    for k in range(5):
        y = DECK + 0.56 + k * 0.075
        box("baffle", vx0 + 0.02, y, vz0 + 0.02, vx1 - 0.02, y + 0.03, vz0 + 0.05, M_STEEL, S, bevel=0.004)
        box("baffle", vx0 + 0.02, y, vz1 - 0.05, vx1 - 0.02, y + 0.03, vz1 - 0.02, M_STEEL, S, bevel=0.004)
        box("baffle", vx0 + 0.02, y, vz0 + 0.05, vx0 + 0.05, y + 0.03, vz1 - 0.05, M_STEEL, S, bevel=0.004)
        box("baffle", vx1 - 0.05, y, vz0 + 0.05, vx1 - 0.02, y + 0.03, vz1 - 0.05, M_STEEL, S, bevel=0.004)
    wedge("vent_hood", [(vx0 - 0.06, DECK + 0.95, vz0 - 0.06), (vx1 + 0.06, DECK + 0.95, vz0 - 0.06), (vx1 + 0.06, DECK + 0.95, vz1 + 0.06),
                        (vx0 - 0.06, DECK + 0.95, vz1 + 0.06),
                        (vx0 + 0.1, DECK + 1.1, vz0 + 0.1), (vx1 - 0.1, DECK + 1.1, vz0 + 0.1), (vx1 - 0.1, DECK + 1.1, vz1 - 0.1),
                        (vx0 + 0.1, DECK + 1.1, vz1 - 0.1)], M_STEEL, S, bevel=0.01)
    cyl("vent_stack", (2.9, DECK + 1.1, -3.0), (2.9, DECK + 1.35, -3.0), 0.12, M_GALV, S, k=10)
    lathe("vent_cap", (2.9, DECK + 1.38, -3.0), (0, 1, 0), [(0.0, 0.0), (0.0, 0.2), (0.06, 0.0)], M_GALV, S, k=10)
    for k in range(3):
        a = k * 2 * math.pi / 3
        beam("vent_cap_leg", (2.9 + math.cos(a) * 0.1, DECK + 1.33, -3.0 + math.sin(a) * 0.1), (2.9 + math.cos(a) * 0.15, DECK + 1.39, -3.0 + math.sin(a) * 0.15),
             0.015, 0.015, M_GALV, S)

    # ---- UHF antenna: a guyed galvanised mast on a footing, crossed dipoles, the coax down to a junction box
    ax, az = -3.05, 3.05
    box("ant_footing", ax - 0.3, DECK - 0.02, az - 0.3, ax + 0.3, DECK + 0.12, az + 0.3, M_CONC_D, S, bevel=0.02)
    box("ant_flange", ax - 0.14, DECK + 0.12, az - 0.14, ax + 0.14, DECK + 0.15, az + 0.14, M_GALV, S, bevel=0.005)
    bolts("ant_bolts", (ax, DECK + 0.15, az), (0, 1, 0), 0.15, 4, 0.016, 0.02, M_GALV, S, phase=math.pi / 4)
    cyl("ant_mast", (ax, DECK + 0.15, az), (ax, DECK + 4.5, az), 0.055, M_GALV, S, k=10)
    cyl("ant_mast2", (ax, DECK + 4.5, az), (ax, DECK + 6.2, az), 0.035, M_GALV, S, k=8)
    for i, y in enumerate((DECK + 4.3, DECK + 5.05, DECK + 5.8)):
        ang = i * math.pi / 4
        for a in (ang, ang + math.pi / 2):
            c, s_ = math.cos(a), math.sin(a)
            cyl("dipole", (ax - c * 0.55, y, az - s_ * 0.55), (ax + c * 0.55, y, az + s_ * 0.55), 0.012, M_GALV, S, k=6)
            box("dipole_hub", ax - 0.05, y - 0.04, az - 0.05, ax + 0.05, y + 0.04, az + 0.05, M_WHITE, S, bevel=0.008)
    cyl("lightning_rod", (ax, DECK + 6.2, az), (ax, DECK + 6.55, az), 0.008, M_GALV, S, k=6)
    pipe("coax", [(ax + 0.06, DECK + 5.7, az), (ax + 0.065, DECK + 0.5, az), (ax + 0.2, DECK + 0.3, az - 0.25)], 0.015, M_RUBBER, S, bend=0.1)
    box("ant_box", ax + 0.1, DECK + 0.12, az - 0.45, ax + 0.4, DECK + 0.5, az - 0.25, M_WHITE, S, bevel=0.01)
    # guy wires to anchors at the apron corners
    for gx, gz in ((-3.65, 1.4), (-1.4, 3.65), (-3.65, 3.65)):
        box("guy_anchor", gx - 0.06, DECK, gz - 0.06, gx + 0.06, DECK + 0.05, gz + 0.06, M_STEEL, S, bevel=0.006)
        pipe("guy", catenary((gx, DECK + 0.05, gz), (ax, DECK + 3.9, az), 0.04, n=6), 0.006, M_GALV, S, k=4)

    # ---- warning beacons on posts (the game lights the lenses: 0.32 square, DECK + 1.19 .. 1.47)
    for bx, bz in BEACONS:
        box("beacon_base", bx - 0.12, DECK, bz - 0.12, bx + 0.12, DECK + 0.03, bz + 0.12, M_STEEL, S, bevel=0.006)
        cyl("beacon_post", (bx, DECK + 0.03, bz), (bx, DECK + 1.2, bz), 0.04, M_GALV, S, k=8)
        lathe("beacon_seat", (bx, DECK + 1.17, bz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.13), (0.05, 0.13), (0.05, 0.0)], M_STEEL, S, k=12)
        lathe("beacon_lens", (bx, DECK + 1.22, bz), (0, 1, 0), [(0.0, 0.115), (0.13, 0.115), (0.2, 0.08), (0.22, 0.0)], M_RED_LENS, S, k=12)
        for k in range(4):
            a = k * math.pi / 2 + math.pi / 4
            cyl("beacon_guard", (bx + math.cos(a) * 0.125, DECK + 1.22, bz + math.sin(a) * 0.125),
                (bx + math.cos(a) * 0.09, DECK + 1.44, bz + math.sin(a) * 0.09), 0.008, M_STEEL, S, k=4)
        pipe("beacon_cable", [(bx + 0.05, DECK + 0.9, bz), (bx + 0.05, DECK + 0.02, bz), (bx + 0.3, DECK + 0.01, bz)], 0.012, M_RUBBER, S, k=6, bend=0.05)

    # ---- intrusion sensors on short posts
    for px, pz, a in ((3.45, 2.35, -2.4), (-3.45, -2.2, 0.75), (1.0, 3.5, -1.6)):
        cyl("sensor_post", (px, DECK, pz), (px, DECK + 0.85, pz), 0.035, M_GALV, S, k=8)
        ybox("sensor", px, pz, a, -0.08, DECK + 0.8, -0.06, 0.08, DECK + 1.0, 0.06, M_WHITE, S, bevel=0.012)
        ybox("sensor_face", px, pz, a, 0.08, DECK + 0.83, -0.045, 0.09, DECK + 0.97, 0.045, M_DARK, S, bevel=0.004)

    # ---- drains, tie-down rings and a survey mark on the apron
    for gx, gz in ((-3.35, -0.0), (3.35, 0.0)):
        box("drain", gx - 0.14, DECK - 0.002, gz - 0.25, gx + 0.14, DECK + 0.006, gz + 0.25, M_GRATE, S, bevel=0)
    for gx, gz in ((-1.2, 2.45), (1.2, 2.45), (-0.9, -2.45), (1.6, -2.45)):
        lathe("tiedown", (gx, DECK + 0.025, gz), (1, 0, 0), [(-0.008, 0.035), (0.008, 0.035), (0.008, 0.022), (-0.008, 0.022)], M_STEEL, S, k=8, loop=True)
        box("tiedown_plate", gx - 0.04, DECK, gz - 0.03, gx + 0.04, DECK + 0.006, gz + 0.03, M_STEEL, S, bevel=0)

    # ---- the security fence: galvanised posts, outriggers with barbed wire, chain-link, a double gate on -z
    FH = 2.1
    G0 = GROUND - 0.05
    post_step = 1.6
    gate = 1.6

    def chain(ax_, az_, bx_, bz_, y0, y1, step=0.24):
        length = math.hypot(bx_ - ax_, bz_ - az_)
        dx, dz = (bx_ - ax_) / length, (bz_ - az_) / length
        h = y1 - y0
        for k in range(-int(h / step) - 1, int(length / step) + 2):
            for sgn in (1, -1):
                a0, a1 = k * step, k * step + sgn * h
                lo, hi = 0.0, length
                tlo, thi = (lo - a0) / (a1 - a0), (hi - a0) / (a1 - a0)
                t0, t1 = max(0.0, min(tlo, thi)), min(1.0, max(tlo, thi))
                if t1 - t0 < 1e-3:
                    continue
                sa, sb = a0 + (a1 - a0) * t0, a0 + (a1 - a0) * t1
                cyl("mesh", (ax_ + dx * sa, y0 + h * t0, az_ + dz * sa), (ax_ + dx * sb, y0 + h * t1, az_ + dz * sb), 0.004, M_GALV, S, k=3, caps=False)

    runs = [((-FENCE, FENCE), (FENCE, FENCE), (0, 1)), ((-FENCE, -FENCE), (-FENCE, FENCE), (-1, 0)), ((FENCE, -FENCE), (FENCE, FENCE), (1, 0)),
            ((-FENCE, -FENCE), (-gate, -FENCE), (0, -1)), ((gate, -FENCE), (FENCE, -FENCE), (0, -1))]
    for (ax_, az_), (bx_, bz_), (ox, oz) in runs:
        length = math.hypot(bx_ - ax_, bz_ - az_)
        n = max(1, round(length / post_step))
        for k in range(n + 1):
            t = k / n
            px, pz = ax_ + (bx_ - ax_) * t, az_ + (bz_ - az_) * t
            corner = abs(abs(px) - FENCE) < 0.01 and abs(abs(pz) - FENCE) < 0.01 or abs(abs(px) - gate) < 0.01 and pz < 0
            r = 0.045 if corner else 0.032
            cyl("fence_post", (px, G0, pz), (px, GROUND + FH, pz), r, M_GALV, S, k=8)
            lathe("post_cap", (px, GROUND + FH, pz), (0, 1, 0), [(0.0, 0.0), (0.0, r + 0.008), (0.03, r + 0.008), (0.045, 0.0)], M_GALV, S, k=8)
            lathe("footing", (px, GROUND - 0.06, pz), (0, 1, 0), [(0.0, 0.0), (0.0, 0.13), (0.08, 0.12), (0.1, 0.0)], M_CONC_D, S, k=8)
            cyl("outrigger", (px, GROUND + FH - 0.05, pz), (px + ox * 0.32, GROUND + FH + 0.3, pz + oz * 0.32), 0.016, M_GALV, S, k=6)
        cyl("top_rail", (ax_, GROUND + FH - 0.08, az_), (bx_, GROUND + FH - 0.08, bz_), 0.022, M_GALV, S, k=8)
        cyl("tension", (ax_, GROUND + 0.08, az_), (bx_, GROUND + 0.08, bz_), 0.006, M_GALV, S, k=4)
        for k in range(3):
            f = (k + 1) / 3.3
            pipe("barbed", catenary((ax_ + ox * 0.32 * f, GROUND + FH - 0.05 + 0.35 * f, az_ + oz * 0.32 * f),
                                    (bx_ + ox * 0.32 * f, GROUND + FH - 0.05 + 0.35 * f, bz_ + oz * 0.32 * f), 0.03, n=6), 0.005, M_GALV, S, k=4)
        chain(ax_, az_, bx_, bz_, GROUND + 0.04, GROUND + FH - 0.08)
    # razor wire coil along the top of the -x and +z runs (the far side from the gate), a few loops
    # the gate: two framed leaves with a diagonal brace, a drop rod and a padlocked chain
    for s in (-1, 1):
        x_h, x_m = s * (gate - 0.06), s * 0.03
        y0, y1 = GROUND + 0.1, GROUND + FH - 0.1
        for a, b in (((x_h, y0), (x_m, y0)), ((x_h, y1), (x_m, y1)), ((x_h, y0), (x_h, y1)), ((x_m, y0), (x_m, y1)), ((x_h, y0), (x_m, y1))):
            cyl("gate_frame", (a[0], a[1], -FENCE), (b[0], b[1], -FENCE), 0.022, M_GALV, S, k=6)
        chain(x_h, -FENCE, x_m, -FENCE, y0, y1)
        for y in (y0 + 0.3, y1 - 0.3):
            cyl("gate_hinge", (s * (gate - 0.03), y - 0.05, -FENCE), (s * (gate - 0.03), y + 0.05, -FENCE), 0.03, M_STEEL, S, k=6)
    cyl("gate_rod", (0.06, GROUND - 0.05, -FENCE - 0.04), (0.06, GROUND + 1.2, -FENCE - 0.04), 0.012, M_GALV, S, k=6)
    pipe("gate_chain", [(-0.05, GROUND + 1.1, -FENCE - 0.03), (0.0, GROUND + 1.03, -FENCE - 0.05), (0.05, GROUND + 1.1, -FENCE - 0.03)], 0.01, M_STEEL, S, k=4)
    box("padlock", -0.025, GROUND + 0.96, -FENCE - 0.07, 0.025, GROUND + 1.02, -FENCE - 0.05, M_YELLOW, S, bevel=0.005)
    # signs on the fence
    for x, z, rot in ((-0.0, FENCE + 0.03, 0), (-FENCE - 0.03, 1.6, 1), (FENCE + 0.03, -1.6, 1), (-2.4, -FENCE - 0.03, 0)):
        if rot:
            box("sign", x - 0.008, GROUND + 1.0, z - 0.3, x + 0.008, GROUND + 1.42, z + 0.3, M_WHITE, S, bevel=0.003)
            box("sign_band", x - 0.012, GROUND + 1.3, z - 0.3, x + 0.012, GROUND + 1.42, z + 0.3, M_RED, S, bevel=0)
        else:
            box("sign", x - 0.3, GROUND + 1.0, z - 0.008, x + 0.3, GROUND + 1.42, z + 0.008, M_WHITE, S, bevel=0.003)
            box("sign_band", x - 0.3, GROUND + 1.3, z - 0.012, x + 0.3, GROUND + 1.42, z + 0.012, M_RED, S, bevel=0)


# ================================================================= the launcher closure (two halves)
def build_door(side):
    P = "door_r" if side > 0 else "door_l"
    x0, x1 = sx(side, 0.0, DOOR_X)
    # the armoured slab: a reinforced concrete core in a steel case, its top dressed with plate
    xa, xb = sx(side, 0.03, DOOR_X - 0.05)
    NOSE = 1.3  # where the outer end starts to slope down
    xa, xn = sx(side, 0.03, NOSE)
    box("core", xa, DECK + 0.05, -1.56, xn, DECK + 0.42, 1.56, M_DOOR, P, bevel=0.012)

    def slope(name, za, zb, y0, top0, top1, x_end, mat):
        """The sloped nose of the door from x = NOSE (top0) out to x_end (top1)."""
        a, b = side * NOSE, side * x_end
        wedge(name, [(a, y0, za), (b, y0, za), (b, y0, zb), (a, y0, zb), (a, top0, za), (b, top1, za), (b, top1, zb), (a, top0, zb)], mat, P, bevel=0.01)
    slope("nose", -1.56, 1.56, DECK + 0.05, DECK + 0.42, DECK + 0.3, DOOR_X - 0.05, M_DSTEEL)
    # side girders over the wheels
    for z in (-1, 1):
        za, zb = sorted((z * 1.56, z * DOOR_Z))
        ga, gb = sx(side, 0.0, NOSE)
        box("girder", ga, DECK + 0.17, za, gb, DECK + 0.44, zb, M_DSTEEL, P, bevel=0.012)
        slope("girder_nose", za, zb, DECK + 0.17, DECK + 0.44, DECK + 0.32, DOOR_X, M_DSTEEL)
        zo = z * DOOR_Z
        # stiffeners down the outer face, a stencil plate
        for k in range(5):
            xs = side * (0.1 + k * 0.28)
            box("stiffener", xs - 0.025, DECK + 0.19, min(zo, zo + z * 0.03), xs + 0.025, DECK + 0.42, max(zo, zo + z * 0.03), M_DSTEEL, P, bevel=0.005)
        # wheels on the rail under the girder, with their axle boxes
        for xw in (0.3, 1.3):
            xc = side * xw
            for zz in (RAIL_Z - 0.105, RAIL_Z + 0.105):
                box("axlebox", xc - 0.07, DECK + 0.11, z * zz - 0.03, xc + 0.07, DECK + 0.19, z * zz + 0.03, M_DSTEEL, P, bevel=0.006)
            lathe("wheel", (xc, RAIL_TOP + WHEEL_R, z * RAIL_Z - 0.04), (0, 0, 1),
                  [(0.0, 0.0), (0.0, WHEEL_R + 0.012), (0.012, WHEEL_R + 0.012), (0.016, WHEEL_R), (0.08, WHEEL_R), (0.08, 0.0)], M_RAIL, P, k=10)
            cyl("axle", (xc, RAIL_TOP + WHEEL_R, z * RAIL_Z - 0.14), (xc, RAIL_TOP + WHEEL_R, z * RAIL_Z + 0.14), 0.022, M_STEEL, P, k=6)
        # bolt rows along the girder top
        for k in range(6):
            xs = side * (0.1 + k * 0.22)
            bolts("girder_bolt", (xs, DECK + 0.44, z * 1.73), (0, 1, 0), 0.0, 1, 0.016, 0.012, M_STEEL, P)
    # the meeting edge: a steel face with a rubber seal, the hazard-painted lip on top
    ma, mb = sx(side, 0.0, 0.035)
    box("meet_plate", ma, DECK + 0.04, -DOOR_Z, mb, DECK + 0.44, DOOR_Z, M_DSTEEL, P, bevel=0.006)
    ha, hb = sx(side, 0.0, 0.26)
    box("meet_lip", ha, DECK + 0.42, -1.56, hb, DECK + 0.445, 1.56, M_HAZ, P, bevel=0.004)
    # the outer end: a heavy end plate, rubber buffers where the stops are
    ea, eb = sx(side, DOOR_X - 0.06, DOOR_X)
    box("end_plate", ea, DECK + 0.04, -DOOR_Z, eb, DECK + 0.32, DOOR_Z, M_DSTEEL, P, bevel=0.01)
    for z in (-RAIL_Z, RAIL_Z):
        cyl("door_buffer", (side * DOOR_X, DECK + 0.25, z), (side * (DOOR_X + 0.04), DECK + 0.25, z), 0.065, M_RUBBER, P, k=10)
    # top: weld seams between the cover plates, lifting points, a stencil
    for k in range(1, 4):
        xs = side * (0.26 + k * 0.33)
        box("seam", xs - 0.008, DECK + 0.42, -1.56, xs + 0.008, DECK + 0.428, 1.56, M_DSTEEL, P, bevel=0)
    sa, sb = sx(side, 0.26, DOOR_X - 0.05)
    box("seam", sa, DECK + 0.42, -0.008, sb, DECK + 0.428, 0.008, M_DSTEEL, P, bevel=0)
    for z in (-0.95, 0.95):
        xc = side * 0.95
        box("lift_pocket", xc - 0.1, DECK + 0.42, z - 0.1, xc + 0.1, DECK + 0.423, z + 0.1, M_DARK, P, bevel=0)
        lathe("lift_eye", (xc, DECK + 0.45, z), (0, 0, 1), [(-0.012, 0.06), (0.012, 0.06), (0.012, 0.04), (-0.012, 0.04)], M_STEEL, P, k=10, loop=True)
    sa, sb = sx(side, 0.45, 1.25)
    box("stencil", sa, DECK + 0.42, 0.25, sb, DECK + 0.4215, 0.33, M_YELLOW, P, bevel=0)


# ================================================================= the launch tube
def build_tube():
    T = "tube"
    W = 1.5
    R = HOLE_R
    # the cap that hides the corners of the dug shaft round the bore
    outer, inner = square_circle(W, R, 32)
    frame_plate("tube_cap", outer, inner, M_CONC_D, T, GROUND - 0.25, GROUND - 0.12)
    inner_tube("liner", R, FLOOR, GROUND - 0.12, 24, M_LINER, T, rows=5)
    # stiffening rings every two metres
    y = FLOOR + 1.5
    while y < GROUND - 0.6:
        lathe("ring", (0, y, 0), (0, 1, 0), [(0.0, R), (0.0, R - 0.07), (0.1, R - 0.07), (0.1, R)], M_LINER, T, k=24, loop=True, smooth=30)
        y += 2.0
    # cable trays down two sides, a ladder down a third
    for a in (math.radians(225), math.radians(315)):
        c, s = math.cos(a), math.sin(a)
        p0 = (c * (R - 0.02), FLOOR, s * (R - 0.02))
        p1 = (c * (R - 0.02), GROUND - 0.15, s * (R - 0.02))
        section("tray", p0, p1, [(-0.13, 0.0), (0.13, 0.0), (0.13, 0.08), (-0.13, 0.08)], M_STEEL, T, roll=0.0)
        for k in range(3):
            off = (k - 1) * 0.07
            cyl("tray_cable", (c * (R - 0.08) - s * off, FLOOR, s * (R - 0.08) + c * off), (c * (R - 0.08) - s * off, GROUND - 0.15, s * (R - 0.08) + c * off),
                0.025, M_RUBBER, T, k=6, caps=False)
    a = math.radians(120)
    ladder("ladder", math.cos(a) * (R - 0.22), math.sin(a) * (R - 0.22), FLOOR, GROUND - 1.1, (-math.cos(a), -math.sin(a)), M_YELLOW, T, width=0.4, cage=False)
    for yb in range(int(FLOOR) + 2, int(GROUND), 3):
        for s in (-1, 1):
            p = (math.cos(a) * (R - 0.22) - math.sin(a) * s * 0.2, yb, math.sin(a) * (R - 0.22) + math.cos(a) * s * 0.2)
            q = (math.cos(a) * R - math.sin(a) * s * 0.2, yb, math.sin(a) * R + math.cos(a) * s * 0.2)
            beam("ladder_bracket", p, q, 0.03, 0.03, M_YELLOW, T)
    # work platforms folded up against the wall, at three levels
    for yp in (FLOOR + 6.0, FLOOR + 12.0, FLOOR + 18.0):
        for a0 in (math.radians(0), math.radians(180)):
            c, s = math.cos(a0), math.sin(a0)
            ybox("plat", 0.0, 0.0, -a0, R - 0.2, yp, -0.7, R - 0.14, yp + 0.95, 0.7, M_GRATE, T, bevel=0.005)
            ybox("plat_edge", 0.0, 0.0, -a0, R - 0.22, yp + 0.9, -0.72, R - 0.12, yp + 0.98, 0.72, M_YELLOW, T, bevel=0.006)
            ybox("plat_hinge", 0.0, 0.0, -a0, R - 0.2, yp - 0.04, -0.72, R - 0.02, yp + 0.04, 0.72, M_STEEL, T, bevel=0.006)
    # lamp housings (the game's lamps: x = -1.36 .. -1.33 at FLOOR + 2.5 + 4k, x = +1.33 .. 1.36 two metres higher)
    y = FLOOR + 2.5
    while y < GROUND - 1.0:
        for xs, yy in ((-1, y), (1, y + 2.0)):
            box("lamp_housing", min(xs * 1.47, xs * 1.36), yy, -0.15, max(xs * 1.47, xs * 1.36), yy + 0.3, 0.15, M_STEEL, T, bevel=0.01)
            for zz in (-0.13, 0.13):
                box("lamp_guard", min(xs * 1.36, xs * 1.32), yy + 0.02, zz - 0.006, max(xs * 1.36, xs * 1.32), yy + 0.28, zz + 0.006, M_STEEL, T, bevel=0)
        y += 4.0
    # the umbilical from its wall box to the missile's equipment section
    box("umb_box", 1.2, FLOOR + 6.8, 0.2, 1.42, FLOOR + 8.0, 0.6, M_OLIVE, T, bevel=0.015)
    pipe("umbilical", [(1.2, FLOOR + 7.5, 0.4), (0.95, FLOOR + 7.45, 0.38), (0.62, FLOOR + 7.2, 0.3)], 0.06, M_RUBBER, T, k=8, bend=0.1)
    # the floor: soot over concrete, the launch platform on its shock isolators
    lathe("floor", (0, FLOOR, 0), (0, 1, 0), [(0.0, 0.0), (0.0, R), (0.006, R), (0.006, 0.0)], M_SOOT, T, k=24)
    lathe("platform", (0, FLOOR + 0.16, 0), (0, 1, 0), [(0.0, 0.0), (0.0, 1.1), (0.12, 1.1), (0.14, 1.07), (0.14, 0.0)], M_STEEL, T, k=24, smooth=30)
    lathe("platform_ring", (0, PLATFORM - 0.001, 0), (0, 1, 0), [(0.0, 1.07), (0.004, 1.07), (0.004, 0.8), (0.0, 0.8)], M_HAZ, T, k=24, loop=True)
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        c, s = math.cos(a), math.sin(a)
        cyl("isolator", (c * 0.85, FLOOR, s * 0.85), (c * 0.85, FLOOR + 0.16, s * 0.85), 0.11, M_RUBBER, T, k=8)
        cyl("strut", (c * (R - 0.03), FLOOR + 2.4, s * (R - 0.03)), (c * 1.02, PLATFORM - 0.05, s * 1.02), 0.05, M_RED, T, k=8)
        box("strut_lug", c * 1.02 - 0.06, PLATFORM - 0.1, s * 1.02 - 0.06, c * 1.02 + 0.06, PLATFORM - 0.01, s * 1.02 + 0.06, M_STEEL, T, bevel=0.005)
        lathe("strut_mount", (c * R, FLOOR + 2.4, s * R), (-c, 0, -s), [(0.0, 0.1), (0.06, 0.1), (0.06, 0.0)], M_STEEL, T, k=8)


if BUILD_SITE:
    build_site()
    build_door(-1)
    build_door(1)
    for p in ("site", "door_l", "door_r"):
        join_part(p)
if BUILD_TUBE:
    build_tube()
    join_part("tube")

count_quads([p for p in ("site", "door_l", "door_r", "tube") if p in PARTS])

if SHOTS:
    outdoor(sun_elevation=34, sun_rotation=140)
    scene.view_settings.exposure = -1.3
    ground_plane(GROUND - 0.004, hole=1.5)
    which = os.environ.get("SHOTS", "closed,open,detail,down")
    TAG = os.environ.get("TAG", "")
    if "closed" in which:
        shoot("silo_closed" + TAG, (10.5, 7.5, 9.5), (0.0, 1.6, 0.0), lens=30)
    if "detail" in which:
        shoot("silo_detail" + TAG, (3.4, 2.9, 4.3), (0.8, 1.6, 0.0), lens=28)
    if "open" in which or "down" in which:
        hide_part("door_l")
        hide_part("door_r")
        pose_copy("door_l", block_matrix((-DOOR_TRAVEL, 0, 0)))
        pose_copy("door_r", block_matrix((DOOR_TRAVEL, 0, 0)))
        if "open" in which:
            shoot("silo_open" + TAG, (-9.0, 8.0, -10.0), (0.0, 1.4, 0.0), lens=30)
        if "down" in which:
            for k, yl in enumerate((-2.0, -8.0, -14.0, -19.5)):
                lamp = bpy.data.lights.new(f"tube_lamp{k}", "POINT")
                lamp.energy = 400
                lamp.color = (1.0, 0.9, 0.75)
                lo = bpy.data.objects.new(f"tube_lamp{k}", lamp)
                scene.collection.objects.link(lo)
                lo.location = mc(0.0, yl, 0.0)
            shoot("silo_down" + TAG, (0.6, 9.0, 3.2), (0.0, -6.0, 0.0), lens=28)
if "export" in ARGS:
    if TUBE:
        export("silo_tube", ["tube"], tex=2048, ao=0.5)
    else:
        export("silo", ["site", "door_l", "door_r"], tex=2048, ao=0.5)
