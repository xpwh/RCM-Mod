package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.GORE_BLOOD;
import static de.rcm.ballistic.client.render.StructureKit.GORE_BONE;
import static de.rcm.ballistic.client.render.StructureKit.GORE_CLOTH;
import static de.rcm.ballistic.client.render.StructureKit.GORE_FLESH;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * The gore on a player's body, all of it moving with the part it is on:
 * <ul>
 *   <li>a leg shot off below the knee or an arm below the elbow: the trouser leg or sleeve torn to ragged,
 *       blood-soaked strips, the raw end of the muscle bulging out of it, the two bones (shin and fibula,
 *       radius and ulna) sticking out white and splintered, strings of blood hanging from it and drops
 *       falling off - at the end of the limb {@code PlayerModelMixin} shortened;</li>
 *   <li>the head wounds - a graze open to the skull, the skull blown open - see {@link GoreHead}; here only
 *       the drops falling off the jaw.</li>
 * </ul>
 */
public final class StumpLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** One model pixel. */
	private static final float P = 1.0F / 16.0F;
	private static final BoxMesh LEFT = stump(1);
	private static final BoxMesh RIGHT = stump(2);
	private static final BoxMesh ARM_LEFT = stump(3);
	private static final BoxMesh ARM_RIGHT = stump(4);
	private static final BoxMesh DROP = new BoxMesh.Builder().box(-0.18F * P, 0.0F, -0.18F * P, 0.18F * P, 0.55F * P, 0.18F * P, GORE_BLOOD).build();

	public StumpLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		LostLeg gore = (LostLeg) state;
		int legs = gore.ballisticmissiles$lostLeg();
		int arms = gore.ballisticmissiles$lostArm();
		int head = gore.ballisticmissiles$head();
		if ((legs | arms | head) == 0 || !ModConfig.gore || state.isInvisible) {
			return;
		}
		PlayerModel model = this.getParentModel();
		float age = state.ageInTicks;
		boolean slim = state.skin != null && state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		for (int side : new int[] {Wounds.LEFT, Wounds.RIGHT}) {
			if ((legs & side) != 0) {
				ModelPart leg = side == Wounds.LEFT ? model.leftLeg : model.rightLeg;
				// the end of the shortened leg: 12 px down in the leg's own, squashed, space
				this.stumpAt(poseStack, collector, light, leg, 0.0F, 12.0F, 1.0F, side == Wounds.LEFT ? LEFT : RIGHT, age, side);
			}
			if ((arms & side) != 0) {
				ModelPart arm = side == Wounds.LEFT ? model.leftArm : model.rightArm;
				// the arm hangs from the shoulder 2 px above its top; its middle is 1 px out (half a pixel, slim)
				float cx = (side == Wounds.LEFT ? 1.0F : -1.0F) * (slim ? 0.5F : 1.0F);
				this.stumpAt(poseStack, collector, light, arm, cx, 10.0F, slim ? 0.75F : 0.95F, side == Wounds.LEFT ? ARM_LEFT : ARM_RIGHT, age, side + 2);
			}
		}
		if (head != 0) {
			poseStack.pushPose();
			model.head.translateAndRotate(poseStack);
			GoreHead.submit(poseStack, collector, light, head);
			// drops running off the chin (and, the skull open, out of it)
			float[][] drips = head == Wounds.SHATTERED
				? new float[][] {{1.2F, 0.4F, -3.4F}, {-1.5F, 0.4F, -3.0F}, {-3.0F, -2.6F, 2.2F}}
				: new float[][] {{3.6F, 0.4F, -2.2F}};
			for (int i = 0; i < drips.length; i++) {
				float phase = ((age + i * 6.1F) % 17.0F) / 17.0F;
				if (phase > 0.85F) {
					continue;
				}
				poseStack.pushPose();
				poseStack.translate(drips[i][0] * P, (drips[i][1] + phase * phase * 16.0F) * P, drips[i][2] * P);
				poseStack.scale(1.0F, 1.0F + phase * 1.5F, 1.0F);
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DROP.emit(pose, consumer, light));
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}

	/**
	 * A stump at the end of a limb shortened by {@code PlayerModelMixin}: {@code end} px down the part's own
	 * (squashed) space, {@code cx} px across to its middle; drawn at true scale, {@code width} times as wide.
	 */
	private void stumpAt(PoseStack poseStack, SubmitNodeCollector collector, int light, ModelPart part, float cx, float end, float width, BoxMesh mesh, float age,
		int seed) {
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		poseStack.translate(cx * P, end * P, 0.0F);
		if (part.yScale > 0.01F) {
			poseStack.scale(1.0F, 1.0F / part.yScale, 1.0F);
		}
		poseStack.scale(width, width, width);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> mesh.emit(pose, consumer, light));
		// drops running off the stump and falling away
		for (int i = 0; i < 2; i++) {
			float phase = ((age + seed * 7.3F + i * 11.0F) % 19.0F) / 19.0F;
			if (phase > 0.85F) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate((i == 0 ? 0.6F : -0.9F) * P, (1.2F + phase * phase * 14.0F) * P, (i == 0 ? -0.4F : 0.8F) * P);
			poseStack.scale(1.0F, 1.0F + phase * 1.5F, 1.0F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DROP.emit(pose, consumer, light));
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x * P, y * P, z * P);
	}

	/**
	 * In the leg's space, in pixels: the leg ran down +Y from the hip, 4 x 4 px (the trouser layer 4.5),
	 * cut off at y = 0. Everything here hangs on down from the cut. Each side torn its own way.
	 */
	private static BoxMesh stump(int seed) {
		RandomSource r = RandomSource.create(1903L * seed);
		BoxMesh.Builder b = new BoxMesh.Builder();
		// the raw end: the muscle bulging out of the cut, in torn lumps
		b.box(-2.0F * P, -0.3F * P, -2.0F * P, 2.0F * P, 0.45F * P, 2.0F * P, GORE_FLESH);
		float[][] lumps = {{-1.9F, -1.7F, -0.5F, 0.1F, 1.15F}, {0.3F, -1.9F, 1.9F, -0.4F, 0.9F}, {-1.3F, 0.5F, 0.4F, 1.9F, 1.35F},
			{0.8F, 0.6F, 2.0F, 1.95F, 0.75F}, {-0.6F, -0.7F, 0.6F, 0.6F, 0.6F}};
		for (float[] l : lumps) {
			float j = (r.nextFloat() - 0.5F) * 0.3F;
			b.box((l[0] + j) * P, 0.2F * P, (l[1] - j) * P, (l[2] + j) * P, (l[4] + j) * P, (l[3] - j) * P, GORE_FLESH);
		}
		// clotted blood in the folds
		b.box(-1.6F * P, 0.44F * P, 0.9F * P, -0.2F * P, 0.62F * P, 1.8F * P, GORE_BLOOD);
		b.box(0.9F * P, 0.44F * P, -1.7F * P, 1.8F * P, 0.7F * P, -0.6F * P, GORE_BLOOD);
		// the shinbone: thick, a little off centre to the front, broken off in long splinters
		float tx = 0.35F;
		float tz = -0.55F;
		float tl = 2.3F + r.nextFloat() * 0.6F;
		b.revolve(GORE_BONE, v(tx, -0.4F, tz), new Vector3f(0, 1, 0), new float[][] {{0.0F, 0.6F * P}, {1.4F * P, 0.52F * P}, {(tl + 0.4F) * P, 0.46F * P}}, 10);
		b.revolve(GORE_BLOOD, v(tx, tl + 0.3F, tz), new Vector3f(0, 1, 0), new float[][] {{0.0F, 0.38F * P}, {0.06F * P, 0.0F}}, 8); // the marrow
		b.beam(v(tx + 0.3F, tl, tz + 0.2F), v(tx + 0.55F, tl + 1.3F, tz + 0.1F), 0.32F * P, 0.22F * P, GORE_BONE);
		b.beam(v(tx - 0.4F, tl - 0.1F, tz - 0.3F), v(tx - 0.3F, tl + 0.8F, tz - 0.75F), 0.26F * P, 0.2F * P, GORE_BONE);
		b.beam(v(tx, tl + 0.2F, tz + 0.5F), v(tx + 0.1F, tl + 0.65F, tz + 0.85F), 0.2F * P, 0.16F * P, GORE_BONE);
		// the fibula: thin, out at the side, snapped shorter
		float fx = seed % 2 == 1 ? -1.25F : 1.25F;
		b.revolve(GORE_BONE, v(fx, -0.4F, 0.75F), new Vector3f(0, 1, 0), new float[][] {{0.0F, 0.24F * P}, {1.9F * P, 0.21F * P}}, 8);
		b.beam(v(fx, 1.4F, 0.75F), v(fx + (seed % 2 == 1 ? -0.2F : 0.2F), 2.15F, 0.95F), 0.2F * P, 0.14F * P, GORE_BONE);
		// the trouser leg torn into strips round the edge, soaked through, hanging down and flaring a little
		for (int i = 0; i < 12; i++) {
			int face = i / 3;
			float along = -1.5F + (i % 3) * 1.5F + (r.nextFloat() - 0.5F) * 0.5F;
			float len = 0.3F + r.nextFloat() * r.nextFloat() * 2.2F;
			float flare = 0.15F + r.nextFloat() * 0.35F;
			float w = 0.45F + r.nextFloat() * 0.45F;
			Vector3f out = switch (face) {
				case 0 -> new Vector3f(0, 0, -1);
				case 1 -> new Vector3f(1, 0, 0);
				case 2 -> new Vector3f(0, 0, 1);
				default -> new Vector3f(-1, 0, 0);
			};
			Vector3f tangent = new Vector3f(out.z, 0, -out.x);
			Vector3f top = new Vector3f(out).mul(2.2F).add(new Vector3f(tangent).mul(along)).add(0, -0.5F, 0);
			Vector3f bottom = new Vector3f(out).mul(2.15F + flare).add(new Vector3f(tangent).mul(along + (r.nextFloat() - 0.5F) * 0.6F)).add(0, len, 0);
			// a strip: thin across the leg's surface, as wide as torn
			boolean sideways = face == 1 || face == 3;
			b.beam(v(top.x, top.y, top.z), v(bottom.x, bottom.y, bottom.z), (sideways ? 0.2F : w) * P, (sideways ? w : 0.2F) * P, GORE_CLOTH);
		}
		// the ring of soaked cloth where the trousers end
		b.box(-2.3F * P, -0.9F * P, -2.3F * P, 2.3F * P, -0.2F * P, 2.3F * P, GORE_CLOTH);
		// strings of blood hanging off it
		for (int i = 0; i < 3; i++) {
			float x = (r.nextFloat() - 0.5F) * 3.0F;
			float z = (r.nextFloat() - 0.5F) * 3.4F;
			float len = 0.7F + r.nextFloat() * 1.6F;
			b.beam(v(x, 0.6F, z), v(x + (r.nextFloat() - 0.5F) * 0.3F, 0.6F + len, z), (0.12F + r.nextFloat() * 0.08F) * P, (0.12F + r.nextFloat() * 0.08F) * P,
				GORE_BLOOD);
		}
		return b.build();
	}
}
