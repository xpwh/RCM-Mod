package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * An ash storm in the Nether (the server says when: AshStorms): a hot gale driving ash and glowing embers past in
 * streaks, the far side lost in a dark red-brown murk (BlastShader), the wind roaring and howling through the
 * basalt. Under a roof you are out of the worst of it.
 */
public final class AshStorm {
	private static final Identifier TEX = BallisticMissiles.id("textures/effect/wildlife.png");
	private static final RenderType TYPE = RenderTypes.entityTranslucentEmissive(TEX);
	private static final float TILE = 1.0F / 8.0F;
	private static final RandomSource R = RandomSource.create();
	private static final int MAX = 1100;
	/** The flakes: position, velocity, ember (glows) or ash, size. */
	private static final double[] X = new double[MAX];
	private static final double[] Y = new double[MAX];
	private static final double[] Z = new double[MAX];
	private static final double[] PX = new double[MAX];
	private static final double[] PY = new double[MAX];
	private static final double[] PZ = new double[MAX];
	private static final float[] VX = new float[MAX];
	private static final float[] VY = new float[MAX];
	private static final float[] VZ = new float[MAX];
	private static final boolean[] EMBER = new boolean[MAX];
	private static final float[] SIZE = new float[MAX];
	private static int count;

	/** When it started and when it ends (client game time), -1: none. */
	private static long start = -1L;
	private static long end = -1L;
	private static float amount;
	private static float shelter = 1.0F;
	private static float dir;
	private static Loop loop;

	private AshStorm() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(AshStorm::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(AshStorm::render);
	}

	/** From the server: the storm rages for this many more ticks (0: none). */
	public static void receive(int ticks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		if (ticks <= 0) {
			end = Math.min(end < 0L ? now : end, now);
			return;
		}
		if (start < 0L || now > end) {
			start = now;
			dir = R.nextFloat() * Mth.TWO_PI;
		}
		end = now + ticks;
	}

	/** How thick the storm is where you are (0-1), for the screen's murk. */
	public static float amount() {
		return amount * (0.35F + 0.65F * shelter);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			start = end = -1L;
			amount = 0.0F;
			count = 0;
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		float want = 0.0F;
		if (mc.level.dimension() == Level.NETHER && start >= 0L) {
			float in = Mth.clamp((now - start) / 300.0F, 0.0F, 1.0F);
			float out = Mth.clamp((end - now) / 400.0F, 0.0F, 1.0F);
			want = in * out;
			if (now > end + 400L) {
				start = end = -1L;
			}
		}
		amount += (want - amount) * 0.05F;
		if (amount < 0.003F) {
			amount = 0.0F;
		}
		// under a roof: out of the worst
		BlockPos head = mc.player.blockPosition().above(2);
		boolean open = true;
		for (int i = 0; i < 6; i++) {
			if (mc.level.getBlockState(head.above(i)).blocksMotion()) {
				open = false;
				break;
			}
		}
		shelter += ((open ? 1.0F : 0.0F) - shelter) * 0.08F;
		sound(mc);
		flakes(mc);
	}

	private static void sound(Minecraft mc) {
		if (amount > 0.01F && (loop == null || loop.isStopped() || !mc.getSoundManager().isActive(loop))) {
			loop = new Loop();
			mc.getSoundManager().play(loop);
		}
	}

	private static void flakes(Minecraft mc) {
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float budget = switch (de.rcm.ballistic.client.ModConfig.natureQuality) {
			case LOW -> 0.35F;
			case MEDIUM -> 0.65F;
			default -> 1.0F;
		};
		int want = Math.round(MAX * budget * amount * (0.4F + 0.6F * shelter));
		long tick = mc.level.getGameTime();
		float gust = 0.75F + 0.35F * Mth.sin(mc.level.getGameTime() * 0.05F) * Mth.sin(mc.level.getGameTime() * 0.013F);
		float wx = Mth.cos(dir) * 0.85F * gust;
		float wz = Mth.sin(dir) * 0.85F * gust;
		for (int i = 0; i < count; i++) {
			PX[i] = X[i];
			PY[i] = Y[i];
			PZ[i] = Z[i];
			VX[i] += (wx - VX[i]) * 0.1F + (R.nextFloat() - 0.5F) * 0.08F;
			VZ[i] += (wz - VZ[i]) * 0.1F + (R.nextFloat() - 0.5F) * 0.08F;
			VY[i] += (R.nextFloat() - 0.5F) * 0.05F - VY[i] * 0.05F;
			X[i] += VX[i];
			Y[i] += VY[i];
			Z[i] += VZ[i];
			double dx = X[i] - cam.x;
			double dz = Z[i] - cam.z;
			// out of range downwind, or into a wall: back upwind
			// into a wall: looked at every fourth tick only (a flake moves less than a block in that time)
			if (dx * dx + dz * dz > 24.0 * 24.0 || Math.abs(Y[i] - cam.y) > 14.0
				|| (i + tick) % 4 == 0 && mc.level.getBlockState(BlockPos.containing(X[i], Y[i], Z[i])).blocksMotion()) {
				spawn(i, cam, true);
			}
		}
		while (count < want) {
			spawn(count++, cam, false);
		}
		if (count > want) {
			count = Math.max(want, count - 20);
		}
	}

