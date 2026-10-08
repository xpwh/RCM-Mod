package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_MAG;
import static de.rcm.ballistic.client.render.StructureKit.BAKELITE;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.FLASH;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WOOD;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
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
	static final float MUZZLE_Z = -0.71F;
	static final BoxMesh BODY = body();
	static final BoxMesh HANDLE = handle();
	static final BoxMesh MAG = magazine(false);
	static final BoxMesh MAG_TRACER = magazine(true);
	static final BoxMesh FLASH_MESH = flash();

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("ak47"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(GunState state, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		Minecraft mc = Minecraft.getInstance();
		float now = mc.level == null ? 0.0F : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F); // undo the item transform's corner offset
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));

		// ---- bolt carrier and charging handle: back and forward with each shot, racked on an empty reload
		float shot = now - state.lastShot();
		float bolt = 0.0F;
		if (shot >= 0.0F && shot < AkItem.CYCLE) {
			bolt = Mth.sin(shot / AkItem.CYCLE * Mth.PI);
		}
		float reload = state.reloading() ? now - state.reloadStart() : -1.0F;
		if (reload >= 0.0F && state.reloadKind() == GunState.EMPTY) {
			float c = reload - (AkItem.T_CHARGE - 7);
			if (c >= 0.0F && c < 5.0F) {
				bolt = Math.min(1.0F, c / 3.0F); // pulled all the way back...
			} else if (c >= 5.0F && c < 7.0F) {
				bolt = 1.0F - (c - 5.0F) / 2.0F; // ...and let go
			}
		}
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, 0.115F * bolt);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> HANDLE.emit(pose, consumer, light));
		poseStack.popPose();

		// ---- magazine: rocked out forward and down, then the new one rocked in
		boolean tracer = state.ammo() == GunState.TRACER;
		boolean show = state.hasMag();
		float out = 0.0F;
		if (reload >= 0.0F) {
			if (reload < AkItem.T_MAG_OUT) {
				out = 0.0F;
			} else if (reload < AkItem.T_MAG_OUT + 8) {
				out = (reload - AkItem.T_MAG_OUT) / 8.0F;
				show = state.hasMag();
			} else if (reload < AkItem.T_MAG_IN - 8) {
				show = false;
			} else if (reload < AkItem.T_MAG_IN) {
				out = 1.0F - (reload - (AkItem.T_MAG_IN - 8)) / 8.0F;
				show = true;
				tracer = state.reloadAmmo() == GunState.TRACER;
			} else {
				show = true;
				tracer = state.reloadAmmo() == GunState.TRACER;
			}
		}
		if (show) {
			BoxMesh mag = tracer ? MAG_TRACER : MAG;
			poseStack.pushPose();
			// hinge at the front catch of the magazine well
			poseStack.translate(0.0F, 0.03F, -0.085F);
			poseStack.mulPose(Axis.XP.rotationDegrees(-28.0F * out));
			poseStack.translate(0.0F, -0.03F - 0.18F * out * out, 0.085F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> mag.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// ---- muzzle flash, for the first frame or two of each shot
		if (shot >= 0.0F && shot < 1.2F) {
			float size = 1.0F - shot / 1.2F;
			long seed = state.lastShot();
			poseStack.pushPose();
			poseStack.translate(0.0F, BORE_Y, MUZZLE_Z);
			poseStack.mulPose(Axis.ZP.rotationDegrees((seed * 47L) % 360L));
			poseStack.scale(0.6F + 0.6F * size, 0.6F + 0.6F * size, 0.7F + 0.8F * size);
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> FLASH_MESH.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			poseStack.popPose();
		}
		poseStack.popPose();
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

	private static BoxMesh body() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = 0.028F;
		// receiver: the stamped box, the ribbed dust cover on top, rear sight block, trunnion
		b.box(-w, 0.03F, -0.205F, w, 0.112F, 0.165F, GUNMETAL);
		b.revolve(GUNMETAL, v(0, 0.112F, -0.06F), v(0, 0, 1), new float[][] {{0.0F, 0.024F}, {0.225F, 0.024F}, {0.235F, 0.0F}}, 10);
		for (float z = -0.03F; z < 0.15F; z += 0.035F) {
			b.box(-0.022F, 0.128F, z, 0.022F, 0.134F, z + 0.008F, STEEL); // stiffening ribs
		}
		b.box(-0.026F, 0.112F, -0.205F, 0.026F, 0.15F, -0.135F, GUNMETAL);
		b.box(-0.016F, 0.15F, -0.17F, 0.016F, 0.158F, -0.12F, STEEL); // tangent leaf
		b.box(-0.031F, 0.035F, -0.24F, 0.031F, 0.115F, -0.205F, GUNMETAL);
		// ejection port on the right and the long selector lever over it
		b.box(w - 0.001F, 0.075F, -0.08F, w + 0.002F, 0.105F, 0.02F, BLACK);
		b.box(w + 0.001F, 0.062F, -0.03F, w + 0.006F, 0.074F, 0.14F, STEEL);
		// pistol grip, trigger guard, trigger, magazine release paddle
		b.beam(v(0, 0.035F, 0.045F), v(0, -0.12F, 0.1F), 0.034F, 0.05F, BAKELITE);
		b.beam(v(0, 0.03F, -0.062F), v(0, -0.02F, -0.06F), 0.012F, 0.008F, GUNMETAL);
		b.beam(v(0, -0.02F, -0.062F), v(0, -0.022F, 0.04F), 0.012F, 0.008F, GUNMETAL);
		b.beam(v(0, 0.032F, -0.02F), v(0, 0.0F, -0.012F), 0.008F, 0.012F, STEEL);
		b.box(-0.008F, 0.012F, -0.045F, 0.008F, 0.032F, -0.035F, STEEL);
		// laminated wood stock, sloping down from the receiver to the steel butt plate
		b.hexa(WOOD,
			v(-0.026F, 0.03F, 0.165F), v(0.026F, 0.03F, 0.165F), v(0.026F, 0.112F, 0.165F), v(-0.026F, 0.112F, 0.165F),
			v(-0.03F, -0.085F, 0.52F), v(0.03F, -0.085F, 0.52F), v(0.03F, 0.06F, 0.52F), v(-0.03F, 0.06F, 0.52F));
		b.box(-0.032F, -0.09F, 0.52F, 0.032F, 0.065F, 0.535F, GUNMETAL);
		b.box(-0.031F, -0.02F, 0.36F, 0.031F, 0.0F, 0.39F, STEEL); // sling swivel plate
		// lower handguard with its finger swells, upper handguard over the gas tube
		b.box(-0.034F, 0.035F, -0.47F, 0.034F, 0.1F, -0.24F, WOOD);
		for (float z : new float[] {-0.43F, -0.37F, -0.31F}) {
			b.box(-0.036F, 0.045F, z, 0.036F, 0.09F, z + 0.035F, WOOD);
		}
		b.box(-0.031F, 0.032F, -0.48F, 0.031F, 0.1F, -0.47F, GUNMETAL); // retainer
		b.revolve(WOOD, v(0, 0.135F, -0.42F), v(0, 0, 1), new float[][] {{0.0F, 0.018F}, {0.18F, 0.022F}}, 10);
		b.revolve(GUNMETAL, v(0, 0.135F, -0.51F), v(0, 0, 1), new float[][] {{0.0F, 0.013F}, {0.09F, 0.013F}}, 8);
		// barrel, gas block, front sight with ears, cleaning rod, bayonet lug, muzzle brake
		b.revolve(GUNMETAL, v(0, BORE_Y, MUZZLE_Z + 0.04F), v(0, 0, 1), new float[][] {{0.0F, 0.0115F}, {0.43F, 0.0125F}}, 10);
		b.box(-0.02F, 0.07F, -0.53F, 0.02F, 0.15F, -0.49F, GUNMETAL);
		b.box(-0.022F, 0.07F, -0.655F, 0.022F, 0.105F, -0.615F, GUNMETAL);
		b.box(-0.003F, 0.105F, -0.645F, 0.003F, 0.16F, -0.638F, STEEL);
		for (float x : new float[] {-0.02F, 0.016F}) {
			b.box(x, 0.105F, -0.65F, x + 0.004F, 0.165F, -0.62F, GUNMETAL);
		}
		b.beam(v(0, 0.062F, -0.47F), v(0, 0.062F, -0.655F), 0.007F, 0.007F, STEEL);
		b.box(-0.006F, 0.05F, -0.64F, 0.006F, 0.07F, -0.6F, GUNMETAL);
		b.revolve(GUNMETAL, v(0, BORE_Y, MUZZLE_Z), v(0, 0, 1), new float[][] {{0.0F, 0.016F}, {0.035F, 0.016F}, {0.04F, 0.011F}}, 10);
		b.revolve(BLACK, v(0, BORE_Y, MUZZLE_Z - 0.001F), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.001F, 0.007F}}, 8); // bore
		return b.build();
	}

	/** Charging handle on the right, riding the bolt carrier. */
	private static BoxMesh handle() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(0.027F, 0.088F, -0.02F, 0.034F, 0.104F, 0.01F, STEEL);
		b.box(0.034F, 0.086F, -0.012F, 0.062F, 0.106F, 0.008F, GUNMETAL);
		b.revolve(GUNMETAL, v(0.062F, 0.096F, -0.002F), v(1, 0, 0), new float[][] {{0.0F, 0.012F}, {0.014F, 0.012F}, {0.018F, 0.0F}}, 8);
		return b.build();
	}

	/** The curved 30-round magazine, top at the magazine well. */
	private static BoxMesh magazine(boolean tracer) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f[] spine = {v(0, 0.035F, -0.052F), v(0, -0.055F, -0.066F), v(0, -0.135F, -0.095F), v(0, -0.2F, -0.137F), v(0, -0.255F, -0.19F)};
		for (int i = 0; i + 1 < spine.length; i++) {
			b.beam(spine[i], spine[i + 1], 0.026F, 0.068F - i * 0.002F, AK_MAG);
		}
		// reinforcing ribs on the sides and the floor plate
		for (int i = 1; i + 1 < spine.length; i++) {
			b.beam(new Vector3f(spine[i]).add(0.0135F, 0, 0), new Vector3f(spine[i + 1]).add(0.0135F, 0, 0), 0.002F, 0.02F, BLACK);
			b.beam(new Vector3f(spine[i]).add(-0.0135F, 0, 0), new Vector3f(spine[i + 1]).add(-0.0135F, 0, 0), 0.002F, 0.02F, BLACK);
		}
		Vector3f end = spine[spine.length - 1];
		b.beam(end, new Vector3f(end).add(0, -0.012F, -0.012F), 0.029F, 0.072F, GUNMETAL);
		if (tracer) {
			// a green band painted round a magazine of tracers
			b.beam(new Vector3f(spine[1]).lerp(spine[2], 0.4F), new Vector3f(spine[1]).lerp(spine[2], 0.6F), 0.0275F, 0.07F, GREEN_LAMP);
		}
		// the top round showing in the feed lips
		b.beam(v(0, 0.036F, -0.07F), v(0, 0.04F, -0.03F), 0.01F, 0.01F, HOT);
		return b.build();
	}

	/** Muzzle flash: crossed blades of fire and a bright core, along -Z from the brake. */
	private static BoxMesh flash() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 3; i++) {
			float a = i * Mth.PI / 3.0F;
			float c = Mth.cos(a);
			float s = Mth.sin(a);
			// a thin blade in the plane of (c, s) and the bore, thickness along its normal (-s, c)
			float nx = -s * 0.0015F;
			float ny = c * 0.0015F;
			b.hexa(FLASH,
				v(-c * 0.012F - nx, -s * 0.012F - ny, 0.0F), v(c * 0.012F - nx, s * 0.012F - ny, 0.0F),
				v(c * 0.07F - nx, s * 0.07F - ny, -0.12F), v(-c * 0.07F - nx, -s * 0.07F - ny, -0.12F),
				v(-c * 0.012F + nx, -s * 0.012F + ny, 0.0F), v(c * 0.012F + nx, s * 0.012F + ny, 0.0F),
				v(c * 0.07F + nx, s * 0.07F + ny, -0.12F), v(-c * 0.07F + nx, -s * 0.07F + ny, -0.12F));
		}
		b.revolve(HOT, v(0, 0, 0), v(0, 0, -1), new float[][] {{0.0F, 0.02F}, {0.05F, 0.05F}, {0.16F, 0.0F}}, 8);
		// the AKM brake throws its flash up and to the right
		b.beam(v(0, 0.01F, -0.01F), v(0.04F, 0.08F, -0.03F), 0.02F, 0.03F, FLASH);
		return b.build();
	}
}
