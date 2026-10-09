package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.JetType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * F-22-style stealth strike fighter: chined, faceted fuselage lofted from diamond cross-sections,
 * caret intakes, a gold-tinted bubble canopy, trapezoid wings and stabilators, twin canted tails and
 * two flat thrust-vectoring nozzles. The weapons bay doors swing open for the bomb run and the bombs
 * hang in the bay until they drop. Near Mach 1 a vapour cone wraps the airframe. Model space: +Y
 * nose, +X right wing, +Z up (same convention as {@link MissileRenderer#orient}).
 */
public class JetRenderer extends EntityRenderer<JetEntity, JetRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/strike_jet.png"));
	static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	static final RenderType VAPOR_TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/vapor.png"));

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

	/** The two engine nozzle centres (x) and their exit plane (y). Declared before the meshes. */
	private static final float[] NOZZLES_X = {-0.5F, 0.5F};
	private static final float NOZZLE_EXIT = -7.05F;

	private static final BoxMesh AIRFRAME = buildAirframe();
	private static final BoxMesh BAY_DOORS = buildBayDoors();
	private static final BoxMesh[] BOMBS = buildBombs();
	private static final BoxMesh WARTHOG = buildWarthog();
	private static final BoxMesh SPIRIT = buildSpirit();
	private static final BoxMesh SPIRIT_BAY = buildSpiritBay();
	private static final BoxMesh GUNSHIP = buildGunship();
	private static final BoxMesh REAPER = buildReaper();
	private static final BoxMesh APACHE = buildApache();
	private static final BoxMesh PROP3 = buildProp(3, 1.1F, 0.2F);
	private static final BoxMesh MAIN_ROTOR = buildMainRotor();
	private static final BoxMesh TAIL_ROTOR = buildTailRotor();
	private static final RenderType GLOW_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/strike_jet.png"));

	public JetRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public JetType type = JetType.STRIKE;
		public boolean firing;
		public final Quaternionf rotation = new Quaternionf();
		public int bombs;
		public boolean bayOpen;
		public boolean afterburner;
		public float mach;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(JetEntity entity) {
		return entity.getBoundingBox().inflate(10.0);
	}

	@Override
	public void extractRenderState(JetEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		MissileRenderer.orient(state.rotation, entity.getDir());
		state.rotation.rotateY(entity.getBank());
		state.bombs = entity.getBombsLeft();
		state.bayOpen = entity.isBayOpen();
		state.afterburner = entity.isAfterburner();
		state.mach = entity.getMach();
		state.time = entity.tickCount + partialTick;
		state.type = entity.getJetType();
		state.firing = entity.isFiring();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.8F, 0.0F);
		poseStack.mulPose(state.rotation);
		if (state.type == JetType.WARTHOG) {
			this.submitWarthog(state, poseStack, collector, light);
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
			return;
		}
		if (state.type == JetType.GUNSHIP || state.type == JetType.REAPER || state.type == JetType.APACHE) {
			this.submitPropAircraft(state, poseStack, collector, light);
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
			return;
		}
		if (state.type == JetType.SPIRIT) {
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> SPIRIT.emit(pose, consumer, light));
			if (state.bayOpen) {
				collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> SPIRIT_BAY.emit(pose, consumer, light));
			}
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
			return;
		}
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> AIRFRAME.emit(pose, consumer, light));
		if (state.bayOpen) {
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BAY_DOORS.emit(pose, consumer, light));
			int shown = Math.min(BOMBS.length, state.bombs);
			for (int i = 0; i < shown; i++) {
				BoxMesh bomb = BOMBS[i];
				collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> bomb.emit(pose, consumer, light));
			}
		}

		// twin plumes: a dull glow in military power, long shock-diamond flames in afterburner
		float t = state.time;
		float flicker = 0.85F + 0.15F * Mth.sin(t * 2.3F) * Mth.cos(t * 3.7F);
		float radius = state.afterburner ? 0.36F : 0.26F;
		float length = (state.afterburner ? 7.0F : 1.1F) * flicker;
		for (float x : NOZZLES_X) {
			float phase = x * 11.0F;
			poseStack.pushPose();
			poseStack.translate(x, NOZZLE_EXIT, 0.0F);
			collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
				MissileRenderer.flame(pose, consumer, radius, length, t + phase, state.afterburner ? 1.0F : 0.55F);
				MissileRenderer.flame(pose, consumer, radius * 0.55F, length * 0.7F, t + phase + 5.0F, 1.0F);
			});
			poseStack.popPose();
		}

		// Prandtl-Glauert condensation cone around Mach 1
		float vapor = Mth.clamp(1.0F - Math.abs(state.mach - 1.0F) / 0.12F, 0.0F, 1.0F);
		if (vapor > 0.02F) {
			int alpha = (int) (vapor * (0.55F + 0.1F * Mth.sin(t * 1.7F)) * 255.0F);
			collector.submitCustomGeometry(poseStack, VAPOR_TYPE, (pose, consumer) -> vaporCone(pose, consumer, alpha, t));
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/** Gunship helicopter, MQ-9 and AH-64: airframe plus spinning propellers / rotors. */
	private void submitPropAircraft(State state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		float t = state.time;
		if (state.type == JetType.GUNSHIP) {
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> GUNSHIP.emit(pose, consumer, light));
			poseStack.pushPose();
			poseStack.translate(0.0F, -0.4F, 2.05F);
			poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(t * 0.95F));
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MAIN_ROTOR.emit(pose, consumer, light));
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(0.35F, -9.0F, 2.25F);
			poseStack.mulPose(com.mojang.math.Axis.XP.rotation(t * 1.7F));
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> TAIL_ROTOR.emit(pose, consumer, light));
			poseStack.popPose();
			if (state.firing && Mth.sin(t * 9.0F) > -0.3F) {
				float flash = 0.25F + 0.15F * Mth.sin(t * 13.0F);
				BoxMesh muzzle = new BoxMesh.Builder().box(-3.2F - flash * 2.0F, 1.08F - flash, -0.1F - flash, -3.0F, 1.08F + flash, -0.1F + flash, YELLOW).build();
				collector.submitCustomGeometry(poseStack, GLOW_TYPE, (pose, consumer) -> muzzle.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			}
		} else if (state.type == JetType.REAPER) {
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> REAPER.emit(pose, consumer, light));
			poseStack.pushPose();
			poseStack.translate(0.0F, -4.85F, 0.05F);
			poseStack.mulPose(com.mojang.math.Axis.YP.rotation(-t * 1.6F));
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> PROP3.emit(pose, consumer, light));
			poseStack.popPose();
		} else {
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> APACHE.emit(pose, consumer, light));
			poseStack.pushPose();
			poseStack.translate(0.0F, -0.2F, 2.45F);
			poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(t * 0.95F));
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MAIN_ROTOR.emit(pose, consumer, light));
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(0.42F, -9.15F, 2.35F);
			poseStack.mulPose(com.mojang.math.Axis.XP.rotation(t * 1.7F));
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> TAIL_ROTOR.emit(pose, consumer, light));
			poseStack.popPose();
		}
	}

	/**
	 * Gunship helicopter (MH-60 style): cabin with the cockpit up front, twin engines on the roof, the
	 * tail boom with fin, stabilator and canted tail rotor, wheeled gear - and the open left cabin door
	 * with the door gunner sitting at his pintle-mounted cannon, ammunition can beside him.
	 */
	private static BoxMesh buildGunship() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		loft(b, DARK_GREY,
			new float[] {5.0F, 0.3F, -0.5F, 0.25F, 0.2F},
			new float[] {4.4F, 0.85F, -0.95F, 0.75F, 0.95F},
			new float[] {3.0F, 1.0F, -1.0F, 0.95F, 1.15F},
			new float[] {-1.6F, 1.0F, -1.0F, 0.95F, 1.15F},
			new float[] {-2.6F, 0.55F, -0.4F, 0.5F, 1.05F},
			new float[] {-8.6F, 0.22F, 0.35F, 0.2F, 0.85F});
		loft(b, GLASS,
			new float[] {4.85F, 0.45F, -0.3F, 0.35F, 0.45F},
			new float[] {4.1F, 0.88F, -0.2F, 0.7F, 1.05F},
			new float[] {3.1F, 0.98F, -0.1F, 0.85F, 1.15F});
		// the open left door: a dark cabin opening, the slid-back door panel beside it
		b.box(-1.02F, 0.2F, -0.75F, -0.99F, 2.4F, 0.95F, BLACK);
		b.box(-1.08F, -1.5F, -0.75F, -1.03F, 0.15F, 0.95F, DARK_GREY);
		b.box(-1.0F, 0.2F, -0.8F, 0.0F, 2.4F, -0.75F, STEEL); // cabin floor
		// door gunner: seated, legs out of the door, body, arms to the gun, helmet with visor
		b.box(-0.75F, 0.7F, -0.75F, -0.25F, 1.3F, -0.35F, BOMB);      // seat and lower body
		b.box(-0.95F, 0.85F, -0.72F, -0.65F, 1.15F, -0.55F, BOMB);    // thighs
		b.box(-0.7F, 0.75F, -0.35F, -0.3F, 1.25F, 0.4F, BOMB);        // torso in a flight suit
		b.box(-0.68F, 0.78F, -0.2F, -0.32F, 1.22F, 0.3F, DARK_GREY);  // vest
		b.box(-0.68F, 0.82F, 0.4F, -0.32F, 1.18F, 0.72F, PANEL);      // face
		b.box(-0.72F, 0.78F, 0.55F, -0.28F, 1.22F, 0.82F, DARK_GREY); // helmet
		b.box(-0.73F, 0.88F, 0.5F, -0.71F, 1.12F, 0.62F, BLACK);      // visor
		b.beam(v(-0.6F, 1.25F, 0.15F), v(-1.05F, 1.3F, 0.05F), 0.12F, 0.12F, BOMB); // arms
		b.beam(v(-0.6F, 0.75F, 0.15F), v(-1.05F, 0.95F, 0.05F), 0.12F, 0.12F, BOMB);
		// pintle mount and the cannon pointing out of the door, ammunition can and belt
		b.box(-1.1F, 1.0F, -0.75F, -1.0F, 1.1F, 0.0F, STEEL);
		b.box(-1.3F, 0.95F, -0.05F, -1.0F, 1.2F, 0.15F, DARK_GREY);
		b.beam(v(-1.1F, 1.08F, 0.08F), v(-3.0F, 1.08F, -0.1F), 0.16F, 0.16F, BLACK);
		b.beam(v(-1.1F, 1.08F, 0.08F), v(-1.6F, 1.08F, 0.04F), 0.3F, 0.26F, DARK_GREY); // receiver
		b.box(-0.95F, 1.25F, -0.7F, -0.55F, 1.75F, -0.35F, BOMB); // ammunition can
		b.beam(v(-0.9F, 1.5F, -0.4F), v(-1.15F, 1.15F, 0.05F), 0.08F, 0.03F, YELLOW);
		for (float s : new float[] {-1.0F, 1.0F}) {
			// twin engines on the roof, exhausts angled out
			b.revolve(DARK_GREY, v(s * 0.55F, 0.9F, 1.35F), v(0, -1, 0), new float[][] {
				{0.0F, 0.0F}, {0.0F, 0.3F}, {0.2F, 0.38F}, {2.4F, 0.38F}, {2.8F, 0.25F}, {2.9F, 0.0F}
			}, 12);
			b.box(s * 0.55F - 0.2F, -2.1F, 1.25F, s * 0.55F + 0.2F, -1.9F, 1.55F, BLACK);
			// main gear: strut and wheel
			b.box(s * 1.0F - 0.06F, 2.0F, -1.45F, s * 1.0F + 0.06F, 2.2F, -0.8F, STEEL);
			b.box(s * 1.0F - 0.12F, 1.8F, -1.75F, s * 1.0F + 0.12F, 2.4F, -1.3F, BLACK);
			// stabilator
			surface(b, DARK_GREY, v(s * 0.2F, -8.4F, 0.65F), v(s * 0.2F, -9.2F, 0.65F), 0.1F,
				v(s * 1.9F, -8.6F, 0.65F), v(s * 1.9F, -9.2F, 0.65F), 0.06F, up);
		}
		// roof fairing, rotor mast, fin, tail wheel, nose sensor turret, markings
		b.box(-0.6F, -1.6F, 1.1F, 0.6F, 1.8F, 1.3F, DARK_GREY);
		b.revolve(DARK_GREY, v(0.0F, -0.4F, 1.3F), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.0F, 0.32F}, {0.6F, 0.24F}, {0.75F, 0.0F}}, 10);
		b.hexa(DARK_GREY,
			v(-0.08F, -7.8F, 0.6F), v(-0.08F, -9.1F, 0.6F), v(0.08F, -9.1F, 0.6F), v(0.08F, -7.8F, 0.6F),
			v(-0.06F, -8.7F, 2.7F), v(-0.06F, -9.5F, 2.7F), v(0.06F, -9.5F, 2.7F), v(0.06F, -8.7F, 2.7F));
		b.box(-0.05F, -7.9F, -0.4F, 0.05F, -7.7F, 0.4F, STEEL);
		b.box(-0.12F, -7.95F, -0.55F, 0.12F, -7.65F, -0.35F, BLACK);
		b.box(-0.25F, 4.3F, -1.05F, 0.25F, 4.7F, -0.7F, BLACK);
		b.box(-0.22F, 4.32F, -1.07F, 0.22F, 4.5F, -1.04F, GLASS);
		b.box(0.95F, -0.8F, 0.0F, 1.01F, 0.4F, 0.6F, MARKING);
		return b.build();
	}

	/**
	 * MQ-9 Reaper: slender fuselage with the satellite-dish hump, very long straight wings, the
	 * Y-tail (V-tail plus ventral fin), a pusher propeller, the sensor ball under the nose and four
	 * Hellfires on the wing pylons.
	 */
	private static BoxMesh buildReaper() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		loft(b, GREY,
			new float[] {4.9F, 0.15F, -0.2F, 0.1F, 0.25F},
			new float[] {4.3F, 0.42F, -0.45F, 0.38F, 0.75F},
			new float[] {2.5F, 0.48F, -0.5F, 0.45F, 0.85F},
			new float[] {0.5F, 0.48F, -0.48F, 0.42F, 0.6F},
			new float[] {-3.0F, 0.32F, -0.3F, 0.28F, 0.42F},
			new float[] {-4.6F, 0.18F, -0.12F, 0.16F, 0.22F});
		for (float s : new float[] {-1.0F, 1.0F}) {
			surface(b, GREY, v(s * 0.4F, 0.75F, 0.42F), v(s * 0.4F, -0.45F, 0.42F), 0.16F,
				v(s * 8.6F, 0.35F, 0.55F), v(s * 8.6F, -0.15F, 0.55F), 0.06F, up);
			// V-tail
			surface(b, GREY, v(s * 0.2F, -3.3F, 0.35F), v(s * 0.2F, -4.4F, 0.35F), 0.08F,
				v(s * 2.2F, -4.0F, 1.6F), v(s * 2.2F, -4.55F, 1.6F), 0.05F, v(-s * 0.55F, 0, 0.83F));
			// Hellfires on the pylons
			for (float x : new float[] {2.6F, 3.8F}) {
				b.box(s * x - 0.04F, 0.4F, 0.1F, s * x + 0.04F, -0.1F, 0.38F, DARK_GREY);
				b.beam(v(s * x, -0.7F, -0.02F), v(s * x, 0.95F, -0.02F), 0.11F, 0.11F, BOMB);
			}
		}
		// ventral fin, sensor ball, satcom bulge, tail cone
		b.hexa(GREY,
			v(-0.05F, -3.4F, -0.2F), v(-0.05F, -4.4F, -0.1F), v(0.05F, -4.4F, -0.1F), v(0.05F, -3.4F, -0.2F),
			v(-0.03F, -3.9F, -1.2F), v(-0.03F, -4.45F, -1.2F), v(0.03F, -4.45F, -1.2F), v(0.03F, -3.9F, -1.2F));
		b.revolve(DARK_GREY, v(0.0F, 3.4F, -0.55F), v(0, 0, -1), new float[][] {{0.0F, 0.0F}, {0.0F, 0.32F}, {0.3F, 0.34F}, {0.6F, 0.0F}}, 12);
		b.box(-0.18F, 3.55F, -1.0F, 0.18F, 3.75F, -0.8F, GLASS);
		b.revolve(BLACK, v(0.0F, -4.6F, 0.05F), v(0, -1, 0), new float[][] {{0.0F, 0.12F}, {0.3F, 0.0F}}, 10);
		b.box(-0.3F, 1.6F, 0.82F, 0.3F, 2.3F, 0.86F, MARKING);
		return b.build();
	}

	/**
	 * AH-64 Apache: tandem stepped cockpits, engines either side of the rotor mast, long tail boom
	 * with the fin, stabilator and tail rotor, stub wings with Hellfire rails and Hydra pods, chin
	 * turret with the 30 mm chain gun and the fixed landing gear.
	 */
	private static BoxMesh buildApache() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		loft(b, BOMB,
			new float[] {5.4F, 0.2F, -0.4F, 0.15F, 0.0F},
			new float[] {4.6F, 0.55F, -0.9F, 0.45F, 0.7F},
			new float[] {2.8F, 0.75F, -1.0F, 0.6F, 1.0F},
			new float[] {0.8F, 0.95F, -1.0F, 0.8F, 1.2F},
			new float[] {-1.8F, 0.9F, -0.8F, 0.75F, 1.1F},
			new float[] {-2.6F, 0.45F, -0.1F, 0.4F, 0.9F},
			new float[] {-8.8F, 0.22F, 0.3F, 0.2F, 0.75F});
		// stepped tandem canopies: gunner in front and below, pilot behind and above
		loft(b, GLASS,
			new float[] {4.7F, 0.42F, 0.6F, 0.3F, 0.75F},
			new float[] {3.6F, 0.55F, 0.85F, 0.4F, 1.35F},
			new float[] {2.9F, 0.58F, 0.95F, 0.42F, 1.4F});
		loft(b, GLASS,
			new float[] {2.8F, 0.58F, 1.0F, 0.4F, 1.5F},
			new float[] {1.8F, 0.62F, 1.15F, 0.45F, 1.95F},
			new float[] {1.0F, 0.65F, 1.2F, 0.45F, 1.95F});
		for (float s : new float[] {-1.0F, 1.0F}) {
			// engines with exhaust suppressors
			b.revolve(BOMB, v(s * 1.1F, 0.9F, 1.15F), v(0, -1, 0), new float[][] {
				{0.0F, 0.0F}, {0.0F, 0.35F}, {0.3F, 0.45F}, {2.8F, 0.45F}, {3.4F, 0.3F}, {3.5F, 0.0F}
			}, 12);
			b.box(s * 1.1F - 0.25F, -2.65F, 1.1F, s * 1.1F + 0.25F, -2.4F, 1.45F, BLACK);
			// stub wing, Hellfire rail, Hydra pod
			surface(b, BOMB, v(s * 0.9F, 0.9F, 0.2F), v(s * 0.9F, -0.3F, 0.2F), 0.18F,
				v(s * 2.7F, 0.7F, 0.15F), v(s * 2.7F, -0.1F, 0.15F), 0.1F, up);
			b.box(s * 1.7F - 0.05F, 0.1F, -0.1F, s * 1.7F + 0.05F, 0.6F, 0.12F, DARK_GREY);
			for (float dx : new float[] {-0.18F, 0.18F}) {
				b.beam(v(s * 1.7F + dx, -0.6F, -0.3F), v(s * 1.7F + dx, 1.0F, -0.3F), 0.09F, 0.09F, DARK_GREY);
			}
			b.revolve(DARK_GREY, v(s * 2.45F, 1.2F, -0.3F), v(0, -1, 0), new float[][] {
				{0.0F, 0.0F}, {0.0F, 0.3F}, {0.1F, 0.36F}, {1.8F, 0.36F}, {1.85F, 0.0F}
			}, 10);
			// main gear: strut and wheel
			b.box(s * 1.0F - 0.06F, 2.2F, -1.6F, s * 1.0F + 0.06F, 2.4F, -0.8F, STEEL);
			b.box(s * 1.0F - 0.12F, 2.0F, -1.85F, s * 1.0F + 0.12F, 2.6F, -1.45F, BLACK);
			// stabilator at the tail
			surface(b, BOMB, v(s * 0.2F, -8.5F, 0.55F), v(s * 0.2F, -9.3F, 0.55F), 0.1F,
				v(s * 1.6F, -8.7F, 0.55F), v(s * 1.6F, -9.3F, 0.55F), 0.06F, up);
		}
		// fin, tail wheel, mast, chin turret with chain gun, nose sensors (TADS/PNVS)
		b.hexa(BOMB,
			v(-0.08F, -8.0F, 0.6F), v(-0.08F, -9.3F, 0.6F), v(0.08F, -9.3F, 0.6F), v(0.08F, -8.0F, 0.6F),
			v(-0.06F, -8.9F, 2.7F), v(-0.06F, -9.8F, 2.7F), v(0.06F, -9.8F, 2.7F), v(0.06F, -8.9F, 2.7F));
		b.box(-0.05F, -8.6F, -0.3F, 0.05F, -8.4F, 0.4F, STEEL);
		b.box(-0.12F, -8.65F, -0.45F, 0.12F, -8.35F, -0.25F, BLACK);
		b.revolve(DARK_GREY, v(0.0F, -0.2F, 1.2F), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.0F, 0.3F}, {1.1F, 0.22F}, {1.25F, 0.0F}}, 10);
		b.box(-0.28F, 2.9F, -1.35F, 0.28F, 3.5F, -0.95F, DARK_GREY);
		b.beam(v(0.0F, 3.4F, -1.2F), v(0.0F, 5.0F, -1.25F), 0.07F, 0.07F, BLACK);
		b.box(-0.35F, 5.0F, -0.55F, 0.35F, 5.6F, 0.05F, BLACK);
		b.box(-0.22F, 5.55F, -0.45F, 0.22F, 5.62F, -0.1F, GLASS);
		return b.build();
	}

	/** Propeller with {@code blades} blades of {@code radius}, spinning about the Y axis (in X/Z). */
	private static BoxMesh buildProp(int blades, float radius, float chord) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < blades; i++) {
			float a = Mth.TWO_PI * i / blades;
			float cx = Mth.cos(a);
			float cz = Mth.sin(a);
			// a twisted paddle: wide near the hub, narrow at the tip
			b.hexa(BLACK,
				v(cx * 0.2F - cz * chord * 0.5F, 0.06F, cz * 0.2F + cx * chord * 0.5F), v(cx * 0.2F + cz * chord * 0.5F, -0.06F, cz * 0.2F - cx * chord * 0.5F),
				v(cx * 0.2F + cz * chord * 0.5F, 0.02F, cz * 0.2F - cx * chord * 0.5F), v(cx * 0.2F - cz * chord * 0.5F, 0.1F, cz * 0.2F + cx * chord * 0.5F),
				v(cx * radius - cz * chord * 0.25F, 0.02F, cz * radius + cx * chord * 0.25F), v(cx * radius + cz * chord * 0.25F, -0.02F, cz * radius - cx * chord * 0.25F),
				v(cx * radius + cz * chord * 0.25F, 0.0F, cz * radius - cx * chord * 0.25F), v(cx * radius - cz * chord * 0.25F, 0.04F, cz * radius + cx * chord * 0.25F));
		}
		return b.build();
	}

	/** AH-64 main rotor: four long blades spinning about Z, with the hub. */
	private static BoxMesh buildMainRotor() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 4; i++) {
			float a = Mth.HALF_PI * i;
			float cx = Mth.cos(a);
			float cy = Mth.sin(a);
			float w = 0.28F;
			b.hexa(DARK_GREY,
				v(cx * 0.4F - cy * w, cy * 0.4F + cx * w, -0.04F), v(cx * 0.4F + cy * w, cy * 0.4F - cx * w, -0.04F),
				v(cx * 0.4F + cy * w, cy * 0.4F - cx * w, 0.04F), v(cx * 0.4F - cy * w, cy * 0.4F + cx * w, 0.04F),
				v(cx * 6.3F - cy * w, cy * 6.3F + cx * w, -0.1F), v(cx * 6.3F + cy * w, cy * 6.3F - cx * w, -0.1F),
				v(cx * 6.3F + cy * w, cy * 6.3F - cx * w, -0.05F), v(cx * 6.3F - cy * w, cy * 6.3F + cx * w, -0.05F));
		}
		b.box(-0.35F, -0.35F, -0.1F, 0.35F, 0.35F, 0.25F, STEEL);
		return b.build();
	}

	/** AH-64 tail rotor: four blades in two scissored pairs, spinning about X. */
	private static BoxMesh buildTailRotor() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI + (i % 2 == 0 ? 0.0F : 0.35F);
			float cy = Mth.cos(a);
			float cz = Mth.sin(a);
			float w = 0.11F;
			b.hexa(DARK_GREY,
				v(-0.02F, cy * 0.1F - cz * w, cz * 0.1F + cy * w), v(-0.02F, cy * 0.1F + cz * w, cz * 0.1F - cy * w),
				v(0.02F, cy * 0.1F + cz * w, cz * 0.1F - cy * w), v(0.02F, cy * 0.1F - cz * w, cz * 0.1F + cy * w),
				v(-0.02F, cy * 1.35F - cz * w, cz * 1.35F + cy * w), v(-0.02F, cy * 1.35F + cz * w, cz * 1.35F - cy * w),
				v(0.02F, cy * 1.35F + cz * w, cz * 1.35F - cy * w), v(0.02F, cy * 1.35F - cz * w, cz * 1.35F + cy * w));
		}
		return b.build();
	}

	/** A-10: the airframe, plus muzzle flash and tracer stream while the cannon fires. */
	private void submitWarthog(State state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> WARTHOG.emit(pose, consumer, light));
		if (!state.firing) {
			return;
		}
		float t = state.time;
		float flash = 0.35F + 0.2F * Mth.sin(t * 11.0F);
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-flash, 8.55F, -0.25F - flash, flash, 8.75F + flash * 3.0F, -0.25F + flash, YELLOW);
		// tracers: every fifth round glows, a dashed stream racing ahead
		float spacing = 9.0F;
		float travel = (t * 22.0F) % spacing;
		for (float y = 10.0F + travel; y < 160.0F; y += spacing) {
			float wob = Mth.sin(y * 0.7F + t) * 0.08F * (y / 40.0F);
			b.box(-0.07F + wob, y, -0.32F - wob, 0.07F + wob, y + 3.0F, -0.18F - wob, YELLOW);
		}
		BoxMesh glow = b.build();
		collector.submitCustomGeometry(poseStack, GLOW_TYPE, (pose, consumer) -> glow.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		poseStack.pushPose();
		poseStack.translate(0.0F, 8.6F, -0.25F);
		poseStack.mulPose(new Quaternionf().rotationX(Mth.PI));
		collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> MissileRenderer.flame(pose, consumer, 0.3F, 1.6F * flash / 0.35F, t, 1.0F));
		poseStack.popPose();
	}

	/** A bell of condensed vapour that starts behind the canopy and flares out past the wings. */
	static void vaporCone(PoseStack.Pose pose, VertexConsumer consumer, int alpha, float time) {
		final int segments = 24;
		final int rings = 6;
		for (int ring = 0; ring < rings; ring++) {
			float f0 = (float) ring / rings;
			float f1 = (float) (ring + 1) / rings;
			float y0 = 2.2F - f0 * 6.0F;
			float y1 = 2.2F - f1 * 6.0F;
			float r0 = 1.0F + 4.6F * f0 * f0 + 0.05F * Mth.sin(time * 3.0F + ring);
			float r1 = 1.0F + 4.6F * f1 * f1 + 0.05F * Mth.sin(time * 3.0F + ring + 1);
			// thickest just behind the leading edge, fading towards the open end
			int a0 = (int) (alpha * Mth.sin(Mth.PI * Math.min(1.0F, f0 * 1.4F + 0.1F)));
			int a1 = (int) (alpha * Mth.sin(Mth.PI * Math.min(1.0F, f1 * 1.4F + 0.1F)));
			for (int s = 0; s < segments; s++) {
				float b0 = Mth.TWO_PI * s / segments;
				float b1 = Mth.TWO_PI * (s + 1) / segments;
				float u0 = (float) s / segments;
				float u1 = (float) (s + 1) / segments;
				vertex(consumer, pose, Mth.cos(b0) * r0, y0, Mth.sin(b0) * r0, u0, f0, a0, Mth.cos(b0), Mth.sin(b0));
				vertex(consumer, pose, Mth.cos(b1) * r0, y0, Mth.sin(b1) * r0, u1, f0, a0, Mth.cos(b1), Mth.sin(b1));
				vertex(consumer, pose, Mth.cos(b1) * r1, y1, Mth.sin(b1) * r1, u1, f1, a1, Mth.cos(b1), Mth.sin(b1));
				vertex(consumer, pose, Mth.cos(b0) * r1, y1, Mth.sin(b0) * r1, u0, f1, a1, Mth.cos(b0), Mth.sin(b0));
			}
		}
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int alpha, float nx, float nz) {
		consumer.addVertex(pose, x, y, z)
			.setColor(Math.max(0, Math.min(255, alpha)) << 24 | 0xFFFFFF)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightTexture.FULL_BRIGHT)
			.setNormal(pose, nx, 0.0F, nz);
	}

	// ------------------------------------------------------------------ geometry

	static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/**
	 * Lofts a body through cross-sections {y, bottomHalfWidth, bottomZ, topHalfWidth, topZ}: each pair
	 * of neighbouring sections becomes one six-sided solid with trapezoid ends.
	 */
	static void loft(BoxMesh.Builder b, int patch, float[]... sections) {
		for (int i = 0; i + 1 < sections.length; i++) {
			float[] a = sections[i];
			float[] c = sections[i + 1];
			b.hexa(patch,
				v(-a[1], a[0], a[2]), v(a[1], a[0], a[2]), v(a[3], a[0], a[4]), v(-a[3], a[0], a[4]),
				v(-c[1], c[0], c[2]), v(c[1], c[0], c[2]), v(c[3], c[0], c[4]), v(-c[3], c[0], c[4]));
		}
	}

	/**
	 * Flat surface (wing, stabilator, fin) between a root chord and a tip chord. Root: leading edge
	 * {@code rl}, trailing edge {@code rt} (points at mid-thickness), thickness {@code rth} along
	 * {@code up}; same for the tip.
	 */
	static void surface(BoxMesh.Builder b, int patch, Vector3f rl, Vector3f rt, float rth, Vector3f tl, Vector3f tt, float tth, Vector3f up) {
		Vector3f ru = new Vector3f(up).mul(rth * 0.5F);
		Vector3f tu = new Vector3f(up).mul(tth * 0.5F);
		b.hexa(patch,
			new Vector3f(rl).sub(ru), new Vector3f(rt).sub(ru), new Vector3f(rt).add(ru), new Vector3f(rl).add(ru),
			new Vector3f(tl).sub(tu), new Vector3f(tt).sub(tu), new Vector3f(tt).add(tu), new Vector3f(tl).add(tu));
	}

	private static BoxMesh buildAirframe() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		// upper fuselage (chine line at z = 0) and lower fuselage: together a diamond cross-section
		loft(b, GREY,
			new float[] {7.0F, 0.03F, 0.0F, 0.01F, 0.03F},
			new float[] {5.6F, 0.42F, 0.0F, 0.2F, 0.36F},
			new float[] {3.6F, 0.6F, 0.0F, 0.3F, 0.55F},
			new float[] {1.2F, 0.78F, 0.0F, 0.45F, 0.62F},
			new float[] {-0.8F, 1.28F, 0.0F, 0.82F, 0.62F},
			new float[] {-4.6F, 1.28F, 0.0F, 0.82F, 0.56F},
			new float[] {-6.3F, 1.0F, 0.0F, 0.7F, 0.45F});
		loft(b, GREY,
			new float[] {7.0F, 0.01F, -0.03F, 0.03F, 0.0F},
			new float[] {5.6F, 0.2F, -0.3F, 0.42F, 0.0F},
			new float[] {3.6F, 0.34F, -0.44F, 0.6F, 0.0F},
			new float[] {1.2F, 0.62F, -0.56F, 0.78F, 0.0F},
			new float[] {-0.8F, 0.92F, -0.6F, 1.28F, 0.0F},
			new float[] {-4.6F, 0.92F, -0.56F, 1.28F, 0.0F},
			new float[] {-6.3F, 0.8F, -0.45F, 1.0F, 0.0F});
		// radome tip
		loft(b, RADOME,
			new float[] {7.6F, 0.005F, -0.005F, 0.005F, 0.005F},
			new float[] {7.0F, 0.03F, -0.03F, 0.03F, 0.03F});
		// bubble canopy with its frame bow
		loft(b, GLASS,
			new float[] {4.5F, 0.05F, 0.48F, 0.02F, 0.5F},
			new float[] {3.8F, 0.27F, 0.52F, 0.14F, 0.86F},
			new float[] {2.3F, 0.32F, 0.6F, 0.18F, 0.95F},
			new float[] {0.8F, 0.26F, 0.62F, 0.06F, 0.68F});
		loft(b, BLACK,
			new float[] {2.42F, 0.33F, 0.6F, 0.19F, 0.96F},
			new float[] {2.32F, 0.33F, 0.6F, 0.19F, 0.96F});
		for (float s : new float[] {-1.0F, 1.0F}) {
			// caret intake with its dark mouth
			b.hexa(GREY,
				v(s * 0.7F, 2.5F, -0.5F), v(s * 1.32F, 1.9F, -0.5F), v(s * 1.32F, 1.9F, 0.32F), v(s * 0.7F, 2.5F, 0.32F),
				v(s * 0.78F, -1.0F, -0.58F), v(s * 1.3F, -1.0F, -0.58F), v(s * 1.3F, -1.0F, 0.4F), v(s * 0.78F, -1.0F, 0.4F));
			b.hexa(INTAKE,
				v(s * 0.76F, 2.46F, -0.44F), v(s * 1.26F, 1.96F, -0.44F), v(s * 1.26F, 1.96F, 0.26F), v(s * 0.76F, 2.46F, 0.26F),
				v(s * 0.76F, 2.4F, -0.44F), v(s * 1.26F, 1.9F, -0.44F), v(s * 1.26F, 1.9F, 0.26F), v(s * 0.76F, 2.4F, 0.26F));
			// trapezoid wing, slight anhedral, thin at the tip
			surface(b, GREY, v(s * 1.2F, 1.0F, 0.0F), v(s * 1.2F, -4.5F, 0.0F), 0.18F,
				v(s * 5.3F, -3.0F, -0.08F), v(s * 5.3F, -4.6F, -0.08F), 0.05F, up);
			// flaperons in a darker shade
			surface(b, DARK_GREY, v(s * 1.25F, -4.45F, 0.0F), v(s * 1.25F, -4.85F, 0.0F), 0.1F,
				v(s * 4.6F, -4.5F, -0.07F), v(s * 4.6F, -4.85F, -0.07F), 0.04F, up);
			// all-moving stabilators
			surface(b, GREY, v(s * 1.0F, -4.5F, 0.0F), v(s * 1.0F, -6.7F, 0.0F), 0.12F,
				v(s * 3.4F, -6.1F, 0.0F), v(s * 3.4F, -7.0F, 0.0F), 0.04F, up);
			// canted twin tails (leaning out about 27 degrees) with rudders and a tail marking
			Vector3f finNormal = v(s * 0.9F, 0, -0.45F).normalize();
			surface(b, GREY, v(s * 0.85F, -3.4F, 0.56F), v(s * 0.85F, -6.3F, 0.5F), 0.12F,
				v(s * 1.75F, -5.4F, 3.0F), v(s * 1.75F, -6.8F, 3.0F), 0.05F, finNormal);
			surface(b, DARK_GREY, v(s * 0.86F, -6.0F, 0.52F), v(s * 0.86F, -6.4F, 0.5F), 0.13F,
				v(s * 1.7F, -6.5F, 2.8F), v(s * 1.7F, -6.85F, 2.8F), 0.06F, finNormal);
			surface(b, MARKING, v(s * 1.18F, -5.0F, 1.3F), v(s * 1.18F, -5.8F, 1.3F), 0.14F,
				v(s * 1.4F, -5.5F, 2.0F), v(s * 1.4F, -6.2F, 2.0F), 0.08F, finNormal);
			// wingtip nav light
			b.box(s < 0 ? -5.34F : 5.26F, -3.5F, -0.14F, s < 0 ? -5.26F : 5.34F, -3.2F, -0.03F, s < 0 ? RED : GREEN);
			// flat thrust-vectoring nozzle with its dark exit
			float x = s * 0.5F;
			b.hexa(STEEL,
				v(x - 0.42F, -6.3F, -0.36F), v(x + 0.42F, -6.3F, -0.36F), v(x + 0.42F, -6.3F, 0.36F), v(x - 0.42F, -6.3F, 0.36F),
				v(x - 0.4F, -7.0F, -0.24F), v(x + 0.4F, -7.0F, -0.24F), v(x + 0.4F, -7.0F, 0.24F), v(x - 0.4F, -7.0F, 0.24F));
			b.box(x - 0.34F, -7.06F, -0.18F, x + 0.34F, -6.98F, 0.18F, BLACK);
		}
		// panel lines, antennas, refuelling door, formation light strips
		b.box(-0.84F, -2.2F, 0.6F, 0.84F, -2.1F, 0.64F, PANEL);
		b.box(-0.6F, 0.4F, 0.6F, 0.6F, 0.5F, 0.66F, PANEL);
		b.box(-0.1F, -1.4F, 0.62F, 0.1F, -0.9F, 0.67F, DARK_GREY);
		b.box(-0.03F, -3.2F, 0.6F, 0.03F, -2.6F, 0.74F, BLACK);
		b.box(1.1F, -1.5F, 0.0F, 1.3F, 0.5F, 0.04F, YELLOW);
		b.box(-1.3F, -1.5F, 0.0F, -1.1F, 0.5F, 0.04F, YELLOW);
		return b.build();
	}

	/**
	 * A-10 Thunderbolt II: straight low wing with wheel pods, the twin turbofans high on the rear
	 * fuselage, twin tails on a high stabiliser, the GAU-8 muzzle under the nose and a full load of
	 * stores under the wings.
	 */
	private static BoxMesh buildWarthog() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		loft(b, GREY,
			new float[] {8.2F, 0.2F, -0.35F, 0.15F, 0.15F},
			new float[] {7.0F, 0.5F, -0.65F, 0.45F, 0.45F},
			new float[] {4.6F, 0.68F, -0.8F, 0.55F, 0.8F},
			new float[] {1.0F, 0.72F, -0.85F, 0.6F, 0.78F},
			new float[] {-3.0F, 0.6F, -0.65F, 0.5F, 0.62F},
			new float[] {-6.6F, 0.36F, -0.38F, 0.3F, 0.42F},
			new float[] {-7.9F, 0.18F, -0.2F, 0.14F, 0.26F});
		loft(b, GLASS,
			new float[] {6.6F, 0.3F, 0.55F, 0.12F, 0.62F},
			new float[] {5.6F, 0.42F, 0.72F, 0.3F, 1.25F},
			new float[] {4.0F, 0.42F, 0.78F, 0.28F, 1.2F},
			new float[] {3.0F, 0.3F, 0.78F, 0.1F, 0.85F});
		b.beam(v(0.05F, 7.6F, -0.28F), v(0.05F, 8.7F, -0.28F), 0.18F, 0.18F, BLACK); // GAU-8 muzzle
		for (float s : new float[] {-1.0F, 1.0F}) {
			// straight wing with a slight dihedral on the outer panel
			surface(b, GREY, v(s * 0.6F, 1.9F, -0.55F), v(s * 0.6F, -1.8F, -0.55F), 0.32F,
				v(s * 3.6F, 1.6F, -0.55F), v(s * 3.6F, -1.4F, -0.55F), 0.26F, up);
			surface(b, GREY, v(s * 3.6F, 1.6F, -0.55F), v(s * 3.6F, -1.4F, -0.55F), 0.26F,
				v(s * 8.7F, 0.95F, -0.2F), v(s * 8.7F, -0.45F, -0.2F), 0.12F, up);
			surface(b, DARK_GREY, v(s * 3.7F, -1.35F, -0.55F), v(s * 3.7F, -1.75F, -0.55F), 0.1F,
				v(s * 8.5F, -0.4F, -0.2F), v(s * 8.5F, -0.7F, -0.2F), 0.06F, up); // ailerons and split-flap airbrakes
			// main gear pods
			b.hexa(GREY,
				v(s * 1.75F, 1.5F, -0.95F), v(s * 2.35F, 1.5F, -0.95F), v(s * 2.35F, 1.5F, -0.6F), v(s * 1.75F, 1.5F, -0.6F),
				v(s * 1.8F, -0.9F, -0.85F), v(s * 2.3F, -0.9F, -0.85F), v(s * 2.3F, -0.9F, -0.6F), v(s * 1.8F, -0.9F, -0.6F));
			// turbofan nacelle on its pylon, dark intake, exhaust
			float nx = s * 1.25F;
			b.revolve(GREY, v(nx, -0.9F, 1.05F), v(0, -1, 0), new float[][] {
				{0.0F, 0.0F}, {0.0F, 0.5F}, {0.3F, 0.58F}, {2.0F, 0.55F}, {2.9F, 0.4F}, {3.0F, 0.0F}
			}, 16);
			b.revolve(INTAKE, v(nx, -0.88F, 1.05F), v(0, -1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.47F}, {0.02F, 0.47F}, {0.02F, 0.0F}}, 16);
			b.box(nx - 0.08F, -2.6F, 0.55F, nx + 0.08F, -1.6F, 0.75F, DARK_GREY);
			// tailplane and twin fins at its tips
			surface(b, GREY, v(s * 0.3F, -6.3F, 0.35F), v(s * 0.3F, -7.8F, 0.35F), 0.14F,
				v(s * 3.2F, -6.5F, 0.35F), v(s * 3.2F, -7.7F, 0.35F), 0.1F, up);
			b.hexa(GREY,
				v(s * 3.15F, -6.2F, 0.0F), v(s * 3.15F, -7.9F, 0.0F), v(s * 3.25F, -7.9F, 0.0F), v(s * 3.25F, -6.2F, 0.0F),
				v(s * 3.15F, -6.7F, 2.2F), v(s * 3.15F, -7.8F, 2.2F), v(s * 3.25F, -7.8F, 2.2F), v(s * 3.25F, -6.7F, 2.2F));
			// stores: Mavericks and bombs on the wing pylons
			for (float x : new float[] {2.9F, 4.4F, 5.9F}) {
				float px = s * x;
				b.box(px - 0.05F, 0.6F, -0.85F, px + 0.05F, -0.2F, -0.6F, DARK_GREY);
				b.beam(v(px, -1.1F, -1.05F), v(px, 1.3F, -1.05F), x > 5.0F ? 0.3F : 0.36F, x > 5.0F ? 0.3F : 0.36F, x > 5.0F ? WHITE : BOMB);
			}
		}
		b.box(-0.55F, 2.0F, 0.75F, 0.55F, 2.4F, 0.79F, MARKING);
		b.box(-0.62F, -4.5F, -0.2F, -0.6F, -3.8F, 0.3F, MARKING);
		return b.build();
	}

	/**
	 * B-2 Spirit: a flying wing - straight swept leading edges, the double-W sawtooth trailing edge,
	 * a blended centre body with the cockpit, and the engine inlets and exhausts buried in the top.
	 */
	private static BoxMesh buildSpirit() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float[] xs = {0.0F, 2.5F, 5.2F, 8.2F, 12.0F};
		float[] te = {-3.6F, -1.4F, -3.9F, -1.3F, -4.3F};
		float[] thick = {1.0F, 0.8F, 0.45F, 0.28F, 0.1F};
		for (float s : new float[] {-1.0F, 1.0F}) {
			for (int i = 0; i + 1 < xs.length; i++) {
				float x0 = xs[i] * s;
				float x1 = xs[i + 1] * s;
				float le0 = 5.2F - xs[i] * 0.72F;
				float le1 = 5.2F - xs[i + 1] * 0.72F;
				float h0 = thick[i] * 0.5F;
				float h1 = thick[i + 1] * 0.5F;
				// upper and lower halves: a sharp leading edge, thickest a third of the way back
				float m0 = le0 + (te[i] - le0) * 0.35F;
				float m1 = le1 + (te[i + 1] - le1) * 0.35F;
				b.hexa(DARK_GREY,
					v(x0, le0, 0.0F), v(x0, m0, 0.0F), v(x0, m0, h0), v(x0, le0, 0.02F),
					v(x1, le1, 0.0F), v(x1, m1, 0.0F), v(x1, m1, h1), v(x1, le1, 0.02F));
				b.hexa(DARK_GREY,
					v(x0, m0, 0.0F), v(x0, te[i], 0.0F), v(x0, te[i], 0.03F), v(x0, m0, h0),
					v(x1, m1, 0.0F), v(x1, te[i + 1], 0.0F), v(x1, te[i + 1], 0.03F), v(x1, m1, h1));
				b.hexa(DARK_GREY,
					v(x0, le0, -0.02F), v(x0, m0, -h0 * 0.6F), v(x0, m0, 0.0F), v(x0, le0, 0.0F),
					v(x1, le1, -0.02F), v(x1, m1, -h1 * 0.6F), v(x1, m1, 0.0F), v(x1, le1, 0.0F));
				b.hexa(DARK_GREY,
					v(x0, m0, -h0 * 0.6F), v(x0, te[i], -0.02F), v(x0, te[i], 0.0F), v(x0, m0, 0.0F),
					v(x1, m1, -h1 * 0.6F), v(x1, te[i + 1], -0.02F), v(x1, te[i + 1], 0.0F), v(x1, m1, 0.0F));
			}
			// buried engine inlets (with their saw-tooth splitters) and the exhaust troughs
			float ix = s * 2.6F;
			b.hexa(DARK_GREY,
				v(ix - 0.9F, 2.2F, 0.4F), v(ix + 0.9F, 2.2F, 0.4F), v(ix + 0.7F, 2.2F, 0.72F), v(ix - 0.7F, 2.2F, 0.72F),
				v(ix - 0.9F, -0.4F, 0.42F), v(ix + 0.9F, -0.4F, 0.42F), v(ix + 0.7F, -0.4F, 0.62F), v(ix - 0.7F, -0.4F, 0.62F));
			b.box(ix - 0.62F, 2.18F, 0.42F, ix + 0.62F, 2.22F, 0.68F, INTAKE);
			b.box(ix - 0.8F, -2.6F, 0.25F, ix + 0.8F, -0.6F, 0.32F, BLACK);
			// wingtip split rudders
			b.box(s * 11.2F, -4.0F, -0.04F, s * 11.9F, -3.2F, 0.04F, PANEL);
		}
		// blended centre body and the cockpit with its four windows
		loft(b, DARK_GREY,
			new float[] {5.3F, 0.05F, 0.0F, 0.02F, 0.05F},
			new float[] {3.8F, 1.6F, 0.0F, 0.9F, 0.75F},
			new float[] {1.5F, 1.7F, 0.0F, 1.0F, 0.95F},
			new float[] {-1.5F, 1.4F, 0.0F, 0.8F, 0.7F},
			new float[] {-3.4F, 0.6F, 0.0F, 0.3F, 0.15F});
		b.hexa(BLACK,
			v(-0.7F, 3.55F, 0.7F), v(0.7F, 3.55F, 0.7F), v(0.55F, 3.2F, 0.84F), v(-0.55F, 3.2F, 0.84F),
			v(-0.7F, 3.5F, 0.72F), v(0.7F, 3.5F, 0.72F), v(0.55F, 3.15F, 0.86F), v(-0.55F, 3.15F, 0.86F));
		b.box(-1.2F, 0.0F, 0.94F, 1.2F, 0.08F, 0.96F, PANEL);
		return b.build();
	}

	/** B-2 weapons bay doors hanging open under the centre body, the rotary launcher inside. */
	private static BoxMesh buildSpiritBay() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float s : new float[] {-1.0F, 1.0F}) {
			b.box(s < 0 ? -1.3F : 1.24F, -1.8F, -1.0F, s < 0 ? -1.24F : 1.3F, 2.0F, -0.1F, DARK_GREY);
		}
		b.box(-1.2F, -1.8F, -0.12F, 1.2F, 2.0F, -0.08F, BLACK);
		b.beam(v(0, -1.6F, -0.5F), v(0, 1.8F, -0.5F), 0.75F, 0.75F, BOMB); // the MOAB in the bay
		return b.build();
	}

	/** Main weapons bay doors, swung down to vertical on both sides of the bay. */
	private static BoxMesh buildBayDoors() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float s : new float[] {-1.0F, 1.0F}) {
			b.box(s < 0 ? -0.66F : 0.6F, -3.6F, -1.35F, s < 0 ? -0.6F : 0.66F, 0.6F, -0.56F, DARK_GREY);
			b.box(s < 0 ? -0.6F : 0.56F, -3.5F, -1.3F, s < 0 ? -0.56F : 0.6F, 0.5F, -0.6F, YELLOW);
		}
		b.box(-0.58F, -3.6F, -0.6F, 0.58F, 0.6F, -0.55F, BLACK); // bay roof
		return b.build();
	}

	/** Bombs in two rows of four, hanging in the open bay; listed in release order (last first). */
	private static BoxMesh[] buildBombs() {
		BoxMesh[] bombs = new BoxMesh[8];
		int i = 0;
		for (float y : new float[] {-2.0F, -0.2F}) {
			for (float x : new float[] {-0.4F, -0.14F, 0.14F, 0.4F}) {
				BoxMesh.Builder b = new BoxMesh.Builder();
				b.beam(v(x, y - 0.8F, -0.82F), v(x, y + 0.5F, -0.82F), 0.2F, 0.2F, BOMB);
				b.beam(v(x, y + 0.5F, -0.82F), v(x, y + 0.75F, -0.82F), 0.11F, 0.11F, BOMB);
				b.beam(v(x, y + 0.25F, -0.82F), v(x, y + 0.32F, -0.82F), 0.22F, 0.22F, YELLOW);
				b.box(x - 0.12F, y - 0.8F, -0.84F, x + 0.12F, y - 0.6F, -0.8F, DARK_GREY);
				b.box(x - 0.02F, y - 0.8F, -0.94F, x + 0.02F, y - 0.6F, -0.7F, DARK_GREY);
				b.box(x - 0.02F, y - 0.2F, -0.72F, x + 0.02F, y, -0.56F, STEEL); // ejector rack
				bombs[i++] = b.build();
			}
		}
		return bombs;
	}
}