	private static void spawn(int i, Vec3 cam, boolean upwind) {
		double a;
		double r;
		if (upwind) {
			a = dir + Mth.PI + (R.nextFloat() - 0.5F) * 2.2F;
			r = 16.0 + R.nextFloat() * 7.0;
		} else {
			a = R.nextFloat() * Mth.TWO_PI;
			r = Math.sqrt(R.nextFloat()) * 22.0;
		}
		X[i] = PX[i] = cam.x + Math.cos(a) * r;
		Z[i] = PZ[i] = cam.z + Math.sin(a) * r;
		Y[i] = PY[i] = cam.y + (R.nextFloat() - 0.4F) * 18.0;
		VX[i] = Mth.cos(dir) * 0.8F;
		VZ[i] = Mth.sin(dir) * 0.8F;
		VY[i] = (R.nextFloat() - 0.5F) * 0.1F;
		EMBER[i] = R.nextFloat() < 0.22F;
		SIZE[i] = EMBER[i] ? 0.03F + R.nextFloat() * 0.04F : 0.035F + R.nextFloat() * 0.06F;
	}

	private static void render(WorldRenderContext context) {
		if (count == 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f side = new Vector3f();
		Vector3f vel = new Vector3f();
		Vector3f eye = new Vector3f();
		context.commandQueue().submitCustomGeometry(context.matrices(), TYPE, (pose, c) -> {
			Matrix4f m = pose.pose();
			for (int i = 0; i < count; i++) {
				float x = (float) (Mth.lerp(partial, PX[i], X[i]) - cam.x);
				float y = (float) (Mth.lerp(partial, PY[i], Y[i]) - cam.y);
				float z = (float) (Mth.lerp(partial, PZ[i], Z[i]) - cam.z);
				eye.set(x, y, z);
				// streaked along the way it flies (the eye's shutter): embers more, ash less
				vel.set(VX[i], VY[i], VZ[i]).mul(EMBER[i] ? 0.9F : 0.4F);
				vel.cross(eye, side);
				if (side.lengthSquared() < 1.0E-8F) {
					continue;
				}
				side.normalize().mul(SIZE[i]);
				int color;
				float u0;
				if (EMBER[i]) {
					// glowing orange, flickering
					float f = 0.6F + 0.4F * Mth.sin((mc.level.getGameTime() + partial) * 0.9F + i);
					color = (int) (Mth.clamp(f, 0.0F, 1.0F) * 230.0F) << 24 | 0xFF8A2A;
					u0 = 5 * TILE;
				} else {
					color = 200 << 24 | 0x3A3230;
					u0 = 5 * TILE;
				}
				float u1 = u0 + TILE;
				vertex(c, m, x - vel.x - side.x, y - vel.y - side.y, z - vel.z - side.z, u0, 1.0F, color);
				vertex(c, m, x - vel.x + side.x, y - vel.y + side.y, z - vel.z + side.z, u1, 1.0F, color);
				vertex(c, m, x + vel.x + side.x, y + vel.y + side.y, z + vel.z + side.z, u1, 0.0F, color);
				vertex(c, m, x + vel.x - side.x, y + vel.y - side.y, z + vel.z - side.z, u0, 0.0F, color);
			}
		});
	}

	private static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, float u, float v, int color) {
		c.addVertex(m, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0.0F, 1.0F, 0.0F);
	}

	/** The gale: as loud as the storm is thick where you are, muffled under a roof. */
	private static final class Loop extends AbstractTickableSoundInstance {
		Loop() {
			super(ModRegistry.ASH_STORM, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = true;
			this.volume = 0.01F;
		}

		@Override
		public void tick() {
			this.volume = Math.max(0.001F, amount * (0.45F + 0.55F * shelter) * 0.9F);
			if (amount <= 0.0F) {
				this.stop();
			}
		}
	}
}
