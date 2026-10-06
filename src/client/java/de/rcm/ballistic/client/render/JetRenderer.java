package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	private static final RenderType VAPOR_TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/vapor.png"));

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
	private static final int MARKING = 11;
	private static final int PANEL = 12;
	private static final int YELLOW = 13;

	/** The two engine nozzle centres (x) and their exit plane (y). Declared before the meshes. */
	private static final float[] NOZZLES_X = {-0.5F, 0.5F};
	private static final float NOZZLE_EXIT = -7.05F;

	private static final BoxMesh AIRFRAME = buildAirframe();
	private static final BoxMesh BAY_DOORS = buildBayDoors();
	private static final BoxMesh[] BOMBS = buildBombs();

	public JetRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
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
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.8F, 0.0F);
		poseStack.mulPose(state.rotation);
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

	/** A bell of condensed vapour that starts behind the canopy and flares out past the wings. */
	private static void vaporCone(PoseStack.Pose pose, VertexConsumer consumer, int alpha, float time) {
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

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/**
	 * Lofts a body through cross-sections {y, bottomHalfWidth, bottomZ, topHalfWidth, topZ}: each pair
	 * of neighbouring sections becomes one six-sided solid with trapezoid ends.
	 */
	private static void loft(BoxMesh.Builder b, int patch, float[]... sections) {
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
	private static void surface(BoxMesh.Builder b, int patch, Vector3f rl, Vector3f rt, float rth, Vector3f tl, Vector3f tt, float tth, Vector3f up) {
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
