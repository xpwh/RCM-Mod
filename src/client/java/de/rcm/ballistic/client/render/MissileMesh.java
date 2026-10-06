package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.entity.MissileType;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Procedurally built 3D missile meshes modelled after real systems: lathe-turned bodies with smooth
 * normals, ogive/biconic noses, multi-nozzle engine clusters, jet vanes, fins, raceways and lugs.
 * Built once, stored as a flat quad list and streamed every frame.
 * <p>
 * Texture layout (any resolution, given as fractions): v 0-0.75 is the body unwrapped around the axis
 * (v = height from nose to tail, u = angle), v 0.75-0.87 the fin skin, v 0.88-1.0 metal: u 0-0.45 dark
 * nozzle interior, u 0.55-1.0 bare engine steel.
 */
public final class MissileMesh {
	private static final int STRIDE = 8; // x y z u v nx ny nz
	private static final int SEGMENTS = 48;
	private static final float BODY_V = 0.75F;
	private static final float FIN_V0 = 192F / 256F;
	private static final float FIN_V1 = 222F / 256F;
	private static final float METAL_V0 = 226F / 256F;
	private static final float METAL_V1 = 254F / 256F;

	enum Skin {
		BODY,
		DARK,
		METAL
	}

	public static final MissileMesh TACTICAL = buildTactical();
	public static final MissileMesh ICBM = buildMinuteman();
	public static final MissileMesh HEAVY_ICBM = buildTitan();
	public static final MissileMesh CRUISE = buildCruise();
	public static final MissileMesh CRUISE_BOOSTER = buildCruiseBooster();
	public static final MissileMesh HYPERSONIC = buildHypersonic();
	public static final MissileMesh BOMBLET = buildBomblet();
	public static final MissileMesh REENTRY_VEHICLE = buildReentryVehicle();
	public static final MissileMesh INTERCEPTOR = buildInterceptor();
	public static final MissileMesh DRONE = buildDrone();
	public static final MissileMesh DRONE_PROP = buildDronePropeller();
	public static final MissileMesh AERIAL_BOMB = buildAerialBomb();
	public static final MissileMesh MOAB = buildMoab();

	/** Length of the cruise missile booster hanging below the airframe. */
	public static final float CRUISE_BOOSTER_LENGTH = 1.25F;

	private final float[] data;
	private final float length;

	private MissileMesh(float[] data, float length) {
		this.data = data;
		this.length = length;
	}

	public static MissileMesh of(MissileType type) {
		return switch (type.model) {
			case ICBM -> ICBM;
			case HEAVY_ICBM -> HEAVY_ICBM;
			case CRUISE -> CRUISE;
			case HYPERSONIC -> HYPERSONIC;
			case DRONE -> DRONE;
			default -> TACTICAL;
		};
	}

	public void emit(PoseStack.Pose pose, VertexConsumer consumer, int light) {
		this.emit(pose, consumer, light, -1);
	}

