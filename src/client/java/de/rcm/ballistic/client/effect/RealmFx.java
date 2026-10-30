package de.rcm.ballistic.client.effect;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The air of the other worlds, for the screen effect (BlastShader): the heat shimmering over the Nether's lava
 * seas - stronger the more lava lies round you and the more air there is between you and what you look at - and
 * the passage through a portal: the air rippling round the portal as you come near, the pressure in the ears
 * inside it, and on the far side the heat of the Nether (or the cold of the End) hitting your eyes.
 */
public final class RealmFx {
	private static final RandomSource R = RandomSource.create();
	private static float heat;
	private static float heatTarget;
	private static float nearPortal;
	private static float portal;
	private static int portalRgb = 0xFF7020;
	private static float portalShimmer;
	private static ResourceKey<Level> lastDim;
	private static int scan;

	private RealmFx() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(RealmFx::tick);
	}

	/** How hard the air shimmers (0-1). */
	public static float heat() {
		if (!de.rcm.ballistic.client.ModConfig.heatShimmer) {
			return 0.0F;
		}
		return Mth.clamp(heat + nearPortal * 0.5F, 0.0F, 1.0F);
	}

	/** How strong the portal's passage is on the eyes now (0-1), and in what colour. */
	public static float portal() {
		return de.rcm.ballistic.client.ModConfig.heatShimmer ? portal : 0.0F;
	}

	public static int portalColor() {
		return portalRgb;
	}

	/** The ripple of a portal's passage (0-1). */
	public static float portalShimmer() {
		return de.rcm.ballistic.client.ModConfig.heatShimmer ? portalShimmer : 0.0F;
	}

	public static boolean any() {
		return heat() > 0.01F || portal() > 0.01F || portalShimmer() > 0.01F;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			heat = heatTarget = nearPortal = portal = portalShimmer = 0.0F;
			lastDim = null;
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		if (lastDim != null && dim != lastDim) {
			arrive(mc, dim, lastDim);
		}
		lastDim = dim;
		boolean nether = dim == Level.NETHER;
		// look round for lava (and fire) every half second: how much lies near, below and round
		if (++scan % 10 == 0) {
			BlockPos at = mc.player.blockPosition();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			int lava = 0;
			int portals = 0;
			for (int i = 0; i < 360; i++) {
				m.set(at.getX() + R.nextInt(33) - 16, at.getY() + R.nextInt(25) - 16, at.getZ() + R.nextInt(33) - 16);
				BlockState s = mc.level.getBlockState(m);
				if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
					lava++;
				}
			}
			// a portal close by: checked block by block round you
			float closest = 99.0F;
			for (int dx = -4; dx <= 4; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -4; dz <= 4; dz++) {
						m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
						if (mc.level.getBlockState(m).is(Blocks.NETHER_PORTAL)) {
							portals++;
							closest = Math.min(closest, Mth.sqrt(dx * dx + dy * dy + dz * dz));
						}
					}
				}
			}
			float lavaNear = Mth.clamp(lava / 30.0F, 0.0F, 1.0F);
			heatTarget = nether ? 0.2F + 0.65F * lavaNear : 0.5F * lavaNear;
			nearPortal = portals > 0 ? Mth.clamp(1.0F - closest / 5.0F, 0.0F, 1.0F) * 0.7F : nearPortal * 0.5F;
		}
		heat += (heatTarget - heat) * 0.03F;
		// in the portal: the pressure building in the ears, the air boiling
		float inside = mc.player.portalEffectIntensity;
		portalShimmer = Math.max(portalShimmer * 0.96F, inside);
		portal *= 0.965F;
		if (portal < 0.005F) {
			portal = 0.0F;
		}
	}

	/** Through a portal into {@code dim}: what hits you on the other side. */
	private static void arrive(Minecraft mc, ResourceKey<Level> dim, ResourceKey<Level> from) {
		if (dim == Level.NETHER) {
			// the heat: like opening an oven, the eyes squinting, the air swimming
			portal = 0.8F;
			portalRgb = 0xFF6A1E;
			portalShimmer = 1.0F;
			heat = 0.9F;
		} else if (dim == Level.END) {
			// the cold and the stillness of the void
			portal = 0.75F;
			portalRgb = 0x9AC4FF;
			portalShimmer = 0.6F;
		} else if (from == Level.NETHER) {
			// out of the heat: cool, clear air
			portal = 0.35F;
			portalRgb = 0xD8E8FF;
			portalShimmer = 0.5F;
		} else {
			portal = 0.3F;
			portalRgb = 0xC8B0FF;
		}
		// the pressure change: the ears pop and ring a moment
		ClientEffects.deafen(mc, 0.35F, 15);
	}
}
