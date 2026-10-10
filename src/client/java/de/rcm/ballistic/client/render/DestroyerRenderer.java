package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.ARRAY;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.HAZE;
import static de.rcm.ballistic.client.render.StructureKit.HAZE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_DECK;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.entity.DestroyerEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Arleigh Burke-style destroyer in ship space (+Z the bow, origin 0.6 below the waterline): a
 * flared hull with red antifouling below the boot-topping, the forward deckhouse and bridge with
 * the four octagonal SPY-1 arrays, the tripod mast with its radars, two funnels, the 5-inch gun,
 * forward and aft vertical launch cells (open hatches where a round has been fired), the two
 * Phalanx mounts that turn onto their target and stream tracers, the hangar and flight deck aft.
 * It rolls and pitches gently in the swell.
 */
public class DestroyerRenderer extends EntityRenderer<DestroyerEntity, DestroyerRenderer.State> {
	private static final float WATER = 0.6F;
	private static final float DECK = 3.0F;
	/** Hull stations: {z, deck half-beam, waterline half-beam, keel half-beam, deck height}. */
	private static final float[][] STATIONS = {
		{-17.0F, 1.55F, 1.45F, 0.6F, 2.9F},
		{-12.0F, 2.0F, 1.95F, 0.9F, 2.9F},
		{4.0F, 2.05F, 1.95F, 0.9F, 3.0F},
		{10.0F, 1.85F, 1.5F, 0.5F, 3.2F},
		{14.0F, 1.2F, 0.8F, 0.2F, 3.5F},
		{17.5F, 0.12F, 0.05F, 0.02F, 3.8F}
	};
	private static final float[] CIWS_FORE = {0.0F, 5.9F, 7.6F};
	private static final float[] CIWS_AFT = {0.0F, 5.5F, -12.0F};

	private static final BoxMesh HULL = buildHull();
	private static final BoxMesh LIGHTS = new BoxMesh.Builder()
		.box(-0.08F, 13.6F, 3.42F, 0.08F, 13.75F, 3.58F, RED_LAMP) // masthead
		.box(-2.15F, 6.6F, 5.8F, -2.05F, 6.75F, 6.0F, RED_LAMP) // port sidelight
		.box(2.05F, 6.6F, 5.8F, 2.15F, 6.75F, 6.0F, LAMP)
		.build();
	private static final BoxMesh CIWS_MOUNT = buildCiws();
	private static final BoxMesh[] HATCH = {
		new BoxMesh.Builder().box(-0.24F, 0.0F, -0.24F, 0.24F, 0.05F, 0.24F, STEEL).build(),
		new BoxMesh.Builder().box(-0.24F, -0.02F, -0.24F, 0.24F, 0.0F, 0.24F, BLACK).box(-0.24F, 0.0F, -0.27F, 0.24F, 0.48F, -0.24F, STEEL).build()
	};

	public DestroyerRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public float heading;
		public float time;
		public int cells;
		public @Nullable Vec3 ciwsTarget;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(DestroyerEntity entity) {
		return entity.getBoundingBox().inflate(20.0, 10.0, 20.0);
	}

