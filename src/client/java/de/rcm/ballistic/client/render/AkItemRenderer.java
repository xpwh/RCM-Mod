package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.gun.AkAnim;
import de.rcm.ballistic.client.gun.AkClient;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The AK-47 in the hand, modelled in Blender after a photograph of a Type II rifle (tools/ak47_blender):
 * the milled receiver with its lightening cut, the dust cover, the tangent rear sight, the 45-degree gas
 * block, the hooded front sight, the slab-sided ribbed magazine and the shellacked birch furniture, the
 * look of the worn bluing and the wood baked into one texture. Moving parts: the bolt carrier with its
 * charging handle slams back and forward with every shot, the selector lever turns, the magazine is
 * rocked out and a new one rocked in during a reload - and the muzzle flash.
 * <p>
 * Item space as for the RPG-7: origin at the hand on the grip, -Z forward, +Y up, +X right.
 */
public final class AkItemRenderer implements SpecialModelRenderer<GunAttachmentModels.Armed<GunState>> {
	/** Height of the bore above the hand. */
	static final float BORE_Y = 0.085F;
	static final float MUZZLE_Z = -0.712F;
	/** The selector lever turns about this point (its mesh is drawn relative to it). */
	static final Vector3f SELECTOR_PIVOT = new Vector3f(0.0225F, 0.0691F, 0.0784F);
	/** Shaded physically per pixel (GunShading): the base colour and the surface map baked from the Blender materials. */
	public static final net.minecraft.client.renderer.rendertype.RenderType TYPE = GunShading.type("ak47",
		BallisticMissiles.id("textures/entity/ak47.png"), BallisticMissiles.id("textures/entity/ak47_mat.png"));
	/** Plain paint swatches in the strip under the baked texture: green and red, for tracer tips and tape. */
	private static final float SWATCH_V = 2064.0F / 2080.0F;
	private static final float GREEN_U = 32.0F / 2048.0F;
	private static final float RED_U = 96.0F / 2048.0F;
	private static final java.util.Map<String, BoxMesh> PARTS = load("/assets/ballisticmissiles/meshes/ak47.mesh.gz");
	static final BoxMesh BODY = PARTS.get("body");
	/** The bolt carrier with the charging handle. */
	static final BoxMesh HANDLE = PARTS.get("carrier");
	static final BoxMesh SELECTOR = PARTS.get("selector");
	static final BoxMesh MAG = magazine(GunState.BALL, true);
	static final BoxMesh MAG_TRACER = magazine(GunState.TRACER, true);
	static final BoxMesh MAG_TRACER_RED = magazine(GunState.TRACER_RED, true);
	static final BoxMesh MAG_EMPTY = magazine(GunState.BALL, false);
	static final BoxMesh MAG_TRACER_EMPTY = magazine(GunState.TRACER, false);
	static final BoxMesh MAG_TRACER_RED_EMPTY = magazine(GunState.TRACER_RED, false);

	/** The magazine model for an ammunition type (green or red tips on the top rounds of a tracer mix). */
	static BoxMesh magazineMesh(int ammo, boolean loaded) {
		return switch (ammo) {
			case GunState.TRACER -> loaded ? MAG_TRACER : MAG_TRACER_EMPTY;
			case GunState.TRACER_RED -> loaded ? MAG_TRACER_RED : MAG_TRACER_RED_EMPTY;
			default -> loaded ? MAG : MAG_EMPTY;
		};
	}

	/**
	 * The magazine with the top two rounds in its lips when loaded: lacquered steel cases, copper-washed
	 * bullets, their noses painted green or red on tracers, and a strip of the same colour taped round it.
	 */
	private static BoxMesh magazine(int ammo, boolean loaded) {
		java.util.List<BoxMesh> parts = new java.util.ArrayList<>();
		parts.add(PARTS.get("magazine"));
		float paint = ammo == GunState.TRACER_RED ? RED_U : GREEN_U;
		boolean tracer = GunState.tracer(ammo);
		if (loaded) {
			parts.add(PARTS.get("rounds"));
			parts.add(tracer ? PARTS.get("tips").withUv(paint, SWATCH_V) : PARTS.get("tips"));
		}
		if (tracer) {
			parts.add(PARTS.get("band").withUv(paint, SWATCH_V));
		}
		return BoxMesh.merge(parts.toArray(BoxMesh[]::new));
	}

