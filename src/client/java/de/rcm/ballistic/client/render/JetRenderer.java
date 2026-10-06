package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.JetEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * F-16-style multirole fighter built from boxes: blended fuselage, belly intake, bubble canopy,
 * cropped-delta wings with wingtip missiles, all-moving stabilators, a tall fin, and the bomb load
 * on four wing stations that empties as the stick is released. Model space: +Y nose, +X right
 * wing, +Z up (same convention as {@link MissileRenderer#orient}).
 */
public class JetRenderer extends EntityRenderer<JetEntity, JetRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/strike_jet.png"));
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));

	private static final int GREY = 0;
	private static final int DARK_GREY = 1;
	private static final int GLASS = 2;
	private static final int STEEL = 3;
	private static final int BLACK = 4;
	private static final int RADOME = 5;
	private static final int INTAKE = 6;
	private static final int RED = 7;
	private static final int GREEN = 8;
	private static final int BOMB = 9;
	private static final int WHITE = 10;
	private static final int MARKING = 11;
	private static final int PANEL = 12;
	private static final int YELLOW = 13;

	/** Bomb stations: x position, two bombs in tandem per station. Must come before the meshes that use it. */
	private static final float[] STATIONS = {-2.7F, -1.7F, 1.7F, 2.7F};
	private static final BoxMesh AIRFRAME = buildAirframe();
	private static final BoxMesh[] BOMBS = buildBombs();

	public JetRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public int bombs;
		public boolean afterburner;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(JetEntity entity) {
		return entity.getBoundingBox().inflate(8.0);
	}

	@Override
	public void extractRenderState(JetEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		MissileRenderer.orient(state.rotation, entity.getDir());
		state.rotation.rotateY(entity.getBank());
		state.bombs = entity.getBombsLeft();
		state.afterburner = entity.isAfterburner();
		state.time = entity.tickCount + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.8F, 0.0F);
		poseStack.mulPose(state.rotation);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> AIRFRAME.emit(pose, consumer, light));
		int shown = Math.min(BOMBS.length, state.bombs);
		for (int i = 0; i < shown; i++) {
			BoxMesh bomb = BOMBS[i];
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> bomb.emit(pose, consumer, light));
		}

		// engine plume: a faint glow in military power, a long shock-diamond flame in afterburner
		float t = state.time;
		float flicker = 0.85F + 0.15F * Mth.sin(t * 2.3F) * Mth.cos(t * 3.7F);
		float radius = state.afterburner ? 0.42F : 0.3F;
		float length = (state.afterburner ? 5.5F : 1.2F) * flicker;
		poseStack.pushPose();
		poseStack.translate(0.0F, -6.05F, 0.0F);
		collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
			MissileRenderer.flame(pose, consumer, radius, length, t, state.afterburner ? 1.0F : 0.6F);
			MissileRenderer.flame(pose, consumer, radius * 0.55F, length * 0.65F, t + 5.0F, 1.0F);
		});
		poseStack.popPose();
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static BoxMesh buildAirframe() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// fuselage: centre body, forward fuselage tapering into the radome
		b.box(-0.5F, -5.2F, -0.45F, 0.5F, 2.4F, 0.45F, GREY);
		b.box(-0.42F, 2.4F, -0.4F, 0.42F, 4.2F, 0.42F, GREY);
		b.box(-0.32F, 4.2F, -0.3F, 0.32F, 5.2F, 0.32F, GREY);
		b.beam(new Vector3f(0, 5.2F, 0), new Vector3f(0, 6.1F, 0), 0.42F, 0.42F, RADOME);
		b.beam(new Vector3f(0, 6.1F, 0), new Vector3f(0, 6.5F, 0), 0.16F, 0.16F, RADOME);
		b.beam(new Vector3f(0, 6.5F, 0), new Vector3f(0, 7.0F, 0), 0.04F, 0.04F, BLACK); // pitot probe
		// blended wing-body strakes (leading-edge extensions)
		b.box(0.5F, -0.6F, -0.12F, 0.85F, 2.6F, 0.12F, GREY);
		b.box(-0.85F, -0.6F, -0.12F, -0.5F, 2.6F, 0.12F, GREY);
		b.box(0.5F, 2.6F, -0.1F, 0.65F, 3.3F, 0.1F, GREY);
		b.box(-0.65F, 2.6F, -0.1F, -0.5F, 3.3F, 0.1F, GREY);
		// bubble canopy with a frame, and the dorsal spine behind it
		b.box(-0.3F, 2.7F, 0.42F, 0.3F, 4.3F, 0.82F, GLASS);
		b.box(-0.24F, 3.0F, 0.82F, 0.24F, 4.0F, 0.9F, GLASS);
		b.box(-0.31F, 2.65F, 0.42F, 0.31F, 2.75F, 0.86F, BLACK);
		b.box(-0.36F, -3.6F, 0.45F, 0.36F, 2.7F, 0.66F, GREY);
		b.box(-0.06F, 0.0F, 0.66F, 0.06F, 0.5F, 0.72F, BLACK); // antenna
		// belly intake with a dark mouth and the splitter plate
		b.box(-0.42F, 0.2F, -0.95F, 0.42F, 2.9F, -0.45F, GREY);
		b.box(-0.36F, 2.85F, -0.9F, 0.36F, 2.95F, -0.5F, INTAKE);
		b.box(-0.43F, 2.95F, -0.95F, 0.43F, 3.05F, -0.88F, DARK_GREY);
		// panel lines and national markings
		b.box(-0.51F, 0.9F, -0.3F, 0.51F, 1.0F, 0.3F, PANEL);
		b.box(-0.51F, -2.0F, -0.3F, 0.51F, -1.9F, 0.3F, PANEL);
		b.box(0.5F, -3.5F, -0.2F, 0.52F, -3.0F, 0.2F, MARKING);
		b.box(-0.52F, -3.5F, -0.2F, -0.5F, -3.0F, 0.2F, MARKING);
		// engine nozzle with petals
		b.beam(new Vector3f(0, -5.2F, 0), new Vector3f(0, -6.0F, 0), 0.82F, 0.82F, STEEL);
		b.beam(new Vector3f(0, -5.95F, 0), new Vector3f(0, -6.05F, 0), 0.62F, 0.62F, BLACK);
		// cropped delta wings as stepped slabs (trapezoid planform), wingtip launch rails
		for (float side : new float[] {-1.0F, 1.0F}) {
			for (int i = 0; i < 4; i++) {
				float x0 = 0.85F + i * 0.62F;
				float x1 = x0 + 0.62F;
				float y0 = -2.6F + i * 0.38F;
				float y1 = 1.6F - i * 0.72F;
				float th = 0.11F - i * 0.015F;
				b.box(side < 0 ? -x1 : x0, y0, -th, side < 0 ? -x0 : x1, y1, th, i == 3 ? DARK_GREY : GREY);
			}
			float tip = 0.85F + 4 * 0.62F;
			b.box(side < 0 ? -tip - 0.1F : tip, -2.0F, -0.07F, side < 0 ? -tip : tip + 0.1F, -0.4F, 0.07F, STEEL);
			// AIM-9 on the wingtip rail
			b.beam(new Vector3f(side * (tip + 0.12F), -2.4F, 0), new Vector3f(side * (tip + 0.12F), 0.4F, 0), 0.12F, 0.12F, WHITE);
			b.beam(new Vector3f(side * (tip + 0.12F), 0.4F, 0), new Vector3f(side * (tip + 0.12F), 0.7F, 0), 0.07F, 0.07F, DARK_GREY);
			b.box(side < 0 ? -tip - 0.4F : tip - 0.16F, -2.4F, -0.01F, side < 0 ? -tip + 0.16F : tip + 0.4F, -2.0F, 0.01F, WHITE);
			// nav lights
			b.box(side < 0 ? -tip - 0.02F : tip - 0.08F, -0.4F, -0.04F, side < 0 ? -tip + 0.08F : tip + 0.02F, -0.25F, 0.04F, side < 0 ? RED : GREEN);
			// flaperons: a slightly darker strip on the trailing edge
			b.box(side < 0 ? -2.9F : 0.9F, -2.62F, -0.1F, side < 0 ? -0.9F : 2.9F, -2.3F, 0.1F, DARK_GREY);
			// all-moving horizontal stabilators
			b.box(side < 0 ? -1.6F : 0.5F, -5.4F, -0.07F, side < 0 ? -0.5F : 1.6F, -3.7F, 0.07F, GREY);
			b.box(side < 0 ? -2.4F : 1.6F, -5.4F, -0.05F, side < 0 ? -1.6F : 2.4F, -4.4F, 0.05F, GREY);
			// ventral fins
			b.box(side * 0.35F - 0.03F, -4.2F, -1.2F, side * 0.35F + 0.03F, -3.4F, -0.45F, DARK_GREY);
			// pylons for the bomb stations
			for (float x : STATIONS) {
				if (Math.signum(x) == side) {
					b.box(x - 0.06F, -1.6F, -0.42F, x + 0.06F, 0.4F, -0.08F, DARK_GREY);
				}
			}
		}
		// vertical tail: stepped fin, rudder and the beacon on top
		for (int i = 0; i < 4; i++) {
			float z0 = 0.66F + i * 0.6F;
			float z1 = z0 + 0.6F;
			float y0 = -5.3F + i * 0.45F;
			float y1 = -2.9F - i * 0.25F;
			b.box(-0.06F, y0, z0, 0.06F, y1, z1, GREY);
		}
		b.box(-0.07F, -5.4F, 1.0F, 0.07F, -4.8F, 2.9F, DARK_GREY); // rudder
		b.box(-0.08F, -4.2F, 3.06F, 0.08F, -3.8F, 3.16F, RED);
		b.box(-0.07F, -4.4F, 1.6F, 0.07F, -3.8F, 2.2F, MARKING);
		// main landing gear doors (closed) on the belly
		b.box(-0.6F, -1.4F, -0.5F, -0.3F, 0.4F, -0.44F, PANEL);
		b.box(0.3F, -1.4F, -0.5F, 0.6F, 0.4F, -0.44F, PANEL);
		return b.build();
	}

	private static BoxMesh[] buildBombs() {
		BoxMesh[] bombs = new BoxMesh[STATIONS.length * 2];
		int i = 0;
		for (float y : new float[] {-0.15F, -1.75F}) {
			for (float x : STATIONS) {
				BoxMesh.Builder b = new BoxMesh.Builder();
				float yy = y + 0.6F;
				b.beam(new Vector3f(x, yy - 1.0F, -0.6F), new Vector3f(x, yy + 0.7F, -0.6F), 0.34F, 0.34F, BOMB);
				b.beam(new Vector3f(x, yy + 0.7F, -0.6F), new Vector3f(x, yy + 1.1F, -0.6F), 0.2F, 0.2F, BOMB);
				b.beam(new Vector3f(x, yy + 0.4F, -0.6F), new Vector3f(x, yy + 0.5F, -0.6F), 0.36F, 0.36F, YELLOW);
				b.box(x - 0.24F, yy - 1.0F, -0.62F, x + 0.24F, yy - 0.75F, -0.58F, DARK_GREY); // tail fins
				b.box(x - 0.02F, yy - 1.0F, -0.84F, x + 0.02F, yy - 0.75F, -0.36F, DARK_GREY);
				bombs[i++] = b.build();
			}
		}
		// the outer stations release first, so they are drawn last
		BoxMesh[] ordered = new BoxMesh[bombs.length];
		int[] order = {1, 2, 5, 6, 0, 3, 4, 7};
		for (int k = 0; k < order.length; k++) {
			ordered[k] = bombs[order[k]];
		}
		return ordered;
	}
}
