package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.block.CommandCenterBlock;
import de.rcm.ballistic.block.CommandCenterBlockEntity;
import de.rcm.ballistic.block.DefenseSiteBlock;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The command center as a military operations console: a steel desk with vents and locked doors, a sloped
 * deck with the keyboard, trackball, a row of status lamps and the launch switch under its striped guard,
 * and on the instrument tower three screens turned towards the operator - the situation map with tracks
 * crawling over its grid in the middle, a radar scope sweeping on the left, a scrolling message log on the
 * right. A field telephone hangs on the side, a whip antenna and a rotating beacon stand on top. On alarm
 * the beacon turns and flashes red, the screens go red and the log fills with warnings.
 * <p>
 * Model space: origin at the middle of the block's floor, the operator stands on the +Z side.
 */
public class CommandCenterRenderer implements BlockEntityRenderer<CommandCenterBlockEntity, CommandCenterRenderer.State> {
	private static final float DECK_FRONT_Z = 0.44F;
	private static final float DECK_BACK_Z = 0.08F;
	private static final float DECK_FRONT_Y = 0.70F;
	private static final float DECK_BACK_Y = 0.80F;
	/** The three screens: centre x, centre y, z of the glass, half width, half height, yaw towards the operator. */
	private static final float[][] SCREENS = {
		{0.0F, 1.07F, 0.035F, 0.2F, 0.15F, 0.0F},
		{-0.33F, 1.02F, 0.08F, 0.12F, 0.1F, 0.42F},
		{0.33F, 1.02F, 0.08F, 0.12F, 0.1F, -0.42F},
	};
	private static final BoxMesh CONSOLE = buildConsole();
	/** A unit square of light, facing +Z: scaled and placed for every line and dot on the screens. */
	private static final BoxMesh DOT = new BoxMesh.Builder().box(-0.5F, -0.5F, 0.0F, 0.5F, 0.5F, 0.001F, GREEN_LAMP).build();
	private static final BoxMesh DOT_RED = new BoxMesh.Builder().box(-0.5F, -0.5F, 0.0F, 0.5F, 0.5F, 0.001F, RED_LAMP).build();
	private static final BoxMesh DOT_WHITE = new BoxMesh.Builder().box(-0.5F, -0.5F, 0.0F, 0.5F, 0.5F, 0.001F, LAMP).build();
	private static final BoxMesh BEACON_DOME = new BoxMesh.Builder()
		.revolve(RED_LAMP, v(0.3F, 1.36F, -0.28F), v(0, 1, 0), new float[][] {{0.0F, 0.045F}, {0.06F, 0.045F}, {0.085F, 0.03F}, {0.095F, 0.0F}}, 12)
		.build();
	private static final BoxMesh BEACON_MIRROR = new BoxMesh.Builder().box(-0.004F, -0.025F, -0.035F, 0.004F, 0.025F, 0.035F, LAMP).build();

	public CommandCenterRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facingYaw;
		public float time;
		public boolean alarm;
		public int seed;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CommandCenterBlockEntity center, State state, float partialTick, Vec3 cameraPos,
		ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(center, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(DefenseSiteBlock.FACING) ? state.blockState.getValue(DefenseSiteBlock.FACING) : Direction.NORTH;
		state.facingYaw = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.time = center.getLevel() == null ? 0.0F : (center.getLevel().getGameTime() % 240000L) + partialTick;
		state.alarm = state.blockState.hasProperty(CommandCenterBlock.POWERED) && state.blockState.getValue(CommandCenterBlock.POWERED);
		state.seed = center.getBlockPos().hashCode();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		ModelPick.part(state, poseStack, camera, CONSOLE);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CONSOLE.emit(pose, consumer, light));