	public void emit(PoseStack.Pose pose, VertexConsumer consumer, int light, int color) {
		float[] d = this.data;
		for (int i = 0; i < d.length; i += STRIDE) {
			consumer.addVertex(pose, d[i], d[i + 1], d[i + 2])
				.setColor(color)
				.setUv(d[i + 3], d[i + 4])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, d[i + 5], d[i + 6], d[i + 7]);
		}
	}

	public float length() {
		return this.length;
	}

	// ------------------------------------------------------------------ real-world inspired models

	/** Iskander-style quasi-ballistic missile: tail skirt, recessed nozzle with jet vanes, long ogive. */
	private static MissileMesh buildTactical() {
		float L = 9.0F;
		float R = 0.55F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {
			{0.00F, 0.62F}, {0.45F, 0.60F}, {0.85F, 0.56F}, {1.00F, R},
			{2.58F, R}, {2.61F, R - 0.012F}, {2.66F, R - 0.012F}, {2.69F, R},
			{4.38F, R}, {4.41F, R - 0.012F}, {4.46F, R - 0.012F}, {4.49F, R},
			{5.62F, R}, {5.66F, R + 0.012F}, {5.86F, R + 0.012F}, {5.90F, R}
		}, Skin.BODY);
		b.lathe(ogive(5.90F, L, R, 18), Skin.BODY);
		// skirt base ring and recessed engine
		b.lathe(new float[][] {{0.0F, 0.62F}, {0.0F, 0.50F}}, Skin.METAL);
		b.lathe(new float[][] {{0.38F, 0.21F}, {0.02F, 0.49F}}, Skin.DARK);
		b.disc(0.38F, 0.21F, true, Skin.DARK);
		// four graphite jet vanes in the exhaust
		for (int i = 0; i < 4; i++) {
			b.radialBox(i * Mth.HALF_PI, 0.12F, 0.3F, 0.035F, 0.04F, 0.3F, Skin.DARK);
		}
		// small trapezoid tail fins with rudders
		for (int i = 0; i < 4; i++) {
			float a = Mth.PI / 4 + i * Mth.HALF_PI;
			b.fin(a, 0.58F, 0.25F, 1.55F, 0.35F, 0.95F, 0.40F, 0.07F);
			b.fin(a, 0.97F, 0.08F, 0.33F, 0.10F, 0.28F, 0.16F, 0.05F);
		}
		// cable raceways, lifting lugs, umbilical plate
		b.radialBox(0.0F, R, 0.055F, 0.15F, 1.05F, 5.58F, Skin.METAL);
		b.radialBox(Mth.PI, R, 0.055F, 0.15F, 1.05F, 5.58F, Skin.METAL);
		b.radialBox(Mth.HALF_PI, R, 0.07F, 0.12F, 2.05F, 2.22F, Skin.METAL);
		b.radialBox(Mth.HALF_PI, R, 0.07F, 0.12F, 4.75F, 4.92F, Skin.METAL);
		b.radialBox(-Mth.HALF_PI, R, 0.05F, 0.24F, 1.25F, 1.62F, Skin.DARK);
		// bolted joint rings between the motor case, the guidance bay and the warhead section
		b.ring(2.635F, R, 0.018F, 0.06F);
		b.ring(4.435F, R, 0.018F, 0.06F);
		b.ring(5.76F, R + 0.012F, 0.02F, 0.08F);
		// GLONASS/radio antennas on the guidance bay, decoy dispenser hatches, seeker window
		b.radialBox(Mth.PI / 4, R, 0.16F, 0.035F, 5.0F, 5.12F, Skin.DARK);
		b.radialBox(Mth.PI + Mth.PI / 4, R, 0.12F, 0.03F, 5.05F, 5.14F, Skin.DARK);
		b.radialBox(Mth.PI * 0.75F, R, 0.025F, 0.3F, 3.1F, 3.5F, Skin.DARK);
		b.radialBox(-Mth.PI * 0.25F, R, 0.025F, 0.3F, 3.1F, 3.5F, Skin.DARK);
		b.radialBox(Mth.HALF_PI, R * 0.62F, 0.02F, 0.16F, 7.4F, 7.58F, Skin.DARK);
		return b.build();
	}

	/** Minuteman-style three-stage ICBM with four first-stage nozzles and a biconic shroud. */
	private static MissileMesh buildMinuteman() {
		float L = 12.0F;
		float R = 0.6F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {
			{0.45F, 0.64F}, {0.80F, 0.615F}, {0.92F, R},
			{5.20F, R}, {5.32F, 0.575F}, {5.50F, 0.555F},
			{7.98F, 0.555F}, {8.12F, 0.525F}, {8.28F, 0.505F},
			{9.30F, 0.505F}, {9.34F, 0.515F}, {9.80F, 0.515F}
		}, Skin.BODY);
		b.lathe(new float[][] {{9.80F, 0.515F}, {10.95F, 0.32F}}, Skin.BODY);
		b.lathe(ogive(10.95F, L, 0.32F, 12), Skin.BODY);
		// aft closure with four gimballed nozzles
		b.disc(0.45F, 0.64F, true, Skin.METAL);
		for (int i = 0; i < 4; i++) {
			float a = Mth.PI / 4 + i * Mth.HALF_PI;
			float cx = Mth.cos(a) * 0.30F;
			float cz = Mth.sin(a) * 0.30F;
			b.latheAt(cx, cz, 24, new float[][] {{0.0F, 0.23F}, {0.18F, 0.185F}, {0.36F, 0.13F}, {0.46F, 0.11F}}, Skin.METAL);
			b.latheAt(cx, cz, 24, new float[][] {{0.44F, 0.10F}, {0.02F, 0.22F}}, Skin.DARK);
		}
		// two long raceways and the stage-3 umbilical
		b.radialBox(0.0F, R, 0.07F, 0.16F, 0.95F, 5.15F, Skin.METAL);
		b.radialBox(0.0F, 0.555F, 0.07F, 0.14F, 5.55F, 7.95F, Skin.METAL);
		b.radialBox(Mth.PI, R, 0.05F, 0.12F, 0.95F, 5.15F, Skin.METAL);
		b.radialBox(Mth.HALF_PI, 0.505F, 0.05F, 0.2F, 8.6F, 9.0F, Skin.DARK);
		// interstage separation joints with their linear-shaped-charge bands
		b.ring(5.26F, 0.59F, 0.02F, 0.1F);
		b.ring(8.05F, 0.545F, 0.02F, 0.1F);
		b.ring(9.32F, 0.51F, 0.015F, 0.05F);
		// aft skirt stiffener ribs and nozzle actuators
		for (int i = 0; i < 8; i++) {
			b.radialBox(i * Mth.PI / 4 + Mth.PI / 8, 0.62F, 0.03F, 0.06F, 0.47F, 0.9F, Skin.METAL);
		}
		for (int i = 0; i < 4; i++) {
			b.radialBox(i * Mth.HALF_PI, 0.12F, 0.12F, 0.05F, 0.15F, 0.42F, Skin.DARK);
		}
		// post-boost vehicle access doors on the shroud
		b.radialBox(Mth.PI, 0.5F, 0.012F, 0.22F, 10.0F, 10.3F, Skin.DARK);
		return b.build();
	}

	/** Titan-style heavy ICBM: two big first-stage engines, truss interstage, blunt Mk-6 re-entry vehicle. */
	private static MissileMesh buildTitan() {
		float L = 12.0F;
		float R = 0.6F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {
			{0.78F, 0.61F}, {0.95F, R},
			{7.38F, R}, {7.42F, R - 0.02F}, {7.88F, R - 0.02F}, {7.92F, R},
			{10.15F, R}, {10.20F, R + 0.01F}, {10.45F, 0.585F}
		}, Skin.BODY);
		// blunt cone with a rounded nose cap
		b.lathe(new float[][] {{10.45F, 0.585F}, {11.62F, 0.23F}}, Skin.BODY);
		float[][] cap = new float[7][];
		for (int k = 0; k <= 6; k++) {
			double ang = Math.toRadians(90.0 * k / 6.0);
			cap[k] = new float[] {11.62F + 0.38F * (float) Math.sin(ang), k == 6 ? 0.0F : 0.23F * (float) Math.cos(ang)};
		}
		b.lathe(cap, Skin.BODY);
		b.disc(0.78F, 0.61F, true, Skin.METAL);
		// two large LR-87-style engines
		for (int i = 0; i < 2; i++) {
			float cx = i == 0 ? 0.29F : -0.29F;
			b.latheAt(cx, 0.0F, 28, new float[][] {{0.0F, 0.285F}, {0.30F, 0.235F}, {0.58F, 0.16F}, {0.72F, 0.12F}, {0.80F, 0.14F}}, Skin.METAL);
			b.latheAt(cx, 0.0F, 28, new float[][] {{0.70F, 0.11F}, {0.02F, 0.275F}}, Skin.DARK);
		}
		// thrust-frame struts between the engines
		b.radialBox(Mth.HALF_PI, 0.05F, 0.5F, 0.08F, 0.55F, 0.78F, Skin.DARK);
		b.radialBox(-Mth.HALF_PI, 0.05F, 0.5F, 0.08F, 0.55F, 0.78F, Skin.DARK);
		// raceways
		b.radialBox(Mth.HALF_PI, R, 0.08F, 0.18F, 1.0F, 10.1F, Skin.METAL);
		b.radialBox(-Mth.HALF_PI, R, 0.06F, 0.12F, 1.0F, 7.3F, Skin.METAL);
		// turbopumps and propellant feed lines between the engines
		b.radialBox(Mth.HALF_PI, 0.0F, 0.22F, 0.16F, 0.5F, 0.86F, Skin.DARK);
		b.radialBox(-Mth.HALF_PI, 0.0F, 0.22F, 0.16F, 0.5F, 0.86F, Skin.DARK);
		b.radialBox(Mth.PI / 3, 0.1F, 0.08F, 0.06F, 0.55F, 0.8F, Skin.METAL);
		b.radialBox(-Mth.PI * 2 / 3, 0.1F, 0.08F, 0.06F, 0.55F, 0.8F, Skin.METAL);
		// stage joint and re-entry vehicle adapter ring
		b.ring(7.4F, R - 0.02F, 0.025F, 0.12F);
		b.ring(10.18F, R + 0.01F, 0.02F, 0.08F);
		// telemetry antennas
		b.radialBox(Mth.PI, R, 0.2F, 0.03F, 9.5F, 9.62F, Skin.DARK);
		b.radialBox(0.0F, R, 0.2F, 0.03F, 9.5F, 9.62F, Skin.DARK);
		return b.build();
	}

	/**
	 * Tomahawk-style cruise missile. Local +X/-X carry the wings, local -Z is the belly with the
	 * turbofan intake (the renderer keeps local +Z pointing up).
	 */
	private static MissileMesh buildCruise() {
		float L = 6.0F;
		float R = 0.3F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {
			{0.00F, 0.15F}, {0.10F, 0.22F}, {0.28F, 0.285F}, {0.40F, R},
			{2.40F, R}, {2.42F, R - 0.008F}, {2.46F, R - 0.008F}, {2.48F, R},
			{4.95F, R}
		}, Skin.BODY);
		float[][] nose = new float[11][];
		for (int k = 0; k <= 10; k++) {
			float f = k / 10.0F;
			nose[k] = new float[] {4.95F + f * 1.05F, k == 10 ? 0.0F : R * (float) Math.sqrt(1.0 - f * f * 0.985)};
		}
		b.lathe(nose, Skin.BODY);
		b.disc(0.0F, 0.15F, true, Skin.DARK);
		// swept pop-out wings (thin airfoil, thinner at the tip)
		b.fin(0.0F, R - 0.02F, 2.55F, 3.40F, 2.70F, 3.10F, 1.6F, 0.06F);
		b.fin(Mth.PI, R - 0.02F, 2.55F, 3.40F, 2.70F, 3.10F, 1.6F, 0.06F);
		// cruciform tail
		for (int i = 0; i < 4; i++) {
			b.fin(Mth.PI / 4 + i * Mth.HALF_PI, R - 0.02F, 0.30F, 0.98F, 0.32F, 0.60F, 0.40F, 0.045F);
		}
		// flush belly intake with a dark mouth, dorsal antenna, seeker window band
		b.radialBox(-Mth.HALF_PI, R, 0.12F, 0.26F, 1.10F, 1.95F, Skin.METAL);
		b.radialBox(-Mth.HALF_PI, R + 0.01F, 0.115F, 0.2F, 1.90F, 1.97F, Skin.DARK);
		b.radialBox(Mth.HALF_PI, R, 0.18F, 0.03F, 3.9F, 4.05F, Skin.METAL);
		// turbofan exhaust cone, radar altimeter and DSMAC camera windows on the belly
		b.latheAt(0, 0, 24, new float[][] {{0.0F, 0.13F}, {-0.08F, 0.1F}}, Skin.METAL);
		b.radialBox(-Mth.HALF_PI, R, 0.01F, 0.12F, 3.3F, 3.42F, Skin.DARK);
		b.radialBox(-Mth.HALF_PI, R, 0.01F, 0.1F, 4.3F, 4.4F, Skin.DARK);
		b.ring(4.95F, R, 0.008F, 0.04F);
		return b.build();
	}

	/** Solid rocket booster of the cruise missile, dropped after the climb-out. Hangs below y=0. */
	private static MissileMesh buildCruiseBooster() {
		Builder b = new Builder(6.0F);
		float B = CRUISE_BOOSTER_LENGTH;
		b.lathe(new float[][] {{-B, 0.2F}, {-B + 0.12F, 0.27F}, {-0.06F, 0.27F}, {0.0F, 0.2F}}, Skin.METAL);
		b.latheAt(0, 0, 24, new float[][] {{-B - 0.28F, 0.17F}, {-B - 0.05F, 0.11F}, {-B, 0.12F}}, Skin.METAL);
		b.latheAt(0, 0, 24, new float[][] {{-B - 0.02F, 0.1F}, {-B - 0.27F, 0.16F}}, Skin.DARK);
		for (int i = 0; i < 4; i++) {
			b.fin(i * Mth.HALF_PI, 0.26F, -B + 0.05F, -B + 0.55F, -B + 0.08F, -B + 0.35F, 0.22F, 0.035F);
		}
		return b.build();
	}

	/** Kinzhal-style hypersonic missile: long sharp cone, small tail fins, recessed nozzle. */
	private static MissileMesh buildHypersonic() {
		float L = 8.0F;
		float R = 0.5F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.00F, 0.44F}, {0.22F, 0.49F}, {0.30F, R}, {4.40F, R}, {4.44F, R - 0.01F}, {4.50F, R}}, Skin.BODY);
		float[][] cone = new float[15][];
		for (int k = 0; k <= 14; k++) {
			float f = k / 14.0F;
			float r = R * (1.0F - f) * (1.0F + 0.25F * f * (1.0F - f)); // near-conical, slightly convex
			cone[k] = new float[] {4.50F + f * 3.5F, k == 14 ? 0.0F : Math.max(r, 0.015F)};
		}
		b.lathe(cone, Skin.BODY);
		b.lathe(new float[][] {{0.30F, 0.26F}, {0.02F, 0.42F}}, Skin.DARK);
		b.disc(0.30F, 0.26F, true, Skin.DARK);
		b.lathe(new float[][] {{0.0F, 0.44F}, {0.0F, 0.42F}}, Skin.METAL);
		for (int i = 0; i < 4; i++) {
			b.fin(Mth.PI / 4 + i * Mth.HALF_PI, R - 0.02F, 0.18F, 1.25F, 0.22F, 0.62F, 0.30F, 0.05F);
		}
		b.radialBox(0.0F, R, 0.045F, 0.12F, 0.8F, 4.3F, Skin.METAL);
		// tail cone fairing for the carrier aircraft (left on), aft and mid joints
		b.ring(0.32F, R, 0.025F, 0.08F);
		b.ring(4.47F, R, 0.012F, 0.05F);
		b.radialBox(Mth.PI, R, 0.04F, 0.2F, 2.0F, 2.4F, Skin.DARK);
		return b.build();
	}

	/** Small finned cluster sub-munition. */
	private static MissileMesh buildBomblet() {
		float L = 0.6F;
		float R = 0.12F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.0F, 0.06F}, {0.08F, R}, {0.42F, R}, {0.52F, 0.09F}, {0.58F, 0.04F}, {0.6F, 0.0F}}, Skin.BODY);
		for (int i = 0; i < 4; i++) {
			b.fin(i * Mth.HALF_PI, R - 0.01F, 0.0F, 0.2F, 0.0F, 0.12F, 0.1F, 0.015F);
		}
		// BLU-97 style: shaped-charge liner ring and the standoff probe on the nose
		b.ring(0.42F, R, 0.01F, 0.03F);
		b.latheAt(0, 0, 12, new float[][] {{0.58F, 0.02F}, {0.7F, 0.02F}, {0.7F, 0.0F}}, Skin.METAL);
		return b.build();
	}

	/** MIRV re-entry vehicle: slender cone with an ablative heat shield. */
	private static MissileMesh buildReentryVehicle() {
		float L = 1.8F;
		Builder b = new Builder(L);
		// Mk 21-style: slender cone with a slightly bulged base (heat shield), carbon nose tip
		b.lathe(new float[][] {{0.0F, 0.0F}, {0.0F, 0.26F}, {0.03F, 0.31F}, {0.1F, 0.33F}, {0.9F, 0.2F}, {1.62F, 0.07F}}, Skin.BODY);
		float[][] tip = new float[7][];
		for (int k = 0; k <= 6; k++) {
			double a = Math.toRadians(90.0 * k / 6.0);
			tip[k] = new float[] {1.62F + 0.13F * (float) Math.sin(a), k == 6 ? 0.0F : 0.07F * (float) Math.cos(a)};
		}
		b.lathe(tip, Skin.DARK);
		b.ring(0.12F, 0.325F, 0.008F, 0.03F); // aft closure joint
		for (int i = 0; i < 4; i++) {
			b.radialBox(i * Mth.HALF_PI, 0.3F, 0.02F, 0.05F, 0.12F, 0.3F, Skin.METAL); // spin rockets / fairings
		}
		return b.build();
	}

	/** Surface-to-air interceptor: thin body, canards up front, tail fins. */
	private static MissileMesh buildInterceptor() {
		float L = 2.6F;
		float R = 0.11F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.0F, 0.08F}, {0.06F, R}, {1.9F, R}}, Skin.BODY);
		b.lathe(ogive(1.9F, L, R, 8), Skin.BODY);
		b.disc(0.0F, 0.08F, true, Skin.DARK);
		for (int i = 0; i < 4; i++) {
			b.fin(Mth.PI / 4 + i * Mth.HALF_PI, R - 0.01F, 0.02F, 0.45F, 0.04F, 0.25F, 0.2F, 0.02F);
			b.fin(Mth.PI / 4 + i * Mth.HALF_PI, R - 0.01F, 1.55F, 1.75F, 1.58F, 1.68F, 0.09F, 0.015F);
		}
		// PAC-3 attitude control motors: a ring of tiny solid thrusters behind the seeker
		for (int i = 0; i < 12; i++) {
			b.radialBox(i * Mth.TWO_PI / 12, R, 0.018F, 0.03F, 1.8F, 1.86F, Skin.DARK);
		}
		b.ring(1.2F, R, 0.008F, 0.03F);
		return b.build();
	}

	/**
	 * Shahed-136-style loitering munition: short fuselage, big cropped delta wing with winglets,
	 * piston engine and a two-blade pusher propeller at the tail (drawn separately so it can spin).
	 * Like the cruise missile, local +X/-X carry the wings and +Z points up.
	 */
	private static MissileMesh buildDrone() {
		float L = 3.5F;
		float R = 0.22F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.0F, 0.1F}, {0.12F, 0.16F}, {0.35F, R}, {2.85F, R}}, Skin.BODY);
		float[][] nose = new float[9][];
		for (int k = 0; k <= 8; k++) {
			float f = k / 8.0F;
			nose[k] = new float[] {2.85F + f * 0.65F, k == 8 ? 0.0F : R * (float) Math.sqrt(1.0 - f * f * 0.97)};
		}
		b.lathe(nose, Skin.BODY);
		// engine cowling and exhaust stubs
		b.latheAt(0, 0, 24, new float[][] {{0.0F, 0.1F}, {-0.1F, 0.07F}, {-0.16F, 0.04F}}, Skin.METAL);
		b.radialBox(Mth.PI / 3, 0.15F, 0.06F, 0.05F, 0.1F, 0.3F, Skin.DARK);
		b.radialBox(Mth.PI * 2 / 3, 0.15F, 0.06F, 0.05F, 0.1F, 0.3F, Skin.DARK);
		// cropped delta wing, thin, swept back to the tail
		b.fin(0.0F, R - 0.02F, 0.3F, 2.55F, 0.3F, 0.82F, 1.35F, 0.07F);
		b.fin(Mth.PI, R - 0.02F, 0.3F, 2.55F, 0.3F, 0.82F, 1.35F, 0.07F);
		// vertical winglets (fins) at the wing tips, standing up and down
		b.radialBox(0.0F, R + 1.33F, 0.05F, 0.62F, 0.3F, 0.88F, Skin.DARK);
		b.radialBox(Mth.PI, R + 1.33F, 0.05F, 0.62F, 0.3F, 0.88F, Skin.DARK);
		// GPS antenna and warhead fuze probe
		b.radialBox(Mth.HALF_PI, R, 0.07F, 0.05F, 2.1F, 2.2F, Skin.DARK);
		b.latheAt(0, 0, 12, new float[][] {{3.46F, 0.025F}, {3.62F, 0.02F}, {3.62F, 0.0F}}, Skin.METAL);
		return b.build();
	}

	/** Two-blade pusher propeller, hub at the origin, blades in the XZ plane. */
	private static MissileMesh buildDronePropeller() {
		Builder b = new Builder(3.5F);
		b.latheAt(0, 0, 16, new float[][] {{-0.12F, 0.0F}, {-0.12F, 0.05F}, {0.0F, 0.06F}, {0.02F, 0.0F}}, Skin.METAL);
		b.radialBox(0.0F, 0.04F, 0.55F, 0.09F, -0.08F, -0.05F, Skin.DARK);
		b.radialBox(Mth.PI, 0.04F, 0.55F, 0.09F, -0.08F, -0.05F, Skin.DARK);
		return b.build();
	}

	/** Mk 82-class low-drag general-purpose bomb with a cruciform conical tail. */
	private static MissileMesh buildAerialBomb() {
		float L = 2.2F;
		float R = 0.18F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.0F, 0.07F}, {0.45F, 0.15F}, {0.62F, R}, {1.45F, R}}, Skin.BODY);
		b.lathe(ogive(1.45F, L, R, 12), Skin.BODY);
		b.disc(0.0F, 0.07F, true, Skin.DARK);
		for (int i = 0; i < 4; i++) {
			b.fin(Mth.PI / 4 + i * Mth.HALF_PI, 0.09F, 0.0F, 0.55F, 0.0F, 0.2F, 0.18F, 0.02F);
		}
		// suspension lugs and the nose fuze
		b.radialBox(Mth.HALF_PI, R, 0.05F, 0.05F, 0.85F, 0.92F, Skin.METAL);
		b.radialBox(Mth.HALF_PI, R, 0.05F, 0.05F, 1.2F, 1.27F, Skin.METAL);
		b.latheAt(0, 0, 12, new float[][] {{2.15F, 0.03F}, {2.28F, 0.03F}, {2.28F, 0.0F}}, Skin.METAL);
		return b.build();
	}

	/**
	 * GBU-43/B "Mother of All Bombs": a long fat case with a blunt ogive, a boat-tail, four slender
	 * strakes and the four lattice grid fins that steer it onto the target.
	 */
	private static MissileMesh buildMoab() {
		float L = 7.4F;
		float R = 0.46F;
		Builder b = new Builder(L);
		b.lathe(new float[][] {{0.0F, 0.26F}, {0.5F, 0.36F}, {1.0F, R}, {5.2F, R}}, Skin.BODY);
		float[][] nose = ogive(5.2F, L, R, 14);
		nose[nose.length - 1][1] = 0.0F;
		b.lathe(nose, Skin.BODY);
		b.disc(0.0F, 0.26F, true, Skin.DARK);
		for (int i = 0; i < 4; i++) {
			float a = Mth.PI / 4 + i * Mth.HALF_PI;
			// long low strakes along the case
			b.fin(a, R - 0.01F, 1.3F, 4.7F, 1.6F, 4.4F, 0.07F, 0.03F);
			b.gridFin(a, 0.3F, 1.12F, 0.8F, 0.12F, 0.42F);
		}
		// lifting lugs and the nose fuze well
		b.radialBox(0.0F, R, 0.06F, 0.12F, 3.1F, 3.25F, Skin.METAL);
		b.radialBox(0.0F, R, 0.06F, 0.12F, 4.0F, 4.15F, Skin.METAL);
		b.latheAt(0, 0, 12, new float[][] {{7.36F, 0.05F}, {7.5F, 0.05F}, {7.5F, 0.0F}}, Skin.METAL);
		return b.build();
	}

	/** Tangent-ogive-like nose: smooth shoulder, sharp tip. */
	private static float[][] ogive(float y0, float y1, float r0, int steps) {
		float[][] pts = new float[steps + 1][];
		for (int k = 0; k <= steps; k++) {
			float f = (float) k / steps;
			float r = r0 * (float) Math.pow(Math.max(0.0, 1.0 - Math.pow(f, 1.8)), 0.62);
			pts[k] = new float[] {Mth.lerp(f, y0, y1), k == steps ? 0.0F : r};
		}
		return pts;
	}

	// ------------------------------------------------------------------ builder

	private static final class Builder {
		private final FloatArrayList out = new FloatArrayList();
		private final float length;

		Builder(float length) {
			this.length = length;
		}

		MissileMesh build() {
			return new MissileMesh(this.out.toFloatArray(), this.length);
		}

		private float bodyV(float y) {
			return BODY_V * (1.0F - Mth.clamp(y / this.length, 0.0F, 1.0F));
		}

		void lathe(float[][] profile, Skin skin) {
			this.latheAt(0, 0, SEGMENTS, profile, skin);
		}

		/**
		 * Surface of revolution around a vertical axis through (cx, cz). Normals are smoothed across
		 * profile points unless the profile has a sharp corner there (> 35 degrees), so curved noses
		 * shade smoothly while seams and steps keep crisp edges.
		 */
		void latheAt(float cx, float cz, int segments, float[][] profile, Skin skin) {
			int n = profile.length;
			float[] segR = new float[n - 1];
			float[] segY = new float[n - 1];
			boolean[] valid = new boolean[n - 1];
			for (int p = 0; p < n - 1; p++) {
				float dy = profile[p + 1][0] - profile[p][0];
				float dr = profile[p + 1][1] - profile[p][1];
				float len = Mth.sqrt(dy * dy + dr * dr);
				valid[p] = len > 1.0E-5F;
				if (valid[p]) {
					// profile tangent rotated by -90 degrees: outward when climbing, inward when descending
					segR[p] = dy / len;
					segY[p] = -dr / len;
				}
			}
			for (int p = 0; p < n - 1; p++) {
				if (!valid[p]) {
					continue;
				}
				float ya = profile[p][0], ra = profile[p][1];
				float yb = profile[p + 1][0], rb = profile[p + 1][1];
				float[] na = this.smoothed(segR, segY, valid, p, p - 1);
				float[] nb = this.smoothed(segR, segY, valid, p, p + 1);
				float va = this.v(skin, ya, true);
				float vb = this.v(skin, yb, false);
				for (int s = 0; s < segments; s++) {
					float a0 = Mth.TWO_PI * s / segments;
					float a1 = Mth.TWO_PI * (s + 1) / segments;
					float u0 = this.u(skin, (float) s / segments);
					float u1 = this.u(skin, (float) (s + 1) / segments);
					float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
					this.vertex(cx + c0 * ra, ya, cz + s0 * ra, u0, va, c0 * na[0], na[1], s0 * na[0]);
					this.vertex(cx + c0 * rb, yb, cz + s0 * rb, u0, vb, c0 * nb[0], nb[1], s0 * nb[0]);
					this.vertex(cx + c1 * rb, yb, cz + s1 * rb, u1, vb, c1 * nb[0], nb[1], s1 * nb[0]);
					this.vertex(cx + c1 * ra, ya, cz + s1 * ra, u1, va, c1 * na[0], na[1], s1 * na[0]);
				}
			}
		}

		private float[] smoothed(float[] segR, float[] segY, boolean[] valid, int self, int other) {
			float r = segR[self];
			float y = segY[self];
			if (other >= 0 && other < segR.length && valid[other]) {
				float dot = r * segR[other] + y * segY[other];
				if (dot > 0.82F) { // < ~35 degrees: same surface, blend
					r = (r + segR[other]) * 0.5F;
					y = (y + segY[other]) * 0.5F;
					float l = Mth.sqrt(r * r + y * y);
					r /= l;
					y /= l;
				}
			}
			return new float[] {r, y};
		}

		private float u(Skin skin, float f) {
			return switch (skin) {
				case BODY -> f;
				case DARK -> 0.45F * f;
				case METAL -> 0.55F + 0.45F * f;
			};
		}

		private float v(Skin skin, float y, boolean start) {
			return switch (skin) {
				case BODY -> this.bodyV(y);
				default -> start ? METAL_V0 : METAL_V1;
			};
		}

		/** Raised metal band around the hull: a joint, clamp ring or bolt circle. */
		void ring(float y, float radius, float thickness, float height) {
			this.lathe(new float[][] {
				{y, radius - 0.005F}, {y, radius + thickness}, {y + height, radius + thickness}, {y + height, radius - 0.005F}
			}, Skin.METAL);
		}

		/** Flat disc at height y facing down (or up). */
		void disc(float y, float radius, boolean facingDown, Skin skin) {
			float ny = facingDown ? -1.0F : 1.0F;
			float uc = this.u(skin, 0.5F);
			float vc = (METAL_V0 + METAL_V1) * 0.5F;
			for (int s = 0; s < SEGMENTS; s++) {
				float a0 = Mth.TWO_PI * s / SEGMENTS;
				float a1 = Mth.TWO_PI * (s + 1) / SEGMENTS;
				this.vertex(0, y, 0, uc, vc, 0, ny, 0);
				this.vertex(0, y, 0, uc, vc, 0, ny, 0);
				this.vertex(Mth.cos(a1) * radius, y, Mth.sin(a1) * radius, uc + 0.05F, METAL_V1, 0, ny, 0);
				this.vertex(Mth.cos(a0) * radius, y, Mth.sin(a0) * radius, uc - 0.05F, METAL_V1, 0, ny, 0);
			}
		}

		/**
		 * Trapezoidal fin plate standing radially at {@code angle}: root chord rootY0..rootY1 at radius
		 * {@code root}, tip chord tipY0..tipY1 at radius root + span. The tip is thinner than the root.
		 */
		void fin(float angle, float root, float rootY0, float rootY1, float tipY0, float tipY1, float span, float thickness) {
			Vector3f radial = new Vector3f(Mth.cos(angle), 0, Mth.sin(angle));
			Vector3f tangent = new Vector3f(-Mth.sin(angle), 0, Mth.cos(angle));
			float h = thickness / 2;
			float tip = root + span;
			Vector3f rb0 = point(radial, tangent, root, rootY0, -h), rb1 = point(radial, tangent, root, rootY0, h);
			Vector3f rt0 = point(radial, tangent, root, rootY1, -h), rt1 = point(radial, tangent, root, rootY1, h);
			Vector3f tt0 = point(radial, tangent, tip, tipY1, -h * 0.45F), tt1 = point(radial, tangent, tip, tipY1, h * 0.45F);
			Vector3f tb0 = point(radial, tangent, tip, tipY0, -h * 0.45F), tb1 = point(radial, tangent, tip, tipY0, h * 0.45F);
			Vector3f center = point(radial, tangent, root + span * 0.5F, (rootY0 + rootY1 + tipY0 + tipY1) * 0.25F, 0);

			float u0 = 0.0F, u1 = 0.5F;
			float yMin = Math.min(rootY0, tipY0), yMax = Math.max(rootY1, tipY1);
			float span01 = Math.max(1.0E-4F, yMax - yMin);
			float vr0 = Mth.lerp((rootY0 - yMin) / span01, FIN_V1, FIN_V0);
			float vr1 = Mth.lerp((rootY1 - yMin) / span01, FIN_V1, FIN_V0);
			float vt0 = Mth.lerp((tipY0 - yMin) / span01, FIN_V1, FIN_V0);
			float vt1 = Mth.lerp((tipY1 - yMin) / span01, FIN_V1, FIN_V0);

			this.quad(center, rb0, u0, vr0, rt0, u0, vr1, tt0, u1, vt1, tb0, u1, vt0);
			this.quad(center, rb1, u0, vr0, tb1, u1, vt0, tt1, u1, vt1, rt1, u0, vr1);
			this.quad(center, rt0, u0, FIN_V0, rt1, 0.02F, FIN_V0, tt1, 0.02F, FIN_V1, tt0, u0, FIN_V1);
			this.quad(center, rb0, u0, FIN_V0, tb0, u0, FIN_V1, tb1, 0.02F, FIN_V1, rb1, 0.02F, FIN_V0);
			this.quad(center, tb0, u1, FIN_V0, tt0, u1, FIN_V1, tt1, u1 - 0.02F, FIN_V1, tb1, u1 - 0.02F, FIN_V0);
		}

		/**
		 * Lattice grid fin standing out from the hull at {@code angle}: an outer frame from radius r0 to
		 * r1, {@code width} wide, with its cells open along the axis (airflow passes through them).
		 */
		void gridFin(float angle, float r0, float r1, float width, float y0, float y1) {
			float t = 0.035F;
			float w = width / 2;
			// outer frame
			this.bar(angle, r0, r0 + t, -w, w, y0, y1);
			this.bar(angle, r1 - t, r1, -w, w, y0, y1);
			this.bar(angle, r0, r1, -w, -w + t, y0, y1);
			this.bar(angle, r0, r1, w - t, w, y0, y1);
			// diagonal-looking lattice: evenly spaced webs both ways
			for (int k = 1; k < 5; k++) {
				float r = Mth.lerp(k / 5.0F, r0, r1);
				this.bar(angle, r - t * 0.35F, r + t * 0.35F, -w, w, y0 + 0.02F, y1 - 0.02F);
			}
			for (int k = 1; k < 4; k++) {
				float s = Mth.lerp(k / 4.0F, -w, w);
				this.bar(angle, r0, r1, s - t * 0.35F, s + t * 0.35F, y0 + 0.02F, y1 - 0.02F);
			}
			// hinge post into the case
			this.bar(angle, r0 - 0.12F, r0, -0.04F, 0.04F, y0 + 0.05F, y1 - 0.05F);
		}

		/** Box spanning radius r0-r1, sideways s0-s1 and height y0-y1 in the frame of {@code angle}. */
		void bar(float angle, float r0, float r1, float s0, float s1, float y0, float y1) {
			Vector3f radial = new Vector3f(Mth.cos(angle), 0, Mth.sin(angle));
			Vector3f tangent = new Vector3f(-Mth.sin(angle), 0, Mth.cos(angle));
			Vector3f[] c = new Vector3f[8];
			int i = 0;
			for (float r : new float[] {r0, r1}) {
				for (float y : new float[] {y0, y1}) {
					for (float s : new float[] {s0, s1}) {
						c[i++] = point(radial, tangent, r, y, s);
					}
				}
			}
			Vector3f center = point(radial, tangent, (r0 + r1) / 2, (y0 + y1) / 2, (s0 + s1) / 2);
			float u0 = this.u(Skin.METAL, 0.2F), u1 = this.u(Skin.METAL, 0.8F);
			float v0 = METAL_V0, v1 = METAL_V1;
			this.quad(center, c[4], u0, v0, c[6], u0, v1, c[7], u1, v1, c[5], u1, v0);
			this.quad(center, c[0], u0, v0, c[2], u0, v1, c[6], u1, v1, c[4], u1, v0);
			this.quad(center, c[1], u0, v0, c[5], u1, v0, c[7], u1, v1, c[3], u0, v1);
			this.quad(center, c[2], u0, v0, c[3], u1, v0, c[7], u1, v1, c[6], u0, v1);
			this.quad(center, c[0], u0, v0, c[4], u0, v1, c[5], u1, v1, c[1], u1, v0);
			this.quad(center, c[0], u0, v0, c[1], u1, v0, c[3], u1, v1, c[2], u0, v1);
		}

		/** Small rectangular box lying on the hull at {@code angle} (conduits, lugs, vanes, plates). */
		void radialBox(float angle, float hull, float height, float width, float y0, float y1, Skin skin) {
			Vector3f radial = new Vector3f(Mth.cos(angle), 0, Mth.sin(angle));
			Vector3f tangent = new Vector3f(-Mth.sin(angle), 0, Mth.cos(angle));
			float r0 = hull - 0.02F, r1 = hull + height, w = width / 2;
			Vector3f[] c = new Vector3f[8];
			int i = 0;
			for (float r : new float[] {r0, r1}) {
				for (float y : new float[] {y0, y1}) {
					for (float s : new float[] {-w, w}) {
						c[i++] = point(radial, tangent, r, y, s);
					}
				}
			}
			Vector3f center = point(radial, tangent, (r0 + r1) / 2, (y0 + y1) / 2, 0);
			float u0 = this.u(skin, 0.2F), u1 = this.u(skin, 0.8F);
			float v0 = METAL_V0, v1 = METAL_V1;
			this.quad(center, c[4], u0, v0, c[6], u0, v1, c[7], u1, v1, c[5], u1, v0);
			this.quad(center, c[0], u0, v0, c[2], u0, v1, c[6], u1, v1, c[4], u1, v0);
			this.quad(center, c[1], u0, v0, c[5], u1, v0, c[7], u1, v1, c[3], u0, v1);
			this.quad(center, c[2], u0, v0, c[3], u1, v0, c[7], u1, v1, c[6], u0, v1);
			this.quad(center, c[0], u0, v0, c[4], u0, v1, c[5], u1, v1, c[1], u1, v0);
		}

		private static Vector3f point(Vector3f radial, Vector3f tangent, float r, float y, float side) {
			return new Vector3f(radial.x * r + tangent.x * side, y, radial.z * r + tangent.z * side);
		}

		/** Flat quad with a normal pointing away from {@code center}. */
		private void quad(Vector3f center, Vector3f a, float ua, float va, Vector3f b, float ub, float vb, Vector3f c, float uc, float vc, Vector3f d, float ud, float vd) {
			Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
			if (n.lengthSquared() < 1.0E-10F) {
				n = new Vector3f(d).sub(a).cross(new Vector3f(c).sub(a));
			}
			n.normalize();
			Vector3f faceCenter = new Vector3f(a).add(b).add(c).add(d).mul(0.25F);
			if (n.dot(faceCenter.sub(center)) < 0) {
				n.negate();
			}
			this.vertex(a.x, a.y, a.z, ua, va, n.x, n.y, n.z);
			this.vertex(b.x, b.y, b.z, ub, vb, n.x, n.y, n.z);
			this.vertex(c.x, c.y, c.z, uc, vc, n.x, n.y, n.z);
			this.vertex(d.x, d.y, d.z, ud, vd, n.x, n.y, n.z);
		}

		private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
			this.out.add(x);
			this.out.add(y);
			this.out.add(z);
			this.out.add(u);
			this.out.add(v);
			this.out.add(nx);
			this.out.add(ny);
			this.out.add(nz);
		}
	}
}
