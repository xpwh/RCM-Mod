package de.rcm.ballistic.gun;

import de.rcm.ballistic.entity.FighterEntity;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.JetType;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * C4 stuck to something that moves - a mob, a player, a truck, a helicopter, a jet, a missile on its pad: where on it
 * the charge sits and how that turns as it turns. Everything is kept in the host's model space (the space its renderer
 * draws it in: a pivot, then its attitude), so the charge rides along exactly as the model moves, on both sides.
 * <p>
 * The aircraft are much larger than their hitboxes, so for them the charge goes onto the fuselage itself: their
 * fuselages are lofted through cross-sections (JetRenderer / FighterRenderer), and the same sections are kept here -
 * a ray is marched into them to find where you are pointing, and the charge is laid flat on that panel.
 */
public final class C4Mount {
	private C4Mount() {
	}

	/** Can a charge be stuck to {@code e}? */
	public static boolean canHost(Entity e, @Nullable Entity placer) {
		if (e == placer || e.isRemoved() || e.isSpectator() || e instanceof C4ChargeEntity || e instanceof ItemEntity
			|| e instanceof ExperienceOrb) {
			return false;
		}
		return e instanceof LivingEntity || e.isPickable();
	}

	// ------------------------------------------------------------------ the host's frame

	/** Where the host's model space starts (its renderer's translate before the attitude). */
	public static Vec3 pivot(Entity host, float partial) {
		Vec3 p = host.getPosition(partial);
		if (host instanceof JetEntity) {
			return p.add(0.0, 0.8, 0.0);
		}
		if (host instanceof FighterEntity) {
			// FighterRenderer.GEAR_HEIGHT
			return p.add(0.0, 2.3, 0.0);
		}
		return p;
	}

