package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.gun.C4Client;
import de.rcm.ballistic.gun.C4ChargeEntity;
import java.util.function.Consumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * C4 and its firing device, modelled in Blender (tools/structures/c4.py) and drawn with the guns' shader: the charge
 * stuck to a wall (its red LED blinking, steady once the signal came), the charge in the hand, and the firing device
 * with its moving parts - the red safety cover on its hinge, the FIRE button, the arming switch - and its two LEDs.
 */
public final class C4Models {
	public static final StructureModel CHARGE = StructureModel.load("c4_charge");
	public static final StructureModel DETONATOR = StructureModel.load("c4_detonator");
	private static final RenderType GLOW = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/glow.png"));
	/** On the charge: its LED (local space, c4.py). */
	public static final Vector3f LED = new Vector3f(0.027F, 0.0508F + 0.024F + 0.003F, -0.021F);
	/** On the firing device (c4.py): the cover's hinge, the button, the switch's pivot, the LEDs. */
	public static final Vector3f COVER_HINGE = new Vector3f(0.012F, 0.016F + 0.0105F, -0.0165F);
	public static final Vector3f BUTTON = new Vector3f(0.012F, 0.016F, -0.03F);
	public static final Vector3f SWITCH_PIVOT = new Vector3f(-0.015F, 0.016F + 0.004F, -0.004F);
	public static final Vector3f LED_PWR = new Vector3f(0.010F, 0.016F + 0.003F, 0.006F);
	public static final Vector3f LED_ARM = new Vector3f(0.021F, 0.016F + 0.003F, 0.006F);

