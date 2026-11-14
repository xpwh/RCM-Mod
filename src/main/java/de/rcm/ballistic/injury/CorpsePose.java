package de.rcm.ballistic.injury;

import com.mojang.serialization.Codec;
import de.rcm.ballistic.BallisticMissiles;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where a body lying dead is and how it lies - the same for the server (which rounds hit it, how it moves)
 * and the client (how it is drawn). A body has its resting pose - an animal fallen on its side, a person on
 * their face or back, as the game draws them - in its "body frame" (the entity's own, turned with its body).
 * On top of that it has a turn of its own ({@link #TURN}, synced): thrown, it tumbles; rolling, it turns over;
 * at rest it lies flat again, on whichever side it came down. The turn is about the middle of the body.
 * <p>
 * In world terms, a point {@code p} of the body frame lies at {@code pos + C + Q (L + R p - C)}: {@code R} the
 * body's facing, {@code L} its lift off the ground, {@code Q} the turn, {@code C} the body's middle ({@code L + R c}).
 */
public final class CorpsePose {
	/** The body's turn, as a quaternion {x, y, z, w} (identity: lying as it fell). */
	public static final AttachmentType<List<Float>> TURN = AttachmentRegistry.create(BallisticMissiles.id("corpse_turn"),
		b -> b.persistent(Codec.FLOAT.listOf()).syncWith(ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(4)), AttachmentSyncPredicate.all()));

	private CorpsePose() {
	}

	/** Registers {@link #TURN} at start-up, on both sides, before any body is synced. */
	public static void init() {
		// loading the class does it
	}

	public static Quaternionf turn(Entity e) {
		List<Float> q = e.getAttached(TURN);
		if (q == null || q.size() != 4) {
			return new Quaternionf();
		}
		return new Quaternionf(q.get(0), q.get(1), q.get(2), q.get(3));
	}

	public static void setTurn(Entity e, Quaternionf q) {
		e.setAttached(TURN, List.of(q.x, q.y, q.z, q.w));
	}

	/** A person's body ({@link Corpses}) or a dead creature's (lying, its death fall over). */
	public static boolean lying(Entity e) {
		return CorpseHits.isCorpse(e);
	}

	/** The body's facing: the turn the renderer gives the model. */
	public static Quaternionf facing(LivingEntity e) {
		return new Quaternionf().rotationY((180.0F - e.yBodyRot) * Mth.DEG_TO_RAD);
	}

	/** How far a dead creature lying on its side is lifted, so it lies on the ground and not half in it. */
	public static float lift(LivingEntity e) {
		if (e instanceof Mannequin) {
			return 0.0F;
		}
		float fall = Math.min(1.0F, Mth.sqrt(Math.max(0.0F, (e.deathTime - 1.0F) / 20.0F * 1.6F)));
		return e.getBbWidth() * 0.5F * fall;
	}

	/** The lying body's extent in its body frame (before the lift): {min x, y, z, max x, y, z}. */
	public static float[] box(LivingEntity e) {
		if (e instanceof Mannequin) {
			// a person flat on the ground, placed by the middle of the body (Corpses.HALF from the feet)
			return new float[] {-0.45F, 0.0F, -0.95F, 0.45F, 0.34F, 0.95F};
		}
		// fallen over on its side: what stood h high now reaches h along the ground; its length is along its facing
		float w = e.getBbWidth();
		float h = e.getBbHeight();
		return new float[] {-h, -w * 0.5F, -w * 0.7F, 0.0F, w * 0.5F, w * 0.7F};
	}

	/** The middle of the lying body, relative to its feet, in world directions. */
	public static Vector3f centre(LivingEntity e) {
		float[] b = box(e);
		Vector3f c = new Vector3f((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
		return facing(e).transform(c).add(0, lift(e), 0);
	}

	/** A world point as a point of the body frame (where on the body it is). */
	public static Vector3f toBody(LivingEntity e, Vec3 world) {
		Vector3f c = centre(e);
		Vector3f p = new Vector3f((float) (world.x - e.getX()), (float) (world.y - e.getY()), (float) (world.z - e.getZ())).sub(c);
		turn(e).conjugate().transform(p);
		p.add(c).sub(0, lift(e), 0);
		return facing(e).conjugate().transform(p);
	}

	/** A point of the body frame in the world. */
	public static Vec3 toWorld(LivingEntity e, Vector3f body) {
		Vector3f c = centre(e);
		Vector3f p = facing(e).transform(new Vector3f(body)).add(0, lift(e), 0).sub(c);
		turn(e).transform(p).add(c);
		return new Vec3(e.getX() + p.x, e.getY() + p.y, e.getZ() + p.z);
	}

	/** Where the line from {@code from} to {@code to} first meets the lying body (grown by {@code grow}), if it does. */
	public static Optional<Vec3> clip(LivingEntity e, Vec3 from, Vec3 to, float grow) {
		Vector3f a = toBody(e, from);
		Vector3f b = toBody(e, to);
		float[] box = box(e);
		AABB local = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]).inflate(grow);
		return local.clip(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z)).map(p -> toWorld(e, new Vector3f((float) p.x, (float) p.y, (float) p.z)));
	}

	/** A box round the whole lying body, in the world (to find it at all). */
	public static AABB bounds(LivingEntity e) {
		float[] b = box(e);
		AABB out = null;
		for (int i = 0; i < 8; i++) {
			Vec3 p = toWorld(e, new Vector3f(b[(i & 1) == 0 ? 0 : 3], b[(i & 2) == 0 ? 1 : 4], b[(i & 4) == 0 ? 2 : 5]));
			AABB one = new AABB(p, p);
			out = out == null ? one : out.minmax(one);
		}
		return out;
	}
}
