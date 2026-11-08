# Run inside a model script (python ak47.py --export=<dir>, glock.py the same): bakes the look of the
# materials into one texture and writes the parts as quad meshes in the mod's item space.
#
# The AK's item space (as the old hand-built model): x right, y up, z back, origin at the hand on the grip,
# bore at y = 0.085, muzzle at z = -0.712, the rifle at 1.325 times its real size. Another model sets
# EXPORT_NAME, ORDER, to_item and nrm_item before it runs this (and leaves out SELECTOR_PIVOT).
import gzip
import struct

EXPORT_DIR = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--export=")][0]
TEX = 2048
S_ITEM = 1.325
BORE_Y = 0.085
MUZZLE_Z = -0.712
MUZZLE_YB = 0.6305


if "EXPORT_NAME" not in globals():
    EXPORT_NAME = "ak47"
    ORDER = ["body", "carrier", "selector", "magazine", "rounds", "tips", "band"]

    def to_item(v):
        return Vector((S_ITEM * v.x, BORE_Y + S_ITEM * v.z, MUZZLE_Z + S_ITEM * (MUZZLE_YB - v.y)))

    def nrm_item(n):
        return Vector((n.x, n.z, -n.y)).normalized()
# parts moved off to the side (x, metres) while baking: they would shade what they overlap
BAKE_APART = globals().get("BAKE_APART", {"band": 1.0})

# lighter meshes for the game: no subdivision, at most two bevel segments
for part in ORDER:
    for ob in PARTS.get(part, []):
        for md in ob.modifiers:
            if md.type == "SUBSURF":
                md.levels = md.render_levels = 0
            if md.type == "BEVEL":
                md.segments = min(md.segments, 2)

# one object for all, its faces tagged with their part
dg = bpy.context.evaluated_depsgraph_get()
bm_all = bmesh.new()
part_layer = bm_all.faces.layers.int.new("part")
mats = []
for pi, part in enumerate(ORDER):
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
        if part in BAKE_APART:
            for v in {v for f in bm_all.faces[n0:] for v in f.verts}:
                v.co.x += BAKE_APART[part]
        bpy.data.meshes.remove(tmp)
        bpy.data.meshes.remove(me)
        ob.hide_render = True
        ob.hide_viewport = True
me_all = bpy.data.meshes.new(EXPORT_NAME + "_game")
bm_all.to_mesh(me_all)
bm_all.free()
for m in mats:
    me_all.materials.append(m)
for p in me_all.polygons:
    p.use_smooth = True
me_all.set_sharp_from_angle(angle=math.radians(35))
game = bpy.data.objects.new(EXPORT_NAME + "_game", me_all)
scene.collection.objects.link(game)
print("faces", len(me_all.polygons), "verts", len(me_all.vertices), flush=True)

# unwrap everything into one atlas
bpy.context.view_layer.objects.active = game
for o in scene.objects:
    o.select_set(False)
game.select_set(True)
bpy.ops.object.mode_set(mode="EDIT")
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.0015, area_weight=0.0, scale_to_bounds=True)
bpy.ops.uv.pack_islands(margin=0.0015, rotate=True)
bpy.ops.object.mode_set(mode="OBJECT")

# bake two maps for the game's gun shader (gun.fsh):
#   <name>.png      base colour (sRGB)
#   <name>_mat.png  r metallic, g roughness, b ambient occlusion, a clear coat (linear)
import numpy as np  # noqa: E402

for m in mats:
    nt = m.node_tree
    tn = nt.nodes.new("ShaderNodeTexImage")
    tn.name = "bake_target"
    nt.nodes.active = tn
scene.render.engine = "CYCLES"
scene.render.bake.margin = 8


