package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;

/**
 * The wind in the grass. Plants, flowers and crops lean and sway with it, the gusts running over a meadow in
 * waves, the leaves of the trees rustling; harder in rain, hardest in a thunderstorm. And when something big goes
 * off the blast wave sweeps out over the land, pressing everything flat as it passes - and the grass springs back
 * up behind it.
 * <p>
 * Done in the terrain shader ({@code assets/minecraft/shaders/core/terrain.vsh}): the chunk mesher marks the
 * vertices of plants and leaves in their vertex alpha (how far up the plant the vertex is), and a uniform block of
 * our own ({@code BmWind}) carries the wind and the blast waves each frame.
 */
public final class Wind {
	/** Kinds of swaying blocks, as the mesher marks them. */
	public static final int NONE = 0;
	public static final int PLANT = 1;
	public static final int LOWER = 2;
	public static final int UPPER = 3;
	public static final int LEAVES = 4;

	private static final int BLASTS = 4;
	private static final int UBO_SIZE = 16 + 16 * BLASTS * 2;

	private record Blast(Vec3 at, float start, float speed, float reach, float push) {
		boolean over(float now) {
			return (now - this.start) / 20.0F > this.reach / this.speed + 8.0F;
		}
	}

	private static final List<Blast> WAVES = new ArrayList<>();
	private static GpuBuffer buffer;

	private Wind() {
	}

	public static void init() {
		WorldRenderEvents.START_MAIN.register(context -> upload());
	}

	/** The kind of sway for a block about to be meshed. */
	public static int kind(BlockState state) {
		if (state.is(BlockTags.LEAVES)) {
			return LEAVES;
		}
		if (!(state.getBlock() instanceof VegetationBlock) || state.getBlock() instanceof FlowerBedBlock || state.getBlock() instanceof LeafLitterBlock
			|| state.getBlock() instanceof WaterlilyBlock || !state.getFluidState().isEmpty()) {
			return NONE;
		}
		if (state.getBlock() instanceof DoublePlantBlock) {
			return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? UPPER : LOWER;
		}
		return PLANT;
	}

	/**
	 * The vertex colour with the sway marked in its alpha: 252 for leaves, 210-250 for plants (the lower, the
	 * further up the plant: 250 at its foot, which stays put).
	 */
	public static int mark(int color, int kind, float localY) {
		int alpha;
		if (kind == LEAVES) {
			alpha = 252;
		} else {
			float h = Mth.clamp(localY, 0.0F, 1.0F);
			float amp = kind == LOWER ? h * 0.5F : kind == UPPER ? 0.5F + h * 0.5F : h;
			alpha = 250 - Math.round(amp * 40.0F);
		}
		return color & 0x00FFFFFF | alpha << 24;
	}

	/** A blast wave from {@code at}: out to {@code reach} blocks at {@code speed} blocks a second. */
	public static void blast(Vec3 at, float reach, float speed, float push) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		WAVES.add(new Blast(at, mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), speed, reach, push));
		while (WAVES.size() > BLASTS) {
			WAVES.removeFirst();
		}
	}

	/** The wind blows the same way as the clouds drift, swinging a little over the minutes. */
	public static float direction(float seconds) {
		return (float) Math.atan2(1.0, 2.0) + 0.35F * Mth.sin(seconds * 0.004F) + 0.15F * Mth.sin(seconds * 0.017F);
	}

	/** How hard it blows, 0-1.4: a breeze on a fine day, stronger in rain, a gale in a thunderstorm. */
	public static float strength(float seconds, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0.0F;
		}
		float rain = mc.level.getRainLevel(partialTick);
		float thunder = mc.level.getThunderLevel(partialTick);
		float calm = 0.75F + 0.25F * Mth.sin(seconds * 0.011F) * Mth.sin(seconds * 0.0037F + 1.0F);
		return (0.4F + 0.35F * rain + 0.45F * thunder) * calm;
	}

	private static GpuBuffer buffer() {
		if (buffer == null) {
			buffer = RenderSystem.getDevice().createBuffer(() -> "Ballistic wind UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
		}
		return buffer;
	}

	/** Every render pass gets the wind (only the terrain shaders read it). */
	public static void bind(RenderPass pass) {
		pass.setUniform("BmWind", buffer());
	}

	private static void upload() {
		Minecraft mc = Minecraft.getInstance();
		GpuBuffer target = buffer();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float now = mc.level == null ? 0.0F : mc.level.getGameTime() + partial;
		// an hour of game ticks round and round: the sway's phases jump once an hour, unseen
		float seconds = mc.level == null ? 0.0F : ((mc.level.getGameTime() % 72000L) + partial) / 20.0F;
		float dir = direction(seconds);
		float strength = mc.level == null ? 0.0F : strength(seconds, partial);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		int cx = Mth.floor(cam.x);
		int cy = Mth.floor(cam.y);
		int cz = Mth.floor(cam.z);
		WAVES.removeIf(b -> b.over(now) || mc.level == null);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder b = Std140Builder.onStack(stack, UBO_SIZE);
			b.putVec4(Mth.cos(dir), Mth.sin(dir), strength, seconds);
			for (int i = 0; i < BLASTS; i++) {
				if (i < WAVES.size()) {
					Blast w = WAVES.get(i);
					b.putVec4((float) (w.at().x - cx), (float) (w.at().y - cy), (float) (w.at().z - cz), (now - w.start()) / 20.0F);
				} else {
					b.putVec4(0.0F, 0.0F, 0.0F, -1.0F);
				}
			}
			for (int i = 0; i < BLASTS; i++) {
				if (i < WAVES.size()) {
					Blast w = WAVES.get(i);
					b.putVec4(w.speed(), w.reach(), w.push(), 0.0F);
				} else {
					b.putVec4(1.0F, 0.0F, 0.0F, 0.0F);
				}
			}
			ByteBuffer data = b.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), data);
		}
	}

	/** Implemented by the chunk mesher's buffer: which kind of block it is meshing, and where its foot is. */
	public interface Target {
		void ballisticmissiles$sway(int kind, float baseY);
	}
}
