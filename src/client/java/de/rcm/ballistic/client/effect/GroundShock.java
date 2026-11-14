package de.rcm.ballistic.client.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * The blast wave running out along the ground, as it looks in footage of real large explosions, for the volumetric
 * smoke ({@link SmokeVolume}): first the shock front itself, racing out at the speed of sound, invisible but for the
 * thin sheet of fine dust it rips off the ground as it passes - a pale ring flashing outward in a fraction of a
 * second, weaker the further it gets; then, behind it, the dust surge - the outflow of air behind the shock dragging
 * a rolling wall of dust and dirt out along the ground, fast at first and slowing, piling up and curling over at its
 * crest, leaving a dust haze that hangs, drifts with the wind and settles.
 * <p>
 * The dust is the ground's own: brown off grass and earth, yellow off sand, grey off stone and concrete, white off
 * snow, spray off water. It runs into walls and buildings and stays out of the hills it cannot get over (the smoke's
 * own collision, {@link SmokeCollision}). The shock front reaches you together with the bang.
 */
final class GroundShock implements ClientEffects.Effect {
	/** Blocks a tick: the blast front, as the sound (ClientEffects.SPEED_OF_SOUND). */
	private static final double FRONT_SPEED = 17.15;

	private final Vec3 pos;
	/** Size of the blast in units of a 1-tonne-class missile warhead (BlastEffect's scale). */
	private final double size;
	private final int delay;
	/** How far the shock still lifts dust, blocks. */
	private final double reach;
	/** Where the dust surge comes to rest, blocks, and how fast it slows (ticks). */
	private final double surgeReach;
	private final double tau;
	/** How high above the ground the blast may be and still raise the ground (else it is an air burst). */
	private final boolean grounded;
	private final double baseY;
	private int age;

	GroundShock(Vec3 pos, double size, int delay) {
		this.pos = pos;
		this.size = size;
		this.delay = delay;
		this.reach = 55.0 * Math.pow(size, 0.6);
		this.surgeReach = 42.0 * Math.sqrt(size);
		this.tau = 10.0 + 8.0 * Math.sqrt(size);
		Minecraft mc = Minecraft.getInstance();
		double ground = ClientEffects.groundY(mc, pos.x, pos.y - 1.0, pos.z);
		this.baseY = Math.min(pos.y, ground);
		this.grounded = pos.y - ground < 15.0 + 20.0 * size;
	}

	@Override
	public Vec3 origin() {
		return this.pos;
	}

	@Override
	public boolean tick(Minecraft mc) {
		int t = this.age++ - this.delay;
		if (t < 0) {
			return false;
		}
		if (!this.grounded || mc.level == null) {
			return true;
		}
		double sz = Math.sqrt(this.size);
		// ---- the shock front: the dust it tears off the ground in the ring it ran over this tick
		double r0 = Math.max(2.5 * sz, FRONT_SPEED * t);
		double r1 = FRONT_SPEED * (t + 1);
		if (r0 < this.reach) {
			double outer = Math.min(r1, this.reach);
			double area = Math.PI * (outer * outer - r0 * r0);
			int n = Math.min(150, (int) (area / (16.0 * sz)) + 8);
			for (int i = 0; i < n; i++) {
				// even over the ring's area
				double r = Math.sqrt(r0 * r0 + ClientEffects.rand() * (outer * outer - r0 * r0));
				double a = ClientEffects.rand() * Mth.TWO_PI;
				double f = Math.pow(Math.max(0.0, 1.0 - r / this.reach), 1.3);
				this.kick(mc, r, a, f, sz);
			}
		}
		// ---- the dust surge: a rolling wall laid down along its front as it runs out and slows
		double span = 3.0 * this.tau;
		if (t < span && t % 3 == 0) {
			double grow = 1.0 - Math.exp(-t / this.tau);
			double front = 3.0 * sz + (this.surgeReach - 3.0 * sz) * grow;
			double speed = (this.surgeReach - 3.0 * sz) / this.tau * Math.exp(-t / this.tau);
			double fade = 1.0 - 0.55 * t / span;
			double height = (2.0 + 6.0 * grow) * sz;
			int n = Math.min(80, (int) (Mth.TWO_PI * front / (5.0 * sz)) + 6);
			double phase = ClientEffects.rand() * Mth.TWO_PI;
			for (int i = 0; i < n; i++) {
				double a = phase + i * Mth.TWO_PI / n + ClientEffects.gauss() * 0.04;
				this.surge(mc, front + ClientEffects.gauss() * 0.8 * sz, a, speed, fade, sz, 0.0);
				if (i % 2 == 0) {
					// the crest, a little behind the foot and up, curling back over
					this.surge(mc, front - 1.6 * sz, a + 0.5 * Mth.TWO_PI / n, speed, fade, sz, height);
				}
			}
		}
		return t > span + 2;
	}