def emit_bake(name, feed, samples):
    """Bake what feed(material, node_tree) returns (a colour socket) through an emission shader."""
    im = bpy.data.images.new(name, TEX, TEX, alpha=False, float_buffer=True)
    for m in mats:
        nt = m.node_tree
        out = [n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"][0]
        em = nt.nodes.new("ShaderNodeEmission")
        nt.links.new(feed(m, nt), em.inputs["Color"])
        nt.links.new(em.outputs[0], out.inputs["Surface"])
        nt.nodes["bake_target"].image = im
    scene.cycles.samples = samples
    bpy.ops.object.bake(type="EMIT", use_clear=True)
    return np.array(im.pixels[:], dtype=np.float32).reshape(TEX, TEX, 4)[::-1, :, :3]


def socket_or_value(nt, v):
    if isinstance(v, (int, float)):
        n = nt.nodes.new("ShaderNodeValue")
        n.outputs[0].default_value = v
        return n.outputs[0]
    return v


def feed_albedo(m, nt):
    src, gain = DISPLAY[m.name]
    rgb = nt.nodes.new("ShaderNodeRGB")
    mix = nt.nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    mix.blend_type = "MULTIPLY"
    mix.inputs["Factor"].default_value = 1.0
    if isinstance(src, tuple):
        mix.inputs["A"].default_value = (*src, 1)
    else:
        nt.links.new(src, mix.inputs["A"])
    rgb.outputs[0].default_value = (gain, gain, gain, 1)
    nt.links.new(rgb.outputs[0], mix.inputs["B"])
    return mix.outputs["Result"]


def feed_surface(m, nt):
    metal, rough, coat = SURFACE[m.name]
    ao = nt.nodes.new("ShaderNodeAmbientOcclusion")
    ao.inputs["Distance"].default_value = 0.007
    ao.only_local = True
    comb = nt.nodes.new("ShaderNodeCombineColor")
    nt.links.new(socket_or_value(nt, metal), comb.inputs[0])
    nt.links.new(socket_or_value(nt, rough), comb.inputs[1])
    nt.links.new(ao.outputs["AO"], comb.inputs[2])
    return comb.outputs[0]


def feed_coat(m, nt):
    comb = nt.nodes.new("ShaderNodeCombineColor")
    v = socket_or_value(nt, SURFACE[m.name][2])
    for k in range(3):
        nt.links.new(v, comb.inputs[k])
    return comb.outputs[0]


albedo = emit_bake("bake_albedo", feed_albedo, 4)
surface = emit_bake("bake_surface", feed_surface, 32)
coat = emit_bake("bake_coat", feed_coat, 1)[..., 0]

STRIP = 32
H = TEX + STRIP


def save_png(name, rgba):
    im = bpy.data.images.new(name, TEX, H, alpha=True)
    im.pixels[:] = (rgba[::-1].astype(np.float32) / 255.0).ravel()
    im.filepath_raw = os.path.join(EXPORT_DIR, name)
    im.file_format = "PNG"
    im.save()


# base colour to sRGB, with a strip of plain swatches below for the tracer paint: green, red
px = np.clip(albedo, 0.0, 1.0)
srgb = np.where(px <= 0.0031308, px * 12.92, 1.055 * np.power(px, 1 / 2.4) - 0.055)
full = np.zeros((H, TEX, 4), dtype=np.uint8)
full[:TEX, :, :3] = (srgb * 255 + 0.5).astype(np.uint8)
full[:, :, 3] = 255
SWATCH = {"green": (60, 200, 70), "red": (215, 40, 30)}
for i, c in enumerate(SWATCH.values()):
    full[TEX:, i * 64:(i + 1) * 64, :3] = c
save_png(EXPORT_NAME + ".png", full)
# the surface map, linear; the swatches as glossy paint
mat = np.zeros((H, TEX, 4), dtype=np.uint8)
mat[:TEX, :, :3] = (np.clip(surface, 0.0, 1.0) * 255 + 0.5).astype(np.uint8)
mat[:TEX, :, 3] = (np.clip(coat, 0.0, 1.0) * 255 + 0.5).astype(np.uint8)
mat[TEX:] = (0, 140, 255, 80)
save_png(EXPORT_NAME + "_mat.png", mat)

# the parts moved apart back in their place
pl0 = me_all.attributes["part"].data
for part, dx in BAKE_APART.items():
    idx = ORDER.index(part)
    moved = {me_all.loops[li].vertex_index for p in me_all.polygons if pl0[p.index].value == idx for li in p.loop_indices}
    for vi in moved:
        me_all.vertices[vi].co.x -= dx

# the meshes: quads stay quads, everything else goes as triangles (a quad with its last corner doubled)
SELECTOR_PIVOT_ITEM = to_item(SELECTOR_PIVOT) if "selector" in ORDER else Vector()
uv = me_all.uv_layers.active.data
cn = me_all.corner_normals
pl = me_all.attributes["part"].data
me_all.calc_loop_triangles()
tris = {}
for t in me_all.loop_triangles:
    tris.setdefault(t.polygon_index, []).append(t.loops)
data = {p: [] for p in ORDER}


# a part may have a frame of its own: {part: (position, normal)} from Blender's coordinates
PART_ITEM = globals().get("PART_ITEM", {})


def corner(li, part):
    v = me_all.vertices[me_all.loops[li].vertex_index].co
    to_p, to_n = PART_ITEM.get(part, (to_item, nrm_item))
    p = to_p(v)
    if part == "selector":
        p = p - SELECTOR_PIVOT_ITEM
    n = to_n(cn[li].vector)
    u, w = uv[li].uv
    return (p.x, p.y, p.z, u, (1.0 - w) * TEX / H, n.x, n.y, n.z)


for poly in me_all.polygons:
    part = ORDER[pl[poly.index].value]
    loops = list(poly.loop_indices)
    if len(loops) == 4:
        quads = [loops]
    else:
        quads = [list(t) + [t[2]] for t in tris[poly.index]]
    for q in quads:
        for li in q:
            data[part].extend(corner(li, part))

with gzip.open(os.path.join(EXPORT_DIR, EXPORT_NAME + ".mesh.gz"), "wb") as f:
    f.write(struct.pack(">I", len(ORDER)))
    for part in ORDER:
        name = part.encode()
        f.write(struct.pack(">H", len(name)) + name)
        f.write(struct.pack(">I", len(data[part])))
        f.write(struct.pack(f">{len(data[part])}f", *data[part]))
for part in ORDER:
    print(part, len(data[part]) // 32, "quads", flush=True)
print("selector pivot", tuple(round(c, 4) for c in SELECTOR_PIVOT_ITEM), "tex", TEX, H, flush=True)