	@Override
	public void extractRenderState(DestroyerEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.heading = Mth.rotLerpRad(partialTick, entity.clientYawO, entity.getHeading());
		state.time = entity.tickCount + partialTick;
		state.cells = entity.getCells();
		Entity target = entity.getCiwsTarget() >= 0 ? entity.level().getEntity(entity.getCiwsTarget()) : null;
		state.ciwsTarget = target == null ? null : target.getPosition(partialTick).subtract(entity.getPosition(partialTick));
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(state.heading));
		// gentle roll and pitch in the swell
		poseStack.translate(0.0F, WATER, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationXYZ(0.012F * Mth.sin(state.time * 0.05F), 0.0F, 0.025F * Mth.sin(state.time * 0.037F)));
		poseStack.translate(0.0F, -WATER, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> HULL.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> LIGHTS.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		// VLS hatches: closed while loaded, open with the lid up once fired
		int fired = DestroyerEntity.CELLS - state.cells;
		for (int cell = 0; cell < DestroyerEntity.CELLS; cell++) {
			float along = cell < 8 ? 8.6F + (cell % 4) * 0.7F : -8.6F - (cell % 4) * 0.7F;
			float side = cell % 8 < 4 ? -0.4F : 0.4F;
			BoxMesh hatch = HATCH[cell < fired ? 1 : 0];
			poseStack.pushPose();
			poseStack.translate(side, DECK, along);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> hatch.emit(pose, consumer, light));
			poseStack.popPose();
		}
		// the two Phalanx mounts track the target and stream tracers at it
		for (float[] m : new float[][] {CIWS_FORE, CIWS_AFT}) {
			float yaw = 0.0F;
			float pitch = 0.3F;
			Vector3f local = null;
			if (state.ciwsTarget != null) {
				Vector3f t = new Vector3f((float) state.ciwsTarget.x, (float) state.ciwsTarget.y, (float) state.ciwsTarget.z)
					.rotateY(-state.heading).sub(m[0], m[1], m[2]);
				yaw = (float) Mth.atan2(t.x, t.z);
				pitch = (float) Mth.atan2(t.y, Math.sqrt(t.x * t.x + t.z * t.z));
				local = t;
			}
			poseStack.pushPose();
			poseStack.translate(m[0], m[1], m[2]);
			poseStack.mulPose(new Quaternionf().rotationY(yaw));
			poseStack.mulPose(new Quaternionf().rotationX(-pitch));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CIWS_MOUNT.emit(pose, consumer, light));
			poseStack.popPose();
			if (local != null && Mth.sin(state.time * 3.0F) > -0.3F) {
				Vector3f muzzle = new Vector3f(local).normalize().mul(1.4F).add(m[0], m[1], m[2]);
				Vector3f end = new Vector3f(local).add(m[0], m[1], m[2]);
				float jitter = 0.4F * Mth.sin(state.time * 7.3F);
				BoxMesh tracer = new BoxMesh.Builder().beam(muzzle, end.add(jitter, -jitter, jitter), 0.05F, 0.05F, LAMP).build();
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> tracer.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			}
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	public boolean shouldShowName(DestroyerEntity entity, double distanceToCameraSq) {
		return false;
	}

	private static BoxMesh buildHull() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// ---- hull: topsides above the waterline in haze grey, red antifouling below
		for (int i = 0; i + 1 < STATIONS.length; i++) {
			float[] s0 = STATIONS[i];
			float[] s1 = STATIONS[i + 1];
			b.hexa(HAZE,
				v(-s0[2], WATER, s0[0]), v(s0[2], WATER, s0[0]), v(s0[1], s0[4], s0[0]), v(-s0[1], s0[4], s0[0]),
				v(-s1[2], WATER, s1[0]), v(s1[2], WATER, s1[0]), v(s1[1], s1[4], s1[0]), v(-s1[1], s1[4], s1[0]));
			b.hexa(RUST,
				v(-s0[3], -2.2F, s0[0]), v(s0[3], -2.2F, s0[0]), v(s0[2], WATER, s0[0]), v(-s0[2], WATER, s0[0]),
				v(-s1[3], -2.2F, s1[0]), v(s1[3], -2.2F, s1[0]), v(s1[2], WATER, s1[0]), v(-s1[2], WATER, s1[0]));
			// black boot-topping band at the waterline
			b.hexa(BLACK,
				v(-s0[2] - 0.02F, WATER - 0.1F, s0[0]), v(s0[2] + 0.02F, WATER - 0.1F, s0[0]), v(s0[2] + 0.02F, WATER + 0.15F, s0[0]), v(-s0[2] - 0.02F, WATER + 0.15F, s0[0]),
				v(-s1[2] - 0.02F, WATER - 0.1F, s1[0]), v(s1[2] + 0.02F, WATER - 0.1F, s1[0]), v(s1[2] + 0.02F, WATER + 0.15F, s1[0]), v(-s1[2] - 0.02F, WATER + 0.15F, s1[0]));
			// non-skid deck on top
			b.hexa(SUB_DECK,
				v(-s0[1] + 0.05F, s0[4], s0[0]), v(s0[1] - 0.05F, s0[4], s0[0]), v(s0[1] - 0.05F, s0[4] + 0.03F, s0[0]), v(-s0[1] + 0.05F, s0[4] + 0.03F, s0[0]),
				v(-s1[1] + 0.05F, s1[4], s1[0]), v(s1[1] - 0.05F, s1[4], s1[0]), v(s1[1] - 0.05F, s1[4] + 0.03F, s1[0]), v(-s1[1] + 0.05F, s1[4] + 0.03F, s1[0]));
			// deck-edge railings
			for (float sx : new float[] {-1.0F, 1.0F}) {
				b.beam(v(sx * (s0[1] - 0.05F), s0[4] + 0.55F, s0[0]), v(sx * (s1[1] - 0.05F), s1[4] + 0.55F, s1[0]), 0.03F, 0.03F, STEEL);
			}
		}
		// transom
		float[] t = STATIONS[0];
		b.hexa(HAZE,
			v(-t[3], -2.2F, t[0]), v(t[3], -2.2F, t[0]), v(t[1], t[4], t[0]), v(-t[1], t[4], t[0]),
			v(-t[3], -2.2F, t[0] + 0.05F), v(t[3], -2.2F, t[0] + 0.05F), v(t[1], t[4], t[0] + 0.05F), v(-t[1], t[4], t[0] + 0.05F));
		// hull number on the bow
		b.box(-1.62F, 2.0F, 11.2F, -1.6F, 2.8F, 12.4F, WHITE);
		b.box(1.6F, 2.0F, 11.2F, 1.62F, 2.8F, 12.4F, WHITE);

