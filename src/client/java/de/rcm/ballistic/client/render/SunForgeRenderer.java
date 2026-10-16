package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BEAM;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.HOT;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.SHAFT;
import static de.rcm.ballistic.client.render.StructureKit.SHELTER;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.TUNGSTEN;
import static de.rcm.ballistic.client.render.StructureKit.ABLATIVE;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.sun.SunLayout.BARREL_R;
import static de.rcm.ballistic.client.sun.SunLayout.CRANE_H;
import static de.rcm.ballistic.client.sun.SunLayout.CRANE_X;
import static de.rcm.ballistic.client.sun.SunLayout.CRANE_Z;
import static de.rcm.ballistic.client.sun.SunLayout.JIB;
import static de.rcm.ballistic.client.sun.SunLayout.LEG;
import static de.rcm.ballistic.client.sun.SunLayout.PLINTH;
import static de.rcm.ballistic.client.sun.SunLayout.PLINTH_HALF;
import static de.rcm.ballistic.client.sun.SunLayout.SHAFT_HALF;
import static de.rcm.ballistic.client.sun.SunLayout.TOWERS;
import static de.rcm.ballistic.client.sun.SunLayout.TOWER_H;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.sun.SunLayout;
import de.rcm.ballistic.sun.SunForgeBlock;
import de.rcm.ballistic.sun.SunForgeBlockEntity;
import de.rcm.ballistic.sun.SunTimeline;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The Helios launch complex: a coil accelerator fifty metres tall - its barrel ringed by fourteen great copper coils
 * in steel casings, held in a four-legged lattice, a flared muzzle crown with blast deflectors on top - standing on
 * a concrete plinth with the loading shaft under armoured sliding doors; beside it a tower crane, two rows of
 * capacitor banks on insulator stacks with their feeders running in under the plinth, two hyperbolic cooling
 * towers and the half-buried control bunker.
 * <p>
 * Ordered to fire it comes alive by {@link SunTimeline}: the beacons start turning, the crane swings its jib away,
 * the doors grind apart, the round rises on its cradle into the breech, the coils light one after another from the
 * bottom up - blue-white, humming, arcs cracking between them - and on the shot the light runs up the barrel in a
 * blink and the muzzle flashes. Then the cradle sinks, the doors close and the crane swings back.
 */
public class SunForgeRenderer implements BlockEntityRenderer<SunForgeBlockEntity, SunForgeRenderer.State> {
	private static final BoxMesh SITE = buildSite();
	private static final BoxMesh SITE_GLOW = buildSiteGlow();
	private static final BoxMesh PICK = new BoxMesh.Builder()
		.box(-PLINTH_HALF, 0.0F, -PLINTH_HALF, PLINTH_HALF, PLINTH, PLINTH_HALF, CONCRETE)
		.box(-LEG - 0.5F, PLINTH, -LEG - 0.5F, LEG + 0.5F, SunTimeline.MUZZLE, LEG + 0.5F, STEEL)
		.build();
	private static final BoxMesh DOOR_MESH = buildDoor();
	private static final BoxMesh CRANE_TOP = buildCraneTop();
	private static final BoxMesh CRANE_GLOW = new BoxMesh.Builder()
		.box(-0.25F, 2.6F, -0.25F, 0.25F, 3.0F, 0.25F, RED_LAMP)
		.box(-JIB - 0.2F, 0.6F, -0.2F, -JIB + 0.2F, 0.95F, 0.2F, RED_LAMP)
		.build();
	private static final BoxMesh CRADLE = buildCradle();
	private static final BoxMesh ROUND = buildRound();
	private static final BoxMesh ROUND_RINGS = buildRoundRings();
	private static final BoxMesh ROUND_GLOW = buildRoundGlow();
	private static final BoxMesh[] RING_GLOW = buildRingGlow();
	private static final BoxMesh MUZZLE_GLOW = new BoxMesh.Builder()
		.revolve(HOT, new Vector3f(0.0F, SunTimeline.MUZZLE - 1.2F, 0.0F), new Vector3f(0, 1, 0), new float[][] {{0.0F, BARREL_R * 0.9F}, {1.6F, 2.6F},
			{3.0F, 3.4F}, {4.2F, 0.0F}}, 20)
		.build();
	private static final BoxMesh BORE_GLOW = new BoxMesh.Builder()
		.revolve(BEAM, new Vector3f(0.0F, PLINTH + 0.6F, 0.0F), new Vector3f(0, 1, 0), new float[][] {{0.0F, BARREL_R * 0.72F},
			{SunTimeline.MUZZLE - PLINTH - 0.4F, BARREL_R * 0.72F}}, 16)
		.build();
	private static final BoxMesh BEACON_A = new BoxMesh.Builder()
		.box(-PLINTH_HALF - 0.3F, PLINTH, -PLINTH_HALF - 0.3F, -PLINTH_HALF + 0.3F, PLINTH + 0.6F, -PLINTH_HALF + 0.3F, LAMP)
		.box(PLINTH_HALF - 0.3F, PLINTH, PLINTH_HALF - 0.3F, PLINTH_HALF + 0.3F, PLINTH + 0.6F, PLINTH_HALF + 0.3F, LAMP)
		.build();
	private static final BoxMesh BEACON_B = new BoxMesh.Builder()
		.box(PLINTH_HALF - 0.3F, PLINTH, -PLINTH_HALF - 0.3F, PLINTH_HALF + 0.3F, PLINTH + 0.6F, -PLINTH_HALF + 0.3F, LAMP)
		.box(-PLINTH_HALF - 0.3F, PLINTH, PLINTH_HALF - 0.3F, -PLINTH_HALF + 0.3F, PLINTH + 0.6F, PLINTH_HALF + 0.3F, LAMP)
		.build();
	private static final BoxMesh AVIATION = buildAviation();
	private static final BoxMesh READY = new BoxMesh.Builder().box(9.6F, 2.2F, -10.05F, 10.0F, 2.6F, -9.95F, GREEN_LAMP).build();

