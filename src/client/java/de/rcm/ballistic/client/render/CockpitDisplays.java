package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.entity.AirMissileEntity;
import de.rcm.ballistic.entity.FighterEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The live cockpit displays, drawn self-lit onto the instrument panel:
 * <ul>
 * <li>attitude (ADI): blue sky over brown earth, the horizon rolling and climbing with the jet, a
 * ladder line every 10 degrees and the fixed aircraft symbol;</li>
 * <li>tactical situation display: north-up turned heading-up, two range rings, the jet in the middle,
 * other aircraft as green (or, if they are the jet's own kind, cyan) squares, missiles in red;</li>
 * <li>engine and stores: thrust bar (amber in afterburner), engine spool, gun rounds, flares, a
 * block per missile left;</li>
 * <li>the master caution and warning lights on the glare shield.</li>
 * </ul>
 * The F-35 shows all of it side by side on its single panoramic screen; the F-22 on its three MFDs.
 */
public final class CockpitDisplays {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float U = (FighterRenderer.WHITE % 8 + 0.5F) / 8.0F;
	private static final float V = (FighterRenderer.WHITE / 8 + 0.5F) / 8.0F;
	private static final double TSD_RANGE = 1500.0;
	private static final int MAX_CONTACTS = 24;

	private CockpitDisplays() {
	}

	public static final class Data {
		float roll;
		float pitch;
		float throttle;
		boolean afterburner;
		float spool;
		int missiles;
		int maxMissiles;
		float ammo;
		float flares;
		boolean warning;
		boolean caution;
		boolean blink;
		int contactCount;
		/** x, y in -1..1 (y ahead), kind: 0 aircraft, 1 friendly fighter, 2 missile. */
		final float[] contacts = new float[MAX_CONTACTS * 3];
	}

	public static void extract(FighterEntity jet, Data d, float partialTick, boolean own) {
		d.roll = jet.getRoll(partialTick);
		d.pitch = -jet.getViewXRot(partialTick);
		d.throttle = jet.throttle();
		d.afterburner = jet.isAfterburner();
		d.spool = jet.spool();
		d.missiles = jet.missiles();
		d.maxMissiles = jet.type().missiles;
		d.ammo = jet.ammo() / (float) jet.type().gunRounds;
		d.flares = jet.flares() / 24.0F;
		d.warning = jet.warning() > 0;
		d.caution = !jet.onGround() && jet.speed() < jet.type().stallSpeed || jet.health() < jet.type().maxHealth * 0.35F;
		d.blink = (jet.tickCount / 5) % 2 == 0;
		d.contactCount = 0;
		Minecraft mc = Minecraft.getInstance();
		if (!own || mc.level == null) {
			return;
		}
		// heading-up: rotate the world so the nose points up the display
		double yaw = Math.toRadians(jet.getViewYRot(partialTick));
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		Vec3 me = jet.getPosition(partialTick);
		for (Entity e : mc.level.entitiesForRendering()) {
			if (d.contactCount >= MAX_CONTACTS) {
				break;
			}
			if (e == jet || !(e instanceof AirThreat || e instanceof AirMissileEntity) || e.onGround()) {
				continue;
			}
			double dx = e.getX() - me.x;
			double dz = e.getZ() - me.z;
			double ahead = dx * fx + dz * fz;
			double right = -(dx * fz - dz * fx);
			if (ahead * ahead + right * right > TSD_RANGE * TSD_RANGE) {
				continue;
			}
			int i = d.contactCount++ * 3;
			d.contacts[i] = (float) (right / TSD_RANGE);
			d.contacts[i + 1] = (float) (ahead / TSD_RANGE);
			d.contacts[i + 2] = e instanceof AirMissileEntity ? 2 : e instanceof FighterEntity ? 1 : 0;
		}
	}

	/** Draws onto the panel face of the cockpit built around a pilot's eye at (0, eye, 0.95). */
	public static void draw(PoseStack.Pose pose, VertexConsumer c, Data d, boolean f22, float eye) {
		float y = eye + 0.72F - 0.017F;
		if (f22) {
			adi(pose, c, y, -0.26F, 0.49F, 0.095F, d);
			tsd(pose, c, y, 0.0F, 0.415F, 0.1F, d);
			stores(pose, c, y, 0.26F, 0.49F, 0.095F, d);
			// up-front display: a strip that lights amber or red with a caution or warning
			int ufd = d.warning && d.blink ? 0xFFE02020 : d.caution && d.blink ? 0xFFE0A020 : 0xFF103018;
			rect(pose, c, y, -0.1F, 0.575F, 0.1F, 0.69F, ufd);
		} else {
			// the panoramic display, split into its usual portals
			rect(pose, c, y, -0.32F, 0.43F, 0.32F, 0.77F, 0xFF05080C);
			adi(pose, c, y - 0.001F, -0.22F, 0.6F, 0.095F, d);
			tsd(pose, c, y - 0.001F, 0.0F, 0.6F, 0.1F, d);
			stores(pose, c, y - 0.001F, 0.22F, 0.6F, 0.095F, d);
		}
		// master warning (red) and caution (amber) on the glare shield edge
		float lz = (f22 ? 0.72F : 0.8F) - 0.015F;
		float ly = eye + 0.655F;
		rect(pose, c, ly, -0.36F, lz, -0.3F, lz + 0.03F, d.warning && d.blink ? 0xFFFF2020 : 0xFF301010);
		rect(pose, c, ly, 0.3F, lz, 0.36F, lz + 0.03F, d.caution && d.blink ? 0xFFFFB020 : 0xFF302010);
	}

	/** Attitude: centre (cx, cz), half size h. */
	private static void adi(PoseStack.Pose pose, VertexConsumer c, float y, float cx, float cz, float h, Data d) {
		int n = 18;
		float cell = 2.0F * h / n;
		double phi = Math.toRadians(d.roll);
		double nx = -Math.sin(phi);
		double nz = Math.cos(phi);
		double scale = 1.0 / 30.0; // half the display spans 30 degrees of pitch
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double px = -1.0 + (i + 0.5) * 2.0 / n;
				double pz = -1.0 + (j + 0.5) * 2.0 / n;
				double up = px * nx + pz * nz + d.pitch * scale; // degrees above the horizon, scaled
				int col;
				if (Math.abs(up) < 0.06) {
					col = 0xFFFFFFFF;
				} else {
					double deg = up / scale;
					double along = px * nz - pz * nx;
					boolean ladder = Math.abs(deg - Math.round(deg / 10.0) * 10.0) < 0.9 && Math.abs(along) < 0.35;
					col = ladder ? 0xFFE0E0E0 : up > 0 ? 0xFF2A78D0 : 0xFF7A4A22;
				}
				float x0 = cx - h + i * cell;
				float z0 = cz - h + j * cell;
				rect(pose, c, y, x0, z0, x0 + cell, z0 + cell, col);
			}
		}
		// the fixed aircraft symbol: wings and a dot, in yellow
		float yy = y - 0.002F;
		rect(pose, c, yy, cx - h * 0.6F, cz - h * 0.03F, cx - h * 0.15F, cz + h * 0.03F, 0xFFFFD020);
		rect(pose, c, yy, cx + h * 0.15F, cz - h * 0.03F, cx + h * 0.6F, cz + h * 0.03F, 0xFFFFD020);
		rect(pose, c, yy, cx - h * 0.04F, cz - h * 0.04F, cx + h * 0.04F, cz + h * 0.04F, 0xFFFFD020);
	}

	/** Tactical situation display: heading-up, ownship in the middle. */
	private static void tsd(PoseStack.Pose pose, VertexConsumer c, float y, float cx, float cz, float h, Data d) {
		rect(pose, c, y, cx - h, cz - h, cx + h, cz + h, 0xFF06121C);
		float yy = y - 0.002F;
		for (float ring : new float[] {0.5F, 1.0F}) {
			for (int k = 0; k < 32; k++) {
				double a = k * Math.PI * 2.0 / 32.0;
				float x = cx + (float) Math.sin(a) * h * ring * 0.95F;
				float z = cz + (float) Math.cos(a) * h * ring * 0.95F;
				rect(pose, c, yy, x - 0.002F, z - 0.002F, x + 0.002F, z + 0.002F, 0xFF3A6A80);
			}
		}
		// ownship
		rect(pose, c, yy, cx - 0.006F, cz - 0.008F, cx + 0.006F, cz + 0.004F, 0xFFFFFFFF);
		rect(pose, c, yy, cx - 0.002F, cz + 0.004F, cx + 0.002F, cz + 0.014F, 0xFFFFFFFF);
		for (int i = 0; i < d.contactCount; i++) {
			float x = cx + d.contacts[i * 3] * h * 0.95F;
			float z = cz + d.contacts[i * 3 + 1] * h * 0.95F;
			int kind = (int) d.contacts[i * 3 + 2];
			int col = kind == 2 ? (d.blink ? 0xFFFF3030 : 0xFF801818) : kind == 1 ? 0xFF40E0FF : 0xFF40FF60;
			float r = kind == 2 ? 0.004F : 0.006F;
			rect(pose, c, yy - 0.001F, x - r, z - r, x + r, z + r, col);
		}
	}

	/** Engine and weapons. */
	private static void stores(PoseStack.Pose pose, VertexConsumer c, float y, float cx, float cz, float h, Data d) {
		rect(pose, c, y, cx - h, cz - h, cx + h, cz + h, 0xFF050A08);
		float yy = y - 0.002F;
		float bottom = cz - h * 0.85F;
		float top = cz + h * 0.85F;
		// thrust and spool: vertical bars on the left
		bar(pose, c, yy, cx - h * 0.85F, bottom, cx - h * 0.6F, top, d.throttle, d.afterburner ? 0xFFFFA020 : 0xFF40FF60);
		bar(pose, c, yy, cx - h * 0.5F, bottom, cx - h * 0.25F, top, d.spool, d.spool >= 1.0F ? 0xFF40FF60 : 0xFFFFFFFF);
		// gun and flares: horizontal bars on the right
		bar(pose, c, yy, cx, cz + h * 0.45F, cx + h * 0.85F, cz + h * 0.65F, d.ammo, 0xFFE0E0E0, true);
		bar(pose, c, yy, cx, cz + h * 0.1F, cx + h * 0.85F, cz + h * 0.3F, d.flares, 0xFFFFD020, true);
		// a block per missile, white while it is still on its rail
		int n = Math.max(1, d.maxMissiles);
		float w = h * 0.85F / n;
		for (int i = 0; i < n; i++) {
			float x0 = cx + i * w;
			rect(pose, c, yy, x0 + w * 0.15F, cz - h * 0.8F, x0 + w * 0.85F, cz - h * 0.2F, i < d.missiles ? 0xFFF0F0F0 : 0xFF303030);
		}
	}

	private static void bar(PoseStack.Pose pose, VertexConsumer c, float y, float x0, float z0, float x1, float z1, float fill, int col) {
		bar(pose, c, y, x0, z0, x1, z1, fill, col, false);
	}

	private static void bar(PoseStack.Pose pose, VertexConsumer c, float y, float x0, float z0, float x1, float z1, float fill, int col,
		boolean horizontal) {
		rect(pose, c, y, x0, z0, x1, z1, 0xFF203020);
		fill = Mth.clamp(fill, 0.0F, 1.0F);
		if (horizontal) {
			rect(pose, c, y - 0.001F, x0, z0, x0 + (x1 - x0) * fill, z1, col);
		} else {
			rect(pose, c, y - 0.001F, x0, z0, x1, z0 + (z1 - z0) * fill, col);
		}
	}

	/** A self-lit rectangle on the plane at depth y, facing the pilot (towards -y). */
	private static void rect(PoseStack.Pose pose, VertexConsumer c, float y, float x0, float z0, float x1, float z1, int argb) {
		vertex(pose, c, x0, y, z0, argb);
		vertex(pose, c, x1, y, z0, argb);
		vertex(pose, c, x1, y, z1, argb);
		vertex(pose, c, x0, y, z1, argb);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, int argb) {
		c.addVertex(pose, x, y, z).setColor(argb).setUv(U, V).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0.0F, -1.0F, 0.0F);
	}
}