	/** The exported parts: a count, then per part its name and its vertex data (x y z u v nx ny nz, four to a face). */
	static java.util.Map<String, BoxMesh> load(String path) {
		java.util.Map<String, BoxMesh> out = new java.util.HashMap<>();
		try (java.io.InputStream raw = AkItemRenderer.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IllegalStateException("missing " + path);
			}
			java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(raw)));
			int parts = in.readInt();
			for (int p = 0; p < parts; p++) {
				String name = in.readUTF();
				float[] data = new float[in.readInt()];
				for (int i = 0; i < data.length; i++) {
					data[i] = in.readFloat();
				}
				out.put(name, BoxMesh.of(data));
			}
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return out;
	}

	private static final net.minecraft.client.renderer.rendertype.RenderType FLASH_TYPE = net.minecraft.client.renderer.rendertype.RenderTypes.eyes(
		BallisticMissiles.id("textures/effect/muzzle_flash.png"));
	private static final AkAnim ANIM = new AkAnim();
	/** The first-person muzzle in camera space (x right, y up, -z ahead), as last drawn. */
	public static final Vector3f VIEW_MUZZLE = new Vector3f(0.36F, -0.3F, -1.4F);

	public static void register() {
		GunShading.init();
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("ak47"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(GunAttachmentModels.Armed<GunState> armed, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light,
		int overlay, boolean foil, int outline) {
		GunState state = armed.pose();
		Minecraft mc = Minecraft.getInstance();
		float now = mc.level == null ? 0.0F : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long lastShot = context.firstPerson() ? Math.max(state.lastShot(), AkClient.lastShotTick()) : state.lastShot();
		AkAnim anim = ANIM.compute(state, now, lastShot, context.firstPerson() ? AkClient.checkTime(now) : -1.0F,
			context.firstPerson() ? AkClient.inspectTime(now) : -1.0F);
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F); // undo the item transform's corner offset
		if (context.firstPerson()) {
			// where the muzzle is on screen, in camera space: tracers of our own shots start there
			poseStack.last().pose().transformPosition(0.0F, BORE_Y, GunAttachmentModels.muzzleEnd(armed.att(), GunAttachmentModels.AK) - 0.02F, VIEW_MUZZLE);
		}
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));

		// ---- fire selector: safe (up, blocking the handle), full auto (middle), semi (down)
		float selector = switch (state.mode()) {
			case GunState.SAFE -> 0.0F;
			case GunState.AUTO -> -9.0F;
			default -> -18.0F;
		};
		poseStack.pushPose();
		poseStack.translate(SELECTOR_PIVOT.x, SELECTOR_PIVOT.y, SELECTOR_PIVOT.z);
		poseStack.mulPose(Axis.XP.rotationDegrees(selector));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> SELECTOR.emit(pose, consumer, light));
		poseStack.popPose();

		// ---- bolt carrier with its charging handle, seen through the ejection port
		float travel = AkAnim.BOLT_TRAVEL * anim.bolt;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, travel);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> HANDLE.emit(pose, consumer, light));
		poseStack.popPose();

		// ---- magazine
		if (anim.magVisible) {
			BoxMesh mag = magazineMesh(anim.magTracer, anim.magLoaded);
			poseStack.pushPose();
			poseStack.mulPose(anim.mag);
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> mag.emit(pose, consumer, light));
			poseStack.popPose();
		}

		if (anim.mag2Visible) {
			BoxMesh mag2 = magazineMesh(anim.mag2Tracer, true);
			poseStack.pushPose();
			poseStack.mulPose(anim.mag2);
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> mag2.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// ---- the light and laser on the handguard
		GunAttachmentModels.submit(armed.att(), GunAttachmentModels.AK, false, poseStack, collector, light, context.firstPerson());
		if (context.firstPerson()) {
			// a light or laser on its way from the pouch onto the rail (key P)
			de.rcm.ballistic.client.gun.AttachAnim.submitCarried(poseStack, collector, light);
		}

		// ---- muzzle flash, for the first frame or two of each shot
		float shot = now - lastShot;
		if (shot >= 0.0F && shot < 1.4F && !armed.att().suppressor()) {
			muzzleFlash(poseStack, collector, shot, lastShot, ambient(light, mc), BORE_Y, MUZZLE_Z, 1.0F);
		}
		poseStack.popPose();
	}


	/** How bright the surroundings are (0 dark .. 1 daylight): a flash that is a spark at noon is a fireball at night. */
	static float ambient(int light, Minecraft mc) {
		float block = LightTexture.block(light) / 15.0F;
		float sky = LightTexture.sky(light) / 15.0F;
		float day = 1.0F;
		if (mc.level != null) {
			long t = mc.level.getDayTime() % 24000L;
			day = t < 12500L || t > 23500L ? 1.0F : 0.1F;
		}
		return Math.max(block * 0.7F, sky * day);
	}

	/**
	 * The flash as the eye catches it: a white-hot star at the brake seen head-on, crossed sheets of
	 * burning gas reaching forward (with the secondary fireball) seen from the side, and the jets the
	 * slant brake throws up and to the right. Drawn additively; picked and turned at random per shot.
	 */
	static void muzzleFlash(PoseStack poseStack, SubmitNodeCollector collector, float age, long seed, float ambient, float boreY, float muzzleZ, float size) {
		java.util.Random r = new java.util.Random(seed * 31L + 7L);
		float fade = age < 0.35F ? 1.0F : Math.max(0.0F, 1.0F - (age - 0.35F) / 1.05F);
		float dark = 1.0F - ambient;
		float bright = fade * (0.85F + 0.15F * dark);
		float scale = size * (1.15F + 0.55F * dark) * (0.85F + 0.3F * r.nextFloat()) * (0.8F + 0.25F * Math.min(1.0F, age * 2.0F));
		int side = r.nextInt(4);
		int front = r.nextInt(4);
		float roll = r.nextFloat() * 360.0F;
		int c = Mth.clamp((int) (bright * 255.0F), 0, 255);
		int color = 0xFF000000 | c << 16 | c << 8 | c;
		poseStack.pushPose();
		poseStack.translate(0.0F, boreY, muzzleZ);
		poseStack.scale(scale, scale, scale);
		poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
		float len = 0.36F;
		float half = 0.065F;
		collector.submitCustomGeometry(poseStack, FLASH_TYPE, (pose, consumer) -> {
			for (int k = 0; k < 3; k++) {
				float a = k * Mth.PI / 3.0F;
				sheet(pose, consumer, Mth.cos(a) * half, Mth.sin(a) * half, 0.0F, 0.0F, 0.0F, -len, side, color);
			}
			// head-on star, just ahead of the bare muzzle (no brake to break it up: a rounder, fuller flash)
			float s = 0.13F;
			float u0 = 0.75F;
			float v0 = front * 0.25F;
			// drawn twice: additive, so the white-hot core burns out to full brightness
			for (int k = 0; k < 2; k++) {
				float z = -0.01F - k * 0.004F;
				quad(pose, consumer, new float[][] {{-s, -s, z, u0, v0 + 0.25F}, {s, -s, z, 1.0F, v0 + 0.25F}, {s, s, z, 1.0F, v0}, {-s, s, z, u0, v0}}, color);
			}
		});
		poseStack.popPose();
	}

	/** A flame sheet from the origin along (dx, dy, dz), (wx, wy) its half-width across (wz along). */
	private static void sheet(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, float wx, float wy, float wz, float dx, float dy, float dz,
		int row, int color) {
		float v0 = row * 0.25F;
		float v1 = v0 + 0.25F;
		quad(pose, consumer, new float[][] {
			{-wx, -wy, -wz, 0.0F, v1}, {dx - wx, dy - wy, dz - wz, 0.75F, v1}, {dx + wx, dy + wy, dz + wz, 0.75F, v0}, {wx, wy, wz, 0.0F, v0}
		}, color);
	}

	/** Both windings, so it shows from either side. */
	private static void quad(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, float[][] v, int color) {
		for (int i : new int[] {0, 1, 2, 3, 3, 2, 1, 0}) {
			consumer.addVertex(pose, v[i][0], v[i][1], v[i][2]).setColor(color).setUv(v[i][3], v[i][4])
				.setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
		}
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.42F, 0.2F, -0.25F));
		output.accept(new Vector3f(0.58F, 0.7F, 1.05F));
	}

	@Override
	public GunAttachmentModels.Armed<GunState> extractArgument(ItemStack stack) {
		return new GunAttachmentModels.Armed<>(AkItem.state(stack), de.rcm.ballistic.gun.GunAttachments.of(stack));
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new AkItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}

	// ------------------------------------------------------------------ geometry (shared with the grenades)

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/**
	 * Chamfered solid swept along a curve in the YZ plane (a magazine, a pistol grip): at every
	 * spine point the section is {@code depth} across the curve and {@code 2 * hw} wide in X.
	 */
	static void sweep(BoxMesh.Builder b, int patch, Vector3f[] spine, float[] depth, float[] hw, float bevel, float cx) {
		Vector3f[][] secs = new Vector3f[spine.length][];
		for (int i = 0; i < spine.length; i++) {
			Vector3f t = new Vector3f(spine[Math.min(i + 1, spine.length - 1)]).sub(spine[Math.max(i - 1, 0)]).normalize();
			Vector3f n = v(0.0F, -t.z, t.y); // across the curve, in the YZ plane
			float d = depth[i] * 0.5F;
			float w = hw[i];
			float bv = Math.min(bevel, w * 0.9F);
			Vector3f p = spine[i];
			// corners: 0-3 inner rectangle, 4/5 right side, 6/7 left side; "bottom" = -n side, "top" = +n side
			secs[i] = new Vector3f[] {
				at(p, n, -d, cx - w + bv), at(p, n, -d, cx + w - bv), at(p, n, d, cx + w - bv), at(p, n, d, cx - w + bv),
				at(p, n, -d + bv, cx + w), at(p, n, d - bv, cx + w),
				at(p, n, -d + bv, cx - w), at(p, n, d - bv, cx - w)
			};
		}
		for (int i = 0; i + 1 < spine.length; i++) {
			Vector3f[] a = secs[i];
			Vector3f[] c = secs[i + 1];
			b.hexa(patch, a[0], a[1], a[2], a[3], c[0], c[1], c[2], c[3]);
			b.hexa(patch, a[1], a[4], a[5], a[2], c[1], c[4], c[5], c[2]);
			b.hexa(patch, a[0], a[3], a[7], a[6], c[0], c[3], c[7], c[6]);
		}
	}

	private static Vector3f at(Vector3f p, Vector3f n, float along, float x) {
		return v(x, p.y + n.y * along, p.z + n.z * along);
	}
}
