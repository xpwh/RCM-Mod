package de.rcm.ballistic.client.sun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.effect.BlastShader;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.LatePass;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.effect.SunStar;
import de.rcm.ballistic.sun.SunForgeBlock;
import de.rcm.ballistic.sun.SunNetwork.StrikePayload;
import de.rcm.ballistic.sun.SunTimeline;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Everything of a Sun Destroyer strike that is seen and heard, played from {@link SunTimeline} for the time since
 * the order: the countdown at the Helios complex (klaxons, the doors, the lift, the coils charging and arcing, the
 * cooling towers venting), the shot, the round's flight with its plasma trail and roar, and at the target the
 * collapsing core bending the light, the star igniting, burning, collapsing and going nova - each sound reaching
 * you when it would through the air, the shock when its front gets to you.
 */
public final class SunClient {
	/** Speed of sound, blocks per tick. */
	private static final double SOUND = 17.15;
	private static final List<Strike> STRIKES = new ArrayList<>();
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private SunClient() {
	}

	/** A strike as this client knows it. */
	public static final class Strike {
		public final int id;
		public final BlockPos launcher;
		public final Vec3 target;
		public final long launchTime;
		public final int flight;
		final Vec3 centre;
		int lastT = Integer.MIN_VALUE;
		boolean shockArrived;
		RoarSound roar;
		LoopSound burn;

		Strike(StrikePayload p) {
			this.id = p.id();
			this.launcher = p.launcher();
			this.target = p.target();
			this.launchTime = p.launchTime();
			this.flight = p.flight();
			this.centre = SunTimeline.sunCentre(this.target);
		}

		public float time(float partialTick) {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null ? 0.0F : (float) (mc.level.getGameTime() - this.launchTime) + partialTick;
		}

		public int impact() {
			return SunTimeline.FIRE + this.flight;
		}

		/** Where the round is at time {@code t}, or null when it is not in the air. */
		public Vec3 round(float t) {
			if (t < SunTimeline.FIRE || t > this.impact()) {
				return null;
			}
			return SunTimeline.flight(this.launcher, this.target, (t - SunTimeline.FIRE) / this.flight);
		}
	}

	private record Pending(long at, Runnable action) {
	}