		float t = state.time;
		boolean alarm = state.alarm;
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> {
			PoseStack ps = new PoseStack();
			ps.last().pose().set(pose.pose());
			ps.last().normal().set(pose.normal());
			for (int i = 0; i < SCREENS.length; i++) {
				float[] s = SCREENS[i];
				ps.pushPose();
				ps.translate(s[0], s[1], s[2] + 0.002F);
				ps.mulPose(new Quaternionf().rotationY(s[5]));
				switch (i) {
					case 0 -> situationMap(ps, consumer, s[3], s[4], t, alarm, state.seed);
					case 1 -> radarScope(ps, consumer, s[3], s[4], t, alarm);
					default -> messageLog(ps, consumer, s[3], s[4], t, alarm);
				}
				ps.popPose();
			}
			statusLamps(ps, consumer, t, alarm);
			beacon(ps, consumer, t, alarm);
		});
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 96;
	}

	// ------------------------------------------------------------------ the screens

	/** A dot or line of light on a screen: centre (x, y), size (w, h), turned by {@code angle}, with a brightness. */
	private static void mark(PoseStack ps, VertexConsumer consumer, BoxMesh mesh, float x, float y, float w, float h, float angle, float alpha) {
		ps.pushPose();
		ps.translate(x, y, 0.0F);
		if (angle != 0.0F) {
			ps.mulPose(new Quaternionf().rotationZ(angle));
		}
		ps.scale(w, h, 1.0F);
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		mesh.emit(ps.last(), consumer, LightTexture.FULL_BRIGHT, a << 24 | 0xFFFFFF);
		ps.popPose();
	}

	private static void situationMap(PoseStack ps, VertexConsumer c, float hw, float hh, float t, boolean alarm, int seed) {
		BoxMesh ink = alarm ? DOT_RED : DOT;
		// grid, faint
		for (int i = -4; i <= 4; i++) {
			mark(ps, c, ink, i * hw / 4.5F, 0.0F, 0.0025F, hh * 1.9F, 0.0F, 0.18F);
		}
		for (int j = -3; j <= 3; j++) {
			mark(ps, c, ink, 0.0F, j * hh / 3.5F, hw * 1.9F, 0.0025F, 0.0F, 0.18F);
		}
		// header bar and a coast-like line
		mark(ps, c, ink, 0.0F, hh - 0.012F, hw * 1.9F, 0.014F, 0.0F, 0.45F);
		for (int k = 0; k < 14; k++) {
			float x0 = -hw * 0.9F + k * hw * 1.8F / 14.0F;
			float y0 = -hh * 0.35F + Mth.sin(k * 1.3F + seed) * hh * 0.18F;
			float y1 = -hh * 0.35F + Mth.sin((k + 1) * 1.3F + seed) * hh * 0.18F;
			float dx = hw * 1.8F / 14.0F;
			float len = Mth.sqrt(dx * dx + (y1 - y0) * (y1 - y0));
			mark(ps, c, ink, x0 + dx * 0.5F, (y0 + y1) * 0.5F, len, 0.004F, (float) Math.atan2(y1 - y0, dx), 0.55F);
		}
		// own position, and tracks crawling across with a short trail
		mark(ps, c, DOT_WHITE, 0.0F, -hh * 0.1F, 0.012F, 0.012F, 0.0F, 0.9F);
		int tracks = alarm ? 5 : 3;
		for (int k = 0; k < tracks; k++) {
			float speed = 0.004F + 0.002F * k;
			float phase = (t * speed + k * 0.37F) % 1.0F;
			float x = -hw * 0.95F + phase * hw * 1.9F;
			float y = hh * (0.55F - 0.28F * k) + Mth.sin(phase * 3.0F + k) * hh * 0.1F;
			BoxMesh dot = alarm && k % 2 == 0 ? DOT_RED : k == 1 ? DOT_WHITE : DOT;
			for (int tail = 4; tail >= 1; tail--) {
				mark(ps, c, dot, x - tail * 0.012F, y - Mth.cos(phase * 3.0F + k) * tail * 0.002F, 0.006F, 0.006F, 0.0F, 0.15F * (5 - tail));
			}
			mark(ps, c, dot, x, y, 0.014F, 0.014F, Mth.PI / 4.0F, 0.95F);
		}
	}

	private static void radarScope(PoseStack ps, VertexConsumer c, float hw, float hh, float t, boolean alarm) {
		BoxMesh ink = alarm ? DOT_RED : DOT;
		float r = Math.min(hw, hh) * 0.92F;
		for (int ring = 1; ring <= 3; ring++) {
			float rr = r * ring / 3.0F;
			int n = 24;
			for (int k = 0; k < n; k++) {
				float a = Mth.TWO_PI * k / n;
				mark(ps, c, ink, Mth.cos(a) * rr, Mth.sin(a) * rr, Mth.TWO_PI * rr / n, 0.0025F, a + Mth.HALF_PI, ring == 3 ? 0.5F : 0.22F);
			}
		}
		mark(ps, c, ink, 0.0F, 0.0F, r * 2.0F, 0.002F, 0.0F, 0.2F);
		mark(ps, c, ink, 0.0F, 0.0F, 0.002F, r * 2.0F, 0.0F, 0.2F);
		// the sweep and its afterglow
		float sweep = -t * (alarm ? 0.2F : 0.1F);
		for (int k = 0; k < 6; k++) {
			float a = sweep + k * 0.07F;
			mark(ps, c, ink, Mth.cos(a) * r * 0.5F, Mth.sin(a) * r * 0.5F, r, k == 0 ? 0.004F : 0.003F, a, k == 0 ? 0.95F : 0.35F - k * 0.05F);
		}
		// contacts lighting up as the sweep passes
		float[][] blips = {{0.6F, 0.55F}, {2.3F, 0.8F}, {3.9F, 0.35F}, {5.1F, 0.7F}};
		for (float[] b : blips) {
			float behind = Mth.positiveModulo(b[0] - sweep, Mth.TWO_PI);
			float glow = 1.0F - behind / Mth.TWO_PI;
			mark(ps, c, alarm ? DOT_RED : DOT, Mth.cos(b[0]) * b[1] * r, Mth.sin(b[0]) * b[1] * r, 0.01F, 0.01F, 0.0F, glow * glow);
		}
	}

	private static void messageLog(PoseStack ps, VertexConsumer c, float hw, float hh, float t, boolean alarm) {
		BoxMesh ink = alarm ? DOT_RED : DOT;
		int lines = 9;
		float step = hh * 2.0F / (lines + 1);
		float scroll = (t * 0.05F) % 1.0F;
		int first = (int) (t * 0.05F);
		for (int i = 0; i < lines; i++) {
			int n = first + i;
			float y = hh - step * (i + 1) + scroll * step;
			if (y > hh - step * 0.5F || y < -hh + step * 0.3F) {
				continue;
			}
			float len = (0.35F + 0.6F * ((n * 2654435761L >>> 8 & 255) / 255.0F)) * hw * 1.8F;
			// a line of "text": a run of short dashes, warnings in bold
			boolean warn = alarm && n % 3 == 0;
			float x = -hw * 0.9F;
			int words = (int) (len / 0.03F);
			for (int w = 0; w < words; w++) {
				float wl = 0.012F + ((n * 31 + w * 17) % 7) * 0.003F;
				mark(ps, c, warn ? DOT_RED : ink, x + wl * 0.5F, y, wl, warn ? 0.012F : 0.007F, 0.0F, warn ? 0.95F : 0.6F);
				x += wl + 0.008F;
				if (x > hw * 0.9F) {
					break;
				}
			}
		}
		if (alarm && (int) (t / 6.0F) % 2 == 0) {
			mark(ps, c, DOT_RED, 0.0F, hh - 0.025F, hw * 1.8F, 0.03F, 0.0F, 0.9F);
		}
	}

	private static void statusLamps(PoseStack ps, VertexConsumer c, float t, boolean alarm) {
		// a row of lamps along the top of the deck, tilted with it
		float slope = (float) Math.atan2(DECK_BACK_Y - DECK_FRONT_Y, DECK_FRONT_Z - DECK_BACK_Z);
		for (int i = 0; i < 8; i++) {
			float x = -0.36F + i * 0.06F;
			boolean on = alarm ? (int) (t / 4.0F + i) % 2 == 0 : i < 6 || (int) (t / 20.0F) % 2 == 0;
			BoxMesh lamp = alarm || i >= 6 ? DOT_RED : i == 3 ? DOT_WHITE : DOT;
			ps.pushPose();
			ps.translate(x, DECK_BACK_Y + 0.012F, DECK_BACK_Z + 0.03F);
			ps.mulPose(new Quaternionf().rotationX(-Mth.HALF_PI + slope));
			mark(ps, c, lamp, 0.0F, 0.0F, 0.022F, 0.022F, 0.0F, on ? 1.0F : 0.12F);
			ps.popPose();
		}
	}

	private static void beacon(PoseStack ps, VertexConsumer c, float t, boolean alarm) {
		float pulse = alarm ? 0.55F + 0.45F * Mth.sin(t * 0.9F) : 0.15F;
		int a = (int) (pulse * 255.0F);
		BEACON_DOME.emit(ps.last(), c, LightTexture.FULL_BRIGHT, a << 24 | 0xFFFFFF);
		if (alarm) {
			// the mirror turning inside it throws a bright flash round
			ps.pushPose();
			ps.translate(0.3F, 1.395F, -0.28F);
			ps.mulPose(new Quaternionf().rotationY(t * 0.6F));
			ps.translate(0.0F, 0.0F, 0.02F);
			BEACON_MIRROR.emit(ps.last(), c, LightTexture.FULL_BRIGHT, 0xFFFFFFFF);
			ps.popPose();
		}
	}

	// ------------------------------------------------------------------ the console

	/** A box of half sizes (hx, hy, hz) centred on (x, y, z), turned by {@code yaw} about its own vertical axis. */
	private static void turned(BoxMesh.Builder b, float x, float y, float z, float hx, float hy, float hz, float yaw, int patch) {
		float cs = Mth.cos(yaw);
		float sn = Mth.sin(yaw);
		Vector3f[] c = new Vector3f[8];
		float[][] signs = {{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
		for (int i = 0; i < 8; i++) {
			float lx = signs[i][0] * hx;
			float lz = signs[i][2] * hz;
			c[i] = v(x + lx * cs + lz * sn, y + signs[i][1] * hy, z - lx * sn + lz * cs);
		}
		b.hexa(patch, c);
	}

	/** A slab lying on the sloped deck: from {@code z0} to {@code z1} along it, {@code lift} above its surface. */
	private static void onDeck(BoxMesh.Builder b, float x0, float x1, float z0, float z1, float lift, float thick, int patch) {
		float k = (DECK_BACK_Y - DECK_FRONT_Y) / (DECK_FRONT_Z - DECK_BACK_Z);
		float y0 = DECK_FRONT_Y + (DECK_FRONT_Z - z0) * k + lift;
		float y1 = DECK_FRONT_Y + (DECK_FRONT_Z - z1) * k + lift;
		b.hexa(patch,
			v(x0, y0, z0), v(x0, y1, z1), v(x0, y1 + thick, z1), v(x0, y0 + thick, z0),
			v(x1, y0, z0), v(x1, y1, z1), v(x1, y1 + thick, z1), v(x1, y0 + thick, z0));
	}

	private static BoxMesh buildConsole() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// plinth and desk body: steel cabinet with vents, two locked doors and a kick plate
		b.box(-0.49F, 0.0F, -0.47F, 0.49F, 0.05F, 0.4F, BLACK);
		b.box(-0.47F, 0.05F, -0.45F, 0.47F, 0.68F, 0.32F, OLIVE_DARK);
		b.box(-0.4F, 0.08F, 0.32F, 0.4F, 0.14F, 0.325F, BLACK);
		b.box(-0.43F, 0.17F, 0.32F, -0.015F, 0.6F, 0.33F, PANEL);
		b.box(0.015F, 0.17F, 0.32F, 0.43F, 0.6F, 0.33F, PANEL);
		b.box(-0.06F, 0.36F, 0.33F, -0.035F, 0.44F, 0.345F, STEEL); // handles
		b.box(0.035F, 0.36F, 0.33F, 0.06F, 0.44F, 0.345F, STEEL);
		b.box(-0.36F, 0.22F, 0.33F, -0.1F, 0.32F, 0.335F, VENT);
		b.box(0.1F, 0.22F, 0.33F, 0.36F, 0.32F, 0.335F, VENT);
		b.box(-0.47F, 0.1F, -0.3F, -0.475F, 0.5F, 0.2F, VENT); // side vents
		b.box(0.47F, 0.1F, -0.3F, 0.475F, 0.5F, 0.2F, VENT);
		// the sloped deck, overhanging the cabinet, with its front lip
		onDeck(b, -0.47F, 0.47F, DECK_FRONT_Z, DECK_BACK_Z, -0.035F, 0.035F, OLIVE);
		b.box(-0.47F, DECK_FRONT_Y - 0.06F, DECK_FRONT_Z - 0.02F, 0.47F, DECK_FRONT_Y, DECK_FRONT_Z + 0.005F, STEEL);
		b.box(-0.47F, 0.62F, 0.3F, 0.47F, DECK_FRONT_Y - 0.02F, 0.44F, OLIVE_DARK); // under the overhang
		// keyboard: four rows of keys on a dark tray, function keys and a trackball beside it
		onDeck(b, -0.3F, 0.16F, 0.41F, 0.24F, 0.0F, 0.006F, BLACK);
		for (int row = 0; row < 4; row++) {
			float z = 0.39F - row * 0.04F;
			for (int col = 0; col < 12; col++) {
				float x = -0.285F + col * 0.037F;
				int patch = row == 3 && col < 2 ? RED : row == 3 && col > 9 ? YELLOW : WHITE;
				onDeck(b, x, x + 0.03F, z, z - 0.032F, 0.006F, 0.012F, patch);
			}
		}
		onDeck(b, 0.2F, 0.3F, 0.41F, 0.31F, 0.0F, 0.01F, BLACK); // trackball pad
		float[] ball = deck(0.25F, 0.36F);
		b.revolve(GLASS, v(0.25F, ball[0] + 0.01F, 0.36F), v(0, 1, 0), new float[][] {{0.0F, 0.02F}, {0.015F, 0.022F}, {0.03F, 0.0F}}, 10);
		// the launch switch under its striped flip guard, and a key switch
		onDeck(b, 0.33F, 0.43F, 0.41F, 0.29F, 0.0F, 0.012F, BLACK);
		onDeck(b, 0.355F, 0.405F, 0.38F, 0.33F, 0.012F, 0.02F, RED);
		onDeck(b, 0.345F, 0.415F, 0.39F, 0.31F, 0.04F, 0.006F, HAZARD); // guard, closed over the switch
		onDeck(b, -0.43F, -0.34F, 0.38F, 0.3F, 0.0F, 0.015F, STEEL);
		onDeck(b, -0.395F, -0.375F, 0.36F, 0.33F, 0.015F, 0.03F, YELLOW); // the key in it
		// instrument tower behind the deck
		b.box(-0.47F, 0.68F, -0.45F, 0.47F, 1.24F, 0.0F, OLIVE_DARK);
		b.box(-0.47F, 1.24F, -0.45F, 0.47F, 1.27F, 0.02F, STEEL); // cap
		b.box(-0.44F, 0.86F, -0.452F, 0.44F, 1.1F, -0.45F, VENT); // fans at the back
		// the three screens in their bezels
		for (float[] s : SCREENS) {
			turned(b, s[0], s[1], s[2] - 0.025F, s[3] + 0.022F, s[4] + 0.022F, 0.025F, s[5], BLACK);
			turned(b, s[0], s[1], s[2] - 0.002F, s[3], s[4], 0.002F, s[5], GLASS);
		}
		// arms holding the side screens
		b.box(-0.47F, 0.98F, 0.0F, -0.43F, 1.02F, 0.06F, STEEL);
		b.box(0.43F, 0.98F, 0.0F, 0.47F, 1.02F, 0.06F, STEEL);
		// field telephone on the left side with its coiled cord
		b.box(-0.53F, 0.42F, -0.1F, -0.47F, 0.58F, 0.08F, OLIVE);
		b.box(-0.56F, 0.56F, -0.12F, -0.52F, 0.6F, 0.1F, BLACK); // handset on its cradle
		b.box(-0.56F, 0.53F, -0.12F, -0.52F, 0.57F, -0.08F, BLACK);
		b.box(-0.56F, 0.53F, 0.06F, -0.52F, 0.57F, 0.1F, BLACK);
		b.beam(v(-0.54F, 0.53F, -0.1F), v(-0.5F, 0.44F, -0.05F), 0.01F, 0.01F, CABLE);
		// whip antenna and the beacon on top
		b.box(-0.38F, 1.27F, -0.38F, -0.32F, 1.31F, -0.32F, STEEL);
		b.beam(v(-0.35F, 1.31F, -0.35F), v(-0.35F, 2.05F, -0.35F), 0.008F, 0.008F, BLACK);
		b.revolve(STEEL, v(0.3F, 1.27F, -0.28F), v(0, 1, 0), new float[][] {{0.0F, 0.055F}, {0.09F, 0.05F}}, 12);
		// cables from the tower into the floor
		b.beam(v(0.4F, 0.7F, -0.46F), v(0.42F, 0.02F, -0.52F), 0.025F, 0.025F, CABLE);
		b.beam(v(0.3F, 0.7F, -0.46F), v(0.28F, 0.02F, -0.55F), 0.02F, 0.02F, CABLE);
		return b.build();
	}

	/** Height of the deck's top at depth z: {y}. */
	private static float[] deck(float x, float z) {
		float k = (DECK_BACK_Y - DECK_FRONT_Y) / (DECK_FRONT_Z - DECK_BACK_Z);
		return new float[] {DECK_FRONT_Y + (DECK_FRONT_Z - z) * k};
	}
}