		// ---- forward deckhouse, bridge and the SPY-1 arrays
		b.box(-1.65F, DECK, 0.5F, 1.65F, 5.8F, 7.6F, HAZE);
		b.hexa(HAZE,
			v(-1.85F, 5.8F, 4.6F), v(1.85F, 5.8F, 4.6F), v(1.85F, 7.0F, 4.6F), v(-1.85F, 7.0F, 4.6F),
			v(-1.85F, 5.8F, 7.0F), v(1.85F, 5.8F, 7.0F), v(1.7F, 7.0F, 6.6F), v(-1.7F, 7.0F, 6.6F));
		b.box(-1.7F, 6.35F, 6.62F, 1.7F, 6.75F, 6.95F, GLASS); // bridge windows
		b.box(-2.1F, 6.0F, 5.2F, -1.85F, 6.15F, 6.6F, HAZE_DARK); // bridge wings
		b.box(1.85F, 6.0F, 5.2F, 2.1F, 6.15F, 6.6F, HAZE_DARK);
		b.box(-1.5F, 7.0F, 3.0F, 1.5F, 7.6F, 6.0F, HAZE); // pilot house roof / director deck
		spy(b, v(-1.2F, 5.0F, 7.1F), -0.6F); // forward pair, canted out
		spy(b, v(1.2F, 5.0F, 7.1F), 0.6F);
		// ---- tripod mast with the surface-search radar, yardarm and antennas
		Vector3f top = v(0.0F, 13.5F, 3.5F);
		b.beam(v(-1.2F, 7.6F, 2.2F), top, 0.18F, 0.18F, HAZE);
		b.beam(v(1.2F, 7.6F, 2.2F), top, 0.18F, 0.18F, HAZE);
		b.beam(v(0.0F, 7.6F, 5.4F), top, 0.18F, 0.18F, HAZE);
		b.box(-2.4F, 11.0F, 3.4F, 2.4F, 11.12F, 3.6F, STEEL); // yardarm
		b.box(-0.6F, 12.0F, 3.1F, 0.6F, 12.4F, 3.9F, HAZE_DARK); // radar
		b.box(-0.04F, 13.5F, 3.46F, 0.04F, 15.0F, 3.54F, STEEL);
		// ---- funnels
		for (float z : new float[] {0.0F, -4.0F}) {
			b.hexa(HAZE,
				v(-0.7F, DECK, z - 1.1F), v(0.7F, DECK, z - 1.1F), v(0.7F, DECK, z + 1.1F), v(-0.7F, DECK, z + 1.1F),
				v(-0.55F, 9.5F, z - 0.9F), v(0.55F, 9.5F, z - 0.9F), v(0.55F, 9.5F, z + 0.9F), v(-0.55F, 9.5F, z + 0.9F));
			b.box(-0.5F, 9.5F, z - 0.85F, 0.5F, 9.75F, z + 0.85F, BLACK);
		}
		// ---- aft deckhouse with its pair of SPY arrays, hangar and flight deck
		b.box(-1.6F, DECK, -8.0F, 1.6F, 5.6F, -1.0F, HAZE);
		spy(b, v(-1.15F, 4.9F, -8.1F), -0.6F + Mth.PI);
		spy(b, v(1.15F, 4.9F, -8.1F), 0.6F + Mth.PI);
		b.box(-1.9F, DECK, -13.0F, 1.9F, 5.3F, -11.0F, HAZE); // hangar
		b.box(-1.5F, 3.1F, -13.02F, -0.1F, 4.8F, -13.0F, HAZE_DARK); // hangar doors
		b.box(0.1F, 3.1F, -13.02F, 1.5F, 4.8F, -13.0F, HAZE_DARK);
		b.box(-0.05F, DECK + 0.04F, -16.5F, 0.05F, DECK + 0.05F, -13.3F, WHITE); // flight deck markings
		b.box(-1.2F, DECK + 0.04F, -15.0F, 1.2F, DECK + 0.05F, -14.9F, WHITE);
		// ---- 5-inch gun forward
		b.hexa(HAZE,
			v(-0.6F, 3.25F, 11.6F), v(0.6F, 3.25F, 11.6F), v(0.6F, 4.0F, 11.8F), v(-0.6F, 4.0F, 11.8F),
			v(-0.5F, 3.25F, 13.4F), v(0.5F, 3.25F, 13.4F), v(0.4F, 3.7F, 13.2F), v(-0.4F, 3.7F, 13.2F));
		b.beam(v(0.0F, 3.65F, 13.0F), v(0.0F, 3.85F, 16.2F), 0.14F, 0.14F, HAZE_DARK);
		// ---- VLS deck plates (the hatches are drawn separately)
		b.box(-0.75F, DECK, 8.25F, 0.75F, DECK + 0.02F, 11.0F, HAZE_DARK);
		b.box(-0.75F, DECK, -11.0F, 0.75F, DECK + 0.02F, -8.25F, HAZE_DARK);
		// ---- CIWS foundations, boats in davits, anchor
		b.box(-0.6F, 5.8F, 7.0F, 0.6F, 5.9F, 8.2F, HAZE_DARK);
		b.box(-0.6F, 5.3F, -12.6F, 0.6F, 5.5F, -11.4F, HAZE_DARK);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			b.box(sx * 1.75F - 0.3F, 4.2F, -3.2F, sx * 1.75F + 0.3F, 4.7F, -0.8F, WHITE); // RHIB
		}
		b.box(-1.25F, 2.6F, 14.2F, -1.15F, 2.9F, 14.5F, BLACK);
		return b.build();
	}

	/** An octagonal SPY-1 face centred at {@code c}, facing yaw {@code angle} from +Z, tilted back. */
	private static void spy(BoxMesh.Builder b, Vector3f c, float angle) {
		Vector3f n = new Vector3f(Mth.sin(angle), 0.25F, Mth.cos(angle)).normalize();
		Vector3f right = new Vector3f(0, 1, 0).cross(n).normalize();
		Vector3f up = new Vector3f(n).cross(right).normalize();
		float r = 0.85F;
		Vector3f[] ring = new Vector3f[8];
		for (int i = 0; i < 8; i++) {
			float a = Mth.PI / 8 + i * Mth.PI / 4;
			ring[i] = new Vector3f(c).add(new Vector3f(right).mul(Mth.cos(a) * r)).add(new Vector3f(up).mul(Mth.sin(a) * r));
		}
		Vector3f t = new Vector3f(n).mul(0.06F);
		for (int i = 0; i < 4; i++) {
			// two quads of the octagon per strip, as slabs
			Vector3f a0 = ring[i];
			Vector3f a1 = ring[i + 1];
			Vector3f b0 = ring[7 - i];
			Vector3f b1 = ring[6 - i];
			b.hexa(ARRAY,
				new Vector3f(a0).sub(t), new Vector3f(a1).sub(t), new Vector3f(b1).sub(t), new Vector3f(b0).sub(t),
				new Vector3f(a0).add(t), new Vector3f(a1).add(t), new Vector3f(b1).add(t), new Vector3f(b0).add(t));
		}
	}

	/** Phalanx mount in its own frame: +Z along the barrels. */
	private static BoxMesh buildCiws() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.35F, -0.35F, -0.35F, 0.35F, 0.0F, 0.35F, WHITE); // base
		b.revolve(WHITE, v(0, 0.0F, -0.2F), v(0, 1, 0), new float[][] {{0.0F, 0.32F}, {0.6F, 0.32F}, {0.85F, 0.22F}, {0.95F, 0.0F}}, 14); // radome
		b.revolve(STEEL, v(0, 0.2F, 0.1F), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.0F, 0.12F}, {1.3F, 0.1F}, {1.3F, 0.0F}}, 10); // barrels
		return b.build();
	}
}