	public SunForgeRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facing;
		/** Ticks since the launch order, -1 when none runs. */
		public float t;
		public boolean loaded;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SunForgeBlockEntity forge, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(forge, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(SunForgeBlock.FACING) ? state.blockState.getValue(SunForgeBlock.FACING) : Direction.NORTH;
		state.facing = SunLayout.angle(facing);
		state.t = forge.launchAge(partialTick);
		state.loaded = forge.loaded();
		state.time = forge.clientAge + partialTick;
	}

	private static float ease(float t, float a, float b) {
		return SunTimeline.smooth((t - a) / (b - a));
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float t = state.t;
		boolean busy = t >= 0.0F;
		float fire = SunTimeline.FIRE;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(new Quaternionf().rotationY(state.facing));
		ModelPick.part(state, poseStack, camera, PICK);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SITE.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> SITE_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		StructureKit.light(state, camera, poseStack, 0, SITE_GLOW, 0xFFF0D8, 0.6F, 22.0F); // the floodlights and the bunker's windows

		// ---- the shaft doors: they grind apart before the lift comes up, and close once it is down again
		float open = busy ? ease(t, 45.0F, 92.0F) - ease(t, fire + 110.0F, fire + 170.0F) : 0.0F;
		for (int side : new int[] {-1, 1}) {
			poseStack.pushPose();
			poseStack.translate(side * (SHAFT_HALF + 0.05F) * 1.05F * open, 0.0F, 0.0F);
			if (side < 0) {
				poseStack.mulPose(new Quaternionf().rotationY(Mth.PI));
			}
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DOOR_MESH.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// ---- the lift: the round comes up into the breech; the empty cradle goes back down after the shot
		float lift = busy ? ease(t, SunTimeline.LIFT_FROM, SunTimeline.LIFT_TO) - ease(t, fire + 30.0F, fire + 100.0F) : 0.0F;
		float cradleY = 0.4F + lift * (PLINTH + 0.6F);
		poseStack.pushPose();
		poseStack.translate(0.0F, cradleY, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CRADLE.emit(pose, consumer, light));
		boolean roundThere = state.loaded && (!busy || t < fire);
		if (roundThere) {
			poseStack.translate(0.0F, 0.35F + 3.0F, 0.0F);
			float spin = busy ? Math.max(0.0F, t - SunTimeline.CHARGE_FROM) * Math.max(0.0F, t - SunTimeline.CHARGE_FROM) * 0.0002F : 0.0F;
			drawRoundParts(poseStack, collector, light, spin, busy ? Mth.clamp((t - SunTimeline.CHARGE_FROM) / 300.0F, 0.15F, 1.0F) : 0.15F);
		}
		poseStack.popPose();

		// ---- the coils: lighting one after another from the bottom up while they charge, a blink up the barrel on the shot
		if (busy && t >= SunTimeline.CHARGE_FROM && t < fire + 60.0F) {
			float charge = Mth.clamp((t - SunTimeline.CHARGE_FROM) / (SunTimeline.CHARGE_TO - SunTimeline.CHARGE_FROM), 0.0F, 1.0F);
			for (int i = 0; i < SunTimeline.RINGS; i++) {
				float g;
				if (t < fire) {
					g = Mth.clamp(charge * SunTimeline.RINGS - i, 0.0F, 1.0F);
					float hum = 0.82F + 0.18F * Mth.sin(state.time * (1.7F + i * 0.13F) + i);
					g *= hum * (t > SunTimeline.CHARGE_TO ? 0.9F + 0.1F * Mth.sin(t * 2.3F) : 1.0F);
				} else {
					// the shot: each coil fires as the round passes it, white, then the glow dies away
					float pass = fire + i * 0.12F;
					g = t < pass ? 1.0F : 1.0F + 1.5F * (float) Math.exp(-(t - pass) * 1.5F);
					g *= (float) Math.exp(-(t - fire) / 18.0F);
				}
				if (g > 0.01F && i % 2 == 0) {
					// the coils' blue-white glow on the tower, the lattice and the ground
					float y = SunTimeline.RING_BASE + (i + 0.5F) * SunTimeline.RING_STEP;
					for (int side = -1; side <= 1; side += 2) {
						poseStack.pushPose();
						poseStack.translate(0.0F, y, side * 4.8F);
						StructureKit.light(state, camera, poseStack, 1 + i + (side > 0 ? 1 : 0), StructureKit.POINT, g > 1.0F ? 0xFFFFFF : 0x9CC4FF,
							Mth.clamp(g, 0.0F, 1.0F), 9.0F + 4.0F * Mth.clamp(g - 1.0F, 0.0F, 1.0F));
						poseStack.popPose();
					}
				}
				if (g > 0.01F) {
					int argb = Math.round(Mth.clamp(g, 0.0F, 1.0F) * 255.0F) << 24 | (g > 1.0F ? 0xFFFFFF : 0xC8E4FF);
					BoxMesh ring = RING_GLOW[i];
					collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> ring.emit(pose, consumer, LightTexture.FULL_BRIGHT, argb));
				}
			}
			// the bore glowing through the slots between the coils, the arcs cracking between them
			float bore = t < fire ? charge * 0.45F : 1.0F * (float) Math.exp(-(t - fire) / 6.0F);
			if (bore > 0.02F) {
				int argb = Math.round(Mth.clamp(bore, 0.0F, 1.0F) * 255.0F) << 24 | 0xE0F0FF;
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BORE_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT, argb));
			}
			if (t < fire) {
				BoxMesh arcs = arcs(state.time, charge);
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> arcs.emit(pose, consumer, LightTexture.FULL_BRIGHT, 0xFFE8F4FF));
			}
		}
		// ---- the muzzle flash
		if (busy && t >= fire - 0.5F && t < fire + 14.0F) {
			float k = t < fire ? 0.0F : (float) Math.exp(-(t - fire) / 3.5F);
			float s = 0.7F + (t - fire) * 0.12F;
			poseStack.pushPose();
			poseStack.translate(0.0F, SunTimeline.MUZZLE - 1.2F, 0.0F);
			poseStack.scale(s, 0.6F + (t - fire) * 0.35F, s);
			poseStack.translate(0.0F, -(SunTimeline.MUZZLE - 1.2F), 0.0F);
			int argb = Math.round(k * 255.0F) << 24 | 0xFFFFFF;
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> MUZZLE_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT, argb));
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(0.0F, SunTimeline.MUZZLE + 2.0F, 0.0F);
			StructureKit.light(state, camera, poseStack, 20, StructureKit.POINT, 0xDCEBFF, k, 60.0F);
			poseStack.popPose();
		}

		// ---- the crane: its jib over the tower for servicing, swung clear for the shot
		float slew = busy ? ease(t, SunTimeline.CRANE_FROM, SunTimeline.CRANE_TO) - ease(t, fire + 140.0F, fire + 230.0F) : 0.0F;
		poseStack.pushPose();
		poseStack.translate(CRANE_X, CRANE_H, CRANE_Z);
		poseStack.mulPose(new Quaternionf().rotationY(-slew * 1.9F));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CRANE_TOP.emit(pose, consumer, light));
		if ((state.time % 40.0F) < 20.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> CRANE_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 21, CRANE_GLOW, 0xFF2A18, 0.5F, 9.0F);
		}
		poseStack.popPose();

		// ---- lamps: the aviation lights, the turning beacons while it counts down, the ready lamp
		if ((state.time % 40.0F) < 20.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> AVIATION.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 23, AVIATION, 0xFF2A18, 0.5F, 9.0F);
		}
		if (busy && t < fire + 60.0F) {
			boolean a = (state.time % 16.0F) < 8.0F;
			BoxMesh beacon = a ? BEACON_A : BEACON_B;
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> beacon.emit(pose, consumer, LightTexture.FULL_BRIGHT, 0xFFFFA020));
			StructureKit.light(state, camera, poseStack, 24, beacon, 0xFFA020, 0.8F, 12.0F);
		}
		if (state.loaded && !busy) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 25, READY, 0x40FF60, 0.3F, 5.0F);
		}
		poseStack.popPose();
	}

	private static void drawRoundParts(PoseStack poseStack, SubmitNodeCollector collector, int light, float spin, float glow) {
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ROUND.emit(pose, consumer, light));
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(spin));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ROUND_RINGS.emit(pose, consumer, light));
		poseStack.popPose();
		int argb = Math.round(Mth.clamp(glow, 0.0F, 1.0F) * 255.0F) << 24 | 0xFFFFFF;
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> ROUND_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT, argb));
	}

	/** The round in flight at {@code at}, nose along {@code dir}: its containment rings spinning, its core glowing through the slots. */
	public static void drawRound(WorldRenderContext context, Vec3 at, Vec3 dir, float t) {
		if (!(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		PoseStack ps = new PoseStack();
		ps.last().pose().set(context.matrices().last().pose());
		ps.last().normal().set(context.matrices().last().normal());
		ps.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) dir.x, (float) dir.y, (float) dir.z)));
		int light = LightTexture.FULL_BRIGHT;
		ROUND.emit(ps.last(), buffers.getBuffer(StructureKit.TYPE), light);
		ps.pushPose();
		ps.mulPose(new Quaternionf().rotationY(t * 0.9F));
		ROUND_RINGS.emit(ps.last(), buffers.getBuffer(StructureKit.TYPE), light);
		ps.popPose();
		buffers.endBatch(StructureKit.TYPE);
		ROUND_GLOW.emit(ps.last(), buffers.getBuffer(StructureKit.GLOW_TYPE), light, 0xFFFFFFFF);
		buffers.endBatch(StructureKit.GLOW_TYPE);
	}

	/** This instant's arcs between the coils: jagged, re-struck a few times a second. */
	private static BoxMesh arcs(float time, float charge) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		RandomSource r = RandomSource.create((long) (time * 0.5F) * 7919L);
		int n = 1 + Math.round(charge * 4.0F);
		int lit = Math.max(1, Math.round(charge * SunTimeline.RINGS));
		for (int k = 0; k < n; k++) {
			int i = r.nextInt(lit);
			float y0 = SunTimeline.RING_BASE + i * SunTimeline.RING_STEP + 0.8F;
			float a0 = r.nextFloat() * Mth.TWO_PI;
			Vector3f from = new Vector3f(Mth.cos(a0) * 3.5F, y0, Mth.sin(a0) * 3.5F);
			Vector3f to;
			if (r.nextBoolean() && i + 1 < SunTimeline.RINGS) {
				float a1 = a0 + (r.nextFloat() - 0.5F) * 1.2F;
				to = new Vector3f(Mth.cos(a1) * 3.5F, y0 + SunTimeline.RING_STEP, Mth.sin(a1) * 3.5F);
			} else {
				// to the nearest leg of the lattice
				float lx = Mth.cos(a0) >= 0.0F ? LEG : -LEG;
				float lz = Mth.sin(a0) >= 0.0F ? LEG : -LEG;
				to = new Vector3f(lx, y0 + (r.nextFloat() - 0.5F) * 2.0F, lz);
			}
			Vector3f prev = from;
			int segs = 6;
			for (int s = 1; s <= segs; s++) {
				Vector3f p = new Vector3f(from).lerp(to, s / (float) segs);
				if (s < segs) {
					p.add((r.nextFloat() - 0.5F) * 0.7F, (r.nextFloat() - 0.5F) * 0.7F, (r.nextFloat() - 0.5F) * 0.7F);
				}
				b.beam(prev, p, 0.09F, 0.09F, BEAM);
				prev = p;
			}
		}
		return b.build();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 512;
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	// ------------------------------------------------------------------ the fixed structure

	private static BoxMesh buildSite() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// ---- the apron, with expansion joints and a painted ring round the plinth
		b.box(-24.0F, 0.0F, -24.0F, 24.0F, 0.4F, 24.0F, CONCRETE);
		for (float g = -16.0F; g <= 16.0F; g += 8.0F) {
			b.box(-24.0F, 0.4F, g - 0.04F, 24.0F, 0.41F, g + 0.04F, CONCRETE_DARK);
			b.box(g - 0.04F, 0.4F, -24.0F, g + 0.04F, 0.41F, 24.0F, CONCRETE_DARK);
		}
		b.box(-PLINTH_HALF - 1.2F, 0.4F, -PLINTH_HALF - 1.2F, PLINTH_HALF + 1.2F, 0.42F, PLINTH_HALF + 1.2F, YELLOW);
		// ---- the plinth: battered walls, a shaft through it lined with steel, the doors' running rails, hazard paint
		b.hexa(CONCRETE, v(-PLINTH_HALF - 0.8F, 0.0F, -PLINTH_HALF - 0.8F), v(PLINTH_HALF + 0.8F, 0.0F, -PLINTH_HALF - 0.8F),
			v(PLINTH_HALF + 0.8F, 0.0F, PLINTH_HALF + 0.8F), v(-PLINTH_HALF - 0.8F, 0.0F, PLINTH_HALF + 0.8F),
			v(-PLINTH_HALF, PLINTH, -PLINTH_HALF), v(PLINTH_HALF, PLINTH, -PLINTH_HALF), v(PLINTH_HALF, PLINTH, PLINTH_HALF), v(-PLINTH_HALF, PLINTH, PLINTH_HALF));
		float s = SHAFT_HALF;
		b.box(-s - 0.2F, PLINTH - 0.02F, -s - 0.2F, s + 0.2F, PLINTH + 0.02F, s + 0.2F, SHAFT); // the shaft mouth
		b.box(-s, 0.4F, -s, s, 0.6F, s, SOOT); // its floor, blackened
		for (int side : new int[] {-1, 1}) {
			b.box(side > 0 ? s : -s - 0.15F, 0.6F, -s, side > 0 ? s + 0.15F : -s, PLINTH, s, STEEL); // lining
			b.box(-s, 0.6F, side > 0 ? s : -s - 0.15F, s, PLINTH, side > 0 ? s + 0.15F : -s, STEEL);
			b.box(-PLINTH_HALF + 0.3F, PLINTH, side * (s + 0.25F) - 0.12F, PLINTH_HALF - 0.3F, PLINTH + 0.18F, side * (s + 0.25F) + 0.12F, GUNMETAL); // rails
		}
		for (float x = -PLINTH_HALF + 0.2F; x < PLINTH_HALF; x += 1.2F) {
			if (Math.abs(x + 0.3F) > s + 0.6F) {
				b.box(x, PLINTH, -PLINTH_HALF + 0.1F, x + 0.6F, PLINTH + 0.01F, -PLINTH_HALF + 0.6F, HAZARD);
				b.box(x, PLINTH, PLINTH_HALF - 0.6F, x + 0.6F, PLINTH + 0.01F, PLINTH_HALF - 0.1F, HAZARD);
			}
		}
		// guide rails of the lift inside the shaft
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			b.box(c[0] * (s - 0.25F) - 0.1F, 0.6F, c[1] * (s - 0.25F) - 0.1F, c[0] * (s - 0.25F) + 0.1F, PLINTH + 7.0F, c[1] * (s - 0.25F) + 0.1F, STEEL);
		}
		// stairs up the plinth's front and a railing round its top
		for (int i = 0; i < 10; i++) {
			float y = i * 0.5F;
			b.box(-1.5F, 0.0F, PLINTH_HALF + 0.8F + (9 - i) * 0.55F - 0.55F, 1.5F, y + 0.5F, PLINTH_HALF + 0.8F + (9 - i) * 0.55F, CONCRETE_DARK);
		}
		for (float[] e : new float[][] {{-PLINTH_HALF, -PLINTH_HALF, PLINTH_HALF, -PLINTH_HALF}, {PLINTH_HALF, -PLINTH_HALF, PLINTH_HALF, PLINTH_HALF},
			{-PLINTH_HALF, -PLINTH_HALF, -PLINTH_HALF, PLINTH_HALF}}) {
			b.beam(v(e[0], PLINTH + 1.1F, e[1]), v(e[2], PLINTH + 1.1F, e[3]), 0.06F, 0.06F, YELLOW);
			b.beam(v(e[0], PLINTH + 0.55F, e[1]), v(e[2], PLINTH + 0.55F, e[3]), 0.04F, 0.04F, YELLOW);
		}
		// ---- the accelerator: the barrel, its breech and the fourteen coils
		Vector3f up = v(0, 1, 0);
		b.revolve(GUNMETAL, v(0, PLINTH + 0.6F, 0), up, new float[][] {{0.0F, BARREL_R + 0.5F}, {0.8F, BARREL_R + 0.5F}, {1.0F, BARREL_R + 0.15F},
			{SunTimeline.MUZZLE - PLINTH - 2.0F, BARREL_R + 0.1F}, {SunTimeline.MUZZLE - PLINTH - 0.6F, BARREL_R + 0.1F}}, 24);
		for (int i = 0; i < 6; i++) {
			// the four rails the round rides up, seen through the slots
			float a = i * Mth.TWO_PI / 6.0F;
			b.box(Mth.cos(a) * (BARREL_R + 0.12F) - 0.09F, PLINTH + 1.6F, Mth.sin(a) * (BARREL_R + 0.12F) - 0.09F,
				Mth.cos(a) * (BARREL_R + 0.12F) + 0.09F, SunTimeline.MUZZLE - 1.4F, Mth.sin(a) * (BARREL_R + 0.12F) + 0.09F, STEEL);
		}
		// the breech: a heavy collar with hydraulic clamps over the shaft
		b.revolve(STEEL, v(0, PLINTH + 0.1F, 0), up, new float[][] {{0.0F, 2.6F}, {0.5F, 2.6F}, {0.7F, 2.1F}, {1.4F, 2.1F}}, 24);
		for (int i = 0; i < 8; i++) {
			float a = i * Mth.TWO_PI / 8.0F + 0.2F;
			b.beam(v(Mth.cos(a) * 2.2F, PLINTH + 1.3F, Mth.sin(a) * 2.2F), v(Mth.cos(a) * 3.0F, PLINTH + 0.2F, Mth.sin(a) * 3.0F), 0.22F, 0.22F, GUNMETAL);
		}
		for (int i = 0; i < SunTimeline.RINGS; i++) {
			float y = SunTimeline.RING_BASE + i * SunTimeline.RING_STEP;
			// the coil: copper windings in a steel casing, cooling jacket ribs, the bus bars feeding it
			b.revolve(STEEL, v(0, y, 0), up, new float[][] {{0.0F, 1.6F}, {0.0F, 3.7F}, {0.25F, 3.85F}, {1.55F, 3.85F}, {1.8F, 3.7F}, {1.8F, 1.6F}}, 28);
			b.revolve(BRASS, v(0, y + 0.3F, 0), up, new float[][] {{0.0F, 3.9F}, {1.2F, 3.9F}}, 28);
			for (int k = 0; k < 4; k++) {
				float yy = y + 0.35F + k * 0.28F;
				b.revolve(RUST, v(0, yy, 0), up, new float[][] {{0.0F, 3.93F}, {0.06F, 3.93F}}, 28);
			}
			for (int k = 0; k < 12; k++) {
				float a = k * Mth.TWO_PI / 12.0F;
				b.box(Mth.cos(a) * 3.85F - 0.08F, y + 0.1F, Mth.sin(a) * 3.85F - 0.08F, Mth.cos(a) * 3.85F + 0.08F, y + 1.7F, Mth.sin(a) * 3.85F + 0.08F, GUNMETAL);
			}
			// the bus bars running down the tower's back to the feeders
			b.box(-0.35F, y + 0.4F, -4.15F, 0.35F, y + 1.2F, -3.8F, BRASS);
			b.box(-0.15F, y - SunTimeline.RING_STEP + 1.8F, -4.3F, 0.15F, y + 0.4F, -4.1F, BRASS);
			// struts from the casing to the legs
			for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
				b.beam(v(c[0] * 2.6F, y + 0.9F, c[1] * 2.6F), v(c[0] * LEG, y + 0.9F, c[1] * LEG), 0.22F, 0.3F, GUNMETAL);
			}
		}
		// the lattice holding it all up
		StructureKit.lattice(b, 0.0F, 0.0F, LEG, LEG * 0.82F, PLINTH, SunTimeline.MUZZLE - 2.0F, 15, 0.42F, STEEL, STEEL, GUNMETAL);
		// service platforms every third bay, with ladders
		for (int k = 1; k <= 4; k++) {
			float y = PLINTH + k * 10.4F;
			float h = LEG * (1.0F - 0.18F * (y - PLINTH) / (SunTimeline.MUZZLE - 2.0F - PLINTH)) + 0.6F;
			b.box(-h, y, -h, h, y + 0.12F, -h + 1.0F, GRATING);
			b.box(-h, y, h - 1.0F, h, y + 0.12F, h, GRATING);
			b.box(-h, y + 1.0F, -h - 0.04F, h, y + 1.06F, -h + 0.04F, YELLOW);
			b.box(-h, y + 1.0F, h - 0.04F, h, y + 1.06F, h + 0.04F, YELLOW);
		}
		b.box(LEG + 0.3F, PLINTH, -0.35F, LEG + 0.38F, SunTimeline.MUZZLE - 2.0F, -0.3F, STEEL);
		b.box(LEG + 0.3F, PLINTH, 0.3F, LEG + 0.38F, SunTimeline.MUZZLE - 2.0F, 0.35F, STEEL);
		for (float y = PLINTH + 0.3F; y < SunTimeline.MUZZLE - 2.0F; y += 0.4F) {
			b.box(LEG + 0.3F, y, -0.32F, LEG + 0.36F, y + 0.04F, 0.32F, STEEL);
		}
		// ---- the muzzle crown: a flared ring, four blast deflectors, lightning rods
		float m = SunTimeline.MUZZLE;
		b.revolve(GUNMETAL, v(0, m - 2.2F, 0), up, new float[][] {{0.0F, 4.6F}, {0.6F, 4.6F}, {0.8F, 2.6F}, {2.0F, 2.2F}, {2.3F, 2.6F}}, 28);
		b.revolve(SOOT, v(0, m - 0.05F, 0), up, new float[][] {{0.0F, 2.6F}, {0.1F, BARREL_R + 0.1F}}, 28);
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI + Mth.PI / 4.0F;
			float c = Mth.cos(a);
			float sn = Mth.sin(a);
			b.hexa(STEEL, v(c * 2.8F - sn * 0.9F, m - 1.6F, sn * 2.8F + c * 0.9F), v(c * 2.8F + sn * 0.9F, m - 1.6F, sn * 2.8F - c * 0.9F),
				v(c * 3.0F + sn * 0.9F, m - 1.6F, sn * 3.0F - c * 0.9F), v(c * 3.0F - sn * 0.9F, m - 1.6F, sn * 3.0F + c * 0.9F),
				v(c * 3.6F - sn * 0.7F, m + 1.2F, sn * 3.6F + c * 0.7F), v(c * 3.6F + sn * 0.7F, m + 1.2F, sn * 3.6F - c * 0.7F),
				v(c * 3.8F + sn * 0.7F, m + 1.2F, sn * 3.8F - c * 0.7F), v(c * 3.8F - sn * 0.7F, m + 1.2F, sn * 3.8F + c * 0.7F));
			b.beam(v(c * 4.0F, m - 2.0F, sn * 4.0F), v(c * 4.2F, m + 3.5F, sn * 4.2F), 0.07F, 0.07F, STEEL);
		}
		// ---- the feeders: fat cables from the capacitor banks over the apron into the plinth
		for (int k = 0; k < 3; k++) {
			float z = -9.4F - k * 0.5F;
			b.beam(v(-8.0F, 0.75F, z), v(-PLINTH_HALF - 0.6F, 0.75F, z), 0.3F, 0.3F, CABLE);
			b.beam(v(-PLINTH_HALF - 0.6F, 0.75F, z), v(-PLINTH_HALF + 0.2F, 2.5F, z + 2.0F), 0.3F, 0.3F, CABLE);
		}
		// ---- capacitor banks: two rows of cabinets on plinths, insulator stacks and bus bars on top
		for (int row = 0; row < 2; row++) {
			float z0 = -20.0F + row * 5.5F;
			for (int i = 0; i < 5; i++) {
				float x0 = -22.0F + i * 3.0F;
				b.box(x0, 0.4F, z0, x0 + 2.4F, 0.7F, z0 + 3.2F, CONCRETE_DARK);
				b.box(x0 + 0.1F, 0.7F, z0 + 0.1F, x0 + 2.3F, 3.6F, z0 + 3.1F, PANEL);
				b.box(x0 + 0.05F, 3.6F, z0 + 0.05F, x0 + 2.35F, 3.75F, z0 + 3.15F, STEEL);
				b.box(x0 + 0.3F, 1.0F, z0 + 3.1F, x0 + 2.1F, 3.3F, z0 + 3.14F, VENT);
				for (float zz : new float[] {z0 + 0.8F, z0 + 2.4F}) {
					b.revolve(WHITE, v(x0 + 1.2F, 3.75F, zz), up, new float[][] {{0.0F, 0.22F}, {0.15F, 0.32F}, {0.3F, 0.18F}, {0.45F, 0.3F}, {0.6F, 0.17F},
						{0.75F, 0.28F}, {0.95F, 0.12F}}, 10);
				}
				b.box(x0 + 1.1F, 4.65F, z0 + 0.7F, x0 + 1.3F, 4.75F, z0 + 2.5F, BRASS);
			}
			b.box(-22.0F, 4.7F, z0 + 1.5F, -7.6F, 4.85F, z0 + 1.7F, BRASS);
		}
		// ---- the cooling towers: hyperbolic shells on their columns, with the hot-water pipes
		for (float[] tw : TOWERS) {
			Vector3f base = v(tw[0], 0.4F, tw[1]);
			for (int i = 0; i < 16; i++) {
				float a = i * Mth.TWO_PI / 16.0F;
				b.beam(v(tw[0] + Mth.cos(a) * 5.6F, 0.4F, tw[1] + Mth.sin(a) * 5.6F), v(tw[0] + Mth.cos(a) * 5.4F, 2.2F, tw[1] + Mth.sin(a) * 5.4F), 0.3F, 0.3F, CONCRETE);
			}
			float[][] prof = new float[9][2];
			for (int k = 0; k < 9; k++) {
				float y = 2.2F + (TOWER_H - 2.2F) * k / 8.0F;
				float u = (y - 2.2F - (TOWER_H - 2.2F) * 0.72F) / (TOWER_H * 0.55F);
				prof[k][0] = y - 0.4F;
				prof[k][1] = 3.2F * (float) Math.sqrt(1.0F + 3.0F * u * u) - (k == 0 ? -0.1F : 0.0F);
			}
			b.revolve(CONCRETE, base, up, prof, 28);
			b.revolve(SOOT, v(tw[0], TOWER_H, tw[1]), up, new float[][] {{-0.6F, prof[8][1] * 0.86F}, {0.0F, prof[8][1] * 0.86F}}, 28);
			b.revolve(STEEL, v(tw[0], TOWER_H - 0.4F, tw[1]), up, new float[][] {{0.0F, prof[8][1] + 0.05F}, {0.25F, prof[8][1] + 0.05F}}, 28);
			b.box(tw[0] - 0.5F, 0.4F, tw[1] - 6.5F, tw[0] + 0.5F, 1.4F, tw[1] - 5.2F, GUNMETAL);
			b.beam(v(tw[0], 0.9F, tw[1] - 6.0F), v(Math.signum(tw[0]) * (PLINTH_HALF + 0.8F), 0.9F, 4.0F), 0.6F, 0.6F, STEEL);
		}
		// ---- the control bunker: half-buried, slit windows facing the tower, antennas
		b.hexa(CONCRETE, v(7.0F, 0.0F, -23.0F), v(21.0F, 0.0F, -23.0F), v(21.0F, 0.0F, -11.0F), v(7.0F, 0.0F, -11.0F),
			v(8.2F, 4.0F, -21.8F), v(19.8F, 4.0F, -21.8F), v(19.8F, 4.0F, -12.2F), v(8.2F, 4.0F, -12.2F));
		b.box(9.0F, 1.9F, -11.6F, 18.0F, 2.5F, -11.4F, GLASS);
		b.box(8.8F, 0.4F, -11.2F, 11.0F, 2.6F, -10.2F, CONCRETE_DARK);
		b.box(9.2F, 0.4F, -10.25F, 10.6F, 2.3F, -10.15F, DOOR);
		b.box(12.0F, 4.0F, -20.0F, 14.0F, 5.0F, -18.0F, VENT);
		b.beam(v(17.0F, 4.0F, -19.0F), v(17.0F, 11.0F, -19.0F), 0.1F, 0.1F, STEEL);
		b.revolve(WHITE, v(15.0F, 4.6F, -14.5F), v(0.0F, 0.7F, 0.7F), new float[][] {{0.0F, 0.05F}, {0.2F, 0.6F}, {0.35F, 0.9F}}, 14);
		b.beam(v(15.0F, 4.0F, -14.5F), v(15.0F, 4.6F, -14.5F), 0.12F, 0.12F, STEEL);
		// ---- the crane's mast, standing on its own foundation
		b.box(CRANE_X - 1.6F, 0.0F, CRANE_Z - 1.6F, CRANE_X + 1.6F, 1.2F, CRANE_Z + 1.6F, CONCRETE_DARK);
		StructureKit.lattice(b, CRANE_X, CRANE_Z, 0.9F, 0.9F, 1.2F, CRANE_H - 0.6F, 20, 0.18F, YELLOW, YELLOW, YELLOW);
		b.revolve(GUNMETAL, v(CRANE_X, CRANE_H - 0.6F, CRANE_Z), up, new float[][] {{0.0F, 1.3F}, {0.6F, 1.3F}}, 16); // slewing ring
		// ---- floodlight masts at the corners
		for (float[] c : new float[][] {{-23.0F, -23.0F}, {23.0F, -23.0F}, {23.0F, 23.0F}, {-23.0F, 23.0F}}) {
			StructureKit.floodlight(b, c[0], c[1], 9.0F, -c[0], -c[1]);
		}
		// ---- the fence round the apron
		for (float k = -24.0F; k <= 24.01F; k += 2.0F) {
			for (float[] p : new float[][] {{k, -24.0F}, {k, 24.0F}, {-24.0F, k}, {24.0F, k}}) {
				b.box(p[0] - 0.05F, 0.0F, p[1] - 0.05F, p[0] + 0.05F, 2.2F, p[1] + 0.05F, STEEL);
			}
		}
		for (float y : new float[] {0.6F, 1.4F, 2.2F}) {
			b.box(-24.0F, y - 0.02F, -24.02F, 24.0F, y + 0.02F, -23.98F, STEEL);
			b.box(-24.0F, y - 0.02F, 23.98F, 24.0F, y + 0.02F, 24.02F, STEEL);
			b.box(-24.02F, y - 0.02F, -24.0F, -23.98F, y + 0.02F, 24.0F, STEEL);
			b.box(23.98F, y - 0.02F, -24.0F, 24.02F, y + 0.02F, 24.0F, STEEL);
		}
		// warning signs on the plinth
		b.box(-1.0F, 2.6F, PLINTH_HALF + 0.42F, 1.0F, 3.8F, PLINTH_HALF + 0.46F, RED);
		b.box(-0.8F, 2.75F, PLINTH_HALF + 0.46F, 0.8F, 3.65F, PLINTH_HALF + 0.48F, WHITE);
		return b.build();
	}

	private static BoxMesh buildSiteGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] c : new float[][] {{-23.0F, -23.0F}, {23.0F, -23.0F}, {23.0F, 23.0F}, {-23.0F, 23.0F}}) {
			StructureKit.floodlightGlow(b, c[0], c[1], 9.0F, -c[0], -c[1]);
		}
		b.box(9.05F, 1.95F, -11.38F, 17.95F, 2.45F, -11.36F, LAMP); // the bunker's lit slits
		return b.build();
	}

	private static BoxMesh buildAviation() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float m = SunTimeline.MUZZLE;
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			float h = LEG * 0.82F;
			b.box(c[0] * h - 0.25F, m - 1.9F, c[1] * h - 0.25F, c[0] * h + 0.25F, m - 1.4F, c[1] * h + 0.25F, RED_LAMP);
			b.box(c[0] * LEG * 0.9F - 0.2F, PLINTH + 26.0F, c[1] * LEG * 0.9F - 0.2F, c[0] * LEG * 0.9F + 0.2F, PLINTH + 26.4F, c[1] * LEG * 0.9F + 0.2F, RED_LAMP);
		}
		for (float[] tw : TOWERS) {
			b.box(tw[0] - 0.25F, TOWER_H, tw[1] - 4.3F, tw[0] + 0.25F, TOWER_H + 0.4F, tw[1] - 3.9F, RED_LAMP);
		}
		return b.build();
	}

	/** One door leaf, closed over the +X half of the shaft (the other is turned round). */
	private static BoxMesh buildDoor() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float s = SHAFT_HALF + 0.3F;
		b.box(0.0F, PLINTH, -s, s, PLINTH + 0.7F, s, DOOR);
		b.box(0.0F, PLINTH + 0.7F, -s, 0.12F, PLINTH + 0.78F, s, BLACK); // the seal
		for (float z = -s + 0.4F; z < s; z += 1.0F) {
			b.box(0.4F, PLINTH + 0.7F, z, s - 0.2F, PLINTH + 0.76F, z + 0.25F, STEEL); // stiffeners
		}
		for (float z = -s; z < s - 0.3F; z += 0.8F) {
			b.box(0.15F, PLINTH + 0.7F, z, 0.55F, PLINTH + 0.71F, z + 0.4F, HAZARD);
		}
		b.box(s - 0.4F, PLINTH - 0.1F, -s, s, PLINTH, s, GUNMETAL); // the rollers' carriage
		return b.build();
	}

	/** The crane's slewing part, about its mast's top: cab, jib towards -X over the tower, counter-jib, hook. */
	private static BoxMesh buildCraneTop() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-1.0F, 0.0F, -1.0F, 1.0F, 0.6F, 1.0F, YELLOW);
		b.box(0.6F, -1.8F, 0.9F, 2.2F, 0.0F, 2.3F, SHELTER); // the cab
		b.box(0.65F, -1.4F, 2.28F, 2.15F, -0.5F, 2.32F, GLASS);
		// the tower head and its pendants
		b.beam(v(0, 0.6F, 0), v(0, 3.0F, 0), 0.5F, 0.5F, YELLOW);
		b.beam(v(0, 3.0F, 0), v(-JIB * 0.95F, 0.95F, 0), 0.05F, 0.05F, STEEL);
		b.beam(v(0, 3.0F, 0), v(7.5F, 0.95F, 0), 0.05F, 0.05F, STEEL);
		// the jib: a triangular truss
		for (float k = 0.0F; k < JIB; k += 1.5F) {
			float x0 = -k;
			float x1 = -k - 1.5F;
			b.beam(v(x0, 0.0F, -0.45F), v(x1, 0.0F, -0.45F), 0.12F, 0.12F, YELLOW);
			b.beam(v(x0, 0.0F, 0.45F), v(x1, 0.0F, 0.45F), 0.12F, 0.12F, YELLOW);
			b.beam(v(x0, 0.9F, 0.0F), v(x1, 0.9F, 0.0F), 0.12F, 0.12F, YELLOW);
			b.beam(v(x0, 0.0F, -0.45F), v(x1, 0.9F, 0.0F), 0.06F, 0.06F, YELLOW);
			b.beam(v(x0, 0.0F, 0.45F), v(x1, 0.9F, 0.0F), 0.06F, 0.06F, YELLOW);
		}
		// the trolley and the hook hanging over the tower top
		b.box(-JIB * 0.72F - 0.5F, -0.4F, -0.5F, -JIB * 0.72F + 0.5F, 0.0F, 0.5F, GUNMETAL);
		b.beam(v(-JIB * 0.72F, -0.4F, 0), v(-JIB * 0.72F, -5.0F, 0), 0.04F, 0.04F, CABLE);
		b.box(-JIB * 0.72F - 0.3F, -5.6F, -0.2F, -JIB * 0.72F + 0.3F, -5.0F, 0.2F, YELLOW);
		// the counter-jib with its concrete counterweights
		b.box(0.6F, 0.0F, -0.5F, 7.5F, 0.3F, 0.5F, YELLOW);
		b.box(5.2F, -1.6F, -0.7F, 7.4F, 0.0F, 0.7F, CONCRETE);
		b.box(1.8F, 0.3F, -0.4F, 3.0F, 1.1F, 0.4F, GUNMETAL); // the hoist winch
		return b.build();
	}

	/** The lift's cradle: a platform with four guide shoes and the round's clamps. */
	private static BoxMesh buildCradle() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float s = SHAFT_HALF - 0.4F;
		b.box(-s, 0.0F, -s, s, 0.35F, s, GUNMETAL);
		b.box(-s + 0.1F, 0.35F, -s + 0.1F, s - 0.1F, 0.38F, s - 0.1F, GRATING);
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			b.box(c[0] * s - 0.25F, 0.0F, c[1] * s - 0.25F, c[0] * s + 0.25F, 1.2F, c[1] * s + 0.25F, STEEL);
			b.beam(v(c[0] * 0.8F, 0.35F, c[1] * 0.8F), v(c[0] * 0.66F, 2.2F, c[1] * 0.66F), 0.18F, 0.18F, YELLOW);
		}
		b.box(-1.1F, 0.35F, -1.1F, 1.1F, 0.6F, 1.1F, STEEL);
		return b.build();
	}

	/** The Sun Destroyer round along +Y, centred: ablative-armoured body, tungsten tip, base plate. */
	private static BoxMesh buildRound() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 1, 0);
		b.revolve(ABLATIVE, v(0, -3.0F, 0), up, new float[][] {{0.0F, 0.0F}, {0.0F, 0.52F}, {0.2F, 0.62F}, {4.6F, 0.62F}, {5.2F, 0.5F}, {5.7F, 0.28F}, {6.0F, 0.0F}}, 20);
		b.revolve(TUNGSTEN, v(0, 2.7F, 0), up, new float[][] {{0.0F, 0.3F}, {0.32F, 0.06F}, {0.4F, 0.0F}}, 14);
		b.revolve(GUNMETAL, v(0, -3.05F, 0), up, new float[][] {{0.0F, 0.0F}, {0.0F, 0.5F}, {0.25F, 0.56F}}, 16);
		// panel seams and the slots the core's light shows through
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI + Mth.PI / 4.0F;
			b.box(Mth.cos(a) * 0.6F - 0.06F, -2.6F, Mth.sin(a) * 0.6F - 0.06F, Mth.cos(a) * 0.6F + 0.06F, 1.4F, Mth.sin(a) * 0.6F + 0.06F, BLACK);
		}
		b.revolve(BLACK, v(0, 1.55F, 0), up, new float[][] {{0.0F, 0.63F}, {0.05F, 0.63F}}, 20);
		return b.build();
	}

	/** Its three containment rings, which spin up while the coils charge and spin in flight. */
	private static BoxMesh buildRoundRings() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 1, 0);
		for (float y : new float[] {-2.3F, -1.0F, 0.3F}) {
			b.revolve(BRASS, v(0, y, 0), up, new float[][] {{0.0F, 0.74F}, {0.35F, 0.78F}, {0.7F, 0.74F}}, 20);
			for (int k = 0; k < 3; k++) {
				float a = k * Mth.TWO_PI / 3.0F;
				b.box(Mth.cos(a) * 0.76F - 0.07F, y + 0.1F, Mth.sin(a) * 0.76F - 0.07F, Mth.cos(a) * 0.76F + 0.07F, y + 0.6F, Mth.sin(a) * 0.76F + 0.07F, GUNMETAL);
			}
		}
		return b.build();
	}

	/** The core's light through the slots between the armour panels. */
	private static BoxMesh buildRoundGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI;
			b.box(Mth.cos(a) * 0.6F - 0.07F, -2.4F, Mth.sin(a) * 0.6F - 0.07F, Mth.cos(a) * 0.6F + 0.07F, 1.2F, Mth.sin(a) * 0.6F + 0.07F, HOT);
		}
		b.revolve(HOT, v(0, -3.12F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.32F}}, 12);
		return b.build();
	}

	/** Each coil's glowing face, between its casing's lips. */
	private static BoxMesh[] buildRingGlow() {
		BoxMesh[] out = new BoxMesh[SunTimeline.RINGS];
		for (int i = 0; i < SunTimeline.RINGS; i++) {
			float y = SunTimeline.RING_BASE + i * SunTimeline.RING_STEP;
			out[i] = new BoxMesh.Builder()
				.revolve(BEAM, v(0, y + 0.22F, 0), v(0, 1, 0), new float[][] {{0.0F, 3.96F}, {1.36F, 3.96F}}, 28)
				.revolve(BEAM, v(0, y + 1.82F, 0), v(0, 1, 0), new float[][] {{0.0F, 1.7F}, {0.05F, 1.7F}}, 20)
				.build();
		}
		return out;
	}
}
