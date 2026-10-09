package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.JetRenderer.loft;
import static de.rcm.ballistic.client.render.JetRenderer.surface;
import static de.rcm.ballistic.client.render.JetRenderer.v;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.FighterEntity;
import de.rcm.ballistic.entity.FighterType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The F-22A Raptor and F-35A Lightning II, built to their real dimensions (1 block = 1 m).
 * <p>
 * <b>F-22A</b> (18.9 m, span 13.6 m): diamond-section fuselage with a sharp chine all round, the long
 * frameless gold-tinted bubble canopy, caret intakes raked in two planes, the diamond wing (42 degrees
 * leading-edge sweep, trailing edge swept forward), all-moving stabilators, twin tails canted out 28
 * degrees, and the two flat 2D thrust-vectoring nozzles between the tail booms.
 * <p>
 * <b>F-35A</b> (15.7 m, span 10.7 m): a shorter, deeper fuselage, the two-piece canopy with its frame
 * behind the pilot, diverterless (DSI) bump intakes, the faceted EOTS window under the nose, the gun
 * fairing over the left intake, a trapezoid wing, canted tails and the single round F135 nozzle with
 * its serrated edge.
 * <p>
 * Both: landing gear that is down on the ground and in the circuit, weapons-bay doors that swing open
 * with the missiles inside when one is fired, afterburner plumes, a vapour cone near Mach 1. The
 * canopy is drawn translucent so the pilot can see out.
 */
