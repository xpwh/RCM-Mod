"""Helpers shared by the silo, round-hatch silo, launch tube and submarine scripts (on top of structkit):
lofts through rings of points, square frames, flipped (inward looking) shells, and posing of the moving parts
for the preview renders.

    from silokit import *
"""
import math

import bmesh
import bpy
from mathutils import Matrix, Vector

from structkit import PARTS, mc, mcv, new_obj, node_mat, scene, _coords, _finish, _math, _mix, _noise, weathered  # noqa: F401


def orient(bm, outward):
    """Turn every face of bm to face along outward(centre) (Blender-space vectors)."""
    for f in bm.faces:
        f.normal_update()
        if f.normal.dot(outward(f.calc_center_median())) < 0:
            f.normal_flip()


def inner_tube(name, r, y0, y1, k, mat, part, cx=0.0, cz=0.0, rows=1, smooth=50.0):
    """A cylinder wall seen from inside (faces looking at the axis), vertical, from y0 to y1."""
    bm = bmesh.new()
    rings = []
    for j in range(rows + 1):
        y = y0 + (y1 - y0) * j / rows
        rings.append([bm.verts.new(mc(cx + math.cos(2 * math.pi * i / k) * r, y, cz + math.sin(2 * math.pi * i / k) * r)) for i in range(k)])
    for r0, r1 in zip(rings, rings[1:]):
        for i in range(k):
            j = (i + 1) % k
            bm.faces.new((r0[i], r0[j], r1[j], r1[i]))
    c = mc(cx, 0, cz)
    orient(bm, lambda p: Vector((c.x - p.x, c.y - p.y, 0.0)))
    return new_obj(name, bm, mat, part, smooth=smooth)


def loft(name, rings, mat, part, closed=True, cap0=False, cap1=False, bevel=0.0, smooth=40.0, flip=False, outward=None):
    """A surface through rings of points (block space, all the same count), ring to ring; closed joins each ring's
    last point back to its first. cap0/cap1 close the ends with a fan round their centre."""
    bm = bmesh.new()
    vs = [[bm.verts.new(mcv(p)) for p in r] for r in rings]
    n = len(rings[0])
    m = n if closed else n - 1
    for r0, r1 in zip(vs, vs[1:]):
        for i in range(m):
            j = (i + 1) % n
            bm.faces.new((r0[i], r0[j], r1[j], r1[i]))
    for cap, r in ((cap0, vs[0]), (cap1, vs[-1])):
        if cap:
            c = bm.verts.new(sum((v.co for v in r), Vector()) / len(r))
            for i in range(n):
                bm.faces.new((c, r[i], r[(i + 1) % n]))
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    if flip:
        bmesh.ops.reverse_faces(bm, faces=bm.faces)
    if outward is not None:
        orient(bm, outward)
    return new_obj(name, bm, mat, part, bevel=bevel, smooth=smooth)


def square(h, y, cx=0.0, cz=0.0, round_r=0.0, k=3):
    """The corners of a square of half-size h at height y (counter-clockwise from -x -z), the corners rounded off
    with radius round_r in k steps."""
    pts = []
    for qx, qz, a0 in ((-1, -1, math.pi), (1, -1, 1.5 * math.pi), (1, 1, 0.0), (-1, 1, 0.5 * math.pi)):
        if round_r <= 0:
            pts.append((cx + qx * h, y, cz + qz * h))
            continue
        ox, oz = cx + qx * (h - round_r), cz + qz * (h - round_r)
        for i in range(k + 1):
            a = a0 + 0.5 * math.pi * i / k
            pts.append((ox + math.cos(a) * round_r, y, oz + math.sin(a) * round_r))
    return pts


def circle(r, y, k, cx=0.0, cz=0.0, phase=0.0):
    return [(cx + math.cos(phase + 2 * math.pi * i / k) * r, y, cz + math.sin(phase + 2 * math.pi * i / k) * r) for i in range(k)]


