package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Physically based shading for the guns modelled in Blender (shaders/core/gun.fsh): a base colour texture and a
 * surface map (metallic, roughness, ambient occlusion, clear coat) baked from the Blender materials, lit per
 * pixel by the entity lights with GGX highlights, the surroundings mirrored in the steel, the lacquer's sheen
 * on the wood - then the light level, a filmic curve and the fog, like any entity. Both textures are sampled
 * smoothly, not pixel by pixel.
 */
public final class GunShading {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/gun"))
		.withVertexShader(BallisticMissiles.id("core/gun"))
		.withFragmentShader(BallisticMissiles.id("core/gun"))
		.withSampler("Sampler0")
		.withSampler("Sampler2")
		.withSampler("Sampler3")
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static boolean registered;

	private GunShading() {
	}

	public static void init() {
		if (!registered) {
			registered = true;
			RenderPipelines.register(PIPELINE);
		}
	}

	/** The render type for a gun with this base colour texture and surface map. */
	public static RenderType type(String name, Identifier albedo, Identifier surface) {
		return RenderType.create("ballisticmissiles_gun_" + name, RenderSetup.builder(PIPELINE)
			.withTexture("Sampler0", albedo, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
			.withTexture("Sampler3", surface, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
			.useLightmap()
			.affectsCrumbling()
			.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
			.createRenderSetup());
	}
}
