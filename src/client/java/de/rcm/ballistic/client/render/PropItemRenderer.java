package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.gun.GunState;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Ammunition as a solid object instead of a flat sprite: the AK and Glock magazines, a shotgun shell and the
 * PG-7 round, drawn from the same meshes the guns load. The mesh is centred on the item's middle, so on the
 * ground ({@link GroundProps}), in the hand or in a frame it sits where a block item would.
 */
public final class PropItemRenderer implements NoDataSpecialModelRenderer {
	private final BoxMesh mesh;
	private final net.minecraft.client.renderer.rendertype.RenderType type;
	private final float[] bounds;

	private PropItemRenderer(BoxMesh mesh, net.minecraft.client.renderer.rendertype.RenderType type) {
		this.mesh = mesh;
		this.type = type;
		this.bounds = mesh.bounds();
	}

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("prop"), Unbaked.MAP_CODEC);
	}

	/** The meshes by name, as the item model files name them. */
	static BoxMesh mesh(String name) {
		return switch (name) {
			case "ak_magazine_tracer" -> AkItemRenderer.magazineMesh(GunState.TRACER, true);
			case "ak_magazine_tracer_red" -> AkItemRenderer.magazineMesh(GunState.TRACER_RED, true);
			case "glock_magazine" -> GlockItemRenderer.MAG;
			case "glock_magazine_extended" -> GlockItemRenderer.MAG_EXT;
			case "shotgun_shell" -> ShotgunItemRenderer.SHELL;
			case "rpg_rocket" -> RPG_ROUND;
			default -> AkItemRenderer.magazineMesh(GunState.BALL, true);
		};
	}

	/** The whole PG-7 round: the rocket body with its folded fins. */
	static final BoxMesh RPG_ROUND = BoxMesh.merge(RpgGrenadeRenderer.BODY, RpgGrenadeRenderer.FINS);

	@Override
	public void submit(ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		float[] b = this.bounds;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F); // the middle of the item space...
		poseStack.translate(-(b[0] + b[3]) * 0.5F, -(b[1] + b[4]) * 0.5F, -(b[2] + b[5]) * 0.5F); // ...on the middle of the mesh
		collector.submitCustomGeometry(poseStack, this.type, (pose, consumer) -> this.mesh.emit(pose, consumer, light));
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		float[] b = this.bounds;
		float hx = (b[3] - b[0]) * 0.5F;
		float hy = (b[4] - b[1]) * 0.5F;
		float hz = (b[5] - b[2]) * 0.5F;
		output.accept(new Vector3f(0.5F - hx, 0.5F - hy, 0.5F - hz));
		output.accept(new Vector3f(0.5F + hx, 0.5F + hy, 0.5F + hz));
	}

	public record Unbaked(String mesh) implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(
			i -> i.group(Codec.STRING.fieldOf("mesh").forGetter(Unbaked::mesh)).apply(i, Unbaked::new));

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			// the AK magazines are textured from the rifle's own baked texture
			return new PropItemRenderer(PropItemRenderer.mesh(this.mesh), this.mesh.startsWith("ak_") ? AkItemRenderer.TYPE : StructureKit.TYPE);
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
