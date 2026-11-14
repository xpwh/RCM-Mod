package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * A structure modelled in Blender (tools/structures): its parts, each in block space about its own pivot, and
 * the physically based look baked from the Blender materials - grime, worn paint, rust, stained concrete and the
 * occlusion in every corner - drawn with the guns' shader ({@link GunShading}).
 */
public final class StructureModel {
	private final Map<String, BoxMesh> parts;
	private final RenderType type;

	private StructureModel(String name) {
		GunShading.init();
		this.parts = AkItemRenderer.load("/assets/ballisticmissiles/meshes/" + name + ".mesh.gz");
		this.type = GunShading.type(name, BallisticMissiles.id("textures/entity/" + name + ".png"),
			BallisticMissiles.id("textures/entity/" + name + "_mat.png"));
	}

	public static StructureModel load(String name) {
		return new StructureModel(name);
	}

	public BoxMesh part(String name) {
		BoxMesh m = this.parts.get(name);
		if (m == null) {
			throw new IllegalArgumentException("no part " + name);
		}
		return m;
	}

	public RenderType type() {
		return this.type;
	}

	/** Draws a part where the pose stack is now. */
	public void submit(String name, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		BoxMesh mesh = this.part(name);
		collector.submitCustomGeometry(poseStack, this.type, (pose, consumer) -> mesh.emit(pose, consumer, light));
	}
}
