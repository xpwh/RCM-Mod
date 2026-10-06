package de.rcm.ballistic.client;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.MissileClientTicker;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.render.BombletRenderer;
import de.rcm.ballistic.client.render.MissileRenderer;
import de.rcm.ballistic.client.render.MobileLauncherRenderer;
import de.rcm.ballistic.client.screen.RadarScreen;
import de.rcm.ballistic.client.render.ProjectileRenderer;
import de.rcm.ballistic.client.screen.TargetScreen;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.network.ModNetworking.DetonationPayload;
import de.rcm.ballistic.network.ModNetworking.RadarDataPayload;
import de.rcm.ballistic.network.ModNetworking.SetTargetPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BallisticMissilesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		for (MissileType type : MissileType.values()) {
			EntityRendererRegistry.register(ModRegistry.missileEntity(type), MissileRenderer::new);
		}
		EntityRendererRegistry.register(ModRegistry.BOMBLET, BombletRenderer::new);
		EntityRendererRegistry.register(ModRegistry.REENTRY_VEHICLE, ctx -> new ProjectileRenderer<>(ctx, false));
		EntityRendererRegistry.register(ModRegistry.INTERCEPTOR, ctx -> new ProjectileRenderer<>(ctx, true));
		EntityRendererRegistry.register(ModRegistry.MOBILE_LAUNCHER, MobileLauncherRenderer::new);

		ParticleFactoryRegistry.getInstance().register(ModRegistry.SMOKE, CloudParticle.SmokeProvider::new);
		ParticleFactoryRegistry.getInstance().register(ModRegistry.FIRE, CloudParticle.FireProvider::new);

		ClientPlayNetworking.registerGlobalReceiver(DetonationPayload.TYPE, (payload, context) ->
			ClientEffects.detonation(payload.warhead(), new Vec3(payload.x(), payload.y(), payload.z()))
		);
		ClientPlayNetworking.registerGlobalReceiver(RadarDataPayload.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			if (mc.screen instanceof RadarScreen scope && scope.radarPos().equals(payload.radar())) {
				scope.update(payload);
			} else if (payload.open()) {
				mc.setScreen(new RadarScreen(payload));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientEffects.clear());
		ClientTickEvents.END_CLIENT_TICK.register(ClientEffects::tick);
		ClientTickEvents.END_CLIENT_TICK.register(BallisticMissilesClient::debrisTrails);

		HudElementRegistry.addLast(BallisticMissiles.id("flash"), (graphics, tickCounter) -> {
			float alpha = ClientEffects.flashAlpha(tickCounter.getGameTimeDeltaPartialTick(false));
			if (alpha > 0.003F) {
				int a = (int) (Math.min(1.0F, alpha) * 255.0F);
				graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), a << 24 | ClientEffects.flashColor());
			}
		});

		ClientHooks.missileClientTick = MissileClientTicker::tick;
		ClientHooks.projectileClientTick = MissileClientTicker::projectileTick;
		ClientHooks.openTargetScreen = hand -> Minecraft.getInstance().setScreen(new TargetScreen(hand));
		ClientHooks.designateLookedAtBlock = BallisticMissilesClient::designate;
	}

	/**
	 * Locks the block in the crosshair. Past the render distance the line of sight is extended until it
	 * meets sea level, so far-away targets can be designated by just looking at the horizon.
	 */
	private static void designate(InteractionHand hand) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		double range = mc.options.getEffectiveRenderDistance() * 16.0;
		HitResult hit = mc.player.pick(range, 1.0F, false);
		boolean offhand = hand == InteractionHand.OFF_HAND;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			ClientPlayNetworking.send(new SetTargetPayload(offhand, blockHit.getBlockPos(), false));
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		double seaLevel = mc.level.getSeaLevel();
		if (look.y < -0.003 && eye.y > seaLevel) {
			double t = Math.min((eye.y - seaLevel) / -look.y, 30000.0);
			Vec3 p = eye.add(look.scale(t));
			ClientPlayNetworking.send(new SetTargetPayload(offhand, net.minecraft.core.BlockPos.containing(p.x, seaLevel, p.z), true));
			mc.player.displayClientMessage(Component.translatable("message.ballisticmissiles.long_range", (int) Math.hypot(p.x - eye.x, p.z - eye.z)).withStyle(ChatFormatting.GOLD), false);
		} else {
			mc.player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_block").withStyle(ChatFormatting.YELLOW), true);
		}
	}

	/** Dust trails behind blocks hurled away by explosions. */
	private static void debrisTrails(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			return;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof FallingBlockEntity block && block.getDeltaMovement().lengthSqr() > 0.36) {
				CloudParticle p = ClientEffects.cloud(false, e.getX(), e.getY() + 0.5, e.getZ(), 0, 0, 0);
				if (p != null) {
					p.configure(30 + (int) (ClientEffects.rand() * 30), 0.6F, 2.2F, 0x8A7C6A, 0x6A645C, 0.55F).physics(0.9F, 0.002F);
				}
			}
		}
	}
}