	/** The thin, pale dust the shock rips off the ground at {@code r}, {@code a}; {@code f}: how strong it still is. */
	private void kick(Minecraft mc, double r, double a, double f, double sz) {
		double x = this.pos.x + Math.cos(a) * r;
		double z = this.pos.z + Math.sin(a) * r;
		Ground g = this.ground(mc, x, z);
		if (g == null) {
			return;
		}
		int color = ClientEffects.mix(g.dust, 0xE6E2DA, 0.3F);
		float alpha = (float) (0.1 + 0.36 * f);
		double out = 0.25 + 1.1 * f;
		SmokeField.puff(x, g.y + 0.3 + ClientEffects.rand() * 0.6, z, Math.cos(a) * out, 0.04 + 0.12 * f * ClientEffects.rand(), Math.sin(a) * out,
			90 + (int) (ClientEffects.rand() * 70), (float) (0.8 + 1.2 * f), (float) ((2.5 + 4.0 * f) * sz) + 1.0F, color, alpha, 0.0F, 0.0008F);
		// a few bits of the ground itself thrown up close to the eye
		if (f > 0.35 && g.state != null && ClientEffects.rand() < 0.25F) {
			ClientEffects.vanilla(new BlockParticleOption(ParticleTypes.BLOCK, g.state), x, g.y + 0.2, z, Math.cos(a) * 0.3 * f, 0.25 * f,
				Math.sin(a) * 0.3 * f);
		}
	}

	/** One puff of the surge at radius {@code r}, angle {@code a}, {@code up} above the ground (0: its foot). */
	private void surge(Minecraft mc, double r, double a, double speed, double fade, double sz, double up) {
		double x = this.pos.x + Math.cos(a) * r;
		double z = this.pos.z + Math.sin(a) * r;
		Ground g = this.ground(mc, x, z);
		if (g == null) {
			return;
		}
		boolean crest = up > 0.0;
		// the foot runs out with the outflow and lifts; the crest rolls back over it
		double out = speed * (crest ? 0.18 : 0.45);
		double vy = crest ? 0.06 + ClientEffects.rand() * 0.05 : 0.03 + ClientEffects.rand() * 0.05 * sz;
		int color = crest ? ClientEffects.mix(g.dust, 0xD8D2C8, 0.2F) : ClientEffects.mix(g.dust, 0x3C352E, 0.18F);
		float alpha = (float) ((crest ? 0.42 : 0.62) * fade);
		float rStart = (float) ((crest ? 3.0 : 2.0) * sz);
		float rEnd = (float) ((crest ? 9.0 : 6.5) * sz * (0.85 + ClientEffects.rand() * 0.4));
		SmokeField.puff(x, g.y + (crest ? up : 0.6 + ClientEffects.rand() * 1.2 * sz), z, Math.cos(a) * out, vy, Math.sin(a) * out,
			260 + (int) (ClientEffects.rand() * 220), rStart, rEnd, color, alpha, 0.0F, 0.0006F);
	}

	private record Ground(double y, int dust, BlockState state) {
	}

	/** The ground at (x, z) and the colour of its dust; null where the wave cannot reach (high above the blast, a cliff). */
	private Ground ground(Minecraft mc, double x, double z) {
		int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
		if (top <= mc.level.getMinY()) {
			return null;
		}
		// it runs over gentle ground and down slopes, not up onto ridges and rooftops high over the blast
		if (top - this.baseY > 4.0 + 3.0 * Math.sqrt(this.size)) {
			return null;
		}
		BlockState state = mc.level.getBlockState(BlockPos.containing(x, top - 1, z));
		return new Ground(top, dustOf(mc, state, x, top - 1, z), state.isAir() ? null : state);
	}

	/** The dust the blast raises off {@code state}. */
	static int dustOf(Minecraft mc, BlockState state, double x, int y, double z) {
		if (!state.getFluidState().isEmpty()) {
			return 0xDCE4E8; // spray
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
			return 0xEEF0F2;
		}
		if (state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.DIRT) || state.is(BlockTags.LEAVES) || state.is(Blocks.FARMLAND)
			|| state.is(Blocks.DIRT_PATH)) {
			return 0x8C7A62; // the earth under the grass
		}
		MapColor map = state.getMapColor(mc.level, BlockPos.containing(x, y, z));
		int c = map == null || map == MapColor.NONE ? 0x9A9086 : map.col;
		// dust is paler and greyer than the block it comes off
		return ClientEffects.mix(c, 0xA49C92, 0.45F);
	}
}
