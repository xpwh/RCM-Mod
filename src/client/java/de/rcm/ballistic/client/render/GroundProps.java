package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Guns and ammunition dropped on the ground lie there like the real things instead of bobbing and spinning
 * in the air: full size, flat on their side. Thrown, they tumble end over end; landing, they clatter, bounce
 * a little, rock and settle on whichever side they came down on, turned the way they fell, and a stack is
 * a small pile. Only the look is simulated here (on the client, per item entity); where the item is and how
 * it slides is still the game's own item physics.
 */
public final class GroundProps {
	/** What lies on the ground: its extent in model space (min x y z, max x y z), and the sound it lands with. */
	record Prop(float[] bounds, SoundEvent sound, float pitch, float volume) {
		float centre(int axis) {
			return (this.bounds[axis] + this.bounds[axis + 3]) * 0.5F;
		}

		float size(int axis) {
			return this.bounds[axis + 3] - this.bounds[axis];
		}

		int thinAxis() {
			int a = 0;
			for (int i = 1; i < 3; i++) {
				if (size(i) < size(a)) {
					a = i;
				}
			}
			return a;
		}

		int longAxis() {
			int a = 0;
			for (int i = 1; i < 3; i++) {
				if (size(i) > size(a)) {
					a = i;
				}
			}
			return a;
		}
	}

	private static final class Body {
		final Quaternionf q = new Quaternionf();
		final Quaternionf prev = new Quaternionf();
		final Quaternionf rest = new Quaternionf();
		final Vector3f spin = new Vector3f();
		boolean onGround;
		float lastVy;
		long seen;
	}

	private static @Nullable Map<Item, Prop> props;
	private static final Int2ObjectMap<Body> BODIES = new Int2ObjectOpenHashMap<>();
	private static final Vector3f[] AXES = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
	private static long clock;

	private GroundProps() {
	}

	private static Map<Item, Prop> props() {
		if (props == null) {
			Map<Item, Prop> m = new IdentityHashMap<>();
			Prop akMag = new Prop(centred(PropItemRenderer.mesh("ak_magazine")), ModRegistry.MAG_DROP, 1.0F, 0.7F);
			m.put(ModRegistry.AK_MAG, akMag);
			m.put(ModRegistry.AK_MAG_TRACER, akMag);
			m.put(ModRegistry.AK_MAG_TRACER_RED, akMag);
			m.put(ModRegistry.GLOCK_MAG, new Prop(centred(GlockItemRenderer.MAG), ModRegistry.MAG_DROP, 1.25F, 0.55F));
			m.put(ModRegistry.GLOCK_MAG_EXTENDED, new Prop(centred(GlockItemRenderer.MAG_EXT), ModRegistry.MAG_DROP, 1.15F, 0.6F));
			m.put(ModRegistry.SHOTGUN_SHELL, new Prop(centred(ShotgunItemRenderer.SHELL), ModRegistry.MAG_DROP, 1.8F, 0.3F));
			m.put(ModRegistry.RPG_ROCKET, new Prop(centred(PropItemRenderer.RPG_ROUND), ModRegistry.METAL_THUD, 1.4F, 0.5F));
			m.put(ModRegistry.AK47, new Prop(union(AkItemRenderer.BODY, AkItemRenderer.MAG), ModRegistry.METAL_THUD, 1.2F, 0.8F));
			m.put(ModRegistry.GLOCK, new Prop(union(GlockItemRenderer.FRAME, GlockItemRenderer.SLIDE, GlockItemRenderer.MAG), ModRegistry.MAG_DROP, 0.85F, 0.8F));
			m.put(ModRegistry.SHOTGUN, new Prop(ShotgunItemRenderer.GUN.bounds(), ModRegistry.METAL_THUD, 1.15F, 0.8F));
			m.put(ModRegistry.ROCKET_LAUNCHER, new Prop(union(Rpg7ItemRenderer.LAUNCHER, Rpg7ItemRenderer.WARHEAD), ModRegistry.METAL_THUD, 0.9F, 0.9F));
			m.put(ModRegistry.GRENADE, new Prop(union(GrenadeRenderers.BODY, GrenadeRenderers.LEVER), ModRegistry.MAG_DROP, 0.9F, 0.5F));
			m.put(ModRegistry.SMOKE_GRENADE, new Prop(union(GrenadeRenderers.SMOKE_BODY, GrenadeRenderers.CAN_LEVER), ModRegistry.MAG_DROP, 0.85F, 0.5F));
			m.put(ModRegistry.FLASHBANG, new Prop(union(GrenadeRenderers.FLASH_BODY, GrenadeRenderers.TUBE_LEVER), ModRegistry.MAG_DROP, 1.0F, 0.5F));
			props = m;
		}
		return props;
	}

