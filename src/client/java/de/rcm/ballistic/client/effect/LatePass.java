package de.rcm.ballistic.client.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import org.jspecify.annotations.Nullable;

/**
 * Drawing that has to come after the particles: the smoke field and the volumetric clouds write no depth, so
 * drawn at the end of the main pass every particle behind them (fireballs, explosion smoke, sparks) was
 * painted over them afterwards and showed straight through. Drawn after the particle pass instead, they
 * lie over what is behind them, and the particles in front of them still hide them through the depth the
 * particles wrote. Run from {@code LevelRendererMixin}, in a frame pass of its own right after the particles.
 */
public final class LatePass {
	private static final List<Consumer<WorldRenderContext>> RENDERERS = new ArrayList<>();
	private static @Nullable WorldRenderContext context;

	private LatePass() {
	}

	/** Renderers run in the order they are registered. */
	public static void register(Consumer<WorldRenderContext> renderer) {
		if (RENDERERS.isEmpty()) {
			// the frame's context (pose, buffers) is the same one the later pass draws with
			WorldRenderEvents.END_MAIN.register(c -> context = c);
		}
		RENDERERS.add(renderer);
	}

	public static void run() {
		WorldRenderContext c = context;
		if (c == null) {
			return;
		}
		context = null; // once per frame, and never with a stale context
		for (Consumer<WorldRenderContext> renderer : RENDERERS) {
			renderer.accept(c);
		}
	}
}