	public static void init() {
		SunStar.init();
		ClientPlayNetworking.registerGlobalReceiver(StrikePayload.TYPE, (payload, context) -> receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(SunClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		LatePass.register(SunClient::render); // before the smoke and the clouds, which lie over it
		SunCutscene.init();
	}

	public static List<Strike> strikes() {
		return STRIKES;
	}

	/** The strike going on at the complex at {@code pos}, if any. */
	public static Strike at(BlockPos pos) {
		for (Strike s : STRIKES) {
			if (s.launcher.equals(pos)) {
				return s;
			}
		}
		return null;
	}

	private static void receive(StrikePayload p) {
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			if (s.id == p.id()) {
				stop(s);
				it.remove();
			}
		}
		if (p.aborted()) {
			SunCutscene.aborted(p.id());
			return;
		}
		Strike s = new Strike(p);
		STRIKES.add(s);
		if (p.cutscene()) {
			SunCutscene.start(s);
		}
	}

	private static void stop(Strike s) {
		if (s.roar != null) {
			s.roar.end();
		}
		if (s.burn != null) {
			s.burn.end();
		}
	}

	public static void clear() {
		for (Strike s : STRIKES) {
			stop(s);
		}
		STRIKES.clear();
		PENDING.clear();
		SunCutscene.clear();
	}

	// ------------------------------------------------------------------ every tick

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
			Pending p = it.next();
			if (now >= p.at) {
				it.remove();
				p.action.run();
			}
		}
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			int t = (int) (now - s.launchTime);
			int from = s.lastT == Integer.MIN_VALUE ? t : s.lastT + 1;
			s.lastT = t;
			for (int k = Math.max(from, t - 40); k <= t; k++) {
				event(mc, s, k, k == t);
			}
			everyTick(mc, s, t);
			if (t > s.impact() + SunTimeline.AFTER_END) {
				stop(s);
				it.remove();
			}
		}
	}

	private static void later(Minecraft mc, double ticks, Runnable action) {
		if (ticks < 1.0) {
			action.run();
		} else {
			PENDING.add(new Pending(mc.level.getGameTime() + (long) ticks, action));
		}
	}

	private static double distance(Minecraft mc, Vec3 at) {
		return mc.gameRenderer.getMainCamera().position().distanceTo(at);
	}

	/** A sound at the complex, heard (and fading with distance) as a normal positioned sound. */
	private static void local(Minecraft mc, Vec3 at, SoundEvent sound, float volume, float pitch) {
		mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch, false);
	}

	/** A sound heard from very far: it arrives when sound would get here, and stays where it came from. */
	private static void far(Minecraft mc, Vec3 at, SoundEvent sound, float volume, float pitch, double reach) {
		double d = distance(mc, at);
		if (d > reach) {
			return;
		}
		float v = (float) Mth.clamp(volume * (1.15 - d / reach), 0.0, 1.0);
		later(mc, d / SOUND, () -> ClientEffects.playDistant(mc, sound, at, v, pitch));
	}

	private static Vec3 base(Strike s) {
		return Vec3.atBottomCenterOf(s.launcher);
	}

	private static Direction facing(Minecraft mc, Strike s) {
		BlockState state = mc.level.getBlockState(s.launcher);
		return state.hasProperty(SunForgeBlock.FACING) ? state.getValue(SunForgeBlock.FACING) : Direction.NORTH;
	}

	/** The moments of the strike, each played once as the timeline passes it. */
	private static void event(Minecraft mc, Strike s, int t, boolean fresh) {
		Vec3 base = base(s);
		Vec3 muzzle = SunTimeline.muzzle(s.launcher);
		boolean nearLauncher = distance(mc, base) < 700.0;
		// ---- the countdown at the complex
		if (nearLauncher && t < SunTimeline.FIRE) {
			if (t % 110 == 0 && t < SunTimeline.FIRE - 40) {
				local(mc, base.add(0, 14, 0), ModRegistry.SUN_KLAXON, 8.0F, 1.0F);
			}
			if (t == SunTimeline.CRANE_FROM) {
				local(mc, base.add(0, 30, 0), ModRegistry.UPLINK_SERVO, 6.0F, 0.55F);
			}
			if (t == SunTimeline.DOORS_OPEN - 10) {
				local(mc, base.add(0, 2, 0), ModRegistry.SUN_DOORS, 8.0F, 1.0F);
			}
			if (t == SunTimeline.LIFT_FROM) {
				local(mc, base.add(0, 2, 0), ModRegistry.SUN_LIFT, 7.0F, 1.0F);
			}
			if (t == SunTimeline.CHARGE_FROM) {
				local(mc, base.add(0, 26, 0), ModRegistry.SUN_CHARGE, 10.0F, 1.0F);
			}
		}
		// ---- the shot
		if (t == SunTimeline.FIRE) {
			double d = distance(mc, muzzle);
			if (d < 260.0) {
				local(mc, muzzle, ModRegistry.SUN_FIRE, 16.0F, 1.0F);
			}
			far(mc, muzzle, d < 260.0 ? ModRegistry.SUN_FIRE : ModRegistry.SUN_FIRE_FAR, 1.0F, 1.0F, 2600.0);
			if (fresh) {
				float near = (float) Mth.clamp(1.0 - d / 600.0, 0.0, 1.0);
				later(mc, d / SOUND, () -> {
					ClientEffects.addShake(4.0F * near);
					BlastShader.kick(0.6F * near);
					if (d < 120.0) {
						ClientEffects.deafen(mc, 0.6F, 50);
					}
				});
				BlastShader.flash(0.55F * (float) Mth.clamp(1.2 - d / 800.0, 0.0, 1.0));
				DynamicLights.flash(muzzle, 0xBFDcFF, 1.0F, 140.0F, 900.0F);
				DynamicLights.flash(base.add(0, 20, 0), 0xA8C8FF, 1.0F, 90.0F, 600.0F);
				// the muzzle blast: a ring of torn air and steam, and the cloud rolling off the complex
				for (int i = 0; i < 70; i++) {
					double a = i * Mth.TWO_PI / 70.0;
					SmokeField.puff(muzzle.x, muzzle.y + 2.0, muzzle.z, Math.cos(a) * 1.1, 0.12 + RANDOM.nextDouble() * 0.2, Math.sin(a) * 1.1,
						500 + RANDOM.nextInt(300), 2.5F, 11.0F, 0xE8ECF0, 0.7F, 0.5F, 0.0008F);
				}
				SmokeField.burst(base.add(0, 3, 0), 60, 14.0, Vec3.ZERO, 0.8, 700, 4.0F, 16.0F, 0xD6D6D2, 0.75F, 0.0006F);
			}
		}
		// ---- at the target
		int d = t - s.impact();
		Vec3 c = s.centre;
		double dist = distance(mc, c);
		if (d == 0) {
			far(mc, c, ModRegistry.SUN_IMPLODE, 1.0F, 1.0F, 2400.0);
			if (fresh) {
				DynamicLights.flash(s.target.add(0, 2, 0), 0xC8E0FF, 1.0F, 50.0F, 300.0F);
			}
		}
		if (d == SunTimeline.IMPLODE) {
			far(mc, c, ModRegistry.SUN_IGNITE, 1.0F, 1.0F, 3200.0);
			if (fresh) {
				BlastShader.blast(BlastShader.Kind.NUCLEAR, c, 3.0, 0xFFC878);
				BlastShader.flash((float) Mth.clamp(1.1 - dist / 1500.0, 0.15, 1.0));
				ClientEffects.setFlash((float) Mth.clamp(0.8 - dist / 2000.0, 0.1, 0.8), 0xFFE8C0);
				later(mc, dist / SOUND, () -> {
					float near = (float) Mth.clamp(1.0 - dist / 900.0, 0.0, 1.0);
					ClientEffects.addShake(5.0F * near);
					BlastShader.quake(0.6F * near);
				});
				// the ground thrown up where the core ignites: a ring of dirt and fire
				ClientEffects.fireball(s.target.add(0, 4, 0), SunTimeline.RADIUS * 0.6, 120, 14.0F, 1.6, 50);
				ClientEffects.embers(s.target, 260, 3.0);
				for (int i = 0; i < 90; i++) {
					double a = RANDOM.nextDouble() * Mth.TWO_PI;
					double r = SunTimeline.RADIUS * (0.9 + RANDOM.nextDouble() * 0.4);
					double gy = ClientEffects.groundY(mc, c.x + Math.cos(a) * r, s.target.y, c.z + Math.sin(a) * r);
					SmokeField.puff(c.x + Math.cos(a) * r, gy + 1.0, c.z + Math.sin(a) * r, Math.cos(a) * 0.9, 0.25, Math.sin(a) * 0.9,
						900 + RANDOM.nextInt(400), 4.0F, 18.0F, 0x5A4E44, 0.85F, 0.6F, 0.0008F);
				}
			}
		}
		if (d == SunTimeline.BURN_END) {
			far(mc, c, ModRegistry.SUN_COLLAPSE, 1.0F, 1.0F, 2600.0);
		}
		if (d == SunTimeline.NOVA) {
			far(mc, c, dist < 700.0 ? ModRegistry.SUN_NOVA : ModRegistry.SUN_NOVA_FAR, 1.0F, 1.0F, 5000.0);
			if (dist < 700.0) {
				far(mc, c, ModRegistry.SUN_NOVA_FAR, 0.8F, 0.8F, 5000.0);
			}
			if (fresh) {
				BlastShader.blast(BlastShader.Kind.NUCLEAR, c, 6.0, 0xFFF0D8);
				BlastShader.flash((float) Mth.clamp(1.3 - dist / 2500.0, 0.2, 1.0));
				ClientEffects.setFlash((float) Mth.clamp(1.0 - dist / 3000.0, 0.2, 1.0), 0xFFFFFF);
				DynamicLights.explosion(c, 5.0, 0xFFE0B0);
				ClientEffects.fireball(c, SunTimeline.RADIUS * 0.8, 220, 22.0F, 2.6, 80);
				ClientEffects.embers(c, 500, 4.5);
				ClientEffects.shockSphere(c, SunTimeline.NOVA_REACH * 0.6, 200);
			}
		}
	}

	/** What goes on continuously: steam and arcs while charging, the round's trail, the burning star's smoke. */
	private static void everyTick(Minecraft mc, Strike s, int t) {
		Vec3 base = base(s);
		boolean nearLauncher = distance(mc, base) < 500.0;
		// ---- the coils charging: arcs crack between the rings, the cooling towers vent steam
		if (nearLauncher && t >= SunTimeline.CHARGE_FROM && t < SunTimeline.FIRE + 160) {
			float charge = Mth.clamp((t - SunTimeline.CHARGE_FROM) / (float) (SunTimeline.CHARGE_TO - SunTimeline.CHARGE_FROM), 0.0F, 1.0F);
			Direction f = facing(mc, s);
			if (t < SunTimeline.FIRE && RANDOM.nextFloat() < 0.04F + 0.18F * charge) {
				float y = SunTimeline.RING_BASE + RANDOM.nextInt(SunTimeline.RINGS) * SunTimeline.RING_STEP;
				Vec3 at = base.add(0, y, 0);
				local(mc, at, ModRegistry.SUN_ARC, 3.0F + 3.0F * charge, 0.8F + RANDOM.nextFloat() * 0.5F);
				DynamicLights.flash(at, 0xB8D8FF, 0.8F, 18.0F + 20.0F * charge, 120.0F);
			}
			if (t < SunTimeline.FIRE) {
				int lit = Math.round(charge * SunTimeline.RINGS);
				DynamicLights.lamp(s.id * 7919L + 1L, base.add(0, SunTimeline.RING_BASE + lit * SunTimeline.RING_STEP * 0.5, 0), 0x9CC4FF,
					0.35F + 0.65F * charge, 18.0F + 30.0F * charge);
			}
			// steam from the cooling towers, harder as the coils heat, a great gout after the shot
			float vent = t < SunTimeline.FIRE ? 0.15F + 0.6F * charge : 1.0F - (t - SunTimeline.FIRE) / 160.0F;
			for (Vec3 tower : SunLayout.towerTops(f)) {
				if (RANDOM.nextFloat() < vent) {
					Vec3 p = base.add(tower);
					SmokeField.puff(p.x + ClientEffects.gauss() * 0.8, p.y, p.z + ClientEffects.gauss() * 0.8, ClientEffects.gauss() * 0.03, 0.22 + 0.2 * vent,
						ClientEffects.gauss() * 0.03, 260 + RANDOM.nextInt(160), 1.6F, 7.0F, 0xF4F5F6, 0.6F, 0.0F, 0.0015F);
				}
			}
		}
		// ---- the round in flight
		Vec3 round = s.round(t);
		int key = 0x5D000000 | (s.id & 0xFFFFFF);
		if (round != null) {
			SmokeField.trail(key, round, new SmokeField.Style(1.4F, 7.0F, 1100, 0xDCE6F4, 0.55F, 1.0F, 0.0008F, 1.0F), 1.0F);
			DynamicLights.lamp(s.id * 7919L + 2L, round, 0xA8C8FF, 1.0F, 46.0F);
			if (s.roar == null) {
				s.roar = new RoarSound(s);
				mc.getSoundManager().play(s.roar);
			}
		} else if (t > s.impact()) {
			SmokeField.cut(key);
			if (s.roar != null) {
				s.roar.end();
				s.roar = null;
			}
		}
		// ---- at the target
		int d = t - s.impact();
		if (d < 0 || d > SunTimeline.AFTER_END) {
			return;
		}
		Vec3 c = s.centre;
		float radius = SunTimeline.sunRadius(d);
		if (d < SunTimeline.IMPLODE) {
			BlastShader.lens(c, 3.0F + 16.0F * d / SunTimeline.IMPLODE, 0.0F);
			// dust and loose things streaming in towards the core
			for (int i = 0; i < 6; i++) {
				double a = RANDOM.nextDouble() * Mth.TWO_PI;
				double r = 30.0 + RANDOM.nextDouble() * 40.0;
				double gy = ClientEffects.groundY(mc, c.x + Math.cos(a) * r, s.target.y, c.z + Math.sin(a) * r);
				SmokeField.puff(c.x + Math.cos(a) * r, gy + 1.0, c.z + Math.sin(a) * r, -Math.cos(a) * 1.2, 0.25, -Math.sin(a) * 1.2,
					40 + RANDOM.nextInt(20), 1.5F, 3.0F, 0x8A7C6C, 0.6F, 0.0F, 0.0F);
			}
		} else if (d < SunTimeline.COLLAPSED) {
			BlastShader.lens(c, d > SunTimeline.BURN_END ? 6.0F * (d - SunTimeline.BURN_END) / (SunTimeline.COLLAPSED - SunTimeline.BURN_END) : 0.0F, 0.75F);
			DynamicLights.lamp(s.id * 7919L + 3L, c.add(0, radius * 0.6, 0), 0xFFB858, 1.0F, Math.min(160.0F, radius * 4.5F + 20.0F));
			if (s.burn == null) {
				s.burn = new LoopSound(ModRegistry.SUN_BURN, c, 1400.0);
				mc.getSoundManager().play(s.burn);
			}
			if (ModConfig.smokeAmount > 0) {
				// the ground round its foot on fire, black smoke boiling up the star's sides into a towering plume
				for (int i = 0; i < 5; i++) {
					double a = RANDOM.nextDouble() * Mth.TWO_PI;
					double r = radius * (1.02 + RANDOM.nextDouble() * 0.25);
					double gy = ClientEffects.groundY(mc, c.x + Math.cos(a) * r, s.target.y, c.z + Math.sin(a) * r);
					SmokeField.puff(c.x + Math.cos(a) * r, gy + 1.5, c.z + Math.sin(a) * r, Math.cos(a) * 0.08, 0.28 + RANDOM.nextDouble() * 0.2,
						Math.sin(a) * 0.08, 900 + RANDOM.nextInt(500), 3.0F, 15.0F, 0x2C2724, 0.75F, 0.55F, 0.0018F);
				}
				if (RANDOM.nextFloat() < 0.6F) {
					double a = RANDOM.nextDouble() * Mth.TWO_PI;
					SmokeField.puff(c.x + Math.cos(a) * radius * 0.5, c.y + radius * 0.95, c.z + Math.sin(a) * radius * 0.5, ClientEffects.gauss() * 0.05,
						0.55 + RANDOM.nextDouble() * 0.2, ClientEffects.gauss() * 0.05, 1600 + RANDOM.nextInt(600), 8.0F, 32.0F, 0x3A3430, 0.7F, 0.7F, 0.002F);
				}
				if (RANDOM.nextFloat() < 0.3F) {
					ClientEffects.embers(c.add(0, -radius * 0.2, 0), 6, 1.4);
				}
			}
		} else {
			if (s.burn != null) {
				s.burn.end();
				s.burn = null;
			}
			int n = d - SunTimeline.NOVA;
			// the glowing crater cooling for a long while
			float glow = Mth.clamp(1.0F - n / 480.0F, 0.0F, 1.0F);
			if (glow > 0.0F) {
				DynamicLights.lamp(s.id * 7919L + 3L, s.target.add(0, 3, 0), 0xFF7A30, glow, 30.0F + 60.0F * glow);
			}
			// the shock front rolling over the land: a wall of dust along the ground
			float front = SunTimeline.shockRadius(d);
			if (n < 90 && ModConfig.smokeAmount > 0) {
				int k = 26;
				for (int i = 0; i < k; i++) {
					double a = RANDOM.nextDouble() * Mth.TWO_PI;
					double x = c.x + Math.cos(a) * front;
					double z = c.z + Math.sin(a) * front;
					double gy = ClientEffects.groundY(mc, x, s.target.y, z);
					SmokeField.puff(x, gy + 1.5, z, Math.cos(a) * 0.5, 0.12, Math.sin(a) * 0.5, 500 + RANDOM.nextInt(300), 4.0F, 14.0F,
						RANDOM.nextBoolean() ? 0x8A7A66 : 0x6E6255, 0.65F, 0.0F, 0.0004F);
				}
			}
			// the column of smoke and dust standing over the crater
			if (n < 260 && ModConfig.smokeAmount > 0 && RANDOM.nextFloat() < 0.8F) {
				double h = Math.min(220.0, n * 1.4);
				SmokeField.puff(c.x + ClientEffects.gauss() * 8.0, s.target.y + RANDOM.nextDouble() * h, c.z + ClientEffects.gauss() * 8.0,
					ClientEffects.gauss() * 0.05, 0.4, ClientEffects.gauss() * 0.05, 1800 + RANDOM.nextInt(800), 10.0F, 34.0F,
					RANDOM.nextBoolean() ? 0x4A423C : 0x5C524A, 0.75F, Math.max(0.0F, 0.6F - n / 200.0F), 0.0015F);
			}
			// the shock reaching you
			double dist = distance(mc, c);
			if (!s.shockArrived && front >= dist) {
				s.shockArrived = true;
				float near = (float) Mth.clamp(1.0 - dist / SunTimeline.NOVA_REACH, 0.0, 1.0);
				ClientEffects.addShake(3.0F + 6.0F * near);
				BlastShader.kick(0.5F + 0.5F * near);
				BlastShader.quake(0.5F * near + 0.2F);
				if (near > 0.2F) {
					ClientEffects.deafen(mc, 0.5F + 0.45F * near, 100);
				}
			}
		}
	}

	// ------------------------------------------------------------------ drawing

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (STRIKES.isEmpty() || mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<SunStar.Star> stars = new ArrayList<>();
		for (Strike s : STRIKES) {
			float t = s.time(partial);
			Vec3 round = s.round(t);
			if (round != null) {
				float f = (t - SunTimeline.FIRE) / s.flight;
				Vec3 dir = SunTimeline.heading(s.launcher, s.target, f);
				de.rcm.ballistic.client.render.SunForgeRenderer.drawRound(context, round, dir, t);
				stars.add(new SunStar.Star(round.subtract(dir.scale(2.4)), 0.0F, 1.6F, 0.0F, 0.95F, 0.0F, 0.9F, 0.3F));
			}
			float d = t - s.impact();
			if (d < 0.0F || d > SunTimeline.NOVA + 120) {
				continue;
			}
			float seed = (s.id * 0.137F) % 1.0F;
			if (d < SunTimeline.IMPLODE) {
				float x = d / SunTimeline.IMPLODE;
				// the core: a black speck growing, light falling in towards it
				stars.add(new SunStar.Star(s.centre, SunTimeline.sunRadius(d), 0.0F, 80.0F * (1.0F - x) + 1.0F, 0.9F, 1.0F, 1.0F, seed));
			} else if (d < SunTimeline.COLLAPSED) {
				float r = SunTimeline.sunRadius(d);
				float age = d - SunTimeline.IMPLODE;
				float temp;
				if (d < SunTimeline.GROWN) {
					temp = Mth.lerp(SunTimeline.smooth(age / (SunTimeline.GROWN - SunTimeline.IMPLODE)), 0.95F, 0.36F);
				} else if (d < SunTimeline.BURN_END) {
					temp = 0.36F + 0.02F * Mth.sin(d * 0.07F);
				} else {
					temp = Mth.lerp(SunTimeline.smooth((d - SunTimeline.BURN_END) / (SunTimeline.COLLAPSED - SunTimeline.BURN_END)), 0.37F, 1.0F);
				}
				float dark = Mth.clamp(1.0F - age / 5.0F, 0.0F, 1.0F);
				stars.add(new SunStar.Star(s.centre, r, r, 0.0F, temp, dark * 0.97F, 1.0F, seed));
			} else {
				float n = d - SunTimeline.NOVA;
				float flash = (float) Math.exp(-n / 16.0);
				float shock = n < 110.0F ? SunTimeline.shockRadius(d) : 0.0F;
				stars.add(new SunStar.Star(s.centre, 0.0F, SunTimeline.RADIUS * 1.8F * flash, shock, Mth.lerp(Math.min(1.0F, n / 40.0F), 1.0F, 0.3F), 0.0F,
					1.0F, seed));
			}
		}
		SunStar.draw(context, stars);
	}

	// ------------------------------------------------------------------ sounds

	/** The round's roar, following it: loud overhead, a distant rumble from far off. */
	private static final class RoarSound extends AbstractTickableSoundInstance {
		private final Strike strike;
		private boolean ending;

		RoarSound(Strike strike) {
			super(ModRegistry.SUN_FLIGHT, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.strike = strike;
			this.looping = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = false;
			this.volume = 0.01F;
			this.place();
		}

		void end() {
			this.ending = true;
		}

		private void place() {
			Minecraft mc = Minecraft.getInstance();
			Vec3 ear = mc.gameRenderer.getMainCamera().position();
			float t = this.strike.time(0.0F);
			Vec3 at = this.strike.round(t);
			if (at == null) {
				this.stop();
				return;
			}
			double d = ear.distanceTo(at);
			Vec3 dir = at.subtract(ear);
			Vec3 p = d < 8.0 ? at : ear.add(dir.scale(8.0 / d));
			this.x = p.x;
			this.y = p.y;
			this.z = p.z;
			float v = (float) Mth.clamp(1.1 - d / 1600.0, 0.0, 1.0);
			this.volume = v * v * ModConfig.explosionVolume / 100.0F;
			this.pitch = (float) Mth.clamp(1.15 - d / 3000.0, 0.7, 1.15);
		}

		@Override
		public void tick() {
			if (this.ending) {
				this.stop();
				return;
			}
			this.place();
		}
	}

	/** A sound held while the star burns, placed towards it, by distance. */
	private static final class LoopSound extends AbstractTickableSoundInstance {
		private final Vec3 at;
		private final double reach;
		private boolean ending;
		private float fade = 1.0F;

		LoopSound(SoundEvent sound, Vec3 at, double reach) {
			super(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.at = at;
			this.reach = reach;
			this.looping = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = false;
			this.volume = 0.01F;
			this.place();
		}

		void end() {
			this.ending = true;
		}

		private void place() {
			Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().position();
			double d = ear.distanceTo(this.at);
			Vec3 p = d < 8.0 ? this.at : ear.add(this.at.subtract(ear).scale(8.0 / d));
			this.x = p.x;
			this.y = p.y;
			this.z = p.z;
			float v = (float) Mth.clamp(1.15 - d / this.reach, 0.0, 1.0);
			this.volume = v * this.fade * ModConfig.explosionVolume / 100.0F;
		}

		@Override
		public void tick() {
			if (this.ending) {
				this.fade -= 0.08F;
				if (this.fade <= 0.0F) {
					this.stop();
					return;
				}
			}
			this.place();
		}
	}
}
