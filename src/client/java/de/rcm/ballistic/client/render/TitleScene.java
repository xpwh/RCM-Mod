package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.MissileType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The title screen's background: a live 3D scene instead of a picture. The mod's own launch complex at night
 * under the stars, a Minuteman on the table, floodlights and searchlights, the liquid-oxygen tank venting,
 * mountains on the horizon - and about once a minute a launch: countdown lights, the umbilical arms swing
 * away, ignition, a wall of smoke out of the flame trench, the missile climbs away on its flame and lights
 * up everything round it, and the camera follows it into the sky. The camera drifts round the pad and leans
 * with the mouse.
 * <p>
 * Drawn into a texture as a picture-in-picture GUI element with its own perspective camera. Everything is lit
 * here, per vertex, by the moon, the sky, the lamps and the rocket flame (the GUI has no world lighting).
 */
public final class TitleScene {
	private static final RenderType SOLID = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/structures.png"));
	private static final RenderType SMOKE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/title_smoke.png"));
	private static final RenderType SKY = MissileRenderer.GLOW_TYPE;
	private static final MissileType MISSILE = MissileType.NUCLEAR;
	/** One launch cycle, seconds. */
	private static final float CYCLE = 64.0F;
	private static final float ARMS_AWAY = 30.0F;
	private static final float IGNITION = 37.0F;
	private static final float LIFTOFF = 38.6F;
	/** A normal that both GUI entity lights fall on fully: the shader's own shading then changes nothing. */
	private static final float NX;
	private static final float NY;
	private static final float NZ;

	static {
		Vector3f a = new Vector3f(0.2F, -1.0F, 1.0F).normalize();
		Vector3f b = new Vector3f(-0.2F, -1.0F, 0.0F).normalize();
		Vector3f n = a.add(b).normalize();
		NX = n.x;
		NY = n.y;
		NZ = n.z;
	}

	private static final long START = Util.getMillis();
	private static final BoxMesh GROUND = buildGround();
	private static final BoxMesh HILLS = buildHills();
	private static final float[][] STARS = buildStars();
	private static int frames;

	private TitleScene() {
	}

	/** Seconds since the title screen first showed. */
	private static float time() {
		return (Util.getMillis() - START) / 1000.0F;
	}

	/** Submits the scene over the whole screen, with the fade between launch cycles on top. */
	public static void draw(GuiGraphics g, int w, int h) {
		frames++;
		g.fill(0, 0, w, h, 0xFF04060C);
		Minecraft mc = Minecraft.getInstance();
		float mx = (float) (mc.mouseHandler.xpos() / Math.max(1, mc.getWindow().getScreenWidth()) - 0.5);
		float my = (float) (mc.mouseHandler.ypos() / Math.max(1, mc.getWindow().getScreenHeight()) - 0.5);
		if (frames > 3 && RenderSystem.getShaderFog() != null) {
			g.guiRenderState.submitPicturesInPictureState(new State(0, 0, w, h, time(), mx, my, null));
		}
		// fade to black and back between launches
		float c = time() % CYCLE;
		float fade = Math.max(Mth.clamp((c - (CYCLE - 2.5F)) / 2.0F, 0.0F, 1.0F), time() < CYCLE ? 0.0F : Mth.clamp(1.0F - c / 2.0F, 0.0F, 1.0F));
		fade = Math.max(fade, Mth.clamp(1.0F - time() / 1.5F, 0.0F, 1.0F));
		if (fade > 0.01F) {
			g.fill(0, 0, w, h, (int) (fade * 255.0F) << 24);
		}
	}