	/** Model space to world: the host's attitude as its renderer turns it. */
	public static Quaternionf attitude(Entity host, float partial) {
		Quaternionf q = new Quaternionf();
		if (host instanceof JetEntity jet) {
			orient(q, jet.getDir());
			q.rotateY(jet.getBank());
		} else if (host instanceof FighterEntity fighter) {
			orient(q, fighter.forward(partial));
			q.rotateY(fighter.getRoll(partial) * Mth.DEG_TO_RAD);
		} else if (host instanceof MissileEntity missile) {
			Vec3 dir;
			if (host.level().isClientSide() || missile.getState() != MissileEntity.FLIGHT) {
				dir = missile.getNoseDirection(partial);
			} else {
				Vec3 v = host.getDeltaMovement();
				dir = v.lengthSqr() > 1.0E-4 ? v.normalize() : missile.getNoseDirection(partial);
			}
			orient(q, dir);
		} else {
			float yaw = host instanceof LivingEntity living ? Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot) : host.getYRot(partial);
			q.rotationY(-yaw * Mth.DEG_TO_RAD);
		}
		return q;
	}

	/** As MissileRenderer.orient: model +y along {@code dir}, +z up as far as it goes. */
	static void orient(Quaternionf out, Vec3 dir) {
		Vector3f forward = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
		if (forward.lengthSquared() < 1.0E-8F) {
			forward.set(0, 1, 0);
		}
		forward.normalize();
		Vector3f right = new Vector3f(forward).cross(0, 1, 0);
		if (right.lengthSquared() < 1.0E-4F) {
			right.set(1, 0, 0);
		} else {
			right.normalize();
		}
		Vector3f up = new Vector3f(right).cross(forward).normalize();
		out.setFromNormalized(new Matrix3f(right, forward, up));
	}

	/** The charge's own turn on a block face (as C4Models.orient): its +y out of {@code face}. */
	public static Quaternionf faceRotation(Direction face, float spin) {
		Quaternionf q = new Quaternionf().rotationY(-spin * Mth.DEG_TO_RAD);
		return switch (face) {
			case UP -> q;
			case DOWN -> q.rotateX(Mth.PI);
			default -> new Quaternionf().rotationY(-face.toYRot() * Mth.DEG_TO_RAD).rotateX(Mth.HALF_PI);
		};
	}

	// ------------------------------------------------------------------ aircraft fuselages

	/**
	 * A fuselage as cross-sections {y, bottom half-width, bottom z, chine half-width, chine z, top half-width, top z},
	 * nose first, in the model's space (x right, y forward, z up). From the lofts in JetRenderer / FighterRenderer.
	 */
	record Hull(float[][] stations) {
		float front() {
			return this.stations[0][0];
		}

		float back() {
			return this.stations[this.stations.length - 1][0];
		}

		/** The section at {@code y} (clamped onto the fuselage), interpolated. */
		float[] at(float y) {
			float[][] s = this.stations;
			if (y >= s[0][0]) {
				return s[0];
			}
			for (int i = 0; i + 1 < s.length; i++) {
				float[] a = s[i];
				float[] b = s[i + 1];
				if (y >= b[0]) {
					float t = (a[0] - y) / (a[0] - b[0]);
					float[] out = new float[7];
					for (int k = 0; k < 7; k++) {
						out[k] = Mth.lerp(t, a[k], b[k]);
					}
					return out;
				}
			}
			return s[s.length - 1];
		}

		/** The section's outline, counter-clockwise in (x, z). */
		static float[][] outline(float[] s) {
			return new float[][] {{-s[1], s[2]}, {s[1], s[2]}, {s[3], s[4]}, {s[5], s[6]}, {-s[5], s[6]}, {-s[3], s[4]}};
		}

		boolean inside(Vector3f p) {
			if (p.y > this.front() || p.y < this.back()) {
				return false;
			}
			float[][] o = outline(this.at(p.y));
			for (int i = 0; i < o.length; i++) {
				float[] a = o[i];
				float[] b = o[(i + 1) % o.length];
				// left of every edge (counter-clockwise): inside
				if ((b[0] - a[0]) * (p.z - a[1]) - (b[1] - a[1]) * (p.x - a[0]) < 0.0F) {
					return false;
				}
			}
			return true;
		}

		/** Out from the section's middle through {@code p} to the skin: the point there and the panel's outward normal. */
		Vector3f[] skin(Vector3f p) {
			float y = Mth.clamp(p.y, this.back() + 0.3F, this.front() - 0.3F);
			float[] s = this.at(y);
			float cz = (s[2] + s[6]) * 0.5F;
			float dx = p.x;
			float dz = p.z - cz;
			if (dx * dx + dz * dz < 1.0E-6F) {
				dz = 1.0F;
			}
			float[][] o = outline(s);
			float best = Float.MAX_VALUE;
			float nx = 0.0F;
			float nz = 1.0F;
			for (int i = 0; i < o.length; i++) {
				float[] a = o[i];
				float[] b = o[(i + 1) % o.length];
				float ex = b[0] - a[0];
				float ez = b[1] - a[1];
				// (0, cz) + t (dx, dz) = a + u e
				float den = dx * ez - dz * ex;
				if (Math.abs(den) < 1.0E-7F) {
					continue;
				}
				float ax = a[0];
				float az = a[1] - cz;
				float t = (ax * ez - az * ex) / den;
				float u = (ax * dz - az * dx) / den;
				if (t > 0.0F && u >= -1.0E-4F && u <= 1.0001F && t < best) {
					best = t;
					nx = ez;
					nz = -ex;
				}
			}
			if (best == Float.MAX_VALUE) {
				best = 0.0F;
			}
			Vector3f n = new Vector3f(nx, 0.0F, nz).normalize();
			return new Vector3f[] {new Vector3f(dx * best, y, cz + dz * best), n};
		}

		AABB box() {
			float w = 0.0F;
			float lo = Float.MAX_VALUE;
			float hi = -Float.MAX_VALUE;
			for (float[] s : this.stations) {
				w = Math.max(w, Math.max(s[1], Math.max(s[3], s[5])));
				lo = Math.min(lo, s[2]);
				hi = Math.max(hi, s[6]);
			}
			return new AABB(-w, this.back(), lo, w, this.front(), hi);
		}
	}

	private static Hull single(float[]... lofts) {
		// a one-piece loft {y, bottom half-width, bottom z, top half-width, top z}: the chine half way
		float[][] s = new float[lofts.length][];
		for (int i = 0; i < lofts.length; i++) {
			float[] l = lofts[i];
			s[i] = new float[] {l[0], l[1], l[2], (l[1] + l[3]) * 0.5F, (l[2] + l[4]) * 0.5F, l[3], l[4]};
		}
		return new Hull(s);
	}

	private static final Hull STRIKE = new Hull(new float[][] {
		{5.6F, 0.2F, -0.3F, 0.42F, 0.0F, 0.2F, 0.36F}, {3.6F, 0.34F, -0.44F, 0.6F, 0.0F, 0.3F, 0.55F},
		{1.2F, 0.62F, -0.56F, 0.78F, 0.0F, 0.45F, 0.62F}, {-0.8F, 0.92F, -0.6F, 1.28F, 0.0F, 0.82F, 0.62F},
		{-4.6F, 0.92F, -0.56F, 1.28F, 0.0F, 0.82F, 0.56F}, {-6.3F, 0.8F, -0.45F, 1.0F, 0.0F, 0.7F, 0.45F}});
	private static final Hull F22 = new Hull(new float[][] {
		{7.3F, 0.28F, -0.45F, 0.55F, 0.0F, 0.28F, 0.48F}, {5.6F, 0.42F, -0.62F, 0.75F, 0.0F, 0.4F, 0.68F},
		{3.5F, 0.72F, -0.76F, 0.98F, 0.0F, 0.56F, 0.8F}, {1.5F, 1.32F, -0.82F, 1.7F, 0.0F, 1.0F, 0.82F},
		{-1.0F, 1.38F, -0.82F, 1.8F, 0.0F, 1.1F, 0.82F}, {-5.5F, 1.22F, -0.76F, 1.75F, 0.0F, 1.06F, 0.76F},
		{-7.6F, 1.02F, -0.62F, 1.38F, 0.0F, 0.92F, 0.62F}, {-8.4F, 0.86F, -0.5F, 1.1F, 0.0F, 0.78F, 0.5F}});
	private static final Hull F35 = new Hull(new float[][] {
		{6.0F, 0.3F, -0.5F, 0.55F, 0.0F, 0.3F, 0.55F}, {4.6F, 0.55F, -0.76F, 0.8F, 0.0F, 0.45F, 0.78F},
		{3.0F, 0.82F, -0.96F, 1.08F, 0.0F, 0.62F, 0.92F}, {1.0F, 1.32F, -1.06F, 1.85F, 0.0F, 1.12F, 0.96F},
		{-1.5F, 1.42F, -1.06F, 1.95F, 0.0F, 1.22F, 0.96F}, {-4.5F, 1.3F, -0.96F, 1.78F, 0.0F, 1.1F, 0.86F},
		{-6.0F, 0.95F, -0.76F, 1.25F, 0.0F, 0.8F, 0.66F}, {-6.6F, 0.66F, -0.56F, 0.82F, 0.0F, 0.56F, 0.52F}});
	private static final Hull WARTHOG = single(
		new float[] {7.0F, 0.5F, -0.65F, 0.45F, 0.45F}, new float[] {4.6F, 0.68F, -0.8F, 0.55F, 0.8F},
		new float[] {1.0F, 0.72F, -0.85F, 0.6F, 0.78F}, new float[] {-3.0F, 0.6F, -0.65F, 0.5F, 0.62F},
		new float[] {-6.6F, 0.36F, -0.38F, 0.3F, 0.42F});
	private static final Hull GUNSHIP = single(
		new float[] {4.4F, 0.85F, -0.95F, 0.75F, 0.95F}, new float[] {3.0F, 1.0F, -1.0F, 0.95F, 1.15F},
		new float[] {-1.6F, 1.0F, -1.0F, 0.95F, 1.15F}, new float[] {-2.6F, 0.55F, -0.4F, 0.5F, 1.05F},
		new float[] {-8.6F, 0.22F, 0.35F, 0.2F, 0.85F});
	private static final Hull APACHE = single(
		new float[] {4.6F, 0.55F, -0.9F, 0.45F, 0.7F}, new float[] {2.8F, 0.75F, -1.0F, 0.6F, 1.0F},
		new float[] {0.8F, 0.95F, -1.0F, 0.8F, 1.2F}, new float[] {-1.8F, 0.9F, -0.8F, 0.75F, 1.1F},
		new float[] {-2.6F, 0.45F, -0.1F, 0.4F, 0.9F}, new float[] {-8.8F, 0.22F, 0.3F, 0.2F, 0.75F});
	private static final Hull REAPER = single(
		new float[] {4.3F, 0.42F, -0.45F, 0.38F, 0.75F}, new float[] {2.5F, 0.48F, -0.5F, 0.45F, 0.85F},
		new float[] {0.5F, 0.48F, -0.48F, 0.42F, 0.6F}, new float[] {-3.0F, 0.32F, -0.3F, 0.28F, 0.42F},
		new float[] {-4.6F, 0.18F, -0.12F, 0.16F, 0.22F});
	/** The flying wing: thick in the middle, thin and wide further back (JetRenderer.buildSpirit, roughly). */
	private static final Hull SPIRIT = new Hull(new float[][] {
		{4.6F, 0.8F, -0.4F, 0.8F, 0.0F, 0.8F, 0.4F}, {2.0F, 4.4F, -0.35F, 4.4F, 0.0F, 4.4F, 0.35F},
		{-1.0F, 8.5F, -0.2F, 8.5F, 0.0F, 8.5F, 0.2F}, {-3.2F, 6.0F, -0.15F, 6.0F, 0.0F, 6.0F, 0.15F}});

	static @Nullable Hull hull(Entity host) {
		if (host instanceof FighterEntity fighter) {
			return fighter.type() == de.rcm.ballistic.entity.FighterType.F22 ? F22 : F35;
		}
		if (host instanceof JetEntity jet) {
			JetType t = jet.getJetType();
			return switch (t) {
				case STRIKE -> STRIKE;
				case WARTHOG -> WARTHOG;
				case SPIRIT -> SPIRIT;
				case GUNSHIP -> GUNSHIP;
				case REAPER -> REAPER;
				case APACHE -> APACHE;
			};
		}
		return null;
	}

	// ------------------------------------------------------------------ aiming at it

	/** Where on {@code host} a charge would go: the point (world), its turn (world), how far along the ray. */
	public record Spot(Entity host, Vec3 at, Quaternionf rotation, double distance) {
	}

	/** The ray from {@code eye} to {@code end} against {@code host}: the spot it hits, or null. {@code spin}: for a flat top. */
	public static @Nullable Spot aim(Entity host, Vec3 eye, Vec3 end, float spin) {
		Hull hull = hull(host);
		if (hull == null) {
			AABB box = host.getBoundingBox().inflate(host.getPickRadius());
			var hit = box.clip(eye, end);
			if (hit.isEmpty() && !box.contains(eye)) {
				return null;
			}
			Vec3 at = hit.orElse(eye);
			Direction face = faceOf(box, at);
			float s = face.getAxis() == Direction.Axis.Y ? spin : 0.0F;
			return new Spot(host, at, faceRotation(face, s), eye.distanceTo(at));
		}
		// into model space, then march into the fuselage's sections
		Vec3 pivot = pivot(host, 1.0F);
		Quaternionf att = attitude(host, 1.0F);
		Quaternionf inv = new Quaternionf(att).conjugate();
		Vector3f a = inv.transform(new Vector3f((float) (eye.x - pivot.x), (float) (eye.y - pivot.y), (float) (eye.z - pivot.z)));
		Vector3f b = inv.transform(new Vector3f((float) (end.x - pivot.x), (float) (end.y - pivot.y), (float) (end.z - pivot.z)));
		var enter = hull.box().clip(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z));
		if (enter.isEmpty()) {
			return null;
		}
		Vector3f dir = new Vector3f(b).sub(a);
		float len = dir.length();
		dir.div(len);
		float t0 = (float) enter.get().distanceTo(new Vec3(a.x, a.y, a.z));
		for (float t = t0; t <= len; t += 0.05F) {
			Vector3f p = new Vector3f(dir).mul(t).add(a);
			if (hull.inside(p)) {
				Vector3f[] skin = hull.skin(p);
				Vector3f n = skin[1];
				// laid on the panel: long side along the fuselage, +y out of it
				Quaternionf local = new Quaternionf().setFromNormalized(new Matrix3f(new Vector3f(0, 1, 0), n, new Vector3f(n.z, 0, -n.x)));
				Vector3f w = att.transform(new Vector3f(skin[0]));
				return new Spot(host, pivot.add(w.x, w.y, w.z), new Quaternionf(att).mul(local), t);
			}
		}
		return null;
	}

	/** The face of {@code box} that {@code p} lies on. */
	static Direction faceOf(AABB box, Vec3 p) {
		Direction best = Direction.UP;
		double d = Math.abs(p.y - box.maxY);
		double[] ds = {Math.abs(p.y - box.minY), Math.abs(p.x - box.maxX), Math.abs(p.x - box.minX), Math.abs(p.z - box.maxZ), Math.abs(p.z - box.minZ)};
		Direction[] fs = {Direction.DOWN, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
		for (int i = 0; i < ds.length; i++) {
			if (ds[i] < d) {
				d = ds[i];
				best = fs[i];
			}
		}
		return best;
	}

	/** The nearest thing within {@code reach} along {@code player}'s look that a charge can go on, or null. */
	public static @Nullable Spot aim(Player player, double reach) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(reach));
		Spot best = null;
		// wide enough for an airliner-sized fuselage whose middle is out of reach
		AABB around = new AABB(eye, end).inflate(12.0);
		for (Entity e : player.level().getEntities(player, around, e -> canHost(e, player))) {
			Spot s = aim(e, eye, end, player.getYRot());
			if (s != null && (best == null || s.distance < best.distance)) {
				best = s;
			}
		}
		return best;
	}
}