	/** Ammunition is drawn centred on the item's middle ({@link PropItemRenderer}): its extent about that. */
	private static float[] centred(BoxMesh mesh) {
		float[] b = mesh.bounds();
		for (int k = 0; k < 3; k++) {
			float h = (b[k + 3] - b[k]) * 0.5F;
			b[k] = -h;
			b[k + 3] = h;
		}
		return b;
	}

	private static float[] union(BoxMesh... meshes) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (BoxMesh mesh : meshes) {
			float[] m = mesh.bounds();
			for (int k = 0; k < 3; k++) {
				b[k] = Math.min(b[k], m[k]);
				b[k + 3] = Math.max(b[k + 3], m[k + 3]);
			}
		}
		return b;
	}

	static @Nullable Prop prop(Item item) {
		return props().get(item);
	}

	// ------------------------------------------------------------------ simulation (per client tick)

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			if (mc.level == null) {
				BODIES.clear();
			}
			return;
		}
		clock++;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item)) {
				continue;
			}
			Prop prop = prop(item.getItem().getItem());
			if (prop == null) {
				continue;
			}
			Body body = BODIES.get(item.getId());
			if (body == null) {
				body = spawn(item, prop);
				BODIES.put(item.getId(), body);
			}
			step(mc, item, prop, body);
			body.seen = clock;
		}
		BODIES.values().removeIf(b -> b.seen != clock);
	}

	private static Body spawn(ItemEntity item, Prop prop) {
		Body b = new Body();
		RandomSource r = RandomSource.create(item.getId() * 31L + 7L);
		// lying on one side or the other, turned any way
		Quaternionf lying = new Quaternionf().rotateY(r.nextFloat() * Mth.TWO_PI).mul(lie(prop.thinAxis(), r.nextBoolean()));
		b.onGround = item.onGround();
		if (b.onGround && item.getDeltaMovement().horizontalDistanceSqr() < 0.001) {
			b.q.set(lying); // was lying there already (loaded in, or dropped onto the spot)
		} else {
			// thrown: some random way up, tumbling about a random axis, faster the harder it was thrown
			b.q.set(lying).rotateX((r.nextFloat() - 0.5F) * 2.4F).rotateZ((r.nextFloat() - 0.5F) * 2.4F);
			float speed = (float) item.getDeltaMovement().length();
			Vector3f axis = new Vector3f(r.nextFloat() - 0.5F, (r.nextFloat() - 0.5F) * 0.4F, r.nextFloat() - 0.5F).normalize();
			b.spin.set(axis).mul(0.18F + speed * 1.1F + r.nextFloat() * 0.1F);
		}
		b.rest.set(b.q);
		b.prev.set(b.q);
		return b;
	}

	private static void step(Minecraft mc, ItemEntity item, Prop prop, Body b) {
		b.prev.set(b.q);
		boolean ground = item.onGround() || item.isInWater() && item.getDeltaMovement().lengthSqr() < 0.002;
		float vy = (float) (item.getY() - item.yo);
		if (ground && !b.onGround) {
			// landing: a clatter as loud as the fall was hard, and a kick that makes it rock or flip over
			float impact = -Math.min(b.lastVy, vy);
			if (impact > 0.06F) {
				float volume = prop.volume() * Mth.clamp(impact * 2.2F, 0.15F, 1.0F);
				mc.level.playLocalSound(item.getX(), item.getY(), item.getZ(), prop.sound(), SoundSource.NEUTRAL, volume,
					prop.pitch() * (0.92F + item.getRandom().nextFloat() * 0.16F), false);
				RandomSource r = item.getRandom();
				b.spin.add((r.nextFloat() - 0.5F) * impact * 1.6F, 0.0F, (r.nextFloat() - 0.5F) * impact * 1.6F);
			}
			b.rest.set(rest(prop, b.q));
		}
		b.onGround = ground;
		b.lastVy = vy;

		float rate = b.spin.length();
		if (rate > 1.0E-4F) {
			b.q.premul(new Quaternionf().rotateAxis(rate, b.spin.x / rate, b.spin.y / rate, b.spin.z / rate));
		}
		if (ground) {
			// friction and the side it lies on pull it flat; sliding turns it a little
			b.spin.mul(0.55F);
			double slide = item.getDeltaMovement().horizontalDistance();
			if (slide > 0.01) {
				float turn = (float) slide * 0.6F * ((item.getId() & 1) == 0 ? 1.0F : -1.0F);
				b.rest.premul(new Quaternionf().rotateY(turn));
			}
			b.q.slerp(b.rest, 0.3F).normalize();
		} else {
			b.spin.mul(0.985F); // air drag
			b.rest.set(rest(prop, b.q));
		}
	}

	/** Rotation that turns the thin axis (or its negative) straight up. */
	private static Quaternionf lie(int thin, boolean positive) {
		return switch (thin) {
			case 0 -> new Quaternionf().rotateZ(positive ? Mth.HALF_PI : -Mth.HALF_PI);
			case 2 -> new Quaternionf().rotateX(positive ? -Mth.HALF_PI : Mth.HALF_PI);
			default -> positive ? new Quaternionf() : new Quaternionf().rotateX(Mth.PI);
		};
	}

	/** The resting pose nearest to {@code q}: on the side that faces most up, the long axis kept pointing the same way. */
	private static Quaternionf rest(Prop prop, Quaternionf q) {
		int thin = prop.thinAxis();
		Vector3f up = q.transform(new Vector3f(AXES[thin]));
		Quaternionf lie = lie(thin, up.y >= 0.0F);
		int along = prop.longAxis();
		if (along == thin) {
			along = (thin + 1) % 3;
		}
		Vector3f now = q.transform(new Vector3f(AXES[along]));
		Vector3f laid = lie.transform(new Vector3f(AXES[along]));
		float yaw = (float) (Math.atan2(now.x, now.z) - Math.atan2(laid.x, laid.z));
		if (now.x * now.x + now.z * now.z < 1.0E-4F) {
			yaw = 0.0F; // standing on end: any heading will do
		}
		return new Quaternionf().rotateY(yaw).mul(lie);
	}

	// ------------------------------------------------------------------ drawing

	/** Draws a dropped gun or round lying on the ground; false for every other item (drawn as usual). */
	public static boolean submit(ItemEntityRenderState state, int entityId, Item itemType, PoseStack poseStack, SubmitNodeCollector collector, float partialTick) {
		Prop prop = prop(itemType);
		if (prop == null || state.item.isEmpty()) {
			return false;
		}
		Body b = BODIES.get(entityId);
		Quaternionf q = b == null ? new Quaternionf().mul(lie(prop.thinAxis(), true)) : new Quaternionf(b.prev).slerp(b.q, partialTick);
		RandomSource r = RandomSource.create(state.seed * 17L + entityId);
		int copies = Math.max(1, state.count);
		for (int i = 0; i < copies; i++) {
			poseStack.pushPose();
			if (i > 0) {
				// the rest of a stack scattered in a little heap round the first
				float spread = Math.max(prop.size(0), Math.max(prop.size(1), prop.size(2))) * 0.35F + 0.04F;
				poseStack.translate((r.nextFloat() - 0.5F) * 2.0F * spread, 0.0F, (r.nextFloat() - 0.5F) * 2.0F * spread);
			}
			Quaternionf qi = i == 0 ? q : new Quaternionf().rotateY(r.nextFloat() * Mth.TWO_PI).mul(q);
			// lift it so its lowest corner just touches the ground
			float low = Float.MAX_VALUE;
			Vector3f c = new Vector3f();
			for (int k = 0; k < 8; k++) {
				c.set(prop.bounds()[(k & 1) == 0 ? 0 : 3] - prop.centre(0), prop.bounds()[(k & 2) == 0 ? 1 : 4] - prop.centre(1),
					prop.bounds()[(k & 4) == 0 ? 2 : 5] - prop.centre(2));
				low = Math.min(low, qi.transform(c).y);
			}
			poseStack.translate(0.0F, -low + 0.005F + i * 0.002F, 0.0F);
			poseStack.mulPose(qi);
			poseStack.translate(-prop.centre(0), -prop.centre(1), -prop.centre(2));
			state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
		return true;
	}
}