def frame_plate(name, outer, inner, mat, part, y0, y1, bevel=0.0):
    """A flat plate between an outer and an inner outline (same point count, block space x/z pairs) from y0 to
    y1: a frame, a ring, a square plate with a round hole."""
    bm = bmesh.new()
    n = len(outer)
    ot = [bm.verts.new(mc(x, y1, z)) for x, z in outer]
    it = [bm.verts.new(mc(x, y1, z)) for x, z in inner]
    ob = [bm.verts.new(mc(x, y0, z)) for x, z in outer]
    ib = [bm.verts.new(mc(x, y0, z)) for x, z in inner]
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((ot[i], ot[j], it[j], it[i]))
        bm.faces.new((ob[i], ib[i], ib[j], ob[j]))
        bm.faces.new((ob[i], ob[j], ot[j], ot[i]))
        bm.faces.new((ib[i], it[i], it[j], ib[j]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def square_circle(h, r, k):
    """Outline pairs for a square of half-size h with a round hole of radius r, k points each (k divisible by 4):
    each hole point paired with the point of the square in the same direction."""
    outer, inner = [], []
    for i in range(k):
        a = 2 * math.pi * i / k + math.pi / 4
        c, s = math.cos(a), math.sin(a)
        f = h / max(abs(c), abs(s))
        outer.append((c * f, s * f))
        inner.append((c * r, s * r))
    return outer, inner


def wedge(name, pts, mat, part, bevel=0.0):
    """Any hexahedron from its eight corners in block space (0-3 one end, 4-7 the other, same order)."""
    from structkit import _cuboid
    bm = bmesh.new()
    _cuboid(bm, [mcv(p) for p in pts])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj(name, bm, mat, part, bevel=bevel)


def ybox(name, cx, cz, angle, x0, y0, z0, x1, y1, z1, mat, part, bevel=0.01):
    """A box given in a frame turned `angle` about the vertical axis through (cx, cz) the way the game's
    Quaternionf.rotationY turns it: (x, z) -> (x cos a + z sin a, -x sin a + z cos a)."""
    c, s = math.cos(angle), math.sin(angle)
    pts = []
    for y in (y0, y1):
        for x, z in ((x0, z0), (x1, z0), (x1, z1), (x0, z1)):
            pts.append((cx + x * c + z * s, y, cz - x * s + z * c))
    return wedge(name, pts, mat, part, bevel=bevel)


def rot_y(p, angle):
    """A block-space point turned like the game's rotationY."""
    c, s = math.cos(angle), math.sin(angle)
    return (p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c)


# ------------------------------------------------------------------ preview posing


def block_matrix(translate=(0, 0, 0), rot=None):
    """A Blender matrix for a block-space pose: translate, then rotate (a block-space axis and angle, the game's
    right-handed sense)."""
    m = Matrix.Translation(mcv(translate))
    if rot is not None:
        axis, ang = rot
        m = m @ Matrix.Rotation(ang, 4, mcv(axis).normalized())
    return m


def pose_copy(part, matrix, pivot=(0, 0, 0)):
    """A linked copy of a (joined) part for the preview, moved as the game moves it: the part's points relative
    to its pivot, then `matrix` (Blender space). Returns the new object."""
    src = PARTS[part][0]
    ob = bpy.data.objects.new(part + "_pose", src.data)
    scene.collection.objects.link(ob)
    ob.matrix_world = matrix @ Matrix.Translation(-mcv(pivot)) @ src.matrix_world
    return ob


def hide_part(part):
    for ob in PARTS.get(part, []):
        ob.hide_render = True


def show_part(part):
    for ob in PARTS.get(part, []):
        ob.hide_render = False


def ground_plane(y, hole=None, size=60.0, color=(0.12, 0.2, 0.07)):
    """Replace the studio's ground with one at height y (block space), with a square hole (half-size) if asked."""
    g = bpy.data.objects.get("ground")
    mat = g.data.materials[0] if g else None
    if g:
        bpy.data.objects.remove(g)
    bm = bmesh.new()
    if hole:
        h = hole
        quads = [(-size, -size, size, -h), (-size, h, size, size), (-size, -h, -h, h), (h, -h, size, h)]
    else:
        quads = [(-size, -size, size, size)]
    for x0, z0, x1, z1 in quads:
        vs = [bm.verts.new(mc(x, y, z)) for x, z in ((x0, z0), (x1, z0), (x1, z1), (x0, z1))]
        bm.faces.new(vs)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for f in bm.faces:
        if f.normal.z < 0:
            f.normal_flip()
    me = bpy.data.meshes.new("ground")
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new("ground", me)
    scene.collection.objects.link(ob)
    if mat is None:
        mat, nt, bsdf = node_mat("ground2")
        bsdf.inputs["Base Color"].default_value = (*color, 1)
    me.materials.append(mat)
    return ob


def count_quads(parts):
    """Rough quad count of the parts as the export will write them (n-gons fan into triangles, each a quad)."""
    dg = bpy.context.evaluated_depsgraph_get()
    total = 0
    for part in parts:
        n = 0
        for ob in PARTS.get(part, []):
            me = ob.evaluated_get(dg).to_mesh()
            for p in me.polygons:
                n += 1 if len(p.vertices) in (3, 4) else len(p.vertices) - 2
            ob.evaluated_get(dg).to_mesh_clear()
        print("part", part, "~quads", n, flush=True)
        total += n
    print("total ~quads", total, flush=True)
    return total