	private C4Models() {
	}

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("c4"), ChargeItem.Unbaked.MAP_CODEC);
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("c4_detonator"), DetonatorItem.Unbaked.MAP_CODEC);
	}

	/** A small glowing dot facing the camera at {@code at} (in the pose's space), {@code rgb}, {@code size} blocks across. */
	static void glow(PoseStack poseStack, SubmitNodeCollector collector, Vector3fc at, Quaternionf toCamera, int rgb, float alpha, float size) {
		poseStack.pushPose();
		poseStack.translate(at.x(), at.y(), at.z());
		poseStack.mulPose(toCamera);
		int c = (int) (Math.min(1.0F, alpha) * 255.0F) << 24 | rgb;
		float h = size * 0.5F;
		collector.submitCustomGeometry(poseStack, GLOW, (pose, consumer) -> {
			float[][] q = {{-h, -h, 0, 1}, {h, -h, 1, 1}, {h, h, 1, 0}, {-h, h, 0, 0}};
			for (float[] v : q) {
				consumer.addVertex(pose, v[0], v[1], 0.0F).setColor(c).setUv(v[2], v[3]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
			}
		});
		poseStack.popPose();
	}

	/** Turns the pose so the charge's +y points out of {@code face}, its long side level (on a wall) or turned by {@code spin}. */
	public static void orient(PoseStack poseStack, Direction face, float spin) {
		switch (face) {
			case UP -> poseStack.mulPose(Axis.YP.rotationDegrees(-spin));
			case DOWN -> {
				poseStack.mulPose(Axis.YP.rotationDegrees(-spin));
				poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
			}
			default -> {
				poseStack.mulPose(Axis.YP.rotationDegrees(-face.toYRot()));
				poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
			}
		}
	}

	// ------------------------------------------------------------------ the charge on a wall

	public static final class ChargeRenderer extends EntityRenderer<C4ChargeEntity, ChargeRenderer.State> {
		public ChargeRenderer(EntityRendererProvider.Context context) {
			super(context);
			this.shadowRadius = 0.0F;
		}

		public static class State extends EntityRenderState {
			public Direction face = Direction.UP;
			public float spin;
			public boolean triggered;
			public int age;
			public int seed;
			/** On a host: its turn in the world (it is drawn where the host is this frame), else null. */
			public Quaternionf onHost;
			public boolean hidden;
		}

		@Override
		public State createRenderState() {
			return new State();
		}

		@Override
		public void extractRenderState(C4ChargeEntity entity, State state, float partialTick) {
			super.extractRenderState(entity, state, partialTick);
			state.face = entity.face();
			state.spin = entity.spin();
			state.triggered = entity.triggered();
			state.age = entity.tickCount;
			state.seed = entity.getId();
			state.onHost = null;
			state.hidden = false;
			C4ChargeEntity.Pose pose = entity.onHost() ? entity.hostPose(partialTick) : null;
			if (pose != null) {
				state.x = pose.pos().x;
				state.y = pose.pos().y;
				state.z = pose.pos().z;
				state.onHost = pose.rotation();
				// on your own back in first person: out of the way of the camera
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				state.hidden = entity.host() == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
			}
		}

		@Override
		public boolean shouldRender(C4ChargeEntity entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
			// a jet carries it faster than its own box keeps up: drawn whenever it is near enough
			return entity.onHost() ? entity.shouldRender(x, y, z) : super.shouldRender(entity, frustum, x, y, z);
		}

		@Override
		public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			if (state.hidden) {
				return;
			}
			poseStack.pushPose();
			if (state.onHost != null) {
				poseStack.mulPose(state.onHost);
			} else {
				orient(poseStack, state.face, state.spin);
			}
			CHARGE.submit("charge", poseStack, collector, state.lightCoords);
			// the LED: a short red blink every second, steady once the signal came
			int phase = (state.age + state.seed * 7) % 20;
			if (state.triggered || phase < 3) {
				Quaternionf toCamera = new Quaternionf(poseStack.last().pose().getNormalizedRotation(new Quaternionf())).invert().mul(camera.orientation);
				glow(poseStack, collector, LED, toCamera, 0xFF2010, state.triggered ? 1.0F : 0.9F, 0.035F);
			}
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
		}
	}

	// ------------------------------------------------------------------ in the hand

	/** The charge as an item: lying in the hand, its receiver up. */
	public static final class ChargeItem implements SpecialModelRenderer<ItemStack> {
		@Override
		public void submit(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
			int outline) {
			poseStack.pushPose();
			poseStack.translate(0.5F, 0.5F, 0.5F);
			if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
				// across both hands, tipped up at you (C4Motion), or turned onto the wall while placing it
				poseStack.mulPose(de.rcm.ballistic.client.gun.C4Motion.chargeModel(C4Client.placing(stack)));
			} else if (context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
				// carried like a brick, the long side forward
				poseStack.translate(0.0F, -0.04F, -0.04F);
				poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
			}
			CHARGE.submit("charge", poseStack, collector, light);
			poseStack.popPose();
		}

		@Override
		public void getExtents(Consumer<Vector3fc> output) {
			output.accept(new Vector3f(0.35F, 0.4F, 0.2F));
			output.accept(new Vector3f(0.65F, 0.6F, 0.8F));
		}

		@Override
		public ItemStack extractArgument(ItemStack stack) {
			return stack;
		}

		public record Unbaked() implements SpecialModelRenderer.Unbaked {
			public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

			@Override
			public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
				return new ChargeItem();
			}

			@Override
			public MapCodec<Unbaked> type() {
				return MAP_CODEC;
			}
		}
	}

	/** The firing device as an item, its cover, button and switch where the hand has put them (C4Client). */
	public static final class DetonatorItem implements SpecialModelRenderer<C4Client.DevicePose> {
		@Override
		public void submit(C4Client.DevicePose pose, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
			boolean foil, int outline) {
			C4Client.DevicePose p = pose == null ? C4Client.DevicePose.REST : pose;
			poseStack.pushPose();
			poseStack.translate(0.5F, 0.5F, 0.5F);
			boolean flat = context == ItemDisplayContext.GUI || context == ItemDisplayContext.GROUND || context == ItemDisplayContext.FIXED;
			if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
				poseStack.mulPose(de.rcm.ballistic.client.gun.C4Motion.deviceModel());
			}
			DETONATOR.submit("body", poseStack, collector, light);
			poseStack.pushPose();
			poseStack.translate(COVER_HINGE.x, COVER_HINGE.y, COVER_HINGE.z);
			poseStack.mulPose(Axis.XP.rotationDegrees(p.cover()));
			DETONATOR.submit("cover", poseStack, collector, light);
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(BUTTON.x, BUTTON.y - p.press(), BUTTON.z);
			DETONATOR.submit("button", poseStack, collector, light);
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(SWITCH_PIVOT.x, SWITCH_PIVOT.y, SWITCH_PIVOT.z);
			// modelled at SAFE (leaning back towards you), flicked forward to ARMED
			poseStack.mulPose(Axis.XP.rotationDegrees(p.armed() ? -50.0F : 0.0F));
			DETONATOR.submit("switch", poseStack, collector, light);
			poseStack.popPose();
			if (!flat) {
				Quaternionf toCamera = new Quaternionf(poseStack.last().pose().getNormalizedRotation(new Quaternionf())).invert();
				glow(poseStack, collector, LED_PWR, toCamera, 0x30FF40, 0.75F, 0.012F);
				if (p.armed()) {
					glow(poseStack, collector, LED_ARM, toCamera, 0xFF2010, 0.95F, 0.014F);
				}
			}
			poseStack.popPose();
		}

		@Override
		public void getExtents(Consumer<Vector3fc> output) {
			output.accept(new Vector3f(0.4F, 0.4F, 0.3F));
			output.accept(new Vector3f(0.6F, 0.6F, 0.7F));
		}

		@Override
		public C4Client.DevicePose extractArgument(ItemStack stack) {
			return C4Client.devicePose(stack);
		}

		public record Unbaked() implements SpecialModelRenderer.Unbaked {
			public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

			@Override
			public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
				return new DetonatorItem();
			}

			@Override
			public MapCodec<Unbaked> type() {
				return MAP_CODEC;
			}
		}
	}
}