	public record State(int x0, int y0, int x1, int y1, float time, float mouseX, float mouseY, @Nullable ScreenRectangle scissorArea)
		implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1.0F;
		}

		@Override
		public @Nullable ScreenRectangle bounds() {
			return PictureInPictureRenderState.getBounds(this.x0, this.y0, this.x1, this.y1, this.scissorArea);
		}
	}

	// ------------------------------------------------------------------ the renderer

	public static final class Renderer extends PictureInPictureRenderer<State> {
		private final PerspectiveProjectionMatrixBuffer projection = new PerspectiveProjectionMatrixBuffer("ballisticmissiles title scene");
		private final List<Light> lights = new ArrayList<>();
		private Vector3f cam = new Vector3f();
		private float fogDensity = 0.0032F;

		public Renderer(MultiBufferSource.BufferSource buffers) {
			super(buffers);
		}

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected String getTextureLabel() {
			return "ballisticmissiles title scene";
		}

		@Override
		public void close() {
			super.close();
			this.projection.close();
		}

		@Override
		protected void renderToTexture(State state, PoseStack ignored) {
			Minecraft mc = Minecraft.getInstance();
			int scale = mc.getWindow().getGuiScale();
			Matrix4fStack modelView = RenderSystem.getModelViewStack();
			modelView.pushMatrix();
			modelView.identity();
			mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			MultiBufferSource.BufferSource buffers = this.bufferSource;
			this.paint(state, (state.x1() - state.x0()) * scale, (state.y1() - state.y0()) * scale, new Out() {
				@Override
				public void projection(Matrix4f proj) {
					RenderSystem.setProjectionMatrix(Renderer.this.projection.getBuffer(proj), ProjectionType.PERSPECTIVE);
				}

				@Override
				public VertexConsumer get(RenderType type) {
					return buffers.getBuffer(type);
				}

				@Override
				public void flush(RenderType type) {
					buffers.endBatch(type);
				}
			});
			modelView.popMatrix();
		}

		/** The whole scene for {@code state} on a {@code w} x {@code h} pixel picture, drawn into {@code out}. */
		public void paint(State state, float w, float h, Out out) {
			float total = state.time();
			float t = total % CYCLE;

			// --- the launch
			float burn = t - IGNITION;
			float flight = Math.max(0.0F, t - LIFTOFF);
			float rise = climb(flight);
			float tilt = Mth.clamp((flight - 6.0F) / 14.0F, 0.0F, 1.0F) * 0.5F;
			float drift = rise * Mth.sin(tilt) * 0.5F;
			float missileY = LaunchPadRenderer.TABLE + rise;
			Vector3f nozzle = new Vector3f(drift, missileY - 0.2F, 0.0F);
			float thrust = burn < 0.0F ? 0.0F : Mth.clamp(burn / 1.2F, 0.0F, 1.0F) * (flight > 25.0F ? Mth.clamp(1.0F - (flight - 25.0F) / 6.0F, 0.0F, 1.0F) : 1.0F);
			float flicker = 0.88F + 0.12F * Mth.sin(total * 31.0F) * Mth.cos(total * 17.0F);

			// --- the camera: a slow drift round the pad; it pulls back and looks up after the missile
			float follow = smooth(flight / 5.0F) * (1.0F - smooth((flight - 11.0F) / 7.0F));
			float yaw = 0.62F + total * 0.012F + state.mouseX() * 0.25F;
			float dist = 42.0F + 22.0F * follow;
			float camY = 4.0F - state.mouseY() * 3.0F;
			this.cam = new Vector3f(Mth.sin(yaw) * dist, camY, Mth.cos(yaw) * dist);
			float lookY = Mth.lerp(follow, 11.0F, Math.min(missileY * 0.6F + 4.0F, 28.0F));
			Vector3f look = new Vector3f(Math.min(drift, 4.0F) * follow, lookY, 0.0F);
			// the ground shakes the camera while the motors run near it
			float shake = thrust * Mth.clamp(1.0F - flight / 10.0F, 0.0F, 1.0F) * 0.12F;
			look.add(Mth.sin(total * 47.0F) * shake, Mth.sin(total * 53.0F) * shake, 0.0F);

			Matrix4f view = new Matrix4f().lookAt(this.cam, look, new Vector3f(0.0F, 1.0F, 0.0F));
			Matrix4f proj = new Matrix4f().perspective(48.0F * Mth.DEG_TO_RAD, w / Math.max(1.0F, h), 0.3F, 2400.0F);
			out.projection(proj);
			PoseStack ps = new PoseStack();
			ps.last().pose().set(view);
			ps.last().normal().set(new org.joml.Matrix3f(view));
			PoseStack.Pose pose = ps.last();

			// --- lights
			this.lights.clear();
			for (float[] f : LaunchPadRenderer.FLOODLIGHTS) {
				this.lights.add(new Light(f[0] * 0.95F, 5.9F, f[1] * 0.95F, 1.0F, 0.9F, 0.72F, 1.15F, 24.0F, true));
			}
			this.lights.add(new Light(-30.0F, 2.0F, 34.0F, 0.75F, 0.8F, 1.0F, 0.5F, 18.0F, true));
			this.lights.add(new Light(32.0F, 2.0F, -30.0F, 0.75F, 0.8F, 1.0F, 0.5F, 18.0F, true));
			boolean counting = t > ARMS_AWAY - 6.0F && t < LIFTOFF + 8.0F;
			if (counting && Mth.sin(total * 9.0F) > 0.0F) {
				this.lights.add(new Light(LaunchPadRenderer.ARM_HINGE_X + 0.3F, 10.0F, 0.65F, 1.0F, 0.12F, 0.06F, 1.2F, 10.0F));
			}
			if (thrust > 0.0F) {
				float k = thrust * flicker;
				// the flame, and its glare thrown off the smoke round the pad
				this.lights.add(new Light(nozzle.x, nozzle.y - 2.0F, nozzle.z, 1.0F, 0.62F, 0.28F, 5.5F * k, 150.0F));
				float low = k * Mth.clamp(1.0F - flight / 9.0F, 0.0F, 1.0F);
				if (low > 0.0F) {
					this.lights.add(new Light(10.0F, 4.0F, 0.0F, 1.0F, 0.55F, 0.22F, 2.5F * low, 60.0F));
				}
			}
			this.fogDensity = 0.0032F;
			this.anyMoving = this.lights.stream().anyMatch(l -> !l.fixed());

			// --- sky, stars, moon
			VertexConsumer sky = out.get(SKY);
			skyDome(sky, pose, this.cam, thrust * Mth.clamp(1.0F - flight / 14.0F, 0.0F, 1.0F), nozzle);
			stars(sky, pose, this.cam, total, view);
			moon(sky, pose, this.cam, view);
			out.flush(SKY);

			// --- ground, hills, the complex, the missile
			VertexConsumer solid = out.get(SOLID);
			Matrix4f id = new Matrix4f();
			this.still(solid, pose, HILLS.vertices(), 0.55F, 0.6F, 0.7F);
			this.still(solid, pose, GROUND.vertices(), 0.6F, 0.66F, 0.55F);
			this.still(solid, pose, LaunchPadRenderer.COMPLEX.vertices(), 1.0F, 1.0F, 1.0F);
			// the arms swing back before the launch
			float swing = smooth((t - ARMS_AWAY) / 4.0F);
			float top = LaunchPadRenderer.TABLE + MISSILE.length;
			for (int i = 0; i < LaunchPadRenderer.ARMS.length; i++) {
				float r = LaunchPadRenderer.ARM_HEIGHTS[i] < top - 0.6F ? swing : 1.0F;
				Matrix4f m = new Matrix4f().translate(LaunchPadRenderer.ARM_HINGE_X, 0.0F, 0.0F).rotateY(r * LaunchPadRenderer.ARM_SWING);
				this.lit(solid, pose, LaunchPadRenderer.ARMS[i].vertices(), m, 1.0F, 1.0F, 1.0F);
			}
			float clamps = t < LIFTOFF - 0.2F ? 0.0F : Mth.clamp((t - LIFTOFF + 0.2F) / 0.25F, 0.0F, 1.0F);
			for (int c = 0; c < 4; c++) {
				Matrix4f m = new Matrix4f().rotateY(c * Mth.HALF_PI)
					.translate(LaunchPadRenderer.CLAMP_PIVOT, LaunchPadRenderer.TABLE + 0.32F, LaunchPadRenderer.CLAMP_PIVOT)
					.rotate(-clamps * 1.9F, new Vector3f(-1.0F, 0.0F, 1.0F).normalize());
				this.lit(solid, pose, LaunchPadRenderer.CLAMP.vertices(), m, 1.0F, 1.0F, 1.0F);
			}
			float lift = 0.3F + (LaunchPadRenderer.TOWER_TOP - 2.0F) * (0.5F - 0.5F * Mth.cos(total * 0.12F));
			this.lit(solid, pose, LaunchPadRenderer.ELEVATOR.vertices(), new Matrix4f().translate(LaunchPadRenderer.TOWER_X, lift, 0.0F), 1.0F, 1.0F, 1.0F);
			out.flush(SOLID);

			boolean flying = flight < 60.0F;
			if (flying) {
				RenderType body = MissileRenderer.bodyType(MISSILE);
				VertexConsumer mb = out.get(body);
				Matrix4f m = new Matrix4f().translate(drift, missileY, 0.0F).rotateZ(-tilt);
				this.lit(mb, pose, MissileMesh.of(MISSILE).vertices(), m, 1.0F, 1.0F, 1.0F);
				out.flush(body);
			}

			// --- lamps, beacons, searchlights
			VertexConsumer glow = out.get(StructureKit.GLOW_TYPE);
			LaunchPadRenderer.GLOW.emit(pose, glow, LightTexture.FULL_BRIGHT);
			if (Mth.sin(total * 4.0F) > 0.0F) {
				LaunchPadRenderer.BEACON.emit(pose, glow, LightTexture.FULL_BRIGHT);
			}
			if (counting && Mth.sin(total * 9.0F) > 0.0F) {
				LaunchPadRenderer.COUNT_LIGHT.emit(pose, glow, LightTexture.FULL_BRIGHT);
			} else if (!counting) {
				LaunchPadRenderer.READY_LIGHT.emit(pose, glow, LightTexture.FULL_BRIGHT);
			}
			searchlight(glow, ps, -30.0F, 34.0F, total * 0.21F, 1.05F, total);
			searchlight(glow, ps, 32.0F, -30.0F, -total * 0.17F + 2.0F, 1.1F, total);
			out.flush(StructureKit.GLOW_TYPE);

			// --- smoke and vapour, back to front
			List<Puff> puffs = new ArrayList<>();
			vent(puffs, total);
			if (burn > 0.0F) {
				launchSmoke(puffs, t, nozzle, drift);
			}
			puffs.sort((a, b) -> Float.compare(b.dist(this.cam), a.dist(this.cam)));
			Vector3f right = new Vector3f(view.m00(), view.m10(), view.m20());
			Vector3f up = new Vector3f(view.m01(), view.m11(), view.m21());
			VertexConsumer smoke = out.get(SMOKE);
			for (Puff p : puffs) {
				this.puff(smoke, pose, p, right, up);
			}
			out.flush(SMOKE);

			// --- the flame
			if (thrust > 0.0F && flying) {
				VertexConsumer plume = out.get(ExhaustPlume.TYPE);
				ps.pushPose();
				ps.translate(drift, missileY, 0.0F);
				ps.mulPose(new org.joml.Quaternionf().rotationZ(-tilt));
				float len = (6.0F + 6.0F * Mth.clamp(flight / 6.0F, 0.0F, 1.0F)) * flicker * thrust;
				for (float[] n : new float[][] {{0.212F, 0.212F}, {-0.212F, 0.212F}, {0.212F, -0.212F}, {-0.212F, -0.212F}}) {
					ps.pushPose();
					ps.translate(n[0], 0.0F, n[1]);
					ExhaustPlume.plume(ps.last(), plume, 0.21F * 1.4F, len, total * 20.0F + n[0] * 13.0F, 1.0F);
					ps.popPose();
				}
				ps.popPose();
				out.flush(ExhaustPlume.TYPE);
				// a hot glare round the nozzles that stays a bright point far up
				VertexConsumer g = out.get(SKY);
				float d = nozzle.distance(this.cam);
				float r = Math.max(2.5F, d * 0.03F) * flicker;
				billboard(g, pose, nozzle.x, nozzle.y - 1.0F, nozzle.z, right, up, r, (int) (thrust * 230.0F) << 24 | 0xFFC070);
				billboard(g, pose, nozzle.x, nozzle.y - 1.5F, nozzle.z, right, up, r * 3.2F, (int) (thrust * 90.0F) << 24 | 0xFF8A30);
				out.flush(SKY);
			}
		}

		// ------------------------------------------------------------------ lighting

		/** For the meshes that never move: the light of the fixed lamps, moon and sky, worked out once per vertex. */
		private final java.util.Map<float[], float[]> stillLight = new java.util.IdentityHashMap<>();

		/** A fixed mesh: its cached light, plus what changes (the flame, the countdown lights) and the haze. */
		private void still(VertexConsumer c, PoseStack.Pose view, float[] d, float r, float g, float b) {
			float[] base = this.stillLight.computeIfAbsent(d, k -> {
				float[] out = new float[d.length / BoxMesh.STRIDE * 3];
				float[] rgb = new float[3];
				Vector3f p = new Vector3f();
				Vector3f n = new Vector3f();
				for (int i = 0, j = 0; i < d.length; i += BoxMesh.STRIDE, j += 3) {
					p.set(d[i], d[i + 1], d[i + 2]);
					n.set(d[i + 5], d[i + 6], d[i + 7]);
					if (n.lengthSquared() > 1.0E-6F) {
						n.normalize();
					}
					this.gather(p, n, rgb, true);
					out[j] = rgb[0];
					out[j + 1] = rgb[1];
					out[j + 2] = rgb[2];
				}
				return out;
			});
			float[] rgb = new float[3];
			Vector3f p = new Vector3f();
			Vector3f n = new Vector3f();
			for (int i = 0, j = 0; i < d.length; i += BoxMesh.STRIDE, j += 3) {
				p.set(d[i], d[i + 1], d[i + 2]);
				rgb[0] = base[j];
				rgb[1] = base[j + 1];
				rgb[2] = base[j + 2];
				if (this.anyMoving) {
					n.set(d[i + 5], d[i + 6], d[i + 7]);
					if (n.lengthSquared() > 1.0E-6F) {
						n.normalize();
					}
					this.addLights(p, n, rgb, false);
				}
				float fog = (float) Math.exp(-p.distance(this.cam) * this.fogDensity);
				c.addVertex(view, p.x, p.y, p.z)
					.setColor(Mth.clamp(knee(rgb[0]) * fog * r, 0.0F, 1.0F), Mth.clamp(knee(rgb[1]) * fog * g, 0.0F, 1.0F),
						Mth.clamp(knee(rgb[2]) * fog * b, 0.0F, 1.0F), 1.0F)
					.setUv(d[i + 3], d[i + 4])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(LightTexture.FULL_BRIGHT)
					.setNormal(NX, NY, NZ);
			}
		}

		private boolean anyMoving;

		/** A mesh lit per vertex: placed by {@code model}, tinted by {@code r g b}, drawn through {@code view}. */
		private void lit(VertexConsumer c, PoseStack.Pose view, float[] d, Matrix4f model, float r, float g, float b) {
			Vector3f p = new Vector3f();
			Vector3f n = new Vector3f();
			float[] rgb = new float[3];
			for (int i = 0; i < d.length; i += BoxMesh.STRIDE) {
				model.transformPosition(d[i], d[i + 1], d[i + 2], p);
				model.transformDirection(d[i + 5], d[i + 6], d[i + 7], n);
				if (n.lengthSquared() > 1.0E-6F) {
					n.normalize();
				}
				this.shade(p, n, rgb);
				c.addVertex(view, p.x, p.y, p.z)
					.setColor(Mth.clamp(rgb[0] * r, 0.0F, 1.0F), Mth.clamp(rgb[1] * g, 0.0F, 1.0F), Mth.clamp(rgb[2] * b, 0.0F, 1.0F), 1.0F)
					.setUv(d[i + 3], d[i + 4])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(LightTexture.FULL_BRIGHT)
					.setNormal(NX, NY, NZ);
			}
		}

		/** Moonlight, the night sky, the lamps and the flame on a surface; the air swallows it with distance. */
		private void shade(Vector3f p, Vector3f n, float[] out) {
			this.gather(p, n, out, true);
			this.addLights(p, n, out, false);
			float fog = (float) Math.exp(-p.distance(this.cam) * this.fogDensity);
			// a soft knee instead of a hard clip, so strong light rolls off gently
			out[0] = knee(out[0]) * fog;
			out[1] = knee(out[1]) * fog;
			out[2] = knee(out[2]) * fog;
		}

		/** The sky, the moon and (with {@code fixed}) the lamps that never change. */
		private void gather(Vector3f p, Vector3f n, float[] out, boolean fixed) {
			float sky = 0.55F + 0.45F * n.y;
			float moon = Math.max(0.0F, n.x * -0.42F + n.y * 0.62F + n.z * -0.66F);
			out[0] = 0.05F * sky + 0.17F * moon;
			out[1] = 0.065F * sky + 0.21F * moon;
			out[2] = 0.11F * sky + 0.31F * moon;
			this.addLights(p, n, out, fixed);
		}

		/** Adds the lamps that are fixed (or, with {@code fixed} false, the ones that come and go). */
		private void addLights(Vector3f p, Vector3f n, float[] out, boolean fixed) {
			for (Light l : this.lights) {
				if (l.fixed() != fixed) {
					continue;
				}
				float dx = l.x() - p.x;
				float dy = l.y() - p.y;
				float dz = l.z() - p.z;
				float d2 = dx * dx + dy * dy + dz * dz;
				if (d2 >= l.radius() * l.radius()) {
					continue;
				}
				float d = Mth.sqrt(d2) + 1.0E-4F;
				float att = 1.0F - d / l.radius();
				att *= att;
				float lambert = Math.max(0.0F, (dx * n.x + dy * n.y + dz * n.z) / d) * 0.8F + 0.2F;
				float k = att * lambert * l.strength();
				out[0] += l.r() * k;
				out[1] += l.g() * k;
				out[2] += l.b() * k;
			}
		}

		// ------------------------------------------------------------------ smoke

		private void puff(VertexConsumer c, PoseStack.Pose view, Puff p, Vector3f right, Vector3f up) {
			float[] rgb = new float[3];
			Vector3f n = new Vector3f(this.cam).sub(p.x, p.y, p.z).normalize();
			n.y = Math.max(n.y, 0.2F);
			this.shade(new Vector3f(p.x, p.y, p.z), n.normalize(), rgb);
			float a = Mth.clamp(p.alpha, 0.0F, 1.0F);
			float rr = Mth.clamp(rgb[0] * p.grey + p.hot, 0.0F, 1.0F);
			float gg = Mth.clamp(rgb[1] * p.grey + p.hot * 0.55F, 0.0F, 1.0F);
			float bb = Mth.clamp(rgb[2] * p.grey + p.hot * 0.2F, 0.0F, 1.0F);
			float s = p.size;
			float ca = Mth.cos(p.spin);
			float sa = Mth.sin(p.spin);
			Vector3f ax = new Vector3f(right).mul(ca * s).add(new Vector3f(up).mul(sa * s));
			Vector3f ay = new Vector3f(up).mul(ca * s).sub(new Vector3f(right).mul(sa * s));
			float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
			for (float[] k : corners) {
				c.addVertex(view, p.x + ax.x * k[0] + ay.x * k[1], p.y + ax.y * k[0] + ay.y * k[1], p.z + ax.z * k[0] + ay.z * k[1])
					.setColor(rr, gg, bb, a)
					.setUv(k[2], k[3])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(LightTexture.FULL_BRIGHT)
					.setNormal(NX, NY, NZ);
			}
		}
	}

	/** Where the scene goes: the GUI's buffers, or (for a preview) anything else. */
	public interface Out {
		void projection(Matrix4f proj);

		VertexConsumer get(RenderType type);

		void flush(RenderType type);
	}

	private record Light(float x, float y, float z, float r, float g, float b, float strength, float radius, boolean fixed) {
		Light(float x, float y, float z, float r, float g, float b, float strength, float radius) {
			this(x, y, z, r, g, b, strength, radius, false);
		}
	}

	private static final class Puff {
		float x;
		float y;
		float z;
		float size;
		float alpha;
		float grey;
		float hot;
		float spin;

		float dist(Vector3f c) {
			float dx = this.x - c.x;
			float dy = this.y - c.y;
			float dz = this.z - c.z;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	/** The liquid-oxygen tank boiling off: a thin white plume out of its vent, falling and spreading (cold, heavy). */
	private static void vent(List<Puff> out, float total) {
		float vx = LaunchPadRenderer.LOX_SPHERE[0];
		float vz = LaunchPadRenderer.LOX_SPHERE[1];
		for (int i = 0; i < 26; i++) {
			float life = 6.0F;
			float age = (total + i * life / 26.0F) % life;
			float u = age / life;
			Puff p = new Puff();
			float seed = (float) Math.floor((total + i * life / 26.0F) / life) * 7.31F + i * 3.7F;
			p.x = vx + u * 3.5F + Mth.sin(seed) * u * 1.4F;
			p.y = 7.4F + u * 1.6F - u * u * 2.4F;
			p.z = vz + u * 1.2F + Mth.cos(seed * 1.3F) * u * 1.4F;
			p.size = 0.35F + u * 2.2F;
			p.alpha = 0.22F * Mth.sin(Mth.PI * Math.min(1.0F, u * 1.15F));
			p.grey = 1.3F;
			p.spin = seed + u * 0.8F;
			out.add(p);
		}
	}

	/**
	 * The launch cloud: smoke rolling out of the flame trench (to +X) and round the table, and the column the
	 * missile leaves behind it - all of it lit orange by the flame while it burns close by.
	 */
	private static void launchSmoke(List<Puff> out, float t, Vector3f nozzle, float drift) {
		float burn = t - IGNITION;
		// the ground cloud: puffs born through the first seconds, rolling outwards and up
		int ground = 150;
		for (int i = 0; i < ground; i++) {
			float born = i * 0.07F;
			float age = burn - born;
			if (age <= 0.0F) {
				continue;
			}
			float h1 = hash(i, 1);
			float h2 = hash(i, 2);
			float h3 = hash(i, 3);
			Puff p = new Puff();
			boolean trench = h1 < 0.65F;
			float speed = 6.0F + 7.0F * h2;
			float reach = speed * (1.0F - (float) Math.exp(-age * 0.6F)) / 0.6F * 0.35F;
			if (trench) {
				// out of the trench's mouth and fanning wide
				float ang = (h3 - 0.5F) * 1.6F;
				p.x = 9.0F + Mth.cos(ang) * reach;
				p.z = Mth.sin(ang) * reach;
			} else {
				float ang = h3 * Mth.TWO_PI;
				p.x = Mth.cos(ang) * (2.0F + reach * 0.7F);
				p.z = Mth.sin(ang) * (2.0F + reach * 0.7F);
			}
			p.y = 1.0F + age * (0.25F + h2 * 0.4F) + reach * 0.1F;
			p.size = 2.0F + age * (0.55F + 0.35F * h1) + reach * 0.14F;
			p.alpha = Mth.clamp(age * 2.5F, 0.0F, 1.0F) * Mth.clamp(1.0F - (age - 7.0F) / 9.0F, 0.0F, 1.0F) * 0.6F;
			p.grey = 0.75F + h2 * 0.3F;
			float near = Mth.clamp(1.0F - (t - LIFTOFF) / 8.0F, 0.0F, 1.0F);
			p.hot = near * 0.22F * Mth.clamp(1.0F - age / 4.0F, 0.0F, 1.0F) * (0.6F + 0.4F * h3);
			p.spin = h1 * 6.28F + age * (h2 - 0.5F) * 0.3F;
			if (p.alpha > 0.01F) {
				out.add(p);
			}
		}
		// the trail: a puff every two metres of the climb (born when the missile passed there), spreading slowly
		float flight = Math.max(0.0F, t - LIFTOFF);
		float now = climb(flight);
		for (int i = 0; i < 240; i++) {
			float alt = 1.5F + i * 2.0F;
			if (alt > now - 2.0F) {
				break;
			}
			float born = bornAt(alt, flight);
			float age = flight - born;
			float tilt = Mth.clamp((born - 6.0F) / 14.0F, 0.0F, 1.0F) * 0.5F;
			Puff p = new Puff();
			float h = hash(i, 9);
			p.x = alt * Mth.sin(tilt) * 0.5F + (h - 0.5F) * age * 0.5F + age * 0.3F; // and the wind takes it east
			p.y = LaunchPadRenderer.TABLE + alt;
			p.z = (hash(i, 10) - 0.5F) * age * 0.5F;
			p.size = 1.3F + age * 0.7F + alt * 0.008F;
			p.alpha = 0.55F * Mth.clamp(1.0F - age / 28.0F, 0.0F, 1.0F) * Mth.clamp(age * 3.0F, 0.0F, 1.0F);
			p.grey = 0.9F;
			p.hot = Math.max(0.0F, 0.35F - age * 0.4F);
			p.spin = h * 6.28F;
			if (p.alpha > 0.01F) {
				out.add(p);
			}
		}
	}

	/** How high the missile has climbed after {@code flight} seconds. */
	private static float climb(float flight) {
		return 1.6F * flight * flight + 0.1F * flight * flight * flight;
	}

	/** When (seconds into the flight) the missile passed {@code alt}. */
	private static float bornAt(float alt, float max) {
		float lo = 0.0F;
		float hi = max;
		for (int k = 0; k < 18; k++) {
			float m = (lo + hi) * 0.5F;
			if (climb(m) < alt) {
				lo = m;
			} else {
				hi = m;
			}
		}
		return lo;
	}

	// ------------------------------------------------------------------ the sky

	/** The night sky as a dome: deep blue overhead, a lighter band at the horizon, the flame's glow low down. */
	private static void skyDome(VertexConsumer c, PoseStack.Pose view, Vector3f cam, float thrust, Vector3f flame) {
		int seg = 32;
		float[] el = {-0.2F, 0.0F, 0.06F, 0.16F, 0.32F, 0.55F, 0.8F, 1.0F};
		float rad = 1500.0F;
		for (int ri = 0; ri < el.length - 1; ri++) {
			for (int s = 0; s < seg; s++) {
				float a0 = Mth.TWO_PI * s / seg;
				float a1 = Mth.TWO_PI * (s + 1) / seg;
				domeVertex(c, view, cam, a0, el[ri], rad, thrust);
				domeVertex(c, view, cam, a1, el[ri], rad, thrust);
				domeVertex(c, view, cam, a1, el[ri + 1], rad, thrust);
				domeVertex(c, view, cam, a0, el[ri + 1], rad, thrust);
			}
		}
	}

	private static void domeVertex(VertexConsumer c, PoseStack.Pose view, Vector3f cam, float a, float e, float rad, float thrust) {
		float elev = e * Mth.HALF_PI;
		float x = Mth.cos(elev) * Mth.sin(a);
		float y = Mth.sin(elev);
		float z = Mth.cos(elev) * Mth.cos(a);
		float h = Mth.clamp(1.0F - Math.max(0.0F, e) * 3.0F, 0.0F, 1.0F);
		h = h * h;
		// a faint warm glow of towns low in one direction, the moon's side a little lighter
		float town = Math.max(0.0F, Mth.cos(a - 2.4F)) * h;
		float r = 0.010F + 0.045F * h + 0.05F * town;
		float g = 0.014F + 0.055F * h + 0.03F * town;
		float b = 0.034F + 0.085F * h + 0.012F * town;
		float glow = thrust * h * 0.25F;
		r += glow;
		g += glow * 0.55F;
		b += glow * 0.25F;
		c.addVertex(view, cam.x + x * rad, cam.y + y * rad, cam.z + z * rad)
			.setColor(Mth.clamp(r, 0.0F, 1.0F), Mth.clamp(g, 0.0F, 1.0F), Mth.clamp(b, 0.0F, 1.0F), 1.0F)
			.setUv(0.5F, 0.5F)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightTexture.FULL_BRIGHT)
			.setNormal(NX, NY, NZ);
	}

	private static void stars(VertexConsumer c, PoseStack.Pose view, Vector3f cam, float total, Matrix4f m) {
		Vector3f right = new Vector3f(m.m00(), m.m10(), m.m20());
		Vector3f up = new Vector3f(m.m01(), m.m11(), m.m21());
		for (int i = 0; i < STARS.length; i++) {
			float[] s = STARS[i];
			float tw = 0.75F + 0.25F * Mth.sin(total * (1.5F + s[4] * 3.0F) + i);
			float rad = 1400.0F;
			float x = cam.x + s[0] * rad;
			float y = cam.y + s[1] * rad;
			float z = cam.z + s[2] * rad;
			int a = (int) (Mth.clamp(s[4] * tw, 0.0F, 1.0F) * 255.0F);
			int col = a << 24 | (s[4] > 0.8F ? 0xDDE6FF : s[4] > 0.5F ? 0xFFF4E0 : 0xFFFFFF);
			billboard(c, view, x, y, z, right, up, s[3], col);
		}
	}

	private static void moon(VertexConsumer c, PoseStack.Pose view, Vector3f cam, Matrix4f m) {
		Vector3f dir = new Vector3f(0.42F, 0.62F, 0.66F).normalize();
		Vector3f right = new Vector3f(m.m00(), m.m10(), m.m20());
		Vector3f up = new Vector3f(m.m01(), m.m11(), m.m21());
		float d = 1350.0F;
		float x = cam.x + dir.x * d;
		float y = cam.y + dir.y * d;
		float z = cam.z + dir.z * d;
		billboard(c, view, x, y, z, right, up, 160.0F, 0x40A8B8D8);
		billboard(c, view, x, y, z, right, up, 46.0F, 0x90D0DCF0);
		billboard(c, view, x, y, z, right, up, 20.0F, 0xFFF2F4FF);
	}

	private static void billboard(VertexConsumer c, PoseStack.Pose view, float x, float y, float z, Vector3f right, Vector3f up, float s, int argb) {
		float[][] k = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] q : k) {
			c.addVertex(view, x + (right.x * q[0] + up.x * q[1]) * s, y + (right.y * q[0] + up.y * q[1]) * s, z + (right.z * q[0] + up.z * q[1]) * s)
				.setColor(argb).setUv(q[2], q[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(NX, NY, NZ);
		}
	}

	/** A searchlight on the ground sweeping the sky: a long cone of light in the haze, fading with distance. */
	private static void searchlight(VertexConsumer c, PoseStack ps, float x, float z, float yaw, float pitch, float total) {
		ps.pushPose();
		ps.translate(x, 1.5F, z);
		ps.mulPose(new org.joml.Quaternionf().rotationY(yaw));
		ps.mulPose(new org.joml.Quaternionf().rotationX(-pitch + 0.08F * Mth.sin(total * 0.3F + x)));
		for (int i = 0; i < SEARCH.length; i++) {
			int a = (int) (26.0F * (1.0F - i / (float) SEARCH.length));
			SEARCH[i].emit(ps.last(), c, LightTexture.FULL_BRIGHT, a << 24 | 0xC8D8FF);
		}
		ps.popPose();
	}

	private static final BoxMesh[] SEARCH = searchBeam();

	private static BoxMesh[] searchBeam() {
		BoxMesh[] out = new BoxMesh[8];
		float len = 300.0F;
		for (int i = 0; i < out.length; i++) {
			float z0 = len * i / out.length;
			float z1 = len * (i + 1) / out.length;
			float r0 = 0.5F + z0 * 0.022F;
			float r1 = 0.5F + z1 * 0.022F;
			out[i] = new BoxMesh.Builder().hexa(StructureKit.BEAM_HAZE,
				new Vector3f(-r0, -r0, z0), new Vector3f(r0, -r0, z0), new Vector3f(r0, r0, z0), new Vector3f(-r0, r0, z0),
				new Vector3f(-r1, -r1, z1), new Vector3f(r1, -r1, z1), new Vector3f(r1, r1, z1), new Vector3f(-r1, r1, z1)
			).build();
		}
		return out;
	}

	// ------------------------------------------------------------------ static geometry

	/** The ground round the pad: tiles out to the hills, a little uneven. */
	private static BoxMesh buildGround() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		int n = 30;
		float tile = 16.0F;
		for (int i = -n; i < n; i++) {
			for (int j = -n; j < n; j++) {
				float x0 = i * tile;
				float z0 = j * tile;
				float y = -0.05F - hash(i * 131 + j, 4) * 0.04F;
				b.box(x0, y - 0.2F, z0, x0 + tile, y, z0 + tile, StructureKit.OLIVE_DARK);
			}
		}
		// the concrete apron round the table
		b.box(-14.0F, -0.06F, -14.0F, 14.0F, -0.01F, 14.0F, StructureKit.CONCRETE_DARK);
		return b.build();
	}

	/** A ring of mountains on the horizon, jagged ridges in two rows. */
	private static BoxMesh buildHills() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int row = 0; row < 2; row++) {
			float rad = row == 0 ? 520.0F : 760.0F;
			int seg = 96;
			for (int s = 0; s < seg; s++) {
				float a0 = Mth.TWO_PI * s / seg;
				float a1 = Mth.TWO_PI * (s + 1) / seg;
				float h0 = ridge(s, row);
				float h1 = ridge(s + 1, row);
				Vector3f p0 = new Vector3f(Mth.sin(a0) * rad, -2.0F, Mth.cos(a0) * rad);
				Vector3f p1 = new Vector3f(Mth.sin(a1) * rad, -2.0F, Mth.cos(a1) * rad);
				Vector3f q0 = new Vector3f(Mth.sin(a0) * rad, h0, Mth.cos(a0) * rad);
				Vector3f q1 = new Vector3f(Mth.sin(a1) * rad, h1, Mth.cos(a1) * rad);
				Vector3f back0 = new Vector3f(p0).mul(1.04F);
				Vector3f back1 = new Vector3f(p1).mul(1.04F);
				back0.y = h0 * 0.6F;
				back1.y = h1 * 0.6F;
				b.hexa(StructureKit.MOON_ROCK, p0, p1, q1, q0, back0, back1, new Vector3f(back1.x, h1 * 0.95F, back1.z), new Vector3f(back0.x, h0 * 0.95F, back0.z));
			}
		}
		return b.build();
	}

	private static float ridge(int s, int row) {
		int seg = 96;
		s = Math.floorMod(s, seg);
		float a = Mth.TWO_PI * s / seg;
		float base = row == 0 ? 26.0F : 60.0F;
		return base + 22.0F * Mth.sin(a * 3.0F + row) + 14.0F * Mth.sin(a * 7.0F + 1.3F * row) + 9.0F * hash(s, 30 + row);
	}

	private static float[][] buildStars() {
		float[][] s = new float[700][];
		for (int i = 0; i < s.length; i++) {
			float u = hash(i, 50);
			float v = hash(i, 51);
			float y = 0.05F + v * 0.95F;
			float r = Mth.sqrt(1.0F - y * y);
			float a = u * Mth.TWO_PI;
			float bright = hash(i, 52);
			bright = bright * bright * bright;
			s[i] = new float[] {Mth.sin(a) * r, y, Mth.cos(a) * r, 1.0F + bright * 3.2F, 0.25F + bright * 0.75F};
		}
		return s;
	}

	static float hash(int a, int b) {
		long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 40) / (float) (1 << 24);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float knee(float x) {
		return x < 0.8F ? x : 0.8F + 0.2F * (1.0F - (float) Math.exp(-(x - 0.8F) / 0.2F));
	}
}