public class FighterRenderer extends EntityRenderer<FighterEntity, FighterRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/fighter_jets.png"));
	private static final RenderType GLASS_TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/fighter_jets.png"));

	// atlas patches (textures/entity/fighter_jets.png, 8x8 of 16 px)
	static final int F22_GREY = 0;
	static final int F22_DARK = 1;
	static final int F22_GLASS = 2;
	static final int STEEL = 3;
	static final int BLACK = 4;
	static final int RADOME = 5;
	static final int INTAKE = 6;
	static final int RED = 7;
	static final int GREEN = 8;
	static final int F35_GREY = 9;
	static final int F35_DARK = 10;
	static final int F35_GLASS = 11;
	static final int TYRE = 12;
	static final int STRUT = 13;
	static final int MISSILE = 14;
	static final int INSIGNIA = 15;
	static final int F22_EDGE = 16;
	static final int SOOT = 18;
	static final int EOTS = 19;

	/** Height of the jet's centreline above the ground when it stands on its gear. */
	public static final float GEAR_HEIGHT = 2.3F;

	private static final BoxMesh F22 = buildF22();
	private static final BoxMesh F22_CANOPY = buildF22Canopy();
	private static final BoxMesh F22_GEAR = buildF22Gear();
	private static final BoxMesh F22_BAY = buildF22Bay();
	private static final BoxMesh F35 = buildF35();
	private static final BoxMesh F35_CANOPY = buildF35Canopy();
	private static final BoxMesh F35_GEAR = buildF35Gear();
	private static final BoxMesh F35_BAY = buildF35Bay();

	public FighterRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public FighterType type = FighterType.F22;
		public final Quaternionf rotation = new Quaternionf();
		public boolean afterburner;
		public float throttle;
		public float mach;
		public float time;
		public boolean gear;
		public boolean bay;
		public boolean firing;
		public boolean crashing;
		public boolean ownCockpit;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(FighterEntity entity) {
		return entity.getBoundingBox().inflate(12.0);
	}

	@Override
	public void extractRenderState(FighterEntity jet, State state, float partialTick) {
		super.extractRenderState(jet, state, partialTick);
		MissileRenderer.orient(state.rotation, jet.forward(partialTick));
		state.rotation.rotateY(jet.getRoll(partialTick) * Mth.DEG_TO_RAD);
		state.type = jet.type();
		state.afterburner = jet.isAfterburner();
		state.throttle = jet.throttle();
		state.mach = jet.mach();
		state.time = jet.tickCount + partialTick;
		state.gear = jet.gearDown();
		state.bay = jet.bayOpen();
		state.firing = jet.isFiring();
		state.crashing = jet.isCrashing();
		Minecraft mc = Minecraft.getInstance();
		state.ownCockpit = mc.player != null && jet.pilot() == mc.player && mc.options.getCameraType().isFirstPerson();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		boolean f22 = state.type == FighterType.F22;
		poseStack.pushPose();
		poseStack.translate(0.0F, GEAR_HEIGHT, 0.0F);
		poseStack.mulPose(state.rotation);
		BoxMesh body = f22 ? F22 : F35;
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> body.emit(pose, consumer, light));
		if (state.gear) {
			BoxMesh gear = f22 ? F22_GEAR : F35_GEAR;
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> gear.emit(pose, consumer, light));
		}
		if (state.bay) {
			BoxMesh bay = f22 ? F22_BAY : F35_BAY;
			collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> bay.emit(pose, consumer, light));
		}
		// the canopy: tinted glass you can see through (more so from inside)
		BoxMesh canopy = f22 ? F22_CANOPY : F35_CANOPY;
		int glass = state.ownCockpit ? 0x30FFFFFF : 0xA8FFFFFF;
		collector.submitCustomGeometry(poseStack, GLASS_TYPE, (pose, consumer) -> canopy.emit(pose, consumer, light, glass));

		// engine plumes: a faint heat glow in military power, long shock-diamond flames in afterburner
		if (!state.crashing) {
			float t = state.time;
			float flicker = 0.85F + 0.15F * Mth.sin(t * 2.3F) * Mth.cos(t * 3.7F);
			float radius = f22 ? (state.afterburner ? 0.4F : 0.28F) : state.afterburner ? 0.55F : 0.4F;
			float length = (state.afterburner ? (f22 ? 7.5F : 8.5F) : 0.6F + state.throttle * 0.9F) * flicker;
			float alpha = state.afterburner ? 1.0F : 0.25F + 0.35F * state.throttle;
			float[] xs = f22 ? new float[] {-0.62F, 0.62F} : new float[] {0.0F};
			float exit = f22 ? -9.25F : -7.7F;
			for (float x : xs) {
				float phase = x * 11.0F;
				poseStack.pushPose();
				poseStack.translate(x, exit, 0.0F);
				collector.submitCustomGeometry(poseStack, JetRenderer.FLAME_TYPE, (pose, consumer) -> {
					MissileRenderer.flame(pose, consumer, radius, length, t + phase, alpha);
					MissileRenderer.flame(pose, consumer, radius * 0.55F, length * 0.7F, t + phase + 5.0F, Math.min(1.0F, alpha * 1.4F));
				});
				poseStack.popPose();
			}
			// cannon muzzle flash at the gun port
			if (state.firing) {
				poseStack.pushPose();
				poseStack.translate(f22 ? 1.05F : -0.75F, f22 ? 4.6F : 1.9F, 0.75F);
				poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
				collector.submitCustomGeometry(poseStack, JetRenderer.FLAME_TYPE,
					(pose, consumer) -> MissileRenderer.flame(pose, consumer, 0.18F, 1.0F + (t * 7.0F % 1.0F), t * 3.0F, 1.0F));
				poseStack.popPose();
			}
			// Prandtl-Glauert condensation cone around Mach 1
			float vapor = Mth.clamp(1.0F - Math.abs(state.mach - 1.0F) / 0.12F, 0.0F, 1.0F);
			if (vapor > 0.02F) {
				int a = (int) (vapor * (0.55F + 0.1F * Mth.sin(t * 1.7F)) * 255.0F);
				poseStack.pushPose();
				poseStack.scale(1.3F, 1.3F, 1.3F);
				collector.submitCustomGeometry(poseStack, JetRenderer.VAPOR_TYPE, (pose, consumer) -> JetRenderer.vaporCone(pose, consumer, a, t));
				poseStack.popPose();
			}
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/** Model-space position of the gun muzzle and the engine exits, for effects (x right, y forward, z up). */
	public static Vec3 nozzle(FighterType type, int i) {
		return type == FighterType.F22 ? new Vec3(i == 0 ? -0.62 : 0.62, -9.3, 0.0) : new Vec3(0.0, -7.75, 0.0);
	}

	// ================================================================== F-22A

	private static BoxMesh buildF22() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		// upper fuselage down to the chine (z = 0), lower fuselage up to it: a diamond cross-section
		loft(b, F22_GREY,
			new float[] {8.6F, 0.3F, 0.0F, 0.15F, 0.28F},
			new float[] {7.3F, 0.55F, 0.0F, 0.28F, 0.48F},
			new float[] {5.6F, 0.75F, 0.0F, 0.4F, 0.68F},
			new float[] {3.5F, 0.98F, 0.0F, 0.56F, 0.8F},
			new float[] {1.5F, 1.7F, 0.0F, 1.0F, 0.82F},
			new float[] {-1.0F, 1.8F, 0.0F, 1.1F, 0.82F},
			new float[] {-5.5F, 1.75F, 0.0F, 1.06F, 0.76F},
			new float[] {-7.6F, 1.38F, 0.0F, 0.92F, 0.62F},
			new float[] {-8.4F, 1.1F, 0.0F, 0.78F, 0.5F});
		loft(b, F22_DARK,
			new float[] {8.6F, 0.15F, -0.25F, 0.3F, 0.0F},
			new float[] {7.3F, 0.28F, -0.45F, 0.55F, 0.0F},
			new float[] {5.6F, 0.42F, -0.62F, 0.75F, 0.0F},
			new float[] {3.5F, 0.72F, -0.76F, 0.98F, 0.0F},
			new float[] {1.5F, 1.32F, -0.82F, 1.7F, 0.0F},
			new float[] {-1.0F, 1.38F, -0.82F, 1.8F, 0.0F},
			new float[] {-5.5F, 1.22F, -0.76F, 1.75F, 0.0F},
			new float[] {-7.6F, 1.02F, -0.62F, 1.38F, 0.0F},
			new float[] {-8.4F, 0.86F, -0.5F, 1.1F, 0.0F});
		// radome: a sharp, chined cone
		loft(b, RADOME,
			new float[] {9.45F, 0.01F, -0.01F, 0.01F, 0.01F},
			new float[] {8.6F, 0.3F, -0.25F, 0.3F, 0.28F});
		// spine behind the canopy, blending into the wing roots
		loft(b, F22_GREY,
			new float[] {3.2F, 0.2F, 0.8F, 0.05F, 0.86F},
			new float[] {1.0F, 0.6F, 0.82F, 0.3F, 0.9F},
			new float[] {-3.5F, 0.6F, 0.8F, 0.25F, 0.86F},
			new float[] {-6.5F, 0.4F, 0.72F, 0.1F, 0.74F});
		for (float s : new float[] {-1.0F, 1.0F}) {
			// caret intakes: raked in two planes, with the dark duct mouth
			b.hexa(F22_GREY,
				v(s * 0.95F, 4.3F, -0.78F), v(s * 1.75F, 3.4F, -0.78F), v(s * 1.75F, 3.6F, 0.36F), v(s * 0.95F, 4.6F, 0.36F),
				v(s * 1.0F, -0.5F, -0.82F), v(s * 1.8F, -0.5F, -0.82F), v(s * 1.8F, -0.5F, 0.6F), v(s * 1.0F, -0.5F, 0.6F));
			b.hexa(INTAKE,
				v(s * 1.02F, 4.25F, -0.7F), v(s * 1.68F, 3.47F, -0.7F), v(s * 1.68F, 3.65F, 0.3F), v(s * 1.02F, 4.52F, 0.3F),
				v(s * 1.02F, 4.15F, -0.7F), v(s * 1.68F, 3.37F, -0.7F), v(s * 1.68F, 3.55F, 0.3F), v(s * 1.02F, 4.42F, 0.3F));
			// diamond wing: 42 degree leading edge, trailing edge swept forward, slight anhedral
			surface(b, F22_GREY, v(s * 1.7F, 2.2F, 0.0F), v(s * 1.7F, -5.6F, 0.0F), 0.32F,
				v(s * 6.78F, -2.5F, -0.3F), v(s * 6.78F, -4.0F, -0.3F), 0.08F, up);
			// leading-edge flap and flaperon / aileron in the darker shade, sawtooth door edges
			surface(b, F22_DARK, v(s * 2.2F, 1.75F, 0.02F), v(s * 2.2F, 1.2F, 0.02F), 0.2F,
				v(s * 6.6F, -2.35F, -0.27F), v(s * 6.6F, -2.75F, -0.27F), 0.06F, up);
			surface(b, F22_DARK, v(s * 1.8F, -5.25F, 0.0F), v(s * 1.8F, -5.75F, 0.0F), 0.14F,
				v(s * 6.4F, -3.75F, -0.28F), v(s * 6.4F, -4.1F, -0.28F), 0.05F, up);
			// all-moving stabilators reaching back past the nozzles
			surface(b, F22_GREY, v(s * 1.3F, -6.3F, 0.0F), v(s * 1.3F, -9.0F, 0.0F), 0.16F,
				v(s * 4.45F, -8.0F, -0.05F), v(s * 4.45F, -9.4F, -0.05F), 0.05F, up);
			// twin tails, canted out 28 degrees, with rudders
			Vector3f fin = v(s * 0.88F, 0, -0.47F).normalize();
			surface(b, F22_GREY, v(s * 1.25F, -4.0F, 0.78F), v(s * 1.25F, -7.6F, 0.72F), 0.16F,
				v(s * 2.63F, -6.4F, 3.4F), v(s * 2.63F, -7.9F, 3.4F), 0.06F, fin);
			surface(b, F22_DARK, v(s * 1.27F, -7.2F, 0.74F), v(s * 1.27F, -7.75F, 0.72F), 0.17F,
				v(s * 2.55F, -7.55F, 3.2F), v(s * 2.55F, -7.95F, 3.2F), 0.07F, fin);
			surface(b, INSIGNIA, v(s * 1.7F, -5.5F, 1.6F), v(s * 1.7F, -6.4F, 1.6F), 0.18F,
				v(s * 2.0F, -6.0F, 2.3F), v(s * 2.0F, -6.9F, 2.3F), 0.08F, fin);
			// side weapons bay doors (closed), wingtip lights
			b.box(s < 0 ? -1.82F : 1.62F, 1.0F, -0.5F, s < 0 ? -1.62F : 1.82F, -0.6F, -0.1F, F22_EDGE);
			b.box(s < 0 ? -6.84F : 6.72F, -3.1F, -0.36F, s < 0 ? -6.72F : 6.84F, -2.8F, -0.24F, s < 0 ? RED : GREEN);
			// flat 2D thrust-vectoring nozzle, with upper and lower paddles and a sooty exit
			float x = s * 0.62F;
			b.hexa(STEEL,
				v(x - 0.48F, -7.6F, -0.42F), v(x + 0.48F, -7.6F, -0.42F), v(x + 0.48F, -7.6F, 0.42F), v(x - 0.48F, -7.6F, 0.42F),
				v(x - 0.44F, -9.05F, -0.27F), v(x + 0.44F, -9.05F, -0.27F), v(x + 0.44F, -9.05F, 0.27F), v(x - 0.44F, -9.05F, 0.27F));
			b.box(x - 0.44F, -9.4F, 0.2F, x + 0.44F, -9.0F, 0.26F, STEEL);
			b.box(x - 0.44F, -9.4F, -0.26F, x + 0.44F, -9.0F, -0.2F, STEEL);
			b.box(x - 0.38F, -9.12F, -0.2F, x + 0.38F, -9.04F, 0.2F, SOOT);
		}
		// main weapons bay doors (closed), panel lines, antennas, gun port
		b.box(-0.75F, 2.0F, -0.86F, 0.75F, -2.8F, -0.8F, F22_EDGE);
		b.box(-0.04F, 2.0F, -0.88F, 0.04F, -2.8F, -0.86F, F22_DARK);
		b.box(-0.9F, -2.2F, 0.8F, 0.9F, -2.1F, 0.84F, F22_DARK);
		b.box(-0.03F, -4.0F, 0.84F, 0.03F, -3.4F, 0.98F, BLACK);
		b.box(0.95F, 4.75F, 0.6F, 1.12F, 4.45F, 0.74F, BLACK);
		return b.build();
	}

	private static BoxMesh buildF22Canopy() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// the long frameless bubble, gold tinted
		loft(b, F22_GLASS,
			new float[] {7.5F, 0.08F, 0.46F, 0.04F, 0.5F},
			new float[] {6.6F, 0.36F, 0.6F, 0.18F, 1.02F},
			new float[] {5.4F, 0.43F, 0.7F, 0.25F, 1.17F},
			new float[] {4.1F, 0.4F, 0.76F, 0.18F, 1.08F},
			new float[] {3.2F, 0.22F, 0.8F, 0.05F, 0.88F});
		return b.build();
	}

	private static BoxMesh buildF22Gear() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// nose gear under the cockpit, mains folding out of the fuselage sides
		gearLeg(b, 0.0F, 5.4F, 0.6F, 0.34F);
		gearLeg(b, -1.55F, -2.2F, 0.75F, 0.46F);
		gearLeg(b, 1.55F, -2.2F, 0.75F, 0.46F);
		// open gear doors
		b.box(-0.32F, 6.1F, -1.25F, -0.28F, 4.9F, -0.7F, F22_EDGE);
		b.box(0.28F, 6.1F, -1.25F, 0.32F, 4.9F, -0.7F, F22_EDGE);
		b.box(-1.95F, -1.5F, -1.5F, -1.9F, -3.0F, -0.7F, F22_EDGE);
		b.box(1.9F, -1.5F, -1.5F, 1.95F, -3.0F, -0.7F, F22_EDGE);
		return b.build();
	}

	private static BoxMesh buildF22Bay() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// main bay open: doors hanging down either side, the bay lit grey, AMRAAMs on their launchers
		b.box(-0.75F, 2.0F, -0.84F, 0.75F, -2.8F, -0.6F, STRUT);
		b.box(-0.8F, 2.0F, -1.7F, -0.74F, -2.8F, -0.86F, F22_EDGE);
		b.box(0.74F, 2.0F, -1.7F, 0.8F, -2.8F, -0.86F, F22_EDGE);
		for (float x : new float[] {-0.45F, 0.0F, 0.45F}) {
			missile(b, x, 1.4F, -1.05F);
		}
		return b.build();
	}

	// ================================================================== F-35A

	private static BoxMesh buildF35() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 0, 1);
		loft(b, F35_GREY,
			new float[] {7.1F, 0.28F, 0.0F, 0.14F, 0.26F},
			new float[] {6.0F, 0.55F, 0.0F, 0.3F, 0.55F},
			new float[] {4.6F, 0.8F, 0.0F, 0.45F, 0.78F},
			new float[] {3.0F, 1.08F, 0.0F, 0.62F, 0.92F},
			new float[] {1.0F, 1.85F, 0.0F, 1.12F, 0.96F},
			new float[] {-1.5F, 1.95F, 0.0F, 1.22F, 0.96F},
			new float[] {-4.5F, 1.78F, 0.0F, 1.1F, 0.86F},
			new float[] {-6.0F, 1.25F, 0.0F, 0.8F, 0.66F},
			new float[] {-6.6F, 0.82F, 0.0F, 0.56F, 0.52F});
		loft(b, F35_DARK,
			new float[] {7.1F, 0.14F, -0.24F, 0.28F, 0.0F},
			new float[] {6.0F, 0.3F, -0.5F, 0.55F, 0.0F},
			new float[] {4.6F, 0.55F, -0.76F, 0.8F, 0.0F},
			new float[] {3.0F, 0.82F, -0.96F, 1.08F, 0.0F},
			new float[] {1.0F, 1.32F, -1.06F, 1.85F, 0.0F},
			new float[] {-1.5F, 1.42F, -1.06F, 1.95F, 0.0F},
			new float[] {-4.5F, 1.3F, -0.96F, 1.78F, 0.0F},
			new float[] {-6.0F, 0.95F, -0.76F, 1.25F, 0.0F},
			new float[] {-6.6F, 0.66F, -0.56F, 0.82F, 0.0F});
		loft(b, RADOME,
			new float[] {7.84F, 0.01F, -0.01F, 0.01F, 0.01F},
			new float[] {7.1F, 0.28F, -0.24F, 0.28F, 0.26F});
		// the deep spine behind the cockpit (over the lift fan bay on the B; fuel on the A)
		loft(b, F35_GREY,
			new float[] {2.6F, 0.2F, 0.92F, 0.06F, 0.98F},
			new float[] {0.5F, 0.7F, 0.96F, 0.4F, 1.08F},
			new float[] {-3.5F, 0.72F, 0.9F, 0.36F, 1.02F},
			new float[] {-6.0F, 0.4F, 0.7F, 0.1F, 0.74F});
		// EOTS: the faceted sensor window under the nose
		b.hexa(EOTS,
			v(-0.22F, 5.9F, -0.62F), v(0.22F, 5.9F, -0.62F), v(0.16F, 5.9F, -0.5F), v(-0.16F, 5.9F, -0.5F),
			v(-0.24F, 5.1F, -0.74F), v(0.24F, 5.1F, -0.74F), v(0.24F, 5.1F, -0.6F), v(-0.24F, 5.1F, -0.6F));
		// gun fairing over the left intake (the A's internal GAU-22/A)
		b.hexa(F35_GREY,
			v(-1.0F, 2.2F, 0.92F), v(-0.62F, 2.2F, 0.92F), v(-0.62F, 2.2F, 0.98F), v(-1.0F, 2.2F, 0.98F),
			v(-1.06F, -1.2F, 0.94F), v(-0.6F, -1.2F, 0.94F), v(-0.6F, -1.2F, 1.16F), v(-1.06F, -1.2F, 1.16F));
		b.box(-0.88F, 2.0F, 0.94F, -0.72F, 1.85F, 1.06F, BLACK);
		for (float s : new float[] {-1.0F, 1.0F}) {
			// DSI: the bump on the fuselage side that does the job of a boundary-layer splitter
			b.hexa(F35_GREY,
				v(s * 0.92F, 3.4F, -0.3F), v(s * 1.06F, 3.4F, -0.3F), v(s * 1.06F, 3.4F, 0.4F), v(s * 0.92F, 3.4F, 0.4F),
				v(s * 0.95F, 2.2F, -0.4F), v(s * 1.32F, 2.2F, -0.4F), v(s * 1.32F, 2.2F, 0.5F), v(s * 0.95F, 2.2F, 0.5F));
			// the intake lips, swept forward and down, with the dark duct mouth
			b.hexa(F35_GREY,
				v(s * 1.05F, 2.9F, -0.6F), v(s * 1.82F, 2.3F, -0.65F), v(s * 1.82F, 2.55F, 0.45F), v(s * 1.05F, 3.2F, 0.5F),
				v(s * 1.25F, -0.5F, -0.85F), v(s * 1.95F, -0.5F, -0.85F), v(s * 1.95F, -0.5F, 0.7F), v(s * 1.25F, -0.5F, 0.7F));
			b.hexa(INTAKE,
				v(s * 1.34F, 2.7F, -0.55F), v(s * 1.76F, 2.33F, -0.58F), v(s * 1.76F, 2.55F, 0.38F), v(s * 1.34F, 2.98F, 0.42F),
				v(s * 1.34F, 2.6F, -0.55F), v(s * 1.76F, 2.23F, -0.58F), v(s * 1.76F, 2.45F, 0.38F), v(s * 1.34F, 2.88F, 0.42F));
			// trapezoid wing (34 degree leading edge), with flaperons
			surface(b, F35_GREY, v(s * 1.9F, 1.2F, -0.1F), v(s * 1.9F, -4.9F, -0.1F), 0.34F,
				v(s * 5.35F, -1.7F, -0.2F), v(s * 5.35F, -3.85F, -0.2F), 0.09F, up);
			surface(b, F35_DARK, v(s * 1.95F, -4.5F, -0.1F), v(s * 1.95F, -5.0F, -0.1F), 0.15F,
				v(s * 4.9F, -3.55F, -0.19F), v(s * 4.9F, -3.95F, -0.19F), 0.06F, up);
			// stabilators
			surface(b, F35_GREY, v(s * 1.2F, -5.0F, 0.0F), v(s * 1.2F, -7.4F, 0.0F), 0.16F,
				v(s * 3.45F, -6.5F, 0.0F), v(s * 3.45F, -7.75F, 0.0F), 0.05F, up);
			// twin tails canted out 25 degrees, rudders, low-visibility insignia
			Vector3f fin = v(s * 0.9F, 0, -0.42F).normalize();
			surface(b, F35_GREY, v(s * 1.3F, -3.6F, 0.82F), v(s * 1.3F, -6.2F, 0.78F), 0.16F,
				v(s * 2.42F, -5.5F, 3.25F), v(s * 2.42F, -6.6F, 3.25F), 0.06F, fin);
			surface(b, F35_DARK, v(s * 1.32F, -5.85F, 0.8F), v(s * 1.32F, -6.3F, 0.78F), 0.17F,
				v(s * 2.36F, -6.3F, 3.05F), v(s * 2.36F, -6.65F, 3.05F), 0.07F, fin);
			surface(b, INSIGNIA, v(s * 1.75F, -4.7F, 1.7F), v(s * 1.75F, -5.4F, 1.7F), 0.18F,
				v(s * 2.0F, -5.2F, 2.3F), v(s * 2.0F, -5.9F, 2.3F), 0.08F, fin);
			b.box(s < 0 ? -5.4F : 5.3F, -2.2F, -0.26F, s < 0 ? -5.3F : 5.4F, -1.9F, -0.14F, s < 0 ? RED : GREEN);
			// the two weapons bay doors (closed)
			b.box(s < 0 ? -1.15F : 0.15F, 1.6F, -1.12F, s < 0 ? -0.15F : 1.15F, -2.6F, -1.04F, F35_DARK);
		}
		// the single round F135 nozzle: tapering, with its serrated edge
		b.revolve(STEEL, v(0, -6.55F, 0), v(0, -1, 0), new float[][] {{0.0F, 0.66F}, {0.6F, 0.62F}, {1.05F, 0.54F}}, 16);
		for (int i = 0; i < 12; i++) {
			double a = i * Math.PI * 2.0 / 12.0;
			float c = (float) Math.cos(a);
			float sn = (float) Math.sin(a);
			float c2 = (float) Math.cos(a + Math.PI / 12.0);
			float s2 = (float) Math.sin(a + Math.PI / 12.0);
			b.hexa(STEEL,
				v(c * 0.5F, -7.55F, sn * 0.5F), v(c * 0.56F, -7.55F, sn * 0.56F), v(c2 * 0.56F, -7.55F, s2 * 0.56F), v(c2 * 0.5F, -7.55F, s2 * 0.5F),
				v(c2 * 0.48F, -7.8F, s2 * 0.48F), v(c2 * 0.52F, -7.8F, s2 * 0.52F), v(c2 * 0.52F, -7.8F, s2 * 0.52F), v(c2 * 0.48F, -7.8F, s2 * 0.48F));
		}
		b.revolve(SOOT, v(0, -7.5F, 0), v(0, -1, 0), new float[][] {{0.0F, 0.5F}, {0.02F, 0.0F}}, 16);
		return b.build();
	}

	private static BoxMesh buildF35Canopy() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		loft(b, F35_GLASS,
			new float[] {6.3F, 0.08F, 0.55F, 0.04F, 0.6F},
			new float[] {5.6F, 0.36F, 0.72F, 0.2F, 1.08F},
			new float[] {4.4F, 0.44F, 0.82F, 0.26F, 1.24F},
			new float[] {3.25F, 0.4F, 0.88F, 0.2F, 1.14F});
		loft(b, F35_GLASS,
			new float[] {3.15F, 0.4F, 0.88F, 0.2F, 1.14F},
			new float[] {2.6F, 0.22F, 0.92F, 0.06F, 0.98F});
		return b.build();
	}

	private static BoxMesh buildF35Gear() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		gearLeg(b, 0.0F, 4.5F, 0.75F, 0.32F);
		gearLeg(b, -1.45F, -1.6F, 1.0F, 0.45F);
		gearLeg(b, 1.45F, -1.6F, 1.0F, 0.45F);
		b.box(-0.3F, 5.1F, -1.3F, -0.26F, 3.9F, -0.75F, F35_DARK);
		b.box(0.26F, 5.1F, -1.3F, 0.3F, 3.9F, -0.75F, F35_DARK);
		return b.build();
	}

	private static BoxMesh buildF35Bay() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float s : new float[] {-1.0F, 1.0F}) {
			b.box(s < 0 ? -1.15F : 0.15F, 1.6F, -1.06F, s < 0 ? -0.15F : 1.15F, -2.6F, -0.85F, STRUT);
			// doors hinged on the centreline and outboard, hanging open
			b.box(s < 0 ? -1.2F : 1.14F, 1.6F, -1.95F, s < 0 ? -1.14F : 1.2F, -2.6F, -1.1F, F35_DARK);
			b.box(s < 0 ? -0.18F : 0.12F, 1.6F, -1.85F, s < 0 ? -0.12F : 0.18F, -2.6F, -1.1F, F35_DARK);
			missile(b, s * 0.65F, 1.2F, -1.3F);
		}
		return b.build();
	}

	// ================================================================== parts

	/** A gear leg from the fuselage down to a wheel resting at the ground line (z = -GEAR_HEIGHT). */
	private static void gearLeg(BoxMesh.Builder b, float x, float y, float topZ, float wheel) {
		float ground = -GEAR_HEIGHT;
		float axle = ground + wheel;
		b.box(x - 0.07F, y - 0.07F, axle, x + 0.07F, y + 0.07F, -topZ, STRUT);
		b.beam(v(x, y + 0.05F, -topZ - 0.2F), v(x, y - 0.6F, axle + 0.4F), 0.05F, 0.05F, STRUT);
		b.cylinderX(x, y, axle, wheel, 0.24F, 12, TYRE, STRUT);
	}

	/** An AIM-120 on its launch rail: white body, ogive nose, mid-body and tail fins. */
	private static void missile(BoxMesh.Builder b, float x, float yNose, float z) {
		b.revolve(MISSILE, v(x, yNose, z), v(0, -1, 0), new float[][] {{0.0F, 0.0F}, {0.25F, 0.07F}, {0.45F, 0.09F}, {3.4F, 0.09F}, {3.6F, 0.07F}}, 10);
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 4.0 + i * Math.PI / 2.0;
			float dx = (float) Math.cos(a);
			float dz = (float) Math.sin(a);
			b.beam(v(x + dx * 0.09F, yNose - 1.4F, z + dz * 0.09F), v(x + dx * 0.24F, yNose - 1.6F, z + dz * 0.24F), 0.02F, 0.2F, MISSILE);
			b.beam(v(x + dx * 0.09F, yNose - 3.3F, z + dz * 0.09F), v(x + dx * 0.26F, yNose - 3.45F, z + dz * 0.26F), 0.02F, 0.18F, MISSILE);
		}
		b.box(x - 0.03F, yNose - 3.0F, z + 0.09F, x + 0.03F, yNose - 0.6F, z + 0.2F, STEEL);
	}
}
