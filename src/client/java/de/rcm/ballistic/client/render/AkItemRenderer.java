package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BLUED;
import static de.rcm.ballistic.client.render.StructureKit.AK_BRIGHT;
import static de.rcm.ballistic.client.render.StructureKit.AK_GRIP;
import static de.rcm.ballistic.client.render.StructureKit.AK_LAMINATE;
import static de.rcm.ballistic.client.render.StructureKit.AK_MAG;
import static de.rcm.ballistic.client.render.StructureKit.AK_WORN;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HOT;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;

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
 * The AKM in the hand: stamped receiver with its ribbed dust cover and rear sight block, laminated
 * wood stock, upper and lower handguards and the reddish-brown pistol grip, the gas tube over the
 * barrel to the gas block, front sight post with its protective ears, the slanted muzzle brake, the
 * cleaning rod under the barrel, the long selector lever on the right and the curved bakelite
 * magazine. Moving parts: the charging handle (with the bolt carrier) slams back and forward with
 * every shot, the magazine is rocked out and a new one rocked in during a reload, the handle is
 * racked when the chamber was empty - and the muzzle flash.
 * <p>
 * Item space as for the RPG-7: origin at the hand on the grip, -Z forward, +Y up, +X right.
 */
public final class AkItemRenderer implements SpecialModelRenderer<GunState> {
	/** Height of the bore above the hand. */
	static final float BORE_Y = 0.085F;
	static final float MUZZLE_Z = -0.712F;
	/** Half width of the stamped receiver. */
	static final float W = 0.028F;
	/** The ejection port on the right of the receiver (front and back edge). */
	static final float PORT_FRONT = -0.088F;
	static final float PORT_BACK = 0.012F;
	static final Vector3f SELECTOR_PIVOT = new Vector3f(W + 0.002F, 0.064F, 0.128F);
	static final BoxMesh BODY = body();
	static final BoxMesh HANDLE = handle();
	static final BoxMesh CARRIER = carrier();
	static final BoxMesh SELECTOR = selector();
	static final BoxMesh MAG = magazine(false, true);
	static final BoxMesh MAG_TRACER = magazine(true, true);
	static final BoxMesh MAG_EMPTY = magazine(false, false);
	static final BoxMesh MAG_TRACER_EMPTY = magazine(true, false);
	private static final net.minecraft.client.renderer.rendertype.RenderType FLASH_TYPE = net.minecraft.client.renderer.rendertype.RenderTypes.eyes(
		BallisticMissiles.id("textures/effect/muzzle_flash.png"));
	private static final AkAnim ANIM = new AkAnim();
	/** The first-person muzzle in camera space (x right, y up, -z ahead), as last drawn. */
	public static final Vector3f VIEW_MUZZLE = new Vector3f(0.36F, -0.3F, -1.4F);

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("ak47"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(GunState state, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		Minecraft mc = Minecraft.getInstance();
		float now = mc.level == null ? 0.0F : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long lastShot = context.firstPerson() ? Math.max(state.lastShot(), AkClient.lastShotTick()) : state.lastShot();
		AkAnim anim = ANIM.compute(state, now, lastShot, context.firstPerson() ? AkClient.checkTime(now) : -1.0F);
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F); // undo the item transform's corner offset
		if (context.firstPerson()) {
			// where the muzzle is on screen, in camera space: tracers of our own shots start there
			poseStack.last().pose().transformPosition(0.0F, BORE_Y, MUZZLE_Z - 0.02F, VIEW_MUZZLE);
		}
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));

		// ---- fire selector: safe (up, blocking the handle), full auto (middle), semi (down)
		float selector = switch (state.mode()) {
			case GunState.SAFE -> 0.0F;
			case GunState.AUTO -> -9.0F;
			default -> -18.0F;
		};
		poseStack.pushPose();
		poseStack.translate(SELECTOR_PIVOT.x, SELECTOR_PIVOT.y, SELECTOR_PIVOT.z);
		poseStack.mulPose(Axis.XP.rotationDegrees(selector));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SELECTOR.emit(pose, consumer, light));
		poseStack.popPose();

		// ---- bolt carrier with its charging handle, and the bolt face showing in the ejection port
		float travel = AkAnim.BOLT_TRAVEL * anim.bolt;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, travel);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> HANDLE.emit(pose, consumer, light));
		poseStack.popPose();
		float portFront = PORT_FRONT + 0.01F + travel;
		if (portFront < PORT_BACK - 0.004F) {
			poseStack.pushPose();
			poseStack.translate(0.0F, 0.0F, portFront);
			poseStack.scale(1.0F, 1.0F, PORT_BACK - portFront);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CARRIER.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// ---- magazine
		if (anim.magVisible) {
			BoxMesh mag = anim.magLoaded ? (anim.magTracer ? MAG_TRACER : MAG) : (anim.magTracer ? MAG_TRACER_EMPTY : MAG_EMPTY);
			poseStack.pushPose();
			poseStack.mulPose(anim.mag);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> mag.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// ---- muzzle flash, for the first frame or two of each shot
		float shot = now - lastShot;
		if (shot >= 0.0F && shot < 1.4F) {
			muzzleFlash(poseStack, collector, shot, lastShot, ambient(light, mc));
		}
		poseStack.popPose();
	}


	/** How bright the surroundings are (0 dark .. 1 daylight): a flash that is a spark at noon is a fireball at night. */
	private static float ambient(int light, Minecraft mc) {
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
	private static void muzzleFlash(PoseStack poseStack, SubmitNodeCollector collector, float age, long seed, float ambient) {
		java.util.Random r = new java.util.Random(seed * 31L + 7L);
		float fade = age < 0.35F ? 1.0F : Math.max(0.0F, 1.0F - (age - 0.35F) / 1.05F);
		float dark = 1.0F - ambient;
		float bright = fade * (0.85F + 0.15F * dark);
		float scale = (1.15F + 0.55F * dark) * (0.85F + 0.3F * r.nextFloat()) * (0.8F + 0.25F * Math.min(1.0F, age * 2.0F));
		int side = r.nextInt(4);
		int front = r.nextInt(4);
		float roll = r.nextFloat() * 360.0F;
		int c = Mth.clamp((int) (bright * 255.0F), 0, 255);
		int color = 0xFF000000 | c << 16 | c << 8 | c;
		poseStack.pushPose();
		poseStack.translate(0.0F, BORE_Y, MUZZLE_Z);
		poseStack.scale(scale, scale, scale);
		poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
		float len = 0.36F;
		float half = 0.065F;
		collector.submitCustomGeometry(poseStack, FLASH_TYPE, (pose, consumer) -> {
			for (int k = 0; k < 3; k++) {
				float a = k * Mth.PI / 3.0F;
				sheet(pose, consumer, Mth.cos(a) * half, Mth.sin(a) * half, 0.0F, 0.0F, 0.0F, -len, side, color);
			}
			// head-on star, just ahead of the brake
			float s = 0.11F;
			float u0 = 0.75F;
			float v0 = front * 0.25F;
			// drawn twice: additive, so the white-hot core burns out to full brightness
			for (int k = 0; k < 2; k++) {
				float z = -0.01F - k * 0.004F;
				quad(pose, consumer, new float[][] {{-s, -s, z, u0, v0 + 0.25F}, {s, -s, z, 1.0F, v0 + 0.25F}, {s, s, z, 1.0F, v0}, {-s, s, z, u0, v0}}, color);
			}
		});
		poseStack.popPose();
		// the brake's ports vent up and to the right
		poseStack.pushPose();
		poseStack.translate(0.0F, BORE_Y + 0.01F, MUZZLE_Z + 0.012F);
		poseStack.scale(scale, scale, scale);
		int jet = r.nextInt(4);
		int jc = Mth.clamp((int) (bright * 255.0F), 0, 255);
		int jetColor = 0xFF000000 | jc << 16 | jc << 8 | jc;
		collector.submitCustomGeometry(poseStack, FLASH_TYPE, (pose, consumer) -> {
			sheet(pose, consumer, 0.0F, 0.0F, 0.03F, 0.025F, 0.075F, -0.015F, jet, jetColor);
			sheet(pose, consumer, 0.02F, -0.012F, 0.0F, 0.025F, 0.075F, -0.015F, jet, jetColor);
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
	public GunState extractArgument(ItemStack stack) {
		return AkItem.state(stack);
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

	// ------------------------------------------------------------------ geometry

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/**
	 * Solid along Z with an octagonal (chamfered) cross-section. Each station is
	 * {z, top, bottom, half width, chamfer}, optionally a sixth value: centre x.
	 */
	static void loft(BoxMesh.Builder b, int patch, float[]... st) {
		for (int i = 0; i + 1 < st.length; i++) {
			Vector3f[] a = oct(st[i]);
			Vector3f[] c = oct(st[i + 1]);
			// middle slab, then the two chamfered sides
			b.hexa(patch, a[0], a[1], a[2], a[3], c[0], c[1], c[2], c[3]);
			b.hexa(patch, a[1], a[4], a[5], a[2], c[1], c[4], c[5], c[2]);
			b.hexa(patch, a[0], a[3], a[7], a[6], c[0], c[3], c[7], c[6]);
		}
	}

	/** 0-3 inner rectangle (bl, br, tr, tl), 4/5 right side (top, bottom)... see {@link #loft}. */
	private static Vector3f[] oct(float[] s) {
		float z = s[0];
		float top = s[1];
		float bot = s[2];
		float hw = s[3];
		float bv = Math.min(s[4], Math.min(hw * 0.9F, (top - bot) * 0.45F));
		float cx = s.length > 5 ? s[5] : 0.0F;
		float iw = hw - bv;
		return new Vector3f[] {
			v(cx - iw, bot, z), v(cx + iw, bot, z), v(cx + iw, top, z), v(cx - iw, top, z),
			v(cx + hw, bot + bv, z), v(cx + hw, top - bv, z),
			v(cx - hw, bot + bv, z), v(cx - hw, top - bv, z)
		};
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
			// same corner layout as oct(): "bottom" = -n side, "top" = +n side
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

	/** Round head of a rivet or pin on the side of the receiver. */
	private static void rivet(BoxMesh.Builder b, float x, float y, float z, float r, int patch) {
		rivet(b, v(x, y, z), v(Math.signum(x), 0, 0), r, patch);
	}

	private static void rivet(BoxMesh.Builder b, Vector3f at, Vector3f dir, float r, int patch) {
		b.revolve(patch, new Vector3f(dir).mul(-0.001F).add(at), dir, new float[][] {{0.0F, r}, {0.0012F, r * 0.85F}, {0.002F, 0.0F}}, 6);
	}

	/** Points on a circular arc in the YZ plane: start, initial direction (y, z), curvature (1/r, + bends forward), length. */
	static Vector3f[] arc(Vector3f start, float dy, float dz, float curvature, float length, int n) {
		Vector3f[] out = new Vector3f[n + 1];
		float ang = (float) Math.atan2(dz, dy);
		Vector3f p = new Vector3f(start);
		float step = length / n;
		for (int i = 0; i <= n; i++) {
			out[i] = new Vector3f(p);
			p.add(0.0F, Mth.cos(ang) * step, Mth.sin(ang) * step);
			ang += curvature * step;
		}
		return out;
	}

	private static BoxMesh body() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = W;

		// ===== stamped receiver
		loft(b, AK_BLUED, new float[] {-0.205F, 0.112F, 0.03F, w, 0.004F}, new float[] {0.165F, 0.112F, 0.03F, w, 0.004F});
		// front trunnion block, a hair proud of the sheet metal, and the rear trunnion
		loft(b, AK_BLUED, new float[] {-0.24F, 0.116F, 0.034F, w + 0.0015F, 0.005F}, new float[] {-0.165F, 0.116F, 0.034F, w + 0.0015F, 0.005F});
		loft(b, AK_BLUED, new float[] {0.13F, 0.11F, 0.032F, w + 0.0012F, 0.004F}, new float[] {0.168F, 0.11F, 0.032F, w + 0.0012F, 0.004F});
		// rivets: trunnions, the trigger group pins with their retaining spring, the magazine well dimples
		for (float sx : new float[] {-1.0F, 1.0F}) {
			float x = sx * (w + 0.0015F);
			for (float[] r : new float[][] {{-0.232F, 0.045F}, {-0.212F, 0.045F}, {-0.192F, 0.045F}, {-0.232F, 0.104F}, {-0.212F, 0.104F}, {-0.18F, 0.104F}}) {
				rivet(b, x, r[1], r[0], 0.0042F, AK_WORN);
			}
			float xr = sx * (w + 0.0012F);
			for (float[] r : new float[][] {{0.14F, 0.045F}, {0.157F, 0.045F}, {0.14F, 0.098F}, {0.157F, 0.098F}}) {
				rivet(b, xr, r[1], r[0], 0.0038F, AK_WORN);
			}
			float xp = sx * w;
			rivet(b, xp, 0.052F, -0.018F, 0.0034F, STEEL); // trigger pin
			rivet(b, xp, 0.06F, 0.018F, 0.0034F, STEEL);   // hammer pin
			rivet(b, xp, 0.046F, -0.052F, 0.0034F, STEEL); // auto sear pin
			// the AKM's dimple over the magazine well, pressed in to guide the magazine
			b.box(sx > 0 ? w : -w - 0.0006F, 0.058F, -0.112F, sx > 0 ? w + 0.0006F : -w, 0.07F, -0.06F, GUNMETAL);
			b.box(sx > 0 ? w : -w - 0.0006F, 0.06F, -0.11F, sx > 0 ? w + 0.0009F : -w + 0.0003F, 0.068F, -0.062F, BLACK);
		}
		// ejection port (right) with the bolt hold-open notch, and the charging handle slot behind it
		b.box(w - 0.0005F, 0.079F, PORT_FRONT, w + 0.0007F, 0.106F, PORT_BACK, BLACK);
		b.box(w - 0.0005F, 0.09F, PORT_BACK, w + 0.0007F, 0.099F, PORT_BACK + 0.13F, BLACK);
		// magazine well lip and the catch in front of the trigger guard
		b.box(-w - 0.002F, 0.026F, -0.118F, w + 0.002F, 0.036F, -0.03F, AK_BLUED);

		// ===== dust cover: rounded, with stamped ribs and the recoil spring guide button at the back
		loft(b, AK_BLUED, new float[] {-0.135F, 0.138F, 0.108F, w - 0.001F, 0.014F}, new float[] {0.172F, 0.136F, 0.108F, w - 0.001F, 0.014F});
		for (int i = 0; i < 6; i++) {
			float z = -0.1F + i * 0.044F;
			loft(b, AK_BLUED, new float[] {z, 0.1405F, 0.112F, w + 0.0006F, 0.015F}, new float[] {z + 0.006F, 0.1405F, 0.112F, w + 0.0006F, 0.015F});
		}
		loft(b, AK_WORN, new float[] {0.168F, 0.13F, 0.114F, 0.008F, 0.003F}, new float[] {0.182F, 0.128F, 0.116F, 0.008F, 0.003F});

		// ===== rear sight: block on the front trunnion, the sloping tangent leaf, the range slider
		loft(b, AK_BLUED, new float[] {-0.245F, 0.158F, 0.11F, 0.021F, 0.006F}, new float[] {-0.195F, 0.168F, 0.11F, 0.022F, 0.006F},
			new float[] {-0.135F, 0.15F, 0.11F, 0.02F, 0.006F});
		b.hexa(AK_BLUED,
			v(-0.015F, 0.167F, -0.2F), v(0.015F, 0.167F, -0.2F), v(0.015F, 0.172F, -0.2F), v(-0.015F, 0.172F, -0.2F),
			v(-0.015F, 0.17F, -0.122F), v(0.015F, 0.17F, -0.122F), v(0.015F, 0.175F, -0.122F), v(-0.015F, 0.175F, -0.122F));
		// the leaf's support down to the block at the back
		b.box(-0.011F, 0.148F, -0.135F, 0.011F, 0.171F, -0.127F, AK_BLUED);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			// the ears either side of the notch
			b.box(sx > 0 ? 0.0035F : -0.012F, 0.173F, -0.13F, sx > 0 ? 0.012F : -0.0035F, 0.184F, -0.122F, AK_BLUED);
		}
		b.box(-0.018F, 0.164F, -0.172F, 0.018F, 0.177F, -0.16F, AK_WORN); // range slider
		b.box(0.018F, 0.166F, -0.17F, 0.023F, 0.174F, -0.162F, AK_WORN);  // slider catch
		// gas tube lock lever on the right of the sight block
		b.beam(v(0.022F, 0.135F, -0.214F), v(0.026F, 0.12F, -0.185F), 0.005F, 0.008F, AK_WORN);

		// ===== trigger group
		// guard: front leg, bottom strap, rear leg into the grip
		b.beam(v(0, 0.032F, -0.064F), v(0, -0.018F, -0.06F), 0.012F, 0.0045F, AK_BLUED);
		b.beam(v(0, -0.02F, -0.062F), v(0, -0.024F, 0.025F), 0.012F, 0.0045F, AK_BLUED);
		b.beam(v(0, -0.024F, 0.022F), v(0, 0.005F, 0.05F), 0.012F, 0.0045F, AK_BLUED);
		// curved trigger
		b.beam(v(0, 0.034F, -0.024F), v(0, 0.012F, -0.02F), 0.006F, 0.008F, AK_BRIGHT);
		b.beam(v(0, 0.012F, -0.02F), v(0, -0.004F, -0.008F), 0.006F, 0.007F, AK_BRIGHT);
		// magazine release paddle behind the well
		b.beam(v(0, 0.03F, -0.036F), v(0, 0.012F, -0.042F), 0.016F, 0.004F, AK_BLUED);
		b.box(-0.011F, 0.006F, -0.05F, 0.011F, 0.013F, -0.036F, AK_BLUED);

		// ===== pistol grip: reddish polymer, swelling at the palm, with finger ridge and bottom cap
		Vector3f[] gs = {v(0, 0.036F, 0.05F), v(0, -0.01F, 0.066F), v(0, -0.06F, 0.084F), v(0, -0.1F, 0.1F), v(0, -0.124F, 0.109F)};
		sweep(b, AK_GRIP, gs, new float[] {0.036F, 0.042F, 0.047F, 0.046F, 0.044F}, new float[] {0.014F, 0.0165F, 0.0175F, 0.017F, 0.016F}, 0.006F, 0.0F);
		Vector3f[] cap = {v(0, -0.122F, 0.108F), v(0, -0.13F, 0.111F)};
		sweep(b, AK_BLUED, cap, new float[] {0.046F, 0.046F}, new float[] {0.0168F, 0.0168F}, 0.005F, 0.0F);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			// moulded grip panels
			Vector3f[] panel = {v(0, -0.004F, 0.062F), v(0, -0.06F, 0.084F), v(0, -0.098F, 0.099F)};
			sweep(b, AK_GRIP, panel, new float[] {0.028F, 0.032F, 0.03F}, new float[] {0.003F, 0.003F, 0.003F}, 0.001F, sx * 0.0175F);
		}

		// ===== laminated stock: tang, wrist, comb sweeping down to the butt plate
		loft(b, AK_LAMINATE,
			new float[] {0.165F, 0.108F, 0.036F, 0.022F, 0.008F},
			new float[] {0.215F, 0.104F, 0.014F, 0.021F, 0.009F},
			new float[] {0.3F, 0.094F, -0.022F, 0.024F, 0.01F},
			new float[] {0.4F, 0.082F, -0.06F, 0.026F, 0.011F},
			new float[] {0.52F, 0.066F, -0.104F, 0.0285F, 0.011F});
		b.box(-0.012F, 0.108F, 0.165F, 0.012F, 0.112F, 0.235F, AK_BLUED); // tang strap
		rivet(b, v(0.0F, 0.112F, 0.22F), v(0, 1, 0), 0.0035F, AK_WORN);
		// butt plate with the trap door to the cleaning kit
		loft(b, AK_BLUED, new float[] {0.52F, 0.069F, -0.108F, 0.03F, 0.01F}, new float[] {0.535F, 0.069F, -0.108F, 0.03F, 0.01F});
		loft(b, AK_WORN, new float[] {0.535F, 0.035F, -0.06F, 0.017F, 0.004F}, new float[] {0.5368F, 0.035F, -0.06F, 0.017F, 0.004F});
		rivet(b, v(0.0F, 0.052F, 0.536F), v(0, 0, 1), 0.004F, STEEL);
		// sling loop on the left of the stock
		b.box(-0.031F, -0.03F, 0.395F, -0.026F, -0.022F, 0.43F, AK_BLUED);
		b.beam(v(-0.034F, -0.026F, 0.4F), v(-0.034F, -0.026F, 0.425F), 0.004F, 0.012F, AK_WORN);

		// ===== lower handguard with its palm swells, the ferrule and the retainer with its lever
		loft(b, AK_LAMINATE,
			new float[] {-0.242F, 0.104F, 0.04F, 0.027F, 0.01F},
			new float[] {-0.275F, 0.104F, 0.035F, 0.033F, 0.012F},
			new float[] {-0.33F, 0.104F, 0.033F, 0.036F, 0.013F},
			new float[] {-0.42F, 0.104F, 0.034F, 0.035F, 0.013F},
			new float[] {-0.468F, 0.104F, 0.042F, 0.029F, 0.011F});
		loft(b, AK_BLUED, new float[] {-0.245F, 0.108F, 0.036F, 0.03F, 0.01F}, new float[] {-0.24F, 0.108F, 0.036F, 0.03F, 0.01F});
		loft(b, AK_BLUED, new float[] {-0.482F, 0.108F, 0.038F, 0.031F, 0.01F}, new float[] {-0.468F, 0.108F, 0.038F, 0.031F, 0.01F});
		b.beam(v(0.031F, 0.06F, -0.475F), v(0.034F, 0.09F, -0.47F), 0.006F, 0.004F, AK_WORN);

		// ===== gas tube with the upper handguard, vent holes ahead of it
		b.revolve(AK_BLUED, v(0, 0.135F, -0.25F), v(0, 0, -1), new float[][] {{0.0F, 0.0135F}, {0.29F, 0.0135F}}, 10);
		loft(b, AK_LAMINATE,
			new float[] {-0.25F, 0.156F, 0.111F, 0.02F, 0.012F},
			new float[] {-0.3F, 0.158F, 0.11F, 0.021F, 0.012F},
			new float[] {-0.42F, 0.157F, 0.11F, 0.021F, 0.012F},
			new float[] {-0.445F, 0.154F, 0.112F, 0.019F, 0.011F});
		b.box(-0.0215F, 0.13F, -0.4F, 0.0215F, 0.133F, -0.29F, BLACK); // the groove along each side
		loft(b, AK_BLUED, new float[] {-0.452F, 0.152F, 0.114F, 0.017F, 0.006F}, new float[] {-0.445F, 0.152F, 0.114F, 0.017F, 0.006F});
		for (float z : new float[] {-0.468F, -0.484F, -0.5F}) {
			for (float sx : new float[] {-1.0F, 1.0F}) {
				b.box(sx > 0 ? 0.0125F : -0.0142F, 0.13F, z, sx > 0 ? 0.0142F : -0.0125F, 0.138F, z + 0.007F, BLACK);
			}
		}

		// ===== barrel
		b.revolve(AK_BLUED, v(0, BORE_Y, -0.24F), v(0, 0, -1), new float[][] {{0.0F, 0.0145F}, {0.05F, 0.0135F}, {0.42F, 0.0118F}}, 12);

		// ===== 45-degree gas block with the front sling loop
		b.hexa(AK_BLUED,
			v(-0.0175F, 0.07F, -0.505F), v(0.0175F, 0.07F, -0.505F), v(0.0175F, 0.152F, -0.505F), v(-0.0175F, 0.152F, -0.505F),
			v(-0.0175F, 0.07F, -0.55F), v(0.0175F, 0.07F, -0.55F), v(0.0175F, 0.152F, -0.525F), v(-0.0175F, 0.152F, -0.525F));
		b.revolve(AK_BLUED, v(0, 0.135F, -0.525F), v(0, 0, -1), new float[][] {{0.0F, 0.016F}, {0.012F, 0.016F}, {0.016F, 0.0F}}, 10);
		b.box(-0.022F, 0.084F, -0.54F, -0.0175F, 0.092F, -0.515F, AK_BLUED);
		b.beam(v(-0.025F, 0.075F, -0.537F), v(-0.025F, 0.075F, -0.518F), 0.004F, 0.012F, AK_WORN);

		// ===== front sight base: sight post between protective ears, bayonet lug, cleaning rod stop
		loft(b, AK_BLUED,
			new float[] {-0.612F, 0.108F, 0.06F, 0.0185F, 0.006F},
			new float[] {-0.662F, 0.108F, 0.06F, 0.0185F, 0.006F});
		b.hexa(AK_BLUED,
			v(-0.012F, 0.108F, -0.622F), v(0.012F, 0.108F, -0.622F), v(0.012F, 0.12F, -0.632F), v(-0.012F, 0.12F, -0.632F),
			v(-0.012F, 0.108F, -0.66F), v(0.012F, 0.108F, -0.66F), v(0.012F, 0.12F, -0.656F), v(-0.012F, 0.12F, -0.656F));
		for (float sx : new float[] {-1.0F, 1.0F}) {
			b.hexa(AK_BLUED,
				v(sx * 0.0125F, 0.108F, -0.628F), v(sx * 0.018F, 0.108F, -0.628F), v(sx * 0.016F, 0.19F, -0.636F), v(sx * 0.0125F, 0.19F, -0.636F),
				v(sx * 0.0125F, 0.108F, -0.658F), v(sx * 0.018F, 0.108F, -0.658F), v(sx * 0.016F, 0.19F, -0.652F), v(sx * 0.0125F, 0.19F, -0.652F));
		}
		b.revolve(AK_WORN, v(0, 0.12F, -0.644F), v(0, 1, 0), new float[][] {{0.0F, 0.0034F}, {0.063F, 0.003F}, {0.066F, 0.0F}}, 6);
		b.box(-0.006F, 0.044F, -0.66F, 0.006F, 0.06F, -0.618F, AK_BLUED);
		b.box(-0.004F, 0.04F, -0.656F, 0.004F, 0.044F, -0.645F, AK_BLUED);
		// cleaning rod under the barrel, its notched head at the front
		b.revolve(AK_WORN, v(0, 0.058F, -0.47F), v(0, 0, -1), new float[][] {{0.0F, 0.0036F}, {0.19F, 0.0036F}}, 6);
		b.revolve(AK_WORN, v(0, 0.058F, -0.66F), v(0, 0, -1), new float[][] {{0.0F, 0.005F}, {0.012F, 0.005F}, {0.014F, 0.0F}}, 6);

		// ===== slant muzzle brake: longer at the bottom so the blast kicks the muzzle down and right
		b.revolve(AK_WORN, v(0, BORE_Y, -0.664F), v(0, 0, -1), new float[][] {{0.0F, 0.0115F}, {0.004F, 0.0155F}, {0.032F, 0.0155F}}, 12);
		int seg = 8;
		for (int i = 0; i < seg; i++) {
			float a0 = Mth.PI + Mth.PI * i / seg;
			float a1 = Mth.PI + Mth.PI * (i + 1) / seg;
			float r = 0.0155F;
			float ri = 0.009F;
			Vector3f o0 = v(Mth.cos(a0) * r, BORE_Y + Mth.sin(a0) * r, 0);
			Vector3f o1 = v(Mth.cos(a1) * r, BORE_Y + Mth.sin(a1) * r, 0);
			Vector3f i0 = v(Mth.cos(a0) * ri, BORE_Y + Mth.sin(a0) * ri, 0);
			Vector3f i1 = v(Mth.cos(a1) * ri, BORE_Y + Mth.sin(a1) * ri, 0);
			// the cut runs from the top front edge down to the long bottom lip
			float l0 = 0.004F + 0.014F * -Mth.sin(a0);
			float l1 = 0.004F + 0.014F * -Mth.sin(a1);
			float z0 = -0.696F;
			b.hexa(AK_WORN,
				v(i0.x, i0.y, z0), v(o0.x, o0.y, z0), v(o1.x, o1.y, z0), v(i1.x, i1.y, z0),
				v(i0.x, i0.y, z0 - l0), v(o0.x, o0.y, z0 - l0), v(o1.x, o1.y, z0 - l1), v(i1.x, i1.y, z0 - l1));
		}
		b.revolve(BLACK, v(0, BORE_Y, -0.6965F), v(0, 0, -1), new float[][] {{0.0F, 0.0F}, {0.001F, 0.0085F}}, 10);

		// ===== canvas sling from the front loop on the gas block to the loop on the stock, hanging slack
		int links = 12;
		Vector3f front = v(0, 0.07F, -0.528F);
		Vector3f rear = v(0, -0.034F, 0.412F);
		Vector3f[] sling = new Vector3f[links + 1];
		for (int i = 0; i <= links; i++) {
			float f = i / (float) links;
			Vector3f p = new Vector3f(front).lerp(rear, f);
			p.y -= 0.1F * 4.0F * f * (1.0F - f); // the sag of a strap hanging under its own weight
			sling[i] = p;
		}
		float[] across = new float[links + 1];
		float[] thick = new float[links + 1];
		java.util.Arrays.fill(across, 0.024F);
		java.util.Arrays.fill(thick, 0.0016F);
		sweep(b, OLIVE, sling, across, thick, 0.0005F, -0.0365F);
		// the brass-coloured buckle partway along
		Vector3f buckle = sling[8];
		b.box(-0.0395F, buckle.y - 0.015F, buckle.z - 0.008F, -0.0335F, buckle.y + 0.015F, buckle.z + 0.008F, AK_WORN);
		return b.build();
	}

	/** Charging handle on the right, riding the bolt carrier through its slot. */
	private static BoxMesh handle() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(W - 0.002F, 0.089F, -0.004F, W + 0.008F, 0.1F, 0.014F, AK_BLUED);
		b.beam(v(W + 0.006F, 0.094F, 0.005F), v(0.08F, 0.098F, 0.002F), 0.012F, 0.009F, AK_BLUED);
		b.revolve(AK_WORN, v(0.078F, 0.098F, 0.002F), v(1, 0.12F, 0), new float[][] {{0.0F, 0.0085F}, {0.006F, 0.0105F}, {0.016F, 0.0095F}, {0.02F, 0.0F}}, 8);
		return b.build();
	}

	/** The bright bolt carrier seen through the ejection port: unit length along +Z, scaled to what shows. */
	private static BoxMesh carrier() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(W - 0.008F, 0.081F, 0.0F, W - 0.002F, 0.104F, 1.0F, AK_BRIGHT);
		return b.build();
	}

	/** The long selector lever on the right, pivoting at the back of the receiver (pivot at the origin). */
	private static BoxMesh selector() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(AK_WORN, v(0, 0, 0), v(1, 0, 0), new float[][] {{0.0F, 0.009F}, {0.004F, 0.009F}, {0.005F, 0.0F}}, 8);
		b.hexa(AK_WORN,
			v(0.0F, -0.006F, 0.004F), v(0.0035F, -0.006F, 0.004F), v(0.0035F, 0.007F, 0.004F), v(0.0F, 0.007F, 0.004F),
			v(0.0F, 0.012F, -0.19F), v(0.0035F, 0.012F, -0.19F), v(0.0035F, 0.026F, -0.19F), v(0.0F, 0.026F, -0.19F));
		// the finger tab at the front end, bent outward
		b.box(0.0F, 0.002F, -0.205F, 0.012F, 0.014F, -0.188F, AK_WORN);
		return b.build();
	}

	/** The curved 30-round magazine, top at the magazine well, with two rounds showing in the lips. */
	private static BoxMesh magazine(boolean tracer, boolean loaded) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		int n = 10;
		Vector3f[] spine = arc(v(0, 0.036F, -0.06F), -1.0F, -0.12F, 1.9F, 0.31F, n);
		float[] depth = new float[n + 1];
		float[] hw = new float[n + 1];
		for (int i = 0; i <= n; i++) {
			float f = i / (float) n;
			depth[i] = 0.068F + 0.006F * f;
			hw[i] = 0.0135F + 0.002F * f;
		}
		sweep(b, AK_MAG, spine, depth, hw, 0.004F, 0.0F);
		// raised ribs on both sides, following the curve
		Vector3f[] ribF = new Vector3f[n - 1];
		Vector3f[] ribR = new Vector3f[n - 1];
		for (int i = 1; i < n; i++) {
			Vector3f t = new Vector3f(spine[i + 1]).sub(spine[i - 1]).normalize();
			Vector3f nn = v(0.0F, -t.z, t.y);
			ribF[i - 1] = new Vector3f(spine[i]).add(new Vector3f(nn).mul(-0.018F));
			ribR[i - 1] = new Vector3f(spine[i]).add(new Vector3f(nn).mul(0.016F));
		}
		float[] rd = new float[n - 1];
		float[] rw = new float[n - 1];
		java.util.Arrays.fill(rd, 0.008F);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			for (int i = 0; i < n - 1; i++) {
				rw[i] = 0.0015F;
			}
			sweep(b, AK_MAG, ribF, rd, rw, 0.0005F, sx * (0.0145F + 0.001F));
			sweep(b, AK_MAG, ribR, rd, rw, 0.0005F, sx * (0.0145F + 0.001F));
		}
		// floor plate with its tab
		Vector3f end = spine[n];
		Vector3f tEnd = new Vector3f(spine[n]).sub(spine[n - 1]).normalize();
		Vector3f below = new Vector3f(end).add(new Vector3f(tEnd).mul(0.01F));
		sweep(b, AK_BLUED, new Vector3f[] {new Vector3f(end).add(new Vector3f(tEnd).mul(-0.002F)), below},
			new float[] {0.08F, 0.08F}, new float[] {0.0165F, 0.0165F}, 0.004F, 0.0F);
		// front locking lug and the rear catch lug
		b.box(-0.01F, 0.02F, -0.1F, 0.01F, 0.036F, -0.092F, AK_BLUED);
		b.box(-0.008F, 0.022F, -0.028F, 0.008F, 0.032F, -0.02F, AK_BLUED);
		// feed lips and the top two rounds: steel-cased 7.62x39, copper-washed bullets (green tip for tracers)
		b.box(-0.0145F, 0.034F, -0.092F, -0.009F, 0.042F, -0.03F, AK_BLUED);
		b.box(0.009F, 0.034F, -0.092F, 0.0145F, 0.042F, -0.03F, AK_BLUED);
		for (int k = 0; k < (loaded ? 2 : 0); k++) {
			float x = k == 0 ? 0.0035F : -0.0035F;
			float y = k == 0 ? 0.042F : 0.036F;
			b.revolve(BRASS, v(x, y, -0.03F), v(0, -0.06F, -1), new float[][] {{0.0F, 0.0055F}, {0.04F, 0.0052F}, {0.046F, 0.0042F}}, 8);
			b.revolve(tracer ? GREEN_LAMP : HOT, v(x, y - 0.0028F, -0.076F), v(0, -0.06F, -1), new float[][] {{0.0F, 0.0042F}, {0.012F, 0.0036F}, {0.022F, 0.0F}}, 8);
		}
		if (tracer) {
			// a strip of green tape round a magazine loaded with tracers
			Vector3f[] band = {spine[3], new Vector3f(spine[3]).lerp(spine[4], 0.35F)};
			sweep(b, GREEN_LAMP, band, new float[] {depth[3] + 0.002F, depth[3] + 0.002F}, new float[] {hw[3] + 0.0012F, hw[3] + 0.0012F}, 0.004F, 0.0F);
		}
		return b.build();
	}
}
